package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httputil"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
)

type callerKey struct{}

type caller struct {
	Identity
	Role  Role
	Guest *Guest // set for RoleGuest
}

// logName is who the access logs name: a login, or a tagged device and its guest.
func (c caller) logName() string {
	switch {
	case !c.Tagged:
		return c.Login
	case c.Guest != nil:
		return c.Guest.Name + " (" + c.DeviceName + ")"
	}
	return c.DeviceName
}

// displayName is how the app and the owner's feedback list see the caller.
func (c caller) displayName() string {
	switch {
	case c.Guest != nil:
		return c.Guest.Name
	case c.Name != "":
		return c.Name
	}
	return c.Login
}

func (c caller) userID() string {
	if c.Guest != nil {
		return c.Guest.UserID()
	}
	return c.Login
}

// Paths the connector answers itself on :443. Everything else goes to the app.
const (
	claimPath    = "/__ovenlight/claim"
	feedbackPath = "/__ovenlight/feedback"
	whoamiPath   = "/__ovenlight/whoami"
)

// identify asks the tailnet who is calling and decides their role for this app.
func (n *appNode) identify(r *http.Request) (caller, bool) {
	c, ok := n.whois(r)
	if ok {
		n.decide(&c)
	}
	return c, ok
}

// whois asks the tailnet who is calling.
func (n *appNode) whois(r *http.Request) (caller, bool) {
	who, err := n.whoIs(r.Context(), r.RemoteAddr)
	if err != nil || who.UserProfile == nil || who.Node == nil {
		return caller{}, false
	}
	c := caller{Identity: Identity{Login: who.UserProfile.LoginName, Name: who.UserProfile.DisplayName, Tagged: who.Node.IsTagged(),
		Tags: who.Node.Tags, DeviceID: string(who.Node.StableID), DeviceName: who.Node.ComputedName}}
	if c.Tagged {
		c.Login, c.Name = "", "" // a tagged device's "user" is a placeholder, not a person
	}
	return c, true
}

// decide sets the caller's role for this app.
func (n *appNode) decide(c *caller) {
	// Only a shareable app has guests. A slug republished as an ordinary app admits no
	// one from an older guest record.
	if n.host != nil && c.Tagged && n.App().Shareable {
		c.Guest = n.host.sh.guestFor(c.DeviceID, n.App().Slug)
	}
	c.Role = decideRole(c.Identity, n.ownerLogin(), c.Guest, n.App().Slug)
	if c.Role != RoleGuest {
		c.Guest = nil
	}
}

// checkRequest refuses, before asking who is calling, a Host that isn't this node's
// name (DNS rebinding, Host poisoning) and requests a browser makes on another site's
// behalf. Identity comes from the device, not a cookie, so any page open on an
// admitted device could otherwise act as that device and read the answers.
func (n *appNode) checkRequest(r *http.Request) (int, string) {
	name := n.DNSName()
	switch {
	case !sameHost(r.Host, name):
		return http.StatusMisdirectedRequest, "Use this app's tailnet name."
	case !fromThisSite(r, name):
		return http.StatusForbidden, "Cross-site requests are refused."
	}
	return 0, ""
}

// fromThisSite is a Fetch Metadata resource isolation policy. It admits requests from
// this origin, typed into the address bar, and top-level navigations from elsewhere,
// which the other site can start but neither read nor frame. Native clients such as Ovenlight send no
// Sec-Fetch-Site; a browser that doesn't either (an old one, or a WebSocket handshake)
// is judged by its Origin.
func fromThisSite(r *http.Request, name string) bool {
	switch r.Header.Get("Sec-Fetch-Site") {
	case "same-origin", "none":
		return true
	case "":
		origin := r.Header.Get("Origin")
		return origin == "" || policy.EqualFoldASCII(origin, "https://"+name)
	}
	return r.Header.Get("Sec-Fetch-Mode") == "navigate" && r.Header.Get("Sec-Fetch-Dest") == "document" &&
		(r.Method == http.MethodGet || r.Method == http.MethodHead)
}

// ServeHTTP admits the owner and claimed guests, lets unclaimed guest devices claim an
// invite, serves ovenlight.json itself and proxies everything else to the app with
// verified identity headers.
func (n *appNode) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	rec := &statusRecorder{ResponseWriter: w}
	var c caller
	defer func() {
		code := rec.code()
		if rec.status == 0 && r.Header.Get("Upgrade") != "" {
			code = http.StatusSwitchingProtocols // the proxy hijacks without WriteHeader
		}
		user := c.logName()
		n.logf("%s %s %d %s user=%s role=%s", r.Method, logPath(r.URL.Path), code,
			time.Since(start).Round(time.Millisecond), orDash(user), orDash(string(c.Role)))
	}()

	if status, msg := n.checkRequest(r); status != 0 {
		textError(rec, status, codeBadRequest, msg)
		return
	}
	var ok bool
	if c, ok = n.whois(r); !ok {
		textError(rec, http.StatusForbidden, codeUnknownCaller, "Unknown caller.")
		return
	}
	// Track a guest device's request before its role is decided: a revoke that races it
	// either removes the guest first (the request is refused) or finds this request and
	// ends it.
	if c.Tagged {
		var done func()
		r, done = n.trackGuest(r, c.DeviceID)
		defer done()
	}
	n.decide(&c)
	switch {
	case r.URL.Path == claimPath && (c.Role == RoleUnclaimed || c.Role == RoleGuest) && n.host != nil:
		n.host.handleClaim(rec, r, c.Identity, n.App())
		return
	case c.Role == RoleUnclaimed:
		textError(rec, http.StatusForbidden, codeNotInvited, "You're not invited to this app.")
		return
	case c.Role == RoleDenied:
		textError(rec, http.StatusForbidden, codeOwnerOnly, "This app is private to its owner.")
		return
	}

	switch r.URL.Path {
	case feedbackPath:
		if n.host == nil {
			textError(rec, http.StatusNotFound, codeNotFound, "404 page not found")
			return
		}
		n.host.handleFeedback(rec, r, c, n.App())
		return
	case whoamiPath:
		ownerLabel := ""
		if n.host != nil {
			ownerLabel = n.host.config().OwnerLabel
		}
		if c.Role == RoleOwner {
			rec.Header().Set(versionHeader, version()) // guests don't learn the build
		}
		writeJSON(rec, http.StatusOK, whoamiView{Role: c.Role, User: c.displayName(), UserID: c.userID(),
			App: n.App().Slug, AppName: n.App().Name, Owner: ownerLabel})
		return
	}
	if strings.HasPrefix(r.URL.Path, "/__ovenlight/") {
		textError(rec, http.StatusNotFound, codeNotFound, "404 page not found") // the connector's own namespace never reaches the app
		return
	}

	if up := r.Header.Get("Upgrade"); up != "" && !strings.EqualFold(up, "websocket") {
		// Another protocol, h2c above all, would carry requests past the header checks.
		textError(rec, http.StatusBadRequest, codeBadRequest, "Only WebSocket upgrades are supported.")
		return
	}
	if r.URL.Path == siteManifestPath {
		rec.Header().Set("Content-Type", "application/json")
		rec.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(rec).Encode(n.siteManifest())
		return
	}
	n.proxy.ServeHTTP(rec, r.WithContext(context.WithValue(r.Context(), callerKey{}, c)))
}

// rewrite routes the request to the app on 127.0.0.1, keeps the tailnet Host (as
// `tailscale serve` does) and replaces the identity and client-address headers.
func (n *appNode) rewrite(pr *httputil.ProxyRequest) {
	pr.SetURL(n.upstream())
	pr.Out.Host = pr.In.Host
	c, _ := pr.In.Context().Value(callerKey{}).(caller)
	applyIdentity(pr.Out.Header, c)
	pr.SetXForwarded() // after applyIdentity, which drops every client-sent copy
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// statusRecorder notes the response code for the access log. Unwrap lets the reverse
// proxy reach the real writer's Flush and Hijack (WebSockets).
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	if s.status == 0 {
		s.status = code
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	return s.ResponseWriter.Write(b)
}

func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

func (s *statusRecorder) code() int {
	if s.status == 0 {
		return http.StatusOK
	}
	return s.status
}

// maxLogPath bounds a request path in the log, so a refused device can't grow the log
// as fast as it sends.
const maxLogPath = 200

// logPath is a request path as the log keeps it: never the query, which can carry OAuth
// codes and tokens, without control characters, and cut to maxLogPath bytes.
func logPath(path string) string {
	path = stripControl(path, false)
	if len(path) <= maxLogPath {
		return path
	}
	cut := maxLogPath
	for cut > 0 && !utf8.RuneStart(path[cut]) {
		cut--
	}
	return path[:cut] + "..."
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

// humanDuration says a duration the way a person would: "24 hours", "7 days", "90 seconds".
func humanDuration(d time.Duration) string {
	n, unit := int64(d/time.Second), "second"
	switch day := 24 * time.Hour; {
	case d >= 2*day && d%day == 0:
		n, unit = int64(d/day), "day"
	case d >= time.Hour && d%time.Hour == 0:
		n, unit = int64(d/time.Hour), "hour"
	case d >= time.Minute && d%time.Minute == 0:
		n, unit = int64(d/time.Minute), "minute"
	}
	if n != 1 {
		unit += "s"
	}
	return fmt.Sprintf("%d %s", n, unit)
}
