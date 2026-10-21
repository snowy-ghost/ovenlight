package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/rand/v2"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

// The updater's files in the state folder: update.json, its record, and updates-off,
// there while updates are off. That choice is a file of its own, so a binary that drops
// a field of update.json it doesn't know can't lose it.
const (
	updateStateFile = "update.json"
	updatesOffFile  = "updates-off"
)

// The updater's clock. Every interval is a fraction or a multiple of updateHour, so a
// test-key build can run its days in minutes.
var (
	updateMinute = updateHour / 60
	updateDay    = 24 * updateHour
	// quietFor without a request is a quiet moment, and a swap waits as long after the
	// connector starts or wakes, so its apps are serving when the trial records them.
	quietFor = 10 * updateMinute
	// awakeTick is how often a trial adds up its time awake: 10 seconds.
	awakeTick = updateHour / 360
)

// The night, on dayTime's clock, when a quiet connector takes an update that isn't a
// security fix. Each computer's starts later by up to two hours, by its daily minute
// (nightOf), so computers spread out, and fewer swap as the OS restarts for its own
// updates.
const nightStart, nightEnd = 2 * time.Hour, 5 * time.Hour

// nightOf is when the night starts for the computer whose daily check is at minute.
func nightOf(minute int) time.Duration {
	return nightStart + time.Duration(minute%120)*time.Minute
}

// updateState is update.json. Only the connector writes it, and only uninstall --purge
// deletes it. Fields are added, never renamed or reinterpreted (docs/protocol.md).
type updateState struct {
	// Index is the signed index accepted last, byte for byte as fetched. It's local state
	// and never checked again, so its serial, secure and reinstallBelow still bind a binary
	// whose keys couldn't verify it.
	Index []byte `json:"index,omitempty"`
	// Seen is when this computer first saw the index's latest named: its wait starts then.
	Seen time.Time `json:"seen,omitzero"`
	// Since is when the updater first ran here, and Minute the minute of the day, chosen at
	// random then, of its daily check.
	Since  time.Time `json:"since"`
	Minute int       `json:"minute"`
	// Checked is the last verified check, by the version CheckedBy. NextCheck is when the
	// next one is due, and CheckError why the last one failed, "" once one works.
	Checked    time.Time `json:"checked,omitzero"`
	CheckedBy  string    `json:"checkedBy,omitempty"`
	NextCheck  time.Time `json:"nextCheck,omitzero"`
	CheckError string    `json:"checkError,omitempty"`
	// Updated is the last update that passed its trial. Failed is the last one that
	// failed: a download, a swap, or a trial that rolled back.
	Updated *updateEvent   `json:"updated,omitempty"`
	Failed  *updateFailure `json:"failed,omitempty"`
}

// updateEvent is an update from one version to another, and when.
type updateEvent struct {
	From string    `json:"from"`
	To   string    `json:"to"`
	At   time.Time `json:"at"`
}

// updateFailure is the version that failed, when, why, and how many times in a row that
// version has failed. It's tried again the next night.
type updateFailure struct {
	Version string    `json:"version"`
	At      time.Time `json:"at"`
	Error   string    `json:"error"`
	Count   int       `json:"count"`
}

// loadUpdateState reads update.json from the state folder: an empty record when there's
// none.
func loadUpdateState(stateDir string) (*updateState, error) {
	st := &updateState{}
	path := filepath.Join(stateDir, updateStateFile)
	data, err := jsonfile.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return st, nil
	}
	if err == nil {
		err = json.Unmarshal(data, st)
	}
	if err != nil {
		return nil, fmt.Errorf("can't read %s: %w", path, err)
	}
	return st, nil
}

// updatesOff reports whether updates are off. A file it can't check counts as there.
func updatesOff(stateDir string) bool {
	_, err := os.Lstat(filepath.Join(stateDir, updatesOffFile))
	return !errors.Is(err, os.ErrNotExist)
}

// installedBinary is the binary the service runs, in the connector's bin folder.
func installedBinary(stateDir string) string {
	name := "ovenlight"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	return filepath.Join(stateDir, "bin", name)
}

// selfUpdating reports whether a connector of version v updates itself: a release build,
// started as the installed service, whose binary exe is the installed one. They're
// compared as files, so a symlinked home or a differently cased Windows path still
// matches. Development builds, pre-releases, builds from source and a foreground run
// never update themselves.
func selfUpdating(v string, service bool, exe, installed string) bool {
	if _, ok := semver(v); !ok || !service {
		return false
	}
	a, errA := os.Stat(exe)
	b, errB := os.Stat(installed)
	return errA == nil && errB == nil && os.SameFile(a, b)
}

// updater checks daily for a new release, downloads it once it's due, and swaps it in at
// a quiet moment. Go's timers stop while the computer sleeps, so it compares the wall
// clock with the times update.json keeps.
type updater struct {
	d    *daemon
	base string // where releases come from: updateBase
	v    string // the running version
	now  func() time.Time
	swap func(pendingSwap) error

	mu    sync.Mutex // serializes checks, downloads and swaps: the loop's and update now's
	st    updateState
	ready string // the release extracted into next, waiting for its moment
	next  string
	// awake is since when the connector has been up and awake, and lastRan when the
	// updater last looked.
	awake, lastRan time.Time
}

// newUpdater starts the updater from update.json. One it can't read would stop updates
// for good, so it's kept aside as update.json.bad and the updater starts afresh: its first
// check fetches the index again.
func newUpdater(d *daemon) *updater {
	st, err := loadUpdateState(d.stateDir)
	if err != nil {
		path := filepath.Join(d.stateDir, updateStateFile)
		log.Printf("updates: %v; it's kept as %s.bad, and the updater starts afresh", err, updateStateFile)
		if err := os.Rename(path, path+".bad"); err != nil {
			log.Printf("updates: %v", err)
		}
		st = &updateState{}
	}
	u := &updater{d: d, base: updateBase, v: version(),
		now: func() time.Time { return time.Now().Round(0) }, swap: d.swapIn, st: *st}
	u.start()
	return u
}

// start sets up the record on the first run, and has a version's first run check at once,
// so a new release's first check comes within its trial.
func (u *updater) start() {
	now := u.now()
	u.awake, u.lastRan = now, now
	if u.st.Since.IsZero() {
		u.st.Since, u.st.Minute = now, rand.N(24*60)
	}
	if u.st.CheckedBy != u.v {
		u.st.NextCheck = time.Time{}
	}
	// A rollback happens before the connector's log is open, so the release put back says so.
	if f := u.st.Failed; f != nil {
		cur, okC := semver(u.v)
		if fv, okF := semver(f.Version); okC && okF && less(cur, fv) {
			log.Printf("updates: updating to %s failed on %s: %s", f.Version, when(f.At), f.Error)
		}
	}
	u.save()
}

// run polls every minute until stop is closed.
func (u *updater) run(stop <-chan struct{}) {
	ctx, cancel := context.WithCancel(context.Background())
	go func() { <-stop; cancel() }()
	t := time.NewTicker(updateMinute)
	defer t.Stop()
	for {
		u.poll(ctx)
		select {
		case <-stop:
			return
		case <-t.C:
		}
	}
}

// noteAwake notes that the updater runs at now, and returns since when the connector has
// been up and awake. A poll that comes three minutes after the last, or before it, means
// the computer slept (or its clock moved) in between, and it has been awake only since. A
// poll that held u.mu that long looks the same, which only delays a swap. Go's timers stop
// while the computer sleeps, so only the wall clock shows a sleep. u.mu is held.
func (u *updater) noteAwake(now time.Time) time.Time {
	if gap := now.Sub(u.lastRan); gap > 3*updateMinute || gap < 0 {
		u.awake = now
	}
	u.lastRan = now
	return u.awake
}

// poll checks when a check is due, then takes the update the index held names a step
// further: its download once it's due, and its swap at its moment.
func (u *updater) poll(ctx context.Context) {
	u.mu.Lock()
	defer u.mu.Unlock()
	// Once a swap has begun the exit, another would make the new binary .prev.
	if ctx.Err() != nil || u.d.exitBegun.Load() {
		return
	}
	now := u.now()
	u.noteAwake(now)
	if u.st.NextCheck.After(now.Add(updateDay + updateHour)) {
		u.st.NextCheck = now // the clock was set back
	}
	// Times noted while the clock ran ahead would hold the wait and the retry back as long.
	if u.st.Seen.After(now) {
		u.st.Seen = now
	}
	if f := u.st.Failed; f != nil && f.At.After(now) {
		f.At = now
	}
	if !now.Before(u.st.NextCheck) {
		u.check(ctx)
	}
	u.advance(ctx)
}

// check fetches the published index and judges it against the one held. A verified
// check holds a new index, and schedules the next check for the next day at the chosen
// minute. A failed one is tried again within the hour.
func (u *updater) check(ctx context.Context) (data, sig []byte, idx *updateIndex, err error) {
	data, sig, err = fetchIndex(ctx, u.base)
	fresh := false
	if err == nil {
		idx, fresh, err = checkIndex(u.st.Index, data, sig)
	}
	if ctx.Err() != nil {
		return nil, nil, nil, ctx.Err() // shutting down
	}
	now := u.now()
	if err != nil {
		log.Printf("update check: %v", err)
		u.st.CheckError, u.st.NextCheck = err.Error(), now.Add(updateMinute+rand.N(59*updateMinute))
		u.save()
		return nil, nil, nil, err
	}
	held := ""
	if h, err := parseIndex(u.st.Index); err == nil {
		held = h.Latest
	}
	if fresh {
		log.Printf("update check: the index names %s as the latest, %s as secure and %s as the oldest to update without its install script",
			idx.Latest, idx.Secure, idx.ReinstallBelow)
		u.st.Index = data
	}
	u.st.Seen = firstSeen(idx.Latest, held, u.st.Seen, now)
	u.st.Checked, u.st.CheckedBy, u.st.CheckError = now, u.v, ""
	u.st.NextCheck = nextAt(now.Add(updateMinute), time.Duration(u.st.Minute)*time.Minute)
	u.save()
	return data, sig, idx, nil
}

// advance downloads the release the index held names once it's due, and swaps it in at
// its moment, after a fresh fetch of the index that still names it. updates-off is read
// before the download, and before the swap once that fetch is done.
func (u *updater) advance(ctx context.Context) {
	now := u.now()
	p := planUpdate(&u.st, u.v)
	// Nothing is downloaded or swapped during the last update's trial, which may need the
	// record.
	if p.target == "" || now.Before(p.after) || updatesOff(u.d.stateDir) || u.d.onTrial() {
		return
	}
	if u.ready != p.target {
		if err := u.download(ctx, p.idx); err != nil && ctx.Err() == nil {
			u.fail(p.target, err)
		}
		return // its swap comes at a later poll
	}
	// A fetch that failed waits for its retry, as the daily check does.
	if !swapNow(now, p, u.d.lastRequestTime(), u.awake) || (u.st.CheckError != "" && now.Before(u.st.NextCheck)) {
		return
	}
	data, sig, idx, err := u.check(ctx)
	if err != nil {
		return
	}
	if target, _, _ := updateTarget(u.v, idx, u.st.Seen); target != p.target {
		log.Printf("updates: the index no longer names %s, so it isn't installed", p.target)
		return
	}
	// The fetch can take a minute: updates may have gone off meanwhile.
	if updatesOff(u.d.stateDir) {
		return
	}
	u.apply(pendingSwap{Version: p.target, Next: u.next, Index: data, Sig: sig, Namespace: nsLatest})
}

// download fetches idx's latest release and takes its binary out into bin/ovenlight.next.
func (u *updater) download(ctx context.Context, idx *updateIndex) error {
	u.ready = ""
	bin := filepath.Dir(installedBinary(u.d.stateDir))
	archive, err := downloadArchive(ctx, u.base, bin, idx)
	if err != nil {
		return err
	}
	defer os.Remove(archive)
	next, err := extractBinary(archive, bin, idx.Latest)
	if err != nil {
		return err
	}
	log.Printf("updates: downloaded %s", idx.Latest)
	u.ready, u.next = idx.Latest, next
	return nil
}

// apply hands the swap to swapIn, and records its failure.
func (u *updater) apply(p pendingSwap) {
	u.ready = ""
	log.Printf("updates: installing %s", p.Version)
	if err := u.swap(p); err != nil {
		u.fail(p.Version, err)
	}
}

// fail records a failed update of version.
func (u *updater) fail(version string, err error) {
	log.Printf("updates: %s failed: %v", version, err)
	u.st.noteFailure(version, u.now(), err)
	u.save()
}

// noteFailure records a failed update of version at at, counting it with the failures of
// that version before it.
func (st *updateState) noteFailure(version string, at time.Time, err error) {
	f := &updateFailure{Version: version, At: at, Error: err.Error(), Count: 1}
	if st.Failed != nil && st.Failed.Version == version {
		f.Count = st.Failed.Count + 1
	}
	st.Failed = f
}

func (u *updater) save() {
	if err := jsonfile.Save(filepath.Join(u.d.stateDir, updateStateFile), &u.st); err != nil {
		log.Printf("updates: saving %s: %v", updateStateFile, err)
	}
}

// updateNowWait bounds an update now request: its download, and the swap.
const updateNowWait = downloadTimeout + 5*time.Minute

// updateNow applies the release a canary index names, at once: every check but the
// serial, the wait and the quiet window, and no fetch of the published index before the
// swap, which never names a release before it's promoted. It applies once and stores
// nothing: the updater never lowers a secure it holds, so a canary index it kept would
// leave the computer unable to take a pull.
func (u *updater) updateNow(ctx context.Context, data, sig []byte) (string, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if updatesOff(u.d.stateDir) {
		return "", errors.New("updates are off: ovenlight update on turns them back on")
	}
	if u.d.exitBegun.Load() {
		return "", errors.New("the connector is restarting on a new release")
	}
	if u.d.onTrial() {
		return "", fmt.Errorf("%s is still on its trial, which ends %s after it started: try again then", u.v, quietFor)
	}
	// Every check but the serial: a canary index can't lower the held index's secure or
	// reinstallBelow either.
	idx, _, err := readAgainst(u.st.Index, data, sig, nsCanary)
	if err != nil {
		return "", fmt.Errorf("the connector doesn't take this index: %w", err)
	}
	target, _, script := updateTarget(u.v, idx, u.now())
	switch {
	case script:
		return "", fmt.Errorf("%s needs its install script: the connector, %s, is below %s, the oldest an update can replace by binary alone. Install %s with the verified steps in %s",
			idx.Latest, u.v, idx.ReinstallBelow, idx.Latest, installDocs)
	case target == "":
		return "", fmt.Errorf("the index names %s, and the connector runs %s: it never installs the same or an older release", idx.Latest, u.v)
	}
	now := u.now()
	if up := now.Sub(u.noteAwake(now)); up < quietFor {
		return "", fmt.Errorf("the connector has been up and awake for %s, and swaps only after %s, once its apps are serving: try again in %s",
			up.Round(time.Second), quietFor, (quietFor - up).Round(time.Second))
	}
	if err := u.download(ctx, idx); err != nil {
		return "", fmt.Errorf("downloading %s: %w", idx.Latest, err)
	}
	if updatesOff(u.d.stateDir) {
		return "", errors.New("updates were turned off: ovenlight update on turns them back on")
	}
	u.ready = "" // the loop downloads its own target again
	log.Printf("updates: installing %s, as update now --index asked", target)
	if err := u.swap(pendingSwap{Version: target, Next: u.next, Index: data, Sig: sig, Namespace: nsCanary}); err != nil {
		return "", fmt.Errorf("installing %s failed: %w", target, err)
	}
	return fmt.Sprintf("Installing %s: the connector restarts on it.", target), nil
}

// updatePlan is what the updater does with the index held, for a connector at a version:
// the release it installs (target), from when (after) at a quiet moment, and by when
// (by) busy or not. script is set when the latest needs its install script.
type updatePlan struct {
	idx       *updateIndex
	target    string
	after, by time.Time
	night     time.Duration // when this computer's night starts (nightOf)
	security  bool          // the running version is below secure
	script    bool
}

// planUpdate is the update st's index names for version v, as updateTarget times it. One
// still waiting a day after it's due (a security fix, from when it's due) goes at the
// start of the next night, or as soon as the connector runs after that. A version that failed is tried again the next night (the
// first start of one at least an hour after it failed), busy or not.
func planUpdate(st *updateState, v string) updatePlan {
	idx, err := parseIndex(st.Index)
	if err != nil {
		return updatePlan{}
	}
	cur, ok := semver(v)
	p := updatePlan{idx: idx, night: nightOf(st.Minute), security: ok && less(cur, idx.secure)}
	var due time.Time
	if p.target, due, p.script = updateTarget(v, idx, st.Seen); p.target == "" {
		return p
	}
	p.after, p.by = due, nextAt(due.Add(updateDay), p.night)
	if p.security {
		p.by = nextAt(due, p.night)
	}
	// A retry that comes later moves both: a computer that's never quiet takes it that
	// night too.
	if f := st.Failed; f != nil && f.Version == p.target {
		retry := nextAt(f.At.Add(updateHour), p.night)
		if retry.After(p.after) {
			p.after = retry
		}
		if retry.After(p.by) {
			p.by = retry
		}
	}
	return p
}

// swapNow reports whether p's swap may happen now, given the last request through an app
// node, the admin API or the control socket, and since when the connector has been up and
// awake: never in its first quietFor, then at a quiet moment in the night, at any quiet
// moment for a security fix, and from p.by on busy or not.
func swapNow(now time.Time, p updatePlan, lastRequest, awake time.Time) bool {
	switch {
	case now.Before(p.after) || now.Sub(awake) < quietFor:
		return false
	case !now.Before(p.by):
		return true
	}
	return now.Sub(lastRequest) >= quietFor && (p.security || p.inNight(now))
}

// inNight reports whether t is in this computer's night, from p.night to nightEnd.
func (p updatePlan) inNight(t time.Time) bool {
	d := dayTime(t)
	return d >= p.night && d < nightEnd
}

// nextAt is the first time from t on whose time of day, on dayTime's clock, is at. On a
// night the clocks change it can be an hour off. The time zone is the one the connector
// started in: a change of zone takes effect when it next starts.
func nextAt(t time.Time, at time.Duration) time.Time {
	wait := (at - dayTime(t) + 24*time.Hour) % (24 * time.Hour)
	return t.Add(wait / (time.Hour / updateHour))
}

// updateView is the updater as status, the MCP status tool and doctor show it.
type updateView struct {
	// SelfUpdating is set when the running connector checks daily and installs updates: a
	// release build started as the installed service.
	SelfUpdating bool `json:"selfUpdating"`
	// Off is set by ovenlight update off: the connector still checks, and installs nothing.
	Off        bool      `json:"off"`
	LastCheck  time.Time `json:"lastCheck,omitzero"` // the last verified check
	CheckError string    `json:"checkError,omitempty"`
	// Latest and Secure are as the index held names them.
	Latest string `json:"latest,omitempty"`
	Secure string `json:"secure,omitempty"`
	// Pending is the release it installs on its own: from InstallAfter at a quiet moment,
	// and by InstallBy busy or not.
	Pending      string    `json:"pending,omitempty"`
	InstallAfter time.Time `json:"installAfter,omitzero"`
	InstallBy    time.Time `json:"installBy,omitzero"`
	// NeedsScript is set when Latest needs its install script: an update replaces only the
	// binary.
	NeedsScript bool           `json:"needsInstallScript,omitempty"`
	LastUpdate  *updateEvent   `json:"lastUpdate,omitempty"`
	Failure     *updateFailure `json:"failure,omitempty"` // a failed update to the release it installs next
	// BelowSecure is set when the running release has a security problem: it's below Secure.
	BelowSecure bool `json:"belowSecure,omitempty"`

	since time.Time // when the updater first ran here
}

// when is a time as status and doctor show it.
func when(t time.Time) string { return t.Local().Format("2 Jan 15:04") }

// installs says when Pending installs on its own, as status and doctor put it.
func (u *updateView) installs(now time.Time) string {
	if !now.Before(u.InstallBy) {
		return "installs on its own as soon as it can"
	}
	return fmt.Sprintf("installs on its own at a quiet moment from %s, and by %s even if busy", when(u.InstallAfter), when(u.InstallBy))
}

// daemonUpdate is readUpdateView for the connector that sent reply, and its version: with
// none running, for this binary.
func daemonUpdate(stateDir string, reply *controlReply) (*updateView, string) {
	v, self := version(), false
	if reply != nil {
		v, self = reply.Version, reply.SelfUpdating
	}
	return readUpdateView(stateDir, v, self), v
}

// readUpdateView describes the updater for a connector at version v from update.json and
// updates-off, fetching nothing.
func readUpdateView(stateDir, v string, self bool) *updateView {
	u := &updateView{SelfUpdating: self, Off: updatesOff(stateDir)}
	st, err := loadUpdateState(stateDir)
	if err != nil {
		u.CheckError = err.Error()
		return u
	}
	u.LastCheck, u.CheckError, u.LastUpdate, u.since = st.Checked, st.CheckError, st.Updated, st.Since
	p := planUpdate(st, v)
	if p.idx == nil {
		return u
	}
	u.Latest, u.Secure, u.NeedsScript, u.BelowSecure = p.idx.Latest, p.idx.Secure, p.script, p.security
	if p.target != "" {
		u.Pending, u.InstallAfter, u.InstallBy = p.target, p.after, p.by
		// Only the release it would install: one the index no longer names was pulled.
		if f := st.Failed; f != nil && f.Version == p.target {
			u.Failure = f
		}
	}
	return u
}
