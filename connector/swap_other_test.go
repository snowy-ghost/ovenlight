//go:build !windows

package main

import (
	"os"
	"path/filepath"
	"testing"
)

// macOS and Linux: the running binary is linked as .prev, never written into, and the
// release renamed over it.
func TestReplaceBinary(t *testing.T) {
	bin := t.TempDir()
	installed, prev, next := filepath.Join(bin, "ovenlight"), filepath.Join(bin, "ovenlight.prev"), filepath.Join(bin, "ovenlight.next")
	writeFile(t, installed, "running")
	writeFile(t, prev, "older")
	writeFile(t, next, "new")
	running, _ := statFile(installed)
	if err := replaceBinary(installed, next); err != nil {
		t.Fatal(err)
	}
	if info, err := statFile(prev); err != nil || !os.SameFile(info, running) {
		t.Error(".prev isn't the running binary")
	}
	if readFile(t, installed) != "new" || readFile(t, prev) != "running" || exists(next) {
		t.Error("not swapped")
	}
}
