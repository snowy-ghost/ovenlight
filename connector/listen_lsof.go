//go:build !linux && !windows

package main

import (
	"os/exec"
	"strconv"
)

// lsofListen is lsof's `-F pcn` output for the TCP listeners on the port.
func lsofListen(port int) (string, error) {
	lsof, err := exec.LookPath("lsof")
	if err != nil {
		lsof = "/usr/sbin/lsof" // macOS keeps it outside a minimal PATH
	}
	out, err := exec.Command(lsof, "-nP", "-iTCP:"+strconv.Itoa(port), "-sTCP:LISTEN", "-Fpcn").Output()
	if err != nil && len(out) == 0 {
		// lsof exits 1 when nothing matches; that is "no listener", not a failure.
		if exitErr, ok := err.(*exec.ExitError); ok && exitErr.ExitCode() == 1 {
			return "", nil
		}
		return "", err
	}
	return string(out), nil
}

// listeners are what listens on the port.
func listeners(port int) ([]listener, error) {
	out, err := lsofListen(port)
	return parseListeners(out), err
}
