//go:build !windows

package main

import (
	"errors"
	"os"

	"golang.org/x/sys/unix"
)

// lockFile takes an exclusive lock on f, held until f is closed or the process ends.
// With wait false it returns errLocked at once while another process holds it.
func lockFile(f *os.File, wait bool) error {
	how := unix.LOCK_EX
	if !wait {
		how |= unix.LOCK_NB
	}
	err := unix.Flock(int(f.Fd()), how)
	if errors.Is(err, unix.EWOULDBLOCK) {
		return errLocked
	}
	return err
}
