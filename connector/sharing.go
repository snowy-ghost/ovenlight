package main

import (
	"cmp"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// Invite states.
const (
	inviteSent     = "sent" // key minted, link handed to the owner
	inviteClaimed  = "claimed"
	inviteExpired  = "expired"
	inviteCanceled = "canceled"
)

const (
	inviteTTL       = 24 * time.Hour      // key lifetime
	reviewInviteTTL = 90 * 24 * time.Hour // a review invite's key: App Review may test weeks later; Tailscale's maximum
	claimGrace      = time.Hour           // a device that joined just before expiry may still claim
	reconcileEvery  = 3 * time.Minute
	appKeyTTL       = 10 * time.Minute
	defaultControl  = "https://controlplane.tailscale.com"
	sharingFileName = "sharing.json"
)

// Invite is one offer to share one app with one person. The auth key itself is never
// stored, only its SHA-256, which the claim step matches.
type Invite struct {
	ID          string    `json:"id"`
	To          string    `json:"to"`
	Person      string    `json:"person"` // who the invite admits; see Guest.Person
	App         string    `json:"app"`
	State       string    `json:"state"`
	RequestedBy string    `json:"requestedBy"` // terminal or ovenlight
	Created     time.Time `json:"created"`
	Expires     time.Time `json:"expires"`
	KeyID       string    `json:"keyId,omitempty"` // cleared once the key is deleted in the tailnet (or found gone)
	KeyHash     string    `json:"keyHash,omitempty"`
	// Review marks an invite for Apple's App Review: its key lasts reviewInviteTTL.
	Review    bool       `json:"review,omitempty"`
	ClaimedBy string     `json:"claimedBy,omitempty"` // device ID
	ClaimedAt *time.Time `json:"claimedAt,omitempty"`
	// Devices are the person's devices in the tailnet for another app when the invite was
	// made, given this app's guest tag (even one the owner removed from this app) so
	// Ovenlight can reuse them to claim it, while they stay the person's. Devices of theirs
	// that join later reach it through guestTags.
	Devices []string `json:"devices,omitempty"`
}

// Guest is one person's device admitted to one app.
type Guest struct {
	DeviceID string `json:"deviceId"`
	// Person is who the device belongs to, assigned by the connector when the owner first
	// invites them: every device and app of theirs shares it, and apps see it as the
	// user's ID. The name is only a label.
	Person     string     `json:"person"`
	Name       string     `json:"name"`
	App        string     `json:"app"`
	DeviceName string     `json:"deviceName"`
	InviteID   string     `json:"inviteId"`
	ClaimedAt  time.Time  `json:"claimedAt"`
	RemovedAt  *time.Time `json:"removedAt,omitempty"`
	RemovedWhy string     `json:"removedWhy,omitempty"`
	DeviceGone bool       `json:"deviceGone,omitempty"` // no longer in the tailnet
}

func (g Guest) Active() bool { return g.RemovedAt == nil }

// UserID is the stable identifier apps see in Ovenlight-User-Id: one per person, on every
// device of theirs.
func (g Guest) UserID() string { return "guest:" + g.Person }

// Feedback is a note, and maybe a screenshot, sent from Ovenlight about an app.
type Feedback struct {
	ID              string    `json:"id"`
	At              time.Time `json:"at"`
	App             string    `json:"app"`
	From            string    `json:"from"`
	Role            string    `json:"role"`
	UserID          string    `json:"userId"`
	Device          string    `json:"device"`
	PageURL         string    `json:"pageUrl,omitempty"`
	Note            string    `json:"note"`
	Screenshot      string    `json:"screenshot,omitempty"` // file name under <state>/feedback
	ScreenshotBytes int       `json:"screenshotBytes,omitempty"`
}

type sharingState struct {
	Invites  []Invite   `json:"invites"`
	Guests   []Guest    `json:"guests"`
	Feedback []Feedback `json:"feedback"`
}

// sharing is the daemon's record of invites, guests and feedback, saved in
// <state>/sharing.json after every change.
type sharing struct {
	mu   sync.Mutex
	path string
	st   sharingState

	unclaimed []tsapi.Device // guest devices in the tailnet that claimed nothing, as of lastSync
	lastSync  time.Time
	syncErr   string
}

func sharingPath(stateDir string) string { return filepath.Join(stateDir, sharingFileName) }

func feedbackDir(stateDir string) string { return filepath.Join(stateDir, "feedback") }

// loadSharingState reads the file. A missing file is an empty state; a damaged one is
// an error, never silently empty, because that would forget who was removed.
func loadSharingState(path string) (sharingState, error) {
	var st sharingState
	data, err := jsonfile.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return st, nil
	}
	if err != nil {
		return st, err
	}
	if err := json.Unmarshal(data, &st); err != nil {
		return st, fmt.Errorf("%s is damaged (%v); fix or move it aside, then restart", path, err)
	}
	return st, nil
}

func openSharing(stateDir string) (*sharing, error) {
	st, err := loadSharingState(sharingPath(stateDir))
	if err != nil {
		return nil, err
	}
	return &sharing{path: sharingPath(stateDir), st: st}, nil
}

// saveLocked writes the state atomically, readable only by the owner.
func (sh *sharing) saveLocked() error {
	return jsonfile.Save(sh.path, sh.st)
}

// guestFor returns a copy of the active guest record for this device and app.
func (sh *sharing) guestFor(deviceID, app string) *Guest {
	sh.mu.Lock()
	defer sh.mu.Unlock()
	if g := sh.st.activeGuest(deviceID, app); g != nil {
		c := *g
		return &c
	}
	return nil
}

// ---------- pure state changes (unit-tested) ----------

func hashKey(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:])
}

func newID() string {
	b := make([]byte, 5)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (st *sharingState) activeGuest(deviceID, app string) *Guest {
	for i := range st.Guests {
		g := &st.Guests[i]
		if g.Active() && g.DeviceID == deviceID && g.App == app {
			return g
		}
	}
	return nil
}

func (st *sharingState) invite(id string) *Invite {
	for i := range st.Invites {
		if st.Invites[i].ID == id {
			return &st.Invites[i]
		}
	}
	return nil
}

var (
	errNotInvited = errors.New("not invited")
	errInviteUsed = errors.New("this invite was already used by another device")
	// Ovenlight reads "not invited" as a final refusal.
	errOtherPerson = errors.New("not invited: this device already belongs to another guest")
)

// claimInvite binds a guest device to the invite whose key it presents. Only the device
// that joined with the single-use key can have that key, so the key's hash is the
// proof. Claiming again from the same device is harmless; fresh reports a new claim.
func (st *sharingState) claimInvite(keyHash, app string, caller Identity, now time.Time) (inv *Invite, g *Guest, fresh bool, err error) {
	if caller.DeviceID == "" || !caller.Tagged || !slices.Contains(caller.Tags, policy.GuestTag(app)) {
		return nil, nil, false, errNotInvited
	}
	for i := range st.Invites {
		if st.Invites[i].KeyHash != "" && st.Invites[i].KeyHash == keyHash {
			inv = &st.Invites[i]
		}
	}
	if inv == nil || inv.App != app {
		return nil, nil, false, errNotInvited
	}
	switch inv.State {
	case inviteClaimed:
		if inv.ClaimedBy != caller.DeviceID {
			return nil, nil, false, errInviteUsed
		}
		if g := st.activeGuest(caller.DeviceID, app); g != nil {
			return inv, g, false, nil
		}
		return nil, nil, false, errNotInvited // claimed, then removed
	case inviteSent:
		if now.After(inv.Expires.Add(claimGrace)) {
			return nil, nil, false, errNotInvited
		}
	default:
		return nil, nil, false, errNotInvited
	}
	// A device belongs to one person, so a device already admitted to any app may take
	// only that person's invites; otherwise it would spend someone else's. The invite
	// stays usable.
	person := st.personOfDevice(caller.DeviceID)
	if person != "" && person != inv.Person {
		return nil, nil, false, errOtherPerson
	}
	if st.removedFrom(caller.DeviceID, app) && !(person == inv.Person && slices.Contains(inv.Devices, caller.DeviceID)) {
		return nil, nil, false, errNotInvited // a device the owner took off the app needs its own invite
	}
	// A device that already holds the app gains nothing, so the invite stays for the
	// device it was meant for (Add a Device, with the link opened on the old one).
	if g = st.activeGuest(caller.DeviceID, app); g != nil {
		return inv, g, false, nil
	}
	at := now
	inv.State, inv.ClaimedBy, inv.ClaimedAt = inviteClaimed, caller.DeviceID, &at
	st.Guests = append(st.Guests, Guest{DeviceID: caller.DeviceID, Person: cmp.Or(person, inv.Person), Name: inv.To, App: app,
		DeviceName: caller.DeviceName, InviteID: inv.ID, ClaimedAt: now})
	return inv, &st.Guests[len(st.Guests)-1], true, nil
}

// matchGuests finds active guest records, optionally for one app only: with byPerson,
// every device of that person; otherwise by device ID or invite ID, else person, else
// name (any case). An ID any device has had names only that device. A name that
// belongs to more than one current person is an error rather than all of them. person
// is who was named, unless it was one device; they may have only open invites, and no
// records to return.
func (st *sharingState) matchGuests(ref, app string, byPerson bool) (idx []int, person string, err error) {
	find := func(match func(Guest) bool) []int {
		var out []int
		for i, g := range st.Guests {
			if g.Active() && (app == "" || g.App == app) && match(g) {
				out = append(out, i)
			}
		}
		return out
	}
	isPerson := func(g Guest) bool { return g.Person == person }
	if byPerson {
		person = ref
		return find(isPerson), person, nil
	}
	isDevice := func(g Guest) bool { return g.DeviceID == ref || g.InviteID == ref }
	if slices.ContainsFunc(st.Guests, isDevice) {
		return find(isDevice), "", nil
	}
	if _, ok := st.people()[ref]; ok {
		person = ref
		return find(isPerson), person, nil
	}
	switch named := st.peopleNamed(ref); len(named) {
	case 0:
		return nil, "", nil
	case 1:
		person = named[0]
		return find(isPerson), person, nil
	default:
		return nil, "", errAmbiguousName{name: ref, people: named}
	}
}

// removedFrom reports whether the device was taken off the app and holds it no more.
func (st *sharingState) removedFrom(deviceID, app string) bool {
	removed := false
	for _, g := range st.Guests {
		if g.DeviceID == deviceID && g.App == app {
			if g.Active() {
				return false
			}
			removed = true
		}
	}
	return removed
}

// personOfDevice is the person an active guest device belongs to, or "".
func (st *sharingState) personOfDevice(deviceID string) string {
	for _, g := range st.Guests {
		if g.Active() && g.DeviceID == deviceID {
			return g.Person
		}
	}
	return ""
}

// people lists the current people, active guests or holders of an open invite, with the
// name each was last given.
func (st *sharingState) people() map[string]string {
	out := map[string]string{}
	for _, g := range st.Guests {
		if g.Active() {
			out[g.Person] = g.Name
		}
	}
	for _, inv := range st.Invites {
		if inv.open() {
			out[inv.Person] = inv.To
		}
	}
	return out
}

// nameTaken reports whether the invite makes someone new under a name another current
// person has. resolvePerson checks this too, but a key is minted without the lock, so
// the invite is checked again as it's recorded.
func (st *sharingState) nameTaken(inv Invite) bool {
	for _, g := range st.Guests {
		if g.Active() && g.Person == inv.Person {
			return false
		}
	}
	for _, o := range st.Invites {
		if o.ID != inv.ID && o.Person == inv.Person && o.open() {
			return false
		}
	}
	return slices.ContainsFunc(st.peopleNamed(inv.To), func(p string) bool { return p != inv.Person })
}

// errAmbiguousName says a name belongs to several people, by ID.
type errAmbiguousName struct {
	name   string
	people []string
}

func (e errAmbiguousName) Error() string {
	return fmt.Sprintf("%d people are called %q; in a terminal, choose one with --person <ID> (%s)", len(e.people), e.name, strings.Join(e.people, ", "))
}

// errNameTaken says a name belongs to someone else; how says how the caller picks them.
type errNameTaken struct{ name, how string }

func (e errNameTaken) Error() string {
	return fmt.Sprintf("%q is already the name of someone you share with or invited. To invite them again, choose them as someone you already share with%s; for someone else, use a name that tells them apart", e.name, e.how)
}

// forTerminal fits a name error to the terminal: how to pick the person there.
func forTerminal(err error) error {
	var e errNameTaken
	if !errors.As(err, &e) {
		return err
	}
	e.how = " (--existing)"
	return e
}

func (inv Invite) open() bool { return inv.State == inviteSent }

// lifetime is how long the invite's key lasts, to the hour: 24 hours, or 90 days for review.
func (inv Invite) lifetime() time.Duration { return inv.Expires.Sub(inv.Created).Round(time.Hour) }

// peopleNamed lists the current people called name (any case).
func (st *sharingState) peopleNamed(name string) []string {
	var out []string
	for id, n := range st.people() {
		if strings.EqualFold(n, name) {
			out = append(out, id)
		}
	}
	slices.Sort(out)
	return out
}

// guestTags are the tags a guest device should carry: one per app it holds, and one per
// open invite that tagged it or is for its person, so it can reach that app to claim.
func (st *sharingState) guestTags(deviceID string) []string {
	var tags []string
	for _, g := range st.Guests {
		if g.DeviceID == deviceID && g.Active() {
			tags = append(tags, policy.GuestTag(g.App))
		}
	}
	person := st.personOfDevice(deviceID)
	for _, inv := range st.Invites {
		if inv.State == inviteSent && person != "" && inv.Person == person &&
			(slices.Contains(inv.Devices, deviceID) || !st.removedFrom(deviceID, inv.App)) {
			tags = append(tags, policy.GuestTag(inv.App))
		}
	}
	return slices.Compact(slices.Sorted(slices.Values(tags)))
}

// guestTagsOf is the guest tags among a device's tags, sorted.
func guestTagsOf(tags []string) []string {
	var out []string
	for _, t := range tags {
		if policy.IsGuestTag(t) {
			out = append(out, t)
		}
	}
	return slices.Sorted(slices.Values(out))
}

// otherDevicesOf lists the devices of this person that hold other apps but not this one.
func (st *sharingState) otherDevicesOf(person, app string) []string {
	var out []string
	for _, g := range st.Guests {
		if g.Active() && g.Person == person && g.App != app && st.activeGuest(g.DeviceID, app) == nil && !slices.Contains(out, g.DeviceID) {
			out = append(out, g.DeviceID)
		}
	}
	return out
}

// cancelInvitesOf cancels the person's open invites, for one app or all, so removing
// them is final. It returns the canceled invites.
func (st *sharingState) cancelInvitesOf(person, app string) []Invite {
	var out []Invite
	for i := range st.Invites {
		inv := &st.Invites[i]
		if inv.open() && inv.Person == person && (app == "" || inv.App == app) {
			inv.State = inviteCanceled
			out = append(out, *inv)
		}
	}
	return out
}

// reachOf lists the devices an open invite gives its app's tag: its person's (see
// guestTags).
func (st *sharingState) reachOf(inv Invite) []string {
	var out []string
	for _, g := range st.Guests {
		if g.Active() && g.Person == inv.Person {
			out = append(out, g.DeviceID)
		}
	}
	return out
}

// deviceChange is what should happen to a guest device in the tailnet: new tags, or,
// when it holds no app any more, deletion (Tags empty).
type deviceChange struct {
	DeviceID string
	Tags     []string
}

func (c deviceChange) delete() bool { return len(c.Tags) == 0 }

// changesFor lists what the devices' tags should become, one entry per device.
func (st *sharingState) changesFor(devices []string) []deviceChange {
	var out []deviceChange
	for _, dev := range slices.Compact(slices.Sorted(slices.Values(devices))) {
		out = append(out, deviceChange{DeviceID: dev, Tags: st.guestTags(dev)})
	}
	return out
}

// removeGuests marks the records removed at once; the connector stops serving them on
// the next request. changesFor then says what happens to their devices: a device that
// still holds another app loses only this app's tag, the others are deleted.
func (st *sharingState) removeGuests(idx []int, why string, now time.Time) {
	for _, i := range idx {
		at := now
		st.Guests[i].RemovedAt, st.Guests[i].RemovedWhy = &at, why
	}
}

// retireUnpublished ends sharing for every app not in apps, the published ones: its
// open invites are canceled and its guests removed, so a later app under the same slug
// starts with no guests. It works from the records alone, so an app unpublished while
// the connector was down is retired when it next loads the config. It returns the
// canceled invites and the removed guests.
func (st *sharingState) retireUnpublished(apps []string, now time.Time) (canceled []Invite, removed []Guest) {
	for i := range st.Invites {
		inv := &st.Invites[i]
		if inv.open() && !slices.Contains(apps, inv.App) {
			inv.State = inviteCanceled
			canceled = append(canceled, *inv)
		}
	}
	var idx []int
	for i, g := range st.Guests {
		if g.Active() && !slices.Contains(apps, g.App) {
			idx = append(idx, i)
		}
	}
	st.removeGuests(idx, "the app was unpublished", now)
	for _, i := range idx {
		removed = append(removed, st.Guests[i])
	}
	return canceled, removed
}

// syncResult is what reconciling with the tailnet's device list changed or found.
type syncResult struct {
	Removed   []string       // guests whose device disappeared from the tailnet
	Expired   []string       // invites that ran out unused
	Devices   []string       // guest devices whose tags are off: retag, or delete when they hold nothing
	Unclaimed []tsapi.Device // guest devices this connector has no record of
}

func (r syncResult) changed() bool { return len(r.Removed)+len(r.Expired) > 0 }

// reconcile brings the records in line with the tailnet: a guest whose device was
// deleted elsewhere (admin console, another tool) is removed, unused invites expire,
// and the guest devices this connector knows get exactly the tags of the apps they
// hold, or are listed for deletion when they hold none. Guest devices it doesn't know
// (joined with a key, never claimed) are only listed: the owner decides.
// Only devices with a guest tag of apps, this connector's, are listed, so a second
// connector's guests never show up here.
// now is when the device list was fetched: a guest who claimed after that is missing
// from it, not gone.
func (st *sharingState) reconcile(devices []tsapi.Device, apps []string, now time.Time) syncResult {
	var r syncResult
	ours := func(tag string) bool {
		return slices.ContainsFunc(apps, func(slug string) bool { return tag == policy.GuestTag(slug) })
	}
	present := map[string]tsapi.Device{}
	for _, d := range devices {
		present[d.NodeID] = d
	}
	known := map[string]bool{}
	for i := range st.Guests {
		g := &st.Guests[i]
		known[g.DeviceID] = true
		_, inTailnet := present[g.DeviceID]
		switch {
		case g.Active() && !inTailnet && !g.ClaimedAt.After(now):
			at := now
			g.RemovedAt, g.RemovedWhy, g.DeviceGone = &at, "device deleted from the tailnet", true
			r.Removed = append(r.Removed, fmt.Sprintf("%s (%s, %s)", g.Name, g.App, g.DeviceName))
		case !g.Active() && !inTailnet:
			g.DeviceGone = true
		}
	}
	for i := range st.Invites {
		inv := &st.Invites[i]
		if inv.State == inviteSent && now.After(inv.Expires.Add(claimGrace)) {
			inv.State = inviteExpired
			r.Expired = append(r.Expired, fmt.Sprintf("%s for %s (%s)", inv.ID, inv.To, inv.App))
		}
	}
	for _, d := range devices {
		if !known[d.NodeID] {
			continue
		}
		have := guestTagsOf(d.Tags)
		if len(have) == 0 {
			continue // not a guest device (any more); never touch it
		}
		want := st.guestTags(d.NodeID)
		if !slices.Equal(have, want) || len(have) != len(d.Tags) {
			r.Devices = append(r.Devices, d.NodeID)
		}
	}
	for _, d := range devices {
		// Unauthorized ones too (from a key minted before invites were always
		// preauthorized, or de-authorized in the admin console): they reach nothing, but
		// listing them lets the owner see and remove them.
		if !known[d.NodeID] && slices.ContainsFunc(d.Tags, ours) {
			r.Unclaimed = append(r.Unclaimed, d)
		}
	}
	return r
}

// spentKeys lists the invites whose keys can't be used any more (claimed, canceled or
// expired) but are still in the tailnet.
func (st *sharingState) spentKeys() []Invite {
	var out []Invite
	for _, inv := range st.Invites {
		if inv.KeyID != "" && (inv.State == inviteClaimed || inv.State == inviteCanceled || inv.State == inviteExpired) {
			out = append(out, inv)
		}
	}
	return out
}
