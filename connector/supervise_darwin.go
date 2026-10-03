package main

import "golang.org/x/sys/unix"

// processStart is when the process started, in microseconds since 1970, which tells it
// apart from a later process given the same pid.
func processStart(pid int) (int64, error) {
	kp, err := unix.SysctlKinfoProc("kern.proc.pid", pid)
	if err != nil {
		return 0, err // EIO when there is no such process: the kernel returns no record
	}
	return kp.Proc.P_starttime.Sec*1e6 + int64(kp.Proc.P_starttime.Usec), nil
}

// parentPID is the process's parent, or -1 when there is no such process.
func parentPID(pid int) int {
	kp, err := unix.SysctlKinfoProc("kern.proc.pid", pid)
	if err != nil {
		return -1
	}
	return int(kp.Eproc.Ppid)
}
