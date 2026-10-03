//go:build unix && !darwin

package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// processStart is when the process started, in clock ticks since boot, from /proc on
// Linux. Elsewhere it fails, so the connector records no process groups and ends none
// that a crash left running.
func processStart(pid int) (int64, error) {
	fields, err := statFields(pid)
	if err != nil {
		return 0, err
	}
	return strconv.ParseInt(fields[19], 10, 64)
}

// parentPID is the process's parent, or -1 when there is no such process or no /proc.
func parentPID(pid int) int {
	fields, err := statFields(pid)
	if err != nil {
		return -1
	}
	ppid, err := strconv.Atoi(fields[1])
	if err != nil {
		return -1
	}
	return ppid
}

// statFields are the fields of /proc/<pid>/stat after the command name, starting with
// the state, so the parent is the 2nd of them and starttime the 20th.
func statFields(pid int) ([]string, error) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return nil, err
	}
	// The command name, in parentheses, may hold spaces and parentheses.
	i := strings.LastIndexByte(string(data), ')')
	fields := strings.Fields(string(data)[i+1:])
	if i < 0 || len(fields) < 20 {
		return nil, fmt.Errorf("can't read /proc/%d/stat", pid)
	}
	return fields, nil
}
