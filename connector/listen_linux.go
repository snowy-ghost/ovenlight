package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// listeners reads the kernel's socket tables, which list every user's sockets, and finds
// each socket's process among the open files in /proc. Another user's process can't be
// looked into, so its listener has pid 0.
func listeners(port int) ([]listener, error) {
	var socks []procListener
	for _, path := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		table, err := os.ReadFile(path)
		if os.IsNotExist(err) && path == "/proc/net/tcp6" {
			continue // IPv6 is off
		}
		if err != nil {
			return nil, err
		}
		socks = append(socks, parseProcNetTCP(string(table), port)...)
	}
	if len(socks) == 0 {
		return nil, nil
	}
	owner := socketOwners()
	var found []listener
	for _, s := range socks {
		l := listener{pid: owner[s.inode], addr: s.addr}
		if l.pid != 0 {
			comm, _ := os.ReadFile(filepath.Join("/proc", strconv.Itoa(l.pid), "comm"))
			l.name = strings.TrimSpace(string(comm))
		}
		found = append(found, l)
	}
	return found, nil
}

// socketOwners maps each socket inode to the process that has it open, for the
// processes this user can look into.
func socketOwners() map[string]int {
	owner := map[string]int{}
	fds, _ := filepath.Glob("/proc/[0-9]*/fd/*")
	for _, fd := range fds {
		target, err := os.Readlink(fd)
		inode, ok := strings.CutPrefix(target, "socket:[")
		if err != nil || !ok {
			continue
		}
		pid, _ := strconv.Atoi(strings.Split(fd, "/")[2])
		owner[strings.TrimSuffix(inode, "]")] = pid
	}
	return owner
}
