package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
)

// devOptions point the connector at a local control server (Headscale) for testing.
// They are only accepted with OVENLIGHT_DEV=1 and never used by the installed agent.
type devOptions struct {
	ControlURL      string
	AuthKey         string
	TLSCert         *tls.Certificate // Headscale can't issue ts.net certificates
	Headscale       string           // headscale binary: stands in for the Tailscale API
	HeadscaleConfig string
}

// adminPort is the tailnet port of the owner's admin API on every app node. The policy
// that setup-sharing writes lets guests reach only :443, so they never get here.
const adminPort = 8443

// appNode is one published app: its own tsnet node, named after the slug, serving
// HTTPS on :443 (proxying to the app on 127.0.0.1) and the admin API on :8443.
type appNode struct {
	dir     string
	dev     devOptions
	host    *daemon // sharing, guests and the admin API; nil in unit tests
	authKey string  // a key for the first login: tagged for a shareable node
	// untaggedKey marks authKey as the owner's untagged key: once logged in, the node
	// turns off its key expiry, so it never needs a browser sign-in.
	untaggedKey bool

	srv    *tsnet.Server
	lc     *local.Client
	whoIs  func(ctx context.Context, remoteAddr string) (*apitype.WhoIsResponse, error) // lc.WhoIs; tests stub it
	proxy  *httputil.ReverseProxy
	ctx    context.Context
	cancel context.CancelFunc
	logf   func(format string, args ...any)

	mu        sync.Mutex
	app       App
	cfgOwner  string // the owner login from the config; wins over the node's own user
	backend   string // tailscale backend state: NoState, NeedsLogin, Starting, Running...
	authURL   string
	dnsName   string
	selfID    string // stable node ID
	tags      []string
	owner     string // the node's user, for an untagged node
	ownerName string
	serving   bool
	adminUp   bool
	lastErr   string
	meta      *SiteManifest
	metaAt    time.Time
	metaErr   error
	loggedURL string
	noLinkAt  time.Time  // when the node began waiting for login without a link; zero when it isn't
	loginErr  string     // tsnet's last login error, from its health warnings
	noLinkLog bool       // the log has said why no link came, since noLinkAt
	reached   bool       // the node has had a network map this run
	expired   bool       // the node's key has expired
	keyID     string     // authKey's ID until the node first runs, so an unused key can be deleted
	keyMinted time.Time  // when authKey was minted; it expires appKeyTTL later
	keyExpiry *time.Time // when the node's key expires; nil when it doesn't

	connMu     sync.Mutex
	guestConns map[string]map[*http.Request]context.CancelFunc // device ID -> live guest requests
}

// nodeDir is where an app's node keeps its tailnet state.
func nodeDir(stateDir, slug string) string { return filepath.Join(stateDir, "nodes", slug) }

// nodeLoggedIn reports whether the app's node has logged in: its state holds a login
// profile, which tsnet saves once the node has one, not before.
func nodeLoggedIn(stateDir, slug string) bool {
	data, err := os.ReadFile(filepath.Join(nodeDir(stateDir, slug), "tailscaled.state"))
	var state map[string][]byte
	var profiles map[string]json.RawMessage
	return err == nil && json.Unmarshal(data, &state) == nil && json.Unmarshal(state["_profiles"], &profiles) == nil && len(profiles) > 0
}

func newAppNode(app App, stateDir string, dev devOptions, host *daemon) *appNode {
	ctx, cancel := context.WithCancel(context.Background())
	n := &appNode{
		app:        app,
		dir:        nodeDir(stateDir, app.Slug),
		dev:        dev,
		host:       host,
		ctx:        ctx,
		cancel:     cancel,
		guestConns: map[string]map[*http.Request]context.CancelFunc{},
	}
	prefix := "[" + app.Slug + "] "
	n.logf = func(format string, args ...any) { log.Printf(prefix+format, args...) }
	n.proxy = &httputil.ReverseProxy{
		Rewrite:       n.rewrite,
		FlushInterval: -1, // stream: server-sent events and long polls must not buffer
		Transport: &http.Transport{
			DialContext:         (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
			MaxIdleConnsPerHost: 16,
			IdleConnTimeout:     90 * time.Second,
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			n.logf("upstream error %s %s: %v", r.Method, logPath(r.URL.Path), err)
			textError(w, http.StatusBadGateway, codeUnavailable, fmt.Sprintf("%s isn't responding on its computer right now. Try again in a moment.", n.App().Name))
		},
		ErrorLog: log.New(log.Writer(), prefix, log.LstdFlags),
	}
	return n
}

func (n *appNode) App() App {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.app
}

// setApp applies a config change (name or port) without restarting the node.
func (n *appNode) setApp(app App) {
	n.mu.Lock()
	changed := n.app != app
	n.app = app
	n.mu.Unlock()
	if changed {
		n.logf("now proxying %q to 127.0.0.1:%d", app.Name, app.Port)
	}
	go n.refreshMeta()
}

// setOwner applies the configured owner login.
func (n *appNode) setOwner(login string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.cfgOwner = login
}

// hasState reports whether the node has logged in before (its state file exists).
func (n *appNode) hasState() bool {
	_, err := os.Stat(filepath.Join(n.dir, "tailscaled.state"))
	return err == nil
}

func (n *appNode) upstream() *url.URL {
	return &url.URL{Scheme: "http", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(n.App().Port))}
}

func (n *appNode) start() error {
	if err := jsonfile.MkdirPrivate(n.dir); err != nil {
		return err
	}
	authKey := n.dev.AuthKey
	if n.authKey != "" {
		authKey = n.authKey
	}
	// A tagged key tags the node by itself. Without one, a shareable app's first
	// (interactive) login asks for the tag. An existing node keeps its identity;
	// converting it is makeShareable's job.
	var tags []string
	if n.App().Shareable && n.authKey == "" && !n.hasState() {
		tags = []string{policy.AppTag(n.App().Slug)}
	}
	n.srv = &tsnet.Server{
		Dir:           n.dir,
		Hostname:      n.App().Slug,
		ControlURL:    n.dev.ControlURL,
		AuthKey:       authKey,
		AdvertiseTags: tags,
		Logf:          backendLogger(filepath.Join(n.dir, "backend.log")),
		UserLogf: func(format string, args ...any) {
			// tsnet's own login hint talks about TS_AUTHKEY; watch() prints a clearer one.
			if msg := fmt.Sprintf(format, args...); !strings.Contains(msg, "go to: ") {
				n.logf("%s", msg)
			}
		},
	}
	if err := n.srv.Start(); err != nil {
		return err
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		n.srv.Close()
		return err
	}
	n.lc, n.whoIs = lc, lc.WhoIs
	go n.watch()
	go n.serve(443, n, func(up bool, errText string) { n.setServing(up, errText) })
	go n.serve(adminPort, n.adminHandler(), func(up bool, _ string) {
		n.mu.Lock()
		n.adminUp = up
		n.mu.Unlock()
	})
	go n.refreshMeta()
	return nil
}

func (n *appNode) close() {
	n.cancel()
	if n.srv != nil {
		n.srv.Close()
	}
}

// watch polls the node's status: quickly while it starts or waits for login, slowly
// once it runs. It records the owner login used for the role decision.
func (n *appNode) watch() {
	var expiryTried time.Time // the last try to turn off key expiry, until one works
	expiryOff := false
	for {
		interval := 2 * time.Second
		if n.refreshStatus() {
			interval = 15 * time.Second
			if !expiryOff && time.Since(expiryTried) >= keyExpiryRetry && n.wantsKeyExpiryOff() {
				expiryTried = time.Now()
				expiryOff = n.disableKeyExpiry() == nil
			}
		}
		select {
		case <-n.ctx.Done():
			return
		case <-time.After(interval):
		}
	}
}

// refreshStatus reads the node's state, MagicDNS name and owner, and reports whether
// the node is running.
func (n *appNode) refreshStatus() bool {
	ctx, cancel := context.WithTimeout(n.ctx, 5*time.Second)
	st, err := n.lc.Status(ctx)
	cancel()
	if err != nil {
		return false
	}
	return n.applyStatus(st)
}

// applyStatus records the node's status, as refreshStatus reads it.
func (n *appNode) applyStatus(st *ipnstate.Status) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	if st.BackendState != n.backend {
		n.logf("node state %s", st.BackendState)
	}
	n.backend = st.BackendState
	n.authURL = st.AuthURL
	n.loginErr = ""
	for _, h := range st.Health {
		if e, ok := strings.CutPrefix(h, loginErrorHealth); ok {
			n.loginErr = e
		}
	}
	if st.Self != nil {
		n.reached = n.reached || st.Self.InNetworkMap
		n.expired = st.Self.Expired || st.Self.KeyExpiry != nil && time.Now().After(*st.Self.KeyExpiry)
	}
	switch {
	case st.BackendState != "NeedsLogin" || st.AuthURL != "":
		n.noLinkAt, n.noLinkLog = time.Time{}, false
	case n.noLinkAt.IsZero():
		n.noLinkAt = time.Now()
	case time.Since(n.noLinkAt) >= loginLinkWait && !n.noLinkLog:
		n.noLinkLog = true
		switch loginErr, unreachable, restart := n.noLinkLocked(); {
		case unreachable:
			n.logf("no login link after %v: the node %s; check the network connection, and any VPN or firewall", loginLinkWait, unreachableText(loginErr))
		case restart:
			n.logf("no login link after %v: the node lost its login; restart the connector to get a new link", loginLinkWait)
		case loginErr != "":
			n.logf("no login link after %v: the last login error was: %s", loginLinkWait, loginErr)
		}
	}
	if st.BackendState == "Running" {
		n.keyID = "" // logged in, so the key is spent
	}
	if st.Self != nil {
		n.dnsName = strings.TrimSuffix(st.Self.DNSName, ".")
		n.selfID = string(st.Self.ID)
		n.keyExpiry = st.Self.KeyExpiry
		n.tags = nil
		if st.Self.Tags != nil {
			n.tags = st.Self.Tags.AsSlice()
		}
		owner, ownerName := "", ""
		if len(n.tags) == 0 {
			owner, ownerName = st.User[st.Self.UserID].LoginName, st.User[st.Self.UserID].DisplayName
		}
		if owner != n.owner && owner != "" {
			n.logf("owner is %s", owner)
		}
		n.owner, n.ownerName = owner, ownerName
	}
	if st.BackendState == "NeedsLogin" && st.AuthURL != "" && st.AuthURL != n.loggedURL {
		n.loggedURL = st.AuthURL
		if n.app.Shareable {
			log.Printf("\n\n    LOGIN NEEDED for %q: run `%s`\n\n", n.app.Name, reshareCommand(n.app.Slug))
		} else {
			log.Printf("\n\n    LOGIN NEEDED for %q: open this link and sign in to your tailnet\n    %s\n\n", n.app.Name, st.AuthURL)
		}
	}
	return st.BackendState == "Running"
}

// loginLinkWait is how long a node waits for a login link before the connector says why
// none came. A link comes within seconds when it can.
const loginLinkWait = 30 * time.Second

// loginErrorHealth starts tsnet's health warning that holds its last login error.
const loginErrorHealth = "You are logged out. The last login error was: "

// noLinkLocked says why the node has had no login link for loginLinkWait: its last login
// error, whether it can't reach Tailscale's login server, and whether it needs the
// connector restarted. Only a node that hasn't reached the server this run, and whose key
// hasn't expired, counts as unreachable, and only while it has no error or one from a
// request that got no answer. tsnet asks for a link only as it starts, so the owner's node
// that reached the server, or whose key expired, gets one when the connector restarts (a
// shareable one logs in again with reshareCommand).
func (n *appNode) noLinkLocked() (loginErr string, unreachable, restart bool) {
	if n.noLinkAt.IsZero() || time.Since(n.noLinkAt) < loginLinkWait {
		return "", false, false
	}
	unreachable = !n.reached && !n.expired && (n.loginErr == "" || unansweredLogin.MatchString(n.loginErr))
	return n.loginErr, unreachable, (n.reached || n.expired) && !n.app.Shareable && len(n.tags) == 0
}

// unansweredLogin matches a login error from a request that got no answer, which Go's
// HTTP client words with the method and quoted URL, as in
// `fetch control key: Get "https://...": dial tcp ...`.
var unansweredLogin = regexp.MustCompile(`\b(Get|Post) "[^"]*": `)

// unreachableText says the node can't reach Tailscale's login server, and why if known.
func unreachableText(loginErr string) string {
	if loginErr == "" {
		return "can't reach Tailscale's login server"
	}
	return "can't reach Tailscale's login server: " + loginErr
}

// reshareCommand logs a shareable app's node back in. Its login link would sign it in
// untagged, as whoever opens it.
func reshareCommand(slug string) string {
	return "ovenlight publish --slug " + slug + " --shareable"
}

// wantsKeyExpiryOff reports whether the connector should turn off the running node's key
// expiry: it logged in with the owner's untagged key, or it is an untagged node of the
// owner's whose key still expires (a browser login) and an API credential is stored.
func (n *appNode) wantsKeyExpiryOff() bool {
	if n.untaggedKey {
		return true
	}
	if n.host == nil {
		return false
	}
	n.mu.Lock()
	user, expires := n.owner, n.keyExpiry != nil // owner is empty for a tagged node
	n.mu.Unlock()
	if user == "" || !expires {
		return false
	}
	d := n.host
	d.mu.Lock()
	owner := d.appOwner(d.cfg)
	d.mu.Unlock()
	if !policy.EqualFoldASCII(user, owner) {
		return false
	}
	_, err := d.api()
	return err == nil
}

// keyExpiryRetry is how often watch tries again to turn off key expiry until it works,
// so a new credential (`ovenlight auth set`) takes effect without a restart.
const keyExpiryRetry = time.Hour

// disableKeyExpiry turns off the logged-in node's key expiry through the API, once.
func (n *appNode) disableKeyExpiry() error {
	api, err := n.host.api()
	if err == nil {
		ctx, cancel := context.WithTimeout(n.ctx, apiTimeout) // closing the node stops it
		err = api.DisableKeyExpiry(ctx, n.deviceID())
		cancel()
	}
	if err != nil {
		if n.ctx.Err() == nil { // a closed node isn't tried again
			n.logf("couldn't turn off its key expiry (tried again within the hour; until then it expires as after a browser sign-in): %v", err)
		}
		return err
	}
	n.logf("key expiry turned off")
	return nil
}

// taggedLoginPending reports whether the node's first login is under way with a tagged
// key it can still use: not spent (the node never ran) and not expired.
func (n *appNode) taggedLoginPending() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.authKey != "" && !n.untaggedKey && n.keyID != "" && time.Since(n.keyMinted) < appKeyTTL
}

// dropUnusedKey deletes the node's first-login key if the node never ran with it, as
// when it didn't start or was unpublished first. It is best effort, like dropKey.
func (n *appNode) dropUnusedKey() {
	n.mu.Lock()
	id := n.keyID
	n.keyID = ""
	n.mu.Unlock()
	if id == "" {
		return
	}
	api, err := n.host.api()
	if err != nil {
		n.logf("couldn't delete the unused auth key %s: %v", id, err)
		return
	}
	dropKey(api, n.App().Slug, id)
}

// serve waits for the node to come up (through login if needed), then serves HTTPS on
// the port until the node closes. Certificate or HTTPS setup errors are retried slowly.
// report tells the caller whether the port is up.
func (n *appNode) serve(port int, handler http.Handler, report func(up bool, errText string)) {
	for attempt := 0; ; attempt++ {
		ln, err := n.listen(port)
		if err == nil {
			n.refreshStatus() // the owner must be known before the first request
			report(true, "")
			if port == 443 {
				n.logf("serving https://%s/ -> %s", n.DNSName(), n.upstream())
			} else {
				n.logf("admin API on https://%s:%d/", n.DNSName(), port)
			}
			hs := &http.Server{
				Handler:           handler,
				ReadHeaderTimeout: 30 * time.Second,
				IdleTimeout:       2 * time.Minute,
				MaxHeaderBytes:    64 << 10,
				ErrorLog:          log.New(log.Writer(), "["+n.App().Slug+"] ", log.LstdFlags),
			}
			go func() { <-n.ctx.Done(); hs.Close() }()
			err = hs.Serve(ln)
			report(false, "")
		}
		if n.ctx.Err() != nil {
			return
		}
		report(false, err.Error())
		n.logf("can't serve :%d yet: %v", port, err)
		select {
		case <-n.ctx.Done():
			return
		case <-time.After(min(30*time.Second, time.Duration(attempt+1)*5*time.Second)):
		}
	}
}

func (n *appNode) listen(port int) (net.Listener, error) {
	addr := ":" + strconv.Itoa(port)
	if n.dev.TLSCert == nil {
		return n.srv.ListenTLS("tcp", addr) // blocks until the node is up
	}
	if _, err := n.srv.Up(n.ctx); err != nil {
		return nil, err
	}
	ln, err := n.srv.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	return tls.NewListener(ln, &tls.Config{Certificates: []tls.Certificate{*n.dev.TLSCert}}), nil
}

func (n *appNode) setServing(serving bool, errText string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.serving = serving
	n.lastErr = errText
}

func (n *appNode) DNSName() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.dnsName
}

// ownerLogin is who counts as the owner: the configured owner, or else the user who
// owns an untagged node. A tagged node without a configured owner has none.
func (n *appNode) ownerLogin() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.cfgOwner != "" {
		return n.cfgOwner
	}
	return n.owner
}

// isTagged reports whether the node is logged in with the tag. A node that lost its
// login still reports the tags of its last network map.
func (n *appNode) isTagged(tag string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.backend == "Running" && slices.Contains(n.tags, tag)
}

func (n *appNode) deviceID() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.selfID
}

// trackGuest registers a guest's request so revoking the guest can end it, including a
// WebSocket that outlives the request: the proxy closes the upgraded connection when
// the request's context ends.
func (n *appNode) trackGuest(r *http.Request, deviceID string) (*http.Request, func()) {
	ctx, cancel := context.WithCancel(r.Context())
	r = r.WithContext(ctx)
	n.connMu.Lock()
	if n.guestConns[deviceID] == nil {
		n.guestConns[deviceID] = map[*http.Request]context.CancelFunc{}
	}
	n.guestConns[deviceID][r] = cancel
	n.connMu.Unlock()
	return r, func() {
		cancel()
		n.connMu.Lock()
		delete(n.guestConns[deviceID], r)
		if len(n.guestConns[deviceID]) == 0 {
			delete(n.guestConns, deviceID)
		}
		n.connMu.Unlock()
	}
}

// dropGuest ends every live request from the device.
func (n *appNode) dropGuest(deviceID string) {
	n.connMu.Lock()
	defer n.connMu.Unlock()
	for _, cancel := range n.guestConns[deviceID] {
		cancel()
	}
	delete(n.guestConns, deviceID)
}

// peerInfo is a device the node can see, for the post-change health check.
type peerInfo struct {
	ID     string   `json:"id"`
	Name   string   `json:"name"`
	Online bool     `json:"online"`
	Login  string   `json:"login,omitempty"` // empty for tagged devices
	Tags   []string `json:"tags,omitempty"`
}

func (n *appNode) peers(ctx context.Context) ([]peerInfo, error) {
	if n.lc == nil {
		return nil, errors.New("node not started")
	}
	st, err := n.lc.Status(ctx)
	if err != nil {
		return nil, err
	}
	var out []peerInfo
	for _, p := range st.Peer {
		pi := peerInfo{ID: string(p.ID), Name: strings.TrimSuffix(p.DNSName, "."), Online: p.Online}
		if p.Tags != nil && p.Tags.Len() > 0 {
			pi.Tags = p.Tags.AsSlice()
		} else {
			pi.Login = st.User[p.UserID].LoginName
		}
		out = append(out, pi)
	}
	return out, nil
}

// refreshMeta rebuilds ovenlight.json from the app's own page and manifest.
func (n *appNode) refreshMeta() {
	ctx, cancel := context.WithTimeout(n.ctx, 5*time.Second)
	defer cancel()
	app := n.App()
	meta, err := fetchSiteManifest(ctx, metaClient, n.upstream(), app)
	n.mu.Lock()
	n.meta, n.metaAt, n.metaErr = &meta, time.Now(), err
	n.mu.Unlock()
	if err != nil {
		n.logf("couldn't read the app's page for ovenlight.json (will retry): %v", err)
	}
}

// siteManifest returns the cached manifest, refetching when the last try failed a
// while ago (the app may have started after the connector).
func (n *appNode) siteManifest() SiteManifest {
	n.mu.Lock()
	stale := n.meta == nil || (n.metaErr != nil && time.Since(n.metaAt) > 30*time.Second)
	n.mu.Unlock()
	if stale {
		n.refreshMeta()
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	return *n.meta
}

var metaClient = &http.Client{
	Timeout: 5 * time.Second,
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if req.URL.Hostname() != "127.0.0.1" || len(via) > 3 {
			return errors.New("redirect leaves the app")
		}
		return nil
	},
}

// NodeStatus is one app's live state, as `status` and `doctor` report it.
type NodeStatus struct {
	appInfo
	State     string     `json:"state"` // starting, needs-login, running, serving, stopped
	Backend   string     `json:"backend"`
	DNSName   string     `json:"dnsName,omitempty"`
	URL       string     `json:"url,omitempty"`
	AdminURL  string     `json:"adminURL,omitempty"`
	LoginURL  string     `json:"loginURL,omitempty"`
	LoginErr  string     `json:"loginError,omitempty"`      // tsnet's last login error, once the node has waited loginLinkWait for a link
	NoServer  bool       `json:"noLoginServer,omitempty"`   // that wait is because it can't reach Tailscale's login server (see noLinkLocked)
	Restart   bool       `json:"restartForLogin,omitempty"` // or because it lost its login (removed, or its key expired) and gets a link when the connector restarts
	Owner     string     `json:"owner,omitempty"`           // who counts as the owner
	NodeUser  string     `json:"nodeUser,omitempty"`        // the untagged node's own user
	OwnerName string     `json:"ownerName,omitempty"`       // that user's display name
	Tags      []string   `json:"tags,omitempty"`
	DeviceID  string     `json:"deviceId,omitempty"`
	KeyExpiry *time.Time `json:"keyExpiry,omitempty"` // nil when the node's key doesn't expire
	Error     string     `json:"error,omitempty"`
}

func (n *appNode) status() NodeStatus {
	n.mu.Lock()
	defer n.mu.Unlock()
	owner := n.cfgOwner
	if owner == "" {
		owner = n.owner
	}
	s := NodeStatus{appInfo: n.app.view(), Backend: n.backend, DNSName: n.dnsName, Owner: owner, NodeUser: n.owner, OwnerName: n.ownerName,
		Tags: slices.Clone(n.tags), DeviceID: n.selfID, KeyExpiry: n.keyExpiry, Error: n.lastErr}
	switch {
	case n.serving:
		s.State = "serving"
	case n.backend == "NeedsLogin":
		s.State = "needs-login"
		s.LoginErr, s.NoServer, s.Restart = n.noLinkLocked()
		if !n.app.Shareable { // see reshareCommand
			s.LoginURL = n.authURL
		}
	case n.backend == "Running":
		s.State = "running"
	case n.backend == "Stopped":
		s.State = "stopped"
	default:
		s.State = "starting"
	}
	if n.dnsName != "" && (n.serving || n.backend == "Running") {
		s.URL = "https://" + n.dnsName + "/"
	}
	if n.dnsName != "" && n.adminUp {
		s.AdminURL = fmt.Sprintf("https://%s:%d/", n.dnsName, adminPort)
	}
	return s
}

// checkCertificate asks the tailnet for this node's certificate, which is what HTTPS
// needs; it fails when MagicDNS or HTTPS certificates are off for the tailnet.
func (n *appNode) checkCertificate(ctx context.Context) error {
	name := n.DNSName()
	if name == "" {
		return errors.New("the node has no MagicDNS name yet")
	}
	if n.dev.TLSCert != nil {
		leaf, err := x509.ParseCertificate(n.dev.TLSCert.Certificate[0])
		if err != nil {
			return err
		}
		return leaf.VerifyHostname(name)
	}
	_, _, err := n.lc.CertPair(ctx, name)
	return err
}

// backendLogger writes tsnet's verbose backend log to a file in the node's directory,
// moving it to backend.log.1 whenever it grows past 10 MB.
func backendLogger(path string) func(string, ...any) {
	f := &restartingFile{path: path, max: 10 << 20}
	if err := f.open(0); err != nil {
		return func(string, ...any) {}
	}
	return log.New(f, "", log.LstdFlags|log.Lmicroseconds).Printf
}

// restartingFile is a log file that starts over once it would grow past max bytes,
// keeping the full one as path.1 (replacing an older one there). Several loggers may
// share one.
type restartingFile struct {
	path string
	max  int64
	mu   sync.Mutex
	f    *os.File
	size int64
}

func (r *restartingFile) open(flag int) error {
	f, err := os.OpenFile(r.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND|flag, 0o600)
	if err != nil {
		return err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	r.f, r.size = f, info.Size()
	return nil
}

func (r *restartingFile) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.size+int64(len(p)) > r.max {
		r.f.Close()
		os.Rename(r.path, r.path+".1") // Windows refuses while another process has it open; O_TRUNC then starts it over in place
		if err := r.open(os.O_TRUNC); err != nil {
			return 0, err
		}
	}
	n, err := r.f.Write(p)
	r.size += int64(n)
	return n, err
}

func (r *restartingFile) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.f.Close()
}
