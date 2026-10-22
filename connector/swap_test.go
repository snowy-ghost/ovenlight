package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
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
	case why := <-d.exits:
		if !strings.Contains(why, "1.2.0") {
			t.Error(why)
		}
	default:
		t.Error("no exit")
	}
	if tr, exit := beginTrial(d.stateDir, "1.2.0", time.Now); tr == nil || exit {
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
			case <-d.exits:
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
