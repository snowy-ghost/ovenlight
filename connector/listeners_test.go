package main

import (
	"encoding/binary"
	"net"
	"net/netip"
	"os"
	"reflect"
	"testing"
)

func TestProcNetTCPParsing(t *testing.T) {
	// As a little-endian kernel writes them: 127.0.0.1:4317 and 0.0.0.0:4317 listening,
	// another port listening, and a connection from port 4317 that isn't a listener.
	tcp := `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:10DD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 41 1 0 100 0 0 10 0
   1: 00000000:10DD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 42 1 0 100 0 0 10 0
   2: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 43 1 0 100 0 0 10 0
   3: 0100007F:10DD 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  1000        0 44 1 0 100 0 0 10 0
`
	tcp6 := `  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000001000000:10DD 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 51 1 0 100 0 0 10 0
   1: 0000000000000000FFFF00000100007F:10DD 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 52 1 0 100 0 0 10 0
   2: 00000000000000000000000000000000:10DD 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 53 1 0 100 0 0 10 0
`
	if binary.NativeEndian.Uint16([]byte{1, 0}) != 1 {
		t.Skip("the tables above are as a little-endian kernel writes them")
	}
	if got := parseProcNetTCP(tcp, 4317); !reflect.DeepEqual(got, []procListener{{"127.0.0.1:4317", "41"}, {"0.0.0.0:4317", "42"}}) {
		t.Errorf("tcp: %v", got)
	}
	if got := parseProcNetTCP(tcp6, 4317); !reflect.DeepEqual(got, []procListener{{"[::1]:4317", "51"}, {"127.0.0.1:4317", "52"}, {"[::]:4317", "53"}}) {
		t.Errorf("tcp6: %v", got)
	}
	if got := parseProcNetTCP(tcp, 9); got != nil {
		t.Errorf("no listener: %v", got)
	}
}

func TestTCPTableParsing(t *testing.T) {
	// table builds a GetExtendedTcpTable answer with one listener per address on port,
	// owned by pid 7 and up.
	table := func(ipv6 bool, port int, addrs ...string) []byte {
		rowSize, addrAt, portAt := 24, 4, 8
		if ipv6 {
			rowSize, addrAt, portAt = 56, 0, 20
		}
		b := make([]byte, 4+rowSize*len(addrs))
		binary.NativeEndian.PutUint32(b, uint32(len(addrs)))
		for i, a := range addrs {
			row := b[4+i*rowSize:]
			copy(row[addrAt:], netip.MustParseAddr(a).AsSlice())
			binary.BigEndian.PutUint16(row[portAt:], uint16(port))
			binary.NativeEndian.PutUint32(row[rowSize-4:], uint32(7+i))
		}
		return b
	}
	if got := parseTCPTable(table(false, 4317, "127.0.0.1", "0.0.0.0"), false, 4317); !reflect.DeepEqual(got, []listener{{pid: 7, addr: "127.0.0.1:4317"}, {pid: 8, addr: "0.0.0.0:4317"}}) {
		t.Errorf("ipv4: %v", got)
	}
	if got := parseTCPTable(table(true, 4317, "::1", "::"), true, 4317); !reflect.DeepEqual(got, []listener{{pid: 7, addr: "[::1]:4317"}, {pid: 8, addr: "[::]:4317"}}) {
		t.Errorf("ipv6: %v", got)
	}
	if got := parseTCPTable(table(false, 8080, "0.0.0.0"), false, 4317); got != nil {
		t.Errorf("another port: %v", got)
	}
	short := table(false, 4317, "127.0.0.1", "0.0.0.0")
	if got := parseTCPTable(short[:len(short)-1], false, 4317); !reflect.DeepEqual(got, []listener{{pid: 7, addr: "127.0.0.1:4317"}}) {
		t.Errorf("cut short: %v", got)
	}
}

// This OS's own listing finds this process listening, and tells loopback from every
// interface.
func TestListeners(t *testing.T) {
	listen := func(network, addr string) ([]listener, func()) {
		t.Helper()
		ln, err := net.Listen(network, addr)
		if err != nil {
			t.Fatal(err)
		}
		found, err := listeners(ln.Addr().(*net.TCPAddr).Port)
		if err != nil {
			t.Fatal(err)
		}
		return found, func() { ln.Close() }
	}
	found, done := listen("tcp4", "127.0.0.1:0")
	defer done()
	addrs, exposed := bindAddrs(found)
	if len(addrs) != 1 || exposed != nil || found[0].pid != os.Getpid() {
		t.Errorf("127.0.0.1: %+v", found)
	}
	if testing.Short() {
		return // a listener on every interface can bring up a firewall prompt
	}
	found, done = listen("tcp", ":0")
	defer done()
	if _, exposed := bindAddrs(found); len(exposed) == 0 {
		t.Errorf("every interface: %+v", found)
	}
}
