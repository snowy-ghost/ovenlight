package main

// The guarded loopback proxy's tests: Ovenlight for Android's proxy tests against the
// dialer, then the whole proxy through a real node and SOCKS5 client.

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	socksclient "golang.org/x/net/proxy"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netns"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

type fakeNode struct {
	up     atomic.Bool
	sfx    string
	peers  map[string]netip.Addr
	target string // where every dial really goes: the test's echo server
	eps    []netip.Prefix
	// before runs ahead of each dial; an error fails it. Tests set it before any dial.
	before func(ctx context.Context, a netip.AddrPort) error
	open   atomic.Int32 // connections dialed and not yet closed

	mu    sync.Mutex
	dials []netip.AddrPort
}

type countedConn struct {
	net.Conn
	open *atomic.Int32
	once sync.Once
}

func (c *countedConn) Close() error {
	c.once.Do(func() { c.open.Add(-1) })
	return c.Conn.Close()
}

func (f *fakeNode) running(context.Context) bool  { return f.up.Load() }
func (f *fakeNode) suffix(context.Context) string { return f.sfx }
func (f *fakeNode) peerAddr(_ context.Context, h string) (netip.Addr, bool) {
	ip, ok := f.peers[h]
	return ip, ok
}
func (f *fakeNode) isPeer(_ context.Context, ip netip.Addr) bool {
	for _, p := range f.peers {
		if p == ip {
			return true
		}
	}
	return false
}
func (f *fakeNode) endpoints() []netip.Prefix { return f.eps }
func (f *fakeNode) dial(ctx context.Context, a netip.AddrPort) (net.Conn, error) {
	f.mu.Lock()
	f.dials = append(f.dials, a)
	f.mu.Unlock()
	if f.before != nil {
		if err := f.before(ctx, a); err != nil {
			return nil, err
		}
	}
	var d net.Dialer
	c, err := d.DialContext(context.WithoutCancel(ctx), "tcp", f.target)
	if err != nil {
		return nil, err
	}
	f.open.Add(1)
	return &countedConn{Conn: c, open: &f.open}, nil
}

func (f *fakeNode) lastDial() netip.AddrPort {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.dials) == 0 {
		return netip.AddrPort{}
	}
	return f.dials[len(f.dials)-1]
}

func echoServer(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return ln.Addr().String()
}

// newProxy returns a proxy for a running node on the tailnet tailabc123.ts.net.
func newProxy(t *testing.T) (*proxy, *fakeNode, *atomic.Int32) {
	own := &fakeNode{sfx: "tailabc123.ts.net", target: echoServer(t), peers: map[string]netip.Addr{
		"recipe-box.tailabc123.ts.net": netip.MustParseAddr("100.101.102.103"),
		"v6-only.tailabc123.ts.net":    netip.MustParseAddr("fd7a:115c:a1e0::5"),
	}}
	own.up.Store(true)
	var lookups atomic.Int32
	p := &proxy{n: own, phone: func() []netip.Prefix { return nil }}
	p.lookup = func(_ context.Context, host string) ([]netip.Addr, error) {
		lookups.Add(1)
		switch host {
		case "cdn.example.com":
			return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
		case "rebind.example.com":
			return []netip.Addr{netip.MustParseAddr("192.168.1.1")}, nil
		case "mapped-rebind.example.com":
			return []netip.Addr{netip.MustParseAddr("::ffff:10.0.0.1")}, nil
		case "dual.example.com":
			return []netip.Addr{netip.MustParseAddr("2606:4700::1111"), netip.MustParseAddr("2606:4700::1001"),
				netip.MustParseAddr("192.168.1.1"), netip.MustParseAddr("93.184.215.14")}, nil
		case "mixed.example.com":
			return []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("93.184.215.15")}, nil
		}
		return nil, errors.New("no such host")
	}
	return p, own, &lookups
}

func opens(t *testing.T, p *proxy, target string) bool {
	t.Helper()
	c, err := p.dial(context.Background(), "tcp", target)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

func TestOvenlightTailnetNamesResolveOnlyFromPeers(t *testing.T) {
	p, _, lookups := newProxy(t)
	for _, target := range []string{
		"gone.tailabc123.ts.net:443",
		"someone-else.tail999.ts.net:443",
		"RECIPE-BOX.TAIL999.TS.NET.:443",
		"ts.net:443",
	} {
		if opens(t, p, target) {
			t.Errorf("%s: opened", target)
		}
	}
	if n := lookups.Load(); n != 0 {
		t.Errorf("%d tailnet names went to the system resolver", n)
	}
	if !opens(t, p, "Recipe-Box.TailABC123.ts.net..:443") {
		t.Error("letter case and final dots")
	}
	if !opens(t, p, "v6-only.tailabc123.ts.net:443") {
		t.Error("an IPv6 peer")
	}
}

func TestOvenlightRefusesThePhoneItsNetworkAndOtherTailnets(t *testing.T) {
	p, own, _ := newProxy(t)
	for _, target := range []string{
		"127.0.0.1:8080", "[::1]:443", "[::ffff:127.0.0.1]:443", "[::ffff:7f00:1]:443",
		"192.168.1.1:443", "[::ffff:192.168.1.1]:443", "10.0.0.1:443", "172.16.0.1:443",
		"169.254.169.254:80", "[fe80::1%en0]:443", "[fe80::1]:443", "0.0.0.0:443", "[::]:443",
		"100.99.1.1:443", "[::ffff:100.99.1.1]:443", "100.100.100.100:53", "[fd7a:115c:a1e0::9]:443", "[fd00::1]:443",
		"[64:ff9b::7f00:1]:443", "[64:ff9b::c0a8:101]:443", "[64:ff9b::6463:101]:443", "[64:ff9b:1::c0a8:101]:443",
		"198.18.0.1:443", "192.0.2.1:443", "240.0.0.1:443", "255.255.255.255:443", "224.0.0.1:443",
		"[2002:c0a8:101::1]:443", "[2001:db8::1]:443", "[ff02::1]:443",
		"[::7f00:1]:443", "[::c0a8:101]:443", "[::ffff:0:7f00:1]:443", "[100:0:0:1::1]:443",
		"rebind.example.com:443", "mapped-rebind.example.com:443", "nowhere.example.com:443",
		"cdn.example.com:0", "cdn.example.com:65536", "cdn.example.com:https", "cdn.example.com",
	} {
		if opens(t, p, target) {
			t.Errorf("%s: opened", target)
		}
	}
	for _, tc := range []struct{ target, dialed string }{
		{"cdn.example.com:443", "93.184.215.14:443"},
		{"mixed.example.com:443", "93.184.215.15:443"},
		{"93.184.215.14:443", "93.184.215.14:443"},
		{"[::ffff:93.184.215.14]:443", "93.184.215.14:443"},
		{"[64:ff9b::5db8:d70e]:443", "[64:ff9b::5db8:d70e]:443"},
		{"[2606:4700::1111]:443", "[2606:4700::1111]:443"},
		{"100.101.102.103:443", "100.101.102.103:443"},
		{"[::ffff:100.101.102.103]:443", "100.101.102.103:443"},
		{"[fd7a:115c:a1e0::5]:443", "[fd7a:115c:a1e0::5]:443"},
	} {
		if !opens(t, p, tc.target) {
			t.Errorf("%s: refused", tc.target)
			continue
		}
		if got := own.lastDial(); got != netip.MustParseAddrPort(tc.dialed) {
			t.Errorf("%s: dialed %v, want %s", tc.target, got, tc.dialed)
		}
	}
}

func TestOvenlightRefusesThePhonesOwnNetworks(t *testing.T) {
	p, own, _ := newProxy(t)
	p.phone = func() []netip.Prefix {
		return []netip.Prefix{
			netip.MustParsePrefix("192.168.1.20/24"),                 // Wi-Fi
			widen(netip.MustParseAddr("2a02:1810:4d00:1234::7"), 64), // Wi-Fi's global IPv6
			netip.MustParsePrefix("172.58.10.20/32"),                 // a carrier's public IPv4
			netip.MustParsePrefix("2607:fb90:1234:5678::2/64"),       // the carrier's IPv6
			netip.MustParsePrefix("100.64.0.0/10"),                   // another VPN's tailnet
			netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
		}
	}
	lookup := p.lookup
	p.lookup = func(ctx context.Context, host string) ([]netip.Addr, error) {
		if host == "neighbor.example.com" {
			return []netip.Addr{netip.MustParseAddr("2a02:1810:4d00:1234::99")}, nil
		}
		return lookup(ctx, host)
	}
	for _, target := range []string{
		"[2a02:1810:4d00:1234::99]:443", "[2a02:1810:4d00:1234::99%en0]:443", "neighbor.example.com:443",
		"[2a02:1810:4d00:1235::1]:443", "[2a02:1810:4d00:12ff::1]:443", "[2a02:1810:4d00:1200::1]:443",
		"[2a02:1810:4d00:1234::7]:443", "[2607:fb90:1234:5678::2]:443", "[2607:fb90:1234:5678::1]:443",
		"172.58.10.20:443", "[::ffff:172.58.10.20]:443", "[64:ff9b::ac3a:a14]:443",
	} {
		if opens(t, p, target) {
			t.Errorf("%s: opened", target)
		}
	}
	for _, target := range []string{
		"[2001:67c:2b0:db32::5db8:d70e]:443", "[64:ff9b::5db8:d70e]:443", "[2606:4700::1111]:443",
		"93.184.215.14:443", "172.58.10.21:443", "cdn.example.com:443",
		"100.101.102.103:443", "[fd7a:115c:a1e0::5]:443", "recipe-box.tailabc123.ts.net:443",
	} {
		if !opens(t, p, target) {
			t.Errorf("%s: refused", target)
		}
	}
	if got := own.lastDial(); got != netip.MustParseAddrPort("100.101.102.103:443") {
		t.Errorf("the peer was dialed at %v", got)
	}
}

func TestOvenlightWidensAGlobalIPv6Network(t *testing.T) {
	for _, tc := range []struct {
		ip   string
		bits int
		want string
	}{
		{"192.168.1.20", 24, "192.168.1.0/24"},
		{"2a02:1810:4d00:1234::7", 64, "2a02:1810:4d00:1200::/56"},
		{"2607:fb90:1234:5678::2", 128, "2607:fb90:1234:5600::/56"},
		{"fe80::1", 64, "fe80::/64"},
		{"127.0.0.1", 8, "127.0.0.0/8"},
	} {
		if got := widen(netip.MustParseAddr(tc.ip), tc.bits); got.String() != tc.want {
			t.Errorf("%s/%d: %v, want %s", tc.ip, tc.bits, got, tc.want)
		}
	}
	own, err := ownPrefixes()
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, p := range own {
		found = found || p.Contains(netip.MustParseAddr("127.0.0.1"))
	}
	if !found {
		t.Errorf("loopback missing from this computer's networks: %v", own)
	}
}

func TestOvenlightRefusesTheRoutersPublicAddress(t *testing.T) {
	n := &tsnetNode{}
	n.rememberPeers(&ipnstate.Status{Self: &ipnstate.PeerStatus{Addrs: []string{
		"192.168.1.20:41641", "81.2.69.160:41641", "[::ffff:81.2.69.160]:3478", "[2a02:1810:4d00:1234::7]:41641", "nonsense",
	}}})
	if got := fmt.Sprint(n.endpoints()); got != "[192.168.1.20/32 81.2.69.160/32 81.2.69.160/32 2a02:1810:4d00:1234::7/128]" {
		t.Errorf("endpoints: %s", got)
	}
	p, own, _ := newProxy(t)
	own.eps = n.endpoints()
	for _, target := range []string{"81.2.69.160:443", "[::ffff:81.2.69.160]:443", "[64:ff9b::5102:45a0]:443", "[2a02:1810:4d00:1234::7]:443"} {
		if opens(t, p, target) {
			t.Errorf("%s: opened", target)
		}
	}
	for _, target := range []string{"81.2.69.161:443", "[64:ff9b::5102:45a1]:443", "93.184.215.14:443", "[2a02:1810:4d00:1234::8]:443"} {
		if !opens(t, p, target) {
			t.Errorf("%s: refused", target)
		}
	}
}

func TestOvenlightServesOnlyTCP(t *testing.T) {
	p, own, _ := newProxy(t)
	for _, network := range []string{"udp", "udp4", "udp6", "tcp4", "tcp6", "ip"} {
		if c, err := p.dial(context.Background(), network, "93.184.215.14:443"); err == nil {
			c.Close()
			t.Errorf("%s: opened", network)
		}
	}
	if len(own.dials) != 0 {
		t.Errorf("dialed %v", own.dials)
	}
}

func TestOvenlightHoldsWhileTheNodeStarts(t *testing.T) {
	p, own, _ := newProxy(t)
	own.up.Store(false)
	time.AfterFunc(3*holdPoll, func() { own.up.Store(true) })
	if !opens(t, p, "cdn.example.com:443") {
		t.Error("refused while the node started")
	}
	own.up.Store(false)
	ctx, cancel := context.WithTimeout(context.Background(), 3*holdPoll)
	defer cancel()
	if _, err := p.dial(ctx, "tcp", "cdn.example.com:443"); err == nil {
		t.Error("opened with no node running")
	}
}

func TestOvenlightTriesTheNextFamily(t *testing.T) {
	v4 := netip.MustParseAddr("93.184.215.14")
	for _, tc := range []struct {
		name   string
		v6, v4 func(context.Context) error
		opens  bool
		within time.Duration
	}{
		{"IPv6 hangs", hang, nil, true, 2 * attemptDelay},
		{"IPv6 fails at once", fail, nil, true, attemptDelay / 2},
		{"both fail", fail, fail, false, attemptDelay / 2},
		{"both hang", hang, hang, false, 2 * time.Second},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p, own, _ := newProxy(t)
			own.before = func(ctx context.Context, a netip.AddrPort) error {
				if f := map[bool]func(context.Context) error{true: tc.v6, false: tc.v4}[a.Addr().Is6()]; f != nil {
					return f(ctx)
				}
				return nil
			}
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			start := time.Now()
			c, err := p.dial(ctx, "tcp", "dual.example.com:443")
			if (err == nil) != tc.opens {
				t.Fatalf("got %v, want open %v", err, tc.opens)
			}
			if d := time.Since(start); d > tc.within {
				t.Errorf("answered after %v", d)
			}
			if c != nil {
				c.Close()
				if own.lastDial() != netip.AddrPortFrom(v4, 443) {
					t.Errorf("not through IPv4: last dial %v", own.lastDial())
				}
			}
			own.mu.Lock()
			defer own.mu.Unlock()
			for _, a := range own.dials {
				if a.Addr() == netip.MustParseAddr("192.168.1.1") {
					t.Error("a refused address was dialed")
				}
			}
		})
	}
}

func hang(ctx context.Context) error { <-ctx.Done(); return ctx.Err() }
func fail(context.Context) error     { return errors.New("unreachable") }

func TestOvenlightALateConnectionIsClosed(t *testing.T) {
	p, own, _ := newProxy(t)
	own.before = func(_ context.Context, a netip.AddrPort) error {
		if a.Addr().Is6() {
			time.Sleep(2 * attemptDelay) // connects anyway, after IPv4 won
		}
		return nil
	}
	c, err := p.dial(context.Background(), "tcp", "dual.example.com:443")
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(3 * attemptDelay)
	if n := own.open.Load(); n != 1 {
		t.Errorf("%d connections open, want the winner's alone", n)
	}
	c.Close()
	if n := own.open.Load(); n != 0 {
		t.Errorf("%d connections left open", n)
	}
}

func TestOvenlightInterleavesTheFamilies(t *testing.T) {
	var addrs []netip.AddrPort
	for _, s := range []string{"[2606:4700::1]:443", "[2606:4700::2]:443", "[2606:4700::3]:443", "1.1.1.1:443", "1.0.0.1:443"} {
		addrs = append(addrs, netip.MustParseAddrPort(s))
	}
	if got := fmt.Sprint(interleave(addrs)); got != "[[2606:4700::1]:443 1.1.1.1:443 [2606:4700::2]:443 1.0.0.1:443 [2606:4700::3]:443]" {
		t.Errorf("IPv6 first: %s", got)
	}
	if got := fmt.Sprint(interleave(addrs[2:])); got != "[[2606:4700::3]:443 1.1.1.1:443 1.0.0.1:443]" {
		t.Errorf("one IPv6: %s", got)
	}
	if got := fmt.Sprint(interleave([]netip.AddrPort{addrs[3], addrs[0], addrs[4]})); got != "[1.1.1.1:443 [2606:4700::1]:443 1.0.0.1:443]" {
		t.Errorf("IPv4 first: %s", got)
	}
}

// TestOvenlightLoopbackEndToEnd runs two nodes on a test control server and goes
// through the first one's guarded loopback, as a web view's data store does: the
// other node by address and by name, and the internet, open; this computer's loopback
// and local network don't; the LocalAPI answers on the same port.
func TestOvenlightLoopbackEndToEnd(t *testing.T) {
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	control := &testcontrol.Server{DERPMap: integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1"), MagicDNSDomain: "e2e.example"}
	control.HTTPTestServer = httptest.NewServer(control)
	t.Cleanup(control.HTTPTestServer.Close)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	node := func(name string) *tsnet.Server {
		s := &tsnet.Server{Dir: t.TempDir(), ControlURL: control.HTTPTestServer.URL, Hostname: name, Logf: logger.Discard}
		if _, err := s.Up(ctx); err != nil {
			t.Fatal(err)
		}
		return s
	}
	phoneNode, appNode := node("phone"), node("recipe-box")
	t.Cleanup(func() { appNode.Close() })
	appLn, err := appNode.Listen("tcp", ":80")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(appLn, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "recipes") }))
	appIP, _ := appNode.TailscaleIPs()

	// Services on this computer a page must not reach: its loopback and its LAN address.
	local, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { local.Close() })
	go http.Serve(local, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "local") }))
	localPort := local.Addr().(*net.TCPAddr).Port
	targets := []string{fmt.Sprintf("127.0.0.1:%d", localPort), fmt.Sprintf("[::1]:%d", localPort), fmt.Sprintf("[::ffff:127.0.0.1]:%d", localPort), fmt.Sprintf("localhost:%d", localPort)}
	if lan := lanAddress(t); lan.IsValid() {
		targets = append(targets, netip.AddrPortFrom(lan, uint16(localPort)).String())
	} else {
		t.Log("no LAN address to try")
	}

	addr, cred, localAPICred, err := guardedLoopback(phoneNode)
	if err != nil {
		t.Fatal(err)
	}
	if again, _, _, _ := guardedLoopback(phoneNode); again != addr {
		t.Errorf("a second call moved the proxy from %s to %s", addr, again)
	}
	socks, err := socksclient.SOCKS5("tcp", addr, &socksclient.Auth{User: "tsnet", Password: cred}, &net.Dialer{Timeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{DialContext: socks.(socksclient.ContextDialer).DialContext}}
	get := func(url string) (string, error) {
		res, err := client.Get(url)
		if err != nil {
			return "", err
		}
		defer res.Body.Close()
		b, err := io.ReadAll(res.Body)
		return string(b), err
	}
	for _, url := range []string{"http://" + appIP.String() + "/", "http://recipe-box.e2e.example/"} {
		if body, err := get(url); body != "recipes" {
			t.Errorf("%s: %q %v", url, body, err)
		}
	}
	for _, target := range targets {
		if body, err := get("http://" + target + "/"); err == nil {
			t.Errorf("%s: opened, %q", target, body)
		}
	}
	if c, err := (&net.Dialer{Timeout: 5 * time.Second}).Dial("tcp", "example.com:443"); err != nil {
		t.Logf("no internet here (%v): skipping the public site", err)
	} else {
		c.Close()
		conn, err := socks.Dial("tcp", "example.com:443")
		if err != nil {
			t.Errorf("example.com: %v", err)
		} else {
			tc := tls.Client(conn, &tls.Config{ServerName: "example.com"})
			if err := tc.Handshake(); err != nil {
				t.Errorf("example.com TLS: %v", err)
			}
			tc.Close()
		}
	}
	wrong, _ := socksclient.SOCKS5("tcp", addr, &socksclient.Auth{User: "tsnet", Password: "wrong"}, socksclient.Direct)
	if c, err := wrong.Dial("tcp", appIP.String()+":80"); err == nil {
		c.Close()
		t.Error("a wrong password was let through")
	}

	req, _ := http.NewRequestWithContext(ctx, "GET", "http://"+addr+"/localapi/v0/status", nil)
	req.Header.Set("Sec-Tailscale", "localapi")
	req.SetBasicAuth("", localAPICred)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !strings.Contains(string(b), "BackendState") {
		t.Errorf("LocalAPI status: %d %.200s", res.StatusCode, b)
	}

	if err := closeServer(phoneNode); err != nil {
		t.Fatal(err)
	}
	if c, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		c.Close()
		t.Error("the proxy outlived its node")
	}
}

// lanAddress is this computer's private IPv4 address on its local network, if any.
func lanAddress(t *testing.T) netip.Addr {
	t.Helper()
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		t.Fatal(err)
	}
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok {
			if ip, ok := netip.AddrFromSlice(ipn.IP); ok && ip.Unmap().Is4() && ip.Unmap().IsPrivate() {
				return ip.Unmap()
			}
		}
	}
	return netip.Addr{}
}
