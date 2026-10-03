package main

import (
	"encoding/binary"
	"encoding/hex"
	"net/netip"
	"strconv"
	"strings"
)

// Doctor's bind-address check lists what listens on an app's port. Each OS lists it its
// own way (listeners, in listen_*.go); these read what Linux and Windows return, and
// build on every OS, so their tests run everywhere. macOS's lsof output is read by
// parseListeners, in doctor.go.

// procListener is a listening socket from Linux's /proc/net/tcp or tcp6: its address
// and the inode that names the socket among a process's open files.
type procListener struct {
	addr  string
	inode string
}

// parseProcNetTCP reads /proc/net/tcp or /proc/net/tcp6 and returns the sockets
// listening on port. Each address is hex, in 32-bit words of the kernel's byte order;
// the port is hex too.
func parseProcNetTCP(table string, port int) []procListener {
	var found []procListener
	for _, line := range strings.Split(table, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 10 || fields[3] != "0A" { // 0A is TCP_LISTEN
			continue
		}
		hexIP, hexPort, ok := strings.Cut(fields[1], ":")
		p, err := strconv.ParseUint(hexPort, 16, 16)
		raw, herr := hex.DecodeString(hexIP)
		if !ok || err != nil || herr != nil || int(p) != port || (len(raw) != 4 && len(raw) != 16) {
			continue
		}
		ip := make([]byte, len(raw))
		for i := 0; i < len(raw); i += 4 {
			binary.NativeEndian.PutUint32(ip[i:], binary.BigEndian.Uint32(raw[i:]))
		}
		addr, _ := netip.AddrFromSlice(ip)
		found = append(found, procListener{netip.AddrPortFrom(addr.Unmap(), uint16(p)).String(), fields[9]})
	}
	return found
}

// parseTCPTable reads a table from Windows' GetExtendedTcpTable with
// TCP_TABLE_OWNER_PID_LISTENER: a row count, then MIB_TCPROW_OWNER_PID rows (24 bytes)
// for IPv4 or MIB_TCP6ROW_OWNER_PID rows (56 bytes) for IPv6. Addresses and ports are in
// network byte order, a port in the first two bytes of its 32-bit field; the owning
// process ID ends each row.
func parseTCPTable(table []byte, ipv6 bool, port int) []listener {
	rowSize, addrAt, addrLen, portAt := 24, 4, 4, 8
	if ipv6 {
		rowSize, addrAt, addrLen, portAt = 56, 0, 16, 20
	}
	if len(table) < 4 {
		return nil
	}
	rows := int(binary.NativeEndian.Uint32(table))
	var found []listener
	for i := range rows {
		at := 4 + i*rowSize
		if at+rowSize > len(table) {
			break
		}
		row := table[at : at+rowSize]
		if int(binary.BigEndian.Uint16(row[portAt:])) != port {
			continue
		}
		addr, _ := netip.AddrFromSlice(row[addrAt : addrAt+addrLen])
		pid := int(binary.NativeEndian.Uint32(row[rowSize-4:]))
		found = append(found, listener{pid: pid, addr: netip.AddrPortFrom(addr.Unmap(), uint16(port)).String()})
	}
	return found
}
