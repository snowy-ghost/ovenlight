package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif" // icon sizes
	_ "image/jpeg"
	_ "image/png"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"

	"golang.org/x/net/html"
)

// `ovenlight check` inspects one published app as Ovenlight will show it: the home page
// as the phone gets it, its viewport and the CSS it links, and the icon and color that
// discovery finds.

// maxStylesheets is how many stylesheets linked from the home page check reads.
const maxStylesheets = 5

// maxRedirects is how many redirects from / check follows on the app's own host.
const maxRedirects = 10

func cmdCheck(args []string) error {
	fs, p := newFlags("check")
	asJSON := fs.Bool("json", false, "machine-readable output")
	pos, err := parseArgs(fs, args, 1)
	if err != nil {
		return err
	}
	if len(pos) != 1 {
		return errors.New("usage: ovenlight check <slug> [--json]")
	}
	checks, err := checkPublished(p, pos[0])
	if err != nil {
		return err
	}
	reportChecks(checks, *asJSON)
	return nil
}

// checkPublished checks the published app slug, with its tailnet name from the running
// connector.
func checkPublished(p *paths, slug string) ([]Check, error) {
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return nil, err
	}
	app, err := cfg.Published(slug)
	if err != nil {
		return nil, err
	}
	host, status, problem := tailnetHost(p, app)
	checks := withPortChoice(checkApp(withOwner(app, cfg, status), host), app)
	if c := heldByOther(app, status, problem != nil && problem.ID == "daemon"); c != nil {
		checks = append([]Check{*c}, checks...)
	}
	checks = predatesRunChecks(checks, p, app, status)
	if problem == nil {
		return checks, nil
	}
	if problem.ID == "daemon" {
		connectorDownFix(checks, app)
		if reached(checks) {
			problem.Message += ". Host checks used the placeholder name " + host + ", since the real one isn't known until the connector runs"
		}
	}
	return append([]Check{*problem}, checks...), nil
}

// findID is the check with the ID among checks, nil when there is none.
func findID(checks []Check, id string) *Check {
	if i := slices.IndexFunc(checks, func(c Check) bool { return c.ID == id }); i >= 0 {
		return &checks[i]
	}
	return nil
}

// reached reports whether the checks found the app listening.
func reached(checks []Check) bool {
	return slices.ContainsFunc(checks, func(c Check) bool { return c.ID == "port-listening" && c.Status == statusOK })
}

// tailnetHost asks the running connector for the app node's tailnet name, and returns
// its status reply too, nil when it isn't running. Without a name, a finding says why,
// and the name is a placeholder, <slug>.example.ts.net, which a dev server refuses as it
// would the real one. Without a connector, nothing is served, and the finding fails.
func tailnetHost(p *paths, app App) (string, *controlReply, *Check) {
	placeholder := app.Slug + ".example.ts.net"
	reply, err := callDaemon(p.state, "status", 10*time.Second)
	if errors.Is(err, errDaemonDown) {
		return placeholder, nil, &Check{ID: "daemon", App: app.Slug, Status: statusFail,
			Message: err.Error() + ", so the phone can't open the app",
			Fix:     startFix(p), Actor: actorPerson}
	}
	if err != nil {
		return placeholder, nil, &Check{ID: "tailnet-name", App: app.Slug, Status: statusWarn,
			Message: err.Error() + ", so this checked the app with a placeholder for its tailnet name, " + placeholder + ", which a dev server that refuses other host names refuses too",
			Fix:     startFix(p), Actor: actorPerson}
	}
	if host := nodeHost(app, reply); host != "" {
		return host, reply, nil
	}
	return placeholder, reply, &Check{ID: "tailnet-name", App: app.Slug, Status: statusWarn,
		Message: "the app's node has no tailnet name yet, so this checked the app with a placeholder for it, " + placeholder + ", which a dev server that refuses other host names refuses too",
		Fix:     "Run ovenlight doctor, which says what the node needs, then check again.", Actor: actorAgent}
}

// checkApp inspects how app will look and work in Ovenlight. host is the app node's
// tailnet name, "" when it isn't known.
func checkApp(app App, host string) []Check {
	checks := appChecks(app, host)
	if !reached(checks) {
		return checks
	}
	var page *url.URL
	var body []byte
	// A wide bind leaves the pages as they are, so their findings come with it.
	if !slices.ContainsFunc(checks, func(c Check) bool { return c.Status == statusFail && c.ID != "bind-address" }) {
		var found []Check
		page, body, found = fetchHome(app, host)
		checks = append(checks, found...)
		if page != nil {
			viewport, hrefs := parseLayout(body)
			sheets := readStylesheets(app, host, page, hrefs)
			checks = append(checks, layoutChecks(viewport, body, sheets)...)
			for _, c := range []*Check{overscrollCheck(body, sheets), loginCheck(page, body)} {
				if c != nil {
					checks = append(checks, *c)
				}
			}
			checks = append(checks, localURLCheck(body))
		}
		checks = append(checks, discoveryChecks(app, host)...)
	}
	if up := findID(checks, "upstream"); up != nil && up.unanswered != "" {
		// Each request would wait as long for nothing.
		checks = append(checks, unanswered("served-files", servedFilesWhat, up.unanswered), unanswered("cors", corsWhat, up.unanswered),
			unanswered("debug-mode", debugWhat, up.unanswered))
	} else {
		checks = append(checks, servedFilesCheck(app, host, page, body), corsCheck(app), debugCheck(app, host))
	}
	for i := range checks {
		checks[i].App = app.Slug
	}
	return checks
}

// maxProbeBody is how much of an answer to a probe check reads.
const maxProbeBody = 64 << 10

// series is the requests one check sends the app, in turn, as the phone would, with the
// tailnet name in Host. When the app refuses the first of them with 403, such as a dev
// server's host check, the series asks again with 127.0.0.1 and goes on with it, so that
// finding comes with the others rather than after fixing it. After a request the app
// doesn't answer, such as one that times out, it sends no more.
type series struct {
	app   App
	host  string
	asked bool // the app has answered a request
	// stalled is the request the app didn't answer, such as "GET /.env (no answer in 5s)".
	stalled string
	// answers counts the requests the app answered, and refusals those it answered with an
	// error that says it didn't serve them at all; refusal is the first of those.
	answers, refusals int
	refusal           string
}

// get sends method for path, and returns the answer, with ok false when there is none.
func (s *series) get(method, path string, header http.Header) (resp *http.Response, body []byte, ok bool) {
	for s.stalled == "" {
		req, err := appRequest(s.app, s.host, path)
		if err != nil {
			return nil, nil, false
		}
		req.Method = method
		for name, values := range header {
			req.Header[name] = values
		}
		resp, err := appClient.Do(req)
		if err != nil {
			s.stalled = method + " " + clip(path, 80) + " (" + noAnswer(err) + ")"
			return nil, nil, false
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, maxProbeBody))
		resp.Body.Close()
		first := !s.asked
		s.asked = true
		if first && resp.StatusCode == http.StatusForbidden && s.host != "" {
			s.host = ""
			continue
		}
		s.answers++
		// A host allowlist answers 400 (Django) or 421; a broken app or proxy answers 5xx.
		if code := resp.StatusCode; code == http.StatusBadRequest || code == http.StatusMisdirectedRequest || code >= 500 {
			s.refusals++
			if s.refusal == "" {
				s.refusal = method + " " + clip(path, 80) + " returned " + clip(resp.Status, 80)
			}
		}
		return resp, body, true
	}
	return nil, nil, false
}

// noAnswer says why a request to the app got no answer, such as "no answer in 5s" or
// "EOF".
func noAnswer(err error) string {
	var urlErr *url.Error
	switch {
	case !errors.As(err, &urlErr):
	case urlErr.Timeout():
		return "no answer in " + appClient.Timeout.String()
	default:
		err = urlErr.Err
	}
	return clip(err.Error(), 200)
}

// unchecked is the finding of a check whose series can't tell, as the app didn't answer a
// request or refused every one, and nil when it can. what is what the check finds out.
func (s *series) unchecked(id, what string) *Check {
	switch {
	case s.stalled != "":
		c := unanswered(id, what, s.stalled)
		return &c
	case s.answers > 0 && s.refusals == s.answers:
		return &Check{ID: id, Status: statusWarn, Actor: actorAgent,
			Message: "the app refused every request this check sent, such as " + s.refusal + ", so " + what + " wasn't checked",
			Fix:     "Check the app's own log for why it refuses them, such as a Host it doesn't allow (ALLOWED_HOSTS in Django), then check again."}
	}
	return nil
}

// unanswered is the finding of a check that wasn't made because the app didn't answer
// request.
func unanswered(id, what, request string) Check {
	return Check{ID: id, Status: statusWarn, Actor: actorAgent,
		Message: "the app didn't answer " + request + ", so " + what + " wasn't checked",
		Fix:     "Check the app's own log: it accepts connections but doesn't answer requests. Then check again."}
}

// What the checks that send a series of requests find out, for unchecked.
const (
	servedFilesWhat = "whether it serves its own files"
	corsWhat        = "whether it lets other websites read its answers (CORS)"
	debugWhat       = "whether it shows a framework's debug pages"
)

// envLine matches a line of a .env file: NAME=value, maybe after export.
var envLine = regexp.MustCompile(`(?m)^[ \t]*(?:export[ \t]+)?[A-Za-z_][A-Za-z0-9_]*=`)

// servesFile reports whether GET path answers with a body that looks like the file, not
// a page the app answers every path with.
func servesFile(s *series, path string, looksLike func([]byte) bool) bool {
	resp, body, ok := s.get(http.MethodGet, path, nil)
	if !ok || resp.StatusCode != http.StatusOK {
		return false
	}
	return !isHTML(resp, body) && looksLike(body)
}

// isHTML reports whether an answer is a web page, by its Content-Type or, without one,
// by what its body looks like.
func isHTML(resp *http.Response, body []byte) bool {
	contentType := resp.Header.Get("Content-Type")
	if contentType == "" {
		contentType = http.DetectContentType(body)
	}
	mediaType, _, _ := mime.ParseMediaType(contentType)
	return mediaType == "text/html" || mediaType == "application/xhtml+xml"
}

// isPackage reports whether a body is a package.json.
func isPackage(body []byte) bool {
	var pkg map[string]json.RawMessage
	if json.Unmarshal(body, &pkg) != nil {
		return false
	}
	_, name := pkg["name"]
	_, deps := pkg["dependencies"]
	_, scripts := pkg["scripts"]
	return name || deps || scripts
}

// Bounds on servedFilesCheck: the requests it sends, and the paths the home page's assets
// are under it asks at.
const (
	maxServedProbes  = 40
	maxAssetPrefixes = 3
)

// mountPrefixes are paths an app commonly serves a folder at, such as Django's
// STATIC_ROOT, staticfiles/, at /static/.
var mountPrefixes = []string{"/static/", "/assets/", "/media/", "/public/"}

// publicExtensions and publicNames are files a site serves on purpose.
var (
	publicExtensions = []string{".html", ".htm", ".css", ".js", ".mjs", ".map", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".ico", ".webmanifest", ".woff", ".woff2"}
	publicNames      = []string{"manifest.json", "robots.txt", ".DS_Store"}
)

// isDatabase reports whether a file's name is a database's, or one of SQLite's journals.
func isDatabase(name string) bool {
	name = strings.TrimSuffix(strings.TrimSuffix(strings.ToLower(name), "-wal"), "-shm")
	return slices.ContainsFunc([]string{".db", ".sqlite", ".sqlite3"}, func(ext string) bool { return strings.HasSuffix(name, ext) })
}

// ownFile is a file in the app's folder: its path there, and its first bytes, which an
// answer serving it starts with. A file shorter than ownHead is all in head, and an
// answer serving it is head and no more: a few bytes, such as a blank line, can start
// any page.
type ownFile struct {
	rel  string
	head []byte
}

// ownHead is how many of an ownFile's first bytes it keeps.
const ownHead = 16

// serves reports whether an answer's body serves the file.
func (f ownFile) serves(body []byte) bool {
	if len(f.head) < ownHead {
		return bytes.Equal(body, f.head)
	}
	return bytes.HasPrefix(body, f.head)
}

// ownFiles are the files servedFilesCheck asks for, but those a site serves on purpose:
// the database files at the top of the app's folder and a folder down, and the .env, then
// the other files at the top, then the other dotfiles, which some servers hide. It doesn't
// look in a folder that holds more than an app's, such as the home folder, or one macOS
// asks before reading.
func ownFiles(dir string) []ownFile {
	home, _ := os.UserHomeDir()
	same := func(a, b string) bool {
		x, errX := os.Stat(a)
		y, errY := os.Stat(b)
		return errX == nil && errY == nil && os.SameFile(x, y)
	}
	if dir == "" || same(dir, "/") || same(dir, home) || inFolder(dir, filepath.Join(home, "Library")) || protectedFolder(dir) != "" {
		return nil
	}
	var ranked [3][]ownFile
	add := func(rel string, rank int) {
		// A file of blank space has nothing to give away, nor anything to tell it by.
		if head := fileHead(filepath.Join(dir, rel), ownHead); len(bytes.TrimSpace(head)) > 0 {
			ranked[rank] = append(ranked[rank], ownFile{rel, head})
		}
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		name := e.Name()
		switch {
		case e.IsDir():
			sub, _ := os.ReadDir(filepath.Join(dir, name))
			for _, f := range sub {
				if f.Type().IsRegular() && isDatabase(f.Name()) {
					add(name+"/"+f.Name(), 0)
				}
			}
		case !e.Type().IsRegular() || slices.Contains(publicNames, name) || slices.Contains(publicExtensions, strings.ToLower(filepath.Ext(name))):
		case isDatabase(name) || strings.HasPrefix(name, ".env"):
			add(name, 0)
		case !strings.HasPrefix(name, "."):
			add(name, 1)
		default:
			add(name, 2)
		}
	}
	return slices.Concat(ranked[:]...)
}

// assetRef matches a src or href attribute's value.
var assetRef = regexp.MustCompile(`(?i)\b(?:src|href)\s*=\s*["']?([^"'\s>]+)`)

// servedPrefixes are the paths servedFilesCheck asks for the app's files under: /, the
// folders of the first assets the home page (page and body, nil when it wasn't read) links
// on its own host, such as /static/, and mountPrefixes.
func servedPrefixes(page *url.URL, body []byte) []string {
	prefixes := []string{"/"}
	for _, m := range assetRef.FindAllSubmatch(body, -1) {
		u, err := page.Parse(string(m[1]))
		if err != nil || origin(u) != origin(page) || len(prefixes) > maxAssetPrefixes {
			continue
		}
		if top, _, ok := strings.Cut(strings.TrimPrefix(u.Path, "/"), "/"); ok && top != "" && !slices.Contains(prefixes, "/"+top+"/") {
			prefixes = append(prefixes, "/"+top+"/")
		}
	}
	prefixes = append(prefixes, mountPrefixes...)
	slices.Sort(prefixes[1:])
	return slices.Compact(prefixes)
}

// servedFilesCheck fails when the app serves its own files, such as a static server
// started in its folder, or a folder of its data at /static/: then a guest can read the
// .env, the data files and the server's source, not just the pages. It asks for the files
// in the app's folder (see ownFiles) under each of servedPrefixes, a file at its path in
// the folder and, a folder down, at its path in that folder, and counts an answer that
// starts as the file does, unless the answer to a path that can't exist starts so too, as
// a page route that answers every path does when it renders a template from the folder.
// Without the folder, it asks for /.env and /package.json.
func servedFilesCheck(app App, host string, page *url.URL, body []byte) Check {
	c := Check{ID: "served-files", Status: statusFail, Actor: actorAgent,
		Fix: "Serve only a public folder with the pages: build with vite build and serve dist/, or keep the pages in public/ and serve that, " +
			"such as express.static('public'), or http.FileServer(http.Dir(\"public\")) in Go. Keep data files and the server's code outside it, such as in data/ next to public/. " +
			"Never serve the app's folder itself, as express.static('.'), http.Dir(\".\") or python -m http.server there do."}
	const exposed = ", so anyone who opens the app can read its source, secrets and data"
	files := ownFiles(app.Dir)
	s := &series{app: app, host: host}
	if len(files) == 0 {
		switch {
		case servesFile(s, "/.env", envLine.Match):
			c.Message = "GET /.env returns a .env file, so the app serves its folder" + exposed
		// Express hides dotfiles, so there /.env answers 404 though the folder is served.
		case servesFile(s, "/package.json", isPackage):
			c.Message = "GET /package.json returns a package.json, so the app serves its folder" + exposed
		default:
			if u := s.unchecked(c.ID, servedFilesWhat); u != nil {
				return *u
			}
			c.Status, c.Actor, c.Fix, c.Message = statusOK, "", "", "the app serves neither /.env nor /package.json"
		}
		return c
	}
	type ask struct {
		path string
		file ownFile
	}
	var order []ask // each prefix takes its turn, so the bound on requests leaves out the least likely files
	seen := map[string]bool{}
	prefixes := servedPrefixes(page, body)
	for _, f := range files {
		names := []string{f.rel}
		if _, rest, ok := strings.Cut(f.rel, "/"); ok { // a folder down, as when that folder is served
			names = append(names, rest)
		}
		for _, name := range names {
			for _, prefix := range prefixes {
				if path := prefix + name; !seen[path] { // public/app.db a folder down is app.db at the top
					seen[path] = true
					order = append(order, ask{path, f})
				}
			}
		}
	}
	var anyPath []byte
	if resp, got, ok := s.get(http.MethodGet, "/ovenlight-check-"+strings.ToLower(rand.Text()), nil); ok && resp.StatusCode == http.StatusOK {
		anyPath = got
	}
	var served []string
	for _, a := range order[:min(len(order), maxServedProbes)] {
		resp, got, ok := s.get(http.MethodGet, (&url.URL{Path: a.path}).EscapedPath(), nil)
		if ok && resp.StatusCode == http.StatusOK && a.file.serves(got) && !bytes.HasPrefix(anyPath, a.file.head) {
			if served = append(served, "GET "+clip(a.path, 80)+" returns "+clip(a.file.rel, 80)); len(served) == 3 {
				break
			}
		}
	}
	if len(served) == 0 {
		if u := s.unchecked(c.ID, servedFilesWhat); u != nil {
			return *u
		}
		c.Status, c.Actor, c.Fix = statusOK, "", ""
		c.Message = fmt.Sprintf("the app serves none of the files in its folder it was asked for (%d requests)", s.answers)
		return c
	}
	c.Message = strings.Join(served, "; ") + ", from the app's folder" + exposed
	return c
}

// fileHead returns up to n of the file's first bytes, none when it can't be read.
func fileHead(path string, n int) []byte {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	head := make([]byte, n)
	read, _ := io.ReadFull(f, head)
	return head[:read]
}

// debugPages match what a framework's debug mode shows: Django's page for an error or a
// path it doesn't have, and Werkzeug's debugger, which Flask's debug mode serves.
var debugPages = []struct {
	name    string
	pattern *regexp.Regexp
}{
	{"Django's debug page (DEBUG = True)", regexp.MustCompile(`seeing this error because you have (?:<code>)?DEBUG = True`)},
	{"Werkzeug's debugger (Flask's debug mode)", regexp.MustCompile(`Werkzeug Debugger|[?&]__debugger__=`)},
}

// debugCheck fails when the app runs in a framework's debug mode, whose error pages show
// whoever opens the app its source and settings. It asks for a path the app doesn't
// have, which Django answers with its debug page, and for /console, which Werkzeug's
// debugger serves to 127.0.0.1 (the tailnet name it refuses, but its error pages show
// the code to anyone).
func debugCheck(app App, host string) Check {
	s := &series{app: app, host: host}
	for _, path := range []string{"/ovenlight-check-missing", "/console"} {
		if path == "/console" {
			s.host = ""
		}
		_, body, ok := s.get(http.MethodGet, path, nil)
		if !ok {
			continue
		}
		for _, page := range debugPages {
			if page.pattern.Match(body) {
				return Check{ID: "debug-mode", Status: statusFail, Actor: actorAgent,
					Message: "GET " + path + " shows " + page.name + ", so an error shows whoever opens the app its source code and settings",
					Fix:     "Turn debug mode off: in Django, DEBUG = False (with '.ts.net' in ALLOWED_HOSTS); in Flask, debug=False in app.run, and no --debug or FLASK_DEBUG=1."}
			}
		}
	}
	if u := s.unchecked("debug-mode", debugWhat); u != nil {
		return *u
	}
	return Check{ID: "debug-mode", Status: statusOK, Message: "the app shows no framework's debug pages"}
}

// corsCheck fails when the app lets another website read what it answers: when its
// Access-Control-Allow-Origin is * or names that website. Then a page open in this Mac's
// browser can call the app's port and read the answers, with identity headers of its
// own when Access-Control-Allow-Headers lets it. It asks / and a path under /api/, where
// an API's CORS middleware often sits, as that page would: at 127.0.0.1, without
// identity. Other Access-Control-* headers grant nothing without that one, and a
// framework's CORS middleware sends some by default.
func corsCheck(app App) Check {
	const other = "https://example.com"
	var found []string
	headers := false // the preflight lets the other website send identity headers
	s := &series{app: app}
	for _, path := range []string{"/", "/api/ovenlight-check"} {
		for _, ask := range []struct {
			method string
			header http.Header
		}{
			{http.MethodGet, http.Header{"Origin": {other}}},
			{http.MethodOptions, http.Header{"Origin": {other}, "Access-Control-Request-Method": {"POST"}, "Access-Control-Request-Headers": {"ovenlight-user-id"}}},
		} {
			resp, _, ok := s.get(ask.method, path, ask.header)
			if !ok {
				continue
			}
			allowed := strings.TrimSpace(resp.Header.Get("Access-Control-Allow-Origin"))
			if allowed != "*" && !strings.EqualFold(allowed, other) {
				continue
			}
			if h := ask.method + " " + path + ": Access-Control-Allow-Origin: " + clip(allowed, 80); !slices.Contains(found, h) {
				found = append(found, h)
			}
			for _, name := range strings.Split(resp.Header.Get("Access-Control-Allow-Headers"), ",") {
				name = strings.TrimSpace(name)
				headers = headers || name == "*" || strings.EqualFold(name, "ovenlight-user-id")
			}
		}
	}
	if u := s.unchecked("cors", corsWhat); len(found) == 0 && u != nil {
		return *u
	}
	if len(found) == 0 {
		return Check{ID: "cors", Status: statusOK, Message: "the app lets no other website read what it answers (CORS)"}
	}
	also := ""
	if headers {
		also = ", and Access-Control-Allow-Headers lets it send identity headers of its own, such as Ovenlight-User-Id"
	}
	return Check{ID: "cors", Status: statusFail, Actor: actorAgent,
		Message: "the app answers a request from another website, " + other + ", with CORS headers that let it in (" + strings.Join(found[:min(len(found), 3)], "; ") +
			"), so another website, such as one open in this Mac's browser, can call the app and read its answers" + also,
		Fix: "Send no CORS headers: the app's pages come from its own host and need none. In Vite, set server: { cors: false } (and preview: { cors: false }); in Express, remove the cors() middleware."}
}

// homeURL is the app's home page as the phone opens it, or on 127.0.0.1 without host.
func homeURL(port int, host string) *url.URL {
	if host == "" {
		return &url.URL{Scheme: "http", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), Path: "/"}
	}
	return &url.URL{Scheme: "https", Host: host, Path: "/"}
}

// origin is u's scheme, host and port, with the scheme's default port spelled out.
func origin(u *url.URL) string {
	port := u.Port()
	if port == "" {
		port = map[string]string{"http": "80", "https": "443"}[u.Scheme]
	}
	return u.Scheme + "://" + strings.ToLower(u.Hostname()) + ":" + port
}

// clip shortens text the app chose, such as a path or a viewport, to at most n runes,
// on one line, before a check repeats it.
func clip(s string, n int) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, s)
	if r := []rune(s); len(r) > n {
		return string(r[:n]) + "..."
	}
	return s
}

func isLocalHost(host string) bool {
	return slices.Contains([]string{"localhost", "127.0.0.1", "0.0.0.0", "::1"}, strings.ToLower(host))
}

// fetchHome reads the home page as the phone does: GET / with the tailnet Host,
// following redirects that stay on the app. It returns the page's URL and body, nil
// when there is no page to read, with checks on how it got there.
func fetchHome(app App, host string) (*url.URL, []byte, []Check) {
	home := homeURL(app.Port, host)
	page := home
	for hops := 0; ; hops++ {
		req, err := appRequest(app, host, page.RequestURI())
		if err != nil {
			return nil, nil, []Check{{ID: "home-page", Status: statusFail, Message: err.Error()}}
		}
		resp, err := appClient.Do(req)
		if err != nil {
			return nil, nil, []Check{{ID: "home-page", Status: statusFail, Message: "GET " + clip(page.RequestURI(), 80) + " failed: " + clip(err.Error(), 200),
				Fix: "Check the app's own log.", Actor: actorAgent}}
		}
		body, err := io.ReadAll(io.LimitReader(resp.Body, maxMetaBody))
		resp.Body.Close()
		if err != nil {
			return nil, nil, []Check{{ID: "home-page", Status: statusFail, Message: "reading " + clip(page.RequestURI(), 80) + " failed: " + clip(err.Error(), 200),
				Fix: "Check the app's own log.", Actor: actorAgent}}
		}
		location := resp.Header.Get("Location")
		if resp.StatusCode >= 300 && resp.StatusCode < 400 && location != "" {
			next, err := page.Parse(location)
			if err != nil {
				return nil, nil, []Check{{ID: "redirect", Status: statusFail, Message: fmt.Sprintf("GET %s redirects to %q, which isn't a URL", clip(page.RequestURI(), 80), clip(location, 80)),
					Fix: "Redirect to a relative path, such as /app.", Actor: actorAgent}}
			}
			if c := redirectCheck(page, next, home); c != nil {
				return nil, nil, []Check{*c}
			}
			if hops == maxRedirects {
				return nil, nil, []Check{{ID: "redirect", Status: statusFail, Message: fmt.Sprintf("GET / redirects more than %d times", maxRedirects),
					Fix: "Make / lead to a page in a redirect or two.", Actor: actorAgent}}
			}
			page = next
			continue
		}
		var checks []Check
		if page != home {
			checks = append(checks, Check{ID: "redirect", Status: statusOK, Message: "GET / redirects to " + clip(page.RequestURI(), 80) + ", on the app's own host"})
		}
		if resp.StatusCode < 200 || resp.StatusCode > 299 {
			return nil, nil, append(checks, Check{ID: "home-page", Status: statusFail, Message: "the home page, " + clip(page.RequestURI(), 80) + ", returned " + clip(resp.Status, 80) + withoutOwner(app, host),
				Fix: "Ovenlight opens the app at /, so / must lead to a page.", Actor: actorAgent})
		}
		contentType := resp.Header.Get("Content-Type")
		if contentType == "" {
			contentType = http.DetectContentType(body)
		}
		if mediaType, _, _ := mime.ParseMediaType(contentType); mediaType != "text/html" && mediaType != "application/xhtml+xml" {
			return nil, nil, append(checks, Check{ID: "home-page", Status: statusWarn,
				Message: fmt.Sprintf("the home page, %s, is %s, not HTML, so its layout wasn't checked", clip(page.RequestURI(), 80), orDash(clip(mediaType, 80))),
				Fix:     "Ovenlight opens the app at /, so / should lead to a web page.", Actor: actorAgent})
		}
		return page, body, checks
	}
}

// redirectCheck fails a redirect from page to next that leaves the app at home.
func redirectCheck(page, next, home *url.URL) *Check {
	if origin(next) == origin(home) {
		return nil
	}
	c := &Check{ID: "redirect", Status: statusFail, Actor: actorAgent}
	target := clip(next.Scheme+"://"+next.Host+next.Path, 80) // without the query, which may carry a sign-in request
	from := clip(page.RequestURI(), 80)
	switch {
	case isLocalHost(next.Hostname()):
		c.Message = fmt.Sprintf("GET %s redirects to %s, which on the phone is the phone itself", from, target)
		c.Fix = "Redirect to a relative path, such as /app, or build the URL from the request's Host."
	case strings.EqualFold(next.Hostname(), home.Hostname()):
		c.Message = fmt.Sprintf("GET %s redirects to %s, but the app's node serves only https://%s/", from, target, home.Host)
		c.Fix = "Redirect to a relative path, such as /app, or trust X-Forwarded-Proto from 127.0.0.1 (in Express, app.set('trust proxy', 'loopback'))."
	default:
		c.Message = fmt.Sprintf("GET %s redirects to %s, another website, which Ovenlight opens in a Safari sheet that doesn't come back to the app", from, target)
		c.Fix = "Keep / on the app. If this is a sign-in, drop it: the connector already says who is calling in the Ovenlight-User-Id header."
	}
	return c
}

// parseLayout reads the home page's <meta name="viewport"> (nil without one) and the
// stylesheets it links.
func parseLayout(body []byte) (viewport *string, sheets []string) {
	z := html.NewTokenizer(bytes.NewReader(body))
	for {
		switch z.Next() {
		case html.ErrorToken:
			return viewport, sheets
		case html.StartTagToken, html.SelfClosingTagToken:
			name, hasAttr := z.TagName()
			if !hasAttr {
				continue
			}
			attrs := tagAttrs(z)
			switch string(name) {
			case "meta":
				if strings.EqualFold(attrs["name"], "viewport") && viewport == nil {
					content := strings.TrimSpace(attrs["content"])
					viewport = &content
				}
			case "link":
				href := strings.TrimSpace(attrs["href"])
				if href != "" && slices.Contains(strings.Fields(strings.ToLower(attrs["rel"])), "stylesheet") {
					sheets = append(sheets, href)
				}
			}
		}
	}
}

// coversScreen reports whether a viewport's content sets viewport-fit=cover.
func coversScreen(viewport string) bool {
	for _, part := range strings.FieldsFunc(viewport, func(r rune) bool { return r == ',' || r == ';' }) {
		key, value, _ := strings.Cut(part, "=")
		if strings.EqualFold(strings.TrimSpace(key), "viewport-fit") && strings.EqualFold(strings.TrimSpace(value), "cover") {
			return true
		}
	}
	return false
}

// stylesheet is a stylesheet the home page links, by its path. failed says why it
// didn't load, such as "404 Not Found", and is "" when it did.
type stylesheet struct {
	path   string
	css    []byte
	failed string
}

// readStylesheets reads the first stylesheets the page links on its own host.
func readStylesheets(app App, host string, page *url.URL, hrefs []string) []stylesheet {
	var sheets []stylesheet
	for _, href := range hrefs {
		u, err := page.Parse(href)
		if err != nil || origin(u) != origin(page) {
			continue
		}
		if len(sheets) == maxStylesheets {
			break
		}
		req, err := appRequest(app, host, u.RequestURI())
		if err != nil {
			continue
		}
		resp, err := appClient.Do(req)
		if err != nil {
			sheets = append(sheets, stylesheet{path: u.Path, failed: err.Error()})
			continue
		}
		css, _ := io.ReadAll(io.LimitReader(resp.Body, maxMetaBody))
		resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			sheets = append(sheets, stylesheet{path: u.Path, css: css})
		} else {
			sheets = append(sheets, stylesheet{path: u.Path, failed: "GET returned " + resp.Status})
		}
	}
	return sheets
}

// layoutChecks check that the page fits the phone's screen: Ovenlight's web view is full
// screen and adds no insets, so the page pads itself with env(safe-area-inset-*).
func layoutChecks(viewport *string, body []byte, sheets []stylesheet) []Check {
	c := Check{ID: "viewport", Actor: actorAgent}
	switch {
	case viewport == nil:
		c.Status, c.Message = statusFail, `the home page has no <meta name="viewport">, so the phone lays it out 980 px wide and shrinks it to fit`
		c.Fix = `Add <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"> to the page's <head>, and pad its edges with env(safe-area-inset-top) and the other insets.`
		return []Check{c}
	case !coversScreen(*viewport):
		c.Status = statusWarn
		c.Message = fmt.Sprintf("the viewport (%s) doesn't set viewport-fit=cover. Ovenlight shows the page full screen, under the status bar and the home indicator; with viewport-fit=cover the page fills the screen and env(safe-area-inset-*) tells it how far to pad", clip(*viewport, 80))
		c.Fix = "Add viewport-fit=cover to the viewport meta tag's content, and pad the page's edges with env(safe-area-inset-top) and the other insets."
		return []Check{c}
	}
	c.Status, c.Message, c.Actor = statusOK, "viewport: "+clip(*viewport, 80), ""
	return []Check{c, safeAreaCheck(body, sheets)}
}

// safeAreaCheck looks for safe-area-inset in the page and the stylesheets it links.
func safeAreaCheck(body []byte, sheets []stylesheet) Check {
	const marker = "safe-area-inset"
	c := Check{ID: "safe-area"}
	if bytes.Contains(body, []byte(marker)) {
		c.Status, c.Message = statusOK, "the home page pads with env(safe-area-inset-*)"
		return c
	}
	for _, sheet := range sheets {
		if bytes.Contains(sheet.css, []byte(marker)) {
			c.Status, c.Message = statusOK, clip(sheet.path, 80)+" pads with env(safe-area-inset-*)"
			return c
		}
	}
	c.Status, c.Actor = statusWarn, actorAgent
	for _, sheet := range sheets {
		if sheet.failed != "" {
			c.Message = fmt.Sprintf("the home page links the stylesheet %s, which didn't load (%s), so the page goes without it and its safe-area padding wasn't checked", clip(sheet.path, 80), clip(sheet.failed, 200))
			c.Fix = "Serve the stylesheet at " + clip(sheet.path, 80) + ", or link the path the app serves it at."
			return c
		}
	}
	c.Message = "neither the home page nor the stylesheets it links mention safe-area-inset, so its edges may sit under the status bar and the home indicator"
	c.Fix = "Pad the page's edges with env(safe-area-inset-top), -right, -bottom and -left, for example on body. CSS that a script loads isn't read here; if it pads already, nothing needs to change."
	return c
}

// overscrollNone matches overscroll-behavior (or -y) set to none on html, body or :root,
// in a style rule or a style attribute.
var overscrollNone = regexp.MustCompile(`(?i)(?:(?:^|[\s{}>/,])(?:html|body|:root)(?:[.#:\[][^\s,{]*)?\s*(?:,\s*(?:html|body|:root)(?:[.#:\[][^\s,{]*)?\s*)*\{[^}]*?|<(?:html|body)\b[^>]*?\bstyle\s*=\s*(?:"[^"]*?|'[^']*?))overscroll-behavior(?:-y)?\s*:\s*none\b`)

// overscrollCheck warns when the page keeps the page from moving when pulled down at the
// top, which is how people reach Ovenlight's menu.
func overscrollCheck(body []byte, sheets []stylesheet) *Check {
	where := ""
	if overscrollNone.Match(body) {
		where = "the home page"
	}
	for _, sheet := range sheets {
		if where == "" && overscrollNone.Match(sheet.css) {
			where = clip(sheet.path, 80)
		}
	}
	if where == "" {
		return nil
	}
	return &Check{ID: "overscroll", Status: statusWarn, Actor: actorAgent,
		Message: where + " sets overscroll-behavior: none on html or body, so the page doesn't move when pulled down at the top",
		Fix:     "Remove it: pulling down at the top is how people reach Ovenlight's menu (Reload, Send Feedback)."}
}

// passwordField matches a password input.
var passwordField = regexp.MustCompile(`(?i)<input\b[^>]*\btype\s*=\s*["']?password\b`)

// loginCheck warns when the home page is a sign-in, by where / redirects or a password
// field on the page it lands on: Ovenlight says who is calling already.
func loginCheck(page *url.URL, body []byte) *Check {
	what := ""
	if path := strings.ToLower(page.Path); path != "/" && (strings.Contains(path, "login") || strings.Contains(path, "signin") || strings.Contains(path, "sign-in")) {
		what = "GET / redirects to " + clip(page.Path, 80) + ", a sign-in page"
	} else if passwordField.Match(body) {
		what = "the home page, " + clip(page.Path, 80) + ", asks for a password"
	} else {
		return nil
	}
	return &Check{ID: "login", Status: statusWarn, Actor: actorAgent,
		Message: what + ", though only people the owner shares the app with reach it, and the connector says who each one is",
		Fix:     "Ovenlight already says who is calling; drop the login and use Ovenlight-User-Id, the header the connector sends with each request (Ovenlight-User has the person's name)."}
}

// localURL matches an absolute URL naming this Mac's loopback, which on the phone is the
// phone itself.
var localURL = regexp.MustCompile(`(?i)\b(?:https?|wss?)://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(?::\d+)?[^\s"'<>)\x60]*`)

// localURLCheck names the origins of the localhost URLs the page has, not the whole
// URLs, which carry whatever the page says.
func localURLCheck(body []byte) Check {
	var found []string
	for _, m := range localURL.FindAll(body, -1) {
		u, err := url.Parse(string(m))
		if err != nil {
			continue
		}
		if s := strings.ToLower(u.Scheme + "://" + u.Host); !slices.Contains(found, s) && len(found) < 3 {
			found = append(found, s)
		}
	}
	if len(found) == 0 {
		return Check{ID: "local-urls", Status: statusOK, Message: "the home page names no localhost URLs"}
	}
	return Check{ID: "local-urls", Status: statusWarn,
		Message: "the home page names " + strings.Join(found, ", ") + ", which on the phone means the phone itself",
		Fix:     "Use relative URLs, such as /api, or build them from location; the page loads from the app's tailnet name.", Actor: actorAgent}
}

// discoveryChecks read the app as the connector does to list it in Ovenlight (see
// fetchSiteManifest), and report on its manifest, icon and theme color.
func discoveryChecks(app App, host string) []Check {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	upstream := &url.URL{Scheme: "http", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(app.Port))}
	d, err := discover(ctx, metaClient, upstream)
	if err != nil {
		return []Check{{ID: "discovery", Status: statusWarn,
			Message: "the connector can't read the home page to find the app's icon and color (" + clip(err.Error(), 200) + "), so Ovenlight lists the app by name only",
			Fix:     "The connector reads / from 127.0.0.1 without identity headers, following redirects only on 127.0.0.1: answer it with the page, or redirect to a relative path.", Actor: actorAgent}}
	}
	site := buildSiteManifest(app, d.pageURL, d.page, d.manifestURL, d.manifest)

	manifest := Check{ID: "manifest", Status: statusOK}
	switch {
	case d.page.Manifest == "":
		manifest.Status, manifest.Message, manifest.Actor = statusWarn, `the home page links no web manifest (<link rel="manifest">)`, actorAgent
		manifest.Fix = `Add <link rel="manifest" href="/manifest.webmanifest"> and serve a manifest with theme_color and a 512 px PNG in icons, all with relative URLs.`
	case d.manifestErr != nil:
		manifest.Status, manifest.Message, manifest.Actor = statusWarn, "the web manifest isn't used: "+clip(d.manifestErr.Error(), 200), actorAgent
		manifest.Fix = "Link the manifest with a relative URL, such as /manifest.webmanifest, and serve it from the app as JSON."
	default:
		manifest.Message = "web manifest at " + clip(d.manifestURL.Path, 80)
	}
	return []Check{manifest, iconCheck(app, host, d, site), themeCheck(site)}
}

func iconCheck(app App, host string, d *discovery, site SiteManifest) Check {
	c := Check{ID: "icon", Actor: actorAgent}
	const fix = `List a 512 px PNG in the manifest's icons with a relative src, such as {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png"}.`
	if site.Icon == nil {
		reason := "neither the web manifest nor an apple-touch-icon names one"
		switch {
		case d.manifest != nil && len(d.manifest.Icons) > 0 && d.manifest.bestIcon() == "":
			reason = "the manifest lists only SVG icons, which iOS can't use"
		case d.manifest != nil && d.manifest.bestIcon() != "":
			reason = "the manifest's icon " + clip(d.manifest.bestIcon(), 80) + " isn't a relative URL"
		case d.page.TouchIcon != "":
			reason = "the apple-touch-icon " + clip(d.page.TouchIcon, 80) + " isn't a relative URL"
		}
		c.Status, c.Message, c.Fix = statusWarn, "Ovenlight shows the app without an icon: "+reason, fix
		return c
	}
	req, err := appRequest(app, host, *site.Icon)
	path := clip(*site.Icon, 80)
	if err != nil {
		c.Status, c.Message, c.Fix = statusWarn, "the icon "+path+" can't be read: "+clip(err.Error(), 200), fix
		return c
	}
	resp, err := appClient.Do(req)
	if err != nil {
		c.Status, c.Message, c.Fix = statusWarn, "the icon "+path+" can't be read: "+clip(err.Error(), 200), fix
		return c
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		c.Status, c.Message = statusWarn, "the icon "+path+" can't be read: GET returned "+clip(resp.Status, 80)
		c.Fix = "Serve the icon at " + path + ", or point the manifest at one the app serves."
		return c
	}
	img, _, err := image.DecodeConfig(io.LimitReader(resp.Body, maxMetaBody))
	if err != nil {
		c.Status, c.Message, c.Actor = statusOK, "icon "+path+" (not a PNG, JPEG or GIF, so its size wasn't measured)", ""
		return c
	}
	size := min(img.Width, img.Height)
	if size < 180 {
		c.Status = statusWarn
		c.Message = fmt.Sprintf("the icon %s is %dx%d, so it looks blurry in Ovenlight; 180 px is the least, 512 px looks sharpest", path, img.Width, img.Height)
		c.Fix = fix
		return c
	}
	c.Status, c.Message, c.Actor = statusOK, fmt.Sprintf("icon %s, %dx%d", path, img.Width, img.Height), ""
	if size < 512 {
		c.Message += " (512 px looks sharpest)"
	}
	return c
}

// hexColor matches the theme colors Ovenlight reads: #rgb and #rrggbb.
var hexColor = regexp.MustCompile(`^#?(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$`)

func themeCheck(site SiteManifest) Check {
	const fix = `Add "theme_color": "#rrggbb" to the web manifest, or <meta name="theme-color" content="#rrggbb"> to the home page.`
	switch {
	case site.ThemeColor == nil:
		return Check{ID: "theme-color", Status: statusWarn,
			Message: `the app has no theme color, in the manifest's theme_color or a <meta name="theme-color">, so Ovenlight uses its own`,
			Fix:     fix, Actor: actorAgent}
	case !hexColor.MatchString(strings.TrimSpace(*site.ThemeColor)):
		return Check{ID: "theme-color", Status: statusWarn,
			Message: fmt.Sprintf("the theme color %q isn't #rgb or #rrggbb, the forms Ovenlight reads, so it uses its own", clip(*site.ThemeColor, 80)),
			Fix:     fix, Actor: actorAgent}
	}
	return Check{ID: "theme-color", Status: statusOK, Message: "theme color " + clip(*site.ThemeColor, 80)}
}
