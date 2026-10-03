package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi/tsapitest"
)

var (
	update    = flag.Bool("update", false, "rewrite testdata/wire from what the connector answers")
	allowDrop = flag.Bool("allow-drop", false, "with -update, let a file lose keys it has: only when no shipped build of Ovenlight needs them")
)

var (
	// wireT0 has nanoseconds and an offset, as Go writes time.Now().
	wireT0    = time.Date(2026, 9, 28, 12, 4, 5, 123456789, time.FixedZone("", -7*3600))
	wireOwner = Identity{Login: "alex@example.com", Name: "Alex", DeviceID: "nAlexMac", DeviceName: "alex-macbook"}
)

// wireDaemon is testDaemon with wireOwner as the owner.
func wireDaemon(t *testing.T) (*daemon, *tsapitest.Fake) {
	d, f := testDaemon(t)
	d.cfg.Owner, d.cfg.OwnerLabel = wireOwner.Login, wireOwner.Name
	n := d.nodes["coach"]
	n.setOwner(d.cfg.Owner)
	whoIsAs(n, wireOwner)
	return d, f
}

// adminCall sends one request to the node's admin API as the node's WhoIs caller.
func adminCall(n *appNode, method, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, "https://"+n.DNSName()+":8443"+path, strings.NewReader(body))
	if method != http.MethodGet {
		req.Header.Set(adminHeader, "1")
	}
	rec := httptest.NewRecorder()
	n.adminHandler().ServeHTTP(rec, req)
	return rec
}

// encoded is v as writeJSON sends it.
func encoded(v any) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	writeJSON(rec, http.StatusOK, v)
	return rec
}

// wireFixture is a daemon whose records hold, as stored (key hashes and all), a guest
// with two devices, a removed guest, an open and a review invite, an unclaimed device
// and feedback with and without a screenshot.
func wireFixture(t *testing.T) *daemon {
	d, _ := wireDaemon(t)
	day := 24 * time.Hour
	removed := wireT0.Add(2 * day)
	claimed := wireT0.Add(-day)
	invite := func(id, to, person string, created time.Time, ttl time.Duration) Invite {
		return Invite{ID: id, To: to, Person: person, App: "coach", State: inviteSent, RequestedBy: "ovenlight", Created: created,
			Expires: created.Add(ttl), KeyID: "kInvite" + id, KeyHash: hashKey("key-" + id)}
	}
	samPhone := invite("a1b2c3d4e5", "Sam", "7c2e91a4d0", wireT0.Add(-2*day), inviteTTL)
	samPhone.State, samPhone.ClaimedBy, samPhone.ClaimedAt, samPhone.KeyID = inviteClaimed, "nSamPhone", &claimed, ""
	kim := invite("c3d4e5f6a7", "Kim", "e5f6a7b8c9", wireT0.Add(3*day), inviteTTL)
	kim.Devices = []string{"nKimMac"}
	review := invite("d4e5f6a7b8", "App Review", "0a1b2c3d4e", wireT0.Add(3*day), reviewInviteTTL)
	review.RequestedBy, review.Review = "terminal", true
	lee := invite("f6a7b8c9d0", "Lee", "5d6e7f8091", wireT0.Add(-2*day), inviteTTL)
	lee.State, lee.ClaimedBy, lee.ClaimedAt, lee.KeyID = inviteClaimed, "nLeePhone", &claimed, ""
	d.sh.st = sharingState{
		Invites: []Invite{samPhone, kim, review, lee},
		Guests: []Guest{
			{DeviceID: "nSamPhone", Person: "7c2e91a4d0", Name: "Sam", App: "coach", DeviceName: "sam-iphone", InviteID: samPhone.ID, ClaimedAt: claimed},
			{DeviceID: "nSamPad", Person: "7c2e91a4d0", Name: "Sam", App: "coach", DeviceName: "sam-ipad", InviteID: "b2c3d4e5f6", ClaimedAt: wireT0},
			{DeviceID: "nLeePhone", Person: "5d6e7f8091", Name: "Lee", App: "coach", DeviceName: "lee-iphone", InviteID: lee.ID, ClaimedAt: claimed,
				RemovedAt: &removed, RemovedWhy: "removed by the owner", DeviceGone: true},
		},
		Feedback: []Feedback{
			{ID: "e1f2a3b4c5", At: wireT0, App: "coach", From: "Alex", Role: "owner", UserID: "alex@example.com", Device: "alex-iphone",
				Note: "The timer skips a second on the last question."},
			{ID: "f2a3b4c5d6", At: wireT0.Add(day), App: "coach", From: "Sam", Role: "guest", UserID: "guest:7c2e91a4d0", Device: "sam-iphone",
				PageURL: "https://coach.tail1.ts.net/practice?round=2", Note: "The Next button hides behind the keyboard.",
				Screenshot: "f2a3b4c5d6.png", ScreenshotBytes: 48213},
		},
	}
	guest := []string{policy.GuestTag("coach")}
	d.sh.unclaimed = []tsapi.Device{{ID: "5550002", NodeID: "nStray", Name: "iphone.tail1.ts.net", Hostname: "iphone",
		User: "tagged-devices", Tags: guest, Authorized: true, Created: "2026-09-29T08:12:44Z", OS: "iOS", Addresses: []string{"100.101.102.104"}}}
	d.sh.lastSync = wireT0.Add(3 * day)
	d.sh.syncErr = "tailscale api: GET /api/v2/tailnet/-/devices: 401 Unauthorized: API token invalid; run `ovenlight auth set` with a new token"
	if err := d.sh.saveLocked(); err != nil { // GET /v1/feedback reads the file
		t.Fatal(err)
	}
	return d
}

// TestWireFiles builds each answer Ovenlight decodes from fixed records, through the
// handlers wherever the answer doesn't depend on the clock, and compares it with
// testdata/wire/<name>.json. Ovenlight's tests decode the same files, so a change here
// is a change to the app's contract: update them with `go test -run TestWireFiles -update`
// only when Ovenlight still reads them.
func TestWireFiles(t *testing.T) {
	d := wireFixture(t)
	n := d.nodes["coach"]
	got := map[string]*httptest.ResponseRecorder{}

	got["apps"] = appsAnswer(t, d)
	got["guests"] = adminCall(n, "GET", "/v1/guests", "")
	got["feedback"] = adminCall(n, "GET", "/v1/feedback?limit=100", "")
	got["feedback-sent"] = feedbackSentAnswer(t, d)
	got["invite-canceled"] = adminCall(n, "DELETE", "/v1/invites/c3d4e5f6a7", "")

	// Minting and removing stamp the time, so these start from their results.
	kim := d.sh.st.Invites[1]
	kim.State, kim.Created, kim.Expires = inviteSent, wireT0, wireT0.Add(inviteTTL)
	link := InviteLink{Control: defaultControl, Key: "tskey-auth-kExample1CNTRL-ExampleSecretNotReal", Owner: "Alex", App: "coach",
		Invite: kim.ID, Host: "coach.tail1.ts.net", Name: "Interview Coach", To: "Kim"}
	share := shareResult{Invite: kim, Link: link.String(), AppLink: link.AppLink(), AppName: "Interview Coach", Owner: "Alex"}
	share.Message = inviteMessage(&share)
	got["invite-created"] = encoded(share.view())
	at := wireT0.Add(4 * 24 * time.Hour)
	removed := slices.Clone(d.sh.st.Guests[:2])
	for i := range removed {
		removed[i].RemovedAt, removed[i].RemovedWhy = &at, "removed by the owner"
	}
	canceled := Invite{ID: "b3c4d5e6f7", To: "Sam", Person: "7c2e91a4d0", App: "coach", State: inviteCanceled, RequestedBy: "ovenlight",
		Created: wireT0.Add(3 * 24 * time.Hour), Expires: wireT0.Add(4 * 24 * time.Hour)}
	got["guest-removed"] = encoded(revokeResult{Removed: removed, Deleted: []string{"nSamPhone"}, Retagged: []string{"nSamPad"},
		Canceled: []Invite{canceled}}.view())
	got["guest-removed-with-errors"] = encoded(revokeResult{Removed: removed[:1], Errors: []string{
		"blocked here, but deleting device nSamPhone from the tailnet failed (retried every few minutes): tailscale api: DELETE /api/v2/device/nSamPhone: 500 Internal Server Error"}}.view())

	whoami := func(who Identity) *httptest.ResponseRecorder {
		whoIsAs(n, who)
		rec := httptest.NewRecorder()
		n.ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net"+whoamiPath, nil))
		return rec
	}
	got["whoami-owner"] = whoami(wireOwner)
	got["whoami-guest"] = whoami(Identity{Tagged: true, Tags: []string{guestTag}, DeviceID: "nSamPhone", DeviceName: "sam-iphone"})
	got["site-manifest"] = siteManifestAnswer(t, n)

	for name, rec := range wireErrors(t) {
		got[name] = rec
	}

	for name, rec := range got {
		checkWire(t, name, rec.Body.Bytes())
	}
	// Nothing stale is left for Ovenlight's tests to decode.
	files, _ := filepath.Glob(filepath.Join("testdata", "wire", "*.json"))
	for _, f := range files {
		if _, ok := got[strings.TrimSuffix(filepath.Base(f), ".json")]; !ok {
			t.Errorf("%s is no answer the connector gives; delete it", f)
		}
	}
}

// appsAnswer is GET /v1/apps with the fixture's app serving and answering on its port,
// next to an unshared app whose node needs a login and whose port nothing answers on.
func appsAnswer(t *testing.T, d *daemon) *httptest.ResponseRecorder {
	listen := func() net.Listener {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { ln.Close() })
		return ln
	}
	up, down := listen(), listen()
	down.Close()
	n := d.nodes["coach"]
	coach := d.cfg.Apps[0] // the port is checked from the config
	coach.Port = up.Addr().(*net.TCPAddr).Port
	n.mu.Lock()
	n.serving = true
	n.mu.Unlock()
	notes := App{Name: "Notes", Slug: "notes", Port: down.Addr().(*net.TCPAddr).Port}
	d.cfg.Apps = []App{coach, notes}
	d.nodes["notes"] = newAppNode(notes, d.stateDir, devOptions{}, d)
	d.nodes["notes"].backend = "NeedsLogin"
	return adminCall(n, "GET", "/v1/apps", "")
}

// feedbackSentAnswer is the answer to the owner's feedback, with the random ID the
// connector gave it fixed.
func feedbackSentAnswer(t *testing.T, d *daemon) *httptest.ResponseRecorder {
	body, _ := json.Marshal(feedbackRequest{Note: "The timer skips a second on the last question."})
	rec := httptest.NewRecorder()
	d.handleFeedback(rec, httptest.NewRequest("POST", feedbackPath, bytes.NewReader(body)), caller{Identity: wireOwner, Role: RoleOwner}, d.cfg.Apps[0])
	d.sh.mu.Lock()
	id := d.sh.st.Feedback[len(d.sh.st.Feedback)-1].ID
	d.sh.mu.Unlock()
	if rec.Code != http.StatusOK || !bytes.Contains(rec.Body.Bytes(), []byte(id)) {
		t.Fatalf("feedback: %d %s", rec.Code, rec.Body)
	}
	rec.Body = bytes.NewBuffer(bytes.ReplaceAll(rec.Body.Bytes(), []byte(id), []byte("a3b4c5d6e7")))
	return rec
}

// siteManifestAnswer is the node's /.well-known/ovenlight.json, read from an app whose
// page has a web manifest with an icon and a theme color.
func siteManifestAnswer(t *testing.T, n *appNode) *httptest.ResponseRecorder {
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			w.Write([]byte(`<html><head><link rel="manifest" href="/app.webmanifest"></head></html>`))
		case "/app.webmanifest":
			w.Write([]byte(`{"theme_color": "#1f6feb", "icons": [{"src": "/icon-512.png", "sizes": "512x512", "type": "image/png"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(app.Close)
	a := n.App()
	a.Port = app.Listener.Addr().(*net.TCPAddr).Port
	n.setApp(a)
	whoIsAs(n, wireOwner)
	rec := httptest.NewRecorder()
	n.ServeHTTP(rec, httptest.NewRequest("GET", "https://coach.tail1.ts.net"+siteManifestPath, nil))
	return rec
}

// claimAnswers claims Sam's invite, then has the refusals answered: the invite used on
// another device, another person's invite on Sam's phone, a device without the tag.
func claimAnswers(t *testing.T) map[string]*httptest.ResponseRecorder {
	d, _ := wireDaemon(t)
	d.sh.st.Invites = []Invite{sentInvite("i1", "Sam", "coach", "key-sam"), sentInvite("i2", "Kim", "coach", "key-kim")}
	for i := range d.sh.st.Invites {
		d.sh.st.Invites[i].Expires = time.Now().Add(time.Hour)
	}
	post := func(who Identity, key string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		d.handleClaim(rec, httptest.NewRequest(http.MethodPost, claimPath, strings.NewReader(`{"key": "`+key+`"}`)), who, d.cfg.Apps[0])
		return rec
	}
	out := map[string]*httptest.ResponseRecorder{"claim": post(samDev, "key-sam")}
	waitFor(t, func() bool { d.sh.mu.Lock(); defer d.sh.mu.Unlock(); return d.sh.st.invite("i1").KeyID == "" }, "the spent key to be dropped")
	for name, rec := range map[string]*httptest.ResponseRecorder{
		"error-" + codeUsedElsewhere: post(kimDev, "key-sam"),
		"error-" + codeOtherPerson:   post(samDev, "key-kim"),
		"error-" + codeNotInvited:    post(Identity{Tagged: true, DeviceID: "nX"}, "key-kim"),
	} {
		out[name] = rec
	}
	return out
}

// wireErrors has the claim answered, and each JSON error code where the connector
// answers it.
func wireErrors(t *testing.T) map[string]*httptest.ResponseRecorder {
	out := claimAnswers(t)
	d, _ := wireDaemon(t)
	n := d.nodes["coach"]
	whoIsAs(n, Identity{Login: "lee@example.com", Name: "Lee", DeviceID: "nLee"})
	out["error-"+codeOwnerOnly] = adminCall(n, "GET", "/v1/apps", "")
	whoIsAs(n, wireOwner)
	out["error-"+codeNotFound] = adminCall(n, "DELETE", "/v1/invites/0000000000", "")
	out["error-"+codeBadRequest] = adminCall(n, "POST", "/v1/invites", "Sam")
	feedback := func(note string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(feedbackRequest{Note: note})
		rec := httptest.NewRecorder()
		d.handleFeedback(rec, httptest.NewRequest("POST", feedbackPath, bytes.NewReader(body)), caller{Identity: wireOwner, Role: RoleOwner}, d.cfg.Apps[0])
		return rec
	}
	out["error-"+codeTooLarge] = feedback(strings.Repeat("a", maxNote+1))
	done, _ := limiter.begin(wireOwner.Login)
	out["error-"+codeRateLimited] = feedback("hi")
	done()
	for name, rec := range out {
		if name == "claim" {
			continue
		}
		var e errorView
		if err := json.Unmarshal(rec.Body.Bytes(), &e); err != nil || "error-"+e.Code != name || rec.Header().Get(errorHeader) != e.Code {
			t.Errorf("%s: %d %s, header %q", name, rec.Code, rec.Body, rec.Header().Get(errorHeader))
		}
	}
	return out
}

// checkWire compares an answer, indented, with its file, or writes the file with -update.
func checkWire(t *testing.T, name string, body []byte) {
	t.Helper()
	var buf bytes.Buffer
	if err := json.Indent(&buf, body, "", "  "); err != nil {
		t.Errorf("%s: not JSON: %v: %s", name, err, body)
		return
	}
	path := filepath.Join("testdata", "wire", name+".json")
	if *update {
		if old, err := os.ReadFile(path); err == nil && !*allowDrop {
			if gone := droppedKeys(old, buf.Bytes()); len(gone) > 0 {
				t.Errorf("%s would lose %s, which shipped builds of Ovenlight may need; if none does, run with -update -allow-drop", path, strings.Join(gone, ", "))
				return
			}
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Errorf("%s: %v (go test -run TestWireFiles -update writes it)", name, err)
		return
	}
	if !bytes.Equal(want, buf.Bytes()) {
		t.Errorf("%s isn't what the connector answers now; if the change is meant and Ovenlight still decodes it, run go test -run TestWireFiles -update. Now:\n%s", path, buf.Bytes())
	}
}

// keyPaths lists the object keys in a JSON document by path, as invite.keyId, with []
// for any element of a list: invites[] and invites[].keyId.
func keyPaths(data []byte) (map[string]bool, error) {
	var doc any
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, err
	}
	paths := map[string]bool{}
	var walk func(prefix string, v any)
	walk = func(prefix string, v any) {
		switch v := v.(type) {
		case map[string]any:
			for k, child := range v {
				p := k
				if prefix != "" {
					p = prefix + "." + k
				}
				paths[p] = true
				walk(p, child)
			}
		case []any:
			for _, child := range v {
				paths[prefix+"[]"] = true
				walk(prefix+"[]", child)
			}
		}
	}
	walk("", doc)
	return paths, nil
}

// droppedKeys lists the key paths old has and now doesn't.
func droppedKeys(old, now []byte) []string {
	before, err := keyPaths(old)
	if err != nil {
		return nil // an unreadable file holds nothing to keep
	}
	after, _ := keyPaths(now)
	var gone []string
	for p := range before {
		if !after[p] {
			gone = append(gone, p)
		}
	}
	slices.Sort(gone)
	return gone
}

func TestDroppedKeys(t *testing.T) {
	old := `{"invite": {"id": "a", "keyId": null}, "devices": [{"id": "1", "os": "iOS"}, {"id": "2"}]}`
	if got := droppedKeys([]byte(old), []byte(`{"invite": {"id": "a", "keyId": "k"}, "devices": [{"id": "1", "os": "iOS"}], "new": 1}`)); len(got) != 0 {
		t.Errorf("nothing dropped: %v", got)
	}
	if got := droppedKeys([]byte(old), []byte(`{"invite": {"id": "a"}, "devices": [{"id": "1"}]}`)); !slices.Equal(got, []string{"devices[].os", "invite.keyId"}) {
		t.Errorf("dropped: %v", got)
	}
}

// Storage-only fields never reach the wire: the stored invite's key and devices, a
// tailnet device's user and addresses.
func TestWireDropsStorageFields(t *testing.T) {
	invite := func(at string) []string { return []string{at + "keyId", at + "keyHash", at + "devices"} }
	device := func(at string) []string { return []string{at + "user", at + "addresses"} }
	for name, fields := range map[string][]string{
		"guests":          slices.Concat(invite("invites[]."), device("unclaimed[].")),
		"invite-created":  invite("invite."), // its own devices are the person's, by name
		"invite-canceled": invite(""),
		"guest-removed":   invite("canceledInvites[]."),
	} {
		data, err := os.ReadFile(filepath.Join("testdata", "wire", name+".json"))
		if err != nil {
			t.Fatal(err)
		}
		paths, err := keyPaths(data)
		if err != nil {
			t.Fatal(err)
		}
		for _, field := range fields {
			if i := strings.LastIndex(field, "."); i > 0 && !paths[field[:i]] {
				t.Errorf("%s has nothing at %s to check", name, field[:i])
			}
			if paths[field] {
				t.Errorf("%s has %s", name, field)
			}
		}
	}
}

// guests --json is what GET /v1/guests answers.
func TestGuestsJSONIsTheWireView(t *testing.T) {
	d := wireFixture(t)
	var cli, api bytes.Buffer
	if err := writeGuestsJSON(&cli, d.guestList()); err != nil {
		t.Fatal(err)
	}
	json.Compact(&api, adminCall(d.nodes["coach"], "GET", "/v1/guests", "").Body.Bytes())
	var compact bytes.Buffer
	json.Compact(&compact, cli.Bytes())
	if !bytes.Equal(compact.Bytes(), api.Bytes()) {
		t.Errorf("guests --json:\n%s\nGET /v1/guests:\n%s", compact.Bytes(), api.Bytes())
	}
}
