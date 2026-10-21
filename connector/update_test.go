package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestUpdateCheck(t *testing.T) {
	answer := `{"latest": "1.2.0", "secure": "1.1.3"}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(answer)) }))
	defer srv.Close()
	defer func(u, v string) { latestURL, releaseVersion = u, v }(latestURL, releaseVersion)
	latestURL = srv.URL
	for v, want := range map[string]string{"1.1.0": statusFail, "1.1.3": statusWarn, "1.2.0": statusOK, "1.10.0": statusOK} {
		releaseVersion = v
		if c := updateCheck(); c == nil || c.Status != want {
			t.Errorf("%s: %+v, want %s", v, c, want)
		}
	}
	releaseVersion = "" // a development build
	if c := updateCheck(); c != nil {
		t.Errorf("devel: %+v", c)
	}
	// Only the secure version, or a latest older than it: the fix names the secure one.
	releaseVersion = "1.0.0"
	for _, a := range []string{`{"secure": "1.1.3"}`, `{"latest": "1.1", "secure": "1.1.3"}`, `{"latest": "1.0.5", "secure": "1.1.3"}`} {
		answer = a
		if c := updateCheck(); c == nil || c.Status != statusFail || !strings.Contains(c.Fix, "Install 1.1.3 ") {
			t.Errorf("%s: %+v", a, c)
		}
	}
	releaseVersion, answer = "1.0.0", "not json"
	if c := updateCheck(); c != nil {
		t.Errorf("unreadable answer: %+v", c)
	}
}

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
