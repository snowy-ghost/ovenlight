package main

import (
	"os"
	"testing"
)

// TestMain lets the swap's tests run the test binary as a release. supervise_test.go has
// the Unix one.
func TestMain(m *testing.M) {
	runAsRelease()
	os.Exit(m.Run())
}
