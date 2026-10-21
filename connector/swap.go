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
	checked, err := checkNext(p.Next, p.Version)
	if err != nil {
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
	// What moves into place must be the file whose version was checked.
	if info, serr := os.Stat(p.Next); serr != nil || !sameContent(info, checked) {
		err = errors.New("the downloaded binary changed after its version check")
	} else {
		err = replace(installed, p.Next)
	}
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

// sameContent reports whether a and b are the same file, unchanged: a file replaced or
// written into in between differs.
func sameContent(a, b os.FileInfo) bool {
	return os.SameFile(a, b) && a.Size() == b.Size() && a.ModTime().Equal(b.ModTime())
}

// versionWait bounds the version check of a downloaded binary. macOS checks a binary's
// signature on its first run, which can take a while.
const versionWait = time.Minute

// nextVersion runs the binary at path with version and returns what it printed.
var nextVersion = func(path string) (string, error) {
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
// wrong architecture. It returns the file as checked.
func checkNext(path, want string) (os.FileInfo, error) {
	before, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	out, err := nextVersion(path)
	if err != nil {
		return nil, fmt.Errorf("the downloaded %s doesn't run here: %w", want, err)
	}
	if out != want+"\n" {
		return nil, fmt.Errorf("the downloaded %s says it's %q", want, clip(strings.TrimSpace(out), 40))
	}
	return before, nil
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
// install.ps1 removes them. An older .prev moves aside (an older ovenlight mcp may still
// run it), then the running binary becomes .prev, then next takes its place. Between
// those last two renames installed is missing, so they follow each other directly, and
// a last one that fails puts .prev back.
func renameAside(installed, next string) error {
	bin := filepath.Dir(installed)
	old, _ := filepath.Glob(filepath.Join(bin, "ovenlight.old*.exe"))
	for _, f := range old {
		os.Remove(f) // still running, or held: it goes next time
	}
	prev := prevBinary(installed)
	if _, err := os.Lstat(prev); err == nil {
		if err := renameRetry(prev, oldBinary(bin)); err != nil {
			return err
		}
	}
	if err := renameRetry(installed, prev); err != nil {
		return err
	}
	if err := renameRetry(next, installed); err != nil {
		if perr := renameRetry(prev, installed); perr != nil {
			log.Printf("updates: can't put %s back as %s: %v", prev, installed, perr)
		}
		return err
	}
	return nil
}

// restorePrev puts .prev back in place of installed, the running binary, for a
// rollback. On macOS and Linux one rename replaces it, so installed is never missing. On
// Windows the running binary moves aside first, and comes back if .prev can't move.
func restorePrev(installed string, windows bool) error {
	prev := prevBinary(installed)
	if !windows {
		return os.Rename(prev, installed)
	}
	aside := oldBinary(filepath.Dir(installed))
	if err := renameRetry(installed, aside); err != nil {
		return err
	}
	if err := renameRetry(prev, installed); err != nil {
		if aerr := renameRetry(aside, installed); aerr != nil {
			log.Printf("updates: can't put %s back as %s: %v", aside, installed, aerr)
		}
		return err
	}
	return nil
}

// oldBinary is a new name for a binary moved aside on Windows, as install.ps1 names them.
func oldBinary(bin string) string {
	return filepath.Join(bin, fmt.Sprintf("ovenlight.old-%d.exe", time.Now().UnixNano()))
}

// renameRetry renames from to to, trying again for a few seconds while something such as
// antivirus holds either.
func renameRetry(from, to string) error {
	var err error
	for range 50 {
		if err = os.Rename(from, to); err == nil || errors.Is(err, os.ErrNotExist) {
			return err
		}
		time.Sleep(100 * time.Millisecond)
	}
	return err
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
