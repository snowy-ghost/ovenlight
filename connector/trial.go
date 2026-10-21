package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

// updateTrialFile is the last update's trial, in the state folder while it runs.
const updateTrialFile = "update-trial.json"

// updateTrial is update-trial.json. The swap writes it, synced, before the new binary is
// in place, and every run --service reads it before it parses its flags. The install and
// uninstall scripts remove it. Fields are added, never renamed or reinterpreted
// (docs/protocol.md).
type updateTrial struct {
	From string    `json:"from"`
	To   string    `json:"to"`
	At   time.Time `json:"at"` // when the swap was made
	// Apps are the slugs of the apps serving at the swap with their node logged in and its
	// key unexpired. Each must serve again, logged in, for the trial to pass.
	Apps []string `json:"apps"`
	// Index is the signed index that brought To, and Sig its signature in Namespace: To
	// must verify it with its own built-in keys.
	Index     []byte `json:"index"`
	Sig       []byte `json:"sig"`
	Namespace string `json:"namespace"`
	// Starts counts To's starts as the service since the swap or its last clean stop. A
	// second one within the trial means it crashed, hung or was killed, and puts .prev back.
	Starts int `json:"starts"`
	// Started is To's last counted start: a Windows boot after it stands in for a clean stop.
	Started time.Time `json:"started,omitzero"`
}

// serviceStart reports whether args (os.Args[1:]) start the connector as the installed
// service: run with --service. Only such a start reads the trial. CLI commands,
// ovenlight mcp and the login shell's helper never do.
func serviceStart(args []string) bool {
	return len(args) > 0 && args[0] == "run" && slices.Contains(args[1:], "--service")
}

// serviceTrial is the trial this process is on, from main, before the daemon starts.
var serviceTrial *trial

// trial is an update's trial in the binary on trial, the release it installed, started
// as the service. It runs quietFor of time awake from the start, before flag parsing, so
// a release that hangs while starting is caught too. It passes when the daemon is up,
// every app recorded at the swap has served again with its node logged in, and the
// binary has verified the index that brought it. Otherwise .prev comes back, and a
// release that deadlocks its daemon, so the trial can't end, is caught by backstop.
type trial struct {
	rec  updateTrial
	dir  string // the state folder, the binary's bin/..
	now  func() time.Time
	exit func(why string) // ends the connector after a rollback

	mu     sync.Mutex
	d      *daemon // once it's up
	served map[string]bool
	awake  time.Duration
	last   time.Time
	ended  bool
}

// startTrial reads the trial beside the running binary, first thing in main. A start it
// rolls back exits, and the service starts the binary put back.
func startTrial() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	t, exit := beginTrial(filepath.Dir(filepath.Dir(exe)), version(), func() time.Time { return time.Now().Round(0) })
	if exit {
		os.Exit(1)
	}
	if t == nil {
		return
	}
	t.exit = func(why string) {
		if d := t.daemon(); d != nil {
			d.exit(why)
			return
		}
		log.Printf("%s: exiting", why)
		os.Exit(1)
	}
	serviceTrial = t
	// The deadline runs before the new verifier does, so it catches a verifier that
	// hangs, and the start is counted, so a second start catches one that crashes.
	go t.watch()
	go t.backstop(func() { os.Exit(1) })
	if t.verifyIndex() {
		os.Exit(1)
	}
}

// beginTrial counts a start of version v as the service, given the state folder the
// binary is in, and returns its trial when it's on one. A trial to another version ends:
// the swap didn't finish, a rollback did, or an install script put another release in
// place. So does one with nothing to roll back to, or that isn't a trial record. One that
// can't be read now stays for the next start. A second start with no clean stop or
// Windows restart since the last counted start rolls back at once, and exit says the
// connector should then exit.
func beginTrial(dir, v string, now func() time.Time) (t *trial, exit bool) {
	path := filepath.Join(dir, updateTrialFile)
	data, err := jsonfile.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, false
	}
	if err != nil {
		log.Printf("updates: can't read the last update's trial: %v", err)
		return nil, false
	}
	t = &trial{dir: dir, now: now, served: map[string]bool{}}
	switch err := json.Unmarshal(data, &t.rec); {
	case err != nil:
		log.Printf("updates: the last update's trial ends, since it can't be read: %v", err)
	case t.rec.To != v:
	case !exists(prevBinary(installedBinary(dir))):
		log.Printf("updates: %s has nothing to roll back to, so its trial ends", v)
	case t.rec.Starts > 0 && !restartedSince(t.rec.Started, now()):
		t.rollback(fmt.Errorf("%s started again within its trial: it crashed, hung or was killed", v))
		return nil, true
	default:
		if t.rec.Starts > 0 {
			log.Printf("updates: Windows restarted during %s's trial, which starts again", v)
		}
		t.rec.Starts, t.rec.Started = 1, now()
		if err := jsonfile.Save(path, &t.rec); err != nil {
			t.rollback(fmt.Errorf("%s can't count its start: %w", v, err))
			return nil, true
		}
		t.last = now()
		return t, false
	}
	t.end()
	return nil, false
}

func exists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

// verifyIndex verifies the index that brought the release with this binary's own keys,
// which proves its verifier and keys before it's kept, with no network involved. One it
// can't rolls back, and it reports whether the connector should then exit.
func (t *trial) verifyIndex() bool {
	var idx *updateIndex
	err := errors.New("no index of the latest release or a canary")
	if t.rec.Namespace == nsLatest || t.rec.Namespace == nsCanary {
		idx, err = readIndex(t.rec.Index, t.rec.Sig, t.rec.Namespace)
	}
	if err == nil && idx.Latest != t.rec.To {
		err = fmt.Errorf("it names %s", idx.Latest)
	}
	if err != nil {
		t.rollback(fmt.Errorf("%s doesn't verify the index that brought it: %w", t.rec.To, err))
	}
	return err != nil
}

// attach gives the trial the daemon once it's up, serving its control socket.
func (t *trial) attach(d *daemon) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.d = d
}

func (t *trial) daemon() *daemon {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.d
}

// stopped uncounts the start when the connector stops cleanly during its trial, on a
// signal (the computer restarting, or the service restarted), so the next start begins
// the trial afresh rather than rolling back. A crash leaves the count, and a release that
// hangs is still caught by the deadline or the backstop.
func (t *trial) stopped() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ended {
		return
	}
	t.rec.Starts = 0
	if err := jsonfile.Save(filepath.Join(t.dir, updateTrialFile), &t.rec); err != nil {
		log.Printf("updates: marking the trial's clean stop: %v", err)
	}
}

// running reports whether the trial is still on: the updater swaps nothing meanwhile.
func (t *trial) running() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return !t.ended
}

// watch ticks every awakeTick until the trial is over.
func (t *trial) watch() {
	tick := time.NewTicker(awakeTick)
	defer tick.Stop()
	for range tick.C {
		if t.tick() {
			return
		}
	}
}

// awakeFor is the time awake between two ticks: none when a sleep, or a clock change,
// left a gap. Go's timers stop while the computer sleeps, so only the wall clock shows it.
func awakeFor(last, now time.Time) time.Duration {
	if gap := now.Sub(last); gap >= 0 && gap <= 3*awakeTick {
		return gap
	}
	return 0
}

// tick notes the recorded apps that serve, logged in, and adds the time since the last
// tick to the time awake. Once that's quietFor, the trial passes or rolls back. It
// reports whether the trial is over.
func (t *trial) tick() bool {
	now := t.now()
	t.mu.Lock()
	if t.ended {
		t.mu.Unlock()
		return true
	}
	t.awake += awakeFor(t.last, now)
	t.last = now
	d, due := t.d, t.awake >= quietFor
	t.mu.Unlock()
	if d != nil {
		for _, n := range d.snapshot() {
			if s := n.status(); servesLoggedIn(s) {
				t.mu.Lock()
				t.served[s.Slug] = true
				t.mu.Unlock()
			}
		}
	}
	if !due {
		return false
	}
	if err := t.failure(d); err != nil {
		t.rollback(err)
		t.exit("rolled back to " + t.rec.From)
		return true
	}
	// The trial ends before the update is recorded: a crash in between keeps the release,
	// which passed.
	t.end()
	log.Printf("updates: %s passed its trial", t.rec.To)
	if err := t.record(func(st *updateState) { st.Updated = &updateEvent{From: t.rec.From, To: t.rec.To, At: t.rec.At} }); err != nil {
		log.Printf("updates: recording the update to %s: %v", t.rec.To, err)
	}
	return true
}

// backstopAfter is how long past quietFor of time awake the backstop gives a trial that
// hasn't ended. A tick that ends the trial waits a minute at most, for an update check.
var backstopAfter = quietFor / 2

// backstop ends a trial that tick can't: a release that deadlocks its daemon blocks tick
// on the daemon's locks or the updater's, and its own trial would never end. It adds up
// time awake as tick does, takes none of those locks, and calls exit once it has rolled
// back.
func (t *trial) backstop(exit func()) {
	var awake time.Duration
	last := t.now()
	tick := time.NewTicker(awakeTick)
	defer tick.Stop()
	for range tick.C {
		now := t.now()
		awake, last = awake+awakeFor(last, now), now
		if !t.running() {
			return
		}
		if awake >= quietFor+backstopAfter {
			t.giveUp()
			exit()
			return
		}
	}
}

// giveUp is backstop's rollback. It writes the failure to update.json itself, and puts
// .prev back.
func (t *trial) giveUp() {
	why := fmt.Errorf("%s didn't end its trial within %s: it hung", t.rec.To, quietFor+backstopAfter)
	log.Printf("updates: rolling back from %s to %s: %v", t.rec.To, t.rec.From, why)
	if err := recordFile(t.dir, t.failed(why)); err != nil {
		log.Printf("updates: recording the failure of %s: %v", t.rec.To, err)
	}
	t.restore()
}

// failure is why the trial fails at its deadline, or nil when it passes. An app
// unpublished since the swap needn't serve.
func (t *trial) failure(d *daemon) error {
	if d == nil {
		return fmt.Errorf("%s didn't finish starting within its trial", t.rec.To)
	}
	cfg, found, err := loadConfigFile(d.configPath)
	t.mu.Lock()
	defer t.mu.Unlock()
	var missing []string
	for _, slug := range t.rec.Apps {
		if _, ok := cfg.Find(slug); t.served[slug] || (err == nil && found && !ok) {
			continue
		}
		missing = append(missing, slug)
	}
	if len(missing) > 0 {
		return fmt.Errorf("%s didn't serve again with its node logged in within %s's trial", strings.Join(missing, ", "), t.rec.To)
	}
	return nil
}

// servesLoggedIn reports whether an app's node serves with its node logged in.
func servesLoggedIn(s NodeStatus) bool { return s.State == "serving" && s.Backend == "Running" }

// rollback records why the release failed in update.json, puts .prev back in place of
// the binary on trial, and ends the trial. The connector should then exit.
func (t *trial) rollback(why error) {
	log.Printf("updates: rolling back from %s to %s: %v", t.rec.To, t.rec.From, why)
	// The failure first, so the release waits for the next night.
	if err := t.record(t.failed(why)); err != nil {
		log.Printf("updates: recording the failure of %s: %v", t.rec.To, err)
	}
	t.restore()
}

// failed records why the release failed. A crash after it's recorded starts the release
// again, and its second start rolls back without counting it twice.
func (t *trial) failed(why error) func(*updateState) {
	return func(st *updateState) {
		if f := st.Failed; f == nil || f.Version != t.rec.To || f.At.Before(t.rec.At) {
			st.noteFailure(t.rec.To, t.now(), why)
		}
	}
}

// restore puts .prev back in place of the binary on trial, and ends the trial. Either way
// a binary is at the service's path once it returns, for the service to start once the
// connector exits.
func (t *trial) restore() {
	installed := installedBinary(t.dir)
	// A crash once .prev is back starts it, and it ends the trial, which isn't its own.
	if err := restorePrev(installed); err != nil {
		// The trial stays, so the next start tries again.
		log.Printf("updates: can't put %s back: %v", prevBinary(installed), err)
		return
	}
	t.end()
}

// end removes the trial.
func (t *trial) end() {
	t.mu.Lock()
	t.ended = true
	t.mu.Unlock()
	if err := jsonfile.Remove(filepath.Join(t.dir, updateTrialFile)); err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Printf("updates: removing the trial: %v", err)
	}
}

// record changes update.json: through the updater once it runs, which holds the record,
// and in the file before.
func (t *trial) record(change func(*updateState)) error {
	if d := t.daemon(); d != nil && d.upd != nil {
		u := d.upd
		u.mu.Lock()
		defer u.mu.Unlock()
		change(&u.st)
		u.save()
		return nil
	}
	return recordFile(t.dir, change)
}

// recordFile changes update.json in the state folder dir.
func recordFile(dir string, change func(*updateState)) error {
	st, err := loadUpdateState(dir)
	if err != nil {
		return err
	}
	change(st)
	return jsonfile.Save(filepath.Join(dir, updateStateFile), st)
}
