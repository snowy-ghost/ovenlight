package main

import (
	"errors"
	"net"
	"unsafe"

	"golang.org/x/sys/windows"
)

// sioAFUnixGetPeerPID is SIO_AF_UNIX_GETPEERPID: the process at the other end of an
// AF_UNIX connection.
const sioAFUnixGetPeerPID = 0x58000100

// checkPeer refuses a control connection from another user. Windows doesn't check the
// socket file's access list when a client connects, so the daemon asks who it is.
func checkPeer(conn net.Conn) error {
	uc, ok := conn.(*net.UnixConn)
	if !ok {
		return errors.New("not a unix socket")
	}
	raw, err := uc.SyscallConn()
	if err != nil {
		return err
	}
	var pid, n uint32
	var ioErr error
	if err := raw.Control(func(fd uintptr) {
		ioErr = windows.WSAIoctl(windows.Handle(fd), sioAFUnixGetPeerPID, nil, 0, (*byte)(unsafe.Pointer(&pid)), 4, &n, nil, 0)
	}); err != nil {
		return err
	}
	if ioErr != nil {
		return ioErr
	}
	peer, err := processUser(pid)
	if err != nil {
		return err
	}
	me, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return err
	}
	if !peer.Equals(me.User.Sid) {
		return errors.New("the client runs as another user")
	}
	return nil
}

func processUser(pid uint32) (*windows.SID, error) {
	p, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return nil, err
	}
	defer windows.CloseHandle(p)
	var token windows.Token
	if err := windows.OpenProcessToken(p, windows.TOKEN_QUERY, &token); err != nil {
		return nil, err
	}
	defer token.Close()
	u, err := token.GetTokenUser()
	if err != nil {
		return nil, err
	}
	return u.User.Sid.Copy()
}
