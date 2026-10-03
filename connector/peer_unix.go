//go:build !windows

package main

import "net"

// checkPeer accepts every control connection: the socket's mode and its directory's
// already keep other users out.
func checkPeer(net.Conn) error { return nil }
