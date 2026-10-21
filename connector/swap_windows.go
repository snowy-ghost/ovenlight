package main

import (
	"errors"
	"os"
	"path/filepath"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// sinceBoot is how long ago Windows started, sleep included.
var sinceBoot = windows.DurationSinceBoot

// renamer is a file held open for renaming, with DELETE access. It shares every access,
// so the running binary's own handles keep working, and anything that opens the file
// later without sharing a rename, such as antivirus, is kept out. A rename through it
// needs no new open, so nothing can make it wait.
type renamer struct{ h windows.Handle }

// holdForRename opens path for renaming, trying again for up to wait while something
// holds it.
func holdForRename(path string, wait time.Duration) (*renamer, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	for end := time.Now().Add(wait); ; time.Sleep(100 * time.Millisecond) {
		h, err := windows.CreateFile(p, windows.DELETE, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
			nil, windows.OPEN_EXISTING, 0, 0)
		if err == nil {
			return &renamer{h}, nil
		}
		if errors.Is(err, os.ErrNotExist) || time.Now().After(end) {
			return nil, &os.PathError{Op: "open", Path: path, Err: err}
		}
	}
}

// rename moves the file to to. A name without its folder would be read against the
// current directory, so it's made absolute.
func (r *renamer) rename(to string) error {
	abs, err := filepath.Abs(to)
	if err != nil {
		return err
	}
	name, err := windows.UTF16FromString(abs)
	if err != nil {
		return err
	}
	// FILE_RENAME_INFO, its name ending in a NUL that FileNameLength leaves out.
	type renameInfo struct {
		Flags          uint32
		RootDirectory  windows.Handle
		FileNameLength uint32
		FileName       [1]uint16
	}
	var info renameInfo
	buf := make([]byte, unsafe.Offsetof(info.FileName)+uintptr(len(name))*2)
	in := (*renameInfo)(unsafe.Pointer(&buf[0]))
	in.FileNameLength = uint32(len(name)-1) * 2
	copy(unsafe.Slice(&in.FileName[0], len(name)), name)
	if err := windows.SetFileInformationByHandle(r.h, windows.FileRenameInfo, &buf[0], uint32(len(buf))); err != nil {
		return &os.LinkError{Op: "rename", New: to, Err: err}
	}
	return nil
}

func (r *renamer) Close() error { return windows.CloseHandle(r.h) }
