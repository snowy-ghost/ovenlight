package main

import (
	"net"
	"path/filepath"
	"testing"
)

// A client running as the daemon's own user passes the peer check.
func TestCheckPeerAcceptsSameUser(t *testing.T) {
	ln, err := listenControl(filepath.Join(t.TempDir(), "t.sock"))
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	client, err := net.Dial("unix", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	conn, err := ln.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err := checkPeer(conn); err != nil {
		t.Errorf("same user refused: %v", err)
	}
}
