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
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"
)

// testIndex is an index's JSON, with this system's archive hashed as sum.
func testIndex(latest, secure, below string, serial int64, sum string) []byte {
	b, _ := json.Marshal(map[string]any{"latest": latest, "secure": secure, "reinstallBelow": below,
		"serial": serial, "archives": map[string]string{archiveName(): sum}})
	return b
}

func mustIndex(t *testing.T, data []byte) *updateIndex {
	t.Helper()
	idx, err := parseIndex(data)
	if err != nil {
		t.Fatalf("%s: %v", data, err)
	}
	return idx
}

func TestParseIndex(t *testing.T) {
	data, err := os.ReadFile("testdata/update/index.json")
	if err != nil {
		t.Fatal(err)
	}
	idx := mustIndex(t, data)
	if idx.Latest != "1.1.1" || idx.Secure != "1.0.6" || idx.ReinstallBelow != "1.1.0" || idx.Serial != 1793000000 || len(idx.Archives) != 5 {
		t.Errorf("%+v", idx)
	}
	mustIndex(t, []byte(`{"latest": "1.1.1", "secure": "1.1.1", "reinstallBelow": "1.1.1", "serial": 1, "added": [1]}`))
	for _, bad := range []string{
		`not json`,
		`{"latest": "1.1.1", "secure": "1.0.6", "serial": 1}`,
		`{"latest": "1.1", "secure": "1.0.6", "reinstallBelow": "1.0.0", "serial": 1}`,
		`{"latest": "01.1.1", "secure": "1.0.6", "reinstallBelow": "1.0.0", "serial": 1}`,
		`{"latest": "+1.1.1", "secure": "1.0.6", "reinstallBelow": "1.0.0", "serial": 1}`,
		`{"latest": "1.1.1-rc1", "secure": "1.0.6", "reinstallBelow": "1.0.0", "serial": 1}`,
		`{"latest": "1.1.1", "secure": "1.0.6", "reinstallBelow": "1.0.0", "serial": 0}`,
		`{"latest": "1.1.1", "secure": "1.0.6", "reinstallBelow": "1.0.0"}`,
		`{"latest": "1.1.1", "secure": "1.2.0", "reinstallBelow": "1.0.0", "serial": 1}`,
		`{"latest": "1.1.1", "secure": "1.0.6", "reinstallBelow": "1.2.0", "serial": 1}`,
	} {
		if _, err := parseIndex([]byte(bad)); err == nil {
			t.Errorf("%s parsed", bad)
		}
	}
}

// useTestKey trusts only the test key until the test ends.
func useTestKey(t *testing.T) {
	pub, err := os.ReadFile("testdata/releasekey/test-key.pub")
	if err != nil {
		t.Fatal(err)
	}
	k := releaseKeys
	t.Cleanup(func() { releaseKeys = k })
	releaseKeys = mustParseKeys(string(pub))
}

func TestReadIndex(t *testing.T) {
	useTestKey(t)
	data, _ := os.ReadFile("testdata/update/index.json")
	sig, _ := os.ReadFile("testdata/update/index.json.ovenlight-latest.sig")
	if _, err := readIndex(data, sig, nsLatest); err != nil {
		t.Error(err)
	}
	if _, err := readIndex(append(data, bytes.Repeat([]byte(" "), maxIndexBytes)...), sig, nsLatest); err == nil {
		t.Error("an index over 4 KB was read")
	}
	if _, err := readIndex(data, sig, nsCanary); err == nil {
		t.Error("a published index was read as a canary")
	}

	// The first index is held. The same bytes again are a verified check with nothing new.
	if idx, fresh, err := checkIndex(nil, data, sig); err != nil || !fresh || idx.Latest != "1.1.1" {
		t.Errorf("first: %v %v", fresh, err)
	}
	if idx, fresh, err := checkIndex(data, data, sig); err != nil || fresh || idx == nil {
		t.Errorf("again: %v %v", fresh, err)
	}
	if _, _, err := checkIndex(nil, data, nil); err == nil {
		t.Error("an unsigned index was taken")
	}
}

func TestJudgeIndex(t *testing.T) {
	held := testIndex("1.2.0", "1.1.3", "1.1.0", 100, "")
	for name, c := range map[string]struct {
		data  []byte
		fresh bool
		ok    bool
	}{
		"same bytes":                   {held, false, true},
		"higher serial":                {testIndex("1.2.1", "1.1.3", "1.1.0", 101, ""), true, true},
		"higher secure":                {testIndex("1.2.1", "1.2.1", "1.2.0", 101, ""), true, true},
		"a pull, to an older latest":   {testIndex("1.1.9", "1.1.3", "1.1.0", 101, ""), true, true},
		"same serial, different bytes": {testIndex("1.2.1", "1.1.3", "1.1.0", 100, ""), false, false},
		"lower serial":                 {testIndex("1.2.1", "1.1.3", "1.1.0", 99, ""), false, false},
		"lower secure":                 {testIndex("1.2.1", "1.1.2", "1.1.0", 101, ""), false, false},
		"lower reinstallBelow":         {testIndex("1.2.1", "1.1.3", "1.0.9", 101, ""), false, false},
	} {
		fresh, err := judgeIndex(held, c.data, mustIndex(t, c.data))
		if (err == nil) != c.ok || (c.ok && fresh != c.fresh) {
			t.Errorf("%s: fresh %v, %v", name, fresh, err)
		}
	}
	if fresh, err := judgeIndex(nil, held, mustIndex(t, held)); err != nil || !fresh {
		t.Errorf("nothing held: %v %v", fresh, err)
	}
	if _, err := judgeIndex([]byte("{}"), held, mustIndex(t, held)); err == nil {
		t.Error("judged against an unreadable index")
	}
}

// A canary index takes every check but the serial, and only in its own namespace.
func TestCheckCanary(t *testing.T) {
	useTestKey(t)
	data, _ := os.ReadFile("testdata/update/index.json")
	sig, _ := os.ReadFile("testdata/update/index.json.ovenlight-canary.sig")
	for _, c := range []struct {
		held []byte
		ok   bool
	}{
		{nil, true},
		{testIndex("1.1.0", "1.0.6", "1.1.0", 1, ""), true},
		{testIndex("1.1.0", "1.0.6", "1.1.0", 2e9, ""), true}, // a higher serial
		{testIndex("1.1.0", "1.0.7", "1.1.0", 1, ""), false},
		{testIndex("1.1.1", "1.0.6", "1.1.1", 1, ""), false},
	} {
		if _, err := checkCanary(c.held, data, sig); (err == nil) != c.ok {
			t.Errorf("held %s: %v", c.held, err)
		}
	}
	latest, _ := os.ReadFile("testdata/update/index.json.ovenlight-latest.sig")
	if _, err := checkCanary(nil, data, latest); err == nil {
		t.Error("a published index was taken as a canary")
	}
}

func TestUpdateTarget(t *testing.T) {
	idx := mustIndex(t, testIndex("1.2.0", "1.1.3", "1.1.0", 1, ""))
	seen := time.Date(2026, 10, 21, 9, 0, 0, 0, time.UTC)
	for v, want := range map[string]struct {
		target string
		due    time.Time
		script bool
	}{
		"1.1.5":     {"1.2.0", seen.Add(48 * updateHour), false},
		"1.1.3":     {"1.2.0", seen.Add(48 * updateHour), false},
		"1.1.2":     {"1.2.0", seen, false}, // below secure: at once
		"1.1.0":     {"1.2.0", seen, false},
		"1.0.9":     {"", time.Time{}, true}, // below reinstallBelow
		"1.2.0":     {},
		"1.3.0":     {}, // never a downgrade
		"":          {},
		"1.2.0-rc1": {},
	} {
		target, due, script := updateTarget(v, idx, seen)
		if target != want.target || !due.Equal(want.due) || script != want.script {
			t.Errorf("%q: %q %v %v", v, target, due, script)
		}
	}
}

func TestFirstSeen(t *testing.T) {
	now := time.Date(2026, 10, 21, 9, 0, 0, 0, time.UTC)
	before, after := now.Add(-time.Hour), now.Add(time.Hour)
	for _, c := range []struct {
		latest, held string
		seen, want   time.Time
	}{
		{"1.2.0", "1.2.0", before, before},
		{"1.2.1", "1.2.0", before, now},
		{"1.2.0", "", time.Time{}, now},
		{"1.2.0", "1.2.0", after, now}, // the clock was set back
	} {
		if got := firstSeen(c.latest, c.held, c.seen, now); !got.Equal(c.want) {
			t.Errorf("%+v: %v", c, got)
		}
	}
}

func TestDayTime(t *testing.T) {
	if updateHour != time.Hour {
		t.Skip("a compressed schedule")
	}
	if d := dayTime(time.Date(2026, 10, 21, 2, 30, 15, 0, time.Local)); d != 2*time.Hour+30*time.Minute+15*time.Second {
		t.Error(d)
	}
}

func TestDownloadArchive(t *testing.T) {
	body := []byte("an archive")
	sum := sha256.Sum256(body)
	good := mustIndex(t, testIndex("1.2.0", "1.1.0", "1.1.0", 1, hex.EncodeToString(sum[:])))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/1.2.0/" + archiveName():
			w.Write(body)
		case "/redirect/1.2.0/" + archiveName():
			http.Redirect(w, r, "/1.2.0/"+archiveName(), http.StatusFound)
		case "/long/1.2.0/" + archiveName():
			w.Header().Set("Content-Length", strconv.Itoa(maxArchiveBytes+1))
		case "/longer/1.2.0/" + archiveName():
			io.CopyN(w, zeros{}, maxArchiveBytes+1)
		case "/slow/1.2.0/" + archiveName():
			<-r.Context().Done()
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	bin := t.TempDir()
	path := filepath.Join(bin, "ovenlight.download")

	// An old download is deleted, not written into.
	old := filepath.Join(t.TempDir(), "old")
	os.WriteFile(path, []byte("old"), 0o600)
	if err := os.Link(path, old); err != nil {
		t.Fatal(err)
	}
	got, err := downloadArchive(context.Background(), srv.URL, bin, good)
	if b, _ := os.ReadFile(got); err != nil || got != path || !bytes.Equal(b, body) {
		t.Fatalf("%s: %q, %v", got, b, err)
	}
	oldInfo, _ := os.Stat(old)
	newInfo, _ := os.Stat(path)
	if b, _ := os.ReadFile(old); string(b) != "old" || os.SameFile(oldInfo, newInfo) {
		t.Errorf("the old download was written into: %q", b)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	for name, c := range map[string]struct {
		ctx  context.Context
		base string
		idx  *updateIndex
	}{
		"another hash":    {context.Background(), srv.URL, mustIndex(t, testIndex("1.2.0", "1.1.0", "1.1.0", 1, strings.Repeat("0", 64)))},
		"no archive":      {context.Background(), srv.URL, mustIndex(t, []byte(`{"latest": "1.2.0", "secure": "1.1.0", "reinstallBelow": "1.1.0", "serial": 1}`))},
		"not there":       {context.Background(), srv.URL, mustIndex(t, testIndex("1.2.1", "1.1.0", "1.1.0", 1, hex.EncodeToString(sum[:])))},
		"a redirect":      {context.Background(), srv.URL + "/redirect", good},
		"stated too long": {context.Background(), srv.URL + "/long", good},
		"too long":        {context.Background(), srv.URL + "/longer", good},
		"past a deadline": {ctx, srv.URL + "/slow", good},
	} {
		if _, err := downloadArchive(c.ctx, c.base, bin, c.idx); err == nil {
			t.Errorf("%s: downloaded", name)
		}
		if _, err := os.Lstat(path); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("%s: left %v", name, err)
		}
	}
}

type zeros struct{}

func (zeros) Read(b []byte) (int, error) { clear(b); return len(b), nil }

func TestFetchIndex(t *testing.T) {
	big := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if big {
			io.CopyN(w, zeros{}, maxIndexBytes+1)
			return
		}
		w.Write([]byte(r.URL.Path))
	}))
	defer srv.Close()
	data, sig, err := fetchIndex(context.Background(), srv.URL+"/connector")
	if err != nil || string(data) != "/connector/latest.json" || string(sig) != "/connector/latest.json.sig" {
		t.Errorf("%q %q %v", data, sig, err)
	}
	big = true
	if _, _, err := fetchIndex(context.Background(), srv.URL); err == nil {
		t.Error("read an index over 4 KB")
	}
}

// entry is a file in a test archive: a regular file unless typ says otherwise.
type entry struct {
	name, body string
	typ        byte
}

// writeArchive writes a .tar.gz, or a .zip, holding entries into dir.
func writeArchive(t *testing.T, dir string, zipped bool, entries ...entry) string {
	var buf bytes.Buffer
	if zipped {
		zw := zip.NewWriter(&buf)
		for _, e := range entries {
			h := &zip.FileHeader{Name: e.name, Method: zip.Deflate}
			switch e.typ {
			case tar.TypeSymlink:
				h.SetMode(os.ModeSymlink | 0o777)
			case tar.TypeDir:
				h.SetMode(os.ModeDir | 0o755)
			case 'L': // claims more than the size cap
				h.SetMode(0o755)
				h.UncompressedSize64 = maxArchiveBytes + 1
				w, _ := zw.CreateRaw(h)
				w.Write([]byte(e.body))
				continue
			default:
				h.SetMode(0o755)
			}
			w, err := zw.CreateHeader(h)
			if err != nil {
				t.Fatal(err)
			}
			w.Write([]byte(e.body))
		}
		zw.Close()
	} else {
		gz := gzip.NewWriter(&buf)
		tw := tar.NewWriter(gz)
		for _, e := range entries {
			h := &tar.Header{Name: e.name, Mode: 0o755, Size: int64(len(e.body)), Typeflag: tar.TypeReg}
			switch e.typ {
			case tar.TypeSymlink, tar.TypeLink:
				h.Typeflag, h.Linkname, h.Size = e.typ, "ovenlight-connector-1.2.0/LICENSE", 0
			case tar.TypeDir:
				h.Typeflag, h.Size = tar.TypeDir, 0
			case 'L':
				h.Size = maxArchiveBytes + 1
				tw.WriteHeader(h)
				tw = tar.NewWriter(io.Discard) // cut short after the header
				continue
			}
			if err := tw.WriteHeader(h); err != nil {
				t.Fatal(err)
			}
			tw.Write([]byte(e.body))
		}
		tw.Close()
		gz.Close()
	}
	path := filepath.Join(dir, "archive")
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestExtractEntry(t *testing.T) {
	const name = "ovenlight-connector-1.2.0/ovenlight"
	extras := []entry{
		{name: "ovenlight-connector-1.2.0/", typ: tar.TypeDir},
		{name: "ovenlight-connector-1.2.0/LICENSE", body: "license"},
		{name: "ovenlight-connector-1.2.0/install.sh", body: "#!/bin/sh"},
		{name: "ovenlight-connector-1.2.0/launchd/", typ: tar.TypeDir},
		{name: "ovenlight-connector-1.2.0/launchd/app.ovenlight.connector.plist", body: "<plist/>"},
	}
	for _, zipped := range []bool{false, true} {
		dir, bin := t.TempDir(), t.TempDir()
		out := filepath.Join(bin, "ovenlight.next")
		// An old one is deleted, not written into.
		old := filepath.Join(dir, "old")
		os.WriteFile(out, []byte("old"), 0o755)
		if err := os.Link(out, old); err != nil {
			t.Fatal(err)
		}
		archive := writeArchive(t, dir, zipped, append(extras, entry{name: name, body: "the binary"})...)
		if err := extractEntry(archive, zipped, name, out); err != nil {
			t.Fatalf("zip %v: %v", zipped, err)
		}
		b, _ := os.ReadFile(out)
		files, _ := os.ReadDir(bin)
		info, _ := os.Stat(out)
		if string(b) != "the binary" || len(files) != 1 || (runtime.GOOS != "windows" && info.Mode()&0o100 == 0) {
			t.Errorf("zip %v: %q, %d files, %v", zipped, b, len(files), info.Mode())
		}
		oldInfo, _ := os.Stat(old)
		if b, _ := os.ReadFile(old); string(b) != "old" || os.SameFile(oldInfo, info) {
			t.Errorf("zip %v: the old binary was written into", zipped)
		}

		bad := map[string][]entry{
			"another version":   {{name: "ovenlight-connector-1.2.1/ovenlight", body: "x"}},
			"the other OS's":    {{name: name + ".exe", body: "x"}},
			"a dot":             {{name: "./" + name, body: "x"}},
			"rooted":            {{name: "/" + name, body: "x"}},
			"a dot dot":         {{name: "ovenlight-connector-1.2.0/../" + name, body: "x"}},
			"a backslash":       {{name: `ovenlight-connector-1.2.0\ovenlight`, body: "x"}},
			"another case":      {{name: "Ovenlight-connector-1.2.0/ovenlight", body: "x"}},
			"a trailing slash":  {{name: name + "/", typ: tar.TypeDir}},
			"a folder":          {{name: name, typ: tar.TypeDir}},
			"a symlink":         {{name: name, typ: tar.TypeSymlink}},
			"twice":             {{name: name, body: "x"}, {name: name, body: "y"}},
			"over the size cap": {{name: name, body: "x", typ: 'L'}},
		}
		if !zipped {
			bad["a hard link"] = []entry{{name: name, typ: tar.TypeLink}}
		}
		for why, entries := range bad {
			archive := writeArchive(t, dir, zipped, append(extras, entries...)...)
			if err := extractEntry(archive, zipped, name, out); err == nil {
				t.Errorf("zip %v, %s: extracted", zipped, why)
			}
			if _, err := os.Lstat(out); !errors.Is(err, os.ErrNotExist) {
				t.Errorf("zip %v, %s: left %v", zipped, why, err)
			}
		}
		os.WriteFile(archive, []byte("not an archive"), 0o600)
		if err := extractEntry(archive, zipped, name, out); err == nil {
			t.Errorf("zip %v: extracted from no archive", zipped)
		}
	}
}

// extractBinary takes this system's binary, by name, out of this system's archive.
func TestExtractBinary(t *testing.T) {
	exe, next := "ovenlight", "ovenlight.next"
	if runtime.GOOS == "windows" {
		exe, next = "ovenlight.exe", "ovenlight.next.exe"
	}
	dir, bin := t.TempDir(), t.TempDir()
	archive := writeArchive(t, dir, strings.HasSuffix(archiveName(), ".zip"), entry{name: "ovenlight-connector-1.2.0/" + exe, body: "the binary"})
	out, err := extractBinary(archive, bin, "1.2.0")
	if b, _ := os.ReadFile(out); err != nil || out != filepath.Join(bin, next) || string(b) != "the binary" {
		t.Errorf("%s %q %v", out, b, err)
	}
}
