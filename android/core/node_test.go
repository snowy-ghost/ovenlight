package core

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logpolicy"
	"tailscale.com/types/key"
)

func TestNodesGiveLogpolicyADirectory(t *testing.T) {
	t.Setenv("TS_LOGS_DIR", "")
	os.Unsetenv("TS_LOGS_DIR")
	logsDirOnce = sync.Once{}
	parent := t.TempDir()
	n, err := NewNode("logs", filepath.Join(parent, "logs"), "test", "", "")
	if err != nil {
		t.Fatal(err)
	}
	defer n.Close()
	if got := logpolicy.LogsDir(t.Logf); got != parent {
		t.Errorf("LogsDir is %q, want the nodes' parent %q", got, parent)
	}
	if _, err := NewNode("logs", filepath.Join(parent, "logs"), "test", "", ""); err == nil {
		t.Error("a second open node for one membership was allowed")
	}
}

func TestAClosedNodeNeverStarts(t *testing.T) {
	n, err := NewNode("closed", filepath.Join(t.TempDir(), "closed"), "test", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if err := n.Close(); err != nil {
		t.Fatal(err)
	}
	if err := n.Start(); err == nil || err.Error() != "node closed" {
		t.Errorf("Start after Close: %v", err)
	}
	if err := n.WaitUp(100); err == nil || err.Error() != "node closed" {
		t.Errorf("WaitUp after Close: %v", err)
	}
	if openNode("closed") != nil {
		t.Error("a closed node is still open to the proxy")
	}
	again, err := NewNode("closed", filepath.Join(t.TempDir(), "closed"), "test", "", "")
	if err != nil {
		t.Fatalf("a new node for the same membership: %v", err)
	}
	again.Close()
}

// unhashable stands for tailscale.com/client/local's error for an answer other than 200.
type unhashable struct {
	error
	Header http.Header
}

func TestErrorsReachKotlinAsTheirText(t *testing.T) {
	err := error(unhashable{errors.New("500 Internal Server Error: authkey expired"), http.Header{}})
	bindable(&err)
	// gomobile keeps each value it hands over in a map, as here.
	refs := map[any]int{err: 1}
	if refs[err] != 1 || err.Error() != "500 Internal Server Error: authkey expired" {
		t.Errorf("bindable gave %v", err)
	}
	var none error
	if bindable(&none); none != nil {
		t.Errorf("no error became %v", none)
	}
}

type roundTrip func(*http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// A miss asks the node again only for an address a peer could have: a page's
// requests to the internet never wait on the LocalAPI.
func TestOnlyATailnetAddressAsksForAFreshStatus(t *testing.T) {
	peer := netip.MustParseAddr("100.101.102.103")
	asked := 0
	n := &Node{}
	n.lc.Store(&local.Client{OmitAuth: true, Transport: roundTrip(func(r *http.Request) (*http.Response, error) {
		asked++
		body, _ := json.Marshal(&ipnstate.Status{BackendState: "Running"})
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(bytes.NewReader(body)), Request: r}, nil
	})})
	// Older than a recheck may use, young enough for a first look.
	n.peers = &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{{}: {TailscaleIPs: []netip.Addr{peer}}}}
	n.peersAt = time.Now().Add(-time.Second)
	ctx := context.Background()
	if !n.isPeer(ctx, peer) || asked != 0 {
		t.Errorf("a peer in the snapshot: asked %d times", asked)
	}
	for _, s := range []string{"8.8.8.8", "2606:4700::1111", "100.128.0.1"} {
		if n.isPeer(ctx, netip.MustParseAddr(s)) || asked != 0 {
			t.Errorf("%s, outside Tailscale's ranges, asked for a fresh status (%d)", s, asked)
		}
	}
	if n.isPeer(ctx, netip.MustParseAddr("fd7a:115c:a1e0::9")) || asked != 1 {
		t.Errorf("a missing tailnet address asked %d times, want 1", asked)
	}
}
