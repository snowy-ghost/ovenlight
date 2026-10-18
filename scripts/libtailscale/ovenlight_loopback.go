package main

// Ovenlight's loopback proxy. tsnet's own SOCKS5 server dials whatever a page asks for,
// and anything outside the tailnet goes to the system dialer as is: the phone itself, its
// local network and another VPN's tailnet included. This one shares its port with the
// LocalAPI, as tsnet's does, and dials only the node's peers and the internet, by the rules
// of Ovenlight for Android's proxy, whose tests these mirror. build-tailscalekit.sh copies
// this file into libtailscale and points TsnetLoopback and TsnetClose at it.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/proxymux"
	"tailscale.com/net/socks5"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

type guardedProxy struct {
	ln   net.Listener
	cred string
}

var guarded struct {
	sync.Mutex
	m map[*tsnet.Server]*guardedProxy
}

// guardedLoopback stands in for s.Loopback. SOCKS5 on its port is the guarded proxy, and
// HTTP is relayed to tsnet's own listener for the LocalAPI, so a LocalAPI request still
// fails once iOS has reclaimed either listener. tsnet's own SOCKS5 password never leaves Go.
func guardedLoopback(s *tsnet.Server) (addr, proxyCred, localAPICred string, err error) {
	inner, _, localAPICred, err := s.Loopback()
	if err != nil {
		return "", "", "", err
	}
	guarded.Lock()
	defer guarded.Unlock()
	if g := guarded.m[s]; g != nil {
		return g.ln.Addr().String(), g.cred, localAPICred, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", "", "", err
	}
	g := &guardedProxy{ln: ln, cred: randomHex(16)}
	if guarded.m == nil {
		guarded.m = map[*tsnet.Server]*guardedProxy{}
	}
	guarded.m[s] = g

	logf := s.Logf
	if logf == nil {
		logf = logger.Discard
	}
	p := &proxy{
		n: &tsnetNode{s: s},
		lookup: func(ctx context.Context, host string) ([]netip.Addr, error) {
			return net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		},
		phone: phoneNetworks,
	}
	socksLn, httpLn := proxymux.SplitSOCKSAndHTTP(ln)
	srv := &socks5.Server{Logf: logger.WithPrefix(logf, "socks5: "), Dialer: p.dial, Username: "tsnet", Password: g.cred}
	go srv.Serve(socksLn)
	go relay(httpLn, inner)
	return ln.Addr().String(), g.cred, localAPICred, nil
}

// closeServer stands in for s.Close, closing the guarded listener with the node.
func closeServer(s *tsnet.Server) error {
	guarded.Lock()
	g := guarded.m[s]
	delete(guarded.m, s)
	guarded.Unlock()
	if g != nil {
		g.ln.Close()
	}
	return s.Close()
}

func relay(ln net.Listener, to string) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			u, err := net.Dial("tcp", to)
			if err != nil {
				return
			}
			defer u.Close()
			done := make(chan struct{}, 2)
			go func() { io.Copy(u, c); done <- struct{}{} }()
			go func() { io.Copy(c, u); done <- struct{}{} }()
			<-done
		}()
	}
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// upstream is what the proxy needs from the node.
type upstream interface {
	// running reports whether the node's backend is Running.
	running(ctx context.Context) bool
	// suffix is the node's MagicDNS suffix ("tailabc123.ts.net"), or "" while unknown.
	suffix(ctx context.Context) string
	// peerAddr resolves a MagicDNS name from the node's own peers, never public DNS.
	peerAddr(ctx context.Context, host string) (netip.Addr, bool)
	// isPeer reports whether ip is one of the node's peers' tailnet addresses.
	isPeer(ctx context.Context, ip netip.Addr) bool
	// endpoints are where the node last found itself reachable from outside, as
	// host prefixes: the phone's own addresses and its router's public ones.
	endpoints() []netip.Prefix
	// dial connects to an IP literal through the node: netstack for peers, the
	// default network for anything else.
	dial(ctx context.Context, addr netip.AddrPort) (net.Conn, error)
}

type proxy struct {
	n      upstream
	lookup func(ctx context.Context, host string) ([]netip.Addr, error)
	phone  func() []netip.Prefix // the phone's own networks
}

const holdPoll = 200 * time.Millisecond

var errRefused = errors.New("refused")

// dial is the SOCKS5 server's dialer, for CONNECT and UDP ASSOCIATE alike. Only TCP is
// served, as on Android. It holds a request while the node starts, within the server's
// own deadline.
func (p *proxy) dial(ctx context.Context, network, target string) (net.Conn, error) {
	if network != "tcp" {
		return nil, errRefused
	}
	for !p.n.running(ctx) {
		select {
		case <-ctx.Done():
			return nil, errors.New("node not running")
		case <-time.After(holdPoll):
		}
	}
	addrs, err := p.resolve(ctx, target)
	if err != nil {
		return nil, err
	}
	return dialFirst(ctx, p.n, addrs)
}

// attemptDelay is how long one address has before the next starts alongside it,
// as RFC 8305 recommends.
const attemptDelay = 250 * time.Millisecond

// dialFirst dials addrs in turn, Happy Eyeballs style: each attempt has attemptDelay
// before the next starts, a failure starts the next at once, and the first connection
// wins. The others are canceled, and closed if they connect anyway.
func dialFirst(ctx context.Context, n upstream, addrs []netip.AddrPort) (net.Conn, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		conn net.Conn
		err  error
	}
	results := make(chan result, len(addrs))
	next, pending := 0, 0
	start := func() {
		a := addrs[next]
		next, pending = next+1, pending+1
		go func() {
			c, err := n.dial(ctx, a)
			results <- result{c, err}
		}()
	}
	start()
	var firstErr error
	for pending > 0 {
		var later <-chan time.Time
		if next < len(addrs) {
			later = time.After(attemptDelay)
		}
		select {
		case r := <-results:
			pending--
			if r.err == nil {
				go func(left int) {
					for ; left > 0; left-- {
						if r := <-results; r.err == nil {
							r.conn.Close()
						}
					}
				}(pending)
				return r.conn, nil
			}
			if firstErr == nil {
				firstErr = r.err
			}
			if next < len(addrs) {
				start()
			}
		case <-later:
			start()
		}
	}
	return nil, firstErr
}

// resolve picks the addresses to dial, in the order to try them. A name in a
// tailnet resolves only from the node's peers, never public DNS. Every other
// address must be on the internet: not the phone itself, its local network or
// any tailnet, unless it is one of the node's peers.
func (p *proxy) resolve(ctx context.Context, target string) ([]netip.AddrPort, error) {
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return nil, err
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil || port == 0 {
		return nil, fmt.Errorf("bad port %q", portText)
	}
	host = strings.TrimRight(lowerASCII(host), ".")
	if ip, err := netip.ParseAddr(host); err == nil {
		a, err := p.allowed(ctx, ip, uint16(port))
		if err != nil {
			return nil, err
		}
		return []netip.AddrPort{a}, nil
	}
	if isTailnetName(host, p.n.suffix(ctx)) {
		if ip, ok := p.n.peerAddr(ctx, host); ok {
			return []netip.AddrPort{netip.AddrPortFrom(ip, uint16(port))}, nil
		}
		return nil, errRefused
	}
	ips, err := p.lookup(ctx, host)
	if err != nil {
		return nil, err
	}
	var addrs []netip.AddrPort
	for _, ip := range ips {
		if a, err := p.allowed(ctx, ip, uint16(port)); err == nil {
			addrs = append(addrs, a)
		}
	}
	if len(addrs) == 0 {
		return nil, errRefused
	}
	return interleave(addrs), nil
}

// interleave alternates the address families, starting with the first
// address's, as RFC 8305 orders them, so a broken IPv6 path costs one
// attemptDelay, not one per IPv6 address.
func interleave(addrs []netip.AddrPort) []netip.AddrPort {
	var same, other []netip.AddrPort
	for _, a := range addrs {
		if a.Addr().Is4() == addrs[0].Addr().Is4() {
			same = append(same, a)
		} else {
			other = append(other, a)
		}
	}
	out := make([]netip.AddrPort, 0, len(addrs))
	for i := 0; len(out) < len(addrs); i++ {
		if i < len(same) {
			out = append(out, same[i])
		}
		if i < len(other) {
			out = append(out, other[i])
		}
	}
	return out
}

func isTailnetName(host, suffix string) bool {
	return host == "ts.net" || strings.HasSuffix(host, ".ts.net") ||
		(suffix != "" && (host == suffix || strings.HasSuffix(host, "."+suffix)))
}

// lowerASCII lowercases ASCII letters only: host names are ASCII once encoded, and
// strings.ToLower would turn the Kelvin sign (U+212A) into "k" and let a name pass as a
// tailnet one.
func lowerASCII(s string) string {
	b := []byte(s)
	for i, c := range b {
		if 'A' <= c && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}

var wellKnownNAT64 = netip.MustParsePrefix("64:ff9b::/96")

// allowed returns the address to dial for ip, or errRefused. An IPv4-mapped
// address is checked and dialed as IPv4. A NAT64 address under the well-known
// prefix is checked both as itself and by the IPv4 address inside it (Android also
// unwraps the network's own NAT64 prefix, which iOS doesn't tell Go). Nothing inside a network the phone is on passes, so its own
// public addresses and its neighbors' global IPv6 addresses are refused too, and
// neither does an endpoint of the node, so the home's public IPv4 behind NAT,
// where many routers answer, is refused. isPeer has just refreshed the node's
// status, and the node is running.
func (p *proxy) allowed(ctx context.Context, ip netip.Addr, port uint16) (netip.AddrPort, error) {
	ip = ip.Unmap().WithZone("")
	if p.n.isPeer(ctx, ip) {
		return netip.AddrPortFrom(ip, port), nil
	}
	own := append(slices.Clip(p.phone()), p.n.endpoints()...)
	check := []netip.Addr{ip}
	if wellKnownNAT64.Contains(ip) {
		b := ip.As16()
		check = append(check, netip.AddrFrom4([4]byte(b[12:])))
	}
	for _, a := range check {
		if !internet(a) {
			return netip.AddrPort{}, errRefused
		}
		for _, own := range own {
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

// phone caches the phone's own networks for the proxy's refusals, read again once
// they are phoneNetsFresh old.
var phone struct {
	sync.Mutex
	own []netip.Prefix
	at  time.Time
}

const phoneNetsFresh = 2 * time.Second

func phoneNetworks() []netip.Prefix {
	phone.Lock()
	defer phone.Unlock()
	if time.Since(phone.at) >= phoneNetsFresh {
		if own, err := ownPrefixes(); err == nil {
			phone.own, phone.at = own, time.Now()
		}
	}
	return phone.own
}

// ownPrefixes lists every interface's address with its prefix: the phone itself
// and the networks it is on. An empty list is an error, since loopback is always there.
func ownPrefixes() ([]netip.Prefix, error) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil, err
	}
	var own []netip.Prefix
	for _, a := range addrs {
		ipn, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip, ok := netip.AddrFromSlice(ipn.IP)
		if !ok {
			continue
		}
		ip = ip.Unmap()
		bits, _ := ipn.Mask.Size()
		if bits < 0 || bits > ip.BitLen() {
			bits = ip.BitLen()
		}
		own = append(own, widen(ip, bits))
	}
	if len(own) == 0 {
		return nil, errors.New("no interface addresses")
	}
	return own, nil
}

// widen returns the prefix of an interface address. A home is usually delegated a /56
// and numbers its other networks (guest, IoT, a mesh node's) inside it, so a global IPv6
// /64 stands for its /56. On cellular that also covers other subscribers' /64s, which no
// app needs.
func widen(ip netip.Addr, bits int) netip.Prefix {
	if ip.Is6() && ip.IsGlobalUnicast() && bits > 56 {
		bits = 56
	}
	p, _ := ip.Prefix(bits)
	return p
}

// tsnetNode is the proxy's side of a libtailscale node.
type tsnetNode struct {
	s *tsnet.Server

	peersMu    sync.Mutex
	peers      *ipnstate.Status
	peersAt    time.Time
	lastSuffix atomic.Pointer[string]
}

// A lookup that misses asks again if the snapshot is older than this, so a peer
// that just appeared is found without asking on every request.
const (
	peersFresh   = 2 * time.Second
	peersRecheck = 250 * time.Millisecond
)

func (n *tsnetNode) running(ctx context.Context) bool {
	lc, err := n.s.LocalClient()
	if err != nil {
		return false
	}
	st, err := lc.StatusWithoutPeers(ctx)
	return err == nil && st.BackendState == "Running"
}

func (n *tsnetNode) rememberPeers(st *ipnstate.Status) {
	n.peersMu.Lock()
	defer n.peersMu.Unlock()
	n.peers, n.peersAt = st, time.Now()
}

// status returns a recent status with peers, asking again when it is older than maxAge.
func (n *tsnetNode) status(ctx context.Context, maxAge time.Duration) *ipnstate.Status {
	n.peersMu.Lock()
	st, at := n.peers, n.peersAt
	n.peersMu.Unlock()
	if st != nil && time.Since(at) < maxAge {
		return st
	}
	lc, err := n.s.LocalClient()
	if err != nil {
		return st
	}
	fresh, err := lc.Status(ctx)
	if err != nil {
		return st
	}
	n.rememberPeers(fresh)
	return fresh
}

// suffix is the node's MagicDNS suffix, or the last one seen when status can't be
// read, so a name on a network that isn't *.ts.net never goes to the system resolver
// for want of it.
func (n *tsnetNode) suffix(ctx context.Context) string {
	s := ""
	if st := n.status(ctx, peersFresh); st != nil {
		if st.CurrentTailnet != nil && st.CurrentTailnet.MagicDNSSuffix != "" {
			s = lowerASCII(strings.TrimSuffix(st.CurrentTailnet.MagicDNSSuffix, "."))
		} else if st.Self != nil {
			if _, rest, ok := strings.Cut(strings.TrimSuffix(st.Self.DNSName, "."), "."); ok {
				s = lowerASCII(rest)
			}
		}
	}
	if s != "" {
		n.lastSuffix.Store(&s)
		return s
	}
	if last := n.lastSuffix.Load(); last != nil {
		return *last
	}
	return ""
}

// peer returns a peer that match accepts, from a status at most peersFresh old or, on
// a miss, one at most peersRecheck old.
func (n *tsnetNode) peer(ctx context.Context, match func(*ipnstate.PeerStatus) bool) *ipnstate.PeerStatus {
	for _, maxAge := range []time.Duration{peersFresh, peersRecheck} {
		if st := n.status(ctx, maxAge); st != nil {
			for _, p := range st.Peer {
				if match(p) {
					return p
				}
			}
		}
	}
	return nil
}

func (n *tsnetNode) peerAddr(ctx context.Context, host string) (netip.Addr, bool) {
	p := n.peer(ctx, func(p *ipnstate.PeerStatus) bool {
		return len(p.TailscaleIPs) > 0 && lowerASCII(strings.TrimSuffix(p.DNSName, ".")) == host
	})
	if p == nil {
		return netip.Addr{}, false
	}
	for _, ip := range p.TailscaleIPs {
		if ip.Is4() {
			return ip, true
		}
	}
	return p.TailscaleIPs[0], true
}

// endpoints come from the node's last status: what its magicsock found, the
// local, STUN and port-mapped addresses. None until the node has looked.
func (n *tsnetNode) endpoints() []netip.Prefix {
	n.peersMu.Lock()
	st := n.peers
	n.peersMu.Unlock()
	if st == nil || st.Self == nil {
		return nil
	}
	var out []netip.Prefix
	for _, s := range st.Self.Addrs {
		if ap, err := netip.ParseAddrPort(s); err == nil {
			ip := ap.Addr().Unmap().WithZone("")
			out = append(out, netip.PrefixFrom(ip, ip.BitLen()))
		}
	}
	return out
}

func (n *tsnetNode) isPeer(ctx context.Context, ip netip.Addr) bool {
	return n.peer(ctx, func(p *ipnstate.PeerStatus) bool { return slices.Contains(p.TailscaleIPs, ip) }) != nil
}

func (n *tsnetNode) dial(ctx context.Context, addr netip.AddrPort) (net.Conn, error) {
	return n.s.Dial(ctx, "tcp", addr.String())
}
