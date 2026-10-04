package main

import (
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// Check is one doctor finding. Fix says what to do about anything that isn't ok, Actor
// who can do it, and URL where the person does it, when there is such a page.
type Check struct {
	ID      string `json:"id"`
	App     string `json:"app,omitempty"`
	Status  string `json:"status"` // ok, warn, fail
	Message string `json:"message"`
	Fix     string `json:"fix,omitempty"`
	Actor   string `json:"actor,omitempty"`
	URL     string `json:"url,omitempty"`

	unanswered string // the request the app didn't answer, for checkApp
}

const (
	statusOK   = "ok"
	statusWarn = "warn"
	statusFail = "fail"
)

// Who can apply a fix: a coding agent on this Mac, or only the person (the Tailscale
// admin console, a login link, their iPhone, or a terminal command that asks them).
const (
	actorAgent  = "agent"
	actorPerson = "person"
)

// Tailscale admin console pages that fixes send the person to.
const (
	consoleMachines = "https://console.tailscale.com/admin/machines"
	consoleDNS      = "https://console.tailscale.com/admin/dns"
	consoleKeys     = "https://console.tailscale.com/admin/settings/keys"
)

// checksOK reports whether no check failed.
func checksOK(checks []Check) bool {
	for _, c := range checks {
		if c.Status == statusFail {
			return false
		}
	}
	return true
}

// reportChecks prints checks, or encodes them as {"ok", "checks"} with asJSON, and exits
// 1 when any failed.
func reportChecks(checks []Check, asJSON bool) {
	ok := checksOK(checks)
	if asJSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		enc.Encode(struct {
			OK     bool    `json:"ok"`
			Checks []Check `json:"checks"`
		}{ok, checks})
	} else {
		printChecks(checks, false)
	}
	if !ok {
		os.Exit(1)
	}
}

// bindAddrs lists the addresses of listeners, and those of them that aren't loopback: a
// wildcard or LAN address, reachable around the connector.
func bindAddrs(ls []listener) (addrs, exposed []string) {
	for _, l := range ls {
		if slices.Contains(addrs, l.addr) {
			continue
		}
		addrs = append(addrs, l.addr)
		if ip := net.ParseIP(l.host()); ip == nil || !ip.IsLoopback() {
			exposed = append(exposed, l.addr)
		}
	}
	return addrs, exposed
}

// listener is a process listening on a port, by pid and command name, and an address it
// listens on, such as "127.0.0.1:4317", "*:4317" or "[::1]:4317".
type listener struct {
	pid  int
	name string
	addr string
}

// host is the address the listener listens on without its port, "*" for every address.
func (l listener) host() string {
	host, _, err := net.SplitHostPort(l.addr)
	if err != nil {
		return l.addr
	}
	if host == "0.0.0.0" || host == "::" {
		return "*"
	}
	return host
}

// parseListeners reads `lsof -F pcn` output: a listener for each process and address.
func parseListeners(lsofOutput string) []listener {
	var found []listener
	var cur listener
	for _, line := range strings.Split(lsofOutput, "\n") {
		line = strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(line, "p"):
			cur = listener{}
			cur.pid, _ = strconv.Atoi(line[1:])
		case strings.HasPrefix(line, "c"):
			cur.name = line[1:]
		case strings.HasPrefix(line, "n") && len(line) > 1:
			if l := (listener{cur.pid, cur.name, line[1:]}); !slices.Contains(found, l) {
				found = append(found, l)
			}
		}
	}
	return found
}

// appListeners splits what listens on a port into the app's and the others'. The app's
// are the processes a connection to 127.0.0.1 reaches, which listen on 127.0.0.1 or, with
// none there, on every address, and the rest of their process groups. Another program,
// such as macOS's AirPlay Receiver on *:5000 and *:7000, can listen on every address
// beside an app on 127.0.0.1, and AirPlay Receiver is never the app.
func appListeners(all []listener) (own, others []listener) {
	reached := func(host string) []listener {
		return slices.DeleteFunc(slices.Clone(all), func(l listener) bool { return l.host() != host || airPlay(l) })
	}
	reach := reached("127.0.0.1")
	if len(reach) == 0 {
		reach = reached("*")
	}
	pids, groups := map[int]bool{}, map[int]bool{}
	for _, l := range reach {
		pids[l.pid], groups[processGroup(l.pid)] = true, true
	}
	delete(groups, -1) // no such process
	for _, l := range all {
		if pids[l.pid] || groups[processGroup(l.pid)] {
			own = append(own, l)
		} else {
			others = append(others, l)
		}
	}
	return own, others
}

// airPlay reports whether the listener is macOS's AirPlay Receiver, which ControlCenter
// runs on every address, on 5000 and 7000.
func airPlay(l listener) bool {
	return strings.HasPrefix(l.name, "ControlCe") && l.host() == "*" // lsof may cut the name to 9 letters
}

// airPlayCheck fails when AirPlay Receiver is all that listens on the app's port: it
// answers on 127.0.0.1 too, so the port seems taken by the app.
func airPlayCheck(app App) *Check {
	all, err := listeners(app.Port)
	if err != nil || len(all) == 0 || slices.ContainsFunc(all, func(l listener) bool { return !airPlay(l) }) {
		return nil
	}
	return &Check{ID: "port-listening", App: app.Slug, Status: statusFail, Actor: actorAgent,
		Message: fmt.Sprintf("nothing of the app's listens on 127.0.0.1:%d; AirPlay Receiver (ControlCenter) answers on this port", app.Port),
		Fix:     otherPortFix(app)}
}

// appChecks are the checks that need no daemon: the app itself. host is the app node's
// tailnet name, "" when it isn't known.
func appChecks(app App, host string) []Check {
	var checks []Check
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(app.Port))
	if !accepts("127.0.0.1", app.Port) {
		if accepts("::1", app.Port) {
			return append(checks, Check{ID: "bind-address", App: app.Slug, Status: statusFail, Actor: actorAgent,
				Message: fmt.Sprintf("nothing is listening on %s, but something listens on [::1]:%d, IPv6's loopback: an app that binds localhost may get only ::1, and the connector reaches apps at 127.0.0.1", addr, app.Port),
				Fix:     "Bind 127.0.0.1, not localhost: `app.listen(port, '127.0.0.1')` in Node, `-H 127.0.0.1` for Next.js, `--host 127.0.0.1` for Vite and uvicorn."})
		}
		fix := fmt.Sprintf("Start %s, or publish the port it really uses: ovenlight publish --slug %s --port <n>", app.Name, app.Slug)
		if app.Run != "" {
			fix = fmt.Sprintf("The connector runs %s, and its output says why it isn't listening: ovenlight logs %s. "+
				"The app must listen on $PORT, which the connector sets; a port hard-coded in the code is the usual cause.", app.Name, app.Slug)
		}
		return append(checks, Check{ID: "port-listening", App: app.Slug, Status: statusFail,
			Message: fmt.Sprintf("nothing is listening on %s", addr), Fix: fix, Actor: actorAgent})
	}
	if c := airPlayCheck(app); c != nil {
		return append(checks, *c)
	}
	checks = append(checks, Check{ID: "port-listening", App: app.Slug, Status: statusOK, Message: addr + " accepts connections"})
	return append(checks, bindCheck(app), upstreamCheck(app, host))
}

// accepts reports whether something accepts a connection on the port at host.
func accepts(host string, port int) bool {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), time.Second)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

// bindCheck judges the addresses of the app's processes on its port (see appListeners).
func bindCheck(app App) Check {
	c := Check{ID: "bind-address", App: app.Slug}
	all, err := listeners(app.Port)
	if err != nil {
		c.Status, c.Message = statusWarn, "couldn't check which address the app listens on: "+err.Error()
		return c
	}
	own, _ := appListeners(all)
	addrs, exposed := bindAddrs(own)
	switch {
	case len(addrs) == 0:
		c.Status, c.Message = statusWarn, "couldn't see the listening process (is it running as another user?)"
	case len(exposed) == 0:
		c.Status, c.Message = statusOK, "listens on loopback only ("+strings.Join(addrs, ", ")+")"
	default:
		c.Status = statusFail
		c.Message = fmt.Sprintf("the app listens on %s, so anyone on your network can reach it without going through Ovenlight and forge the identity headers (Ovenlight-User-Id, Ovenlight-Role)", strings.Join(exposed, ", "))
		c.Fix = "Bind it to 127.0.0.1 only: `app.listen(port, '127.0.0.1')` in Node, `-H 127.0.0.1` for Next.js, `--host 127.0.0.1` for Vite and uvicorn, `--bind 127.0.0.1` for python -m http.server, " +
			"`http.ListenAndServe(\"127.0.0.1:\"+os.Getenv(\"PORT\"), mux)` in Go, " +
			"`gunicorn --bind 127.0.0.1:$PORT` (gunicorn binds 0.0.0.0 when PORT is set), `manage.py runserver 127.0.0.1:$PORT` for Django, " +
			"`flask run` without --host (Flask binds 127.0.0.1) or with `--host 127.0.0.1`; some servers ignore HOST"
		if flag := wildcardFlag(app.Run); flag != "" {
			c.Fix = fmt.Sprintf("The app's command passes `%s`: make that address 127.0.0.1 and publish again with the corrected --run. %s", flag, c.Fix)
		}
		c.Actor = actorAgent
	}
	return c
}

// wildcardFlag is the flag in a command that gives a server every address, 0.0.0.0 or ::,
// as written, such as --host 0.0.0.0 or --bind=0.0.0.0:$PORT, or "" when it has none.
func wildcardFlag(run string) string {
	fields := strings.Fields(run)
	for i, field := range fields {
		name, value, joined := strings.Cut(field, "=")
		if !slices.Contains([]string{"--host", "-H", "--hostname", "--bind", "-b"}, name) || (!joined && i+1 == len(fields)) {
			continue
		}
		written := field
		if !joined {
			value, written = fields[i+1], field+" "+fields[i+1]
		}
		host := strings.Trim(value, `"'`)
		if h, _, err := net.SplitHostPort(host); err == nil {
			host = h
		}
		if host == "0.0.0.0" || host == "::" {
			return written
		}
	}
	return ""
}

// appClient asks the app directly, following no redirects.
var appClient = &http.Client{Timeout: 5 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

// withOwner is app with the owner its checks' requests through the tailnet name come
// from: the one the connector reports for the app's node (reply, nil when it isn't
// running), else the one the config records, else none.
func withOwner(app App, cfg *Config, reply *controlReply) App {
	app.owner, app.ownerName = cfg.Owner, cfg.OwnerLabel
	if reply != nil {
		for _, st := range reply.Apps {
			if st.Slug == app.Slug && st.Owner != "" {
				app.owner, app.ownerName = st.Owner, cmp.Or(st.OwnerName, app.ownerName)
			}
		}
	}
	return app
}

// appRequest is a GET for path on the app at 127.0.0.1. With host, the app node's
// tailnet name, it carries the Host and forwarding headers the connector's proxy sends,
// as the phone's requests reach the app, and the owner's identity headers when the owner
// is known (see withOwner). A made-up owner would be no one the app knows.
func appRequest(app App, host, path string) (*http.Request, error) {
	req, err := http.NewRequest(http.MethodGet, fmt.Sprintf("http://127.0.0.1:%d%s", app.Port, path), nil)
	if err != nil {
		return nil, err
	}
	if host != "" {
		if app.owner != "" {
			applyIdentity(req.Header, caller{Identity: Identity{Login: app.owner, Name: app.ownerName}, Role: RoleOwner})
		}
		req.Host = host
		req.Header.Set("X-Forwarded-Host", host)
		req.Header.Set("X-Forwarded-Proto", "https")
	}
	return req, nil
}

func upstreamCheck(app App, host string) Check {
	c := Check{ID: "upstream", App: app.Slug}
	answer := func(host string) (*http.Response, string, error) {
		req, err := appRequest(app, host, "/")
		if err != nil {
			return nil, "", err
		}
		resp, err := appClient.Do(req)
		if err != nil {
			return nil, "", err
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<10))
		return resp, string(body), nil
	}
	resp, body, err := answer(host)
	if err != nil {
		c.Status, c.Message = statusFail, "GET / failed: "+err.Error()
		c.Fix, c.Actor = "Check the app's own log; it accepts connections but doesn't answer HTTP.", actorAgent
		c.unanswered = "GET / (" + noAnswer(err) + ")"
		return c
	}
	status := clip(resp.Status, 80) // the app chose its words
	if resp.StatusCode >= 200 && resp.StatusCode < 400 {
		c.Status, c.Message = statusOK, "GET / returned "+status
		return c
	}
	c.Status, c.Message, c.Actor = statusFail, "GET / returned "+status, actorAgent
	c.Fix = "Ovenlight opens the app at /, so / must answer with a page or a redirect."
	if host == "" {
		return c
	}
	if server, fix := hostRejection(resp.StatusCode, body); fix != "" {
		c.Message = fmt.Sprintf("%s refuses the app's tailnet name, %s, with %s, so Ovenlight shows that error", server, host, status)
		c.Fix = fix
	} else if local, _, err := answer(""); err == nil && local.StatusCode >= 200 && local.StatusCode < 400 {
		c.Message = fmt.Sprintf("GET / returned %s for the app's tailnet name, %s, though %s for 127.0.0.1: the app answers only host names it knows", status, host, clip(local.Status, 80))
		c.Fix = "Allow every .ts.net name in the app's allowed hosts setting, such as ALLOWED_HOSTS in Django or config.hosts in Rails, then restart it."
		if app.owner == "" {
			c.Fix += " If the app wants identity headers on / instead, check again once the connector knows the owner."
		}
	}
	c.Message += withoutOwner(app, host)
	return c
}

// withoutOwner says, of a request through the tailnet name, that it carried no identity
// headers because the owner isn't known, which can be why the app refused it.
func withoutOwner(app App, host string) string {
	if host == "" || app.owner != "" {
		return ""
	}
	return ". The owner isn't known yet (the connector reports it once the app's node logs in), so this asked without the identity headers the phone's requests carry; an app that wants them on / answers so too"
}

// hostRejection recognizes a dev server refusing a Host it doesn't know, by the answer
// each one gives, and returns the server's name and the setting that allows it.
func hostRejection(status int, body string) (server, fix string) {
	if status != http.StatusForbidden {
		return "", ""
	}
	switch {
	case strings.Contains(body, "Blocked request. This host") && strings.Contains(body, "angular.json"):
		return "The Angular dev server", `In angular.json, add "allowedHosts": [".ts.net"] to the serve target's options, then restart ng serve.`
	case strings.Contains(body, "Blocked request. This host") && strings.Contains(body, "preview.allowedHosts"):
		return "vite preview", "In vite.config.js (or .ts), set preview: { allowedHosts: ['.ts.net'] }, then restart vite preview."
	case strings.Contains(body, "Blocked request. This host"):
		return "The Vite dev server", "In vite.config.js (or .ts), set server: { allowedHosts: ['.ts.net'] }, then restart the dev server."
	case strings.TrimSpace(body) == "Invalid Host header":
		return "webpack-dev-server", "In webpack.config.js, set devServer: { allowedHosts: ['.ts.net'] }, then restart the dev server."
	}
	return "", ""
}

// sleepCheck reads `pmset -g` output and warns when the Mac sleeps on its own, which
// takes every app offline. Something holding sleep off now (caffeinate, a Screen Sharing
// session) still warns: the Mac sleeps once it lets go.
func sleepCheck(pmsetOutput string) Check {
	c := Check{ID: "sleep", Status: statusWarn}
	for _, line := range strings.Split(pmsetOutput, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 || fields[0] != "sleep" {
			continue
		}
		minutes, err := strconv.Atoi(fields[1])
		idle := humanDuration(time.Duration(minutes) * time.Minute)
		switch {
		case err == nil && minutes == 0:
			c.Status, c.Message = statusOK, "the Mac doesn't sleep on its own"
		case err == nil:
			c.Message = "the Mac sleeps after " + idle + " idle, and your apps are offline while it does"
			if strings.Contains(line, "sleep prevented by") {
				c.Message = "the Mac sleeps after " + idle + " idle once nothing holds it off (" + strings.Trim(strings.Join(fields[2:], " "), "()") + ")"
			}
			c.Fix = "System Settings > Energy (or Battery > Options on a laptop): turn on Prevent automatic sleeping when the display is off; or run `sudo pmset -c sleep 0`"
			c.Actor = actorPerson
		default:
			c.Message = "couldn't read the sleep setting from pmset: " + line
		}
		return c
	}
	c.Message = "couldn't find the sleep setting in pmset's output"
	return c
}

// runDoctor combines the app checks with what the daemon knows about each node, and
// checks sharing when any app is shareable.
func runDoctor(cfg *Config, stateDir, configPath string) []Check {
	var checks []Check
	p := &paths{config: configPath, state: stateDir}
	reply, err := callDaemon(stateDir, "certs", 2*time.Minute)
	down := errors.Is(err, errDaemonDown)
	if err != nil {
		checks = append(checks, Check{ID: "daemon", Status: statusFail, Message: err.Error(),
			Fix: startFix(p), Actor: actorPerson})
	} else {
		checks = append(checks, Check{ID: "daemon", Status: statusOK, Message: fmt.Sprintf("running (pid %d)", reply.PID)})
	}
	if c := updateCheck(); c != nil {
		checks = append(checks, *c)
	}
	if len(cfg.Apps) == 0 {
		return append(checks, Check{ID: "apps", Status: statusWarn, Message: "no apps are published",
			Fix: `ovenlight publish --port <n> --name "<App Name>"`, Actor: actorAgent})
	}
	var status *controlReply // the processes the connector runs, for portHeldCheck
	if reply != nil {
		status, _ = callDaemon(stateDir, "status", 10*time.Second)
	}
	if status != nil {
		var slugs []string
		for _, app := range cfg.Apps {
			if app.Run != "" {
				slugs = append(slugs, app.Slug)
			}
		}
		if c, ok := envWarning(status.EnvError, status.Processes, slugs); ok {
			checks = append(checks, c)
		}
	}
	if runtime.GOOS == "darwin" {
		out, err := exec.Command("/usr/bin/pmset", "-g").Output()
		if err != nil {
			checks = append(checks, Check{ID: "sleep", Status: statusWarn, Message: "couldn't read the sleep setting: " + err.Error()})
		} else {
			checks = append(checks, sleepCheck(string(out)))
		}
	}
	for _, app := range cfg.Apps {
		found := appChecks(withOwner(app, cfg, reply), nodeHost(app, reply))
		if down {
			connectorDownFix(found, app)
		}
		if c := heldByOther(app, status, down); c != nil {
			found = append([]Check{*c}, found...)
		}
		found = predatesRunChecks(found, p, app, status)
		checks = append(checks, withPortChoice(found, app)...)
		if reply == nil {
			continue
		}
		checks = append(checks, nodeChecks(app, reply, p)...)
	}
	checks = append(checks, sharedPortChecks(cfg)...)
	return append(checks, sharingChecks(cfg, configPath, reply)...)
}

// heldByOther is portHeldCheck for check and doctor, for an app the connector runs: with
// status, its status reply, while it runs, or with none while it is down, as publish asks.
// A connector from before --run runs no copy for anything to hold the port from.
func heldByOther(app App, status *controlReply, down bool) *Check {
	if app.Run == "" || (status == nil && !down) || predatesRun(status) {
		return nil
	}
	return portHeldCheck(app, status)
}

// sharedPortChecks warn of two apps published on one port, which publish refuses to set
// up but an older config can have: both reach whatever listens there.
func sharedPortChecks(cfg *Config) []Check {
	var checks []Check
	for i, app := range cfg.Apps {
		for _, other := range cfg.Apps[:i] {
			if other.Port == app.Port {
				checks = append(checks, Check{ID: "shared-port", App: app.Slug, Status: statusWarn, Actor: actorAgent,
					Message: fmt.Sprintf("%s (%s) and %s (%s) are both published on port %d, so both open whatever listens there", other.Name, other.Slug, app.Name, app.Slug, app.Port),
					Fix:     fmt.Sprintf("Give one a port of its own (ovenlight publish --slug %s --port <n>), or unpublish the one you don't need.", app.Slug)})
			}
		}
	}
	return checks
}

// connectorDownFix says, of an app the connector runs that isn't listening while the
// connector is down, that it isn't running because the connector isn't, rather than
// pointing at its output.
func connectorDownFix(checks []Check, app App) {
	for i, c := range checks {
		if c.ID == "port-listening" && c.Status != statusOK && app.Run != "" {
			checks[i].Fix = fmt.Sprintf("%s isn't running because the connector, which runs it, isn't. Once the connector runs, it starts the app (see the daemon finding).", app.Name)
			checks[i].Actor = actorPerson
		}
	}
}

// nodeHost is the app node's tailnet name as the connector reports it, "" when it
// doesn't know one.
func nodeHost(app App, reply *controlReply) string {
	if reply == nil {
		return ""
	}
	for _, st := range reply.Apps {
		if st.Slug == app.Slug {
			return st.DNSName
		}
	}
	return ""
}

// sharingChecks covers what invites need: the owner recorded, an API credential, and
// shareable apps running as tagged nodes.
func sharingChecks(cfg *Config, configPath string, reply *controlReply) []Check {
	shareable := false
	for _, app := range cfg.Apps {
		shareable = shareable || app.Shareable
	}
	if !shareable && cfg.Owner == "" {
		return nil
	}
	var checks []Check
	if cfg.Owner == "" {
		checks = append(checks, Check{ID: "sharing-owner", Status: statusFail, Message: "shareable apps, but no owner is recorded, so nobody can use the admin API",
			Fix: "ovenlight setup-sharing --owner <your Tailscale login>", Actor: actorPerson})
	} else {
		checks = append(checks, Check{ID: "sharing-owner", Status: statusOK, Message: "owner is " + cfg.Owner})
	}
	if reply == nil || !reply.Dev {
		if creds, err := tsapi.LoadCredentials(credentialsPath(configPath)); err != nil {
			checks = append(checks, Check{ID: "sharing-credential", Status: statusFail, Message: err.Error(), Fix: "ovenlight auth set", Actor: actorPerson, URL: consoleKeys})
		} else if _, err := credentialAccess(creds); err != nil {
			checks = append(checks, Check{ID: "sharing-credential", Status: statusFail,
				Message: "the Tailscale API credential (" + creds.Type + " " + creds.Fingerprint() + ") doesn't work: " + err.Error(),
				Fix:     "ovenlight auth set, with a new API access token if this one expired", Actor: actorPerson, URL: consoleKeys})
		} else {
			checks = append(checks, Check{ID: "sharing-credential", Status: statusOK, Message: "Tailscale API credential works (" + creds.Type + " " + creds.Fingerprint() + ")"})
		}
	}
	if reply == nil {
		return checks
	}
	for _, app := range cfg.Apps {
		for _, st := range reply.Apps {
			if st.Slug != app.Slug || (st.State != "serving" && st.State != "running") {
				continue
			}
			if app.Shareable {
				tag := policy.AppTag(app.Slug)
				c := Check{ID: "shareable-node", App: app.Slug, Status: statusOK, Message: "runs as a tagged app node (" + tag + ")"}
				if !slices.Contains(st.Tags, tag) {
					c.Status, c.Message = statusFail, "the app is marked shareable but its node isn't tagged "+tag+", so guests can't reach it"
					c.Fix = "ovenlight publish --slug " + app.Slug + " --shareable"
					c.Actor = actorPerson
				}
				checks = append(checks, c)
			}
			c := Check{ID: "admin-api", App: app.Slug, Status: statusOK, Message: "owner admin API at " + st.AdminURL}
			if st.AdminURL == "" {
				c.Status, c.Message = statusWarn, fmt.Sprintf("the owner admin API isn't up on port %d yet", adminPort)
			}
			checks = append(checks, c)
		}
	}
	return checks
}

// keyExpiryWarning is how long before a node's key expires doctor warns about it.
const keyExpiryWarning = 30 * 24 * time.Hour

// keyExpiryCheck warns that the node's key expires, and says how to keep it signed in.
func keyExpiryCheck(slug, name string, expiry time.Time) Check {
	return Check{ID: "key-expiry", App: slug, Status: statusWarn,
		Message: "the node's key expires on " + expiry.Local().Format("2 Jan 2006") + ", and then it needs a browser sign-in",
		Fix:     keyExpiryFix(name), Actor: actorPerson, URL: consoleMachines}
}

// keyExpiryFix keeps the node called name signed in for good.
func keyExpiryFix(name string) string {
	return "In the Tailscale admin console, Machines, choose Disable key expiry for " + orDash(name) + ", or store an API credential (ovenlight auth set) and the connector does it"
}

// noLinkCheck says why the node has had no login link for loginLinkWait, or is nil: it
// can't reach Tailscale's login server, or, unless the app is shareable and so logs in
// again with reshareCommand, it lost its login after running or its login failed. Only
// the person sees to any of these.
func noLinkCheck(st NodeStatus, p *paths) *Check {
	c := &Check{ID: "node-login", App: st.Slug, Status: statusWarn, Actor: actorPerson}
	switch {
	case st.NoServer:
		c.Message = fmt.Sprintf("the node has had no login link for over %v: it %s", loginLinkWait, unreachableText(st.LoginErr))
		c.Fix = "Check this Mac's network connection, and any VPN or firewall that may block Tailscale; the node keeps trying, and ovenlight status shows the link once it comes"
	case st.Restart:
		c.Message = "the node lost its login (removed from the tailnet, or its key expired), and gets a new login link only when the connector restarts"
		c.Fix = restartLoginFix(p)
	case st.LoginErr != "" && !st.Shareable:
		c.Message = "the node's last login failed: " + st.LoginErr
		c.Fix = "Act on that error; the node keeps trying, and ovenlight status shows a login link once one comes"
	default:
		return nil
	}
	return c
}

func nodeChecks(app App, reply *controlReply, p *paths) []Check {
	var st *NodeStatus
	for i := range reply.Apps {
		if reply.Apps[i].Slug == app.Slug {
			st = &reply.Apps[i]
		}
	}
	if st == nil {
		return []Check{{ID: "node", App: app.Slug, Status: statusFail, Message: "the connector has no node for this app",
			Fix: "Reload the connector: ovenlight publish --slug " + app.Slug + ", or restart it", Actor: actorAgent}}
	}
	login := Check{ID: "node-login", App: app.Slug}
	switch st.State {
	case "serving", "running":
		login.Status, login.Message = statusOK, fmt.Sprintf("logged in as %s, reachable at %s", orDash(st.Owner), orDash(st.URL))
	case "needs-login":
		login.Status, login.Message = statusFail, "the node needs a one-time login"
		login.Fix = "Open " + st.LoginURL + " and sign in with your Tailscale account"
		if st.LoginURL == "" {
			login.Fix = "The node hasn't given a login link yet: run `ovenlight status` in a moment, which shows it, then open it and sign in with your Tailscale account"
		}
		login.Actor, login.URL = actorPerson, st.LoginURL
		if app.Shareable {
			login.Message = "the node needs to log in again as a shareable app node"
			login.Fix = reshareCommand(app.Slug)
		}
		if c := noLinkCheck(*st, p); c != nil {
			login = *c
			login.Status = statusFail
		}
	case "needs-approval":
		login.Status, login.Message = statusFail, "the node waits for approval: your tailnet has device approval on"
		login.Fix = "Approve " + app.Slug + " in the Tailscale admin console (Machines page)"
		login.Actor, login.URL = actorPerson, consoleMachines
	default:
		login.Status, login.Message = statusWarn, "the node is "+st.State+" ("+st.Backend+")"
	}
	checks := []Check{login}
	if st.KeyExpiry != nil && time.Until(*st.KeyExpiry) < keyExpiryWarning {
		checks = append(checks, keyExpiryCheck(app.Slug, st.DNSName, *st.KeyExpiry))
	}
	if certErr, ok := reply.Certs[app.Slug]; ok && (st.State == "serving" || st.State == "running") {
		cert := Check{ID: "certificate", App: app.Slug, Status: statusOK, Message: "HTTPS certificate for " + st.DNSName + " is available"}
		if certErr != "" {
			cert.Status, cert.Message = statusFail, "can't get an HTTPS certificate: "+certErr
			cert.Fix = "Turn on MagicDNS and HTTPS Certificates in the Tailscale admin console (DNS page)"
			cert.Actor, cert.URL = actorPerson, consoleDNS
		}
		checks = append(checks, cert)
	}
	return checks
}
