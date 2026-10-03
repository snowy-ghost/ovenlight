//go:build e2e

// End-to-end test of sharing against a local Headscale: a shareable app node, an
// invite, a guest device that joins with the key and claims it, the admin port closed
// to guests, feedback, revoke and sync with the device list. Headscale has no
// Tailscale-compatible API, so the daemon's --dev-headscale adapter stands in for keys
// and devices; the Tailscale API paths are covered by the unit tests against the fake API.
//
//	OVENLIGHT_E2E_HEADSCALE=/path/to/headscale go test -tags e2e ./e2e -v -timeout 15m
package e2e

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"image"
	"image/png"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
	"time"

	"tailscale.com/tsnet"
)

const baseDomain = "e2e.ovenlight.test"

type rig struct {
	t          *testing.T
	dir        string
	hsBin      string
	hsConfig   string
	controlURL string
	bin        string // ovenlight
	config     string
	state      string
	caPool     *x509.CertPool
	appPort    int
}

func freePort(t *testing.T) int {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

func (r *rig) hs(args ...string) []byte {
	r.t.Helper()
	out, err := exec.Command(r.hsBin, append([]string{"-c", r.hsConfig}, args...)...).CombinedOutput()
	if err != nil {
		r.t.Fatalf("headscale %v: %v\n%s", args, err, out)
	}
	return out
}

// sv runs the ovenlight CLI. With tty it runs under script(1), so the command sees a
// terminal, as when a person types it; input goes to that terminal.
func (r *rig) sv(tty bool, input string, args ...string) (string, error) {
	r.t.Helper()
	args = append(args, "--config", r.config, "--state", r.state)
	var cmd *exec.Cmd
	switch {
	case tty && runtime.GOOS == "linux":
		// util-linux script takes the command as one shell string, and -e for its exit status.
		quoted := []string{shellQuote(r.bin)}
		for _, a := range args {
			quoted = append(quoted, shellQuote(a))
		}
		cmd = exec.Command("script", "-q", "-e", "-c", strings.Join(quoted, " "), "/dev/null")
	case tty:
		cmd = exec.Command("/usr/bin/script", append([]string{"-q", "/dev/null", r.bin}, args...)...)
	default:
		cmd = exec.Command(r.bin, args...)
	}
	if tty {
		// Type the input once the command is waiting for it, as a person would; script
		// would otherwise pass the end of input to the terminal before the answer.
		stdin, err := cmd.StdinPipe()
		if err != nil {
			r.t.Fatal(err)
		}
		go func() {
			time.Sleep(1500 * time.Millisecond)
			io.WriteString(stdin, input)
		}()
	} else {
		cmd.Stdin = strings.NewReader(input)
	}
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

func (r *rig) svOK(args ...string) string {
	r.t.Helper()
	out, err := r.sv(false, "", args...)
	if err != nil {
		r.t.Fatalf("ovenlight %v: %v\n%s", args, err, out)
	}
	return out
}

func (r *rig) start(name string, cmd *exec.Cmd) {
	r.t.Helper()
	logf, err := os.Create(filepath.Join(r.dir, name+".log"))
	if err != nil {
		r.t.Fatal(err)
	}
	cmd.Stdout, cmd.Stderr = logf, logf
	if err := cmd.Start(); err != nil {
		r.t.Fatal(err)
	}
	r.t.Cleanup(func() {
		cmd.Process.Signal(os.Interrupt)
		done := make(chan struct{})
		go func() { cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			cmd.Process.Kill()
		}
		logf.Close()
	})
}

func eventually(t *testing.T, within time.Duration, what string, cond func() (bool, string)) {
	t.Helper()
	deadline := time.Now().Add(within)
	last := ""
	for time.Now().Before(deadline) {
		ok, msg := cond()
		if ok {
			return
		}
		last = msg
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s (last: %s)", what, last)
}

// writeCerts makes a CA and a wildcard certificate for the test domain; Headscale
// can't issue ts.net certificates.
func (r *rig) writeCerts() (certFile, keyFile string) {
	caKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	ca := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "ovenlight e2e CA"}, IsCA: true,
		BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour)}
	caDER, _ := x509.CreateCertificate(rand.Reader, ca, ca, &caKey.PublicKey, caKey)
	caCert, _ := x509.ParseCertificate(caDER)
	leafKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "*." + baseDomain}, DNSNames: []string{"*." + baseDomain},
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour)}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leaf, caCert, &leafKey.PublicKey, caKey)
	keyDER, _ := x509.MarshalECPrivateKey(leafKey)
	certFile, keyFile = filepath.Join(r.dir, "leaf.pem"), filepath.Join(r.dir, "leaf.key")
	os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER}), 0o600)
	os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600)
	r.caPool = x509.NewCertPool()
	r.caPool.AddCert(caCert)
	return certFile, keyFile
}

func (r *rig) startHeadscale() {
	port, metrics, grpc := freePort(r.t), freePort(r.t), freePort(r.t)
	r.controlURL = fmt.Sprintf("http://127.0.0.1:%d", port)
	policy := `{
	"tagOwners": {
		"tag:ovenlight-app-echo": ["owner@"],
		"tag:ovenlight-guest-echo": ["owner@"],
		"tag:ovenlight-app-notes": ["owner@"],
		"tag:ovenlight-guest-notes": ["owner@"],
	},
	"acls": [
		// What setup-sharing and publish --shareable produce from the default policy for
		// two apps, with Headscale's user syntax for the owner.
		{"action": "accept", "src": ["owner@"], "dst": ["owner@:*", "tag:ovenlight-app-echo:*", "tag:ovenlight-app-notes:*"]},
		{"action": "accept", "src": ["tag:ovenlight-guest-echo"], "dst": ["tag:ovenlight-app-echo:443"]},
		{"action": "accept", "src": ["tag:ovenlight-guest-notes"], "dst": ["tag:ovenlight-app-notes:443"]},
	],
}`
	os.WriteFile(filepath.Join(r.dir, "policy.hujson"), []byte(policy), 0o600)
	cfg := fmt.Sprintf(`server_url: %s
listen_addr: 127.0.0.1:%d
metrics_listen_addr: 127.0.0.1:%d
grpc_listen_addr: 127.0.0.1:%d
grpc_allow_insecure: false
noise:
  private_key_path: %[5]s/noise_private.key
prefixes:
  v4: 100.64.0.0/10
  v6: fd7a:115c:a1e0::/48
  allocation: sequential
derp:
  server:
    enabled: false
  urls:
    - https://controlplane.tailscale.com/derpmap/default
  paths: []
  auto_update_enabled: false
  update_frequency: 24h
disable_check_updates: true
node:
  expiry: 0
database:
  type: sqlite
  sqlite:
    path: %[5]s/db.sqlite
    write_ahead_log: true
log:
  level: info
  format: text
policy:
  mode: file
  path: %[5]s/policy.hujson
dns:
  magic_dns: true
  base_domain: %[6]s
  override_local_dns: false
  nameservers:
    global: []
    split: {}
  search_domains: []
  extra_records: []
unix_socket: %[5]s/hs.sock
unix_socket_permission: "0770"
logtail:
  enabled: false
taildrop:
  enabled: false
`, r.controlURL, port, metrics, grpc, r.dir, baseDomain)
	r.hsConfig = filepath.Join(r.dir, "headscale.yaml")
	os.WriteFile(r.hsConfig, []byte(cfg), 0o600)
	r.start("headscale", exec.Command(r.hsBin, "-c", r.hsConfig, "serve"))
	eventually(r.t, 30*time.Second, "headscale", func() (bool, string) {
		out, err := exec.Command(r.hsBin, "-c", r.hsConfig, "users", "list").CombinedOutput()
		return err == nil, string(out)
	})
}

type hsNode struct {
	ID        json.Number `json:"id"`
	GivenName string      `json:"given_name"`
	Tags      []string    `json:"tags"`
	Forced    []string    `json:"forced_tags"`
	Valid     []string    `json:"valid_tags"`
}

func (n hsNode) tags() []string { return append(append(n.Tags, n.Forced...), n.Valid...) }

func (r *rig) nodes() []hsNode {
	var nodes []hsNode
	if err := json.Unmarshal(r.hs("nodes", "list", "-o", "json"), &nodes); err != nil {
		r.t.Fatal(err)
	}
	return nodes
}

func (r *rig) keyCount() int {
	var keys []json.RawMessage
	json.Unmarshal(r.hs("preauthkeys", "list", "-o", "json"), &keys)
	return len(keys)
}

// probe is a device on the test tailnet: the owner's phone or a guest's.
type probe struct {
	srv *tsnet.Server
	hc  *http.Client
}

func (r *rig) join(name, key string) (*probe, error) {
	srv := &tsnet.Server{Dir: filepath.Join(r.dir, "probe-"+name), Hostname: name, AuthKey: key, ControlURL: r.controlURL,
		Logf: func(string, ...any) {}}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if _, err := srv.Up(ctx); err != nil {
		srv.Close()
		return nil, err
	}
	hc := srv.HTTPClient()
	tr := hc.Transport.(*http.Transport).Clone()
	tr.TLSClientConfig = &tls.Config{RootCAs: r.caPool}
	hc.Transport, hc.Timeout = tr, 15*time.Second
	r.t.Cleanup(func() { srv.Close() })
	return &probe{srv: srv, hc: hc}, nil
}

func (p *probe) do(method, url string, body string, header map[string]string) (int, string, error) {
	req, _ := http.NewRequest(method, url, strings.NewReader(body))
	for k, v := range header {
		req.Header.Set(k, v)
	}
	resp, err := p.hc.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b), nil
}

func (p *probe) must(t *testing.T, method, url, body string, header map[string]string, wantCode int) string {
	t.Helper()
	var code int
	var out string
	var err error
	// The first request after a join can race the netmap; retry briefly.
	eventually(t, 20*time.Second, method+" "+url, func() (bool, string) {
		code, out, err = p.do(method, url, body, header)
		if err != nil {
			return false, err.Error()
		}
		return true, ""
	})
	if code != wantCode {
		t.Fatalf("%s %s: HTTP %d, want %d: %s", method, url, code, wantCode, out)
	}
	return out
}

// linkPattern finds the universal link; its fields ride in the fragment.
var linkPattern = regexp.MustCompile(`https://ovenlight\.app/join#[^\s\x1b]+`)

func keyFromLink(t *testing.T, link string) string {
	t.Helper()
	u, err := url.Parse(link)
	if err != nil {
		t.Fatalf("bad link %q", link)
	}
	q, err := url.ParseQuery(u.EscapedFragment())
	if err != nil || q.Get("key") == "" {
		t.Fatalf("bad link %q", link)
	}
	return q.Get("key")
}

func pngBase64() string {
	var buf bytes.Buffer
	png.Encode(&buf, image.NewGray(image.Rect(0, 0, 32, 16)))
	return base64.StdEncoding.EncodeToString(buf.Bytes())
}

// echoApp answers with the request headers it got, and /stream sends a line every
// 200 ms until the client goes away.
func echoApp(t *testing.T) int {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/stream" {
			w.Header().Set("Content-Type", "text/plain")
			for i := 0; ; i++ {
				if _, err := fmt.Fprintf(w, "tick %d\n", i); err != nil {
					return
				}
				w.(http.Flusher).Flush()
				select {
				case <-r.Context().Done():
					return
				case <-time.After(200 * time.Millisecond):
				}
			}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"path": r.URL.Path, "headers": r.Header})
	})}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return ln.Addr().(*net.TCPAddr).Port
}

func headersOf(t *testing.T, body string) http.Header {
	t.Helper()
	var out struct {
		Headers http.Header `json:"headers"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatalf("not the echo app's answer: %s", body)
	}
	return out.Headers
}

func TestSharingEndToEnd(t *testing.T) {
	hsBin := os.Getenv("OVENLIGHT_E2E_HEADSCALE")
	if hsBin == "" {
		t.Skip("set OVENLIGHT_E2E_HEADSCALE to a headscale binary")
	}
	dir, err := os.MkdirTemp("/tmp", "sv-e2e-") // unix socket paths must stay short
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if !t.Failed() {
			os.RemoveAll(dir)
		} else {
			t.Logf("logs kept in %s", dir)
		}
	})
	os.Setenv("TS_DISABLE_PORTMAPPER", "1")
	r := &rig{t: t, dir: dir, hsBin: hsBin, config: filepath.Join(dir, "cfg", "config.json"), state: filepath.Join(dir, "st")}

	r.bin = filepath.Join(dir, "ovenlight")
	if out, err := exec.Command("go", "build", "-o", r.bin, "..").CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	certFile, keyFile := r.writeCerts()
	r.startHeadscale()
	r.hs("users", "create", "owner")
	var users []struct {
		ID json.Number `json:"id"`
	}
	json.Unmarshal(r.hs("users", "list", "-o", "json"), &users)
	var ownerKey struct {
		Key string `json:"key"`
	}
	json.Unmarshal(r.hs("preauthkeys", "create", "-u", users[0].ID.String(), "--reusable", "-e", "1h", "-o", "json"), &ownerKey)

	// 1. The connector publishes an app the ordinary way (owned by the user).
	r.appPort = echoApp(t)
	r.svOK("publish", "--port", fmt.Sprint(r.appPort), "--name", "Echo", "--slug", "echo")
	daemon := exec.Command(r.bin, "run", "--config", r.config, "--state", r.state, "--control-url", r.controlURL, "--auth-key", ownerKey.Key,
		"--dev-tls-cert", certFile, "--dev-tls-key", keyFile, "--dev-headscale", hsBin, "--dev-headscale-config", r.hsConfig)
	daemon.Env = append(os.Environ(), "OVENLIGHT_DEV=1", "TS_NO_LOGS_NO_SUPPORT=1")
	r.start("connector", daemon)
	var nodeUser string
	eventually(t, 60*time.Second, "the echo node to serve", func() (bool, string) {
		out, _ := r.sv(false, "", "status", "--json")
		var st struct {
			Apps []struct {
				State    string `json:"state"`
				NodeUser string `json:"nodeUser"`
			} `json:"apps"`
		}
		json.Unmarshal([]byte(out), &st)
		if len(st.Apps) == 1 && st.Apps[0].State == "serving" {
			nodeUser = st.Apps[0].NodeUser
			return true, ""
		}
		return false, out
	})
	appURL := "https://echo." + baseDomain

	owner, err := r.join("owner-phone", ownerKey.Key)
	if err != nil {
		t.Fatal(err)
	}
	h := headersOf(t, owner.must(t, "GET", appURL+"/", "", map[string]string{"Ovenlight-Role": "guest"}, 200))
	if h.Get("Ovenlight-Role") != "owner" || h.Get("Ovenlight-User-Id") != nodeUser {
		t.Fatalf("owner headers before sharing: %v", h)
	}
	t.Logf("owner login %q reaches the untagged app node", nodeUser)

	// 2. Sharing set up (the policy is Headscale's file; the owner goes in the config),
	// then the app becomes shareable: a tagged node under the same name.
	var cfg map[string]any
	b, _ := os.ReadFile(r.config)
	json.Unmarshal(b, &cfg)
	cfg["owner"], cfg["ownerLabel"] = nodeUser, "Owen"
	b, _ = json.Marshal(cfg)
	os.WriteFile(r.config, b, 0o600)
	out := r.svOK("publish", "--slug", "echo", "--shareable")
	if !strings.Contains(out, "runs as a shareable app node") {
		t.Fatalf("publish --shareable:\n%s", out)
	}
	var echoNodes []hsNode
	for _, n := range r.nodes() {
		if strings.HasPrefix(n.GivenName, "echo") {
			echoNodes = append(echoNodes, n)
		}
	}
	if len(echoNodes) != 1 || echoNodes[0].GivenName != "echo" || !strings.Contains(strings.Join(echoNodes[0].tags(), ","), "tag:ovenlight-app-echo") {
		t.Fatalf("after conversion, echo nodes = %+v", echoNodes)
	}
	t.Logf("echo is now node %s tagged %v", echoNodes[0].ID, echoNodes[0].tags())

	// The owner still gets in, on both ports, as the configured owner.
	h = headersOf(t, owner.must(t, "GET", appURL+"/", "", nil, 200))
	if h.Get("Ovenlight-Role") != "owner" {
		t.Fatalf("owner headers after tagging: %v", h)
	}
	adminURL := appURL + ":8443"
	if out := owner.must(t, "GET", adminURL+"/v1/apps", "", nil, 200); !strings.Contains(out, `"shareable":true`) || !strings.Contains(out, `"online":true`) {
		t.Fatalf("admin apps: %s", out)
	}
	owner.must(t, "POST", adminURL+"/v1/invites", `{"to":"Kim","app":"echo"}`, nil, 403) // no X-Ovenlight-Request header
	// Guests never wait for the owner's approval, so there is nothing to approve.
	if out := owner.must(t, "GET", adminURL+"/v1/guests", "", nil, 200); strings.Contains(out, "pendingApproval") {
		t.Fatalf("admin guests: %s", out)
	}
	owner.must(t, "POST", adminURL+"/v1/devices/1/approve", "", map[string]string{"X-Ovenlight-Request": "1"}, 404)

	// 3. Share from the terminal. Without a terminal the CLI refuses.
	if out, err := r.sv(false, "", "share", "echo", "--to", "Sam"); err == nil || !strings.Contains(out, "a person has to run it in a terminal") {
		t.Fatalf("share without a terminal: %v\n%s", err, out)
	}
	keysBefore := r.keyCount()
	out, err = r.sv(true, "", "share", "echo", "--to", "Sam")
	if err != nil {
		t.Fatalf("share: %v\n%s", err, out)
	}
	link := linkPattern.FindString(out)
	if link == "" || !strings.Contains(out, "▀") || !strings.Contains(out, "I'm sharing Echo with you in Ovenlight") || strings.Contains(out, "approv") {
		t.Fatalf("share output:\n%s", out)
	}
	if r.keyCount() != keysBefore+1 {
		t.Fatalf("keys %d -> %d", keysBefore, r.keyCount())
	}
	samKey := keyFromLink(t, link)

	// 4. Sam's phone joins with the key: not invited until it claims.
	sam, err := r.join("sam-iphone", samKey)
	if err != nil {
		t.Fatalf("guest join: %v", err)
	}
	if out := sam.must(t, "GET", appURL+"/", "", nil, 403); !strings.Contains(out, "not invited") {
		t.Fatalf("unclaimed guest: %s", out)
	}
	// The phone acts only on the code, never the text.
	if out := sam.must(t, "POST", appURL+"/__ovenlight/claim", `{"key":"wrong"}`, nil, 403); !strings.Contains(out, `"code":"not_invited"`) {
		t.Fatalf("wrong key: %s", out)
	}
	if out := sam.must(t, "POST", appURL+"/__ovenlight/claim", fmt.Sprintf(`{"key":%q}`, samKey), nil, 200); !strings.Contains(out, `"name":"Sam"`) {
		t.Fatalf("claim: %s", out)
	}
	h = headersOf(t, sam.must(t, "GET", appURL+"/page", "", map[string]string{"Ovenlight-Role": "owner", "Tailscale-User-Login": nodeUser, "ovenlight_user": "x"}, 200))
	if h.Get("Ovenlight-Role") != "guest" || h.Get("Ovenlight-User") != "Sam" || !strings.HasPrefix(h.Get("Ovenlight-User-Id"), "guest:") || h.Get("Tailscale-User-Login") != "" {
		t.Fatalf("guest headers: %v", h)
	}
	t.Logf("guest headers: Ovenlight-Role=%s Ovenlight-User=%s Ovenlight-User-Id=%s", h.Get("Ovenlight-Role"), h.Get("Ovenlight-User"), h.Get("Ovenlight-User-Id"))
	samID := h.Get("Ovenlight-User-Id")
	if out := sam.must(t, "GET", appURL+"/__ovenlight/whoami", "", nil, 200); !strings.Contains(out, `"role":"guest"`) || !strings.Contains(out, `"owner":"Owen"`) {
		t.Fatalf("whoami: %s", out)
	}

	// The admin port is closed to guests by the policy: the connection never opens.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	if c, err := sam.srv.Dial(ctx, "tcp", "echo."+baseDomain+":8443"); err == nil {
		c.Close()
		cancel()
		t.Fatal("a guest reached the admin port")
	}
	cancel()
	t.Log("guest denied :8443 at the network layer")

	// The key was single use.
	if _, err := r.join("thief-phone", samKey); err == nil {
		t.Fatal("the invite key worked twice")
	}

	// 5. Feedback with a screenshot, read back by the owner.
	fb := fmt.Sprintf(`{"note":"the button overlaps","pageUrl":"%s/page","screenshotPngBase64":%q}`, appURL, pngBase64())
	sam.must(t, "POST", appURL+"/__ovenlight/feedback", fb, nil, 200)
	out = r.svOK("feedback", "--json")
	var items []struct {
		ID, From, Note, ScreenshotPath string
	}
	json.Unmarshal([]byte(out), &items)
	if len(items) != 1 || items[0].From != "Sam" || items[0].ScreenshotPath == "" {
		t.Fatalf("feedback: %s", out)
	}
	if shot := owner.must(t, "GET", adminURL+"/v1/feedback/"+items[0].ID+"/screenshot", "", nil, 200); !strings.HasPrefix(shot, "\x89PNG") {
		t.Fatal("screenshot through the admin API isn't a PNG")
	}
	if out := r.svOK("status"); !strings.Contains(out, "Feedback: 1") || !strings.Contains(out, "1 guests") {
		t.Fatalf("status:\n%s", out)
	}

	// 5b. A second app shared with Sam: Sam's phone gets its tag too (no second device),
	// claims it with the new key as the same user, and revoking it takes only that tag away.
	r.svOK("publish", "--port", fmt.Sprint(echoApp(t)), "--name", "Notes", "--slug", "notes", "--shareable") // a port of its own
	notesURL := "https://notes." + baseDomain
	out, err = r.sv(true, "", "share", "notes", "--to", "Sam", "--existing")
	if err != nil || !strings.Contains(out, "can reach Notes now") {
		t.Fatalf("share notes: %v\n%s", err, out)
	}
	notesKey := keyFromLink(t, linkPattern.FindString(out))
	samTags := func() string {
		for _, n := range r.nodes() {
			if n.GivenName == "sam-iphone" {
				return strings.Join(n.tags(), ",")
			}
		}
		return "(gone)"
	}
	if tags := samTags(); !strings.Contains(tags, "tag:ovenlight-guest-echo") || !strings.Contains(tags, "tag:ovenlight-guest-notes") {
		t.Fatalf("sam-iphone tags after the second invite: %s", tags)
	}
	sam.must(t, "POST", notesURL+"/__ovenlight/claim", fmt.Sprintf(`{"key":%q}`, notesKey), nil, 200)
	if id := headersOf(t, sam.must(t, "GET", notesURL+"/page", "", nil, 200)).Get("Ovenlight-User-Id"); id != samID {
		t.Fatalf("Sam is %s on notes, %s on echo", id, samID)
	}
	out = r.svOK("revoke", "Sam", "--app", "notes")
	if !strings.Contains(out, "Removed Sam from notes") || !strings.Contains(out, "keeps your other apps") {
		t.Fatalf("revoke notes:\n%s", out)
	}
	if tags := samTags(); strings.Contains(tags, "notes") || !strings.Contains(tags, "tag:ovenlight-guest-echo") {
		t.Fatalf("sam-iphone tags after revoking notes: %s", tags)
	}
	eventually(t, 20*time.Second, "notes to be closed to Sam at the network layer", func() (bool, string) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		c, err := sam.srv.Dial(ctx, "tcp", "notes."+baseDomain+":443")
		if err == nil {
			c.Close()
		}
		return err != nil, fmt.Sprint(err)
	})
	sam.must(t, "GET", appURL+"/", "", nil, 200)
	t.Log("second app: tag union on one device, then only that tag removed")

	// 6. Revoke: an open stream ends, the device is deleted, requests fail.
	req, _ := http.NewRequest("GET", appURL+"/stream", nil)
	resp, err := sam.hc.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	streamDone := make(chan time.Time, 1)
	go func() {
		br := bufio.NewReader(resp.Body)
		for {
			if _, err := br.ReadString('\n'); err != nil {
				streamDone <- time.Now()
				return
			}
		}
	}()
	time.Sleep(500 * time.Millisecond)
	revokedAt := time.Now()
	out = r.svOK("revoke", "Sam")
	if !strings.Contains(out, "Removed Sam from echo") || !strings.Contains(out, "Deleted device") {
		t.Fatalf("revoke:\n%s", out)
	}
	select {
	case at := <-streamDone:
		t.Logf("guest's open stream ended %v after revoke", at.Sub(revokedAt).Round(time.Millisecond))
	case <-time.After(5 * time.Second):
		t.Fatal("the guest's open stream survived the revoke")
	}
	resp.Body.Close()
	eventually(t, 20*time.Second, "the revoked guest to be cut off", func() (bool, string) {
		code, body, err := sam.do("GET", appURL+"/", "", nil)
		return err != nil || code == 403, fmt.Sprintf("%d %s", code, body)
	})
	for _, n := range r.nodes() {
		if n.GivenName == "sam-iphone" {
			t.Fatal("the guest device is still in the tailnet")
		}
	}

	// 7. An invite from the owner's phone (the Ovenlight path), then the device is deleted
	// elsewhere: the sync notices.
	out = owner.must(t, "POST", adminURL+"/v1/invites", `{"to":"Kim","app":"echo"}`, map[string]string{"X-Ovenlight-Request": "1"}, 200)
	var shared struct {
		Link string `json:"link"`
	}
	json.Unmarshal([]byte(out), &shared)
	kim, err := r.join("kim-iphone", keyFromLink(t, shared.Link))
	if err != nil {
		t.Fatal(err)
	}
	kim.must(t, "POST", appURL+"/__ovenlight/claim", fmt.Sprintf(`{"key":%q}`, keyFromLink(t, shared.Link)), nil, 200)
	kim.must(t, "GET", appURL+"/", "", nil, 200)
	for _, n := range r.nodes() {
		if n.GivenName == "kim-iphone" {
			r.hs("nodes", "delete", "-i", n.ID.String(), "--force")
		}
	}
	out, _ = r.sv(false, "", "guests", "--sync", "--all")
	if !strings.Contains(out, "device deleted from the tailnet") {
		t.Fatalf("guests after sync:\n%s", out)
	}
	if code, _, err := kim.do("GET", appURL+"/", "", nil); err == nil && code == 200 {
		t.Fatal("a guest whose device was deleted still gets in")
	}

	// 8. Doctor sees a tagged node and the admin API.
	out, _ = r.sv(false, "", "doctor", "--json")
	if !strings.Contains(out, `"id": "shareable-node"`) || !strings.Contains(out, "runs as a tagged app node") || !strings.Contains(out, `"id": "admin-api"`) {
		t.Fatalf("doctor:\n%s", out)
	}
}
