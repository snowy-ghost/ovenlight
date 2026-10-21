package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// TestMain lets the swap's tests run the test binary as a release. supervise_test.go has
// the Unix one.
func TestMain(m *testing.M) {
	runAsRelease()
	os.Exit(m.Run())
}

// The tests' computer booted long before their swaps, unless a test says otherwise.
func init() { sinceBoot = func() time.Duration { return 100000 * time.Hour } }

// oldBinaries are the binaries moved aside in bin.
func oldBinaries(bin string) []string {
	old, _ := filepath.Glob(filepath.Join(bin, "ovenlight.old*.exe"))
	return old
}

// Windows' renames: the old binaries go, an older .prev moves aside, the
// running binary becomes .prev and the release takes its place. With no release, nothing
// moves.
func TestRenameAside(t *testing.T) {
	bin := t.TempDir()
	installed, prev, next := filepath.Join(bin, "ovenlight.exe"), filepath.Join(bin, "ovenlight.prev.exe"), filepath.Join(bin, "ovenlight.next.exe")
	writeFile(t, installed, "running")
	writeFile(t, prev, "older")
	writeFile(t, next, "new")
	writeFile(t, filepath.Join(bin, "ovenlight.old-1.exe"), "stale")
	if err := replaceBinary(installed, next); err != nil {
		t.Fatal(err)
	}
	if readFile(t, installed) != "new" || readFile(t, prev) != "running" || exists(next) {
		t.Error("not swapped")
	}
	if old := oldBinaries(bin); len(old) != 1 || readFile(t, old[0]) != "older" {
		t.Errorf("old binaries: %v", old)
	}

	bin = t.TempDir()
	installed, prev = filepath.Join(bin, "ovenlight.exe"), filepath.Join(bin, "ovenlight.prev.exe")
	writeFile(t, installed, "running")
	if err := replaceBinary(installed, filepath.Join(bin, "ovenlight.next.exe")); err == nil {
		t.Error("no release, and no error")
	}
	if readFile(t, installed) != "running" || exists(prev) {
		t.Error("something moved")
	}
}

// A rollback puts .prev back, with the running binary moved aside first, and back when
// .prev can't move.
func TestRestorePrev(t *testing.T) {
	bin := t.TempDir()
	installed := filepath.Join(bin, "ovenlight.exe")
	prev := prevBinary(installed)
	writeFile(t, installed, "new")
	writeFile(t, prev, "old")
	if err := restorePrev(installed); err != nil {
		t.Fatal(err)
	}
	if readFile(t, installed) != "old" || exists(prev) || len(oldBinaries(bin)) != 1 {
		t.Errorf("not put back: %v", oldBinaries(bin))
	}
	bin = t.TempDir()
	installed = filepath.Join(bin, "ovenlight.exe")
	writeFile(t, installed, "new")
	if err := restorePrev(installed); err == nil || readFile(t, installed) != "new" || len(oldBinaries(bin)) != 0 {
		t.Errorf("no .prev: %v", err)
	}
}

// hold runs cmd until the test ends, as a binary in use. The channel closes if it stops
// sooner.
func hold(t *testing.T, cmd *exec.Cmd) <-chan struct{} {
	t.Helper()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		cmd.Wait()
		close(done)
	}()
	t.Cleanup(func() {
		cmd.Process.Kill()
		<-done
	})
	return done
}

// Windows' renames and rollback with every binary in use, as an older ovenlight mcp keeps
// .prev and an old binary running: they move, and only what nothing runs is deleted.
func TestRenameAsideRunning(t *testing.T) {
	bin := t.TempDir()
	installed, prev, next := filepath.Join(bin, "ovenlight.exe"), filepath.Join(bin, "ovenlight.prev.exe"), filepath.Join(bin, "ovenlight.next.exe")
	stale, running := filepath.Join(bin, "ovenlight.old-1.exe"), filepath.Join(bin, "ovenlight.old-2.exe")
	var held []<-chan struct{}
	for _, path := range []string{installed, running} {
		copyTestBinary(t, path)
		held = append(held, hold(t, exec.Command(path, holdCommand)))
	}
	// .prev runs as an older ovenlight mcp may: with OVENLIGHT_TEST_CONNECTOR naming a
	// connector build, as a dev daemon of it in folders of its own, which no node can log
	// in from.
	if connector := os.Getenv("OVENLIGHT_TEST_CONNECTOR"); connector != "" {
		b, err := os.ReadFile(connector)
		if err != nil {
			t.Fatal(err)
		}
		writeFile(t, prev, string(b))
		dir := t.TempDir()
		cmd := exec.Command(prev, "run", "--config", filepath.Join(dir, "config.json"), "--state", filepath.Join(dir, "s"), "--control-url", "http://127.0.0.1:9")
		cmd.Env = append(os.Environ(), "OVENLIGHT_DEV=1", "TS_NO_LOGS_NO_SUPPORT=true")
		held = append(held, hold(t, cmd))
	} else {
		copyTestBinary(t, prev)
		held = append(held, hold(t, exec.Command(prev, holdCommand)))
	}
	copyTestBinary(t, stale)
	copyTestBinary(t, next)
	if err := os.Rename(next, installed); err == nil {
		t.Fatal("Windows replaced a running binary")
	}
	was, _ := statFile(installed)
	release, _ := statFile(next)
	if err := replaceBinary(installed, next); err != nil {
		t.Fatal(err)
	}
	now, _ := statFile(installed)
	nowPrev, _ := statFile(prev)
	if !os.SameFile(now, release) || !os.SameFile(nowPrev, was) || exists(stale) || !exists(running) || len(oldBinaries(bin)) != 2 {
		t.Errorf("after the swap: %v, the stale one there: %v, the running one there: %v", oldBinaries(bin), exists(stale), exists(running))
	}

	held = append(held, hold(t, exec.Command(installed, holdCommand))) // the release on trial, rolled back
	if err := restorePrev(installed); err != nil {
		t.Fatal(err)
	}
	if now, _ := statFile(installed); !os.SameFile(now, was) || exists(prev) || len(oldBinaries(bin)) != 3 {
		t.Errorf("after the rollback: %v", oldBinaries(bin))
	}
	for i, done := range held {
		select {
		case <-done:
			t.Errorf("binary %d stopped running during the test", i)
		default:
		}
	}
}

// Windows ends the connector at a restart with no clean stop, so a start after the
// computer booted since the last counted start begins the trial afresh. With no boot
// since, it rolls back: a crash after one restart, in the same boot, rolls back at once.
func TestTrialWindowsRestart(t *testing.T) {
	defer func(f func() time.Duration) { sinceBoot = f }(sinceBoot)
	dir, installed := trialRig(t, trialTo(t, "1.2.0", nsLatest))
	clock := &fakeClock{at(0, 2, 31)}
	beginTrial(dir, "1.2.0", clock.now)
	clock.t = at(0, 2, 34)
	sinceBoot = func() time.Duration { return time.Minute } // booted at 02:33
	if tr, exit := beginTrial(dir, "1.2.0", clock.now); tr == nil || exit {
		t.Fatal("a start after a restart rolled back")
	}
	clock.t = at(0, 2, 35)
	sinceBoot = func() time.Duration { return 2 * time.Minute }
	if tr, exit := beginTrial(dir, "1.2.0", clock.now); tr != nil || !exit {
		t.Fatal("a crash in the same boot went on")
	}
	rolledBack(t, dir, installed)

	dir, installed = trialRig(t, trialTo(t, "1.2.0", nsLatest))
	beginTrial(dir, "1.2.0", time.Now)
	sinceBoot = func() time.Duration { return 100000 * time.Hour }
	if _, exit := beginTrial(dir, "1.2.0", time.Now); !exit {
		t.Fatal("a start with no restart went on")
	}
	rolledBack(t, dir, installed)
}
