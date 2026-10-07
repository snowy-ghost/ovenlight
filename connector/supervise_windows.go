package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Windows has no process groups or login shell. An app's group is its process and every
// process it started, found from the process list by parent, and its environment is the
// connector's own, with what Windows keeps for the account read again (see loginEnv).

// shellCommand runs an app's command with cmd, which reads the line as typed, except that
// $PORT and $HOST become cmd's %PORT% and %HOST%, so a command written for sh, as in the
// guide, works here too.
func shellCommand(run string) *exec.Cmd {
	cmd := exec.Command(os.Getenv("ComSpec"))
	if cmd.Path == "" {
		cmd = exec.Command("cmd.exe")
	}
	run = shVars.ReplaceAllString(run, "%${1}${2}%")
	token, err := appToken()
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: `/d /s /c "` + run + `"`, Token: syscall.Token(token)}
	if err != nil {
		cmd.Err = fmt.Errorf("Windows wouldn't give it a token without administrator rights: %w", err)
	}
	return cmd
}

// shVars matches $PORT, ${PORT}, $HOST and ${HOST}, the variables the connector sets.
var shVars = regexp.MustCompile(`\$\{(PORT|HOST)\}|\$(PORT|HOST)\b`)

// appToken is what the apps run with: a standard token when the connector is elevated, as
// Task Scheduler runs it (see install.ps1), since an app has no use for administrator
// rights. Otherwise it's 0, the connector's own. Should Windows refuse to make one, an
// elevated connector starts no app at all.
var appToken = sync.OnceValues(standardToken)

var procCreateRestrictedToken = windows.NewLazySystemDLL("advapi32.dll").NewProc("CreateRestrictedToken")

// standardToken is a copy of the connector's token, when it's elevated, in which the
// administrator groups only deny, as UAC has them in a window not run as administrator.
// It has medium integrity and no privilege but SeChangeNotifyPrivilege. Its owner and
// default access list name the account instead of Administrators: without that, a
// process given the token can't open itself and fails to start (0xc0000142).
func standardToken() (windows.Token, error) {
	if !windows.GetCurrentProcessToken().IsElevated() {
		return 0, nil
	}
	// The copy's handle gets the same rights, which CreateProcessAsUser and setStandard need.
	var own windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_DUPLICATE|windows.TOKEN_QUERY|windows.TOKEN_ASSIGN_PRIMARY|windows.TOKEN_ADJUST_DEFAULT, &own); err != nil {
		return 0, err
	}
	defer own.Close()
	const disableMaxPrivilege, luaToken = 0x1, 0x4 // the LUA flag filters the groups as UAC does
	var token windows.Token
	if r, _, err := procCreateRestrictedToken.Call(uintptr(own), disableMaxPrivilege|luaToken, 0, 0, 0, 0, 0, 0, uintptr(unsafe.Pointer(&token))); r == 0 {
		return 0, err
	}
	if err := setStandard(token, own); err != nil {
		token.Close()
		return 0, err
	}
	return token, nil
}

// setStandard gives the token the account as its owner, a default access list for the
// account and SYSTEM, and medium integrity (the LUA flag leaves it at high).
func setStandard(token, own windows.Token) error {
	user, err := own.GetTokenUser()
	if err != nil {
		return err
	}
	owner := struct{ Owner *windows.SID }{user.User.Sid}
	if err := windows.SetTokenInformation(token, windows.TokenOwner, (*byte)(unsafe.Pointer(&owner)), uint32(unsafe.Sizeof(owner))); err != nil {
		return err
	}
	sd, err := windows.SecurityDescriptorFromString("D:(A;;GA;;;" + user.User.Sid.String() + ")(A;;GA;;;SY)")
	if err != nil {
		return err
	}
	acl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	dacl := struct{ DefaultDacl *windows.ACL }{acl}
	if err := windows.SetTokenInformation(token, windows.TokenDefaultDacl, (*byte)(unsafe.Pointer(&dacl)), uint32(unsafe.Sizeof(dacl))); err != nil {
		return err
	}
	medium, err := windows.CreateWellKnownSid(windows.WinMediumLabelSid)
	if err != nil {
		return err
	}
	label := windows.Tokenmandatorylabel{Label: windows.SIDAndAttributes{Sid: medium, Attributes: windows.SE_GROUP_INTEGRITY}}
	return windows.SetTokenInformation(token, windows.TokenIntegrityLevel, (*byte)(unsafe.Pointer(&label)), label.Size())
}

// loginEnv is env followed by the variables Windows keeps for the account, as they are
// now, which win over env's since the last of a name wins: a tool installed after the
// connector started is on PATH. It leaves out what the connector clears from its own, in
// any case. Should Windows fail to give the account's, env stays as it is.
func loginEnv(env []string) ([]string, error) {
	env = slices.Clone(env)
	var token windows.Token
	if windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_QUERY|windows.TOKEN_DUPLICATE, &token) == nil {
		if account, err := token.Environ(false); err == nil {
			env = append(env, account...)
		}
		token.Close()
	}
	return slices.DeleteFunc(env, func(v string) bool {
		name, _, _ := strings.Cut(v, "=")
		return slices.ContainsFunc(tsnetEnv, func(ts string) bool { return strings.EqualFold(ts, name) })
	}), nil
}

// endAppsWithConnector puts the connector in a job that ends every process in it once the
// connector's handle to it closes, as it does however the connector exits, even when Task
// Scheduler ends it without a word. The apps start in the job too, so they end with it.
func endAppsWithConnector() {
	job, err := windows.CreateJobObject(nil, nil)
	if err == nil {
		info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{
			BasicLimitInformation: windows.JOBOBJECT_BASIC_LIMIT_INFORMATION{LimitFlags: windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE},
		}
		_, err = windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info)))
		if err == nil {
			err = windows.AssignProcessToJobObject(job, windows.CurrentProcess())
		}
		if err != nil {
			windows.CloseHandle(job)
		}
	}
	if err != nil {
		log.Printf("apps may keep running after the connector stops: %v", err)
	}
}

// stopWithConsoleHost has the connector exit as soon as the console host that started it
// ends, as abruptly as Task Scheduler ends a connector it runs directly. A work, school or
// domain account's task runs it under a console host with no window (see install.ps1).
func stopWithConsoleHost() {
	host, err := windows.OpenProcess(windows.SYNCHRONIZE|windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(os.Getppid()))
	if err != nil {
		return
	}
	name := make([]uint16, windows.MAX_PATH)
	n := uint32(len(name))
	hostStart, _ := handleStart(host)
	ownStart, err := processStart(os.Getpid())
	// A parent that started after the connector is another process given the pid.
	if windows.QueryFullProcessImageName(host, 0, &name[0], &n) != nil || err != nil || hostStart > ownStart ||
		!strings.EqualFold(filepath.Base(windows.UTF16ToString(name[:n])), "conhost.exe") {
		windows.CloseHandle(host)
		return
	}
	go func() {
		windows.WaitForSingleObject(host, windows.INFINITE)
		log.Print("the console host ended: stopping")
		os.Exit(1)
	}()
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
