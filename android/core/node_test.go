package core

import (
	"os"
	"path/filepath"
	"sync"
	"testing"

	"tailscale.com/logpolicy"
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
	if err := n.Start(); err != errNodeClosed {
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
