package main

import (
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// sinceBoot is how long ago Windows started, sleep included.
var sinceBoot = windows.DurationSinceBoot

// restartedSince reports whether Windows started since at, the release's last counted
// start. Windows ends the connector at a restart with no signal to mark a clean stop, so
// a boot after that start stands in for one.
func restartedSince(at, now time.Time) bool { return now.Add(-sinceBoot()).After(at) }

// replaceBinary puts next in place of installed, the running binary, which Windows renames
// but won't replace or delete. The old binaries nothing runs go first, as
// install.ps1 removes them, and an older .prev moves aside (an older ovenlight mcp may
// still run it). Then moveIn makes the running binary .prev and puts next in its place.
func replaceBinary(installed, next string) error {
	bin, prev := filepath.Dir(installed), prevBinary(installed)
	// A put-back still trying in the background has the running binary at .prev, which
	// mustn't move aside.
	if !exists(installed) {
		return fmt.Errorf("%s isn't back in place yet", installed)
	}
	old, _ := filepath.Glob(filepath.Join(bin, "ovenlight.old*.exe"))
	for _, f := range old {
		os.Remove(f) // still running, or held: it goes next time
	}
	if exists(prev) {
		r, err := holdForRename(prev, renameWait)
		if err != nil {
			return err
		}
		defer r.Close()
		if err := r.rename(oldBinary(bin)); err != nil {
			return err
		}
	}
	return moveIn(installed, next, prev)
}

// restorePrev puts .prev back in place of installed, the running binary, for a rollback,
// moving the running binary aside.
func restorePrev(installed string) error {
	return moveIn(installed, prevBinary(installed), oldBinary(filepath.Dir(installed)))
}

// moveIn puts from in place of installed, the running binary, which moves to aside, all
// in one folder: Windows renames a running .exe but won't replace one. installed is
// missing between those two renames, so whatever waits comes before them: both files are
// opened for renaming, each tried again while something such as antivirus holds it, and
// held, so the renames follow each other directly. When the second fails, the running
// binary goes back, and a put-back that fails too keeps trying in the background for as
// long as the process runs: until it's back, nothing is at the service's path to start.
func moveIn(installed, from, aside string) error {
	src, err := holdForRename(from, renameWait)
	if err != nil {
		return err
	}
	defer src.Close()
	cur, err := holdForRename(installed, renameWait)
	if err != nil {
		return err
	}
	defer cur.Close()
	if err := cur.rename(aside); err != nil {
		return err
	}
	if err := src.rename(installed); err != nil {
		if perr := cur.rename(installed); perr != nil {
			log.Printf("updates: can't put %s back as %s yet: %v", aside, installed, perr)
			go putBack(aside, installed)
		}
		return err
	}
	return nil
}

// putBack renames from to to, trying every second until something is at to.
func putBack(from, to string) {
	for !exists(to) {
		if os.Rename(from, to) != nil {
			time.Sleep(time.Second)
		}
	}
}

// oldBinary is a new name for a binary moved aside on Windows, as install.ps1 names them.
func oldBinary(bin string) string {
	return filepath.Join(bin, fmt.Sprintf("ovenlight.old-%d.exe", time.Now().UnixNano()))
}

// renameWait is how long a rename is tried while something such as antivirus
// holds a file. Nothing has moved yet while it waits.
const renameWait = 30 * time.Second

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
