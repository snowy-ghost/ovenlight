package main

import (
	"bytes"
	"encoding/base64"
	"errors"
	"fmt"
	"image"
	"image/png"
	"net/http"
	"net/url"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// The tags of the app most tests share, "coach".
var (
	appTag   = policy.AppTag("coach")
	guestTag = policy.GuestTag("coach")
)

var (
	t0      = time.Date(2026, 9, 28, 12, 0, 0, 0, time.UTC)
	samDev  = Identity{Tagged: true, Tags: []string{guestTag}, DeviceID: "nSam", DeviceName: "sam-iphone"}
	kimDev  = Identity{Tagged: true, Tags: []string{guestTag}, DeviceID: "nKim", DeviceName: "kim-iphone"}
	ownerID = Identity{Login: "alex@example.com", Name: "Alex", DeviceID: "nMBP"}
)

func sentInvite(id, to, app, key string) Invite {
	return Invite{ID: id, To: to, Person: "p-" + strings.ToLower(to), App: app, State: inviteSent, Created: t0, Expires: t0.Add(inviteTTL), KeyID: "k" + id, KeyHash: hashKey(key)}
}

func match(t *testing.T, st *sharingState, ref, app string) []int {
	t.Helper()
	idx, _, err := st.matchGuests(ref, app, false)
	if err != nil {
		t.Fatal(err)
	}
	return idx
}

func TestDecideRoleWithGuests(t *testing.T) {
	owner := "alex@example.com"
	sam := &Guest{DeviceID: "nSam", Person: "nSam", Name: "Sam", App: "coach"}
	removedAt := t0
	removed := &Guest{DeviceID: "nSam", Person: "nSam", Name: "Sam", App: "coach", RemovedAt: &removedAt}
	cases := []struct {
		name   string
		caller Identity
		guest  *Guest
		want   Role
	}{
		{"owner's untagged device", ownerID, nil, RoleOwner},
		{"claimed guest", samDev, sam, RoleGuest},
		{"guest device with no claim", samDev, nil, RoleUnclaimed},
		{"removed guest", samDev, removed, RoleUnclaimed},
		{"claim of another device", kimDev, sam, RoleUnclaimed},
		{"tagged, not a guest", Identity{Tagged: true, Tags: []string{appTag}, DeviceID: "nApp"}, nil, RoleDenied},
		{"guest tag but no device ID", Identity{Tagged: true, Tags: []string{guestTag}}, sam, RoleUnclaimed},
		{"guest of another app", Identity{Tagged: true, Tags: []string{policy.GuestTag("notes")}, DeviceID: "nSam"}, sam, RoleDenied},
		{"another user", Identity{Login: "sam@example.com", DeviceID: "nX"}, nil, RoleDenied},
		{"owner login on a tagged device", Identity{Login: owner, Tagged: true, Tags: []string{"tag:server"}}, nil, RoleDenied},
	}
	for _, c := range cases {
		if got := decideRole(c.caller, owner, c.guest, "coach"); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
	if decideRole(ownerID, "", nil, "coach") != RoleDenied {
		t.Error("no owner configured must deny untagged callers")
	}
}

func TestGuestHeaders(t *testing.T) {
	h := http.Header{}
	h.Set("Ovenlight-Role", "owner")
	h.Set("Tailscale-User-Login", "alex@example.com")
	h.Set("Ovenlight-User-Id", "alex@example.com")
	g := &Guest{DeviceID: "nSam", Person: "nSam", Name: "Sam Q", App: "coach"}
	applyIdentity(h, caller{Identity: samDev, Role: RoleGuest, Guest: g})
	want := http.Header{"Ovenlight-Role": {"guest"}, "Ovenlight-User": {"Sam Q"}, "Ovenlight-User-Id": {"guest:nSam"}}
	if !reflect.DeepEqual(h, want) {
		t.Fatalf("headers = %v, want %v", h, want)
	}
	// Anyone else gets no identity at all, only the forged headers removed.
	h = http.Header{"Ovenlight-Role": {"owner"}}
	applyIdentity(h, caller{Identity: samDev, Role: RoleUnclaimed})
	if len(h) != 0 {
		t.Errorf("unclaimed caller got headers %v", h)
	}
}

func TestClaimInvite(t *testing.T) {
	st := sharingState{Invites: []Invite{sentInvite("i1", "Sam", "coach", "key-sam"), sentInvite("i2", "Kim", "coach", "key-kim")}}

	if _, _, _, err := st.claimInvite(hashKey("wrong"), "coach", samDev, t0); !errors.Is(err, errNotInvited) {
		t.Errorf("wrong key: %v", err)
	}
	if _, _, _, err := st.claimInvite(hashKey("key-sam"), "notes", samDev, t0); !errors.Is(err, errNotInvited) {
		t.Errorf("key for another app: %v", err)
	}
	if _, _, _, err := st.claimInvite(hashKey("key-sam"), "coach", ownerID, t0); !errors.Is(err, errNotInvited) {
		t.Errorf("untagged caller: %v", err)
	}

	inv, g, fresh, err := st.claimInvite(hashKey("key-sam"), "coach", samDev, t0)
	if err != nil || !fresh || g.Name != "Sam" || g.DeviceID != "nSam" || inv.State != inviteClaimed || inv.ClaimedBy != "nSam" {
		t.Fatalf("claim: %+v %+v %v %v", inv, g, fresh, err)
	}
	// Same device again: fine, not fresh. Another device with the same key: refused.
	if _, _, fresh, err := st.claimInvite(hashKey("key-sam"), "coach", samDev, t0); err != nil || fresh {
		t.Errorf("repeat claim: fresh=%v err=%v", fresh, err)
	}
	if _, _, _, err := st.claimInvite(hashKey("key-sam"), "coach", kimDev, t0); !errors.Is(err, errInviteUsed) {
		t.Errorf("stolen key: %v", err)
	}
	if len(st.Guests) != 1 {
		t.Fatalf("guests = %v", st.Guests)
	}

	// Removed, then presenting the spent key again doesn't bring the guest back.
	st.removeGuests(match(t, &st, "Sam", ""), "test", t0)
	if _, _, _, err := st.claimInvite(hashKey("key-sam"), "coach", samDev, t0); !errors.Is(err, errNotInvited) {
		t.Errorf("claim after removal: %v", err)
	}

	// Past expiry plus grace: refused. Canceled: refused.
	if _, _, _, err := st.claimInvite(hashKey("key-kim"), "coach", kimDev, t0.Add(inviteTTL+claimGrace+time.Minute)); !errors.Is(err, errNotInvited) {
		t.Errorf("expired: %v", err)
	}
	st.Invites[1].State = inviteCanceled
	if _, _, _, err := st.claimInvite(hashKey("key-kim"), "coach", kimDev, t0); !errors.Is(err, errNotInvited) {
		t.Errorf("canceled: %v", err)
	}
	st.Invites[1].State = inviteSent
	if _, _, fresh, err := st.claimInvite(hashKey("key-kim"), "coach", kimDev, t0.Add(inviteTTL+time.Minute)); err != nil || !fresh {
		t.Errorf("claim within the grace period: %v", err)
	}
	// Kim's device holds coach now. An invite for someone else, presented from it, is
	// refused and stays usable; one for Kim again (any case) is taken.
	st.Invites = append(st.Invites, sentInvite("i3", "Lee", "coach", "key-lee"), sentInvite("i4", "KIM", "coach", "key-kim2"))
	if _, _, _, err := st.claimInvite(hashKey("key-lee"), "coach", kimDev, t0); !errors.Is(err, errOtherPerson) {
		t.Errorf("another person's invite from a guest device: %v", err)
	}
	if st.Invites[2].State != inviteSent || st.Invites[2].ClaimedBy != "" {
		t.Errorf("the refused invite was used up: %+v", st.Invites[2])
	}
	// Kim's invite again, from the device that already holds coach: fine, and the invite
	// stays for another device of Kim's.
	if _, g, fresh, err := st.claimInvite(hashKey("key-kim2"), "coach", kimDev, t0); err != nil || fresh || g.Name != "Kim" || len(st.Guests) != 2 {
		t.Errorf("second invite for the same person: %+v %v %v, guests %d", g, fresh, err, len(st.Guests))
	}
	if st.Invites[3].State != inviteSent {
		t.Errorf("the invite for another device was used up: %+v", st.Invites[3])
	}
	// Kim's device reaches every app Kim has an open invite to, to claim it there.
	st.Invites = append(st.Invites, sentInvite("i5", "Kim", "notes", "key-kim3"))
	if got := st.guestTags("nKim"); !reflect.DeepEqual(got, []string{guestTag, policy.GuestTag("notes")}) {
		t.Errorf("Kim's device is tagged %v", got)
	}
	// Taken off coach, it doesn't: Kim's open coach invite is for another device.
	st.Guests = append(st.Guests, Guest{DeviceID: "nKim", Person: "p-kim", Name: "Kim", App: "notes"})
	st.removeGuests(match(t, &st, "nKim", "coach"), "owner", t0)
	if got := st.guestTags("nKim"); !reflect.DeepEqual(got, []string{policy.GuestTag("notes")}) {
		t.Errorf("after removal from coach Kim's device is tagged %v", got)
	}
	if _, _, _, err := st.claimInvite(hashKey("key-kim2"), "coach", kimDev, t0); !errors.Is(err, errNotInvited) {
		t.Errorf("a removed device claimed coach again: %v", err)
	}
	if hashKey("key-kim") == "key-kim" || len(hashKey("x")) != 64 {
		t.Error("hashKey must be a SHA-256 hex digest")
	}
}

func TestRevokeKeepsDevicesThatHoldAnotherApp(t *testing.T) {
	st := sharingState{Guests: []Guest{
		{DeviceID: "nSam", Person: "nSam", Name: "Sam", App: "coach"},
		{DeviceID: "nSam", Person: "nSam", Name: "Sam", App: "notes"},
		{DeviceID: "nKim", Person: "nKim", Name: "Kim", App: "coach"},
	}}
	st.removeGuests(match(t, &st, "sam", "coach"), "owner", t0)
	changes := st.changesFor([]string{"nSam"})
	if !reflect.DeepEqual(changes, []deviceChange{{DeviceID: "nSam", Tags: []string{policy.GuestTag("notes")}}}) {
		t.Errorf("Sam still holds notes, so the device must stay with only its tag: %+v", changes)
	}
	if st.Guests[0].Active() || !st.Guests[1].Active() {
		t.Errorf("wrong records removed: %+v", st.Guests)
	}
	st.removeGuests(match(t, &st, "nSam", ""), "owner", t0)
	changes = st.changesFor([]string{"nSam"})
	if len(changes) != 1 || changes[0].DeviceID != "nSam" || !changes[0].delete() {
		t.Errorf("changes = %+v, want the device deleted", changes)
	}
	if got := match(t, &st, "Sam", ""); len(got) != 0 {
		t.Errorf("removed guests still match: %v", got)
	}
}

func TestReconcile(t *testing.T) {
	removedAt := t0
	st := sharingState{
		Guests: []Guest{
			{DeviceID: "nSam", Name: "Sam", App: "coach"},                        // device still there
			{DeviceID: "nKim", Name: "Kim", App: "coach"},                        // deleted in the admin console
			{DeviceID: "nPat", Name: "Pat", App: "coach", RemovedAt: &removedAt}, // removed, device lingers
		},
		Invites: []Invite{sentInvite("old", "Lee", "coach", "k1"), sentInvite("new", "Max", "coach", "k2")},
	}
	st.Invites[0].Expires = t0.Add(-2 * time.Hour)
	devices := []tsapi.Device{
		{NodeID: "nSam", Tags: []string{guestTag}, Authorized: true},
		{NodeID: "nPat", Tags: []string{guestTag}, Authorized: true},
		{NodeID: "nNew", Name: "max-iphone", Tags: []string{guestTag}, Authorized: false},
		{NodeID: "nMBP", User: "alex@example.com", Authorized: true},
	}
	res := st.reconcile(devices, []string{"coach"}, t0)
	if len(res.Removed) != 1 || !strings.Contains(res.Removed[0], "Kim") || st.Guests[1].Active() || !st.Guests[1].DeviceGone {
		t.Errorf("removed = %v, guests %+v", res.Removed, st.Guests)
	}
	if changes := st.changesFor(res.Devices); len(changes) != 1 || changes[0].DeviceID != "nPat" || !changes[0].delete() {
		t.Errorf("device changes = %+v, want nPat deleted", changes)
	}
	if len(res.Expired) != 1 || st.Invites[0].State != inviteExpired || st.Invites[1].State != inviteSent {
		t.Errorf("expired = %v, invites %+v", res.Expired, st.Invites)
	}
	if len(res.Unclaimed) != 1 || res.Unclaimed[0].NodeID != "nNew" {
		t.Errorf("unclaimed = %v", res.Unclaimed)
	}
	if !st.Guests[0].Active() {
		t.Error("a present guest was removed")
	}
	// Running again changes nothing more.
	if again := st.reconcile(devices, []string{"coach"}, t0); again.changed() {
		t.Errorf("second reconcile changed %+v", again)
	}
}

func TestInviteLinkRoundTrip(t *testing.T) {
	l := InviteLink{Control: "https://controlplane.tailscale.com", Key: "tskey-auth-kAbc-123&x=y", Owner: "Alex", App: "coach",
		Invite: "a1b2c3", Host: "coach.tailabc123.ts.net", Name: "Interview Coach", To: "Sam Q"}
	s := l.String()
	if !strings.HasPrefix(s, "https://ovenlight.app/join#") || !strings.Contains(s, "v=1") {
		t.Fatalf("link = %s", s)
	}
	// The key rides in the fragment, so a request for the page never carries it.
	if u, err := url.Parse(s); err != nil || u.RawQuery != "" || strings.Contains(u.Path, "tskey") {
		t.Fatalf("link = %s", s)
	}
	app := l.AppLink()
	if !strings.HasPrefix(app, "ovenlight://join?") || strings.TrimPrefix(app, "ovenlight://join?") != strings.TrimPrefix(s, joinPage+"#") {
		t.Fatalf("app link = %s", app)
	}
	for _, link := range []string{s, app} {
		got, err := parseInviteLink(link)
		if err != nil || got != l {
			t.Fatalf("round trip %s: %+v %v", link, got, err)
		}
	}
	for _, bad := range []string{"https://join?v=1", "ovenlight://open?app=x", "ovenlight://join?v=2&control=x&key=k&app=a&invite=i&host=h",
		"ovenlight://join?v=1&control=x", "https://ovenlight.app/join", "https://example.com/join#" + l.query(),
		"https://ovenlight.app/joined#" + l.query()} {
		if _, err := parseInviteLink(bad); err == nil {
			t.Errorf("%s: expected an error", bad)
		}
	}
	// The message says how long the key lasts, to the hour.
	res := &shareResult{Link: s, AppName: "Interview Coach", Invite: Invite{Created: t0, Expires: t0.Add(inviteTTL - time.Second)}}
	if msg := inviteMessage(res); msg != "I'm sharing Interview Coach with you in Ovenlight, an iPhone app. Open this link on your iPhone within 24 hours to join: "+s {
		t.Errorf("message = %s", msg)
	}
	res.Invite.Expires, res.Owner = t0.Add(reviewInviteTTL), "Sam"
	if msg := inviteMessage(res); !strings.HasPrefix(msg, "I'm sharing Interview Coach with you in Ovenlight, an iPhone app. Open this link on your iPhone within 7 days to join: ") {
		t.Errorf("review message = %s", msg)
	}
	var buf bytes.Buffer
	if err := writeQR(&buf, s); err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimRight(buf.String(), "\n"), "\n")
	if len(lines) < 15 || !strings.Contains(lines[0], "\x1b[") || !strings.HasSuffix(lines[0], "\x1b[0m") {
		t.Errorf("QR output looks wrong: %d lines", len(lines))
	}
}

func pngBase64(t *testing.T, w, h int) string {
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewGray(image.Rect(0, 0, w, h))); err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(buf.Bytes())
}

func TestValidateFeedback(t *testing.T) {
	note, img, err := validateFeedback(feedbackRequest{Note: "  button overlaps  ", Screenshot: pngBase64(t, 10, 10)})
	if err != nil || note != "button overlaps" || len(img) == 0 {
		t.Fatalf("valid feedback: %q %d %v", note, len(img), err)
	}
	if _, img, err := validateFeedback(feedbackRequest{Note: "just words"}); err != nil || img != nil {
		t.Errorf("note only: %v", err)
	}
	bad := []feedbackRequest{
		{},
		{Note: strings.Repeat("x", maxNote+1)},
		{Note: "x", Screenshot: "not base64!"},
		{Note: "x", Screenshot: base64.StdEncoding.EncodeToString([]byte("GIF89a not a png"))},
		{Note: "x", Screenshot: base64.StdEncoding.EncodeToString(make([]byte, maxScreenshot+10))},
		{Note: "x", PageURL: strings.Repeat("u", 3000)},
	}
	for i, req := range bad {
		if _, _, err := validateFeedback(req); err == nil {
			t.Errorf("case %d: expected an error", i)
		}
	}
	l := &feedbackLimiter{sent: map[string][]time.Time{}}
	for i := range feedbackPerHour {
		if !l.allow("guest:nSam", t0.Add(time.Duration(i)*time.Second)) {
			t.Fatalf("refused note %d", i)
		}
	}
	if l.allow("guest:nSam", t0.Add(time.Minute)) || !l.allow("guest:nKim", t0.Add(time.Minute)) || !l.allow("guest:nSam", t0.Add(61*time.Minute)) {
		t.Error("rate limit is wrong")
	}
}

func TestSameHost(t *testing.T) {
	for host, want := range map[string]bool{
		"coach.tail1.ts.net:8443": true, "COACH.tail1.ts.net": true, "coach.tail1.ts.net.:8443": true,
		"evil.example.com:8443": false, "100.64.0.1:8443": false, "": false, "\u212Aoach.tail1.ts.net": false,
	} {
		if got := sameHost(host, "coach.tail1.ts.net"); got != want {
			t.Errorf("sameHost(%q) = %v", host, got)
		}
	}
	if sameHost("coach.tail1.ts.net", "") {
		t.Error("an unknown node name must never match")
	}
}

func TestScreenshotPathRefusesTraversal(t *testing.T) {
	for _, name := range []string{"../../etc/passwd", ".hidden.png", "a/b.png", ""} {
		if _, ok := screenshotPath("/state", Feedback{Screenshot: name}); ok {
			t.Errorf("%q accepted", name)
		}
	}
	if p, ok := screenshotPath("/state", Feedback{Screenshot: "abc.png"}); !ok || p != filepath.Join("/state", "feedback", "abc.png") {
		t.Errorf("path = %q", p)
	}
}

// Guest devices the connector has no record of are listed, and never touched, whether
// or not they're authorized; another connector's guest devices aren't listed at all.
func TestReconcileListsUnclaimedDevices(t *testing.T) {
	coach := policy.GuestTag("coach")
	used := sentInvite("i1", "Sam", "coach", "k1")
	used.State = inviteClaimed
	st := sharingState{Guests: []Guest{{DeviceID: "nSam", Person: "nSam", Name: "Sam", App: "coach"}}, Invites: []Invite{used}}
	devices := []tsapi.Device{
		{NodeID: "nSam", Tags: []string{coach}, Authorized: true},                 // the guest
		{NodeID: "nStray", Tags: []string{coach}, Authorized: true},               // joined, never claimed
		{NodeID: "nMixed", Tags: []string{coach, "tag:server"}, Authorized: true}, // not only a guest
		{NodeID: "nUnauthorized", Tags: []string{coach}},                          // de-authorized, or from an old key
		{NodeID: "nMBP", User: "alex@example.com", Authorized: true},              // the owner's
		{NodeID: "nServer", Tags: []string{"tag:server"}, Authorized: true},       // no guest tag
		{NodeID: "nTheirs", Tags: []string{policy.GuestTag("other")}, Authorized: true},
		{NodeID: "nTheirsWaiting", Tags: []string{policy.GuestTag("other")}},
	}
	res := st.reconcile(devices, []string{"coach"}, t0.Add(2*inviteTTL))
	var got []string
	for _, d := range res.Unclaimed {
		got = append(got, d.NodeID)
	}
	if !reflect.DeepEqual(got, []string{"nStray", "nMixed", "nUnauthorized"}) || len(res.Devices) != 0 {
		t.Errorf("unclaimed = %v, changes %+v", got, res.Devices)
	}
}

// A guest who claims while the device list is being fetched is missing from it, not
// gone: the sync must neither remove them nor, next time, delete their device.
func TestReconcileKeepsAGuestWhoClaimedAfterTheFetch(t *testing.T) {
	st := sharingState{Guests: []Guest{{DeviceID: "nMax", Name: "Max", App: "coach", ClaimedAt: t0.Add(time.Second)}}}
	devices := []tsapi.Device{{NodeID: "nMBP", User: "alex@example.com", Authorized: true}}
	if res := st.reconcile(devices, []string{"coach"}, t0); res.changed() || !st.Guests[0].Active() {
		t.Errorf("a guest who claimed after the fetch was removed: %+v", res)
	}
}

// A device given another app's tag for an invite keeps it while the invite is open and
// loses it when the invite lapses; a guest device with a stray tag is put right.
func TestReconcileDeviceTags(t *testing.T) {
	coach, notes := policy.GuestTag("coach"), policy.GuestTag("notes")
	st := sharingState{
		Guests: []Guest{
			{DeviceID: "nSam", Person: "p-sam", Name: "Sam", App: "coach"},
			{DeviceID: "nSam", Person: "p-sam", Name: "Sam", App: "notes", RemovedAt: &t0}, // back only through the invite
			{DeviceID: "nKim", Person: "p-kim", Name: "Kim", App: "coach"},
		},
		Invites: []Invite{sentInvite("i1", "Sam", "notes", "k1")},
	}
	st.Invites[0].Devices = []string{"nSam"}
	devices := []tsapi.Device{
		{NodeID: "nSam", Tags: []string{coach}, Authorized: true},        // the invite's tag is missing
		{NodeID: "nKim", Tags: []string{coach, notes}, Authorized: true}, // holds no notes
		{NodeID: "nNew", Tags: []string{notes}, Authorized: true},        // joined with a key, not claimed yet
		{NodeID: "nMBP", User: "alex@example.com", Authorized: true},     // not a guest
	}
	res := st.reconcile(devices, []string{"coach"}, t0)
	want := []deviceChange{{DeviceID: "nKim", Tags: []string{coach}}, {DeviceID: "nSam", Tags: []string{coach, notes}}}
	if got := st.changesFor(res.Devices); !reflect.DeepEqual(got, want) {
		t.Errorf("changes = %+v, want %+v", got, want)
	}
	// The invite lapses: Sam's device goes back to coach only.
	devices[0].Tags = []string{coach, notes}
	devices[1].Tags = []string{coach}
	res = st.reconcile(devices, []string{"coach"}, t0.Add(inviteTTL+claimGrace+time.Minute))
	if got := st.changesFor(res.Devices); !reflect.DeepEqual(got, []deviceChange{{DeviceID: "nSam", Tags: []string{coach}}}) {
		t.Errorf("after expiry: %+v", got)
	}
}

// A new person's name is checked again as the invite is recorded: another may have taken
// it while the key was minted.
func TestNameTaken(t *testing.T) {
	st := sharingState{
		Guests:  []Guest{{DeviceID: "nSam", Person: "p1", Name: "Sam", App: "coach"}},
		Invites: []Invite{{ID: "i1", To: "Lee", Person: "p2", State: inviteSent}},
	}
	for _, c := range []struct {
		inv  Invite
		want bool
	}{
		{Invite{ID: "x", To: "sam", Person: "new"}, true},
		{Invite{ID: "x", To: "LEE", Person: "new"}, true},
		{Invite{ID: "x", To: "Sam", Person: "p1"}, false},  // Sam again
		{Invite{ID: "x", To: "Lee", Person: "p2"}, false},  // Lee, still only invited
		{Invite{ID: "i1", To: "Lee", Person: "p2"}, false}, // the invite itself
		{Invite{ID: "x", To: "Kim", Person: "new"}, false},
	} {
		if got := st.nameTaken(c.inv); got != c.want {
			t.Errorf("nameTaken(%+v) = %v", c.inv, got)
		}
	}
}

// Two people with one name: revoking by that name is refused, by person it's exact.
func TestMatchGuestsRefusesAnAmbiguousName(t *testing.T) {
	st := sharingState{Guests: []Guest{
		{DeviceID: "nSam", Person: "p1", Name: "Sam", App: "coach"},
		{DeviceID: "nSam2", Person: "p2", Name: "sam", App: "coach"},
	}}
	_, _, err := st.matchGuests("Sam", "", false)
	if err == nil || !strings.Contains(err.Error(), "p1, p2") {
		t.Errorf("ambiguous name: %v", err)
	}
	if got := match(t, &st, "p2", ""); !reflect.DeepEqual(got, []int{1}) {
		t.Errorf("by person: %v", got)
	}
	// One Sam with a device and another with only an open invite are still two people.
	st = sharingState{
		Guests:  []Guest{{DeviceID: "nSam", Person: "p1", Name: "Sam", App: "coach"}},
		Invites: []Invite{{ID: "i2", To: "Sam", Person: "p2", App: "coach", State: inviteSent}},
	}
	if _, _, err := st.matchGuests("sam", "", false); err == nil {
		t.Error("a name with an invite-only namesake matched")
	}
}

// A device that becomes someone else's loses the open invites it was listed in.
func TestListedDeviceThatChangesPersonLosesTheInvite(t *testing.T) {
	st := sharingState{
		Guests:  []Guest{{DeviceID: "nPhone", Person: "q", Name: "Quinn", App: "coach", RemovedAt: &t0}},
		Invites: []Invite{{ID: "i1", To: "Quinn", Person: "q", App: "notes", State: inviteSent, Devices: []string{"nPhone"}}},
	}
	st.Guests = append(st.Guests, Guest{DeviceID: "nPhone", Person: "p", Name: "Pat", App: "echo"})
	if got := st.guestTags("nPhone"); !reflect.DeepEqual(got, []string{policy.GuestTag("echo")}) {
		t.Errorf("tags = %v", got)
	}
	if got := st.reachOf(st.Invites[0]); len(got) != 0 {
		t.Errorf("Quinn's invite reaches %v", got)
	}
}

// A device the owner took off every app is nobody's: an invite listing it can't bring
// it back to an app it was removed from.
func TestRemovedDeviceCantClaimAnInviteThatListedIt(t *testing.T) {
	inv := sentInvite("i1", "Sam", "coach", "k1")
	inv.Devices = []string{"nSam"}
	st := sharingState{
		Guests: []Guest{
			{DeviceID: "nSam", Person: inv.Person, Name: "Sam", App: "coach", RemovedAt: &t0},
			{DeviceID: "nSam", Person: inv.Person, Name: "Sam", App: "notes", RemovedAt: &t0},
		},
		Invites: []Invite{inv},
	}
	dev := Identity{Tagged: true, Tags: []string{policy.GuestTag("coach")}, DeviceID: "nSam"}
	if _, _, _, err := st.claimInvite(hashKey("k1"), "coach", dev, t0); err != errNotInvited {
		t.Errorf("claim = %v, want %v", err, errNotInvited)
	}
}

// Removing a person cancels their open invites, and only theirs.
func TestCancelInvitesOf(t *testing.T) {
	st := sharingState{
		Guests: []Guest{{DeviceID: "nSam", Person: "p1", Name: "Sam", App: "coach"}},
		Invites: []Invite{
			{ID: "i1", To: "Sam", Person: "p1", App: "notes", State: inviteSent},
			{ID: "i2", To: "Lee", Person: "p2", App: "notes", State: inviteSent},
		},
	}
	if got := st.reachOf(st.Invites[0]); !reflect.DeepEqual(got, []string{"nSam"}) {
		t.Errorf("reach of Sam's invite: %v", got)
	}
	canceled := st.cancelInvitesOf("p1", "")
	if len(canceled) != 1 || canceled[0].ID != "i1" || st.Invites[0].State != inviteCanceled {
		t.Errorf("canceled %+v", canceled)
	}
	if st.Invites[1].State != inviteSent {
		t.Errorf("Lee's invite: %+v", st.Invites[1])
	}
}

// parseInviteLink reads either form of the link, as Ovenlight does.
func parseInviteLink(s string) (InviteLink, error) {
	u, err := url.Parse(s)
	var raw string
	switch {
	case err != nil:
	case u.Scheme == "ovenlight" && u.Host == "join":
		raw = u.RawQuery
	case u.Scheme == "https" && u.Host == "ovenlight.app" && u.Path == "/join":
		raw = u.EscapedFragment()
	}
	if raw == "" {
		return InviteLink{}, errors.New("not an Ovenlight invite link")
	}
	q, err := url.ParseQuery(raw)
	if err != nil {
		return InviteLink{}, fmt.Errorf("invite link: %v", err)
	}
	if q.Get("v") != "1" {
		return InviteLink{}, fmt.Errorf("unsupported invite version %q", q.Get("v"))
	}
	l := InviteLink{Control: q.Get("control"), Key: q.Get("key"), Owner: q.Get("owner"), App: q.Get("app"),
		Invite: q.Get("invite"), Host: q.Get("host"), Name: q.Get("name"), To: q.Get("to")}
	if l.Control == "" || l.Key == "" || l.App == "" || l.Invite == "" || l.Host == "" {
		return InviteLink{}, errors.New("invite link is missing a field")
	}
	return l, nil
}
