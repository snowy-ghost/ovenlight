package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/tsaddr"
	"tailscale.com/tsnet"
)

// Node is one membership's tsnet node: the owner's, or one per inviting owner.
type Node struct {
	id  string
	srv *tsnet.Server

	mu      sync.Mutex // serializes Start and Close: Close must never run during Start
	started bool

	lc        atomic.Pointer[local.Client] // set once Start succeeds
	closed    atomic.Bool
	closing   chan struct{} // closed by Close, which ends a WaitUp
	closeOnce sync.Once

	peersMu    sync.Mutex
	peers      *ipnstate.Status // a recent status with peers, for the proxy's lookups
	peersAt    time.Time
	lastSuffix atomic.Pointer[string] // the MagicDNS suffix last seen, while status can't be read
}

var registry = struct {
	sync.Mutex
	nodes map[string]*Node
}{nodes: map[string]*Node{}}

// openNode is the proxy's view of the registry.
func openNode(id string) upstream {
	registry.Lock()
	defer registry.Unlock()
	if n := registry.nodes[id]; n != nil {
		return n
	}
	return nil
}

func openNodes() []*Node {
	registry.Lock()
	defer registry.Unlock()
	var ns []*Node
	for _, n := range registry.nodes {
		ns = append(ns, n)
	}
	return ns
}

var errNodeClosed = errors.New("node closed")

// NewNode makes the node for a membership. id is the membership ID, which its
// node- route key carries, and dir holds its state and is unique to it. A
// membership has one open node at a time, and a node can't start again once
// closed, so restarting one means a new NewNode for the same directory.
func NewNode(id, dir, hostname, controlURL, authKey string) (*Node, error) {
	if id == "" || strings.Contains(id, ":") {
		return nil, fmt.Errorf("core: bad membership ID %q", id)
	}
	if dir == "" {
		return nil, errors.New("core: no state directory")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("core: state directory: %w", err)
	}
	// Each node's LocalBackend asks logpolicy for a logs directory, which panics on Android:
	// no HOME, no writable temp directory, and a working directory of /. Nothing is written
	// there (socket stats need Tailscale's own Go toolchain), so the nodes' shared parent
	// directory, which outlives any one membership, answers it.
	logsDirOnce.Do(func() {
		if os.Getenv("TS_LOGS_DIR") == "" {
			os.Setenv("TS_LOGS_DIR", filepath.Dir(dir))
		}
	})
	n := &Node{id: id, closing: make(chan struct{})}
	logf := func(format string, args ...any) { logLine(n.id + ": " + fmt.Sprintf(format, args...)) }
	n.srv = &tsnet.Server{
		Dir:        dir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Logf:       logf,
		UserLogf:   logf,
	}
	registry.Lock()
	if registry.nodes[id] != nil {
		registry.Unlock()
		return nil, fmt.Errorf("core: membership %s already has an open node", id)
	}
	registry.nodes[id] = n
	registry.Unlock()
	if theProxy != nil {
		theProxy.know(id)
	}
	return n, nil
}

var logsDirOnce sync.Once

// Start starts the node and returns once its backend runs. Signing in, or
// joining with the auth key, carries on after it returns.
func (n *Node) Start() (err error) {
	defer bindable(&err)
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed.Load() {
		return errNodeClosed
	}
	if n.started {
		return nil
	}
	if err := n.srv.Start(); err != nil {
		return err
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		return err
	}
	n.started = true
	n.lc.Store(lc)
	return nil
}

// WaitUp waits until the node is Running. It returns tsnet's error text as it
// is, "timed out waiting for the node" when the time runs out, and "node closed"
// when Close ends the wait.
func (n *Node) WaitUp(timeoutMillis int64) (err error) {
	defer bindable(&err)
	if err := n.Start(); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	go func() {
		select {
		case <-n.closing:
			cancel()
		case <-ctx.Done():
		}
	}()
	_, err = n.srv.Up(ctx)
	select {
	case <-n.closing:
		return errNodeClosed
	default:
	}
	switch {
	case err == nil:
		return nil
	case errors.Is(ctx.Err(), context.DeadlineExceeded):
		return errors.New("timed out waiting for the node")
	}
	return err
}

// bindable makes *err an error gomobile can hand to Kotlin, keeping its text. gomobile
// keeps each Go value it passes in a map, and an error that can't be a map key panics the
// process: tailscale.com/client/local's errors for an answer other than 200 carry the
// answer's headers (a logout refused by control, say).
func bindable(err *error) {
	if *err != nil {
		*err = errors.New((*err).Error())
	}
}

func (n *Node) client() (*local.Client, error) {
	if n.closed.Load() {
		return nil, errNodeClosed
	}
	lc := n.lc.Load()
	if lc == nil {
		return nil, errors.New("node not started")
	}
	return lc, nil
}

// StatusJSON is the node's ipnstate.Status, with peers, as JSON.
func (n *Node) StatusJSON() (_ string, err error) {
	defer bindable(&err)
	lc, err := n.client()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	n.rememberPeers(st)
	b, err := json.Marshal(st)
	return string(b), err
}

// LoginInteractive asks control for a sign-in link, which status then carries
// as AuthURL.
func (n *Node) LoginInteractive() (err error) {
	defer bindable(&err)
	lc, err := n.client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// Logout signs the node out of its network.
func (n *Node) Logout() (err error) {
	defer bindable(&err)
	lc, err := n.client()
	if err != nil {
		return err
	}
	// As long as iOS waits for its logout, so leaving while offline doesn't hang.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

// Close stops the node, keeping its state on disk. It waits for a Start in
// progress, and ends a WaitUp.
func (n *Node) Close() (err error) {
	defer bindable(&err)
	n.closeOnce.Do(func() { close(n.closing) })
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed.Swap(true) {
		return nil
	}
	registry.Lock()
	if registry.nodes[n.id] == n {
		delete(registry.nodes, n.id)
	}
	registry.Unlock()
	if !n.started {
		return nil
	}
	return n.srv.Close()
}

func (n *Node) injectLinkChange() {
	if n.lc.Load() == nil || n.closed.Load() {
		return
	}
	if nm := n.srv.Sys().NetMon.Get(); nm != nil {
		nm.InjectEvent()
	}
}

// The proxy's side of a node.

func (n *Node) running(ctx context.Context) bool {
	lc, err := n.client()
	if err != nil {
		return false
	}
	st, err := lc.StatusWithoutPeers(ctx)
	return err == nil && st.BackendState == "Running"
}

func (n *Node) rememberPeers(st *ipnstate.Status) {
	n.peersMu.Lock()
	defer n.peersMu.Unlock()
	n.peers, n.peersAt = st, time.Now()
}

// status returns a recent status with peers, asking again when it is older than
// maxAge.
func (n *Node) status(ctx context.Context, maxAge time.Duration) *ipnstate.Status {
	n.peersMu.Lock()
	st, at := n.peers, n.peersAt
	n.peersMu.Unlock()
	if st != nil && time.Since(at) < maxAge {
		return st
	}
	lc, err := n.client()
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

// A lookup that misses asks again if the snapshot is older than this, so a peer
// that just appeared is found without asking on every request.
const (
	peersFresh   = 2 * time.Second
	peersRecheck = 250 * time.Millisecond
)

// suffix is the node's MagicDNS suffix, or the last one seen when status can't be
// read, so a name on a network that isn't *.ts.net never goes to the system resolver
// for want of it.
func (n *Node) suffix(ctx context.Context) string {
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
// a miss with recheck, one at most peersRecheck old.
func (n *Node) peer(ctx context.Context, recheck bool, match func(*ipnstate.PeerStatus) bool) *ipnstate.PeerStatus {
	ages := []time.Duration{peersFresh, peersRecheck}
	if !recheck {
		ages = ages[:1]
	}
	for _, maxAge := range ages {
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

func (n *Node) peerAddr(ctx context.Context, host string) (netip.Addr, bool) {
	p := n.peer(ctx, true, func(p *ipnstate.PeerStatus) bool {
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
func (n *Node) endpoints() []netip.Prefix {
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

// isPeer asks for a fresh status on a miss only for an address in Tailscale's ranges,
// where a peer that just appeared would be: a public address costs no wait.
func (n *Node) isPeer(ctx context.Context, ip netip.Addr) bool {
	tailnet := tsaddr.CGNATRange().Contains(ip) || tsaddr.TailscaleULARange().Contains(ip)
	return n.peer(ctx, tailnet, func(p *ipnstate.PeerStatus) bool { return slices.Contains(p.TailscaleIPs, ip) }) != nil
}

func (n *Node) dial(ctx context.Context, addr netip.AddrPort) (net.Conn, error) {
	return n.srv.Dial(ctx, "tcp", addr.String())
}
