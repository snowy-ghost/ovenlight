package core

import "syscall"

// silenceOutput points the process's stdout and stderr at /dev/null again. gomobile hands both
// to the system log (as GoLog), where a library writing to them directly (gVisor's warnings
// name tailnet addresses) would reach it in a release build.
func silenceOutput() {
	null, err := syscall.Open("/dev/null", syscall.O_WRONLY, 0)
	if err != nil {
		return
	}
	syscall.Dup3(null, 1, 0)
	syscall.Dup3(null, 2, 0)
	syscall.Close(null)
}
