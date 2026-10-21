package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// latestURL names the newest connector release and the oldest without a known security
// hole: {"latest": "1.2.0", "secure": "1.1.3"}. doctor reads it; nothing else does.
var latestURL = releaseBase + "/latest.json"

// releaseBase is where releases come from: each in its version's folder, beside the
// signed index, latest.json. Only a test-key build fetches updates from elsewhere
// (updateBase).
const releaseBase = "https://downloads.ovenlight.app/connector"

// updateCheck says when a newer release is out, and fails when this one has a security
// hole fixed since. A development or pre-release build, or a file it can't read, checks
// nothing.
func updateCheck() *Check {
	v, ok := semver(releaseVersion)
	if !ok {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, latestURL, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	var latest struct{ Latest, Secure string }
	if resp.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&latest) != nil {
		return nil
	}
	newest, okN := semver(latest.Latest)
	secure, okS := semver(latest.Secure)
	if okS && (!okN || less(newest, secure)) {
		newest, okN = secure, true // never point to a release older than the secure one
	}
	fix := func(v [3]int) string {
		return fmt.Sprintf("Install %s (%s) with its install script; your apps and node state are kept", vstring(v), latestArchive())
	}
	switch {
	case okS && less(v, secure):
		return &Check{ID: "update", Status: statusFail, Actor: actorPerson,
			Message: "this connector, " + releaseVersion + ", has a security problem fixed in " + vstring(secure), Fix: fix(newest)}
	case okN && less(v, newest):
		return &Check{ID: "update", Status: statusWarn, Actor: actorPerson,
			Message: vstring(newest) + " is out (this is " + releaseVersion + ")", Fix: fix(newest)}
	}
	return &Check{ID: "update", Status: statusOK, Message: releaseVersion + " is the latest"}
}

// latestArchive is this system's archive of the latest release, which is the newest one,
// never older than the secure one: scripts/publish-connector.sh refuses otherwise.
func latestArchive() string {
	return releaseBase + "/latest/" + archiveName()
}

// archiveName is this system's release archive.
func archiveName() string {
	name := "macos.tar.gz"
	switch runtime.GOOS {
	case "linux":
		name = "linux-" + runtime.GOARCH + ".tar.gz"
	case "windows":
		name = "windows-" + runtime.GOARCH + ".zip"
	}
	return "ovenlight-connector-" + name
}

// semver reads "1.2.3", without a pre-release part, and only as vstring writes it.
func semver(s string) ([3]int, bool) {
	var v [3]int
	parts := strings.Split(s, ".")
	if len(parts) != 3 {
		return v, false
	}
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 || strconv.Itoa(n) != p {
			return v, false
		}
		v[i] = n
	}
	return v, true
}

func vstring(v [3]int) string { return fmt.Sprintf("%d.%d.%d", v[0], v[1], v[2]) }

func less(a, b [3]int) bool {
	for i := range a {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return false
}

// updateIndex is a signed index: the latest release, the oldest without a known security
// problem, the oldest that may update by binary alone, when it was signed (in seconds),
// and the SHA-256 of each of the latest release's archives. latest and secure stay
// top-level strings, so 1.0.x's doctor still reads them.
type updateIndex struct {
	Latest         string            `json:"latest"`
	Secure         string            `json:"secure"`
	ReinstallBelow string            `json:"reinstallBelow"`
	Serial         int64             `json:"serial"`
	Archives       map[string]string `json:"archives"`

	latest, secure, below [3]int
}

// maxIndexBytes caps an index and its signature. 1.0.x's doctor reads only this much.
const maxIndexBytes = 4096

// readIndex checks an index's signature in namespace, then reads it.
func readIndex(data, sig []byte, namespace string) (*updateIndex, error) {
	if len(data) > maxIndexBytes {
		return nil, errors.New("the index is over 4 KB")
	}
	if err := verifySig(data, sig, namespace); err != nil {
		return nil, err
	}
	return parseIndex(data)
}

// parseIndex reads an index whose signature was checked, now or when it was accepted:
// every version set, and the latest at least secure and reinstallBelow.
func parseIndex(data []byte) (*updateIndex, error) {
	var idx updateIndex
	if err := json.Unmarshal(data, &idx); err != nil {
		return nil, errors.New("the index can't be read")
	}
	var okL, okS, okR bool
	idx.latest, okL = semver(idx.Latest)
	idx.secure, okS = semver(idx.Secure)
	idx.below, okR = semver(idx.ReinstallBelow)
	if !okL || !okS || !okR || idx.Serial <= 0 || less(idx.latest, idx.secure) || less(idx.latest, idx.below) {
		return nil, errors.New("the index isn't complete")
	}
	return &idx, nil
}

// checkIndex reads a fetched latest.json and its signature, and judges it against the
// index held (nil before the first), which is local state and never checked again. The
// same bytes are a verified check with nothing new. A higher serial replaces the held
// index (fresh), unless it lowers secure or reinstallBelow, whatever the scripts allowed.
// Anything else is refused, and isn't a verified check.
func checkIndex(held, data, sig []byte) (idx *updateIndex, fresh bool, err error) {
	if idx, err = readIndex(data, sig, nsLatest); err != nil {
		return nil, false, err
	}
	if fresh, err = judgeIndex(held, data, idx); err != nil {
		return nil, false, err
	}
	return idx, fresh, nil
}

// judgeIndex is checkIndex's rules, for an index already read.
func judgeIndex(held, data []byte, idx *updateIndex) (fresh bool, err error) {
	if held == nil {
		return true, nil
	}
	if bytes.Equal(data, held) {
		return false, nil
	}
	h, err := parseIndex(held)
	if err != nil {
		return false, err
	}
	if idx.Serial <= h.Serial {
		return false, errors.New("the index is older than the one held")
	}
	if err := notLower(h, idx); err != nil {
		return false, err
	}
	return true, nil
}

// checkCanary reads a canary index and its signature for update now --index, with every
// check but the serial: it can't lower the held index's secure or reinstallBelow either.
// It applies once and is never held.
func checkCanary(held, data, sig []byte) (*updateIndex, error) {
	idx, err := readIndex(data, sig, nsCanary)
	if err != nil || held == nil {
		return idx, err
	}
	h, err := parseIndex(held)
	if err == nil {
		err = notLower(h, idx)
	}
	if err != nil {
		return nil, err
	}
	return idx, nil
}

// notLower refuses an index that lowers the held index's secure or reinstallBelow.
func notLower(held, idx *updateIndex) error {
	if less(idx.secure, held.secure) || less(idx.below, held.below) {
		return errors.New("the index lowers secure or reinstallBelow")
	}
	return nil
}

// updateTarget is the release idx names for a connector at version v, and when it's due:
// two days after this computer first saw it named (seen), or at once when v is below
// secure. There's none ("") when the latest isn't newer than v, so never a downgrade, and
// when v is below reinstallBelow (script): an update replaces only the binary, so that
// release needs its install script.
func updateTarget(v string, idx *updateIndex, seen time.Time) (target string, due time.Time, script bool) {
	cur, ok := semver(v)
	switch {
	case !ok || !less(cur, idx.latest):
		return "", time.Time{}, false
	case less(cur, idx.below):
		return "", time.Time{}, true
	case less(cur, idx.secure):
		return idx.Latest, seen, false
	}
	return idx.Latest, seen.Add(48 * updateHour), false
}

// firstSeen is when this computer first saw latest named, given when it first saw the
// release named before it (held at heldSeen): now for a release it hasn't seen, and when
// heldSeen is after now, since a clock set back must not hold an update for long.
func firstSeen(latest, held string, heldSeen, now time.Time) time.Time {
	if latest != held || heldSeen.After(now) {
		return now
	}
	return heldSeen
}

// dayTime is t's local time of day on the updater's clock. A test-key build may shorten
// the updater's hour, and with it the day, which still runs from 00:00 to 24:00.
func dayTime(t time.Time) time.Duration {
	h, m, s := t.Clock()
	wall := time.Duration(h)*time.Hour + time.Duration(m)*time.Minute + time.Duration(s)*time.Second + time.Duration(t.Nanosecond())
	return wall % (24 * updateHour) * (time.Hour / updateHour)
}
