//go:build !windows

package main

import (
	"errors"
	"os"
	"path/filepath"
	"time"
)

// replaceBinary puts next in place of installed, the running binary, keeping it as .prev.
// Neither is ever written into: macOS kills a binary whose pages change after it ran.
// installed is never missing: a crash before the rename leaves the old binary in place,
// and after it the new one with .prev beside it.
func replaceBinary(installed, next string) error {
	prev := prevBinary(installed)
	// An ovenlight mcp still running an older .prev keeps its open copy.
	if err := os.Remove(prev); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.Link(installed, prev); err != nil {
		return err
	}
	// .prev is on disk before the new binary is, so a trial always has it.
	if err := syncDir(filepath.Dir(installed)); err != nil {
		return err
	}
	return os.Rename(next, installed)
}

// restorePrev puts .prev back in place of installed, the running binary, for a rollback.
// One rename replaces it, so installed is never missing.
func restorePrev(installed string) error { return os.Rename(prevBinary(installed), installed) }

// restartedSince is false: a restart on macOS and Linux stops the connector cleanly first.
func restartedSince(_, _ time.Time) bool { return false }
