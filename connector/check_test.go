package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"html/template"
	"image"
	"image/png"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const testHost = "coach.tail1.ts.net"

// appServer serves h on 127.0.0.1 and returns an app published on its port.
func appServer(t *testing.T, h http.HandlerFunc) App {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return App{Name: "Coach", Slug: "coach", Port: srv.Listener.Addr().(*net.TCPAddr).Port}
}

func findCheck(t *testing.T, checks []Check, id string) Check {
	t.Helper()
	for _, c := range checks {
		if c.ID == id {
			return c
		}
	}
	t.Fatalf("no %s check in %+v", id, checks)
	return Check{}
}

func pngIcon(t *testing.T, size int) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := png.Encode(&b, image.NewRGBA(image.Rect(0, 0, size, size))); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// The upstream check asks the app as the phone's requests reach it: with the node's
// tailnet Host and the proxy's forwarding headers.
func TestUpstreamCheckSendsTailnetHost(t *testing.T) {
	var got *http.Request
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) { got = r.Clone(r.Context()) })
	if c := upstreamCheck(app, testHost); c.Status != statusOK {
		t.Fatalf("check = %+v", c)
	}
	if got.Host != testHost || got.Header.Get("X-Forwarded-Host") != testHost || got.Header.Get("X-Forwarded-Proto") != "https" {
		t.Errorf("Host = %q, headers = %v", got.Host, got.Header)
	}
	upstreamCheck(app, "")
	if want := fmt.Sprintf("127.0.0.1:%d", app.Port); got.Host != want || got.Header.Get("X-Forwarded-Proto") != "" {
		t.Errorf("without a tailnet name: Host = %q, headers = %v", got.Host, got.Header)
	}
}

// Each dev server's refusal, as it answers a Host it doesn't know (Vite 8.3 and
// webpack-dev-server 6 recorded with curl; Angular's from @angular/build's
// host-check-middleware.js), gets a fix naming the setting that allows it.
func TestUpstreamCheckDevServerRefusesHost(t *testing.T) {
	cases := []struct{ name, body, want string }{
		{"vite", "Blocked request. This host (\"coach.tail1.ts.net\") is not allowed.\nTo allow this host, add \"coach.tail1.ts.net\" to `server.allowedHosts` in vite.config.js.",
			"server: { allowedHosts: ['.ts.net'] }"},
		{"vite preview", "Blocked request. This host (\"coach.tail1.ts.net\") is not allowed.\nTo allow this host, add \"coach.tail1.ts.net\" to `preview.allowedHosts` in vite.config.js.",
			"preview: { allowedHosts: ['.ts.net'] }"},
		{"angular", `<h1>Blocked request. This host ("coach.tail1.ts.net") is not allowed.</h1>
      <p>To allow this host, add it to <code>allowedHosts</code> under the <code>serve</code> target in <code>angular.json</code>.</p>`,
			`"allowedHosts": [".ts.net"]`},
		{"webpack", "Invalid Host header", "devServer: { allowedHosts: ['.ts.net'] }"},
	}
	for _, tc := range cases {
		app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
			if strings.HasPrefix(r.Host, "127.0.0.1:") {
				return
			}
			w.WriteHeader(http.StatusForbidden)
			w.Write([]byte(tc.body))
		})
		c := upstreamCheck(app, testHost)
		if c.Status != statusFail || !strings.Contains(c.Fix, tc.want) || c.Actor != actorAgent || !strings.Contains(c.Message, testHost) {
			t.Errorf("%s: %+v", tc.name, c)
		}
		// Without the tailnet name the dev server answers, as it does for the connector.
		if c := upstreamCheck(app, ""); c.Status != statusOK {
			t.Errorf("%s with 127.0.0.1: %+v", tc.name, c)
		}
	}
}

// An app that refuses the tailnet name in its own way is told to allow it.
func TestUpstreamCheckHostAllowlist(t *testing.T) {
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Host, "127.0.0.1:") {
			http.Error(w, "Bad Request (400)", http.StatusBadRequest)
		}
	})
	c := upstreamCheck(app, testHost)
	if c.Status != statusFail || !strings.Contains(c.Fix, "ALLOWED_HOSTS") || !strings.Contains(c.Message, "200 OK for 127.0.0.1") {
		t.Errorf("check = %+v", c)
	}
	// An app that fails whatever the Host gets the plain fix.
	broken := appServer(t, func(w http.ResponseWriter, r *http.Request) { http.Error(w, "oops", http.StatusInternalServerError) })
	if c := upstreamCheck(broken, testHost); c.Status != statusFail || !strings.Contains(c.Fix, "must answer with a page") {
		t.Errorf("broken app: %+v", c)
	}
}

// goodApp is an app that follows the building guide.
func goodApp(t *testing.T) http.HandlerFunc {
	icon := pngIcon(t, 512)
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			w.Write([]byte(`<!doctype html><html><head>
				<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
				<link rel="manifest" href="/manifest.webmanifest">
				<link rel="stylesheet" href="https://cdn.example.com/x.css">
				<link rel="stylesheet" href="app.css">
				</head><body><a href="https://example.com/">elsewhere</a></body></html>`))
		case "/app.css":
			w.Write([]byte(`body { padding-top: env(safe-area-inset-top); }`))
		case "/manifest.webmanifest":
			w.Write([]byte(`{"theme_color": "#0e6b66", "icons": [{"src": "/icon-512.png", "sizes": "512x512", "type": "image/png"}]}`))
		case "/icon-512.png":
			w.Write(icon)
		default:
			http.NotFound(w, r)
		}
	}
}

func TestCheckAppGood(t *testing.T) {
	checks := checkApp(appServer(t, goodApp(t)), testHost)
	for _, c := range checks {
		if c.App != "coach" {
			t.Errorf("%s has app %q", c.ID, c.App)
		}
		if c.Status != statusOK && c.ID != "bind-address" { // lsof may not see the test's own listener
			t.Errorf("%+v", c)
		}
	}
	for _, id := range []string{"upstream", "viewport", "safe-area", "local-urls", "manifest", "icon", "theme-color"} {
		findCheck(t, checks, id)
	}
	if c := findCheck(t, checks, "icon"); c.Message != "icon /icon-512.png, 512x512" {
		t.Errorf("icon: %q", c.Message)
	}
}

func TestCheckAppRedirects(t *testing.T) {
	cases := []struct {
		location, status, want string
	}{
		{"/app/", statusOK, "redirects to /app/, on the app's own host"},
		{"https://" + testHost + ":443/app/", statusOK, "redirects to /app/"},
		{"https://accounts.example.com/o/oauth2/auth?client_id=secret", statusFail, "https://accounts.example.com/o/oauth2/auth, another website"},
		{"http://" + testHost + "/app/", statusFail, "serves only https://" + testHost + "/"},
		{"http://localhost:5173/", statusFail, "on the phone is the phone itself"},
	}
	for _, tc := range cases {
		app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/" {
				http.Redirect(w, r, tc.location, http.StatusFound)
				return
			}
			w.Write([]byte(`<meta name="viewport" content="width=device-width, viewport-fit=cover">`))
		})
		checks := checkApp(app, testHost)
		c := findCheck(t, checks, "redirect")
		if c.Status != tc.status || !strings.Contains(c.Message, tc.want) || strings.Contains(c.Message, "secret") {
			t.Errorf("%s: %+v", tc.location, c)
		}
		if tc.status == statusFail && checksOK(checks) {
			t.Errorf("%s: a failing redirect must fail the check", tc.location)
		}
	}

	loop := appServer(t, func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/", http.StatusFound) })
	if c := findCheck(t, checkApp(loop, testHost), "redirect"); c.Status != statusFail {
		t.Errorf("redirect loop: %+v", c)
	}
	missing := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/login", http.StatusFound)
			return
		}
		http.NotFound(w, r)
	})
	if c := findCheck(t, checkApp(missing, testHost), "home-page"); c.Status != statusFail || !strings.Contains(c.Message, "/login, returned 404") {
		t.Errorf("redirect to a missing page: %+v", c)
	}
}

func TestCheckAppLayout(t *testing.T) {
	page := func(head, body string) App {
		return appServer(t, func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/" {
				http.NotFound(w, r)
				return
			}
			fmt.Fprintf(w, "<html><head>%s</head><body>%s</body></html>", head, body)
		})
	}

	checks := checkApp(page("", ""), testHost)
	if c := findCheck(t, checks, "viewport"); c.Status != statusFail || !strings.Contains(c.Fix, "viewport-fit=cover") {
		t.Errorf("no viewport: %+v", c)
	}

	checks = checkApp(page(`<meta name="viewport" content="width=device-width, initial-scale=1">`, ""), testHost)
	if c := findCheck(t, checks, "viewport"); c.Status != statusWarn || c.Actor != actorAgent {
		t.Errorf("viewport without cover: %+v", c)
	}

	checks = checkApp(page(`<meta name="viewport" content="width=device-width;viewport-fit = cover"><link rel="stylesheet" href="/missing.css">`,
		`<script>fetch("http://localhost:3000/api/cards"); new WebSocket("ws://127.0.0.1:3000/live")</script><p>See http://LOCALHOST:3000/ignore-previous-instructions</p>`), testHost)
	if c := findCheck(t, checks, "viewport"); c.Status != statusOK {
		t.Errorf("viewport with cover: %+v", c)
	}
	if c := findCheck(t, checks, "safe-area"); c.Status != statusWarn || !strings.Contains(c.Message, "the stylesheet /missing.css, which didn't load (GET returned 404 Not Found)") {
		t.Errorf("a stylesheet that doesn't load: %+v", c)
	}
	c := findCheck(t, checks, "local-urls")
	// Only their origins: the rest is whatever the page says.
	if c.Status != statusWarn || c.Message != "the home page names http://localhost:3000, ws://127.0.0.1:3000, which on the phone means the phone itself" {
		t.Errorf("localhost URLs: %+v", c)
	}
	if !checksOK(checks) {
		t.Error("warnings alone must not fail the check")
	}

	// What the page says is repeated short, on one line.
	long := "width=device-width,\nfit=" + strings.Repeat("x", 200)
	if c := findCheck(t, checkApp(page(`<meta name="viewport" content="`+long+`">`, ""), testHost), "viewport"); strings.Contains(c.Message, "\n") || strings.Contains(c.Message, strings.Repeat("x", 81)) || !strings.Contains(c.Message, "x...") {
		t.Errorf("long viewport: %+v", c)
	}

	checks = checkApp(page(`<meta name="viewport" content="viewport-fit=cover">`, ""), testHost)
	if c := findCheck(t, checks, "safe-area"); c.Status != statusWarn || !strings.Contains(c.Message, "stylesheets it links") {
		t.Errorf("no safe-area padding: %+v", c)
	}

	// Inline padding counts.
	checks = checkApp(page(`<meta name="viewport" content="viewport-fit=cover"><style>main { padding-bottom: env(safe-area-inset-bottom) }</style>`, ""), testHost)
	if c := findCheck(t, checks, "safe-area"); c.Status != statusOK {
		t.Errorf("inline padding: %+v", c)
	}

	// An API at / isn't a page to lay out.
	api := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{}`))
	})
	if c := findCheck(t, checkApp(api, testHost), "home-page"); c.Status != statusWarn || !strings.Contains(c.Message, "application/json") {
		t.Errorf("JSON home page: %+v", c)
	}
}

func TestCheckAppIconAndColor(t *testing.T) {
	small := pngIcon(t, 64)
	cases := []struct {
		name, head, manifest string
		icon, theme          string // the checks' statuses
		iconWant, themeWant  string
	}{
		{"no manifest", "", "", statusWarn, statusWarn, "neither the web manifest nor an apple-touch-icon", "no theme color"},
		{"touch icon and meta color", `<link rel="apple-touch-icon" href="/small.png"><meta name="theme-color" content="#abc">`, "",
			statusWarn, statusOK, "is 64x64", "theme color #abc"},
		{"svg only", `<link rel="manifest" href="m.json">`, `{"theme_color": "rgb(1, 2, 3)", "icons": [{"src": "i.svg", "type": "image/svg+xml"}]}`,
			statusWarn, statusWarn, "only SVG icons", `"rgb(1, 2, 3)" isn't #rgb or #rrggbb`},
		{"absolute icon", `<link rel="manifest" href="m.json">`, `{"icons": [{"src": "https://` + testHost + `/i.png", "sizes": "512x512"}]}`,
			statusWarn, statusWarn, "isn't a relative URL", "no theme color"},
		{"missing icon", `<link rel="manifest" href="m.json">`, `{"theme_color": "#123456", "icons": [{"src": "gone.png", "sizes": "512x512"}]}`,
			statusWarn, statusOK, "GET returned 404", "#123456"},
	}
	for _, tc := range cases {
		app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/":
				fmt.Fprintf(w, `<meta name="viewport" content="width=device-width, viewport-fit=cover">%s`, tc.head)
			case "/m.json":
				w.Write([]byte(tc.manifest))
			case "/small.png":
				w.Write(small)
			default:
				http.NotFound(w, r)
			}
		})
		checks := checkApp(app, testHost)
		if c := findCheck(t, checks, "icon"); c.Status != tc.icon || !strings.Contains(c.Message, tc.iconWant) {
			t.Errorf("%s: icon %+v", tc.name, c)
		}
		if c := findCheck(t, checks, "theme-color"); c.Status != tc.theme || !strings.Contains(c.Message, tc.themeWant) {
			t.Errorf("%s: theme %+v", tc.name, c)
		}
	}

	// A manifest link discovery can't use says why.
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `<link rel="manifest" href="https://`+testHost+`/m.json">`)
	})
	if c := findCheck(t, checkApp(app, testHost), "manifest"); c.Status != statusWarn || !strings.Contains(c.Message, "isn't a relative URL") {
		t.Errorf("absolute manifest link: %+v", c)
	}
}

// check_app runs through MCP. Without a connector nothing is served, so it fails, and
// says it checked with a placeholder tailnet name.
func TestMCPCheckApp(t *testing.T) {
	app := appServer(t, goodApp(t))
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	if err := (&Config{Apps: []App{app}}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	out, err := callTool("check_app", json.RawMessage(`{"slug": "coach"}`), p)
	if err != nil {
		t.Fatal(err)
	}
	res := out.(map[string]any)
	checks := res["checks"].([]Check)
	daemon := findCheck(t, checks, "daemon")
	if res["ok"] != false || daemon.Status != statusFail || daemon.Actor != actorPerson || !strings.Contains(daemon.Message, "Host checks used the placeholder name coach.example.ts.net, since") ||
		findCheck(t, checks, "icon").Status != statusOK {
		t.Errorf("result = %+v", res)
	}
	if _, err := callTool("check_app", json.RawMessage(`{"slug": "nope"}`), p); err == nil || !strings.Contains(err.Error(), `no published app has the slug "nope"`) {
		t.Errorf("unknown slug: %v", err)
	}
	// After a rename, the old name finds the app.
	if _, err := callTool("check_app", json.RawMessage(`{"slug": "Coach"}`), p); err == nil || !strings.Contains(err.Error(), `"Coach" is published as coach`) {
		t.Errorf("by name: %v", err)
	}

	// Nothing runs an app without a command, and only the person can have it run.
	app.Port = 1
	if err := (&Config{Apps: []App{app}}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	out, err = callTool("check_app", json.RawMessage(`{"slug": "coach"}`), p)
	if c := findCheck(t, out.(map[string]any)["checks"].([]Check), "port-listening"); err != nil || c.Actor != actorPerson ||
		!strings.Contains(c.Fix, "publish --config "+shellQuote(p.config)+" --state "+shellQuote(p.state)+" --slug coach --dir '<folder>' --run '<command>'") {
		t.Errorf("no command: %+v, %v", c, err)
	}
	if _, err := restartProcess(p, "coach"); err == nil || !strings.Contains(err.Error(), "nothing to restart. To have the connector start the app") {
		t.Errorf("restarting it: %v", err)
	}

	// An app the connector runs isn't listening because the connector isn't.
	app.Run, app.Dir, app.Port = "npm start", dir, 1
	if err := (&Config{Apps: []App{app}}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	checks, err = checkPublished(p, "coach")
	if c := findCheck(t, checks, "port-listening"); err != nil || strings.Contains(c.Fix, "ovenlight logs") || !strings.Contains(c.Fix, "because the connector") {
		t.Errorf("port: %+v, %v", c, err)
	}
	// Nothing answered, so no Host was checked.
	if c := findCheck(t, checks, "daemon"); strings.Contains(c.Message, "placeholder") {
		t.Errorf("daemon: %+v", c)
	}
}

// The connector says who is calling, so a sign-in in front of the app is one too many.
func TestLoginCheck(t *testing.T) {
	good := goodApp(t)
	form := `<!doctype html><meta name="viewport" content="width=device-width, viewport-fit=cover"><form method="post"><input name="user"><input type=password name="pw"></form>`
	for _, tc := range []struct {
		name, want string
		h          http.HandlerFunc
	}{
		{"a redirect to /login", "GET / redirects to /accounts/login/, a sign-in page", func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/" {
				http.Redirect(w, r, "/accounts/login/?next=/", http.StatusFound)
				return
			}
			w.Write([]byte("<!doctype html><title>Sign in</title>"))
		}},
		{"a password field", "the home page, /, asks for a password", func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/" {
				w.Write([]byte(form))
				return
			}
			good(w, r)
		}},
		{"no sign-in", "", good},
	} {
		checks := checkApp(appServer(t, tc.h), testHost)
		i := slices.IndexFunc(checks, func(c Check) bool { return c.ID == "login" })
		switch {
		case tc.want == "" && i >= 0:
			t.Errorf("%s: %+v", tc.name, checks[i])
		case tc.want != "" && (i < 0 || checks[i].Status != statusWarn || !strings.Contains(checks[i].Message, tc.want) || !strings.Contains(checks[i].Fix, "Ovenlight-User-Id")):
			t.Errorf("%s: %+v", tc.name, checks)
		}
	}
}

// Pulling down at the top of the page is how people reach Ovenlight's menu.
func TestOverscrollCheck(t *testing.T) {
	for _, page := range []string{
		`<style>html, body { margin: 0; font-family: "Inter"; overscroll-behavior: none; }</style>`,
		`<style>body{overscroll-behavior-y:none}</style>`,
		`<style>@media (min-width: 1px){:root{overscroll-behavior:NONE !important}}</style>`,
		`<body class="x" style="color: red; overscroll-behavior: none">`,
		`<style>html.dark { overscroll-behavior: none }</style>`,
	} {
		if c := overscrollCheck([]byte(page), nil); c == nil || c.Status != statusWarn || !strings.Contains(c.Message, "the home page") || !strings.Contains(c.Fix, "Send Feedback") {
			t.Errorf("%s: %+v", page, c)
		}
	}
	for _, page := range []string{
		`<style>.sheet { overscroll-behavior: none } body { overscroll-behavior: auto }</style>`,
		`<style>body .list { overscroll-behavior: none }</style>`,
		`<style>html { overscroll-behavior-x: none }</style>`,
		`<div style="overscroll-behavior: none">`,
	} {
		if c := overscrollCheck([]byte(page), nil); c != nil {
			t.Errorf("%s: %+v", page, c)
		}
	}
	sheets := []stylesheet{{path: "/a.css", css: []byte("body{color:red}")}, {path: "/b.css", css: []byte("html,body{overscroll-behavior:none}")}}
	if c := overscrollCheck([]byte("<p>"), sheets); c == nil || !strings.HasPrefix(c.Message, "/b.css ") {
		t.Errorf("in a stylesheet: %+v", c)
	}
}

// Doctor's findings say who applies each fix, and where the person does it.
func TestDoctorActors(t *testing.T) {
	soon := time.Now().Add(24 * time.Hour)
	reply := &controlReply{
		Apps:  []NodeStatus{{appInfo: appInfo{Slug: "coach"}, State: "needs-login", LoginURL: "https://login.tailscale.com/a/x", DNSName: testHost, KeyExpiry: &soon}},
		Certs: map[string]string{},
	}
	checks := nodeChecks(App{Name: "Coach", Slug: "coach", Port: 4317}, reply, &paths{})
	if c := findCheck(t, checks, "node-login"); c.Actor != actorPerson || c.URL != "https://login.tailscale.com/a/x" {
		t.Errorf("login: %+v", c)
	}
	if c := findCheck(t, checks, "key-expiry"); c.Actor != actorPerson || c.URL != consoleMachines {
		t.Errorf("key expiry: %+v", c)
	}

	reply.Apps[0].State, reply.Certs["coach"] = "serving", "no certificate"
	if c := findCheck(t, nodeChecks(App{Slug: "coach"}, reply, &paths{}), "certificate"); c.Actor != actorPerson || c.URL != consoleDNS {
		t.Errorf("certificate: %+v", c)
	}
	if nodeHost(App{Slug: "coach"}, reply) != testHost || nodeHost(App{Slug: "other"}, reply) != "" || nodeHost(App{Slug: "coach"}, nil) != "" {
		t.Error("nodeHost")
	}

	closed := appServer(t, func(http.ResponseWriter, *http.Request) {})
	closed.Port = 1 // nothing listens there
	if c := findCheck(t, appChecks(closed, ""), "port-listening"); c.Actor != actorAgent || c.URL != "" {
		t.Errorf("port: %+v", c)
	}
	closed.Run = "npm start" // the connector runs it, so its output says why
	if c := findCheck(t, appChecks(closed, ""), "port-listening"); !strings.Contains(c.Fix, "ovenlight logs coach") || !strings.Contains(c.Fix, "$PORT") {
		t.Errorf("port of an app the connector runs: %+v", c)
	}
	// Both are omitted from JSON when empty.
	data, _ := json.Marshal(Check{ID: "x", Status: statusOK, Message: "m"})
	if strings.Contains(string(data), "actor") || strings.Contains(string(data), "url") {
		t.Errorf("JSON = %s", data)
	}
}

// An app that serves its folder, or CORS headers, hands what it holds to other
// people and other websites. A dev server's host check doesn't hide either.
func TestExposureChecks(t *testing.T) {
	project := func(env, pkg string, cors bool) App {
		good := goodApp(t)
		return appServer(t, func(w http.ResponseWriter, r *http.Request) {
			if !strings.HasPrefix(r.Host, "127.0.0.1:") {
				http.Error(w, "Blocked request. This host is not allowed.", http.StatusForbidden)
				return
			}
			if cors {
				w.Header().Set("Access-Control-Allow-Origin", "*")
			}
			switch {
			case r.URL.Path == "/.env" && env != "":
				w.Write([]byte(env))
			case r.URL.Path == "/package.json" && pkg != "":
				w.Header().Set("Content-Type", "application/json")
				w.Write([]byte(pkg))
			case r.Method == http.MethodOptions:
			default:
				good(w, r)
			}
		})
	}
	for _, tc := range []struct {
		name         string
		app          App
		folder, cors string
	}{
		{"neither", project("", "", false), statusOK, statusOK},
		{".env", project("# keys\nexport API_KEY=abc\n", `{"name": "coach"}`, false), statusFail, statusOK},
		{"package.json", project("", `{"scripts": {"dev": "vite"}}`, true), statusFail, statusFail},
		{"not the files", project("<p>a=b</p>", `["name"]`, false), statusOK, statusOK},
	} {
		checks := checkApp(tc.app, testHost)
		folder, cors := findCheck(t, checks, "served-files"), findCheck(t, checks, "cors")
		if folder.Status != tc.folder || cors.Status != tc.cors || folder.App != "coach" {
			t.Errorf("%s: %+v, %+v", tc.name, folder, cors)
		}
		if tc.cors == statusFail && (!strings.Contains(cors.Message, "Access-Control-Allow-Origin: *") || !strings.Contains(cors.Fix, "cors: false")) {
			t.Errorf("%s: cors %+v", tc.name, cors)
		}
	}
}

// Only an Access-Control-Allow-Origin that lets another website in fails, on / or under
// /api/; the headers a framework's CORS middleware sends by default grant nothing.
func TestCORSCheck(t *testing.T) {
	cors := func(apiOnly bool, set func(h http.Header, r *http.Request)) App {
		return appServer(t, func(w http.ResponseWriter, r *http.Request) {
			if !apiOnly || strings.HasPrefix(r.URL.Path, "/api/") {
				set(w.Header(), r)
			}
		})
	}
	for _, tc := range []struct {
		name, status, want string
		app                App
	}{
		{"stock FastAPI", statusOK, "", cors(false, func(h http.Header, r *http.Request) {
			if r.Method == http.MethodOptions {
				h.Set("Access-Control-Allow-Methods", "GET")
				h.Set("Access-Control-Allow-Credentials", "true")
				h.Set("Access-Control-Max-Age", "600")
			}
		})},
		{"any origin", statusFail, "GET /: Access-Control-Allow-Origin: *", cors(false, func(h http.Header, r *http.Request) {
			h.Set("Access-Control-Allow-Origin", "*")
		})},
		{"the origin reflected", statusFail, "Access-Control-Allow-Origin: HTTPS://example.com", cors(false, func(h http.Header, r *http.Request) {
			h.Set("Access-Control-Allow-Origin", strings.ToUpper(r.Header.Get("Origin")[:5])+r.Header.Get("Origin")[5:])
			h.Set("Access-Control-Allow-Headers", r.Header.Get("Access-Control-Request-Headers"))
		})},
		{"only on /api", statusFail, "/api/ovenlight-check: Access-Control-Allow-Origin: *", cors(true, func(h http.Header, r *http.Request) {
			h.Set("Access-Control-Allow-Origin", "*")
		})},
	} {
		c := corsCheck(tc.app)
		if c.Status != tc.status || !strings.Contains(c.Message, tc.want) {
			t.Errorf("%s: %+v", tc.name, c)
		}
		if tc.status == statusFail && (!strings.Contains(c.Message, "read its answers") || !strings.Contains(c.Fix, "Send no CORS headers") ||
			!strings.Contains(c.Fix, "cors: false") || !strings.Contains(c.Fix, "cors() middleware")) {
			t.Errorf("%s: %+v", tc.name, c)
		}
		if identity := strings.Contains(c.Message, "identity headers"); identity != (tc.name == "the origin reflected") {
			t.Errorf("%s: identity headers %v: %s", tc.name, identity, c.Message)
		}
	}
}

// Through the tailnet name, check asks as the connector's proxy does for the owner, so an
// app that wants identity on / isn't taken for one that refuses the name.
func TestCheckSendsOwnerIdentity(t *testing.T) {
	var got http.Header
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := strings.Cut(r.Host, ":")
		local := host == "127.0.0.1"
		if !local && !strings.HasSuffix(host, ".ts.net") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if id := r.Header.Get("Ovenlight-User-Id"); id != "" {
			got = r.Header.Clone()
			w.Write([]byte("<!doctype html><title>Coach</title>"))
			return
		}
		if !local || r.Header.Get("Origin") != "" {
			http.Error(w, "who are you?", http.StatusUnauthorized)
			return
		}
		w.Write([]byte("<!doctype html><title>Coach</title>"))
	})
	// No owner known: no made-up one, and the finding says it asked without identity.
	if c := upstreamCheck(app, testHost); c.Status != statusFail || !strings.Contains(c.Message, "without the identity headers") {
		t.Errorf("no owner: %+v", c)
	}
	if got != nil {
		t.Errorf("no owner: sent %v", got)
	}
	cfg := &Config{Owner: "alex@example.com", OwnerLabel: "Alex"}
	if c := upstreamCheck(withOwner(app, cfg, nil), testHost); c.Status != statusOK || got.Get("Ovenlight-User-Id") != "alex@example.com" || got.Get("Ovenlight-User") != "Alex" {
		t.Errorf("the configured owner: %+v, %v", c, got)
	}
	reply := &controlReply{Apps: []NodeStatus{{appInfo: appInfo{Slug: "coach"}, Owner: "sam@example.com", OwnerName: "Sam"}}}
	if upstreamCheck(withOwner(app, &Config{}, reply), testHost); got.Get("Ovenlight-User-Id") != "sam@example.com" || got.Get("Ovenlight-User") != "Sam" {
		t.Errorf("the node's owner: %v", got)
	}
	// Discovery reads / as the connector does, without identity, which this app allows.
	for _, c := range discoveryChecks(app, testHost) {
		if c.ID == "discovery" {
			t.Errorf("%+v", c)
		}
	}
}

// A file in the app's folder that the app serves is anyone's to read, at the root, under
// a path its pages' assets are under or under a common one, or a folder down; a file kept
// out of what it serves, a page answering every path, or a file a site serves on purpose
// isn't.
func TestServedFilesCheck(t *testing.T) {
	dir := t.TempDir()
	for name, data := range map[string]string{"public/app.db": "SQLite format 3\x00", "data/kept.sqlite": "SQLite format 3\x00",
		"node_modules/pkg/x.db": "SQLite format 3\x00", "public/index.html": "<!doctype html>", "index.html": "<!doctype html>", "empty": ""} {
		os.MkdirAll(filepath.Join(dir, filepath.Dir(name)), 0o755)
		os.WriteFile(filepath.Join(dir, name), []byte(data), 0o644)
	}
	check := func(h http.Handler, dir string) Check {
		app := appServer(t, h.ServeHTTP)
		app.Dir = dir
		return findCheck(t, checkApp(app, testHost), "served-files")
	}
	c := check(http.FileServer(http.Dir(filepath.Join(dir, "public"))), dir) // express.static('public')
	if c.Status != statusFail || !strings.Contains(c.Message, "GET /app.db returns public/app.db") || strings.Contains(c.Message, "x.db") || c.Actor != actorAgent {
		t.Errorf("public/: %+v", c)
	}
	if c := check(http.FileServer(http.Dir(dir)), dir); c.Status != statusFail || strings.Contains(c.Message, "index.html") || strings.Contains(c.Message, "empty") {
		t.Errorf("the folder: %+v", c)
	}
	spa := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header()["Content-Type"] = nil // no header, as some servers send a page
		w.Write([]byte("<!doctype html><title>Coach</title>"))
	})
	api := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"error":"not found"}`))
	})
	for name, h := range map[string]http.Handler{"a page for every path": spa, "JSON for every path": api} {
		if c := check(h, dir); c.Status != statusOK {
			t.Errorf("%s: %+v", name, c)
		}
	}
	// A page that starts with a blank line doesn't serve a .env that is one, nor a short
	// file it starts as; a short file served whole is served.
	tiny := t.TempDir()
	os.WriteFile(filepath.Join(tiny, ".env"), []byte("\n"), 0o644)
	os.WriteFile(filepath.Join(tiny, "notes"), []byte("\n<!doc"), 0o644)
	blank := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("\n<!doctype html><title>Coach</title>")) })
	if c := check(blank, tiny); c.Status != statusOK {
		t.Errorf("a page starting with a blank line: %+v", c)
	}
	os.WriteFile(filepath.Join(tiny, "secret.txt"), []byte("K=1\n"), 0o644)
	if c := check(http.FileServer(http.Dir(tiny)), tiny); c.Status != statusFail || !strings.Contains(c.Message, "GET /secret.txt returns secret.txt") || strings.Contains(c.Message, ".env") {
		t.Errorf("a short file served whole: %+v", c)
	}
	if c := check(spa, ""); c.Status != statusOK || !strings.Contains(c.Message, "neither /.env nor /package.json") {
		t.Errorf("no folder: %+v", c)
	}
	// Django with whitenoise serves STATIC_ROOT, here staticfiles/, at /static/.
	django := t.TempDir()
	os.MkdirAll(filepath.Join(django, "staticfiles"), 0o755)
	os.WriteFile(filepath.Join(django, "staticfiles", "db.sqlite3"), []byte("SQLite format 3\x00 and the rest"), 0o644)
	if c := check(http.StripPrefix("/static/", http.FileServer(http.Dir(filepath.Join(django, "staticfiles")))), django); c.Status != statusFail ||
		!strings.Contains(c.Message, "GET /static/db.sqlite3 returns staticfiles/db.sqlite3") {
		t.Errorf("STATIC_ROOT: %+v", c)
	}
	// A Go server that serves its folder under the path its page's assets are under.
	goApp := t.TempDir()
	for name, data := range map[string]string{"main.go": "package main\n\nfunc main() {}\n", "go.mod": "module plants\n", "plants.db": "SQLite format 3\x00", "style.css": "body {}"} {
		os.WriteFile(filepath.Join(goApp, name), []byte(data), 0o644)
	}
	serve := func(folder string) http.Handler {
		mux := http.NewServeMux()
		mux.Handle("/files/", http.StripPrefix("/files/", http.FileServer(http.Dir(folder))))
		mux.HandleFunc("/{$}", func(w http.ResponseWriter, r *http.Request) {
			w.Write([]byte(`<!doctype html><link rel="stylesheet" href="/files/style.css"><title>Plants</title>`))
		})
		return mux
	}
	if c := check(serve(goApp), goApp); c.Status != statusFail || !strings.Contains(c.Message, "GET /files/plants.db returns plants.db") ||
		!strings.Contains(c.Message, "GET /files/go.mod returns go.mod") || !strings.Contains(c.Fix, `http.Dir("public")`) {
		t.Errorf("Go, the folder at /files/: %+v", c)
	}
	os.MkdirAll(filepath.Join(goApp, "public"), 0o755)
	os.WriteFile(filepath.Join(goApp, "public", "style.css"), []byte("body {}"), 0o644)
	if c := check(serve(filepath.Join(goApp, "public")), goApp); c.Status != statusOK {
		t.Errorf("Go, public/ at /files/: %+v", c)
	}
	// A page route for every path renders a template from the folder, which starts as the
	// page does; served as a file, it still counts.
	pages := t.TempDir()
	os.WriteFile(filepath.Join(pages, "index.gohtml"), []byte("<!DOCTYPE html>\n<title>{{.}}</title>\n"), 0o644)
	os.WriteFile(filepath.Join(pages, "main.go"), []byte("package main\n\nfunc main() {}\n"), 0o644)
	page := template.Must(template.ParseFiles(filepath.Join(pages, "index.gohtml")))
	everyPath := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { page.Execute(w, r.URL.Path) })
	if c := check(everyPath, pages); c.Status != statusOK {
		t.Errorf("a template for every path: %+v", c)
	}
	if c := check(http.FileServer(http.Dir(pages)), pages); c.Status != statusFail || !strings.Contains(c.Message, "GET /index.gohtml returns index.gohtml") {
		t.Errorf("the folder with a template: %+v", c)
	}
	// The home folder holds more than an app's, so its files aren't asked for.
	t.Setenv("HOME", goApp)
	t.Setenv("USERPROFILE", goApp) // Windows' home
	if files := ownFiles(goApp); files != nil {
		t.Errorf("the home folder: %v", files)
	}
}

// A framework's debug mode shows its error pages, with the app's source and settings, to
// whoever opens the app.
func TestDebugCheck(t *testing.T) {
	django := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("<p>You\u2019re seeing this error because you have <code>DEBUG = True</code> in your Django settings file.</p>"))
	})
	if c := debugCheck(django, testHost); c.Status != statusFail || !strings.Contains(c.Message, "GET /ovenlight-check-missing shows Django's debug page") || !strings.Contains(c.Fix, "DEBUG = False") {
		t.Errorf("Django: %+v", c)
	}
	// Werkzeug's console answers 127.0.0.1, and a 400 to other names.
	flask := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path != "/console":
			http.NotFound(w, r)
		case !strings.HasPrefix(r.Host, "127.0.0.1:"):
			http.Error(w, "Bad Request", http.StatusBadRequest)
		default:
			w.Write([]byte(`<title>Console // Werkzeug Debugger</title><script src="?__debugger__=yes&amp;cmd=resource&amp;f=debugger.js"></script>`))
		}
	})
	if c := debugCheck(flask, testHost); c.Status != statusFail || !strings.Contains(c.Message, "GET /console shows Werkzeug's debugger") {
		t.Errorf("Flask: %+v", c)
	}
	if c := debugCheck(appServer(t, goodApp(t)), testHost); c.Status != statusOK {
		t.Errorf("no debug mode: %+v", c)
	}
}

// An HTML page sent without a Content-Type isn't taken for the .env.
func TestServesFileWithoutContentType(t *testing.T) {
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header()["Content-Type"] = nil
		w.Write([]byte("<!doctype html>\n<script>\nAPI=1\n</script>"))
	})
	if c := servedFilesCheck(app, testHost, nil, nil); c.Status != statusOK {
		t.Errorf("%+v", c)
	}
}

// CORS is about a page in this Mac's browser, which calls 127.0.0.1 as no one in particular.
func TestCORSAsksAsABrowserPage(t *testing.T) {
	var hosts []string
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Ovenlight-User-Id") != "" {
			t.Errorf("%s %s with identity", r.Method, r.URL.Path)
		}
		hosts = append(hosts, r.Host)
	})
	app.owner = "alex@example.com"
	corsCheck(app)
	for _, host := range hosts {
		if host != fmt.Sprintf("127.0.0.1:%d", app.Port) {
			t.Errorf("Host %q", host)
		}
	}
}

// localhost can bind ::1 only, which the connector, at 127.0.0.1, can't reach.
func TestLocalhostBindsOnlyIPv6(t *testing.T) {
	ln, err := net.Listen("tcp", "[::1]:0")
	if err != nil {
		t.Skip("no IPv6 loopback:", err)
	}
	defer ln.Close()
	app := App{Name: "Coach", Slug: "coach", Port: ln.Addr().(*net.TCPAddr).Port, Run: "npm start"}
	if v4, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", app.Port)); err != nil {
		t.Skip("the port is taken on 127.0.0.1")
	} else {
		v4.Close()
	}
	checks := appChecks(app, "")
	connectorDownFix(checks, app)
	if c := findCheck(t, checks, "bind-address"); c.Status != statusFail || c.Actor != actorAgent || !strings.Contains(c.Message, "[::1]:") || !strings.Contains(c.Fix, "not localhost") {
		t.Errorf("%+v", c)
	}
}

// An app that accepts connections but never answers is checked quickly, and the checks
// its requests would make aren't reported as passing.
func TestCheckAppThatDoesNotAnswer(t *testing.T) {
	defer func(c *http.Client) { appClient = c }(appClient)
	appClient = &http.Client{Timeout: 200 * time.Millisecond, CheckRedirect: appClient.CheckRedirect}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			defer conn.Close()
		}
	}()
	app := App{Name: "Coach", Slug: "coach", Port: ln.Addr().(*net.TCPAddr).Port, Dir: t.TempDir()}
	start := time.Now()
	checks := checkApp(app, testHost)
	if took := time.Since(start); took > 3*time.Second {
		t.Errorf("took %v", took)
	}
	for _, id := range []string{"served-files", "cors", "debug-mode"} {
		if c := findCheck(t, checks, id); c.Status != statusWarn || !strings.Contains(c.Message, "didn't answer GET / (no answer in 200ms)") || !strings.Contains(c.Message, "wasn't checked") {
			t.Errorf("%s: %+v", id, c)
		}
	}

	// One that answers / but no other path: each series stops at its first unanswered request.
	release := make(chan struct{})
	var asked atomic.Int32
	app = appServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			asked.Add(1)
			<-release
		}
	})
	defer close(release) // before the server closes, which waits for its handlers
	if c := servedFilesCheck(app, testHost, nil, nil); c.Status != statusWarn || !strings.Contains(c.Message, "didn't answer GET /.env (no answer in 200ms)") || asked.Load() != 1 {
		t.Errorf("served files, %d requests: %+v", asked.Load(), c)
	}
}

// A check whose every request the app refuses, such as with a host allowlist's 400,
// learns nothing, so it says so rather than passing.
func TestCheckEveryRequestRefused(t *testing.T) {
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.Error(w, "Bad Request (400)", http.StatusBadRequest)
		}
	})
	for _, c := range []Check{servedFilesCheck(app, testHost, nil, nil), debugCheck(app, testHost)} {
		if c.Status != statusWarn || !strings.Contains(c.Message, "refused every request this check sent, such as GET /") || !strings.Contains(c.Fix, "ALLOWED_HOSTS") {
			t.Errorf("%s: %+v", c.ID, c)
		}
	}
	if c := corsCheck(app); c.Status != statusOK { // GET / was answered
		t.Errorf("cors: %+v", c)
	}
}

// An app that refuses the tailnet name is asked with it once per check, and no path twice.
func TestServedFilesAsksOnceForTheTailnetName(t *testing.T) {
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "public"), 0o755)
	for _, name := range []string{"app.db", "public/app.db", "server.js"} {
		os.WriteFile(filepath.Join(dir, name), []byte("data"), 0o644)
	}
	tailnet := 0
	paths := map[string]int{}
	app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Host == testHost {
			tailnet++
			http.Error(w, "Blocked request. This host is not allowed.", http.StatusForbidden)
			return
		}
		paths[r.URL.Path]++
		http.NotFound(w, r)
	})
	app.Dir = dir
	if c := servedFilesCheck(app, testHost, nil, nil); c.Status != statusOK || tailnet != 1 {
		t.Errorf("%d requests with the tailnet name: %+v", tailnet, c)
	}
	for path, n := range paths {
		if n > 1 {
			t.Errorf("%s asked %d times", path, n)
		}
	}
	if paths["/app.db"] != 1 || paths["/public/app.db"] != 1 {
		t.Errorf("asked %v", paths)
	}
}

// AirPlay Receiver on every address answers on 127.0.0.1 too, but isn't the app.
func TestAirPlayAloneOnThePort(t *testing.T) {
	all, err := listeners(5000)
	if err != nil || len(all) == 0 || slices.ContainsFunc(all, func(l listener) bool { return !airPlay(l) }) {
		t.Skipf("AirPlay Receiver isn't alone on 5000 here: %v %v", all, err)
	}
	app := App{Name: "Coach", Slug: "coach", Port: 5000}
	checks := withPortChoice(appChecks(app, ""), app)
	if c := findCheck(t, checks, "port-listening"); c.Status != statusFail || !strings.Contains(c.Message, "nothing of the app's listens on 127.0.0.1:5000; AirPlay Receiver") ||
		!strings.Contains(c.Fix, "20000 to 29999") {
		t.Errorf("%+v", c)
	}
	if len(checks) != 1 {
		t.Errorf("more than port-listening: %+v", checks)
	}
	if c := portHeldCheck(App{Name: "Coach", Slug: "coach", Port: 5000, Run: "npm start"}, &controlReply{}); c != nil {
		t.Errorf("AirPlay Receiver keeps the copy from starting: %+v", c)
	}
}

// A restart finds an app that now serves its folder or lets other websites read it.
func TestRestartChecksServedFilesAndCORS(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, ".env"), []byte("SECRET=1\n"), 0o600)
	os.WriteFile(filepath.Join(dir, "index.html"), []byte("<!doctype html><title>Coach</title>"), 0o644)
	files := http.FileServer(http.Dir(dir))
	for _, open := range []bool{false, true} {
		app := appServer(t, func(w http.ResponseWriter, r *http.Request) {
			if !open {
				w.Write([]byte("<!doctype html><title>Coach</title>"))
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", "*")
			files.ServeHTTP(w, r)
		})
		app.Dir = dir
		checks := restartChecks(app)
		for _, id := range []string{"served-files", "cors"} {
			if c := findCheck(t, checks, id); (c.Status == statusFail) != open || c.App != "coach" {
				t.Errorf("open %v: %+v", open, c)
			}
		}
	}
}
