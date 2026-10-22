package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
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
// every app recorded at the swap has served again with its node logged in, the binary
// has verified the index that brought it, and its updater polls. Otherwise .prev comes
// back: at the deadline, or by backstop when the trial can't end there, as when the
// daemon deadlocks or the updater doesn't poll.
type trial struct {
	rec  updateTrial
	dir  string // the state folder, the binary's bin/..
	now  func() time.Time
	exit func(why string) // ends the connector after a rollback

	mu       sync.Mutex
	d        *daemon // once it's up
	served   map[string]bool
	awake    time.Duration
	last     time.Time
	ended    bool
	givingUp bool // backstop is rolling back, and tick leaves the trial to it
	// polls is the updater's count of polls tick saw last, and polled the time awake then.
	polls  int64
	polled time.Duration
}

// startTrial reads the trial beside the running binary, first thing in main. A start it
// rolls back exits, and the service starts the binary put back.
func startTrial() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	// What the trial logs as it starts goes to the service's --log, which cmdRun opens later:
	// the log owners are pointed at, and a scheduled task's standard error goes nowhere. So
	// on Windows a crash from here on, before cmdRun too, is kept beside the log.
	if i := slices.Index(os.Args, "--log"); i > 0 && i+1 < len(os.Args) {
		if f, err := os.OpenFile(os.Args[i+1], os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
			log.SetOutput(f)
			defer func() { log.SetOutput(os.Stderr); f.Close() }()
		}
		if runtime.GOOS == "windows" {
			crash, err := os.OpenFile(filepath.Join(filepath.Dir(os.Args[i+1]), "ovenlight.stderr.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
			if err == nil {
				debug.SetCrashOutput(crash, debug.CrashOptions{}) // keeps its own copy of the file
				crash.Close()
			}
		}
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
// place. So does one that update.json records as passed, one with nothing to roll back
// to, and one that isn't a trial record. One that can't be read now stays for the next
// start, and so does every trial while another connector listens on the state folder's
// socket: run --service started by hand while the service runs then fails on its lock. A
// second start with no clean stop or Windows restart since the last counted start rolls
// back at once, and exit says the connector should then exit.
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
	// The state lock can't tell: it's the code on trial, and a release whose lock fails
	// would never count a start. A connector listening on the folder's socket is the
	// service, though, even one that won't answer this process, as an elevated one on
	// Windows won't answer one run by hand.
	if c, err := net.DialTimeout("unix", socketPath(dir), time.Second); err == nil {
		c.Close()
		return nil, false
	}
	t = &trial{dir: dir, now: now, served: map[string]bool{}}
	switch err := json.Unmarshal(data, &t.rec); {
	case err != nil:
		log.Printf("updates: the last update's trial ends, since it can't be read: %v", err)
	case t.rec.To != v:
	case passed(dir, t.rec): // and the trial outlived its pass
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
	if d != nil && d.upd != nil {
		if n := d.upd.polls.Load(); n != t.polls {
			t.polls, t.polled = n, t.awake
		}
	}
	polledLately := t.awake-t.polled <= 3*updateMinute
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
	err := t.failure(d)
	// A pass holds the updater's lock, taken before the trial's as poll takes them, and
	// needs its loop to have polled within the last 3 minutes awake, which a clock set back
	// can't fake: a release whose updater hangs holding the lock, or never polls, would
	// never update again, so it's left to the backstop.
	var u *updater
	if err == nil {
		if u = d.upd; !u.mu.TryLock() {
			return false
		}
		defer u.mu.Unlock()
		if !polledLately {
			return false
		}
	}
	t.mu.Lock()
	givingUp := t.givingUp
	if err == nil && !givingUp {
		t.ended = true // at once, so the backstop can't begin rolling a pass back
	}
	t.mu.Unlock()
	if givingUp {
		return true
	}
	if err != nil {
		t.rollback(err)
		t.exit("rolled back to " + t.rec.From)
		return true
	}
	// The pass is on disk before the trial goes, so a trial that a crash, a power cut or a
	// removal that fails leaves behind ends at the next start, and the release stays.
	log.Printf("updates: %s passed its trial", t.rec.To)
	u.st.Updated = &updateEvent{From: t.rec.From, To: t.rec.To, At: t.rec.At}
	u.save()
	syncDir(t.dir)
	t.end()
	return true
}

// backstopAfter is how long past quietFor of time awake the backstop gives a trial that
// hasn't ended. A pass waits for the updater's lock and its next poll, a minute or so at
// most.
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
			if t.giveUp() {
				exit()
			}
			return
		}
	}
}

// giveUp is backstop's rollback. It records the failure, and puts .prev back. It reports
// whether it rolled back: not once the trial has ended.
func (t *trial) giveUp() bool {
	t.mu.Lock()
	ended := t.ended
	t.givingUp = !ended
	t.mu.Unlock()
	if ended {
		return false
	}
	why := fmt.Errorf("%s didn't end its trial within %s: it hung", t.rec.To, humanDuration(quietFor+backstopAfter))
	log.Printf("updates: rolling back from %s to %s: %v", t.rec.To, t.rec.From, why)
	// Through the updater when it's free, and holding its lock until the exit, so no save
	// of its own comes after this one. Otherwise in the file.
	if d := t.daemon(); d != nil && d.upd != nil && d.upd.mu.TryLock() {
		t.failed(why)(&d.upd.st)
		d.upd.save()
	} else if err := recordFile(t.dir, t.failed(why)); err != nil {
		log.Printf("updates: recording the failure of %s: %v", t.rec.To, err)
	}
	t.restore()
	return true
}

// failure is why the trial fails at its deadline, or nil when it passes.
func (t *trial) failure(d *daemon) error {
	if d == nil {
		return fmt.Errorf("%s didn't finish starting within its trial", t.rec.To)
	}
	if d.upd == nil {
		return fmt.Errorf("%s's updater didn't start", t.rec.To)
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	var missing []string
	for _, slug := range t.rec.Apps {
		if !t.served[slug] {
			missing = append(missing, slug)
		}
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
	// The binary put back is on disk before the trial goes, so a power cut can't leave the
	// release on trial in place with no trial to roll it back.
	if err := syncDir(filepath.Dir(installed)); err != nil {
		log.Printf("updates: syncing %s: %v", filepath.Dir(installed), err)
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

// passed reports whether update.json in the state folder dir records rec's update as
// passed.
func passed(dir string, rec updateTrial) bool {
	st, err := loadUpdateState(dir)
	return err == nil && st.Updated != nil && st.Updated.To == rec.To && st.Updated.At.Equal(rec.At)
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
