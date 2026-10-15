//go:build !android

package core

// Only Android's netmon reads a gateway from the app; the host tests build this.
func updateDefaultGateway(string) {}
