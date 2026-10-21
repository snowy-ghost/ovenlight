package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

// pendingSwap is an update whose moment has come: updates are on, the release is
// extracted, and either its quiet moment came and a fresh fetch of the published index
// still names it, or update now --index asked for it.
type pendingSwap struct {
	Version string // the release it installs, newer than the running one
	// Next is bin/ovenlight.next (ovenlight.next.exe on Windows), taken from the archive
	// the index lists. Nothing has run it yet.
	Next string
	// Index is the signed index that named the release, as fetched or as update now was
	// given it, and Sig its signature in Namespace: nsLatest, or nsCanary for update now
	// --index. The trial checks it with the new binary's own keys.
	Index, Sig []byte
	Namespace  string
}

// swapIn checks that Next runs and is p.Version, records the trial, puts Next in place
// of the running binary and has the connector exit through its normal shutdown, so the
// service starts the new one. It returns nil once the exit has begun, so a control
// request can still be answered. An error means nothing was swapped and the running
// binary is in place: the updater records it as the version's failure and tries it again
// the next night.
func (d *daemon) swapIn(p pendingSwap) error {
	if err := checkNext(p.Next, p.Version); err != nil {
		return err
	}
	now := time.Now().Round(0)
	trial := updateTrial{From: version(), To: p.Version, At: now, Apps: d.servingApps(now),
		Index: p.Index, Sig: p.Sig, Namespace: p.Namespace}
	// The trial is on disk before the new binary is in place, so the new binary's first
	// start finds it. A crash before the binary moves leaves a trial to a version that
	// isn't running, which the next start ends.
	path := filepath.Join(d.stateDir, updateTrialFile)
	if err := jsonfile.Save(path, &trial); err != nil {
		return err
	}
	if err := syncDir(d.stateDir); err != nil {
		jsonfile.Remove(path)
		return err
	}
	replace := linkAndRename
	if runtime.GOOS == "windows" {
		replace = renameAside
	}
	installed := installedBinary(d.stateDir)
	err := replace(installed, p.Next)
	if err == nil {
		_, err = os.Stat(installed) // the service's path, which the exit needs
	}
	if err != nil {
		jsonfile.Remove(path)
		return err
	}
	log.Printf("updates: %s is in place of %s", p.Version, trial.From)
	d.exit("updated to " + p.Version)
	return nil
}

// notRunnable is how a failure of a release that can't start here begins, which doctor
// recognises: installing it by hand fails the same way.
const notRunnable = "doesn't run here"

// versionWait bounds the version check of a downloaded binary. macOS checks a binary's
// signature on its first run, which can take a while.
const versionWait = time.Minute

// nextVersion runs the binary at path with version and returns what it printed.
func nextVersion(path string) (string, error) {
	for try := 0; ; try++ {
		ctx, cancel := context.WithTimeout(context.Background(), versionWait)
		out, err := exec.CommandContext(ctx, path, "version").Output()
		cancel()
		// Linux won't run a file that's open for writing, and a child forked while it was
		// written holds it open until that child runs its own program.
		if errors.Is(err, syscall.ETXTBSY) && try < 20 {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		var exit *exec.ExitError
		if errors.As(err, &exit) && len(exit.Stderr) > 0 {
			err = fmt.Errorf("%w: %s", err, clip(strings.TrimSpace(string(exit.Stderr)), 200))
		}
		return string(out), err
	}
}

// checkNext runs the downloaded binary's version and requires exactly want, which
// catches a binary that can't start here: a raised OS minimum, a broken signature, the
// wrong architecture.
func checkNext(path, want string) error {
	out, err := nextVersion(path)
	if err != nil {
		return fmt.Errorf("the downloaded %s %s: %w", want, notRunnable, err)
	}
	if out != want+"\n" {
		return fmt.Errorf("the downloaded %s says it's %q", want, clip(strings.TrimSpace(out), 40))
	}
	return nil
}

// prevBinary is the binary a rollback puts back: bin/ovenlight.prev, or
// ovenlight.prev.exe on Windows.
func prevBinary(installed string) string {
	if base, ok := strings.CutSuffix(installed, ".exe"); ok {
		return base + ".prev.exe"
	}
	return installed + ".prev"
}

// linkAndRename puts next in place of installed on macOS and Linux, keeping the running
// binary as .prev. Neither is ever written into: macOS kills a binary whose pages change
// after it ran. installed is never missing: a crash before the rename leaves the old
// binary in place, and after it the new one with .prev beside it.
func linkAndRename(installed, next string) error {
	prev := prevBinary(installed)
	// An ovenlight mcp still running an older .prev keeps its open copy.
	if err := os.Remove(prev); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.Link(installed, prev); err != nil {
		return err
	}
	// .prev is on disk before the new binary is, so a trial always has it.
	if err := syncDir(filepath.Dir(installed)); err != nil {
		return err
	}
	return os.Rename(next, installed)
}

// renameAside puts next in place of installed on Windows, which renames a running .exe
// but won't replace or delete one. The old binaries nothing runs go first, as
// install.ps1 removes them, and an older .prev moves aside (an older ovenlight mcp may
// still run it). Then moveIn makes the running binary .prev and puts next in its place.
func renameAside(installed, next string) error {
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
		if err := renameRetry(prev, oldBinary(bin), renameWait); err != nil {
			return err
		}
	}
	return moveIn(installed, next, prev)
}

// restorePrev puts .prev back in place of installed, the running binary, for a
// rollback. On macOS and Linux one rename replaces it, so installed is never missing. On
// Windows moveIn moves the running binary aside.
func restorePrev(installed string, windows bool) error {
	prev := prevBinary(installed)
	if !windows {
		return os.Rename(prev, installed)
	}
	return moveIn(installed, prev, oldBinary(filepath.Dir(installed)))
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

// renameWait is how long a rename on Windows is tried while something such as antivirus
// holds a file. Nothing has moved yet while it waits.
const renameWait = 30 * time.Second

// renameRetry renames from to to, trying again for up to wait.
func renameRetry(from, to string, wait time.Duration) error {
	for end := time.Now().Add(wait); ; time.Sleep(100 * time.Millisecond) {
		err := os.Rename(from, to)
		if err == nil || errors.Is(err, os.ErrNotExist) || time.Now().After(end) {
			return err
		}
	}
}

// syncDir flushes a folder's entries to disk, so a rename or a new file in it survives a
// power cut. Windows can't open a folder for this, and NTFS journals its entries in order.
func syncDir(dir string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	f, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}
