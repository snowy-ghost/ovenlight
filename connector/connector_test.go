package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/png"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"tailscale.com/health"
	"tailscale.com/ipn/ipnstate"
)

func TestApplyIdentityStripsForgedHeaders(t *testing.T) {
	h := http.Header{}
	h.Set("Tailscale-User-Login", "mallory@example.com")
	h.Set("Tailscale-Headers-Info", "x")
	h.Set("Ovenlight-Role", "owner")
	h.Set("Ovenlight-Anything", "x")
	h.Set("X-Ovenlight-User", "mallory")
	h["tailscale_user_login"] = []string{"mallory@example.com"} // WSGI/CGI alias
	h["OVENLIGHT_ROLE"] = []string{"owner"}
	h.Set("Cookie", "session=1")
	h.Set("X-Tailscale-Unrelated", "kept") // not a reserved prefix

	applyIdentity(h, caller{Identity: Identity{Login: "alex@example.com", Name: "Alex"}, Role: RoleOwner})

	want := http.Header{
		"Tailscale-User-Login":  {"alex@example.com"},
		"Tailscale-User-Name":   {"Alex"},
		"Ovenlight-User":        {"Alex"},
		"Ovenlight-User-Id":     {"alex@example.com"},
		"Ovenlight-Role":        {"owner"},
		"Cookie":                {"session=1"},
		"X-Tailscale-Unrelated": {"kept"},
	}
	if !reflect.DeepEqual(h, want) {
		t.Fatalf("headers = %v\nwant %v", h, want)
	}
}

func TestApplyIdentityEncodesNonASCIIAndFallsBackToLogin(t *testing.T) {
	h := http.Header{}
	applyIdentity(h, caller{Identity: Identity{Login: "zoe@example.com", Name: "Zoë"}, Role: RoleOwner})
	if got := h.Get("Ovenlight-User"); got != "=?utf-8?q?Zo=C3=AB?=" {
		t.Errorf("Ovenlight-User = %q", got)
	}
	h = http.Header{}
	applyIdentity(h, caller{Identity: Identity{Login: "sam@example.com"}, Role: RoleOwner})
	if got := h.Get("Ovenlight-User"); got != "sam@example.com" {
		t.Errorf("Ovenlight-User without a display name = %q", got)
	}
}

func TestDecideRole(t *testing.T) {
	owner := "alex@example.com"
	cases := []struct {
		name   string
		caller Identity
		owner  string
		want   Role
	}{
		{"owner", Identity{Login: "alex@example.com"}, owner, RoleOwner},
		{"owner, different case", Identity{Login: "Alex@Example.com"}, owner, RoleOwner},
		{"another user", Identity{Login: "sam@example.com"}, owner, RoleDenied},
		{"tagged device", Identity{Login: "alex@example.com", Tagged: true, Tags: []string{"tag:server"}}, owner, RoleDenied},
		{"owner not known yet", Identity{Login: "alex@example.com"}, "", RoleDenied},
		{"no caller login", Identity{}, owner, RoleDenied},
		{"Unicode look-alike of the owner", Identity{Login: "\u212Aim@example.com"}, "kim@example.com", RoleDenied},
	}
	for _, c := range cases {
		if got := decideRole(c.caller, c.owner, nil, "coach"); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

func TestProxyRewriteSetsIdentityAndKeepsHost(t *testing.T) {
	n := newAppNode(App{Name: "Coach", Slug: "coach", Port: 4317}, t.TempDir(), devOptions{}, nil)
	in := httptest.NewRequest("GET", "https://coach.tail1.ts.net/cards?x=1", nil)
	in.Header.Set("Ovenlight-Role", "owner")
	in.Header.Set("X-Real-Ip", "10.0.0.1")
	in.Header.Set("Forwarded", "for=10.0.0.1")
	in.Header.Set("X-Forwarded-Prefix", "/evil")
	in.Header["X_Forwarded_Host"] = []string{"evil.example.com"} // WSGI/CGI alias
	in = in.WithContext(context.WithValue(in.Context(), callerKey{}, caller{Identity: Identity{Login: "d@example.com", Name: "D"}, Role: RoleOwner}))
	out := in.Clone(in.Context())
	n.rewrite(&httputil.ProxyRequest{In: in, Out: out})
	if out.URL.String() != "http://127.0.0.1:4317/cards?x=1" {
		t.Errorf("upstream URL = %s", out.URL)
	}
	if out.Host != "coach.tail1.ts.net" {
		t.Errorf("Host = %q, want the tailnet host", out.Host)
	}
	if out.Header.Get("Ovenlight-Role") != "owner" || out.Header.Get("Tailscale-User-Login") != "d@example.com" {
		t.Errorf("identity headers = %v", out.Header)
	}
	if out.Header.Get("X-Real-Ip") != "" || out.Header.Get("Forwarded") != "" ||
		out.Header.Get("X-Forwarded-Prefix") != "" || out.Header["X_Forwarded_Host"] != nil {
		t.Errorf("client-address headers passed through: %v", out.Header)
	}
	if out.Header.Get("X-Forwarded-Proto") != "https" {
		t.Errorf("X-Forwarded-Proto = %q", out.Header.Get("X-Forwarded-Proto"))
	}
}

func TestParsePageAndManifest(t *testing.T) {
	page := parsePage(strings.NewReader(`<html><head>
	  <meta name="theme-color" content="#eef2f4" media="(prefers-color-scheme: light)">
	  <link href="manifest.webmanifest" rel="manifest">
	  <link rel="icon" href="icon.svg" type="image/svg+xml">
	  <LINK REL="apple-touch-icon" HREF="/apple-touch-icon.png">
	</head></html>`))
	want := pageInfo{Manifest: "manifest.webmanifest", TouchIcon: "/apple-touch-icon.png", ThemeColor: "#eef2f4"}
	if page != want {
		t.Fatalf("page = %+v, want %+v", page, want)
	}

	base, _ := url.Parse("http://127.0.0.1:4317/")
	manifestURL, _ := base.Parse("app/manifest.webmanifest")
	m := webManifest{ThemeColor: "#0e6b66"}
	m.Icons = append(m.Icons,
		manifestIcon{"icon-192.png", "192x192", "image/png"},
		manifestIcon{"icon-512.png", "512x512", "image/png"},
		manifestIcon{"icon.svg", "any", "image/svg+xml"},
		manifestIcon{"huge.png", "2048x2048", "image/png"},
	)
	got := buildSiteManifest(App{Name: "Interview Coach", Slug: "interview-coach"}, base, page, manifestURL, &m)
	if got.Icon == nil || *got.Icon != "/app/icon-512.png" {
		t.Errorf("icon = %v, want /app/icon-512.png", got.Icon)
	}
	if got.ThemeColor == nil || *got.ThemeColor != "#0e6b66" {
		t.Errorf("theme = %v, want the manifest's", got.ThemeColor)
	}
	if got.Name != "Interview Coach" || got.Slug != "interview-coach" || got.Version != 1 {
		t.Errorf("manifest = %+v", got)
	}
}

// The icon closest to 512 px wins, the larger of two as close, whatever the order.
func TestBestIcon(t *testing.T) {
	icon := func(src, sizes string) manifestIcon { return manifestIcon{src, sizes, "image/png"} }
	cases := []struct {
		icons []manifestIcon
		want  string
	}{
		{[]manifestIcon{icon("1024.png", "1024x1024"), icon("512.png", "512x512")}, "512.png"},
		{[]manifestIcon{icon("1024.png", "1024x1024"), icon("600.png", "600x600")}, "600.png"},
		{[]manifestIcon{icon("256.png", "256x256"), icon("768.png", "768x768")}, "768.png"},
		{[]manifestIcon{icon("192.png", "192x192"), icon("2048.png", "2048x2048")}, "192.png"},
		{[]manifestIcon{icon("any.png", "any")}, "any.png"},
	}
	for _, c := range cases {
		if got := (webManifest{Icons: c.icons}).bestIcon(); got != c.want {
			t.Errorf("%v: %s, want %s", c.icons, got, c.want)
		}
	}
}

func TestSiteManifestWithoutWebManifest(t *testing.T) {
	base, _ := url.Parse("http://127.0.0.1:4317/")
	got := buildSiteManifest(App{Name: "Plain", Slug: "plain"}, base, pageInfo{}, nil, nil)
	if got.Icon != nil || got.ThemeColor != nil {
		t.Errorf("expected null icon and theme, got %+v", got)
	}
	// An icon on another origin can't be fetched through the app's node.
	got = buildSiteManifest(App{Name: "X", Slug: "x"}, base, pageInfo{TouchIcon: "https://cdn.example.com/i.png"}, nil, nil)
	if got.Icon != nil {
		t.Errorf("off-origin icon kept: %v", *got.Icon)
	}
}

func TestFetchSiteManifestFromUpstream(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			w.Write([]byte(`<link rel="manifest" href="/m.json">`))
		case "/m.json":
			w.Write([]byte(`{"theme_color":"#123456","icons":[{"src":"i.png","sizes":"180x180"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	up, _ := url.Parse(srv.URL)
	got, err := fetchSiteManifest(t.Context(), srv.Client(), up, App{Name: "T", Slug: "t"})
	if err != nil {
		t.Fatal(err)
	}
	if got.Icon == nil || *got.Icon != "/i.png" || *got.ThemeColor != "#123456" {
		t.Errorf("got %+v", got)
	}
}

func TestBindAddressParsing(t *testing.T) {
	found := parseListeners("p98494\ncnode\nf12\nn127.0.0.1:4317\nf13\nn[::1]:4317\np98495\ncnode\nf12\nn127.0.0.1:4317\n")
	if !reflect.DeepEqual(found, []listener{{98494, "node", "127.0.0.1:4317"}, {98494, "node", "[::1]:4317"}, {98495, "node", "127.0.0.1:4317"}}) {
		t.Fatalf("listeners = %v", found)
	}
	if addrs, exposed := bindAddrs(found); !reflect.DeepEqual(addrs, []string{"127.0.0.1:4317", "[::1]:4317"}) || exposed != nil {
		t.Errorf("loopback only: %v, exposed %v", addrs, exposed)
	}
	cases := map[string][]string{
		"p1\nf3\nn*:4317\n":                  {"*:4317"},
		"p1\nf3\nn0.0.0.0:4317\n":            {"0.0.0.0:4317"},
		"p1\nf3\nn[::]:4317\n":               {"[::]:4317"},
		"p1\nf3\nn192.168.1.20:4317\n":       {"192.168.1.20:4317"},
		"p1\nf3\nn127.0.0.1:4317\nn*:4317\n": {"*:4317"},
	}
	for in, want := range cases {
		if _, exposed := bindAddrs(parseListeners(in)); !reflect.DeepEqual(exposed, want) {
			t.Errorf("%q: exposed %v", in, exposed)
		}
	}
	addrs := func(ls []listener) []string { a, _ := bindAddrs(ls); return a }
	// The app is what a connection to 127.0.0.1 reaches: not AirPlay Receiver on every
	// address beside it, unless nothing listens on 127.0.0.1 (no such pids run here).
	const app, airplay = "p999999991\ncPython\nf3\nn127.0.0.1:5000\n", "p999999992\ncControlCenter\nf9\nn*:5000\nf10\nn*:5000\n"
	own, others := appListeners(parseListeners(app + airplay))
	if !reflect.DeepEqual(addrs(own), []string{"127.0.0.1:5000"}) || len(others) != 1 || others[0].name != "ControlCenter" {
		t.Errorf("beside AirPlay: own %v, others %v", own, others)
	}
	if own, others := appListeners(parseListeners(airplay)); own != nil || len(others) != 1 {
		t.Errorf("AirPlay alone: own %v, others %v", own, others)
	}
	// Another program on every address with nothing on 127.0.0.1 is what a connection reaches.
	const other = "p999999993\ncnode\nf3\nn*:5000\n"
	if own, others := appListeners(parseListeners(other + airplay)); !reflect.DeepEqual(addrs(own), []string{"*:5000"}) || len(others) != 1 || others[0].name != "ControlCenter" {
		t.Errorf("beside AirPlay on every address: own %v, others %v", own, others)
	}
}

func TestSleepCheck(t *testing.T) {
	pmset := func(sleep string) string {
		return "System-wide power settings:\nCurrently in use:\n standby              1\n Sleep On Power Button 1\n disksleep            10\n" +
			" sleep                " + sleep + "\n ttyskeepawake        1\n displaysleep         10\n"
	}
	for out, want := range map[string]struct{ status, message string }{
		pmset("0"): {statusOK, "doesn't sleep on its own"},
		pmset("0 (sleep prevented by caffeinate, caffeinate)"): {statusOK, "doesn't sleep on its own"},
		pmset("1 (sleep prevented by caffeinate, sharingd)"):   {statusWarn, "sleeps after 1 minute idle once nothing holds it off (sleep prevented by caffeinate, sharingd)"},
		pmset("10"): {statusWarn, "sleeps after 10 minutes idle"},
		pmset("60"): {statusWarn, "sleeps after 1 hour idle"},
		"Currently in use:\n displaysleep         10\n": {statusWarn, "couldn't find the sleep setting"},
	} {
		c := sleepCheck(out)
		wantFix := strings.Contains(want.message, "sleeps after")
		if c.Status != want.status || !strings.Contains(c.Message, want.message) || strings.Contains(c.Fix, "sudo pmset -c sleep 0") != wantFix {
			t.Errorf("%q: %+v", out, c)
		}
	}
}

func TestConfigRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "config.json")
	cfg, err := LoadConfig(path)
	if err != nil || len(cfg.Apps) != 0 {
		t.Fatalf("missing config: %v %v", cfg, err)
	}
	cfg.Upsert(App{Name: "Interview Coach", Slug: "interview-coach", Port: 4317})
	cfg.Upsert(App{Name: "Notes", Slug: "notes", Port: 5000})
	if replaced := cfg.Upsert(App{Name: "Interview Coach", Slug: "interview-coach", Port: 4318}); !replaced {
		t.Error("upsert of an existing slug should replace it")
	}
	if err := cfg.Save(path); err != nil {
		t.Fatal(err)
	}
	loaded, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	want := []App{{Name: "Interview Coach", Slug: "interview-coach", Port: 4318}, {Name: "Notes", Slug: "notes", Port: 5000}}
	if !reflect.DeepEqual(loaded.Apps, want) {
		t.Fatalf("apps = %v", loaded.Apps)
	}
	if !loaded.Remove("notes") || loaded.Remove("notes") {
		t.Error("remove should succeed once")
	}
}

// The apps' commands live beside the config, where a connector from before --run, which
// rewrites the config without them, doesn't reach. A command whose app the config no
// longer has is dropped on the next load, so publishing the slug again doesn't bring it
// back.
func TestCommandsLiveBesideTheConfig(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	coach := App{Name: "Coach", Slug: "coach", Port: 4317, Run: "npm start", Dir: dir}
	notes := App{Name: "Notes", Slug: "notes", Port: 4400}
	if err := (&Config{Apps: []App{coach, notes}}).Save(path); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(path); strings.Contains(string(data), "npm start") || strings.Contains(string(data), `"dir"`) {
		t.Errorf("the config holds the command:\n%s", data)
	}
	commandsFile := filepath.Join(dir, "commands.json")
	if err := jsonfile.CheckPrivate(commandsFile); err != nil {
		t.Fatalf("commands.json: %v", err)
	}
	// What a connector from before --run saves, with a field it doesn't know left in.
	os.WriteFile(path, []byte(`{"apps": [{"name": "Coach", "slug": "coach", "port": 4317}, {"name": "Notes", "slug": "notes", "port": 4400, "run": "make serve", "dir": "/srv"}]}`), 0o600)
	if cfg, err := LoadConfig(path); err != nil || !reflect.DeepEqual(cfg.Apps, []App{coach, notes}) {
		t.Errorf("after an older connector's save: %+v, %v", cfg, err)
	}
	// An older connector unpublishes coach, then publishes it again: the load between
	// drops its command, and leaves the config as it was.
	unpublished := `{"apps": [{"name": "Notes", "slug": "notes", "port": 4400}]}`
	os.WriteFile(path, []byte(unpublished), 0o600)
	if cfg, err := LoadConfig(path); err != nil || !reflect.DeepEqual(cfg.Apps, []App{notes}) {
		t.Errorf("after the unpublish: %+v, %v", cfg, err)
	}
	if data, _ := os.ReadFile(path); string(data) != unpublished {
		t.Errorf("the load rewrote the config:\n%s", data)
	}
	if _, err := os.Stat(commandsFile); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("commands.json with no app's command: %v", err)
	}
	os.WriteFile(path, []byte(`{"apps": [{"name": "Coach", "slug": "coach", "port": 4317}, {"name": "Notes", "slug": "notes", "port": 4400}]}`), 0o600)
	if cfg, err := LoadConfig(path); err != nil || cfg.Apps[0].Run != "" {
		t.Errorf("published again: %+v, %v", cfg, err)
	}
	// In a folder it can't write, the load still reads, leaving the cleanup for later.
	// Windows ignores directory modes.
	if runtime.GOOS != "windows" {
		os.WriteFile(commandsFile, []byte(`{"gone": {"run": "make serve", "dir": "/srv"}}`), 0o600)
		if err := os.Chmod(dir, 0o500); err != nil {
			t.Fatal(err)
		}
		defer os.Chmod(dir, 0o700)
		if cfg, err := LoadConfig(path); err != nil || len(cfg.Apps) != 2 || cfg.Apps[0].Run != "" {
			t.Errorf("in a read-only folder: %+v, %v", cfg, err)
		}
		if _, err := os.Stat(commandsFile); err != nil {
			t.Errorf("the read-only folder's commands.json: %v", err)
		}
	}
	// Another config gets its own.
	if got := commandsPath(filepath.Join("x", "test.json")); got != filepath.Join("x", "test.commands.json") {
		t.Errorf("commandsPath = %s", got)
	}
}

func TestSlugAndValidation(t *testing.T) {
	for in, want := range map[string]string{"Interview Coach": "interview-coach", "  Zoë's  App!! ": "zo-s-app", "API v2": "api-v2"} {
		if got := Slugify(in); got != want {
			t.Errorf("Slugify(%q) = %q, want %q", in, got, want)
		}
	}
	bad := []App{{Name: "X", Slug: "Bad_Slug", Port: 80}, {Name: "X", Slug: "-x", Port: 80}, {Name: "X", Slug: "x", Port: 0}, {Name: "", Slug: "x", Port: 80}}
	for _, app := range bad {
		if app.Validate() == nil {
			t.Errorf("%+v should be invalid", app)
		}
	}
	if err := (App{Name: "X", Slug: "x", Port: 80}).Validate(); err != nil {
		t.Error(err)
	}
}

func TestHumanDuration(t *testing.T) {
	for d, want := range map[time.Duration]string{
		inviteTTL: "24 hours", reviewInviteTTL: "7 days", tagWait: "90 seconds",
		time.Hour: "1 hour", 10 * time.Minute: "10 minutes", 48 * time.Hour: "2 days",
	} {
		if got := humanDuration(d); got != want {
			t.Errorf("humanDuration(%v) = %q, want %q", d, got, want)
		}
	}
}

// The access log keeps the path only, never the query (it can carry OAuth codes), and
// bounded, so a refused device can't grow the log as fast as it sends.
func TestAccessLogKeepsABoundedPath(t *testing.T) {
	if got := logPath("/a\nb"); got != "/ab" {
		t.Errorf("control characters kept: %q", got)
	}
	long := "/" + strings.Repeat("é", 200)
	if got := logPath(long); len(got) > maxLogPath+3 || !utf8.ValidString(got) || !strings.HasSuffix(got, "...") {
		t.Errorf("long path logged as %d bytes: %q", len(got), got)
	}

	d, _ := testDaemon(t)
	n := d.nodes["coach"]
	whoIsAs(n, kimDev) // a guest device that never claimed an invite
	var logs strings.Builder
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })
	rec := httptest.NewRecorder()
	n.ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net/callback"+strings.Repeat("x", 5000)+"?code=SECRET", nil))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("unclaimed device: %d", rec.Code)
	}
	if got := logs.String(); strings.Contains(got, "SECRET") || len(got) > 400 {
		t.Errorf("logged %d bytes: %s", len(got), got)
	}
}

// A screenshot is decoded whole before it is kept: a size that would decode to a huge
// image, or a valid header followed by anything else, is refused.
func TestValidateFeedbackDecodesTheWholeScreenshot(t *testing.T) {
	encode := func(w, h int) []byte {
		var buf bytes.Buffer
		if err := png.Encode(&buf, image.NewGray(image.Rect(0, 0, w, h))); err != nil {
			t.Fatal(err)
		}
		return buf.Bytes()
	}
	valid := encode(40, 30)
	cases := map[string][]byte{
		"too wide":         encode(maxScreenshotSide+1, 1),
		"too many pixels":  encode(4200, 4200),
		"header then junk": append(slices.Clone(valid[:33]), bytes.Repeat([]byte{0xAB}, 4096)...),
		"trailing data":    append(slices.Clone(valid), 0),
		"cut short":        valid[:len(valid)-20],
	}
	for name, img := range cases {
		if _, _, err := validateFeedback(feedbackRequest{Screenshot: base64.StdEncoding.EncodeToString(img)}); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	if _, img, err := validateFeedback(feedbackRequest{Screenshot: base64.StdEncoding.EncodeToString(valid)}); err != nil || !bytes.Equal(img, valid) {
		t.Errorf("valid screenshot: %v", err)
	}
}

// whoami to the owner, the admin API and the control socket say which connector build
// answers, whoami to a guest doesn't, and screenshots are never sniffed as anything but
// PNG.
func TestVersionAndScreenshotHeaders(t *testing.T) {
	if version() == "" {
		t.Fatal("no version")
	}
	d, _ := testDaemon(t)
	n := d.nodes["coach"]
	n.setOwner(d.cfg.Owner)
	whoIsAs(n, ownerID)
	rec := httptest.NewRecorder()
	n.ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net"+whoamiPath, nil))
	if rec.Code != 200 || rec.Header().Get(versionHeader) != version() {
		t.Errorf("whoami: %d, version %q", rec.Code, rec.Header().Get(versionHeader))
	}
	d.sh.st.Guests = []Guest{{DeviceID: samDev.DeviceID, Person: "p-sam", Name: "Sam", App: "coach"}}
	whoIsAs(n, samDev)
	rec = httptest.NewRecorder()
	n.ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net"+whoamiPath, nil))
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"role":"guest"`) || rec.Header().Get(versionHeader) != "" {
		t.Errorf("guest whoami: %d %s, version %q", rec.Code, rec.Body, rec.Header().Get(versionHeader))
	}
	whoIsAs(n, ownerID)

	body, _ := json.Marshal(feedbackRequest{Note: "hi", Screenshot: pngBase64(t, 4, 4)})
	rec = httptest.NewRecorder()
	d.handleFeedback(rec, httptest.NewRequest("POST", feedbackPath, bytes.NewReader(body)), caller{Identity: ownerID, Role: RoleOwner}, d.cfg.Apps[0])
	var fb map[string]string
	json.NewDecoder(rec.Body).Decode(&fb)
	rec = httptest.NewRecorder()
	n.adminHandler().ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net:8443/v1/feedback/"+fb["id"]+"/screenshot", nil))
	if rec.Code != 200 || rec.Header().Get("X-Content-Type-Options") != "nosniff" || rec.Header().Get(versionHeader) != version() {
		t.Errorf("screenshot: %d %v", rec.Code, rec.Header())
	}

	client, server := net.Pipe()
	defer client.Close()
	go d.handleControl(server)
	json.NewEncoder(client).Encode(controlRequest{Cmd: "status"})
	var reply controlReply
	if err := json.NewDecoder(client).Decode(&reply); err != nil || reply.Version != version() {
		t.Errorf("control reply: %+v %v", reply, err)
	}
}

// doctor calls the API with the stored credential, so an expired one fails, and warns
// about a node key that expires within a month.
func TestDoctorCredentialAndKeyExpiry(t *testing.T) {
	d, f := testDaemon(t)
	cfg := &Config{Owner: "alex@example.com", Apps: []App{{Name: "Coach", Slug: "coach", Port: 4317, Shareable: true}}}
	credential := func() Check {
		for _, c := range sharingChecks(cfg, d.configPath, nil) {
			if c.ID == "sharing-credential" {
				return c
			}
		}
		t.Fatal("no credential check")
		return Check{}
	}
	if c := credential(); c.Status != statusOK {
		t.Errorf("working credential: %+v", c)
	}
	f.Mu.Lock()
	f.Token = "tskey-api-rotated" // the stored one no longer works
	f.Mu.Unlock()
	if c := credential(); c.Status != statusFail {
		t.Errorf("expired credential: %+v", c)
	}

	soon, later := time.Now().Add(10*24*time.Hour), time.Now().Add(60*24*time.Hour)
	for expiry, warn := range map[*time.Time]bool{&soon: true, &later: false, nil: false} {
		reply := &controlReply{Apps: []NodeStatus{{appInfo: cfg.Apps[0].view(), State: "serving", KeyExpiry: expiry}}}
		got := slices.ContainsFunc(nodeChecks(cfg.Apps[0], reply, &paths{}), func(c Check) bool { return c.ID == "key-expiry" && c.Status == statusWarn })
		if got != warn {
			t.Errorf("key expiring %v: warned %v", expiry, got)
		}
	}
}

// A node of the owner's that logged in through the browser gets its key expiry turned
// off once an API credential is stored; tagged nodes and other people's are left alone.
func TestKeyExpiryOffForBrowserLogins(t *testing.T) {
	d, _ := testDaemon(t)
	n := newNode(d, App{Name: "Notes", Slug: "notes", Port: 4400})
	expiry := time.Now().Add(90 * 24 * time.Hour)
	n.owner, n.keyExpiry = "Alex@example.com", &expiry
	if !n.wantsKeyExpiryOff() {
		t.Error("the owner's browser-logged-in node")
	}
	n.keyExpiry = nil
	if n.wantsKeyExpiryOff() {
		t.Error("a key that doesn't expire")
	}
	n.keyExpiry, n.owner = &expiry, "someone@example.com"
	if n.wantsKeyExpiryOff() {
		t.Error("someone else's node")
	}
	n.owner = "" // tagged
	if n.wantsKeyExpiryOff() {
		t.Error("a tagged node")
	}
	n.owner = "alex@example.com"
	os.Remove(credentialsPath(d.configPath))
	if n.wantsKeyExpiryOff() {
		t.Error("no API credential")
	}
}

// Closing a node stops its call to turn off key expiry.
func TestKeyExpiryCallEndsWithTheNode(t *testing.T) {
	d, f := testDaemon(t)
	n := newNode(d, App{Name: "Notes", Slug: "notes", Port: 4400})
	n.close()
	if err := n.disableKeyExpiry(); !errors.Is(err, context.Canceled) || f.RequestCount("POST /api/v2/device/") != 0 {
		t.Errorf("err = %v, %d requests", err, f.RequestCount("POST /api/v2/device/"))
	}
}

// One daemon per state directory, whatever is left of a crashed one's socket.
func TestOneDaemonPerStateDir(t *testing.T) {
	dir := t.TempDir()
	lock, err := lockStateDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := lockStateDir(dir); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Errorf("second daemon: %v", err)
	}
	lock.Close()
	lock, err = lockStateDir(dir)
	if err != nil {
		t.Fatalf("after the first stopped: %v", err)
	}
	defer lock.Close()
	os.WriteFile(socketPath(dir), nil, 0o600) // a stale socket file
	ln, err := listenControl(socketPath(dir))
	if err != nil {
		t.Fatal(err)
	}
	ln.Close()
}

// A shareable app's login link would sign its node in untagged, so it is never shown;
// the fix is to make it shareable again.
func TestShareableNodeLoginIsRepublish(t *testing.T) {
	d, _ := testDaemon(t)
	n := d.nodes["coach"]
	n.backend, n.authURL = "NeedsLogin", "https://login.tailscale.com/a/x"
	st := n.status()
	if st.State != "needs-login" || st.LoginURL != "" {
		t.Errorf("shareable: %+v", st)
	}
	if checks := nodeChecks(n.App(), &controlReply{Apps: []NodeStatus{st}}, &paths{}); checks[0].Fix != "ovenlight publish --slug coach --shareable" {
		t.Errorf("doctor fix: %q", checks[0].Fix)
	}
	n.app.Shareable = false
	if st := n.status(); st.LoginURL != n.authURL {
		t.Errorf("unshared app: %+v", st)
	}
}

// Ovenlight matches these words in the connector's answers (NodeManager.swift,
// Guests.swift and AdminAPI.swift): "not invited" ends a share or a join, "another
// device" and "another guest" say why a claim failed, "owner only" that the connector is
// someone else's. Builds after 2 read the codes instead. Change neither without the app.
func TestPhoneMatchesErrorText(t *testing.T) {
	d, _ := testDaemon(t)
	n := d.nodes["coach"]
	n.setOwner(d.cfg.Owner)
	refused := func(who Identity, path, words, code string) {
		t.Helper()
		whoIsAs(n, who)
		rec := httptest.NewRecorder()
		n.ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net"+path, nil))
		if rec.Code != http.StatusForbidden || !strings.Contains(rec.Body.String(), words) || rec.Header().Get(errorHeader) != code {
			t.Errorf("%s: %d %s, %s %q", path, rec.Code, rec.Body, errorHeader, rec.Header().Get(errorHeader))
		}
	}
	for _, path := range []string{"/", whoamiPath} {
		refused(kimDev, path, "not invited", codeNotInvited)
		refused(Identity{Login: "lee@example.com", DeviceID: "nLee"}, path, "private to its owner", codeOwnerOnly)
	}

	// Another user's Ovenlight takes "owner only" as a connector set up for someone else.
	rec := httptest.NewRecorder()
	n.adminHandler().ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net:8443/v1/apps", nil))
	var e errorView
	json.NewDecoder(rec.Body).Decode(&e)
	if rec.Code != http.StatusForbidden || !strings.Contains(e.Error, "owner only") || e.Code != codeOwnerOnly || rec.Header().Get(errorHeader) != codeOwnerOnly {
		t.Errorf("admin API for another user: %d %+v", rec.Code, e)
	}

	d.sh.st.Invites = []Invite{sentInvite("i1", "Sam", "coach", "key-sam"), sentInvite("i2", "Kim", "coach", "key-kim")}
	for i := range d.sh.st.Invites {
		d.sh.st.Invites[i].Expires = time.Now().Add(time.Hour)
	}
	claimRefused := func(who Identity, key, words, code string) {
		t.Helper()
		if status, out := claim(t, d, who, key); status != http.StatusForbidden || !strings.Contains(out["error"], words) || out["code"] != code {
			t.Errorf("claim with %s: %d %v", key, status, out)
		}
	}
	claimRefused(Identity{Tagged: true, DeviceID: "nX"}, "key-sam", "not invited", codeNotInvited) // no guest tag
	if code, out := claim(t, d, samDev, "key-sam"); code != 200 {
		t.Fatalf("Sam's claim: %d %v", code, out)
	}
	waitFor(t, func() bool { d.sh.mu.Lock(); defer d.sh.mu.Unlock(); return d.sh.st.invite("i1").KeyID == "" }, "the spent key to be dropped")
	claimRefused(kimDev, "key-sam", "another device", codeUsedElsewhere)
	claimRefused(samDev, "key-kim", "another guest", codeOtherPerson)
}

// The admin API answers only the owner, at the node's own name, and takes a change only
// with X-Ovenlight-Request.
func TestAdminAPIGate(t *testing.T) {
	d, _ := testDaemon(t)
	n := d.nodes["coach"]
	n.setOwner(d.cfg.Owner)
	const self = "https://coach.tail1.ts.net:8443"
	cases := []struct {
		name, method, url string
		who               Identity
		header            bool // X-Ovenlight-Request: 1
		want              int
		code              string
	}{
		{"the owner", "GET", self + "/v1/guests", ownerID, false, http.StatusOK, ""},
		{"the owner's change", "DELETE", self + "/v1/invites/nope", ownerID, true, http.StatusNotFound, codeNotFound},
		{"another user", "GET", self + "/v1/guests", Identity{Login: "lee@example.com", DeviceID: "nLee"}, false, http.StatusForbidden, codeOwnerOnly},
		{"a guest device", "GET", self + "/v1/guests", samDev, false, http.StatusForbidden, codeOwnerOnly},
		{"another name for the node", "GET", "https://evil.example.com:8443/v1/guests", ownerID, false, http.StatusMisdirectedRequest, codeBadRequest},
		{"the node's address", "GET", "https://100.64.0.1:8443/v1/guests", ownerID, false, http.StatusMisdirectedRequest, codeBadRequest},
		{"POST without the header", "POST", self + "/v1/invites", ownerID, false, http.StatusForbidden, codeBadRequest},
		{"device approval is gone", "POST", self + "/v1/devices/nKim/approve", ownerID, true, http.StatusNotFound, codeNotFound},
		{"DELETE without the header", "DELETE", self + "/v1/invites/nope", ownerID, false, http.StatusForbidden, codeBadRequest},
		{"no such endpoint", "GET", self + "/v1/invites", ownerID, false, http.StatusNotFound, codeNotFound},
	}
	for _, c := range cases {
		whoIsAs(n, c.who)
		req := httptest.NewRequest(c.method, c.url, nil)
		if c.header {
			req.Header.Set(adminHeader, "1")
		}
		rec := httptest.NewRecorder()
		n.adminHandler().ServeHTTP(rec, req)
		var e errorView
		json.Unmarshal(rec.Body.Bytes(), &e)
		if rec.Code != c.want || e.Code != c.code || rec.Header().Get(errorHeader) != c.code {
			t.Errorf("%s: %d %s, want %d %q", c.name, rec.Code, rec.Body, c.want, c.code)
		}
	}
}

// A value with a space left unquoted, as in --name Brand New, leaves a stray word, which
// every command refuses before it does anything, naming the flag when it can.
func TestStrayArgumentsAreRefused(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // the default paths, were --config and --state dropped
	dir := t.TempDir()
	config := filepath.Join(dir, "config.json")
	paths := []string{"--config", config, "--state", filepath.Join(dir, "state")}
	for _, tc := range []struct {
		cmd  func([]string) error
		args []string
		want string
	}{
		{cmdPublish, []string{"--name", "Brand", "New", "--port", "3000"}, `unexpected argument "New" after --name "Brand": put a value with spaces in quotes, such as --name "Brand New"`},
		{cmdPublish, []string{"--name=Brand", "New", "--port", "3000"}, `unexpected argument "New" after --name "Brand"`},
		{cmdPublish, []string{"--port", "3000", "New"}, `unexpected argument "New": put a value with spaces in quotes, such as --name "Brand New" or --run 'npm start'`},
		{cmdPublish, []string{"--port", "3000", "--run", "npm", "start"}, `unexpected argument "start" after --run "npm"`},
		{cmdRun, []string{"--log", "my", "log"}, `unexpected argument "log"`},
		{cmdUnpublish, []string{"coach", "extra"}, `unexpected argument "extra"`},
		{cmdStatus, []string{"--json", "extra"}, `unexpected argument "extra"`},
		{cmdDoctor, []string{"extra"}, `unexpected argument "extra"`},
		{cmdCheck, []string{"coach", "extra", "--json"}, `unexpected argument "extra"`},
		{cmdLogs, []string{"coach", "-n", "5", "extra"}, `unexpected argument "extra"`},
		{cmdRestart, []string{"coach", "extra"}, `unexpected argument "extra"`},
		{cmdAuth, []string{"remove", "extra"}, `unexpected argument "extra"`},
		{cmdAuth, []string{"status", "--check", "extra"}, `unexpected argument "extra"`},
		{cmdAuth, []string{"set", "--oauth-client-id", "abc", "secret"}, "the secret is read from standard input"},
		{cmdSetupSharing, []string{"--owner-label", "Sam", "Smith"}, `unexpected argument "Smith" after --owner-label "Sam"`},
		{cmdShare, []string{"coach", "--to", "Sam", "Smith"}, `unexpected argument "Smith" after --to "Sam"`},
		{cmdGuests, []string{"--all", "extra"}, `unexpected argument "extra"`},
		{cmdRevoke, []string{"Sam", "Smith"}, `unexpected argument "Smith"`},
		{cmdFeedback, []string{"--app", "coach", "extra"}, `unexpected argument "extra" after --app "coach"`},
		{cmdMCP, []string{"extra"}, `unexpected argument "extra"`},
		{cmdGuide, []string{"extra"}, `unexpected argument "extra"`},
	} {
		args := tc.args
		if len(args) > 0 && (args[0] == "remove" || args[0] == "status" || args[0] == "set") {
			args = slices.Concat(args[:1], paths, args[1:]) // auth's subcommand comes first
		} else {
			args = slices.Concat(paths, args)
		}
		err := tc.cmd(args)
		if err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%q: %v, want %s", tc.args, err, tc.want)
		}
		if strings.Contains(fmt.Sprint(err), `"secret"`) {
			t.Errorf("%q repeats the secret: %v", tc.args, err)
		}
	}
	// new's name may come unquoted, so a word after a string flag's value, as the paths',
	// is refused even as the name's first, unless it is clearly the whole name; new says to
	// put the name first.
	my := filepath.Join(dir, "My")
	for _, tc := range []struct {
		args []string
		want string
	}{
		{slices.Concat([]string{"Coach", "--dir", my, "Projects"}, paths), `unexpected argument "Projects" after --dir`},
		{slices.Concat([]string{"--dir", my, "Projects", "Todo"}, paths), `unexpected argument "Projects" after --dir "` + my + `": give the name first: ovenlight new "<App Name>" --dir <parent>, ` +
			`and put a value with spaces in quotes, such as --dir "` + my + ` Projects"`},
		{slices.Concat(paths, []string{"Coach"}), `unexpected argument "Coach" after --state`},
	} {
		if err := cmdNew(tc.args); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("new %q: %v, want %s", tc.args, err, tc.want)
		}
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Errorf("a refused command left %v", entries)
	}
	if entries, _ := os.ReadDir(os.Getenv("HOME")); len(entries) != 0 {
		t.Errorf("a refused command left %v in HOME", entries)
	}
}

// A node has logged in once its state holds a login profile; a node that only started
// has a machine key and nothing more.
func TestNodeLoggedIn(t *testing.T) {
	state := t.TempDir()
	write := func(slug string, keys map[string][]byte) {
		os.MkdirAll(nodeDir(state, slug), 0o700)
		data, _ := json.Marshal(keys)
		os.WriteFile(filepath.Join(nodeDir(state, slug), "tailscaled.state"), data, 0o600)
	}
	write("coach", map[string][]byte{"_machinekey": []byte("privkey:x"), "_profiles": []byte(`{"a1b2":{"ID":"a1b2"}}`)})
	write("notes", map[string][]byte{"_machinekey": []byte("privkey:x")})
	os.MkdirAll(nodeDir(state, "plants"), 0o700)
	for slug, want := range map[string]bool{"coach": true, "notes": false, "plants": false, "none": false} {
		if got := nodeLoggedIn(state, slug); got != want {
			t.Errorf("%s: %v", slug, got)
		}
	}
	// Without a login link yet, doctor says where it will be.
	reply := &controlReply{Apps: []NodeStatus{{appInfo: appInfo{Slug: "coach"}, State: "needs-login"}}}
	if c := nodeChecks(App{Name: "Coach", Slug: "coach", Port: 4317}, reply, &paths{})[0]; strings.Contains(c.Fix, "Open  ") || strings.Contains(c.Fix, "Open -") || !strings.Contains(c.Fix, "ovenlight status") {
		t.Errorf("no link: %+v", c)
	}
}

// After loginLinkWait without a link, a node that hasn't reached Tailscale's login server
// this run says it can't, shareable too; a server's error is shown as it is, after a
// shareable app's reshare; the owner's node that has run, or whose key expired, says to
// restart the connector.
func TestNoLoginLinkSaysWhy(t *testing.T) {
	// loginErrorHealth is Tailscale's wording, which an upgrade could change.
	if got := health.LoginStateWarnable.Text(health.Args{health.ArgError: "e"}); got != loginErrorHealth+"e" {
		t.Fatalf("Tailscale's login warning reads %q", got)
	}
	const refused = "fetch control key: Get \"http://127.0.0.1:9/key?v=142\": dial tcp 127.0.0.1:9: connect: connection refused"
	app := App{Name: "Coach", Slug: "coach", Port: 4317}
	waited := func(app App, before *ipnstate.Status, self *ipnstate.PeerStatus, loginErr string) (*appNode, NodeStatus) {
		n := newAppNode(app, t.TempDir(), devOptions{}, nil)
		if before != nil {
			n.applyStatus(before)
		}
		st := &ipnstate.Status{BackendState: "NeedsLogin", Self: self}
		if loginErr != "" {
			st.Health = []string{loginErrorHealth + loginErr}
		}
		n.applyStatus(st)
		if s := n.status(); s.LoginErr != "" || s.NoServer || s.Restart {
			t.Errorf("before the wait: %+v", s)
		}
		n.noLinkAt = n.noLinkAt.Add(-loginLinkWait)
		n.applyStatus(st)
		return n, n.status()
	}
	installed := &paths{config: defaultConfigPath(), state: defaultStateDir()}
	check := func(app App, st NodeStatus) Check {
		return nodeChecks(app, &controlReply{Apps: []NodeStatus{st}}, installed)[0]
	}
	fresh := &ipnstate.PeerStatus{}
	for _, loginErr := range []string{"", refused} {
		for _, app := range []App{app, {Name: "Coach", Slug: "coach", Port: 4317, Shareable: true}} {
			_, st := waited(app, nil, fresh, loginErr)
			c := check(app, st)
			if !st.NoServer || st.Restart || c.Status != statusFail || c.Actor != actorPerson || !strings.HasSuffix(c.Message, "it "+unreachableText(loginErr)) || !strings.Contains(c.Fix, "VPN") {
				t.Errorf("unreachable %q, shareable %v: %+v %+v", loginErr, app.Shareable, st, c)
			}
		}
	}
	// A key the server refused.
	const invalid = "invalid key: unable to validate API key"
	_, st := waited(app, nil, fresh, invalid)
	if c := check(app, st); st.NoServer || c.Message != "the node's last login failed: "+invalid || strings.Contains(c.Fix, "VPN") {
		t.Errorf("refused: %+v %+v", st, c)
	}
	shared := app
	shared.Shareable = true
	_, st = waited(shared, nil, fresh, invalid)
	if c := check(shared, st); c.Fix != reshareCommand("coach") {
		t.Errorf("refused, shareable: %+v", c)
	}
	// Once the node has run, or its key has expired, nothing blames the network, and the
	// owner's node says to restart the connector, which asks for a link as it starts.
	running := &ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{InNetworkMap: true}}
	for name, tc := range map[string]struct {
		before *ipnstate.Status
		self   *ipnstate.PeerStatus
	}{"ran": {running, fresh}, "expired": {nil, &ipnstate.PeerStatus{Expired: true}}} {
		for _, loginErr := range []string{"", refused} {
			_, st := waited(app, tc.before, tc.self, loginErr)
			c := check(app, st)
			if st.NoServer || !st.Restart || st.LoginErr != loginErr || c.Status != statusFail || c.Actor != actorPerson || c.Fix != restartLoginFix(installed) {
				t.Errorf("%s %q: %+v %+v", name, loginErr, st, c)
			}
		}
		_, st := waited(shared, tc.before, tc.self, "")
		if c := check(shared, st); st.Restart || c.Fix != reshareCommand("coach") {
			t.Errorf("%s, shareable: %+v %+v", name, st, c)
		}
	}
	if fix := restartLoginFix(installed); !strings.Contains(fix, "install script again") {
		t.Errorf("installed: %s", fix)
	}
	if fix := restartLoginFix(&paths{config: "/tmp/c.json", state: "/tmp/s"}); strings.Contains(fix, "install script") || !strings.Contains(fix, "--state /tmp/s") {
		t.Errorf("other paths: %s", fix)
	}
}
