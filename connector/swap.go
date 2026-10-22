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
	if err := replaceBinary(installedBinary(d.stateDir), p.Next); err != nil {
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
