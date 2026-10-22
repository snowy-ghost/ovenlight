package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// signIndex signs data in namespace with the test release key, as ssh-keygen -Y sign does.
func signIndex(t *testing.T, data []byte, namespace string) []byte {
	t.Helper()
	key, err := os.ReadFile("testdata/releasekey/test-key")
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.ParsePrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha512.Sum512(data)
	sig, err := signer.Sign(rand.Reader, append([]byte("SSHSIG"), ssh.Marshal(struct {
		Namespace string
		Reserved  []byte
		Hash      string
		Digest    []byte
	}{namespace, nil, "sha512", digest[:]})...))
	if err != nil {
		t.Fatal(err)
	}
	blob := ssh.Marshal(struct {
		Magic     [6]byte
		Version   uint32
		PublicKey []byte
		Namespace string
		Reserved  []byte
		Hash      string
		Signature []byte
	}{[6]byte([]byte("SSHSIG")), 1, signer.PublicKey().Marshal(), namespace, nil, "sha512", ssh.Marshal(sig)})
	return pem.EncodeToMemory(&pem.Block{Type: "SSH SIGNATURE", Bytes: blob})
}

// releaseServer stands in for downloads.ovenlight.app: the published index, its
// signature, and an archive for each release, counting what it serves.
type releaseServer struct {
	*httptest.Server
	t        *testing.T
	mu       sync.Mutex
	index    []byte
	sig      []byte
	archives map[string][]byte // by path
	down     bool              // answer everything with a 503
	hits     map[string]int
	onIndex  func() // runs as the index is served
}

func newReleaseServer(t *testing.T) *releaseServer {
	useTestKey(t)
	s := &releaseServer{t: t, archives: map[string][]byte{}, hits: map[string]int{}}
	s.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.hits[r.URL.Path]++
		if r.URL.Path == "/latest.json" && s.onIndex != nil {
			s.onIndex()
		}
		body, ok := map[string][]byte{"/latest.json": s.index, "/latest.json.sig": s.sig}[r.URL.Path]
		if !ok {
			body, ok = s.archives[r.URL.Path]
		}
		switch {
		case s.down:
			http.Error(w, "down", http.StatusServiceUnavailable)
		case !ok:
			http.NotFound(w, r)
		default:
			w.Write(body)
		}
	}))
	t.Cleanup(s.Close)
	return s
}

// publish puts up an index naming latest, with an archive of it, signed in nsLatest.
func (s *releaseServer) publish(latest, secure, below string, serial int64) []byte {
	sum := s.release(latest)
	data := testIndex(latest, secure, below, serial, sum)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.index, s.sig = data, signIndex(s.t, data, nsLatest)
	return data
}

// release puts up this system's archive of version, and returns its SHA-256.
func (s *releaseServer) release(version string) string {
	exe := "ovenlight"
	if runtime.GOOS == "windows" {
		exe += ".exe"
	}
	path := writeArchive(s.t, s.t.TempDir(), strings.HasSuffix(archiveName(), ".zip"),
		entry{name: "ovenlight-connector-" + version + "/" + exe, body: "connector " + version})
	body, err := os.ReadFile(path)
	if err != nil {
		s.t.Fatal(err)
	}
	sum := sha256.Sum256(body)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.archives["/"+version+"/"+archiveName()] = body
	return hex.EncodeToString(sum[:])
}

func (s *releaseServer) count(path string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.hits[path]
}

func (s *releaseServer) setDown(down bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.down = down
}

type fakeClock struct{ t time.Time }

func (c *fakeClock) now() time.Time { return c.t }

// at is a local time on 10 June 2026 plus day days, far from a change of the clocks.
func at(day, hour, minute int) time.Time {
	return time.Date(2026, 6, 10+day, hour, minute, 0, 0, time.Local)
}

// testUpdater is an updater of a connector at version v, started at start, fetching from
// srv, whose swaps are recorded (or, with realSwap, go to swapIn).
type testUpdater struct {
	*updater
	clock *fakeClock
	swaps []pendingSwap
}

func newTestUpdater(t *testing.T, srv *releaseServer, dir, v string, start time.Time, realSwap bool) *testUpdater {
	t.Helper()
	if updateHour != time.Hour {
		t.Skip("a compressed schedule")
	}
	st, err := loadUpdateState(dir)
	if err != nil {
		t.Fatal(err)
	}
	d := &daemon{stateDir: dir}
	tu := &testUpdater{clock: &fakeClock{start}}
	tu.updater = &updater{d: d, base: srv.URL, v: v, now: tu.clock.now, st: *st, swap: d.swapIn}
	if !realSwap {
		tu.swap = func(p pendingSwap) error { tu.swaps = append(tu.swaps, p); return nil }
	}
	os.MkdirAll(tu.bin(), 0o700)
	tu.start()
	return tu
}

// bin is the connector's bin folder.
func (tu *testUpdater) bin() string { return filepath.Join(tu.d.stateDir, "bin") }

// pass moves the clock on by d, awake all along, with the updater looking every minute.
func (tu *testUpdater) pass(d time.Duration) {
	for end := tu.clock.t.Add(d); tu.clock.t.Before(end); {
		if tu.clock.t = tu.clock.t.Add(updateMinute); tu.clock.t.After(end) {
			tu.clock.t = end
		}
		tu.mu.Lock()
		tu.noteAwake(tu.clock.t)
		tu.mu.Unlock()
	}
}

// sleepUntil moves the clock on to t with the computer asleep, then polls.
func (tu *testUpdater) sleepUntil(t time.Time) {
	tu.clock.t = t
	tu.poll(context.Background())
}

func (tu *testUpdater) pollAt(t time.Time) {
	tu.pass(t.Sub(tu.clock.t))
	tu.poll(context.Background())
}

func TestSelfUpdating(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	other := filepath.Join(t.TempDir(), filepath.Base(exe))
	b, _ := os.ReadFile(exe)
	os.WriteFile(other, b, 0o755)
	same := filepath.Join(filepath.Dir(exe), ".", filepath.Base(exe))
	for _, c := range []struct {
		v         string
		service   bool
		installed string
		want      bool
	}{
		{"1.2.0", true, exe, true},
		{"1.2.0", true, same, true},
		{"1.2.0", false, exe, false}, // a foreground run
		{"", true, exe, false},       // a development build
		{"devel-9cd108a0f732", true, exe, false},
		{"1.2.0-rc1", true, exe, false}, // a pre-release
		{"1.2.0", true, other, false},   // another copy
		{"1.2.0", true, other + ".gone", false},
	} {
		if got := selfUpdating(c.v, c.service, exe, c.installed); got != c.want {
			t.Errorf("%+v: %v", c, got)
		}
	}
	if runtime.GOOS != "windows" {
		link := filepath.Join(t.TempDir(), "home")
		if err := os.Symlink(filepath.Dir(exe), link); err != nil {
			t.Fatal(err)
		}
		if !selfUpdating("1.2.0", true, exe, filepath.Join(link, filepath.Base(exe))) {
			t.Error("a symlinked folder")
		}
	}
}

// setIndex publishes data with sig as it is.
func (s *releaseServer) setIndex(data, sig []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.index, s.sig = data, sig
}

// The daily check: at once on a version's first run, then each day at its minute, again
// within the hour after a failure, and with updates off too.
func TestUpdaterDailyCheck(t *testing.T) {
	srv := newReleaseServer(t)
	held := srv.publish("1.1.5", "1.1.0", "1.1.0", 100)
	dir := t.TempDir()
	tu := newTestUpdater(t, srv, dir, "1.1.5", at(0, 9, 0), false)
	tu.st.Minute = 9*60 + 30
	os.WriteFile(filepath.Join(dir, updatesOffFile), nil, 0o600)
	tu.pollAt(at(0, 9, 0))
	if n := srv.count("/latest.json"); n != 1 || !bytes.Equal(tu.st.Index, held) || !tu.st.Checked.Equal(at(0, 9, 0)) ||
		tu.st.CheckedBy != "1.1.5" || !tu.st.NextCheck.Equal(at(0, 9, 30)) {
		t.Fatalf("first run: %d fetches, %+v", n, tu.st)
	}
	tu.pollAt(at(0, 9, 29))
	tu.pollAt(at(0, 9, 30))
	if n := srv.count("/latest.json"); n != 2 || !tu.st.NextCheck.Equal(at(1, 9, 30)) {
		t.Errorf("at its minute: %d fetches, next %v", n, tu.st.NextCheck)
	}
	if st, err := loadUpdateState(dir); err != nil || !bytes.Equal(st.Index, held) || !st.NextCheck.Equal(at(1, 9, 30)) {
		t.Errorf("update.json: %+v %v", st, err)
	}

	srv.setDown(true)
	tu.pollAt(at(1, 9, 30))
	if tu.st.CheckError == "" || !bytes.Equal(tu.st.Index, held) || !tu.st.Checked.Equal(at(0, 9, 30)) ||
		tu.st.NextCheck.Before(at(1, 9, 31)) || tu.st.NextCheck.After(at(1, 10, 30)) {
		t.Fatalf("a failed check: %+v", tu.st)
	}
	srv.setDown(false)
	n := srv.count("/latest.json")
	tu.pollAt(at(1, 9, 30).Add(30 * time.Second))
	if srv.count("/latest.json") != n {
		t.Error("a failed check was tried again at once")
	}
	tu.pollAt(tu.st.NextCheck)
	if srv.count("/latest.json") != n+1 || tu.st.CheckError != "" || !tu.st.NextCheck.Equal(at(2, 9, 30)) {
		t.Errorf("the retry: %+v", tu.st)
	}

	// A clock set back doesn't hold off the next check for days.
	tu.st.NextCheck = tu.clock.t.Add(3 * updateDay)
	tu.pollAt(tu.clock.t.Add(time.Minute))
	if srv.count("/latest.json") != n+2 {
		t.Error("a clock set back held off the check")
	}

	// A new version checks as it starts; the same one again doesn't.
	if again := newTestUpdater(t, srv, dir, "1.1.5", tu.clock.t, false); again.st.NextCheck.IsZero() {
		t.Error("a restart checked at once")
	}
	if next := newTestUpdater(t, srv, dir, "1.1.6", tu.clock.t, false); !next.st.NextCheck.IsZero() {
		t.Error("a new version didn't check at once")
	}
}

// The index held: replaced only by a higher serial, and refused otherwise.
func TestUpdaterHoldsTheIndex(t *testing.T) {
	srv := newReleaseServer(t)
	first := srv.publish("1.2.0", "1.1.0", "1.1.0", 100)
	tu := newTestUpdater(t, srv, t.TempDir(), "1.2.0", at(0, 9, 0), false)
	check := func(hour int) {
		tu.st.NextCheck = time.Time{}
		tu.pollAt(at(0, hour, 0))
	}
	check(9)
	check(10) // the same bytes again: a verified check
	if !bytes.Equal(tu.st.Index, first) || !tu.st.Checked.Equal(at(0, 10, 0)) || !tu.st.Seen.Equal(at(0, 9, 0)) {
		t.Errorf("the same index: %+v", tu.st)
	}
	second := srv.publish("1.2.0", "1.1.0", "1.1.0", 101)
	check(11)
	if !bytes.Equal(tu.st.Index, second) || !tu.st.Seen.Equal(at(0, 9, 0)) {
		t.Errorf("a higher serial for the same release: %+v", tu.st)
	}
	third := srv.publish("1.2.1", "1.1.0", "1.1.0", 102)
	check(12)
	if !bytes.Equal(tu.st.Index, third) || !tu.st.Seen.Equal(at(0, 12, 0)) {
		t.Errorf("a new release: %+v", tu.st)
	}
	lower := testIndex("1.2.2", "1.1.0", "1.1.0", 101, "")
	lowerSecure := testIndex("1.2.2", "1.0.9", "1.1.0", 103, "")
	newer := testIndex("1.2.2", "1.1.0", "1.1.0", 103, "")
	for hour, c := range map[int]struct{ data, sig []byte }{
		13: {lower, signIndex(t, lower, nsLatest)},
		14: {lowerSecure, signIndex(t, lowerSecure, nsLatest)},
		15: {newer, signIndex(t, newer, nsCanary)},
		16: {newer, nil},
	} {
		srv.setIndex(c.data, c.sig)
		check(hour)
		if !bytes.Equal(tu.st.Index, third) || tu.st.CheckError == "" || !tu.st.Checked.Equal(at(0, 12, 0)) {
			t.Errorf("%s: held %s, %+v", c.data, tu.st.Index, tu.st)
		}
	}
}

// Only the release the index names is a target: two days after it was first seen, at
// once below secure, never below reinstallBelow, never a downgrade, and nothing
// downloads while updates are off.
func TestUpdaterWaitsTwoDays(t *testing.T) {
	srv := newReleaseServer(t)
	srv.publish("1.2.0", "1.1.0", "1.0.6", 100)
	archive := "/1.2.0/" + archiveName()
	for v, want := range map[string]struct {
		download bool
		script   bool
	}{
		"1.0.9": {download: true}, // below secure
		"1.1.5": {},
		"1.2.0": {},
		"1.3.0": {},
		"1.0.5": {script: true},
	} {
		dir := t.TempDir()
		tu := newTestUpdater(t, srv, dir, v, at(0, 9, 0), false)
		before := srv.count(archive)
		tu.pollAt(at(0, 9, 0))
		tu.pollAt(at(1, 9, 0))
		if got := srv.count(archive) > before; got != want.download {
			t.Errorf("%s: downloaded %v", v, got)
		}
		if u := readUpdateView(dir, v, true); u.NeedsScript != want.script || (u.Pending != "") != (v == "1.0.9" || v == "1.1.5") ||
			u.BelowSecure != (v == "1.0.9" || v == "1.0.5") {
			t.Errorf("%s: %+v", v, u)
		}
	}

	dir := t.TempDir()
	tu := newTestUpdater(t, srv, dir, "1.1.5", at(0, 9, 0), false)
	tu.st.Minute = 10 * 60 // its night starts at 02:00
	tu.pollAt(at(0, 9, 0))
	n := srv.count(archive)
	tu.pollAt(at(2, 8, 59))
	if srv.count(archive) != n || tu.ready != "" {
		t.Fatal("downloaded before the two days")
	}
	off := filepath.Join(dir, updatesOffFile)
	os.WriteFile(off, nil, 0o600)
	tu.pollAt(at(2, 9, 0))
	if srv.count(archive) != n {
		t.Error("downloaded with updates off")
	}
	os.Remove(off)
	tu.pollAt(at(2, 9, 1))
	exe := "ovenlight.next"
	if runtime.GOOS == "windows" {
		exe = "ovenlight.next.exe"
	}
	b, _ := os.ReadFile(filepath.Join(tu.bin(), exe))
	if srv.count(archive) != n+1 || tu.ready != "1.2.0" || string(b) != "connector 1.2.0" {
		t.Fatalf("at two days: ready %q, %q", tu.ready, b)
	}
	if _, err := os.Stat(filepath.Join(tu.bin(), "ovenlight.download")); err == nil {
		t.Error("the download was kept")
	}
	u := readUpdateView(dir, "1.1.5", true) // from the night after it's due
	if u.Pending != "1.2.0" || !u.InstallAfter.Equal(at(3, 2, 0)) || !u.InstallBy.Equal(at(4, 2, 0)) {
		t.Errorf("%+v", u)
	}
}

func TestSwapNow(t *testing.T) {
	if updateHour != time.Hour {
		t.Skip("a compressed schedule")
	}
	due := at(0, 9, 0)
	p := updatePlan{target: "1.2.0", after: due, by: nextAt(due.Add(updateDay), nightStart), night: nightStart}
	sec := p
	sec.security = true
	later := p // the night of a computer whose daily check is at 09:00
	later.night = nightOf(9 * 60)
	long := at(-1, 0, 0) // awake, or the last request, long ago
	for _, c := range []struct {
		name             string
		p                updatePlan
		now, last, awake time.Time
		want             bool
	}{
		{"before it's due", sec, at(0, 8, 59), long, long, false},
		{"a quiet day", p, at(0, 14, 0), long, long, false},
		{"a quiet night", p, at(1, 2, 0), long, long, true},
		{"a quiet night, late", p, at(1, 4, 59), long, long, true},
		{"after the night", p, at(1, 5, 0), long, long, false},
		{"a request 9 minutes ago", p, at(1, 2, 30), at(1, 2, 21), long, false},
		{"a request 10 minutes ago", p, at(1, 2, 30), at(1, 2, 20), long, true},
		{"awake 9 minutes", p, at(1, 2, 30), long, at(1, 2, 21), false},
		{"a security fix on a quiet day", sec, at(0, 14, 0), long, long, true},
		{"a security fix on a busy day", sec, at(0, 14, 0), at(0, 13, 55), long, false},
		{"a day late, busy, before 02:00", p, at(1, 23, 0), at(1, 23, 0), long, false},
		{"a day late, busy, at 02:00", p, at(2, 2, 0), at(2, 2, 0), long, true},
		{"a day late, started after 02:00", p, at(2, 9, 10), at(2, 9, 10), at(2, 9, 0), true},
		{"a day late, started 5 minutes ago", p, at(2, 9, 5), at(2, 9, 5), at(2, 9, 0), false},
		{"before a later night", later, at(1, 2, 59), long, long, false},
		{"a later night", later, at(1, 3, 0), long, long, true},
		{"a later night, late", later, at(1, 4, 59), long, long, true},
		{"after a later night", later, at(1, 5, 0), long, long, false},
	} {
		if got := swapNow(c.now, c.p, c.last, c.awake); got != c.want {
			t.Errorf("%s: %v", c.name, got)
		}
	}
}

// readyAt is a test updater at 1.1.5 whose update to 1.2.0 downloaded on day 2 at 09:00.
func readyAt(t *testing.T, realSwap bool) (*releaseServer, *testUpdater, []byte) {
	srv := newReleaseServer(t)
	data := srv.publish("1.2.0", "1.1.0", "1.1.0", 100)
	tu := newTestUpdater(t, srv, t.TempDir(), "1.1.5", at(0, 9, 0), realSwap)
	tu.st.Minute = 10 * 60 // its night starts at 02:00
	tu.pollAt(at(0, 9, 0))
	tu.pollAt(at(2, 9, 0))
	if tu.ready != "1.2.0" {
		t.Fatalf("not ready: %+v", tu.st)
	}
	return srv, tu, data
}

// busyAt polls at t with a request just made.
func (tu *testUpdater) busyAt(t time.Time) {
	tu.pass(t.Sub(tu.clock.t))
	tu.d.lastRequest.Store(t.UnixNano())
	tu.poll(context.Background())
}

func TestUpdaterSwapsAtAQuietMoment(t *testing.T) {
	srv, tu, data := readyAt(t, false)
	tu.busyAt(at(2, 20, 0))
	tu.pollAt(at(3, 1, 59))
	tu.busyAt(at(3, 2, 0))
	tu.pollAt(at(3, 2, 9))
	if len(tu.swaps) != 0 {
		t.Fatal("swapped before a quiet 10 minutes in the night")
	}
	fetches := srv.count("/latest.json")
	tu.pollAt(at(3, 2, 10))
	next := filepath.Join(tu.bin(), "ovenlight.next")
	if runtime.GOOS == "windows" {
		next += ".exe"
	}
	if len(tu.swaps) != 1 {
		t.Fatal("no swap at a quiet moment")
	}
	if s := tu.swaps[0]; s.Version != "1.2.0" || s.Next != next || !bytes.Equal(s.Index, data) || !bytes.Equal(s.Sig, srv.sig) || s.Namespace != nsLatest {
		t.Errorf("%+v", s)
	}
	if srv.count("/latest.json") != fetches+1 {
		t.Error("no fetch of the index before the swap")
	}
}

// A security fix takes any quiet 10 minutes, day or night.
func TestUpdaterSecurityFixByDay(t *testing.T) {
	srv := newReleaseServer(t)
	srv.publish("1.2.0", "1.2.0", "1.1.0", 100)
	tu := newTestUpdater(t, srv, t.TempDir(), "1.1.5", at(0, 9, 0), false)
	tu.busyAt(at(0, 9, 0))
	tu.pollAt(at(0, 9, 9))
	if len(tu.swaps) != 0 {
		t.Fatal("swapped within 10 minutes of a request")
	}
	tu.pollAt(at(0, 9, 10))
	if len(tu.swaps) != 1 {
		t.Error("a security fix waited for the night")
	}
}

// A security fix on a computer that's never quiet goes at the start of the next night.
func TestUpdaterSecurityFixBusy(t *testing.T) {
	srv := newReleaseServer(t)
	srv.publish("1.2.0", "1.2.0", "1.1.0", 100)
	tu := newTestUpdater(t, srv, t.TempDir(), "1.1.5", at(0, 9, 0), false)
	tu.st.Minute = 10 * 60 // its night starts at 02:00
	tu.busyAt(at(0, 9, 0))
	if u := readUpdateView(tu.d.stateDir, "1.1.5", true); !u.InstallAfter.Equal(at(0, 9, 0)) || !u.InstallBy.Equal(at(1, 2, 0)) {
		t.Errorf("%+v", u)
	}
	tu.busyAt(at(1, 1, 59))
	if len(tu.swaps) != 0 {
		t.Fatal("swapped while busy")
	}
	tu.busyAt(at(1, 2, 0))
	if len(tu.swaps) != 1 {
		t.Error("no swap at the start of the next night")
	}
}

// A pull published while an update waits stops its swap, and a fetch that fails before
// the swap waits for its retry.
func TestUpdaterFetchBeforeTheSwap(t *testing.T) {
	srv, tu, _ := readyAt(t, false)
	srv.setDown(true)
	tu.pollAt(at(3, 2, 30))
	if len(tu.swaps) != 0 || tu.st.CheckError == "" {
		t.Fatalf("swapped without a fetch: %+v", tu.st)
	}
	srv.setDown(false)
	n := srv.count("/latest.json")
	tu.pollAt(at(3, 2, 30).Add(30 * time.Second))
	if srv.count("/latest.json") != n {
		t.Error("a failed fetch was tried again at once")
	}
	pull := srv.publish("1.1.9", "1.1.0", "1.1.0", 101)
	tu.pollAt(tu.st.NextCheck)
	if len(tu.swaps) != 0 || !bytes.Equal(tu.st.Index, pull) {
		t.Errorf("swapped to a release the index no longer names: %+v", tu.swaps)
	}
	if u := readUpdateView(tu.d.stateDir, "1.1.5", true); u.Pending != "1.1.9" || !u.InstallAfter.Equal(tu.clock.t.Add(48*time.Hour)) {
		t.Errorf("after the pull: %+v", u)
	}
}

// updates-off is read before the swap, after its fetch.
func TestUpdaterOffBeforeTheSwap(t *testing.T) {
	srv, tu, _ := readyAt(t, false)
	off := filepath.Join(tu.d.stateDir, updatesOffFile)
	srv.onIndex = func() { os.WriteFile(off, nil, 0o600) } // turned off during the fetch
	tu.pollAt(at(3, 2, 30))
	if len(tu.swaps) != 0 {
		t.Fatal("swapped with updates off")
	}
	srv.onIndex = nil
	os.Remove(off)
	tu.pollAt(at(3, 2, 31))
	if len(tu.swaps) != 1 {
		t.Error("no swap with updates back on")
	}
}

// A failed swap, of a release that doesn't run here, is recorded and tried again the next
// night.
func TestUpdaterSwapFailure(t *testing.T) {
	srv, tu, _ := readyAt(t, true)
	archive := "/1.2.0/" + archiveName()
	tu.pollAt(at(3, 2, 30))
	st, err := loadUpdateState(tu.d.stateDir)
	if err != nil || st.Failed == nil || st.Failed.Version != "1.2.0" || !st.Failed.At.Equal(at(3, 2, 30)) ||
		!strings.Contains(st.Failed.Error, "doesn't run here") || st.Failed.Count != 1 {
		t.Fatalf("%+v %v", st.Failed, err)
	}
	n := srv.count(archive)
	tu.pollAt(at(3, 4, 0))
	tu.pollAt(at(4, 1, 59))
	if srv.count(archive) != n {
		t.Error("tried again the same night")
	}
	// It's tried again the next night, busy or not: a computer that's never quiet would
	// otherwise take it only every other night.
	u := readUpdateView(tu.d.stateDir, "1.1.5", true)
	if u.Failure == nil || u.Pending != "1.2.0" || !u.InstallAfter.Equal(at(4, 2, 0)) || !u.InstallBy.Equal(at(4, 2, 0)) {
		t.Errorf("%+v", u)
	}
	tu.busyAt(at(4, 2, 0))
	tu.busyAt(at(4, 2, 1))
	if srv.count(archive) != n+1 || tu.st.Failed.Count != 2 {
		t.Errorf("the next night: %d downloads, %+v", srv.count(archive)-n, tu.st.Failed)
	}
	if u := readUpdateView(tu.d.stateDir, "1.2.0", true); u.Failure != nil {
		t.Error("a failure of the running version is shown")
	}
	// Once the index no longer names it, a pull, its failure isn't shown.
	srv.publish("1.1.9", "1.1.0", "1.1.0", 101)
	tu.st.NextCheck = time.Time{}
	tu.pollAt(at(4, 9, 0))
	if u := readUpdateView(tu.d.stateDir, "1.1.5", true); u.Failure != nil || u.Pending != "1.1.9" {
		t.Errorf("after a pull: %+v", u)
	}
}

// Times noted while the clock ran ahead don't hold the wait or a retry back.
func TestUpdaterClockRanAhead(t *testing.T) {
	_, tu, _ := readyAt(t, false)
	tu.st.Failed = &updateFailure{Version: "1.2.0", At: at(9, 2, 30), Error: "earlier", Count: 1}
	tu.pollAt(at(2, 9, 30)) // the failure is now at most, so it's tried again the next night
	tu.pollAt(at(3, 2, 30))
	if len(tu.swaps) != 1 {
		t.Errorf("a failure noted ahead held the retry back: %+v", tu.st.Failed)
	}

	srv := newReleaseServer(t)
	srv.publish("1.2.0", "1.1.0", "1.1.0", 100)
	tu = newTestUpdater(t, srv, t.TempDir(), "1.1.5", at(0, 9, 0), false)
	tu.st.Minute = 10 * 60 // its night starts at 02:00
	tu.pollAt(at(0, 9, 0))
	tu.st.Seen, tu.st.Checked, tu.st.Since = at(9, 9, 0), at(9, 9, 0), at(9, 9, 0)
	tu.d.lastRequest.Store(at(9, 9, 0).UnixNano())
	tu.pollAt(at(0, 9, 1))
	for name, got := range map[string]time.Time{"seen": tu.st.Seen, "checked": tu.st.Checked, "since": tu.st.Since, "last request": tu.d.lastRequestTime()} {
		if !got.Equal(at(0, 9, 1)) {
			t.Errorf("%s %v", name, got)
		}
	}
}

// An update.json that can't be read is kept aside, and the updater starts afresh.
func TestUpdaterUnreadableRecord(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, updateStateFile)
	writeFile(t, path, "{")
	u := newUpdater(&daemon{stateDir: dir})
	if u == nil || readFile(t, path+".bad") != "{" || u.st.Since.IsZero() {
		t.Fatal("not started afresh")
	}
	if st, err := loadUpdateState(dir); err != nil || st.Since.IsZero() {
		t.Errorf("update.json: %+v %v", st, err)
	}
}

// No swap in the first 10 minutes after a start or a wake, and an update a day late goes
// busy or not: at 02:00, or the first time the connector runs after it.
func TestUpdaterCap(t *testing.T) {
	_, tu, _ := readyAt(t, false)
	for _, t2 := range []time.Time{at(3, 2, 0), at(3, 2, 30), at(3, 9, 0), at(4, 1, 59)} {
		tu.busyAt(t2)
	}
	if len(tu.swaps) != 0 {
		t.Fatal("swapped while busy")
	}
	tu.busyAt(at(4, 2, 0))
	if len(tu.swaps) != 1 {
		t.Fatal("a day late, no swap at 02:00")
	}

	// Asleep through 02:00: on waking, 10 minutes awake first.
	_, tu, _ = readyAt(t, false)
	tu.busyAt(at(3, 23, 0))
	tu.sleepUntil(at(4, 9, 0))
	tu.busyAt(at(4, 9, 9))
	if len(tu.swaps) != 0 {
		t.Fatal("swapped within 10 minutes of waking")
	}
	tu.busyAt(at(4, 9, 10))
	if len(tu.swaps) != 1 {
		t.Fatal("no swap after waking")
	}

	// Off through 02:00, and started that morning: it downloads again, then swaps 10
	// minutes after the start.
	srv, tu, _ := readyAt(t, false)
	tu = newTestUpdater(t, srv, tu.d.stateDir, "1.1.5", at(4, 9, 0), false)
	tu.busyAt(at(4, 9, 0))
	tu.busyAt(at(4, 9, 9))
	if tu.ready != "1.2.0" || len(tu.swaps) != 0 {
		t.Fatalf("after a start: ready %q, %d swaps", tu.ready, len(tu.swaps))
	}
	tu.busyAt(at(4, 9, 10))
	if len(tu.swaps) != 1 {
		t.Error("no swap after a start")
	}
}

func TestNoteAwake(t *testing.T) {
	u := &updater{}
	start := at(0, 9, 0)
	u.awake, u.lastRan = start, start
	u.noteAwake(start.Add(updateMinute))
	if !u.noteAwake(start.Add(4 * updateMinute)).Equal(start) {
		t.Error("polls on time")
	}
	slept := start.Add(10 * updateMinute)
	if !u.noteAwake(slept).Equal(slept) {
		t.Error("a gap")
	}
	back := slept.Add(-time.Minute)
	if !u.noteAwake(back).Equal(back) {
		t.Error("a clock set back")
	}
}

// update now --index: a canary index, applied at once while the published index still
// names the running release, with no fetch of it, storing nothing.
func TestUpdateNow(t *testing.T) {
	srv := newReleaseServer(t)
	srv.publish("1.1.5", "1.1.0", "1.1.0", 100)
	tu := newTestUpdater(t, srv, t.TempDir(), "1.1.5", at(0, 9, 0), false)
	tu.pollAt(at(0, 9, 0))
	statePath := filepath.Join(tu.d.stateDir, updateStateFile)
	before, _ := os.ReadFile(statePath)
	canary := testIndex("1.2.0", "1.1.0", "1.1.0", 1, srv.release("1.2.0"))
	sig := signIndex(t, canary, nsCanary)
	ctx := context.Background()
	if _, err := tu.updateNow(ctx, canary, sig); err == nil || !strings.Contains(err.Error(), "up and awake for 10 minutes, so its apps are serving: try again in 10 minutes") {
		t.Errorf("at the start: %v", err)
	}
	tu.pass(10 * time.Minute)
	script := testIndex("1.2.0", "1.1.0", "1.2.0", 1, "")
	older := testIndex("1.1.5", "1.1.0", "1.1.0", 1, "")
	lowers := testIndex("1.2.0", "1.0.9", "1.1.0", 1, "")
	for name, c := range map[string]struct {
		data, sig []byte
		want      string
	}{
		"signed for latest.json":   {canary, signIndex(t, canary, nsLatest), "ovenlight-latest"},
		"below reinstallBelow":     {script, signIndex(t, script, nsCanary), "needs its install script"},
		"the same release":         {older, signIndex(t, older, nsCanary), "never installs the same or an older"},
		"lowering the secure held": {lowers, signIndex(t, lowers, nsCanary), "lowers secure"},
	} {
		if _, err := tu.updateNow(ctx, c.data, c.sig); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v", name, err)
		}
	}
	off := filepath.Join(tu.d.stateDir, updatesOffFile)
	os.WriteFile(off, nil, 0o600)
	if _, err := tu.updateNow(ctx, canary, sig); err == nil || !strings.Contains(err.Error(), "updates are off") {
		t.Errorf("updates off: %v", err)
	}
	os.Remove(off)

	fetches := srv.count("/latest.json")
	msg, err := tu.updateNow(ctx, canary, sig)
	if err != nil || len(tu.swaps) != 1 || !strings.Contains(msg, "1.2.0") {
		t.Fatalf("%q %v", msg, err)
	}
	if s := tu.swaps[0]; s.Version != "1.2.0" || !bytes.Equal(s.Index, canary) || !bytes.Equal(s.Sig, sig) || s.Namespace != nsCanary {
		t.Errorf("%+v", s)
	}
	tu.swap = tu.d.swapIn
	if _, err := tu.updateNow(ctx, canary, sig); err == nil || !strings.Contains(err.Error(), "doesn't run here") {
		t.Errorf("a failed swap: %v", err)
	}
	if after, _ := os.ReadFile(statePath); !bytes.Equal(after, before) || srv.count("/latest.json") != fetches {
		t.Errorf("update now stored or fetched something: %s", after)
	}
}

// Every request through an app node, the admin API or the control socket is noted, and
// the control socket takes update-now.
func TestRequestsAreNoted(t *testing.T) {
	d, _ := testDaemon(t)
	n := d.nodes["coach"]
	n.setOwner(d.cfg.Owner)
	whoIsAs(n, ownerID)
	for name, serve := range map[string]func(){
		"app": func() {
			n.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "https://coach.tail1.ts.net"+whoamiPath, nil))
		},
		"admin": func() {
			n.adminHandler().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "https://coach.tail1.ts.net:8443/v1/apps", nil))
		},
		"control": func() {
			if reply := control(t, d, controlRequest{Cmd: "update-now"}); !strings.Contains(reply.Error, "doesn't update itself") {
				t.Errorf("update-now without an updater: %+v", reply)
			}
		},
	} {
		d.lastRequest.Store(0)
		serve()
		if d.lastRequest.Load() == 0 {
			t.Errorf("%s: not noted", name)
		}
	}

	srv := newReleaseServer(t)
	tu := newTestUpdater(t, srv, d.stateDir, "1.1.5", time.Now().Add(-time.Hour), false)
	d.upd = tu.updater
	tu.pass(10 * time.Minute)
	canary := testIndex("1.2.0", "1.1.0", "1.1.0", 1, srv.release("1.2.0"))
	reply := control(t, d, controlRequest{Cmd: "update-now", Index: canary, Sig: signIndex(t, canary, nsCanary)})
	if !reply.OK || !strings.Contains(reply.Message, "1.2.0") || !reply.SelfUpdating || len(tu.swaps) != 1 {
		t.Errorf("update-now: %+v", reply)
	}
}

func control(t *testing.T, d *daemon, req controlRequest) controlReply {
	client, server := net.Pipe()
	defer client.Close()
	go d.handleControl(server)
	json.NewEncoder(client).Encode(req)
	var reply controlReply
	if err := json.NewDecoder(client).Decode(&reply); err != nil {
		t.Fatal(err)
	}
	return reply
}

func TestUpdateFinding(t *testing.T) {
	now := at(10, 12, 0)
	view := func(change func(u *updateView)) *updateView {
		u := &updateView{SelfUpdating: true, LastCheck: now.Add(-time.Hour), Latest: "1.2.0", Secure: "1.1.0", since: now.Add(-time.Hour)}
		if change != nil {
			change(u)
		}
		return u
	}
	pending := func(u *updateView) {
		u.Pending, u.InstallAfter, u.InstallBy = "1.2.0", now.Add(time.Hour), now.Add(day)
	}
	for _, c := range []struct {
		name    string
		u       *updateView
		v       string
		running bool
		status  string
		has     string
	}{
		{"up to date", view(nil), "1.2.0", true, statusOK, "1.2.0 is up to date"},
		{"waiting its turn", view(pending), "1.1.5", true, statusOK, "1.2.0 is out, and installs on its own at a quiet moment from"},
		{"past its install-by", view(func(u *updateView) { pending(u); u.InstallBy = now.Add(-time.Hour) }), "1.1.5", true, statusOK, "installs on its own as soon as it can"},
		{"no check yet", view(func(u *updateView) { u.Latest, u.LastCheck = "", time.Time{} }), "1.1.5", true, statusOK, "no update check has worked yet"},
		{"below secure, waiting", view(func(u *updateView) { pending(u); u.BelowSecure = true }), "1.0.9", true, statusFail, "Nothing to do: 1.2.0 installs on its own"},
		{"below secure, off", view(func(u *updateView) { pending(u); u.BelowSecure, u.Off = true, true }), "1.0.9", false, statusFail, "ovenlight update on"},
		{"off", view(func(u *updateView) { pending(u); u.Off = true }), "1.1.5", true, statusWarn, "updates are off"},
		{"off, 8 days without a check", view(func(u *updateView) { u.Off, u.LastCheck = true, now.Add(-8*day) }), "1.2.0", true, statusWarn, "can't tell when its release has a security problem"},
		{"needs its script", view(func(u *updateView) { u.NeedsScript = true }), "1.0.5", true, statusWarn, installDocs},
		{"needs its script, off", view(func(u *updateView) { u.NeedsScript, u.Off = true, true }), "1.0.5", true, statusWarn, installDocs},
		{"needs its script, below secure, off", view(func(u *updateView) { u.NeedsScript, u.BelowSecure, u.Off = true, true, true }), "1.0.5", true, statusFail, installDocs},
		{"below secure, waiting, not running", view(func(u *updateView) { pending(u); u.BelowSecure = true }), "1.0.9", false, statusFail, "has a security problem"},
		{"failed twice", view(func(u *updateView) {
			pending(u)
			u.Failure = &updateFailure{Version: "1.2.0", Count: 2}
		}), "1.1.5", true, statusOK, "on its own"},
		{"failed three times", view(func(u *updateView) {
			pending(u)
			u.Failure = &updateFailure{Version: "1.2.0", Count: 3, Error: "swap not implemented"}
		}), "1.1.5", true, statusWarn, "has failed 3 times in a row"},
		{"doesn't run here", view(func(u *updateView) {
			pending(u)
			u.Failure = &updateFailure{Version: "1.2.0", Count: 3, Error: "the downloaded 1.2.0 " + notRunnable + ": exec format error"}
		}), "1.1.5", true, statusWarn, "newer version of the operating system"},
		{"6 days without a check", view(func(u *updateView) { u.LastCheck = now.Add(-6 * day) }), "1.2.0", true, statusOK, "up to date"},
		{"8 days without a check", view(func(u *updateView) { u.LastCheck, u.CheckError = now.Add(-8*day), "offline" }), "1.2.0", true, statusWarn, "for 7 days: offline"},
		{"8 days without a check, after a key change", view(func(u *updateView) { u.LastCheck = now.Add(-8 * day) }), "1.2.0", true, statusWarn, "If it does, install the latest release with the verified steps in " + installDocs},
		{"never checked in 8 days", view(func(u *updateView) { u.Latest, u.LastCheck, u.since = "", time.Time{}, now.Add(-8*day) }), "1.2.0", true, statusWarn, "for 7 days"},
		{"run by hand", view(func(u *updateView) { u.SelfUpdating = false }), "1.2.0", true, statusWarn, "isn't the installed service"},
		{"not running", view(func(u *updateView) { u.SelfUpdating = false }), "1.2.0", false, statusOK, "up to date"},
	} {
		got := updateFinding(c.u, c.v, c.running, now)
		if got == nil || got.Status != c.status || !strings.Contains(got.Message+" "+got.Fix, c.has) {
			t.Errorf("%s: %+v", c.name, got)
		}
		if got != nil && strings.Contains(got.Fix, "downloads.ovenlight.app/connector/latest/") {
			t.Errorf("%s: points at an unverified download", c.name)
		}
		if c.name == "below secure, waiting, not running" && got.Fix != "" {
			t.Errorf("%s: %q, though it installs nothing while down", c.name, got.Fix)
		}
		if c.name == "doesn't run here" && strings.Contains(got.Fix, installDocs) {
			t.Errorf("%s: offers an install by hand, which fails the same way", c.name)
		}
	}
	if updateFinding(view(nil), "devel-9cd108a0f732", true, now) != nil {
		t.Error("a development build was judged")
	}
}

const day = 24 * time.Hour

func TestUpdateOffAndOn(t *testing.T) {
	state := t.TempDir()
	off := filepath.Join(state, updatesOffFile)
	if err := cmdUpdate([]string{"off", "--state", state}); err != nil || !updatesOff(state) {
		t.Fatalf("off: %v", err)
	}
	if err := cmdUpdate([]string{"on", "--state", state}); err != nil || updatesOff(state) {
		t.Fatalf("on: %v", err)
	}
	if err := cmdUpdate([]string{"on", "--state", state}); err != nil {
		t.Errorf("on again: %v", err)
	}
	if _, err := os.Stat(off); err == nil {
		t.Error("updates-off is still there")
	}
	if err := cmdUpdate([]string{"now", "--state", state}); err == nil {
		t.Error("update now without --index")
	}
}

func TestUpdateLines(t *testing.T) {
	now := at(0, 9, 0)
	u := &updateView{SelfUpdating: true, LastCheck: now, Pending: "1.2.0", InstallAfter: now, InstallBy: now.Add(day), Secure: "1.2.0", BelowSecure: true,
		LastUpdate: &updateEvent{From: "1.1.4", To: "1.1.5", At: now}, Failure: &updateFailure{Version: "1.2.0", At: now, Error: "swap not implemented", Count: 1}}
	lines := func(u *updateView, v string) string { return strings.Join(updateLines(u, v, now), "\n") }
	got := lines(u, "1.1.5")
	for _, want := range []string{"Updates: on; last checked", "SECURITY PROBLEM: 1.1.5 has one, fixed in 1.2.0.", "1.2.0 installs on its own at a quiet moment from",
		"even if busy.", "Last update: 1.1.4 to 1.1.5", "UPDATE FAILED: 1.2.0"} {
		if !strings.Contains(got, want) {
			t.Errorf("no %q in\n%s", want, got)
		}
	}
	u.InstallBy = now
	if got := lines(u, "1.1.5"); !strings.Contains(got, "1.2.0 installs on its own as soon as it can.") {
		t.Errorf("past its install-by:\n%s", got)
	}
	u.Off = true
	if got := lines(u, "1.1.5"); !strings.Contains(got, "Updates: off") || strings.Contains(got, "installs on its own") {
		t.Errorf("off:\n%s", got)
	}
	u.NeedsScript = true
	if got := lines(u, "1.1.5"); !strings.Contains(got, installDocs) {
		t.Errorf("a release that needs its script:\n%s", got)
	}
	if updateLines(u, "devel-9cd108a0f732", now) != nil {
		t.Error("a development build")
	}
}
