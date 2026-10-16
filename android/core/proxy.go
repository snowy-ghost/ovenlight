package core

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// upstream is what the proxy needs from one membership's node.
type upstream interface {
	// running reports whether the node's backend is Running.
	running(ctx context.Context) bool
	// suffix is the node's MagicDNS suffix ("tailabc123.ts.net"), or "" while unknown.
	suffix(ctx context.Context) string
	// peerAddr resolves a MagicDNS name from the node's own peers, never public DNS.
	peerAddr(ctx context.Context, host string) (netip.Addr, bool)
	// isPeer reports whether ip is one of the node's peers' tailnet addresses.
	isPeer(ctx context.Context, ip netip.Addr) bool
	// dial connects to an IP literal through the node: netstack for peers, the
	// default network for anything else.
	dial(ctx context.Context, addr netip.AddrPort) (net.Conn, error)
}

const (
	appKeyPrefix  = "app-"
	nodeKeyPrefix = "node-"
	holdPoll      = 200 * time.Millisecond
)

// Chromium abandons a tunnel 10 seconds after sending CONNECT, so the proxy
// answers within 8, the hold and the dial included. Tests and debug builds change it.
var answerWithin atomic.Int64

func init() { answerWithin.Store(int64(8 * time.Second)) }

// proxy is the one HTTP CONNECT proxy on 127.0.0.1 that every web view and every
// native request goes through. The Basic auth username is a route key, which says
// which membership's node carries the request, and the password is a secret made
// for this launch.
type proxy struct {
	secret string
	realm  string
	ln     net.Listener
	srv    *http.Server

	// node returns the membership's open node, or nil when it has none.
	node   func(membershipID string) upstream
	lookup func(ctx context.Context, host string) ([]netip.Addr, error)
	phone  func() phoneNets
	logf   func(format string, args ...any) // debug builds only; nil in release

	mu      sync.Mutex
	routes  map[string]string // app- route key to membership ID
	known   map[string]bool   // memberships that had a node in this process: node- keys route to them
	tunnels map[string]map[*tunnel]struct{}
	changed chan struct{} // closed and replaced whenever routes change
}

type tunnel struct {
	membership string
	conns      []net.Conn
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// startProxy listens on a random loopback port. fatal runs if the listener ever
// stops, and must end the process, so no other app can take the port and collect
// the secret that clients send up front.
func startProxy(node func(string) upstream, logf func(string, ...any), fatal func(error)) (*proxy, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	p := &proxy{
		secret: randomHex(32),
		realm:  "ovenlight-" + randomHex(16),
		ln:     ln,
		node:   node,
		lookup: func(ctx context.Context, host string) ([]netip.Addr, error) {
			return net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		},
		phone:   phoneNetworks,
		logf:    logf,
		routes:  map[string]string{},
		known:   map[string]bool{},
		tunnels: map[string]map[*tunnel]struct{}{},
		changed: make(chan struct{}),
	}
	p.srv = &http.Server{
		Handler:           p,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
		ErrorLog:          discardLogger,
	}
	go func() { fatal(fmt.Errorf("proxy listener stopped: %w", p.srv.Serve(ln))) }()
	return p, nil
}

func (p *proxy) addr() string { return p.ln.Addr().String() }

func (p *proxy) debugf(format string, args ...any) {
	if p.logf != nil {
		p.logf(format, args...)
	}
}

// setRoute points an app- key at a membership. Pointing it somewhere else closes
// the key's open tunnels, which belonged to the old route.
func (p *proxy) setRoute(key, membershipID string) error {
	if !strings.HasPrefix(key, appKeyPrefix) || len(key) == len(appKeyPrefix) || strings.Contains(key, ":") {
		return fmt.Errorf("route key %q isn't app-<data store ID>", key)
	}
	if membershipID == "" {
		return errors.New("no membership")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if old, ok := p.routes[key]; ok && old == membershipID {
		return nil
	}
	p.routes[key] = membershipID
	p.closeTunnelsLocked(key)
	p.notifyLocked()
	return nil
}

// know makes a membership's node- key a route, held while its node starts or
// restarts, until clearRoute removes it.
func (p *proxy) know(membershipID string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.known[membershipID] {
		p.known[membershipID] = true
		p.notifyLocked()
	}
}

// clearRoute removes a key's route, closes its open tunnels and refuses its held
// CONNECTs. For a node- key, that is when its membership is gone.
func (p *proxy) clearRoute(key string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.routes, key)
	if id, found := strings.CutPrefix(key, nodeKeyPrefix); found {
		delete(p.known, id)
	}
	p.closeTunnelsLocked(key)
	p.notifyLocked()
}

func (p *proxy) closeTunnelsLocked(key string) {
	for t := range p.tunnels[key] {
		for _, c := range t.conns {
			c.Close()
		}
	}
	delete(p.tunnels, key)
}

func (p *proxy) notifyLocked() {
	close(p.changed)
	p.changed = make(chan struct{})
}

// route names the membership a key routes to. A node- key routes to its own
// membership once that membership has had a node in this process, so a CONNECT
// waits for a node that is closed and starting again, as for an app- key.
func (p *proxy) route(key string) (membership string, changed <-chan struct{}, ok bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if id, found := strings.CutPrefix(key, nodeKeyPrefix); found {
		return id, p.changed, p.known[id]
	}
	membership, ok = p.routes[key]
	return membership, p.changed, ok
}

// track records an open tunnel, unless its route changed while it was being made.
func (p *proxy) track(key string, t *tunnel) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if strings.HasPrefix(key, appKeyPrefix) && p.routes[key] != t.membership {
		return false
	}
	if id, found := strings.CutPrefix(key, nodeKeyPrefix); found && !p.known[id] {
		return false
	}
	if p.tunnels[key] == nil {
		p.tunnels[key] = map[*tunnel]struct{}{}
	}
	p.tunnels[key][t] = struct{}{}
	return true
}

func (p *proxy) untrack(key string, t *tunnel) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.tunnels[key], t)
}

// authorize checks Proxy-Authorization and returns the route key it carries.
func (p *proxy) authorize(r *http.Request) (string, bool) {
	h := r.Header.Get("Proxy-Authorization")
	const prefix = "Basic "
	if len(h) < len(prefix) || !strings.EqualFold(h[:len(prefix)], prefix) {
		return "", false
	}
	b, err := base64.StdEncoding.DecodeString(h[len(prefix):])
	if err != nil {
		return "", false
	}
	key, pass, ok := strings.Cut(string(b), ":")
	if !ok || subtle.ConstantTimeCompare([]byte(pass), []byte(p.secret)) != 1 {
		return "", false
	}
	return key, true
}

func (p *proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodConnect {
		// Only a CONNECT draws a challenge. A page's own fetch of the proxy's port
		// arrives in absolute form, since no request bypasses the proxy; another
		// app connecting to the port directly sends an origin-form request.
		if r.URL.IsAbs() {
			p.debugf("proxy: %s %s: 405", r.Method, r.URL.Redacted())
			http.Error(w, "CONNECT only", http.StatusMethodNotAllowed)
		} else {
			p.debugf("proxy: %s origin form: 400", r.Method)
			http.Error(w, "not a proxy request", http.StatusBadRequest)
		}
		return
	}
	key, ok := p.authorize(r)
	if !ok {
		w.Header().Set("Proxy-Authenticate", `Basic realm="`+p.realm+`"`)
		http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(answerWithin.Load()))
	defer cancel()
	status, t, upstreamConn, err := p.open(ctx, key, r.RequestURI)
	if err != nil {
		p.debugf("proxy: %s %s: %d %v", key, r.RequestURI, status, err)
		http.Error(w, http.StatusText(status), status)
		return
	}
	client, buf, err := w.(http.Hijacker).Hijack()
	if err != nil {
		upstreamConn.Close()
		return
	}
	t.conns = []net.Conn{client, upstreamConn}
	if !p.track(key, t) {
		p.debugf("proxy: %s %s: route changed", key, r.RequestURI)
		client.Close()
		upstreamConn.Close()
		return
	}
	defer p.untrack(key, t)
	p.debugf("proxy: %s %s: open", key, r.RequestURI)
	if _, err := io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		client.Close()
		upstreamConn.Close()
		return
	}
	pipe(client, buf.Reader, upstreamConn)
}

// open finds the key's node, holding the CONNECT while that node starts or
// reconnects, and dials the target through it.
func (p *proxy) open(ctx context.Context, key, target string) (int, *tunnel, net.Conn, error) {
	membership, changed, ok := p.route(key)
	if !ok {
		return http.StatusBadGateway, nil, nil, errors.New("no route")
	}
	for {
		if n := p.node(membership); n != nil && n.running(ctx) {
			addr, err := p.resolve(ctx, n, target)
			if err != nil {
				return http.StatusBadGateway, nil, nil, err
			}
			conn, err := n.dial(ctx, addr)
			if err != nil {
				if ctx.Err() != nil {
					return http.StatusGatewayTimeout, nil, nil, err
				}
				return http.StatusBadGateway, nil, nil, err
			}
			return 0, &tunnel{membership: membership}, conn, nil
		}
		select {
		case <-ctx.Done():
			return http.StatusServiceUnavailable, nil, nil, errors.New("node not running")
		case <-changed:
		case <-time.After(holdPoll):
		}
		now, c, ok := p.route(key)
		if !ok || now != membership {
			return http.StatusServiceUnavailable, nil, nil, errors.New("route gone")
		}
		changed = c
	}
}

func pipe(client net.Conn, buffered *bufio.Reader, upstream net.Conn) {
	var src io.Reader = client
	if buffered.Buffered() > 0 {
		src = io.MultiReader(io.LimitReader(buffered, int64(buffered.Buffered())), client)
	}
	done := make(chan struct{}, 2)
	go func() { io.Copy(upstream, src); done <- struct{}{} }()
	go func() { io.Copy(client, upstream); done <- struct{}{} }()
	<-done
	client.Close()
	upstream.Close()
	<-done
}

var errRefused = errors.New("refused")

// resolve picks the address to dial. A name in a tailnet resolves only from the
// node's peers, never public DNS. Every other address must be on the internet:
// not the phone itself, its local network or any tailnet, unless it is one of the
// node's peers.
func (p *proxy) resolve(ctx context.Context, n upstream, target string) (netip.AddrPort, error) {
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return netip.AddrPort{}, err
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil || port == 0 {
		return netip.AddrPort{}, fmt.Errorf("bad port %q", portText)
	}
	host = strings.TrimRight(lowerASCII(host), ".")
	if ip, err := netip.ParseAddr(host); err == nil {
		return p.allowed(ctx, n, ip, uint16(port))
	}
	if isTailnetName(host, n.suffix(ctx)) {
		if ip, ok := n.peerAddr(ctx, host); ok {
			return netip.AddrPortFrom(ip, uint16(port)), nil
		}
		return netip.AddrPort{}, errRefused
	}
	ips, err := p.lookup(ctx, host)
	if err != nil {
		return netip.AddrPort{}, err
	}
	for _, ip := range ips {
		if a, err := p.allowed(ctx, n, ip, uint16(port)); err == nil {
			return a, nil
		}
	}
	return netip.AddrPort{}, errRefused
}

func isTailnetName(host, suffix string) bool {
	return host == "ts.net" || strings.HasSuffix(host, ".ts.net") ||
		(suffix != "" && (host == suffix || strings.HasSuffix(host, "."+suffix)))
}

// lowerASCII lowercases without the locale: host names are ASCII once encoded.
func lowerASCII(s string) string {
	b := []byte(s)
	for i, c := range b {
		if 'A' <= c && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}

// phoneNets is what the proxy learns of the phone's own networks.
type phoneNets struct {
	own   []netip.Prefix // each interface's address and prefix
	nat64 netip.Prefix   // the default network's own NAT64 prefix, if it has one
}

var wellKnownNAT64 = netip.MustParsePrefix("64:ff9b::/96")

// allowed returns the address to dial for ip, or errRefused. An IPv4-mapped
// address is checked and dialed as IPv4. A NAT64 address, under the well-known
// prefix or the network's own, is checked both as itself and by the IPv4
// address inside it. Nothing inside a network the phone is on passes, so its own
// public addresses and its neighbors' global IPv6 addresses are refused too.
func (p *proxy) allowed(ctx context.Context, n upstream, ip netip.Addr, port uint16) (netip.AddrPort, error) {
	ip = ip.Unmap().WithZone("")
	if n.isPeer(ctx, ip) {
		return netip.AddrPortFrom(ip, port), nil
	}
	nets := p.phone()
	check := []netip.Addr{ip}
	for _, prefix := range []netip.Prefix{wellKnownNAT64, nets.nat64} {
		if prefix.IsValid() && prefix.Contains(ip) {
			b := ip.As16()
			check = append(check, netip.AddrFrom4([4]byte(b[12:])))
		}
	}
	for _, a := range check {
		if !internet(a) {
			return netip.AddrPort{}, errRefused
		}
		for _, own := range nets.own {
			if own.Contains(a) {
				return netip.AddrPort{}, errRefused
			}
		}
	}
	return netip.AddrPortFrom(ip, port), nil
}

// notInternet lists the IANA special-purpose ranges that aren't globally
// reachable, beyond what netip's own checks cover, plus CGNAT and Tailscale's
// IPv6 range, where other tailnets live.
var notInternet = func() []netip.Prefix {
	var ps []netip.Prefix
	for _, s := range []string{
		"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
		"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4",
		"::/96", "::ffff:0:0:0/96", "64:ff9b:1::/48", "100::/64", "100:0:0:1::/64", "2001::/23", "2001:db8::/32", "2002::/16",
		"3fff::/20", "5f00::/16", "fec0::/10", "fd7a:115c:a1e0::/48",
	} {
		ps = append(ps, netip.MustParsePrefix(s))
	}
	return ps
}()

func internet(ip netip.Addr) bool {
	if !ip.IsValid() || !ip.IsGlobalUnicast() || ip.IsPrivate() {
		return false
	}
	for _, p := range notInternet {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}
