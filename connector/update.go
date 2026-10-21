package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// releaseBase is where releases come from: each in its version's folder, beside the
// signed index, latest.json. Only a test-key build fetches updates from elsewhere
// (updateBase).
const releaseBase = "https://downloads.ovenlight.app/connector"

// installDocs has the verified install steps, for a release that needs its install script
// and for an owner who installs one by hand.
const installDocs = "https://github.com/snowy-ghost/ovenlight/blob/master/docs/connector.md#update"

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

// updateClient fetches updates. It follows no redirect, which could lead off the release
// host.
var updateClient = &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

// downloadTimeout bounds an archive's download: the cap at 1 Mbit/s takes 18 minutes.
const downloadTimeout = 30 * time.Minute

// fetchIndex fetches the published index and its signature from base, within a minute.
func fetchIndex(ctx context.Context, base string) (data, sig []byte, err error) {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	if data, err = fetchSmall(ctx, base+"/latest.json"); err == nil {
		sig, err = fetchSmall(ctx, base+"/latest.json.sig")
	}
	return data, sig, err
}

// fetchSmall reads a file of at most maxIndexBytes.
func fetchSmall(ctx context.Context, url string) ([]byte, error) {
	resp, err := get(ctx, url, maxIndexBytes)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, maxIndexBytes+1))
	if err == nil && len(b) > maxIndexBytes {
		err = errors.New(url + " is over 4 KB")
	}
	return b, err
}

// get fetches url, refusing anything but a 200 and a stated length over max.
func get(ctx context.Context, url string, max int64) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := updateClient.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK || resp.ContentLength > max {
		resp.Body.Close()
		return nil, fmt.Errorf("%s: %s, %d bytes", url, resp.Status, resp.ContentLength)
	}
	return resp, nil
}

// downloadArchive fetches this system's archive of idx's latest release, from its
// version's folder under base, into bin/ovenlight.download, hashing as it streams, within
// downloadTimeout and maxArchiveBytes. The file is always new, with any old one deleted
// first, and it's kept only when its SHA-256 is the one idx lists.
func downloadArchive(ctx context.Context, base, bin string, idx *updateIndex) (string, error) {
	path := filepath.Join(bin, "ovenlight.download")
	f, err := createNew(path, 0o600)
	if err != nil {
		return "", err
	}
	name := archiveName()
	want, ok := idx.Archives[name]
	if !ok {
		f.Close()
		os.Remove(path)
		return "", errors.New("the index lists no " + name)
	}
	ctx, cancel := context.WithTimeout(ctx, downloadTimeout)
	defer cancel()
	h := sha256.New()
	var n int64
	resp, err := get(ctx, base+"/"+idx.Latest+"/"+name, maxArchiveBytes)
	if err == nil {
		n, err = io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, maxArchiveBytes+1))
		resp.Body.Close()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	switch {
	case err != nil:
	case n > maxArchiveBytes:
		err = errors.New(name + " is over the size cap")
	case hex.EncodeToString(h.Sum(nil)) != want:
		err = errors.New(name + " doesn't match the index")
	}
	if err != nil {
		os.Remove(path)
		return "", err
	}
	return path, nil
}

// extractBinary takes the connector out of a downloaded archive of version, by its exact
// name, ovenlight-connector-<version>/ovenlight (ovenlight.exe on Windows), into
// bin/ovenlight.next (ovenlight.next.exe).
func extractBinary(archive, bin, version string) (string, error) {
	exe, next := "ovenlight", "ovenlight.next"
	if runtime.GOOS == "windows" {
		exe, next = "ovenlight.exe", "ovenlight.next.exe"
	}
	out := filepath.Join(bin, next)
	if err := extractEntry(archive, strings.HasSuffix(archiveName(), ".zip"), "ovenlight-connector-"+version+"/"+exe, out); err != nil {
		return "", err
	}
	return out, nil
}

// extractEntry copies the entry named name out of a .zip or .tar.gz archive into out, a
// new file with any old one deleted first, synced to disk. Nothing else in the archive is
// written anywhere, and an archive with that entry missing, twice, over maxArchiveBytes,
// or as anything but a regular file is refused.
func extractEntry(archive string, zipped bool, name, out string) (err error) {
	f, err := createNew(out, 0o755)
	if err != nil {
		return err
	}
	defer func() {
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			os.Remove(out)
		}
	}()
	found := false
	take := func(r io.Reader, size int64, regular bool) error {
		if found || !regular || size > maxArchiveBytes {
			return errors.New("the archive's " + name + " isn't one file within the size cap")
		}
		found = true
		n, err := io.Copy(f, io.LimitReader(r, maxArchiveBytes+1))
		if err == nil && n > maxArchiveBytes {
			err = errors.New("the archive's " + name + " is over the size cap")
		}
		return err
	}
	if zipped {
		z, err := zip.OpenReader(archive)
		if err != nil {
			return err
		}
		defer z.Close()
		for _, e := range z.File {
			if e.Name != name {
				continue
			}
			r, err := e.Open()
			if err != nil {
				return err
			}
			err = take(r, int64(min(e.UncompressedSize64, maxArchiveBytes+1)), e.Mode().IsRegular())
			r.Close()
			if err != nil {
				return err
			}
		}
	} else {
		a, err := os.Open(archive)
		if err != nil {
			return err
		}
		defer a.Close()
		gz, err := gzip.NewReader(a)
		if err != nil {
			return err
		}
		// The binary and a few small files: a stream far longer is no release.
		tr := tar.NewReader(io.LimitReader(gz, 2*maxArchiveBytes))
		for {
			h, err := tr.Next()
			if err == io.EOF {
				break
			} else if err != nil {
				return err
			}
			if h.Name == name {
				if err := take(tr, h.Size, h.Typeflag == tar.TypeReg); err != nil {
					return err
				}
			}
		}
	}
	if !found {
		return errors.New("the archive has no " + name)
	}
	return f.Sync()
}

// createNew creates path as a new file, deleting any old one first, so nothing written
// earlier, or still open or running, is written into.
func createNew(path string, perm os.FileMode) (*os.File, error) {
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	return os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, perm)
}
