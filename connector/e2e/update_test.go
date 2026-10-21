//go:build e2e

// End-to-end test of the updater as an owner's computer runs it: test releases installed
// with their own install script as the service (systemd, launchd or the scheduled task),
// a local server with an index signed with the test key, and a schedule compressed to a
// 5-second hour, so the daily check, the two-day wait, the night window and the trial all
// come round within minutes. It installs and uninstalls the connector for real, so it
// runs only with OVENLIGHT_E2E_UPDATE=1, in CI's install jobs.
//
//	OVENLIGHT_E2E_UPDATE=1 go test -tags e2e ./e2e -run TestUpdate -v -timeout 60m
package e2e

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// hour is the updater's hour here: a day of two minutes, whose night (02:00 to 05:00) is
// 15 seconds of each.
const hour = 5 * time.Second

// updateRig is the installed connector and the release server.
type updateRig struct {
	t        *testing.T
	releases string // a folder per version, as its archive unpacks
	served   string // what the server serves
	url      string
	support  string // the connector's own folder
	log      string
	serial   int64
}

// updateRecord is the part of update.json the test reads.
type updateRecord struct {
	Seen    time.Time `json:"seen"`
	Minute  int       `json:"minute"`
	Checked time.Time `json:"checked"`
	Updated *struct {
		From, To string
		At       time.Time
	} `json:"updated"`
	Failed *struct {
		Version, Error string
		At             time.Time
		Count          int
	} `json:"failed"`
}

func exeName(name string) string {
	if runtime.GOOS == "windows" {
		return name + ".exe"
	}
	return name
}

// archiveName is this system's release archive, as the connector names it.
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

// dayTime is t's time of day on the compressed clock, as the connector reckons it.
func dayTime(t time.Time) time.Duration {
	t = t.Local()
	h, m, s := t.Clock()
	wall := time.Duration(h)*time.Hour + time.Duration(m)*time.Minute + time.Duration(s)*time.Second + time.Duration(t.Nanosecond())
	return wall % (24 * hour) * (time.Hour / hour)
}

func run(t *testing.T, name string, args ...string) string {
	t.Helper()
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		t.Fatalf("%s %q: %v\n%s", name, args, err, out)
	}
	return string(out)
}

func newUpdateRig(t *testing.T) *updateRig {
	if os.Getenv("OVENLIGHT_E2E_UPDATE") != "1" {
		t.Skip("installs the connector as a service: set OVENLIGHT_E2E_UPDATE=1, as CI's install jobs do")
	}
	home, _ := os.UserHomeDir()
	r := &updateRig{t: t, releases: t.TempDir(), served: t.TempDir()}
	switch runtime.GOOS {
	case "darwin":
		r.support = filepath.Join(home, "Library", "Application Support", "ovenlight")
		r.log = filepath.Join(home, "Library", "Logs", "ovenlight.log")
	case "linux":
		state := os.Getenv("XDG_STATE_HOME")
		if state == "" {
			state = filepath.Join(home, ".local", "state")
		}
		r.support = filepath.Join(state, "ovenlight")
		r.log = filepath.Join(r.support, "ovenlight.log")
	case "windows":
		r.support = filepath.Join(os.Getenv("LOCALAPPDATA"), "ovenlight")
		r.log = filepath.Join(r.support, "ovenlight.log")
	}
	if _, err := os.Stat(r.support); err == nil {
		t.Fatalf("%s exists: this test installs a connector of its own", r.support)
	}
	srv := httptest.NewServer(http.FileServer(http.Dir(r.served)))
	t.Cleanup(srv.Close)
	r.url = srv.URL
	r.serviceEnv(map[string]string{"OVENLIGHT_UPDATE_BASE": r.url, "OVENLIGHT_UPDATE_HOUR": hour.String()})
	t.Cleanup(func() {
		r.script("uninstall", "--purge")
		r.serviceEnv(map[string]string{"OVENLIGHT_UPDATE_BASE": "", "OVENLIGHT_UPDATE_HOUR": ""})
	})
	return r
}

// serviceEnv sets (or with "", unsets) variables in the environment the service gets.
func (r *updateRig) serviceEnv(vars map[string]string) {
	for k, v := range vars {
		switch runtime.GOOS {
		case "linux":
			if v == "" {
				run(r.t, "systemctl", "--user", "unset-environment", k)
			} else {
				run(r.t, "systemctl", "--user", "set-environment", k+"="+v)
			}
		case "darwin":
			if v == "" {
				run(r.t, "launchctl", "unsetenv", k)
			} else {
				run(r.t, "launchctl", "setenv", k, v)
			}
		case "windows":
			// Task Scheduler builds a task's environment from the registry's, the
			// computer's and the account's.
			val := "'" + v + "'"
			if v == "" {
				val = "$null"
			}
			for _, scope := range []string{"Machine", "User"} {
				run(r.t, "powershell", "-NoProfile", "-Command", fmt.Sprintf("[Environment]::SetEnvironmentVariable('%s', %s, '%s')", k, val, scope))
			}
		}
	}
}

// build makes the test-key release v, as its archive unpacks (the binary and the install
// scripts), and puts its archive on the server. crash makes one that crashes as the
// service starts it.
func (r *updateRig) build(v string, crash bool) {
	r.t.Helper()
	dir := filepath.Join(r.releases, v, "ovenlight-connector-"+v)
	ldflags := "-X main.releaseVersion=" + v
	if crash {
		ldflags += " -X main.testCrash=1"
	}
	bin := filepath.Join(dir, exeName("ovenlight"))
	cmd := exec.Command("go", "build", "-trimpath", "-tags", "ovenlight_testkey", "-ldflags", ldflags, "-o", bin, ".")
	cmd.Dir = ".."
	if out, err := cmd.CombinedOutput(); err != nil {
		r.t.Fatalf("building %s: %v\n%s", v, err, out)
	}
	scripts := []string{"install.sh", "uninstall.sh", "systemd/ovenlight.service", "launchd/com.snowyghost.ovenlight.connector.plist"}
	if runtime.GOOS == "windows" {
		scripts = []string{"install.ps1", "uninstall.ps1"}
	}
	for _, s := range scripts {
		b, err := os.ReadFile(filepath.Join("..", s))
		if err == nil {
			err = os.MkdirAll(filepath.Dir(filepath.Join(dir, s)), 0o755)
		}
		if err == nil {
			err = os.WriteFile(filepath.Join(dir, s), b, 0o755)
		}
		if err != nil {
			r.t.Fatal(err)
		}
	}
	body, err := os.ReadFile(bin)
	if err != nil {
		r.t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(r.served, v), 0o755); err != nil {
		r.t.Fatal(err)
	}
	f, err := os.Create(filepath.Join(r.served, v, archiveName()))
	if err != nil {
		r.t.Fatal(err)
	}
	defer f.Close()
	name := "ovenlight-connector-" + v + "/" + exeName("ovenlight")
	if strings.HasSuffix(archiveName(), ".zip") {
		z := zip.NewWriter(f)
		w, err := z.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate})
		if err == nil {
			_, err = w.Write(body)
		}
		if err == nil {
			err = z.Close()
		}
		if err != nil {
			r.t.Fatal(err)
		}
		return
	}
	gz := gzip.NewWriter(f)
	tw := tar.NewWriter(gz)
	err = tw.WriteHeader(&tar.Header{Name: name, Mode: 0o755, Size: int64(len(body)), Typeflag: tar.TypeReg})
	if err == nil {
		_, err = tw.Write(body)
	}
	if err == nil {
		err = tw.Close()
	}
	if err == nil {
		err = gz.Close()
	}
	if err != nil {
		r.t.Fatal(err)
	}
}

// publish puts up an index naming latest, with secure and reinstallBelow as given, signed
// with the test key in the namespace latest.json takes.
func (r *updateRig) publish(latest, secure, below string) {
	r.t.Helper()
	body, err := os.ReadFile(filepath.Join(r.served, latest, archiveName()))
	if err != nil {
		r.t.Fatal(err)
	}
	sum := sha256.Sum256(body)
	r.serial++
	data, _ := json.Marshal(map[string]any{"latest": latest, "secure": secure, "reinstallBelow": below, "serial": r.serial,
		"archives": map[string]string{archiveName(): hex.EncodeToString(sum[:])}})
	sig := sign(r.t, data, "ovenlight-latest")
	if err := os.WriteFile(filepath.Join(r.served, "latest.json"), data, 0o644); err != nil {
		r.t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(r.served, "latest.json.sig"), sig, 0o644); err != nil {
		r.t.Fatal(err)
	}
	r.t.Logf("published %s (secure %s), serial %d", latest, secure, r.serial)
}

// sign signs data in namespace with the test release key, as ssh-keygen -Y sign does.
func sign(t *testing.T, data []byte, namespace string) []byte {
	key, err := os.ReadFile("../testdata/releasekey/test-key")
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

// script runs release v's install script, or with v "uninstall", the uninstall script
// of the newest release built.
func (r *updateRig) script(v string, args ...string) {
	r.t.Helper()
	name := "install"
	if v == "uninstall" {
		name = "uninstall"
		entries, _ := os.ReadDir(r.releases)
		if len(entries) == 0 {
			return
		}
		v = entries[len(entries)-1].Name()
	}
	dir := filepath.Join(r.releases, v, "ovenlight-connector-"+v)
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("powershell", append([]string{"-NoProfile", "-ExecutionPolicy", "Bypass", "-File", filepath.Join(dir, name+".ps1")}, args...)...)
	} else {
		cmd = exec.Command("bash", append([]string{filepath.Join(dir, name+".sh")}, args...)...)
	}
	out, err := cmd.CombinedOutput()
	r.t.Logf("%s %s:\n%s", name, v, out)
	if err != nil {
		r.t.Fatalf("%s %s: %v", name, v, err)
	}
}

func (r *updateRig) record() updateRecord {
	var st updateRecord
	if b, err := os.ReadFile(filepath.Join(r.support, "update.json")); err == nil {
		json.Unmarshal(b, &st)
	}
	return st
}

// status is ovenlight status, which asks the connector over its control socket. Only
// called once a step is done: a request keeps the connector from a quiet moment.
func (r *updateRig) status() string {
	out, _ := exec.Command(filepath.Join(r.support, "bin", exeName("ovenlight")), "status").CombinedOutput()
	return string(out)
}

// waitFor polls until ok, and fails the test with the connector's log after timeout.
func (r *updateRig) waitFor(what string, timeout time.Duration, ok func() bool) {
	r.t.Helper()
	start := time.Now()
	for !ok() {
		if time.Since(start) > timeout {
			b, _ := os.ReadFile(r.log)
			if len(b) > 20000 {
				b = b[len(b)-20000:]
			}
			st, _ := os.ReadFile(filepath.Join(r.support, "update.json"))
			r.t.Fatalf("%s: not within %s\nupdate.json: %s\nlog:\n%s", what, timeout, st, b)
		}
		time.Sleep(time.Second)
	}
	r.t.Logf("%s, after %s", what, time.Since(start).Round(time.Second))
}

// runningVersion waits for the connector to be running v.
func (r *updateRig) runningVersion(v string) {
	r.t.Helper()
	r.waitFor("running "+v, 3*time.Minute, func() bool {
		out := r.status()
		return strings.Contains(out, "Connector: running, pid ") && strings.Contains(out, "version "+v+"\n")
	})
}

// updatedTo waits for an update to v that passed its trial, recorded after since.
func (r *updateRig) updatedTo(from, v string, since time.Time, timeout time.Duration) updateRecord {
	r.t.Helper()
	var st updateRecord
	r.waitFor("updated to "+v, timeout, func() bool {
		st = r.record()
		return st.Updated != nil && st.Updated.From == from && st.Updated.To == v && st.Updated.At.After(since)
	})
	return st
}

// Two updates in a row, each through the daily check, the two-day wait and the night
// window; an older release's install script, with the newer release back after it; and
// a rollback from a release that crashes as the service starts it.
func TestUpdate(t *testing.T) {
	r := newUpdateRig(t)
	for _, v := range []string{"1.1.0", "1.1.1", "1.1.2"} {
		r.build(v, false)
	}
	r.build("1.1.3", true)
	// A day of two minutes, the wait two days of it, and the restart up to a minute on
	// Windows.
	updateWait := 2*48*hour + 2*24*hour + 2*time.Minute

	r.publish("1.1.0", "1.1.0", "1.1.0")
	start := time.Now()
	r.script("1.1.0")
	r.runningVersion("1.1.0")
	// A check error naming downloads.ovenlight.app means the service didn't get the
	// variables serviceEnv set.
	r.waitFor("a verified check of the local index", time.Minute, func() bool { return r.record().Checked.After(start) })

	from := "1.1.0"
	for _, v := range []string{"1.1.1", "1.1.2"} {
		published := time.Now()
		r.publish(v, "1.1.0", "1.1.0")
		st := r.updatedTo(from, v, published, updateWait)
		// The daily check saw it at its minute, and the swap came two days later, in the
		// night.
		if late := (dayTime(st.Seen) - time.Duration(st.Minute)*time.Minute + 24*time.Hour) % (24 * time.Hour); late > 3*time.Minute {
			t.Errorf("%s: seen %v after the daily minute, %d", v, late, st.Minute)
		}
		if st.Updated.At.Sub(st.Seen) < 48*hour {
			t.Errorf("%s: seen %v, installed %v: no two-day wait", v, st.Seen, st.Updated.At)
		}
		if d := dayTime(st.Updated.At); d < 2*time.Hour || d >= 5*time.Hour {
			t.Errorf("%s: installed at %v on the updater's clock, outside the night", v, d)
		}
		r.runningVersion(v)
		from = v
	}

	// An older release's install script puts it back, without .prev or a trial, and the
	// release the index names comes back on its own.
	reinstalled := time.Now()
	r.script("1.1.1")
	for _, f := range []string{"update-trial.json", filepath.Join("bin", exeName("ovenlight.prev"))} {
		if _, err := os.Stat(filepath.Join(r.support, f)); err == nil {
			t.Errorf("the install script left %s", f)
		}
	}
	r.updatedTo("1.1.1", "1.1.2", reinstalled, 2*24*hour+3*time.Minute)
	r.runningVersion("1.1.2")

	// A security release that crashes as the service starts it: its second start puts
	// 1.1.2 back. Updates go off once its trial starts, so it isn't tried again the next
	// night while the test looks.
	r.publish("1.1.3", "1.1.3", "1.1.0")
	trial := filepath.Join(r.support, "update-trial.json")
	r.waitFor("a trial of 1.1.3", 24*hour+2*time.Minute, func() bool {
		b, err := os.ReadFile(trial)
		return err == nil && strings.Contains(string(b), `"to": "1.1.3"`)
	})
	run(t, filepath.Join(r.support, "bin", exeName("ovenlight")), "update", "off")
	r.waitFor("a rollback from 1.1.3", 5*time.Minute, func() bool {
		st := r.record()
		_, err := os.Stat(trial)
		return st.Failed != nil && st.Failed.Version == "1.1.3" && os.IsNotExist(err)
	})
	r.runningVersion("1.1.2")
	if st := r.record(); st.Failed.Count != 1 || !strings.Contains(st.Failed.Error, "started again within its trial") {
		t.Errorf("%+v", st.Failed)
	}
	if out := r.status(); !strings.Contains(out, "UPDATE FAILED: 1.1.3") {
		t.Errorf("status doesn't show the rollback:\n%s", out)
	}
	if out := run(t, filepath.Join(r.support, "bin", exeName("ovenlight")), "version"); out != "1.1.2\n" {
		t.Errorf("the binary in place is %q", out)
	}
}
