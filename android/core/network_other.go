//go:build !android

package core

// Only Android's netmon reads the default route from the app; host builds, for tests, skip it.
func updateDefaultRoute(string, string) {}
