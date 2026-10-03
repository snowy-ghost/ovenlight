package main

import (
	"path/filepath"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var procGetExtendedTcpTable = windows.NewLazySystemDLL("iphlpapi.dll").NewProc("GetExtendedTcpTable")

// tcpTableOwnerPIDListener is TCP_TABLE_OWNER_PID_LISTENER: listening sockets only.
const tcpTableOwnerPIDListener = 3

// listeners asks Windows for its listening TCP sockets, which include every user's, each
// with its process.
func listeners(port int) ([]listener, error) {
	var found []listener
	for _, family := range []uint32{windows.AF_INET, windows.AF_INET6} {
		table, err := tcpTable(family)
		if err != nil {
			return nil, err
		}
		found = append(found, parseTCPTable(table, family == windows.AF_INET6, port)...)
	}
	for i := range found {
		found[i].name = processName(found[i].pid)
	}
	return found, nil
}

// processName is the file name of the process's program, "" when it can't be read.
func processName(pid int) string {
	p, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(pid))
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(p)
	buf := make([]uint16, windows.MAX_PATH)
	n := uint32(len(buf))
	if windows.QueryFullProcessImageName(p, 0, &buf[0], &n) != nil {
		return ""
	}
	return filepath.Base(windows.UTF16ToString(buf[:n]))
}

func tcpTable(family uint32) ([]byte, error) {
	size := uint32(4096)
	for {
		buf := make([]byte, size)
		r, _, _ := procGetExtendedTcpTable.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)),
			0, uintptr(family), tcpTableOwnerPIDListener, 0)
		switch syscall.Errno(r) {
		case 0:
			return buf, nil // the row count says how much of it is the table
		case windows.ERROR_INSUFFICIENT_BUFFER:
			continue // size is now what it needs; a table that grew meanwhile asks again
		default:
			return nil, syscall.Errno(r)
		}
	}
}
