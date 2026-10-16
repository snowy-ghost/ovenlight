package core

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeNode struct {
	up     atomic.Bool
	sfx    string
	peers  map[string]netip.Addr
	target string // where every dial really goes: the test's echo server

	mu    sync.Mutex
	dials []netip.AddrPort
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
func (f *fakeNode) dial(ctx context.Context, a netip.AddrPort) (net.Conn, error) {
	f.mu.Lock()
	f.dials = append(f.dials, a)
	f.mu.Unlock()
	var d net.Dialer
	return d.DialContext(ctx, "tcp", f.target)
}

func (f *fakeNode) lastDial() netip.AddrPort {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.dials) == 0 {
		return netip.AddrPort{}
	}
	return f.dials[len(f.dials)-1]
}

type harness struct {
	p       *proxy
	mu      sync.Mutex
	nodes   map[string]upstream
	lookups atomic.Int32
	fatal   chan error
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

// newHarness starts a proxy with an owner node "own" (suffix tailabc123.ts.net)
// and a guest node "guest" (a Headscale network named e2e.example), both running.
func newHarness(t *testing.T) (*harness, *fakeNode, *fakeNode) {
	echo := echoServer(t)
	own := &fakeNode{sfx: "tailabc123.ts.net", target: echo, peers: map[string]netip.Addr{
		"recipe-box.tailabc123.ts.net": netip.MustParseAddr("100.101.102.103"),
		"v6-only.tailabc123.ts.net":    netip.MustParseAddr("fd7a:115c:a1e0::5"),
	}}
	guest := &fakeNode{sfx: "e2e.example", target: echo, peers: map[string]netip.Addr{
		"house-guide.e2e.example": netip.MustParseAddr("100.64.0.7"),
	}}
	own.up.Store(true)
	guest.up.Store(true)
	h := &harness{nodes: map[string]upstream{"own": own, "guest": guest}, fatal: make(chan error, 1)}
	p, err := startProxy(h.node, nil, func(err error) { h.fatal <- err })
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { p.srv.Close() })
	p.phone = func() phoneNets { return phoneNets{} }
	p.lookup = func(_ context.Context, host string) ([]netip.Addr, error) {
		h.lookups.Add(1)
		switch host {
		case "cdn.example.com":
			return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
		case "rebind.example.com":
			return []netip.Addr{netip.MustParseAddr("192.168.1.1")}, nil
		case "mapped-rebind.example.com":
			return []netip.Addr{netip.MustParseAddr("::ffff:10.0.0.1")}, nil
		case "mixed.example.com":
			return []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("93.184.215.15")}, nil
		}
		return nil, errors.New("no such host")
	}
	h.p = p
	p.know("own")
	p.know("guest")
	if err := p.setRoute("app-recipes", "own"); err != nil {
		t.Fatal(err)
	}
	if err := p.setRoute("app-house", "guest"); err != nil {
		t.Fatal(err)
	}
	return h, own, guest
}

func (h *harness) node(id string) upstream {
	h.mu.Lock()
	defer h.mu.Unlock()
	if n, ok := h.nodes[id]; ok && n != nil {
		return n
	}
	return nil
}

func (h *harness) setNode(id string, n upstream) {
	h.mu.Lock()
	h.nodes[id] = n
	h.mu.Unlock()
}

func basic(key, secret string) string {
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(key+":"+secret))
}

// request sends raw request text and returns the response and the open connection.
func (h *harness) request(t *testing.T, text string) (*http.Response, *bufio.Reader, net.Conn) {
	t.Helper()
	c, err := net.Dial("tcp", h.p.addr())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := io.WriteString(c, text); err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		t.Fatal(err)
	}
	return resp, br, c
}

func (h *harness) connect(t *testing.T, key, target string) (*http.Response, *bufio.Reader, net.Conn) {
	t.Helper()
	auth := ""
	if key != "" {
		auth = "Proxy-Authorization: " + basic(key, h.p.secret) + "\r\n"
	}
	return h.request(t, fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n%s\r\n", target, target, auth))
}

func (h *harness) status(t *testing.T, key, target string) int {
	t.Helper()
	resp, _, _ := h.connect(t, key, target)
	return resp.StatusCode
}

func echoes(t *testing.T, br *bufio.Reader, c net.Conn) bool {
	t.Helper()
	if _, err := io.WriteString(c, "ping\n"); err != nil {
		return false
	}
	line, err := br.ReadString('\n')
	return err == nil && line == "ping\n"
}

func TestOnlyAConnectIsChallenged(t *testing.T) {
	h, _, _ := newHarness(t)
	for _, tc := range []struct {
		name, text string
		want       int
	}{
		{"a page's fetch of the proxy's port", "GET http://" + h.p.addr() + "/ HTTP/1.1\r\nHost: " + h.p.addr() + "\r\n\r\n", 405},
		{"another app talking to the port", "GET / HTTP/1.1\r\nHost: " + h.p.addr() + "\r\n\r\n", 400},
		{"a CONNECT without credentials", "CONNECT recipe-box.tailabc123.ts.net:443 HTTP/1.1\r\nHost: x\r\n\r\n", 407},
		{"a CONNECT with the wrong secret", "CONNECT recipe-box.tailabc123.ts.net:443 HTTP/1.1\r\nHost: x\r\nProxy-Authorization: " + basic("app-recipes", "wrong") + "\r\n\r\n", 407},
		{"a CONNECT with garbled credentials", "CONNECT recipe-box.tailabc123.ts.net:443 HTTP/1.1\r\nHost: x\r\nProxy-Authorization: Basic !!!\r\n\r\n", 407},
		{"a CONNECT with only a secret", "CONNECT recipe-box.tailabc123.ts.net:443 HTTP/1.1\r\nHost: x\r\nProxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(h.p.secret)) + "\r\n\r\n", 407},
	} {
		resp, _, _ := h.request(t, tc.text)
		chal := resp.Header.Get("Proxy-Authenticate")
		if resp.StatusCode != tc.want {
			t.Errorf("%s: got %d, want %d", tc.name, resp.StatusCode, tc.want)
		}
		if tc.want == 407 && chal != `Basic realm="`+h.p.realm+`"` {
			t.Errorf("%s: challenge %q", tc.name, chal)
		}
		if tc.want != 407 && chal != "" {
			t.Errorf("%s: challenged with %q", tc.name, chal)
		}
	}
	if !strings.HasPrefix(h.p.realm, "ovenlight-") || len(h.p.realm) != len("ovenlight-")+32 || len(h.p.secret) != 64 {
		t.Errorf("realm %q, secret of %d characters", h.p.realm, len(h.p.secret))
	}
}

func TestRoutesByKey(t *testing.T) {
	h, own, guest := newHarness(t)
	resp, br, c := h.connect(t, "app-recipes", "recipe-box.tailabc123.ts.net:443")
	if resp.StatusCode != 200 || !echoes(t, br, c) {
		t.Fatalf("owner's app: %d", resp.StatusCode)
	}
	if got := own.lastDial(); got != netip.MustParseAddrPort("100.101.102.103:443") {
		t.Errorf("dialed %v", got)
	}
	if code := h.status(t, "app-house", "house-guide.e2e.example:443"); code != 200 {
		t.Errorf("guest's app: %d", code)
	}
	if got := guest.lastDial(); got != netip.MustParseAddrPort("100.64.0.7:443") {
		t.Errorf("guest dialed %v", got)
	}
	// Each key reaches only its own membership's peers.
	if code := h.status(t, "app-house", "recipe-box.tailabc123.ts.net:443"); code != 502 {
		t.Errorf("guest key to the owner's app: %d", code)
	}
	if code := h.status(t, "app-recipes", "house-guide.e2e.example:443"); code != 502 {
		t.Errorf("owner key to the guest's app: %d", code)
	}
	if code := h.status(t, "node-own", "recipe-box.tailabc123.ts.net:8443"); code != 200 {
		t.Errorf("the owner's node key: %d", code)
	}
	for _, key := range []string{"app-unknown", "node-gone", "node-", "own", "recipes"} {
		if code := h.status(t, key, "recipe-box.tailabc123.ts.net:443"); code != 502 {
			t.Errorf("%s: got %d, want 502", key, code)
		}
	}
}

func TestTailnetNamesResolveOnlyFromPeers(t *testing.T) {
	h, _, _ := newHarness(t)
	for _, target := range []string{
		"gone.tailabc123.ts.net:443",
		"someone-else.tail999.ts.net:443",
		"RECIPE-BOX.TAIL999.TS.NET.:443",
		"ts.net:443",
		"gone.e2e.example:443",
	} {
		key := "app-recipes"
		if strings.Contains(target, "e2e.example") {
			key = "app-house"
		}
		if code := h.status(t, key, target); code != 502 {
			t.Errorf("%s: got %d, want 502", target, code)
		}
	}
	if n := h.lookups.Load(); n != 0 {
		t.Errorf("%d tailnet names went to the system resolver", n)
	}
	if code := h.status(t, "app-recipes", "Recipe-Box.TailABC123.ts.net..:443"); code != 200 {
		t.Errorf("letter case and final dots: %d", code)
	}
	if code := h.status(t, "app-recipes", "v6-only.tailabc123.ts.net:443"); code != 200 {
		t.Errorf("an IPv6 peer: %d", code)
	}
}

func TestRefusesThePhoneItsNetworkAndOtherTailnets(t *testing.T) {
	h, own, _ := newHarness(t)
	for _, target := range []string{
		"127.0.0.1:8080", "[::1]:443", "[::ffff:127.0.0.1]:443", "[::ffff:7f00:1]:443",
		"192.168.1.1:443", "[::ffff:192.168.1.1]:443", "10.0.0.1:443", "172.16.0.1:443",
		"169.254.169.254:80", "[fe80::1%25wlan0]:443", "[fe80::1]:443", "0.0.0.0:443", "[::]:443",
		"100.99.1.1:443", "[::ffff:100.99.1.1]:443", "[fd7a:115c:a1e0::9]:443", "[fd00::1]:443",
		"[64:ff9b::7f00:1]:443", "[64:ff9b::c0a8:101]:443", "[64:ff9b::6463:101]:443",
		"198.18.0.1:443", "192.0.2.1:443", "240.0.0.1:443", "255.255.255.255:443", "224.0.0.1:443",
		"[2002:c0a8:101::1]:443", "[2001:db8::1]:443", "[ff02::1]:443",
		"[::7f00:1]:443", "[::c0a8:101]:443", "[::ffff:0:7f00:1]:443", "[100:0:0:1::1]:443",
		"rebind.example.com:443", "mapped-rebind.example.com:443", "nowhere.example.com:443",
		"cdn.example.com:0", "cdn.example.com:65536",
	} {
		if code := h.status(t, "app-recipes", target); code != 502 {
			t.Errorf("%s: got %d, want 502", target, code)
		}
	}
	// Go's server refuses these before the proxy sees them.
	for _, target := range []string{"cdn.example.com:https", "cdn.example.com"} {
		if code := h.status(t, "app-recipes", target); code == 200 {
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
		if code := h.status(t, "app-recipes", tc.target); code != 200 {
			t.Errorf("%s: got %d, want 200", tc.target, code)
			continue
		}
		if got := own.lastDial(); got != netip.MustParseAddrPort(tc.dialed) {
			t.Errorf("%s: dialed %v, want %s", tc.target, got, tc.dialed)
		}
	}
}

func TestRefusesThePhonesOwnNetworks(t *testing.T) {
	h, own, _ := newHarness(t)
	h.p.phone = func() phoneNets {
		return phoneNets{
			own: []netip.Prefix{
				netip.MustParsePrefix("192.168.1.20/24"),           // Wi-Fi
				netip.MustParsePrefix("2a02:1810:4d00:1234::7/64"), // Wi-Fi's global IPv6
				netip.MustParsePrefix("172.58.10.20/32"),           // a carrier's public IPv4
				netip.MustParsePrefix("2607:fb90:1234:5678::2/64"), // the carrier's IPv6
				netip.MustParsePrefix("100.64.0.0/10"),             // another VPN's tailnet
				netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
			},
			nat64: netip.MustParsePrefix("2001:67c:2b0:db32::/96"),
		}
	}
	lookup := h.p.lookup
	h.p.lookup = func(ctx context.Context, host string) ([]netip.Addr, error) {
		if host == "neighbor.example.com" {
			return []netip.Addr{netip.MustParseAddr("2a02:1810:4d00:1234::99")}, nil
		}
		return lookup(ctx, host)
	}
	for _, target := range []string{
		"[2a02:1810:4d00:1234::99]:443", "[2a02:1810:4d00:1234::99%25wlan0]:443", "neighbor.example.com:443",
		"[2a02:1810:4d00:1234::7]:443", "[2607:fb90:1234:5678::2]:443", "[2607:fb90:1234:5678::1]:443",
		"172.58.10.20:443", "[::ffff:172.58.10.20]:443", "[64:ff9b::ac3a:a14]:443",
		"[2001:67c:2b0:db32::c0a8:101]:443", "[2001:67c:2b0:db32::7f00:1]:443", "[2001:67c:2b0:db32::ac3a:a14]:443",
	} {
		if code := h.status(t, "app-recipes", target); code != 502 {
			t.Errorf("%s: got %d, want 502", target, code)
		}
	}
	for _, target := range []string{
		"[2001:67c:2b0:db32::5db8:d70e]:443", "[64:ff9b::5db8:d70e]:443", "[2606:4700::1111]:443",
		"93.184.215.14:443", "172.58.10.21:443", "cdn.example.com:443",
		"100.101.102.103:443", "[fd7a:115c:a1e0::5]:443", "recipe-box.tailabc123.ts.net:443",
	} {
		if code := h.status(t, "app-recipes", target); code != 200 {
			t.Errorf("%s: got %d, want 200", target, code)
		}
	}
	if got := own.lastDial(); got != netip.MustParseAddrPort("100.101.102.103:443") {
		t.Errorf("the peer was dialed at %v", got)
	}
}

func TestTheNetworksNAT64Prefix(t *testing.T) {
	// The form Android writes, and the form a JVM writes, as Robolectric's tests see it.
	for _, s := range []string{"2001:67c:2b0:db32::/96", "2001:67c:2b0:db32:0:0:0:0/96"} {
		if p := parseNAT64(s); p != netip.MustParsePrefix("2001:67c:2b0:db32::/96") {
			t.Errorf("%s: %v", s, p)
		}
	}
	for _, bad := range []string{"", "nonsense", "2001:db8::/64", "2001:db8::/128", "10.0.0.0/8", "::ffff:0:0/96"} {
		if p := parseNAT64(bad); p.IsValid() {
			t.Errorf("%q was taken as %v", bad, p)
		}
	}
}

func TestBytesSentWithTheConnectArrive(t *testing.T) {
	h, _, _ := newHarness(t)
	target := "recipe-box.tailabc123.ts.net:443"
	resp, br, _ := h.request(t, fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: %s\r\n\r\nearly\n", target, target, basic("app-recipes", h.p.secret)))
	if resp.StatusCode != 200 {
		t.Fatal(resp.StatusCode)
	}
	if line, err := br.ReadString('\n'); err != nil || line != "early\n" {
		t.Fatalf("got %q, %v", line, err)
	}
}

func closedSoon(t *testing.T, br *bufio.Reader) bool {
	t.Helper()
	done := make(chan error, 1)
	go func() { _, err := br.ReadByte(); done <- err }()
	select {
	case err := <-done:
		return err != nil
	case <-time.After(3 * time.Second):
		return false
	}
}

func TestClearingARouteClosesItsTunnels(t *testing.T) {
	h, _, _ := newHarness(t)
	resp, br, c := h.connect(t, "app-recipes", "recipe-box.tailabc123.ts.net:443")
	if resp.StatusCode != 200 || !echoes(t, br, c) {
		t.Fatal("no tunnel")
	}
	_, otherBr, otherC := h.connect(t, "app-house", "house-guide.e2e.example:443")
	h.p.clearRoute("app-recipes")
	if !closedSoon(t, br) {
		t.Error("the tunnel outlived its route")
	}
	if !echoes(t, otherBr, otherC) {
		t.Error("another key's tunnel closed too")
	}
	if code := h.status(t, "app-recipes", "recipe-box.tailabc123.ts.net:443"); code != 502 {
		t.Errorf("after ClearRoute: %d", code)
	}
}

func TestMovingARouteClosesItsTunnels(t *testing.T) {
	h, _, _ := newHarness(t)
	_, br, c := h.connect(t, "app-recipes", "recipe-box.tailabc123.ts.net:443")
	if !echoes(t, br, c) {
		t.Fatal("no tunnel")
	}
	if err := h.p.setRoute("app-recipes", "own"); err != nil {
		t.Fatal(err)
	}
	if !echoes(t, br, c) {
		t.Fatal("setting the same route closed the tunnel")
	}
	if err := h.p.setRoute("app-recipes", "guest"); err != nil {
		t.Fatal(err)
	}
	if !closedSoon(t, br) {
		t.Error("the tunnel outlived its route")
	}
}

func TestBadRouteKeysAreRejected(t *testing.T) {
	h, _, _ := newHarness(t)
	for _, key := range []string{"", "app-", "node-own", "recipes", "app-a:b"} {
		if err := h.p.setRoute(key, "own"); err == nil {
			t.Errorf("%q was accepted", key)
		}
	}
	if err := h.p.setRoute("app-x", ""); err == nil {
		t.Error("an empty membership was accepted")
	}
}

func TestHoldsAConnectWhileTheNodeStarts(t *testing.T) {
	h, own, _ := newHarness(t)
	own.up.Store(false)
	time.AfterFunc(600*time.Millisecond, func() { own.up.Store(true) })
	start := time.Now()
	resp, br, c := h.connect(t, "app-recipes", "recipe-box.tailabc123.ts.net:443")
	if resp.StatusCode != 200 || !echoes(t, br, c) {
		t.Fatalf("got %d", resp.StatusCode)
	}
	if d := time.Since(start); d < 500*time.Millisecond {
		t.Errorf("answered in %v, before the node ran", d)
	}
}

func TestHoldsAConnectWhileTheNodeIsReplaced(t *testing.T) {
	h, own, _ := newHarness(t)
	h.setNode("own", nil) // closed, as when Ovenlight left; NewNode comes on return
	time.AfterFunc(400*time.Millisecond, func() { h.setNode("own", own) })
	if code := h.status(t, "app-recipes", "recipe-box.tailabc123.ts.net:443"); code != 200 {
		t.Fatalf("got %d", code)
	}
}

func TestANodeKeyIsHeldWhileItsNodeIsReplaced(t *testing.T) {
	h, own, _ := newHarness(t)
	h.setNode("own", nil)
	time.AfterFunc(400*time.Millisecond, func() { h.setNode("own", own) })
	if code := h.status(t, "node-own", "recipe-box.tailabc123.ts.net:443"); code != 200 {
		t.Fatalf("got %d", code)
	}
	h.p.clearRoute("node-own")
	if code := h.status(t, "node-own", "recipe-box.tailabc123.ts.net:443"); code != 502 {
		t.Errorf("after its membership was cleared: %d", code)
	}
}

func TestClearingARouteRefusesItsHeldConnects(t *testing.T) {
	h, own, _ := newHarness(t)
	own.up.Store(false)
	time.AfterFunc(300*time.Millisecond, func() { h.p.clearRoute("app-recipes") })
	start := time.Now()
	if code := h.status(t, "app-recipes", "recipe-box.tailabc123.ts.net:443"); code != 503 {
		t.Errorf("got %d, want 503", code)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Errorf("held for %v after the route was cleared", d)
	}
}

func TestAnswersWithinTheBudget(t *testing.T) {
	old := answerWithin.Load()
	answerWithin.Store(int64(time.Second))
	defer answerWithin.Store(old)
	h, own, _ := newHarness(t)
	own.up.Store(false)
	start := time.Now()
	if code := h.status(t, "app-recipes", "recipe-box.tailabc123.ts.net:443"); code != 503 {
		t.Errorf("got %d, want 503", code)
	}
	if d := time.Since(start); d < 900*time.Millisecond || d > 2*time.Second {
		t.Errorf("answered after %v", d)
	}
}

func TestTheProcessEndsWhenTheListenerStops(t *testing.T) {
	h, _, _ := newHarness(t)
	h.p.ln.Close()
	select {
	case err := <-h.fatal:
		if !strings.Contains(err.Error(), "listener stopped") {
			t.Errorf("fatal with %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the listener stopped and the process went on")
	}
}

func TestLogsCarryNoSignInLinksOrKeys(t *testing.T) {
	// Built from parts, so the repository's secret scanning sees no key.
	key := "tskey-" + "auth-k1234CNTRL-abcdefghijk"
	for in, want := range map[string]string{
		"To authenticate, visit: https://login.tailscale.com/a/1a2b3c4d5e6f": "To authenticate, visit: [removed]",
		"AuthURL is http://127.0.0.1:41641/register/abc-DEF_123 now":         "AuthURL is [removed] now",
		"using key " + key:                                      "using key [removed]",
		"key hskey-auth-abc123-DEF":                             "key [removed]",
		"visit http://127.0.0.1:8080/register?key=nodekey:abcd": "visit [removed]",
		"control: https://controlplane.tailscale.com/key?v=130": "control: https://controlplane.tailscale.com/key?v=130",
	} {
		if got := redact(in); got != want {
			t.Errorf("redact(%q) = %q, want %q", in, got, want)
		}
	}
}

type fakePlatform struct{ json string }

func (f fakePlatform) InterfacesJSON() string { return f.json }
func (fakePlatform) Log(string)               {}

func TestInterfacesFromThePlatform(t *testing.T) {
	old := platform
	defer func() { platform = old }()
	platform = fakePlatform{json: `[
		{"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":[{"ip":"127.0.0.1","prefixLen":8},{"ip":"::1","prefixLen":128}]},
		{"name":"wlan0","index":3,"mtu":1500,"up":true,"multicast":true,"addrs":[{"ip":"192.168.1.20","prefixLen":24},{"ip":"fe80::1%wlan0","prefixLen":64}]},
		{"name":"rmnet0","index":5,"mtu":1400,"up":false,"pointToPoint":true,"addrs":[]}
	]`}
	ifs, err := interfaces()
	if err != nil {
		t.Fatal(err)
	}
	if len(ifs) != 3 {
		t.Fatalf("%d interfaces", len(ifs))
	}
	wlan := ifs[1]
	if wlan.Name != "wlan0" || wlan.Index != 3 || wlan.MTU != 1500 || wlan.Flags&net.FlagUp == 0 || wlan.Flags&net.FlagMulticast == 0 || wlan.IsLoopback() {
		t.Errorf("wlan0: %+v", wlan.Interface)
	}
	addrs, _ := wlan.Addrs()
	if fmt.Sprint(addrs) != "[192.168.1.20/24 fe80::1/64]" {
		t.Errorf("wlan0 addresses: %v", addrs)
	}
	if !ifs[0].IsLoopback() || ifs[2].Flags&net.FlagUp != 0 || ifs[2].Flags&net.FlagPointToPoint == 0 {
		t.Errorf("flags: lo %v, rmnet0 %v", ifs[0].Flags, ifs[2].Flags)
	}
	platform = fakePlatform{json: "not json"}
	if _, err := interfaces(); err == nil {
		t.Error("bad JSON was accepted")
	}
}

func TestThePhonesNetworksFollowNetworkChanges(t *testing.T) {
	old := platform
	defer func() {
		platform = old
		NetworkChanged("", "", "")
	}()
	platform = fakePlatform{json: `[
		{"name":"lo","addrs":[{"ip":"127.0.0.1","prefixLen":8}]},
		{"name":"wlan0","addrs":[{"ip":"192.168.1.20","prefixLen":24},{"ip":"2a02:1810:4d00:1234::7","prefixLen":64},{"ip":"fe80::1%wlan0","prefixLen":64},{"ip":"::ffff:81.2.69.160","prefixLen":200}]}
	]`}
	NetworkChanged("wlan0", "192.168.1.1", "2001:67c:2b0:db32::/96")
	nets := phoneNetworks()
	if fmt.Sprint(nets.own) != "[127.0.0.0/8 192.168.1.0/24 2a02:1810:4d00:1234::/64 fe80::/64 81.2.69.160/32]" {
		t.Errorf("Wi-Fi: %v", nets.own)
	}
	if nets.nat64 != netip.MustParsePrefix("2001:67c:2b0:db32::/96") {
		t.Errorf("Wi-Fi's NAT64 prefix: %v", nets.nat64)
	}
	platform = fakePlatform{json: `[{"name":"rmnet_data0","addrs":[{"ip":"2607:fb90:1234:5678::2","prefixLen":64}]}]`}
	NetworkChanged("rmnet_data0", "", "")
	nets = phoneNetworks()
	if fmt.Sprint(nets.own) != "[2607:fb90:1234:5678::/64]" || nets.nat64.IsValid() {
		t.Errorf("cellular: %v %v", nets.own, nets.nat64)
	}
	for _, unreadable := range []string{"not json", "[]"} {
		platform = fakePlatform{json: unreadable}
		NetworkChanged("rmnet_data0", "", "")
		if nets = phoneNetworks(); fmt.Sprint(nets.own) != "[2607:fb90:1234:5678::/64]" {
			t.Errorf("%s replaced the last list: %v", unreadable, nets.own)
		}
	}
}
