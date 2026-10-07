package main

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// A command written for sh gets the app's port and host from cmd too.
func TestShellCommandVars(t *testing.T) {
	cmd := shellCommand("echo $PORT ${PORT} $HOST:${HOST} $PORTS %PORT%")
	cmd.Env = append(os.Environ(), "PORT=4317", "HOST=127.0.0.1")
	out, err := cmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	if got, want := strings.TrimSpace(string(out)), "4317 4317 127.0.0.1:127.0.0.1 $PORTS 4317"; got != want {
		t.Errorf("got %q, want %q", got, want)
	}
}

// The account's variables are read again over the connector's own, as they are now.
func TestLoginEnvRereadsTheAccount(t *testing.T) {
	env, err := loginEnv([]string{`PATH=C:\stale`, "TS_AUTHKEY=x", "ts_authkey=y", "OWN=1"})
	if err != nil {
		t.Fatal(err)
	}
	path := ""
	for _, v := range env {
		if name, value, _ := strings.Cut(v, "="); strings.EqualFold(name, "PATH") {
			path = value // the last of a name wins
		}
	}
	if path == "" || path == `C:\stale` || !slices.Contains(env, "OWN=1") || slices.ContainsFunc(env, func(v string) bool { return strings.HasPrefix(strings.ToUpper(v), "TS_AUTHKEY=") }) {
		t.Errorf("PATH %q in %q", path, env)
	}
}

// A group is the command's process and what it started, and ending it ends them all.
func TestProcessTree(t *testing.T) {
	cmd := shellCommand("ping -n 60 127.0.0.1 >NUL")
	newProcessGroup(cmd)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	pgid := cmd.Process.Pid
	holdGroup(pgid)
	defer signalGroup(pgid, true)
	var members []int
	for range 50 { // until cmd has started ping
		if members = groupMembers(pgid); len(members) >= 2 {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if len(members) < 2 || members[0] != pgid || parentPID(members[1]) != pgid {
		t.Fatalf("members %v", members)
	}
	if processGroup(pgid) != pgid || processGroup(-5) != -1 {
		t.Errorf("processGroup %d", processGroup(pgid))
	}
	if !slices.Contains(groupMembers(os.Getpid()), pgid) {
		t.Errorf("the test's own group should hold the command it started")
	}
	signalGroup(pgid, false)
	cmd.Wait()
	for range 50 {
		if !groupAlive(pgid) {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if groupAlive(pgid) {
		t.Errorf("still running: %v", groupMembers(pgid))
	}
	if _, err := processStart(members[1]); err == nil {
		t.Errorf("ping %d outlived its group", members[1])
	}
	if _, ok := held.Load(pgid); ok {
		t.Error("the group's first process is still held once the group is gone")
	}
}

// An elevated connector, as Task Scheduler runs it, starts its apps with a standard token.
func TestStandardToken(t *testing.T) {
	if os.Getenv("OVENLIGHT_TEST_TOKEN") == "1" {
		fmt.Println(describeToken(windows.GetCurrentProcessToken()))
		return
	}
	if !windows.GetCurrentProcessToken().IsElevated() {
		t.Skip("run the test elevated, as Task Scheduler runs the connector")
	}
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := shellCommand(`"` + exe + `" -test.run=TestStandardToken$`)
	cmd.Env = append(os.Environ(), "OVENLIGHT_TEST_TOKEN=1")
	out, err := cmd.CombinedOutput()
	if want := "elevated false, Administrators deny-only, integrity S-1-16-8192"; err != nil || !strings.Contains(string(out), want) {
		t.Errorf("the app's token: %s (%v), want %s", out, err, want)
	}
}

func describeToken(token windows.Token) string {
	admins := "absent"
	if groups, err := token.GetTokenGroups(); err == nil {
		for _, g := range groups.AllGroups() {
			if g.Sid.IsWellKnown(windows.WinBuiltinAdministratorsSid) {
				admins = "enabled"
				if g.Attributes&windows.SE_GROUP_USE_FOR_DENY_ONLY != 0 {
					admins = "deny-only"
				}
			}
		}
	}
	integrity := "unknown"
	buf := make([]byte, 64)
	var n uint32
	if windows.GetTokenInformation(token, windows.TokenIntegrityLevel, &buf[0], uint32(len(buf)), &n) == nil {
		integrity = (*windows.Tokenmandatorylabel)(unsafe.Pointer(&buf[0])).Label.Sid.String()
	}
	return fmt.Sprintf("elevated %v, Administrators %s, integrity %s", token.IsElevated(), admins, integrity)
}

// Under a console host, as a work, school or domain account's task starts it, the
// connector ends once the host does, as it does when Task Scheduler stops the task.
func TestStopWithConsoleHost(t *testing.T) {
	if dir := os.Getenv("OVENLIGHT_TEST_CONSOLE_HOST"); dir != "" {
		stopWithConsoleHost()
		os.WriteFile(filepath.Join(dir, "watching"), []byte(strconv.Itoa(os.Getpid())), 0o600)
		time.Sleep(time.Minute)
		return
	}
	stopWithConsoleHost() // the test's parent is no console host, so this goes on
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	t.Setenv("OVENLIGHT_TEST_CONSOLE_HOST", dir)
	// Started as Task Scheduler starts it, with no standard handles: a headless console
	// host reads its standard input as its terminal's, so with NUL, which exec.Command
	// would give it, it ends at once.
	line, err := windows.UTF16PtrFromString(fmt.Sprintf(`"%s" --headless "%s" -test.run=TestStopWithConsoleHost$`,
		filepath.Join(os.Getenv("SystemRoot"), "System32", "conhost.exe"), exe))
	if err != nil {
		t.Fatal(err)
	}
	si := windows.StartupInfo{Cb: uint32(unsafe.Sizeof(windows.StartupInfo{}))}
	var host windows.ProcessInformation
	if err := windows.CreateProcess(nil, line, nil, nil, false, 0, nil, nil, &si, &host); err != nil {
		t.Fatal(err)
	}
	defer windows.CloseHandle(host.Thread)
	defer windows.CloseHandle(host.Process)
	defer windows.TerminateProcess(host.Process, 1)
	var pid int
	for range 100 {
		// The file can be there before the pid is in it.
		if b, err := os.ReadFile(filepath.Join(dir, "watching")); err == nil {
			if pid, err = strconv.Atoi(string(b)); err == nil {
				break
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	connector, err := windows.OpenProcess(windows.SYNCHRONIZE|windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		t.Fatalf("the connector never started under the console host (pid %d): %v", pid, err)
	}
	defer windows.CloseHandle(connector)
	defer windows.TerminateProcess(connector, 1)
	windows.TerminateProcess(host.Process, 1)
	if ev, _ := windows.WaitForSingleObject(connector, 5000); ev != windows.WAIT_OBJECT_0 {
		t.Error("the connector kept running once its console host ended")
	}
}
