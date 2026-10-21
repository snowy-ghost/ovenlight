package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"
)

// testVersionVar is what the test binary prints when it's run with version, standing in
// for a downloaded release, and holdCommand keeps it running for a minute, as a binary in
// use.
const testVersionVar, holdCommand = "OVENLIGHT_TEST_VERSION", "__hold"

// runAsRelease is TestMain's part for the swap's tests: the test binary run as a release.
func runAsRelease() {
	if len(os.Args) == 2 && os.Args[1] == "version" && os.Getenv(testVersionVar) != "" {
		fmt.Println(os.Getenv(testVersionVar))
		os.Exit(0)
	}
	if len(os.Args) > 1 && os.Args[1] == holdCommand {
		time.Sleep(time.Minute)
		os.Exit(0)
	}
}

func writeFile(t *testing.T, path, body string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(body), 0o755); err != nil {
		t.Fatal(err)
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Error(err)
	}
	return string(b)
}

// copyTestBinary copies the test binary to path, as a release that runs.
func copyTestBinary(t *testing.T, path string) {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, b, 0o755); err != nil {
		t.Fatal(err)
	}
}

// statFile is the file at path as an open handle to it sees it, so it names that file
// even once another is at path. Windows' os.Stat reads which file it is by its path only
// when it's compared.
func statFile(path string) (os.FileInfo, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return f.Stat()
}

func exe(name string) string {
	if runtime.GOOS == "windows" {
		return name + ".exe"
	}
	return name
}

// swapRig is a daemon whose bin holds the running binary, "old", and a downloaded 1.2.0
// in next, the test binary.
func swapRig(t *testing.T) (d *daemon, installed, next string) {
	useTestKey(t)
	d, _ = testDaemon(t)
	installed = installedBinary(d.stateDir)
	if err := os.MkdirAll(filepath.Dir(installed), 0o700); err != nil {
		t.Fatal(err)
	}
	writeFile(t, installed, "old")
	next = filepath.Join(filepath.Dir(installed), exe("ovenlight.next"))
	copyTestBinary(t, next)
	t.Setenv(testVersionVar, "1.2.0")
	return d, installed, next
}

func readTrial(t *testing.T, dir string) updateTrial {
	t.Helper()
	var rec updateTrial
	b, err := os.ReadFile(filepath.Join(dir, updateTrialFile))
	if err == nil {
		err = json.Unmarshal(b, &rec)
	}
	if err != nil {
		t.Fatal(err)
	}
	return rec
}

// The swap records the trial with the apps serving logged in, keeps the running binary
// as .prev, puts the release in its place and begins the exit. The release's first
// start then finds its trial.
func TestSwapIn(t *testing.T) {
	d, installed, next := swapRig(t)
	d.nodes["coach"].serving = true
	addApp(d, App{Name: "Poll", Slug: "poll", Port: 4318})
	expired := time.Now().Add(-time.Hour)
	d.nodes["poll"].serving, d.nodes["poll"].keyExpiry = true, &expired
	addApp(d, App{Name: "Notes", Slug: "notes", Port: 4319}) // logged in, not serving yet
	checked, err := statFile(next)
	if err != nil {
		t.Fatal(err)
	}
	index := testIndex("1.2.0", "1.1.0", "1.1.0", 1, "")
	sig := signIndex(t, index, nsCanary)
	if err := d.swapIn(pendingSwap{Version: "1.2.0", Next: next, Index: index, Sig: sig, Namespace: nsCanary}); err != nil {
		t.Fatal(err)
	}
	if info, err := statFile(installed); err != nil || !os.SameFile(info, checked) {
		t.Error("the release isn't in place")
	}
	if readFile(t, prevBinary(installed)) != "old" || exists(next) {
		t.Error("the running binary isn't .prev")
	}
	rec := readTrial(t, d.stateDir)
	if rec.From != version() || rec.To != "1.2.0" || !slices.Equal(rec.Apps, []string{"coach"}) || !bytes.Equal(rec.Index, index) ||
		!bytes.Equal(rec.Sig, sig) || rec.Namespace != nsCanary || rec.Starts != 0 || time.Since(rec.At) > time.Minute {
		t.Errorf("%+v", rec)
	}
	select {
	case why := <-d.exiting():
		if !strings.Contains(why, "1.2.0") {
			t.Error(why)
		}
	default:
		t.Error("no exit")
	}
	if tr, exit := beginTrial(d.stateDir, "1.2.0", runtime.GOOS == "windows", time.Now); tr == nil || exit {
		t.Error("the release's start found no trial")
	}
}

// A release that doesn't run here, or says it's another version, isn't swapped in, and
// nothing is left behind.
func TestSwapInRefuses(t *testing.T) {
	for name, c := range map[string]struct {
		setup func(t *testing.T, next string)
		want  string
	}{
		"another version": {func(t *testing.T, _ string) { t.Setenv(testVersionVar, "1.2.1") }, `says it's "1.2.1"`},
		"not a program":   {func(t *testing.T, next string) { writeFile(t, next, "connector 1.2.0") }, "doesn't run here"},
	} {
		t.Run(name, func(t *testing.T) {
			d, installed, next := swapRig(t)
			c.setup(t, next)
			err := d.swapIn(pendingSwap{Version: "1.2.0", Next: next, Namespace: nsLatest})
			if err == nil || !strings.Contains(err.Error(), c.want) {
				t.Errorf("%v", err)
			}
			if readFile(t, installed) != "old" || exists(prevBinary(installed)) || exists(filepath.Join(d.stateDir, updateTrialFile)) {
				t.Error("something was swapped or left behind")
			}
			select {
			case <-d.exiting():
				t.Error("an exit")
			default:
			}
		})
	}
}

// macOS and Linux: the running binary is linked as .prev, never written into, and the
// release renamed over it.
func TestLinkAndRename(t *testing.T) {
	bin := t.TempDir()
	installed, prev, next := filepath.Join(bin, "ovenlight"), filepath.Join(bin, "ovenlight.prev"), filepath.Join(bin, "ovenlight.next")
	writeFile(t, installed, "running")
	writeFile(t, prev, "older")
	writeFile(t, next, "new")
	running, _ := statFile(installed)
	if err := linkAndRename(installed, next); err != nil {
		t.Fatal(err)
	}
	if info, err := statFile(prev); err != nil || !os.SameFile(info, running) {
		t.Error(".prev isn't the running binary")
	}
	if readFile(t, installed) != "new" || readFile(t, prev) != "running" || exists(next) {
		t.Error("not swapped")
	}
}

// oldBinaries are the binaries moved aside in bin.
func oldBinaries(bin string) []string {
	old, _ := filepath.Glob(filepath.Join(bin, "ovenlight.old*.exe"))
	return old
}

// Windows' renames, on every OS: the old binaries go, an older .prev moves aside, the
// running binary becomes .prev and the release takes its place. A last rename that fails
// puts .prev back.
func TestRenameAside(t *testing.T) {
	bin := t.TempDir()
	installed, prev, next := filepath.Join(bin, "ovenlight.exe"), filepath.Join(bin, "ovenlight.prev.exe"), filepath.Join(bin, "ovenlight.next.exe")
	writeFile(t, installed, "running")
	writeFile(t, prev, "older")
	writeFile(t, next, "new")
	writeFile(t, filepath.Join(bin, "ovenlight.old-1.exe"), "stale")
	if err := renameAside(installed, next); err != nil {
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
	if err := renameAside(installed, filepath.Join(bin, "ovenlight.next.exe")); err == nil {
		t.Error("no release, and no error")
	}
	if readFile(t, installed) != "running" || exists(prev) {
		t.Error(".prev isn't back")
	}

	// A put-back that failed left the running binary as .prev and nothing at installed:
	// it's put back first, never moved aside.
	os.Rename(installed, prev)
	writeFile(t, next, "new")
	if err := renameAside(installed, next); err != nil {
		t.Fatal(err)
	}
	if readFile(t, installed) != "new" || readFile(t, prev) != "running" || len(oldBinaries(bin)) != 0 {
		t.Errorf("after a failed put-back: %v", oldBinaries(bin))
	}
}

// A rollback puts .prev back: by one rename on macOS and Linux, and on Windows with the
// running binary moved aside first, and back when .prev can't move.
func TestRestorePrev(t *testing.T) {
	for _, windows := range []bool{false, true} {
		bin := t.TempDir()
		installed := filepath.Join(bin, "ovenlight.exe")
		prev := prevBinary(installed)
		writeFile(t, installed, "new")
		writeFile(t, prev, "old")
		if err := restorePrev(installed, windows); err != nil {
			t.Fatal(err)
		}
		if readFile(t, installed) != "old" || exists(prev) {
			t.Errorf("windows %v: not put back", windows)
		}
		if old := oldBinaries(bin); windows != (len(old) == 1) {
			t.Errorf("windows %v: old binaries %v", windows, old)
		}
	}
	bin := t.TempDir()
	installed := filepath.Join(bin, "ovenlight.exe")
	writeFile(t, installed, "new")
	if err := restorePrev(installed, true); err == nil || readFile(t, installed) != "new" || len(oldBinaries(bin)) != 0 {
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
	if runtime.GOOS != "windows" {
		t.Skip("only Windows keeps a running binary from being replaced or deleted")
	}
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
	if err := renameAside(installed, next); err != nil {
		t.Fatal(err)
	}
	now, _ := statFile(installed)
	nowPrev, _ := statFile(prev)
	if !os.SameFile(now, release) || !os.SameFile(nowPrev, was) || exists(stale) || !exists(running) || len(oldBinaries(bin)) != 2 {
		t.Errorf("after the swap: %v, the stale one there: %v, the running one there: %v", oldBinaries(bin), exists(stale), exists(running))
	}

	held = append(held, hold(t, exec.Command(installed, holdCommand))) // the release on trial, rolled back
	if err := restorePrev(installed, true); err != nil {
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
