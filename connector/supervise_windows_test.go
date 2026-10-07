package main

import (
	"os"
	"slices"
	"strings"
	"testing"
	"time"
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
