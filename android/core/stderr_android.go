package core

import "syscall"

// silenceStderr points the process's stderr at /dev/null again. gomobile hands it to the system
// log (as GoLog), where a library writing to it directly (gVisor's warnings name tailnet
// addresses) would reach it in a release build.
func silenceStderr() {
	null, err := syscall.Open("/dev/null", syscall.O_WRONLY, 0)
	if err != nil {
		return
	}
	syscall.Dup3(null, 2, 0)
	syscall.Close(null)
}
