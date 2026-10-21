//go:build !windows

package main

import (
	"os"
	"time"
)

// replaceBinary puts next in place of installed, the running binary.
var replaceBinary = linkAndRename

// restorePrev puts .prev back in place of installed, the running binary, for a rollback.
// One rename replaces it, so installed is never missing.
func restorePrev(installed string) error { return os.Rename(prevBinary(installed), installed) }

// restartedSince is false: a restart on macOS and Linux stops the connector cleanly first.
func restartedSince(_, _ time.Time) bool { return false }
