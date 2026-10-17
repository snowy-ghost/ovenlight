//go:build !android

package core

// Only on Android does gomobile hand stderr to the system log; host builds, for tests, keep it.
func silenceOutput() {}
