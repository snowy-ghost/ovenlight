package main

import (
	"os"
	"os/exec"
	"slices"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Windows has no process groups or login shell. An app's group is its process and every
// process it started, found from the process list by parent, and its environment is the
// connector's own, which Windows already loads from the user's profile for the task.

// shellCommand runs an app's command with cmd, which reads the line as typed.
func shellCommand(run string) *exec.Cmd {
	cmd := exec.Command(os.Getenv("ComSpec"))
	if cmd.Path == "" {
		cmd = exec.Command("cmd.exe")
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: `/d /s /c "` + run + `"`}
	return cmd
}

// loginEnv is env without what the connector clears from its own.
func loginEnv(env []string) ([]string, error) {
	return slices.DeleteFunc(slices.Clone(env), func(v string) bool {
		name, _, _ := strings.Cut(v, "=")
		return slices.Contains(tsnetEnv, name)
	}), nil
}

// loginShell is cmd, which runs the apps' commands.
func loginShell() string { return "cmd.exe" }

// newProcessGroup lets the command's processes be told apart from the connector's.
func newProcessGroup(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= windows.CREATE_NEW_PROCESS_GROUP
}

// held keeps a handle to each running app's first process, from holdGroup until its
// group is gone: Windows reuses a pid only once no handle to the process is open, and
// the handle gives the process's start time even after it exits.
var held sync.Map // pid -> windows.Handle

// holdGroup holds the app's first process, just started, as above.
func holdGroup(pid int) {
	if h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION|windows.SYNCHRONIZE, false, uint32(pid)); err == nil {
		held.Store(pid, h)
	}
}

// signalGroup ends the process and everything it started. Windows can't ask a console
// program to stop, so kill or not, it terminates them.
func signalGroup(pgid int, kill bool) {
	for _, pid := range groupMembers(pgid) {
		if p, err := windows.OpenProcess(windows.PROCESS_TERMINATE, false, uint32(pid)); err == nil {
			windows.TerminateProcess(p, 1)
			windows.CloseHandle(p)
		}
	}
}

// groupAlive reports whether the process or anything it started still runs; once
// nothing does, the group's first process is let go.
func groupAlive(pgid int) bool {
	if len(groupMembers(pgid)) > 0 {
		return true
	}
	releaseGroup(pgid)
	return false
}

// releaseGroup lets the group's first process go, once nothing is left to end.
func releaseGroup(pgid int) {
	if h, ok := held.LoadAndDelete(pgid); ok {
		windows.CloseHandle(h.(windows.Handle))
	}
}

// processGroup is the process itself, or -1 when there is no such process: a group is
// named by the process that started it.
func processGroup(pid int) int {
	if _, err := processStart(pid); err != nil {
		return -1
	}
	return pid
}

// processStart is when the process started, in 100 ns since 1601, which tells it apart
// from a later process given the same pid.
func processStart(pid int) (int64, error) {
	p, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(pid))
	if err != nil {
		return 0, err
	}
	defer windows.CloseHandle(p)
	var created, exited, kernel, user windows.Filetime
	if err := windows.GetProcessTimes(p, &created, &exited, &kernel, &user); err != nil {
		return 0, err
	}
	var code uint32
	if windows.GetExitCodeProcess(p, &code) == nil && code != 259 { // STILL_ACTIVE
		return 0, windows.ERROR_INVALID_PARAMETER // exited, though a handle keeps it listed
	}
	return int64(created.HighDateTime)<<32 | int64(created.LowDateTime), nil
}

// parentPID is the process's parent, or -1 when there is no such process.
func parentPID(pid int) int {
	for _, p := range processList() {
		if int(p.ProcessID) == pid {
			return int(p.ParentProcessID)
		}
	}
	return -1
}

// groupMembers are the running processes of the group pgid names: it and every process
// started after it whose parent is one of them. Without a held handle, the group is known
// only while its first process runs, so a process that took a reused pid, and what it
// started, are never taken for the group.
func groupMembers(pgid int) []int {
	var rootStart int64
	alive := false
	if h, ok := held.Load(pgid); ok {
		rootStart, alive = handleStart(h.(windows.Handle))
	} else if start, err := processStart(pgid); err == nil {
		rootStart, alive = start, true
	} else {
		return nil
	}
	var members []int
	if alive {
		members = append(members, pgid)
	}
	return append(members, descendantsOf(pgid, rootStart, processList())...)
}

// handleStart is the start time of the process the handle holds, and whether it still
// runs.
func handleStart(h windows.Handle) (int64, bool) {
	var created, exited, kernel, user windows.Filetime
	if windows.GetProcessTimes(h, &created, &exited, &kernel, &user) != nil {
		return 0, false
	}
	var code uint32
	running := windows.GetExitCodeProcess(h, &code) == nil && code == 259 // STILL_ACTIVE
	return int64(created.HighDateTime)<<32 | int64(created.LowDateTime), running
}

func descendantsOf(pid int, start int64, all []windows.ProcessEntry32) []int {
	var found []int
	for _, p := range all {
		if int(p.ParentProcessID) != pid || int(p.ProcessID) == pid {
			continue
		}
		childStart, err := processStart(int(p.ProcessID))
		if err != nil || childStart < start {
			continue // gone, or older than its parent: a reused pid, not a child
		}
		found = append(found, int(p.ProcessID))
		found = append(found, descendantsOf(int(p.ProcessID), childStart, all)...)
	}
	return found
}

// processList is a snapshot of the running processes.
func processList() []windows.ProcessEntry32 {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil
	}
	defer windows.CloseHandle(snap)
	var all []windows.ProcessEntry32
	e := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	for err := windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		all = append(all, e)
	}
	return all
}
