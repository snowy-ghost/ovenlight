package main

import (
	"os"
	"slices"
	"testing"
	"time"
)

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
