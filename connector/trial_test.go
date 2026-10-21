package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

func TestServiceStart(t *testing.T) {
	for _, c := range []struct {
		args []string
		want bool
	}{
		{[]string{"run", "--service", "--log", "x"}, true},
		{[]string{"run", "--log", "x", "--service"}, true},
		{[]string{"run"}, false}, // a foreground run
		{[]string{"mcp"}, false},
		{[]string{"status", "--service"}, false},
		{[]string{env0Command}, false},
		{[]string{"version"}, false},
		{nil, false},
	} {
		if got := serviceStart(c.args); got != c.want {
			t.Errorf("%q: %v", c.args, got)
		}
	}
}

// trialTo is a trial from 1.1.5 to 1.2.0, brought by an index naming latest, signed in
// namespace.
func trialTo(t *testing.T, latest, namespace string) updateTrial {
	index := testIndex(latest, "1.1.0", "1.1.0", 100, "")
	return updateTrial{From: "1.1.5", To: "1.2.0", At: at(0, 2, 30), Index: index, Sig: signIndex(t, index, namespace), Namespace: namespace}
}

// trialRig is a state folder just after the swap rec records: bin/ovenlight is the
// release, "new", .prev the binary before it, "old", and update.json holds the index
// that brought it.
func trialRig(t *testing.T, rec updateTrial) (dir, installed string) {
	useTestKey(t)
	dir = t.TempDir()
	installed = installedBinary(dir)
	if err := os.MkdirAll(filepath.Dir(installed), 0o700); err != nil {
		t.Fatal(err)
	}
	writeFile(t, installed, "new")
	writeFile(t, prevBinary(installed), "old")
	if err := jsonfile.Save(filepath.Join(dir, updateTrialFile), &rec); err != nil {
		t.Fatal(err)
	}
	if err := jsonfile.Save(filepath.Join(dir, updateStateFile), &updateState{Index: rec.Index}); err != nil {
		t.Fatal(err)
	}
	return dir, installed
}

// rolledBack checks that .prev is back and the trial gone, and returns the failure
// recorded.
func rolledBack(t *testing.T, dir, installed string) *updateFailure {
	t.Helper()
	if readFile(t, installed) != "old" || exists(prevBinary(installed)) || exists(filepath.Join(dir, updateTrialFile)) {
		t.Error("not rolled back")
	}
	st, err := loadUpdateState(dir)
	if err != nil || st.Failed == nil {
		t.Fatalf("no failure recorded: %v", err)
	}
	return st.Failed
}

// The release's first start counts itself, and a second start within the trial rolls
// back: the failure recorded, .prev in place and the trial gone.
func TestTrialSecondStart(t *testing.T) {
	dir, installed := trialRig(t, trialTo(t, "1.2.0", nsLatest))
	clock := &fakeClock{at(0, 2, 31)}
	if tr, exit := beginTrial(dir, "1.2.0", clock.now); tr == nil || exit {
		t.Fatal("no trial")
	}
	if rec := readTrial(t, dir); rec.Starts != 1 || readFile(t, installed) != "new" {
		t.Fatalf("the start isn't counted: %+v", rec)
	}
	clock.t = at(0, 2, 32)
	if tr, exit := beginTrial(dir, "1.2.0", clock.now); tr != nil || !exit {
		t.Fatal("a second start went on")
	}
	f := rolledBack(t, dir, installed)
	if f.Version != "1.2.0" || !f.At.Equal(clock.t) || f.Count != 1 || !strings.Contains(f.Error, "started again within its trial") {
		t.Errorf("%+v", f)
	}
	if u := readUpdateView(dir, "1.1.5", true); u.Failure == nil {
		t.Error("status doesn't show the rollback")
	}
}

// A clean stop during the trial, such as the computer restarting, marks it, and the next
// start begins it afresh. A start after that with no clean stop rolls back.
func TestTrialCleanStop(t *testing.T) {
	dir, installed := trialRig(t, trialTo(t, "1.2.0", nsLatest))
	tr, _ := beginTrial(dir, "1.2.0", time.Now)
	tr.stopped()
	if rec := readTrial(t, dir); !rec.Stopped || rec.Starts != 1 {
		t.Fatalf("not marked: %+v", rec)
	}
	if tr, exit := beginTrial(dir, "1.2.0", time.Now); tr == nil || exit {
		t.Fatal("a start after a clean stop rolled back")
	}
	if rec := readTrial(t, dir); rec.Stopped || rec.Starts != 1 || readFile(t, installed) != "new" {
		t.Fatalf("not afresh: %+v", rec)
	}
	if _, exit := beginTrial(dir, "1.2.0", time.Now); !exit {
		t.Fatal("a crash after it went on")
	}
	rolledBack(t, dir, installed)

	// Once the trial is over, a stop leaves nothing behind.
	dir, _ = trialRig(t, trialTo(t, "1.2.0", nsLatest))
	tr, _ = beginTrial(dir, "1.2.0", time.Now)
	tr.end()
	tr.stopped()
	if exists(filepath.Join(dir, updateTrialFile)) {
		t.Error("a stop after the trial wrote it again")
	}
}

// A rollback that a crash cut short after recording its failure doesn't count it again,
// and a failure from an earlier night is counted with it.
func TestTrialRollbackCountsOnce(t *testing.T) {
	for _, c := range []struct {
		failedAt time.Time
		want     int
	}{{at(0, 2, 33), 1}, {at(-1, 2, 30), 2}} {
		rec := trialTo(t, "1.2.0", nsLatest)
		rec.Starts = 1
		dir, installed := trialRig(t, rec)
		st := &updateState{Failed: &updateFailure{Version: "1.2.0", At: c.failedAt, Error: "earlier", Count: 1}}
		if err := jsonfile.Save(filepath.Join(dir, updateStateFile), st); err != nil {
			t.Fatal(err)
		}
		clock := &fakeClock{at(0, 2, 34)}
		beginTrial(dir, "1.2.0", clock.now)
		if f := rolledBack(t, dir, installed); f.Count != c.want {
			t.Errorf("failed at %v: %+v", c.failedAt, f)
		}
	}
}

// A trial to another version ends: the swap didn't finish, a rollback did, or an install
// script put another release in place. So does one with nothing to roll back to, and one
// that can't be read. Nothing moves.
func TestTrialEnds(t *testing.T) {
	for name, setup := range map[string]func(dir, installed string){
		"another version":      func(string, string) {},
		"nothing to roll back": func(_, installed string) { os.Remove(prevBinary(installed)) },
		"unreadable":           func(dir, _ string) { writeFile(t, filepath.Join(dir, updateTrialFile), "{") },
	} {
		dir, installed := trialRig(t, trialTo(t, "1.2.0", nsLatest))
		setup(dir, installed)
		v := "1.2.0"
		if name == "another version" {
			v = "1.1.5"
		}
		if tr, exit := beginTrial(dir, v, time.Now); tr != nil || exit {
			t.Errorf("%s: still on trial", name)
		}
		if exists(filepath.Join(dir, updateTrialFile)) || readFile(t, installed) != "new" {
			t.Errorf("%s: the trial stays, or the binary moved", name)
		}
		if st, _ := loadUpdateState(dir); st.Failed != nil {
			t.Errorf("%s: a failure: %+v", name, st.Failed)
		}
	}

	// One that can't be read now, rather than one that isn't a trial, stays for the next
	// start.
	dir, _ := trialRig(t, trialTo(t, "1.2.0", nsLatest))
	path := filepath.Join(dir, updateTrialFile)
	os.Remove(path)
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}
	if tr, exit := beginTrial(dir, "1.2.0", time.Now); tr != nil || exit || !exists(path) {
		t.Error("a trial that can't be read now went")
	}
}

// The release must verify, with its own keys, the index that brought it: a canary index
// for a canary, the published one otherwise. One it can't, or that names another
// release, rolls back at its first start, which is counted first, so a verifier that
// crashes rolls back at the next start without running again.
func TestTrialIndex(t *testing.T) {
	canary := trialTo(t, "1.2.0", nsCanary)
	dir, _ := trialRig(t, canary)
	if tr, _ := beginTrial(dir, "1.2.0", time.Now); tr == nil || tr.verifyIndex() {
		t.Error("a canary index was refused")
	}
	wrongNS := trialTo(t, "1.2.0", nsLatest)
	wrongNS.Namespace = nsCanary
	file := trialTo(t, "1.2.0", "file")
	other := trialTo(t, "1.2.1", nsLatest)
	unsigned := trialTo(t, "1.2.0", nsLatest)
	unsigned.Sig = nil
	for name, rec := range map[string]updateTrial{"signed for another namespace": wrongNS, "in namespace file": file, "naming 1.2.1": other, "unsigned": unsigned} {
		dir, installed := trialRig(t, rec)
		tr, _ := beginTrial(dir, "1.2.0", time.Now)
		if tr == nil || readTrial(t, dir).Starts != 1 || !tr.verifyIndex() {
			t.Errorf("%s: no rollback", name)
			continue
		}
		if f := rolledBack(t, dir, installed); !strings.Contains(f.Error, "doesn't verify the index that brought it") {
			t.Errorf("%s: %+v", name, f)
		}
	}
	rec := trialTo(t, "1.2.1", nsLatest)
	rec.Starts = 1
	dir, installed := trialRig(t, rec)
	if tr, exit := beginTrial(dir, "1.2.0", time.Now); tr != nil || !exit {
		t.Fatal("the start after a verifier that crashed went on")
	}
	if f := rolledBack(t, dir, installed); !strings.Contains(f.Error, "started again") {
		t.Errorf("%+v", f)
	}
}

// trialing is a release on trial, with apps recorded, whose exits are noted.
type trialing struct {
	*trial
	clock *fakeClock
	exits []string
}

func startTrialing(t *testing.T, apps ...string) (*trialing, string, string) {
	rec := trialTo(t, "1.2.0", nsLatest)
	rec.Apps = apps
	dir, installed := trialRig(t, rec)
	tr := &trialing{clock: &fakeClock{at(0, 2, 31)}}
	tr.trial, _ = beginTrial(dir, "1.2.0", tr.clock.now)
	if tr.trial == nil {
		t.Fatal("no trial")
	}
	tr.exit = func(why string) { tr.exits = append(tr.exits, why) }
	return tr, dir, installed
}

// pass ticks for d of time awake, and reports whether the trial ended.
func (tr *trialing) pass(d time.Duration) bool {
	for end := tr.clock.t.Add(d); tr.clock.t.Before(end); {
		tr.clock.t = tr.clock.t.Add(awakeTick)
		if tr.tick() {
			return true
		}
	}
	return false
}

// The trial runs 10 minutes of time awake, a sleep not counted. Once every app recorded
// has served again, logged in, at any point in it, it passes: the release stays and the
// update is recorded.
func TestTrialPasses(t *testing.T) {
	if updateHour != time.Hour {
		t.Skip("a compressed schedule")
	}
	tr, dir, installed := startTrialing(t, "coach")
	d, _ := testDaemon(t)
	d.upd = &updater{d: d, now: tr.clock.now}
	tr.attach(d)
	if tr.pass(3 * time.Minute) {
		t.Fatal("over in 3 minutes")
	}
	d.nodes["coach"].serving = true
	tr.pass(time.Minute)
	d.nodes["coach"].serving = false
	tr.clock.t = tr.clock.t.Add(time.Hour) // asleep, and the first tick after it counts nothing
	if tr.pass(6*time.Minute) || !tr.running() {
		t.Fatal("over within 10 minutes awake")
	}
	if !tr.pass(10 * time.Second) {
		t.Fatal("not over after 10 minutes awake")
	}
	if len(tr.exits) != 0 || exists(filepath.Join(dir, updateTrialFile)) || readFile(t, installed) != "new" || tr.running() {
		t.Error("didn't pass")
	}
	// Compared field by field: a time read back from JSON has another location.
	st, _ := loadUpdateState(d.stateDir) // the updater's, which holds the record
	for _, u := range []*updateEvent{d.upd.st.Updated, st.Updated} {
		if u == nil || u.From != "1.1.5" || u.To != "1.2.0" || !u.At.Equal(at(0, 2, 30)) {
			t.Errorf("%+v", u)
		}
	}
}

// At the deadline, an app recorded that hasn't served again, logged in, or a daemon that
// never came up, rolls back and exits. An app unpublished since needn't serve.
func TestTrialDeadline(t *testing.T) {
	if updateHour != time.Hour {
		t.Skip("a compressed schedule")
	}
	tr, dir, installed := startTrialing(t, "coach")
	d, _ := testDaemon(t) // coach logged in, not serving
	tr.attach(d)
	if !tr.pass(10 * time.Minute) {
		t.Fatal("not over")
	}
	if f := rolledBack(t, dir, installed); f.Version != "1.2.0" || !strings.Contains(f.Error, "coach didn't serve again") {
		t.Errorf("%+v", f)
	}
	if len(tr.exits) != 1 || !strings.Contains(tr.exits[0], "1.1.5") {
		t.Errorf("exits: %q", tr.exits)
	}

	tr, dir, installed = startTrialing(t)
	tr.pass(10 * time.Minute)
	if f := rolledBack(t, dir, installed); !strings.Contains(f.Error, "didn't finish starting") || len(tr.exits) != 1 {
		t.Errorf("never up: %+v", f)
	}

	tr, dir, installed = startTrialing(t, "coach", "gone")
	d, _ = testDaemon(t)
	d.nodes["coach"].serving = true
	if err := jsonfile.Save(d.configPath, &Config{Apps: []App{{Name: "Interview Coach", Slug: "coach", Port: 4317}}}); err != nil {
		t.Fatal(err)
	}
	tr.attach(d)
	tr.pass(10 * time.Minute)
	if len(tr.exits) != 0 || readFile(t, installed) != "new" || exists(filepath.Join(dir, updateTrialFile)) {
		t.Error("an unpublished app failed the trial")
	}
}

// A release that deadlocks its daemon blocks tick, and the backstop still rolls it back:
// it takes neither the daemon's locks nor the updater's.
func TestTrialBackstop(t *testing.T) {
	tr, dir, installed := startTrialing(t, "coach")
	d, _ := testDaemon(t)
	d.upd = &updater{d: d, now: time.Now}
	tr.attach(d)
	d.mu.Lock()
	d.upd.mu.Lock()
	defer d.upd.mu.Unlock()
	defer d.mu.Unlock()
	defer func(tick, quiet, after time.Duration) { awakeTick, quietFor, backstopAfter = tick, quiet, after }(awakeTick, quietFor, backstopAfter)
	awakeTick, quietFor, backstopAfter = time.Millisecond, 20*time.Millisecond, 10*time.Millisecond
	tr.now = time.Now
	ticked := make(chan bool, 1)
	go func() { ticked <- tr.pass(time.Minute) }() // stuck on d.mu, as the release's own trial is
	done := make(chan struct{})
	go tr.backstop(func() { close(done) })
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("the backstop didn't end the trial")
	}
	if f := rolledBack(t, dir, installed); f.Version != "1.2.0" || !strings.Contains(f.Error, "didn't end its trial") {
		t.Errorf("%+v", f)
	}
	select {
	case <-ticked:
		t.Error("tick wasn't stuck")
	default:
	}
}

// During the last update's trial the updater downloads and swaps nothing, and once a swap
// has begun the exit, it does nothing more.
func TestUpdaterWaitsForTheTrial(t *testing.T) {
	srv, tu, _ := readyAt(t, false)
	tu.d.trial = &trial{}
	tu.pollAt(at(3, 2, 30))
	canary := testIndex("1.2.0", "1.1.0", "1.1.0", 1, srv.release("1.2.0"))
	if _, err := tu.updateNow(context.Background(), canary, signIndex(t, canary, nsCanary)); err == nil || !strings.Contains(err.Error(), "on its trial") {
		t.Errorf("update now: %v", err)
	}
	if len(tu.swaps) != 0 {
		t.Fatal("swapped during the trial")
	}
	tu.d.trial.ended = true
	tu.pollAt(at(3, 2, 31))
	if len(tu.swaps) != 1 {
		t.Error("no swap after the trial")
	}
	tu.d.exitBegun.Store(true)
	n := srv.count("/1.2.0/" + archiveName())
	tu.pollAt(at(3, 2, 32))
	tu.pollAt(at(3, 2, 33))
	if len(tu.swaps) != 1 || srv.count("/1.2.0/"+archiveName()) != n {
		t.Error("the updater went on after the swap")
	}
}
