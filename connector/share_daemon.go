package main

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"slices"
	"strings"
	"time"
	"unicode"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// api returns the control server client: Headscale's CLI in development, otherwise
// Tailscale's API with the credential from `ovenlight auth set`, reread on every call
// so a new credential takes effect without a restart.
func (d *daemon) api() (controlAPI, error) {
	if d.dev.Headscale != "" {
		return headscaleAPI{bin: d.dev.Headscale, config: d.dev.HeadscaleConfig}, nil
	}
	creds, err := tsapi.LoadCredentials(credentialsPath(d.configPath))
	if err != nil {
		return nil, err
	}
	return d.client(creds)
}

// client is the Tailscale API client for creds, kept while the credential stays the same.
func (d *daemon) client(creds tsapi.Credentials) (controlAPI, error) {
	d.apiMu.Lock()
	defer d.apiMu.Unlock()
	if d.apiClient == nil || d.apiFinger != creds.Type+creds.Fingerprint()+creds.BaseURL {
		c, err := tsapi.New(creds, nil)
		if err != nil {
			return nil, err
		}
		d.apiClient, d.apiFinger = c, creds.Type+creds.Fingerprint()+creds.BaseURL
	}
	return d.apiClient, nil
}

func (d *daemon) controlURL() string {
	if d.dev.ControlURL != "" {
		return d.dev.ControlURL
	}
	return defaultControl
}

const apiTimeout = 45 * time.Second

func apiContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), apiTimeout)
}

// withAuthHint says what to run when the API turned the credential down.
func withAuthHint(err error) error {
	if tsapi.IsUnauthorized(err) {
		return fmt.Errorf("%w; run `ovenlight auth set` with a new token", err)
	}
	return err
}

// mintAppKey makes a short-lived, single-use key that logs a node in as an app node.
func (d *daemon) mintAppKey(slug string) (tsapi.Key, error) {
	api, err := d.api()
	if err != nil {
		return tsapi.Key{}, err
	}
	ctx, cancel := apiContext()
	defer cancel()
	return newAppKey(ctx, api, slug, []string{policy.AppTag(slug)})
}

// newAppKey mints a short-lived, single-use, preauthorized key for an app node's first
// login: tagged, or untagged to log it in as the credential's user.
func newAppKey(ctx context.Context, api controlAPI, slug string, tags []string) (tsapi.Key, error) {
	return api.CreateAuthKey(ctx, tsapi.KeyRequest{Tags: tags, Preauthorized: true, Expiry: appKeyTTL, Description: "ovenlight app " + slug})
}

// dropKey deletes a minted key that will go unused. It is best effort: a failure is
// only logged, and the key expires on its own after appKeyTTL.
func dropKey(api controlAPI, slug, id string) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := api.DeleteKey(ctx, id); err != nil && !tsapi.IsNotFound(err) {
		log.Printf("[%s] couldn't delete the unused auth key %s: %v", slug, id, err)
	}
}

// mintOwnerKey makes an untagged key that logs a new unshared app node in as the
// owner, so it needs no browser sign-in. It returns no key and no error when there is
// nothing to try: only an API access token can make an untagged key (an OAuth client's
// must be tagged). A key whose user isn't the owner is deleted, so another admin's
// token can't take the node.
func (d *daemon) mintOwnerKey(slug, owner string) (tsapi.Key, error) {
	if d.dev.Headscale != "" {
		return tsapi.Key{}, nil
	}
	creds, err := tsapi.LoadCredentials(credentialsPath(d.configPath))
	if errors.Is(err, tsapi.ErrNoCredentials) || (err == nil && creds.Type != tsapi.TypeToken) {
		return tsapi.Key{}, nil
	}
	if err != nil {
		return tsapi.Key{}, err
	}
	if owner == "" {
		return tsapi.Key{}, errors.New("no owner is recorded or known yet")
	}
	api, err := d.client(creds)
	if err != nil {
		return tsapi.Key{}, err
	}
	ctx, cancel := apiContext()
	defer cancel()
	key, err := newAppKey(ctx, api, slug, nil)
	if err != nil {
		return tsapi.Key{}, err
	}
	login := ""
	if key.UserID != "" {
		login, err = api.UserLogin(ctx, key.UserID)
	}
	if login == "" || !policy.EqualFoldASCII(login, owner) {
		dropKey(api, slug, key.ID)
		if err != nil {
			return tsapi.Key{}, fmt.Errorf("couldn't tell whose key it is: %w", err)
		}
		return tsapi.Key{}, fmt.Errorf("the API token belongs to %s, not the owner %s", cmp.Or(login, "no user"), owner)
	}
	return key, nil
}

func (d *daemon) handleSharingControl(req controlRequest, reply controlReply) controlReply {
	fail := func(err error) controlReply { return controlReply{Error: err.Error()} }
	switch req.Cmd {
	case "share":
		res, err := d.createInvite(req.guestRef, req.Slug, "terminal", req.Review)
		if err != nil {
			return fail(forTerminal(err))
		}
		reply.Share = &res
	case "share-cancel":
		inv, err := d.cancelInvite(req.ID)
		if err != nil {
			return fail(err)
		}
		reply.Invite = &inv
	case "guests":
		g := d.guestList()
		reply.Guests = &g
	case "revoke":
		res, err := d.revoke(cmp.Or(req.Person, req.ID), req.App, req.Person != "")
		if err != nil {
			return fail(err)
		}
		reply.Revoke = &res
	case "make-shareable":
		msg, err := d.makeShareable(req.Slug)
		if err != nil {
			return fail(err)
		}
		reply.Message = msg
	case "health":
		reply.Health = d.health()
	case "sync":
		reply.Message = d.syncOnce()
	}
	return reply
}

// ---------- invites ----------

// shareResult is a minted invite. Link (the universal link to send) and AppLink (the
// same invite as ovenlight://, for when Link opens the web page) carry the key and are
// shown once.
type shareResult struct {
	Invite  Invite `json:"invite"`
	Link    string `json:"link,omitempty"`
	AppLink string `json:"appLink,omitempty"`
	AppName string `json:"appName,omitempty"`
	Owner   string `json:"owner,omitempty"`
	// Devices names the person's devices already in the tailnet for another app, which
	// can now reach this one too; TagErrors says which of them couldn't be tagged yet.
	Devices   []string `json:"devices,omitempty"`
	TagErrors []string `json:"tagErrors,omitempty"`
	Message   string   `json:"message,omitempty"` // what the owner sends: inviteMessage
}

func validGuestName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", errors.New(`say who the invite is for: --to "Name"`)
	}
	if len([]rune(name)) > 64 {
		return "", errors.New("the name is longer than 64 characters")
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return "", errors.New("the name has control characters")
		}
	}
	return name, nil
}

// shareableApp checks the app exists, is shareable, sharing is set up, and its running
// node carries the app's tag, the only node a guest's tag reaches. publish --shareable
// marks the app shareable before it turns the node into a tagged one, which can fail.
func (d *daemon) shareableApp(slug string) (App, Config, *appNode, error) {
	cfg := d.config()
	app, err := cfg.Published(slug)
	if err != nil {
		return App{}, cfg, nil, err
	}
	if !app.Shareable {
		return App{}, cfg, nil, fmt.Errorf("%s isn't shareable yet: run `ovenlight publish --slug %s --shareable`", app.Name, slug)
	}
	if cfg.Owner == "" {
		return App{}, cfg, nil, errors.New("no owner is recorded: run `ovenlight setup-sharing --owner <your Tailscale login>`")
	}
	n := d.node(slug)
	if n == nil || n.DNSName() == "" {
		return App{}, cfg, nil, fmt.Errorf("%s's node isn't up yet; check `ovenlight status`", app.Name)
	}
	if !n.isTagged(policy.AppTag(slug)) {
		return App{}, cfg, nil, fmt.Errorf("%s's node isn't tagged %s yet, so a guest couldn't reach it: run `ovenlight publish --slug %s --shareable` again", app.Name, policy.AppTag(slug), slug)
	}
	return app, cfg, n, nil
}

// guestRef says who an invite is for: someone new called To, or someone already shared
// with, by Person ID or, with Existing, by their name.
type guestRef struct {
	To       string `json:"to"`
	Person   string `json:"person,omitempty"`
	Existing bool   `json:"existing,omitempty"`
}

// resolvePerson turns a guestRef into a person ID and the name to show. A new person's
// name must be free, so new guests never share a name with a current one.
func (st *sharingState) resolvePerson(ref guestRef) (person, name string, err error) {
	people := st.people()
	if ref.Person != "" {
		name, ok := people[ref.Person]
		if !ok {
			return "", "", fmt.Errorf("no one you share with has the person ID %q; see `ovenlight guests`", ref.Person)
		}
		return ref.Person, name, nil
	}
	name, err = validGuestName(ref.To)
	if err != nil {
		return "", "", err
	}
	named := st.peopleNamed(name)
	switch {
	case !ref.Existing && len(named) == 0:
		return newID(), name, nil
	case !ref.Existing:
		return "", "", errNameTaken{name: people[named[0]]}
	case len(named) == 1:
		return named[0], people[named[0]], nil
	case len(named) == 0:
		return "", "", fmt.Errorf("you don't share with anyone called %q; invite them as someone new instead", name)
	default:
		return "", "", errAmbiguousName{name: name, people: named}
	}
}

// createInvite mints an invite a person asked for, in a terminal or in Ovenlight. A
// review invite, for Apple's App Review, has the same single-use key for the app's guest
// tag, but valid for reviewInviteTTL, because the reviewer may test days later. Only
// `share --review` in a terminal asks for one.
func (d *daemon) createInvite(ref guestRef, slug, by string, review bool) (shareResult, error) {
	if _, _, _, err := d.shareableApp(slug); err != nil {
		return shareResult{}, err
	}
	d.sh.mu.Lock()
	person, to, err := d.sh.st.resolvePerson(ref)
	d.sh.mu.Unlock()
	if err != nil {
		return shareResult{}, err
	}
	return d.mintInvite(Invite{ID: newID(), To: to, Person: person, App: slug, RequestedBy: by, Created: time.Now(), Review: review})
}

// mintInvite creates the invite's single-use guest key and records the invite. The key
// is preauthorized whatever the tailnet's device approval setting; docs/security.md
// (Invites) says why.
func (d *daemon) mintInvite(inv Invite) (shareResult, error) {
	app, cfg, n, err := d.shareableApp(inv.App)
	if err != nil {
		return shareResult{}, err
	}
	api, err := d.api()
	if err != nil {
		return shareResult{}, err
	}
	ctx, cancel := apiContext()
	defer cancel()
	ttl, desc := inviteTTL, "ovenlight invite "+inv.ID
	if inv.Review {
		ttl, desc = reviewInviteTTL, "ovenlight review invite "+inv.ID
	}
	key, err := api.CreateAuthKey(ctx, tsapi.KeyRequest{Tags: []string{policy.GuestTag(app.Slug)}, Preauthorized: true, Expiry: ttl,
		Description: desc})
	if err != nil {
		return shareResult{}, fmt.Errorf("couldn't create the invite key: %w", withAuthHint(err))
	}
	inv.State, inv.KeyID, inv.KeyHash = inviteSent, key.ID, hashKey(key.Key)
	inv.Expires = key.Expires
	if inv.Expires.IsZero() {
		inv.Expires = time.Now().Add(ttl)
	}
	d.sh.mu.Lock()
	// Ovenlight reuses a device the person already has in this tailnet (for another of your
	// apps) instead of joining again, so that device needs this app's tag to reach the
	// app and claim. It is recorded first: if tagging fails, the sync adds the tag.
	inv.Devices = d.sh.st.otherDevicesOf(inv.Person, inv.App)
	if d.sh.st.nameTaken(inv) {
		d.sh.mu.Unlock()
		api.DeleteKey(ctx, key.ID)
		return shareResult{}, errNameTaken{name: inv.To}
	}
	d.sh.st.Invites = append(d.sh.st.Invites, inv)
	err = d.sh.saveLocked()
	names := map[string]string{}
	for _, g := range d.sh.st.Guests {
		names[g.DeviceID] = g.DeviceName
	}
	d.sh.mu.Unlock()
	if err != nil {
		api.DeleteKey(ctx, key.ID)
		return shareResult{}, err
	}
	log.Printf("invite %s for %q to %s: key %s, expires %s, review %v (requested by %s)", inv.ID, inv.To, inv.App, key.ID,
		inv.Expires.Format(time.RFC3339), inv.Review, inv.RequestedBy)
	res := shareResult{Invite: inv, AppName: app.Name, Owner: cfg.OwnerLabel}
	if len(inv.Devices) > 0 {
		_, retagged, errs := d.retag(ctx, api, inv.Devices)
		for _, dev := range retagged {
			res.Devices = append(res.Devices, cmp.Or(names[dev], dev))
		}
		res.TagErrors = errs
	}
	link := InviteLink{Control: d.controlURL(), Key: key.Key, Owner: cfg.OwnerLabel, App: app.Slug, Invite: inv.ID,
		Host: n.DNSName(), Name: app.Name, To: inv.To}
	res.Link, res.AppLink = link.String(), link.AppLink()
	res.Message = inviteMessage(&res)
	return res, nil
}

var errNoInvite = errors.New("no unused invite")

// cancelInvite withdraws an unused invite and deletes its key. The invite it returns
// keeps its KeyID while the key couldn't be deleted: until the sync manages it, the key
// can still join the tailnet, though the connector refuses its claim.
func (d *daemon) cancelInvite(id string) (Invite, error) {
	d.sh.mu.Lock()
	p := d.sh.st.invite(id)
	if p == nil || p.State != inviteSent {
		d.sh.mu.Unlock()
		return Invite{}, fmt.Errorf("%w %q", errNoInvite, id)
	}
	p.State = inviteCanceled
	inv := *p
	reach := d.sh.st.reachOf(inv)
	err := d.sh.saveLocked()
	d.sh.mu.Unlock()
	if err != nil {
		return inv, err
	}
	if inv.KeyID != "" || len(reach) > 0 {
		if api, err := d.api(); err == nil {
			ctx, cancel := apiContext()
			defer cancel()
			if inv.KeyID != "" && d.deleteInviteKey(ctx, api, inv) == nil {
				inv.KeyID = ""
			}
			// Devices given this app's tag for the invite lose it again.
			d.retag(ctx, api, reach)
		}
	}
	log.Printf("invite %s for %q canceled", id, inv.To)
	return inv, nil
}

// deleteInviteKey deletes the key of an invite that can't be used any more and clears
// its KeyID, so the sync retries only the ones that failed. A key already gone counts as
// deleted.
func (d *daemon) deleteInviteKey(ctx context.Context, api controlAPI, inv Invite) error {
	if err := api.DeleteKey(ctx, inv.KeyID); err != nil && !tsapi.IsNotFound(err) {
		log.Printf("invite %s is %s, but deleting its key %s failed (retried every few minutes): %v", inv.ID, inv.State, inv.KeyID, err)
		return err
	}
	d.sh.mu.Lock()
	defer d.sh.mu.Unlock()
	if p := d.sh.st.invite(inv.ID); p != nil && p.KeyID == inv.KeyID {
		p.KeyID = ""
		if err := d.sh.saveLocked(); err != nil {
			log.Printf("invite %s: recording its deleted key: %v", inv.ID, err)
		}
	}
	return nil
}

// ---------- claim ----------

const claimReadTimeout = 10 * time.Second

// handleClaim binds the calling guest device to the invite whose key it presents.
func (d *daemon) handleClaim(w http.ResponseWriter, r *http.Request, caller Identity, app App) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, codeBadRequest, "POST only")
		return
	}
	// A device that holds no invite can reach only this; it mustn't hold a connection open.
	http.NewResponseController(w).SetReadDeadline(time.Now().Add(claimReadTimeout))
	var body struct {
		Key string `json:"key"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&body); err != nil || body.Key == "" {
		writeError(w, http.StatusBadRequest, codeBadRequest, `send {"key": "<the invite's auth key>"}`)
		return
	}
	d.sh.mu.Lock()
	inv, g, fresh, err := d.sh.st.claimInvite(hashKey(body.Key), app.Slug, caller, time.Now())
	var invCopy Invite
	var guestCopy Guest
	var retagNeeded bool
	if err == nil {
		invCopy, guestCopy = *inv, *g
		if fresh {
			err = d.sh.saveLocked()
			retagNeeded = !slices.Equal(d.sh.st.guestTags(caller.DeviceID), guestTagsOf(caller.Tags))
		}
	}
	d.sh.mu.Unlock()
	if err != nil {
		log.Printf("[%s] claim rejected from %s (%s): %v", app.Slug, caller.DeviceName, caller.DeviceID, err)
		status, code, msg := http.StatusForbidden, codeNotInvited, err.Error()
		switch {
		case errors.Is(err, errInviteUsed):
			code = codeUsedElsewhere
		case errors.Is(err, errOtherPerson):
			code = codeOtherPerson
		case !errors.Is(err, errNotInvited):
			// Saving failed; the error names the owner's files, so the guest gets none of it.
			status, code, msg = http.StatusInternalServerError, codeInternal, "the connector couldn't record the claim; try again in a moment"
		}
		writeError(w, status, code, msg)
		return
	}
	if fresh {
		log.Printf("[%s] %s (%s, %s) claimed invite %s", app.Slug, guestCopy.Name, caller.DeviceName, caller.DeviceID, invCopy.ID)
		// The key is spent if this device joined with it. If it joined with another
		// one, deleting this key keeps it from adding a second device.
		go func() {
			api, err := d.api()
			if err != nil {
				return
			}
			ctx, cancel := apiContext()
			defer cancel()
			if invCopy.KeyID != "" {
				d.deleteInviteKey(ctx, api, invCopy)
			}
			if retagNeeded {
				d.retag(ctx, api, []string{caller.DeviceID})
			}
		}()
	}
	writeJSON(w, http.StatusOK, claimView{Name: guestCopy.Name, UserID: guestCopy.UserID(), App: app.Slug, AppName: app.Name,
		Owner: d.config().OwnerLabel})
}

// ---------- guests ----------

type guestList struct {
	Guests    []Guest        `json:"guests"`
	Invites   []Invite       `json:"invites"`   // waiting for the guest
	Unclaimed []tsapi.Device `json:"unclaimed"` // guest devices that never claimed an invite; the owner removes them
	LastSync  time.Time      `json:"lastSync,omitzero"`
	SyncError string         `json:"syncError,omitempty"`
}

func (d *daemon) guestList() guestList {
	d.sh.mu.Lock()
	defer d.sh.mu.Unlock()
	return buildGuestList(d.sh.st, d.sh.unclaimed, d.sh.lastSync, d.sh.syncErr)
}

func buildGuestList(st sharingState, unclaimed []tsapi.Device, lastSync time.Time, syncErr string) guestList {
	out := guestList{Guests: []Guest{}, Invites: []Invite{}, Unclaimed: slices.Clone(unclaimed), LastSync: lastSync, SyncError: syncErr}
	if out.Unclaimed == nil {
		out.Unclaimed = []tsapi.Device{}
	}
	out.Guests = append(out.Guests, st.Guests...)
	for _, inv := range st.Invites {
		if inv.State == inviteSent {
			out.Invites = append(out.Invites, inv)
		}
	}
	return out
}

type revokeResult struct {
	Removed  []Guest  `json:"removed"`
	Deleted  []string `json:"deletedDevices"`
	Retagged []string `json:"retaggedDevices,omitempty"` // kept for another app, minus this app's tag
	Canceled []Invite `json:"canceledInvites,omitempty"` // the person's open invites, when removed by person; keyId stays while the key couldn't be deleted
	Errors   []string `json:"errors,omitempty"`
}

// revoke removes guests as matchGuests finds them, and a person's open invites: the connector refuses them at
// once and ends their open connections, then their devices are deleted from the tailnet,
// or, if they still hold another of the owner's apps, lose only this app's tag.
func (d *daemon) revoke(ref, app string, byPerson bool) (revokeResult, error) {
	var res revokeResult
	d.sh.mu.Lock()
	idx, person, err := d.sh.st.matchGuests(ref, app, byPerson)
	if err != nil {
		d.sh.mu.Unlock()
		return res, err
	}
	if len(idx) == 0 && !slices.ContainsFunc(d.sh.st.Invites, func(inv Invite) bool {
		return person != "" && inv.Person == person && inv.open() && (app == "" || inv.App == app)
	}) {
		d.sh.mu.Unlock()
		return res, fmt.Errorf("no current guest matches %q; see `ovenlight guests`", ref)
	}
	// Removing a person, by name or ID, is final: their open invites go too.
	var canceled []Invite
	var devices []string
	if person != "" {
		canceled = d.sh.st.cancelInvitesOf(person, app)
		for _, inv := range canceled {
			devices = append(devices, d.sh.st.reachOf(inv)...)
		}
	}
	for _, i := range idx {
		devices = append(devices, d.sh.st.Guests[i].DeviceID)
	}
	d.sh.st.removeGuests(idx, "removed by the owner", time.Now())
	for _, i := range idx {
		res.Removed = append(res.Removed, d.sh.st.Guests[i])
	}
	res.Canceled = canceled
	saveErr := d.sh.saveLocked()
	d.sh.mu.Unlock()
	if saveErr != nil {
		res.Errors = append(res.Errors, "removed, but saving that failed; a restart before the next successful save would bring them back: "+saveErr.Error())
	}
	for _, g := range res.Removed {
		if n := d.node(g.App); n != nil {
			n.dropGuest(g.DeviceID)
		}
		log.Printf("[%s] removed guest %s (%s)", g.App, g.Name, g.DeviceID)
	}
	api, err := d.api()
	if err != nil {
		res.Errors = append(res.Errors, "blocked here, but can't change the devices in the tailnet: "+err.Error())
		return res, nil
	}
	ctx, cancel := apiContext()
	defer cancel()
	for i, inv := range res.Canceled {
		if inv.KeyID != "" && d.deleteInviteKey(ctx, api, inv) == nil {
			res.Canceled[i].KeyID = ""
		}
	}
	var errs []string
	res.Deleted, res.Retagged, errs = d.retag(ctx, api, devices)
	for _, e := range errs {
		res.Errors = append(res.Errors, "blocked here, but "+e)
	}
	return res, nil
}

// retag gives each guest device exactly the tags its records call for, or deletes it
// when they call for none. The tags are worked out under tagMu right before they are
// written, so a slower caller can never leave a device with tags from an older state.
func (d *daemon) retag(ctx context.Context, api controlAPI, devices []string) (deleted, retagged, errs []string) {
	d.tagMu.Lock()
	defer d.tagMu.Unlock()
	d.sh.mu.Lock()
	changes := d.sh.st.changesFor(devices)
	d.sh.mu.Unlock()
	return applyDeviceChanges(ctx, api, changes)
}

// applyDeviceChanges retags guest devices, or deletes the ones that hold no app. A
// device already gone counts as done.
func applyDeviceChanges(ctx context.Context, api controlAPI, changes []deviceChange) (deleted, retagged, errs []string) {
	for _, c := range changes {
		if c.delete() {
			if err := api.DeleteDevice(ctx, c.DeviceID); err != nil && !tsapi.IsNotFound(err) {
				errs = append(errs, fmt.Sprintf("deleting device %s from the tailnet failed (retried every few minutes): %v", c.DeviceID, withAuthHint(err)))
				continue
			}
			deleted = append(deleted, c.DeviceID)
			log.Printf("deleted guest device %s from the tailnet", c.DeviceID)
			continue
		}
		if err := api.SetDeviceTags(ctx, c.DeviceID, c.Tags); err != nil {
			if !tsapi.IsNotFound(err) {
				errs = append(errs, fmt.Sprintf("tagging device %s %s failed (retried every few minutes): %v", c.DeviceID, strings.Join(c.Tags, ", "), withAuthHint(err)))
			}
			continue
		}
		retagged = append(retagged, c.DeviceID)
		log.Printf("guest device %s now tagged %s", c.DeviceID, strings.Join(c.Tags, ", "))
	}
	return deleted, retagged, errs
}

// retireUnpublished ends sharing for the apps the loaded config doesn't list (see
// sharingState.retireUnpublished) and reports whether it changed anything. The sync's
// reconcile and spent keys then take the guest tags off devices and delete the keys.
func (d *daemon) retireUnpublished() bool {
	d.mu.Lock()
	cfg, loaded := d.cfg, d.loaded
	d.mu.Unlock()
	if !loaded {
		return false
	}
	var apps []string
	for _, app := range cfg.Apps {
		apps = append(apps, app.Slug)
	}
	d.sh.mu.Lock()
	defer d.sh.mu.Unlock()
	canceled, removed := d.sh.st.retireUnpublished(apps, time.Now())
	if len(canceled)+len(removed) == 0 {
		return false
	}
	if err := d.sh.saveLocked(); err != nil {
		log.Printf("saving the guests of unpublished apps: %v", err)
	}
	for _, inv := range canceled {
		log.Printf("[%s] unpublished: invite %s for %q canceled", inv.App, inv.ID, inv.To)
	}
	for _, g := range removed {
		log.Printf("[%s] unpublished: removed guest %s (%s)", g.App, g.Name, g.DeviceID)
	}
	return true
}

// ---------- shareable app nodes ----------

// makeShareable turns an app's node into a tagged app node: it mints a tagged key,
// deletes the old user-owned device (freeing its name), and logs a fresh node in with
// the key under the same name.
func (d *daemon) makeShareable(slug string) (string, error) {
	d.opMu.Lock()
	defer d.opMu.Unlock()
	cfg := d.config()
	app, ok := cfg.Find(slug)
	if !ok || !app.Shareable {
		return "", fmt.Errorf("%q isn't a shareable app in the config", slug)
	}
	n := d.node(slug)
	if n == nil {
		return "", fmt.Errorf("no node is running for %q", slug)
	}
	if n.taggedLoginPending() {
		// Its first login is already under way with a tagged key. A node that ran and
		// then lost its login, or whose key expired first, is keyed again below.
		return waitTagged(n, app, d.stopping())
	}
	if n.hasState() {
		if err := waitSettled(n, app, d.stopping()); err != nil {
			return "", err
		}
	}
	if n.isTagged(policy.AppTag(slug)) {
		return fmt.Sprintf("%s is already a shareable app node (%s)", app.Name, policy.AppTag(slug)), nil
	}
	api, err := d.api()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	key, err := newAppKey(ctx, api, slug, []string{policy.AppTag(slug)})
	if err != nil {
		return "", fmt.Errorf("couldn't create a tagged key: %w", err)
	}
	if old := n.deviceID(); old != "" {
		if err := api.DeleteDevice(ctx, old); err != nil && !tsapi.IsNotFound(err) {
			api.DeleteKey(ctx, key.ID)
			return "", fmt.Errorf("couldn't remove the old node from the tailnet, nothing changed: %w", err)
		}
		log.Printf("[%s] deleted the untagged node %s to make it shareable", slug, old)
	}
	d.mu.Lock()
	delete(d.nodes, slug)
	d.mu.Unlock()
	n.close()
	n.dropUnusedKey() // an untagged first-login key it never used
	if err := os.RemoveAll(n.dir); err != nil {
		return "", err
	}
	nn := newAppNode(app, d.stateDir, d.dev, d)
	nn.setOwner(cfg.Owner)
	nn.authKey, nn.keyID, nn.keyMinted = key.Key, key.ID, time.Now()
	if err := nn.start(); err != nil {
		nn.dropUnusedKey()
		return "", fmt.Errorf("the new node didn't start: %w", err)
	}
	d.mu.Lock()
	d.nodes[slug] = nn
	d.mu.Unlock()
	return waitTagged(nn, app, d.stopping())
}

const tagWait = 90 * time.Second

// settleWait bounds waitSettled, well within the three minutes publish waits in all.
const settleWait = 30 * time.Second

// waitSettled waits for a node started from saved state to run or to need a login.
// Before its first network map it knows neither its tags nor its device ID, so replacing
// it then would wipe a tagged node's state, or keep its device holding the name.
func waitSettled(n *appNode, app App, stop <-chan struct{}) error {
	deadline := time.Now().Add(settleWait)
	for time.Now().Before(deadline) {
		if b := n.status().Backend; b == "Running" || b == "NeedsLogin" {
			return nil
		}
		select {
		case <-stop:
			return fmt.Errorf("the connector stopped before %s's node came up; once it runs again, run `%s` again", app.Name, reshareCommand(app.Slug))
		case <-time.After(500 * time.Millisecond):
		}
	}
	return fmt.Errorf("%s's node hasn't come up within %s, so Ovenlight left it as it is; see `ovenlight status`", app.Name, humanDuration(settleWait))
}

// waitTagged waits for a node logging in with a tagged key to be up.
func waitTagged(n *appNode, app App, stop <-chan struct{}) (string, error) {
	deadline := time.Now().Add(tagWait)
	for time.Now().Before(deadline) {
		st := n.status()
		if slices.Contains(st.Tags, policy.AppTag(app.Slug)) && st.Backend == "Running" && st.DNSName != "" {
			return fmt.Sprintf("%s runs as a shareable app node (%s) at https://%s/", app.Name, policy.AppTag(app.Slug), st.DNSName), nil
		}
		select {
		case <-stop:
			return "", fmt.Errorf("the connector stopped before %s's tagged node came up; once it runs again, run `ovenlight publish --slug %s --shareable` again", app.Name, app.Slug)
		case <-time.After(500 * time.Millisecond):
		}
	}
	return "", fmt.Errorf("the tagged node for %s hasn't come up within %s; see `ovenlight status`", app.Name, humanDuration(tagWait))
}

// ---------- health and sync ----------

// health lists what each app node sees, for setup-sharing's check after a policy change.
func (d *daemon) health() map[string][]peerInfo {
	out := map[string][]peerInfo{}
	for _, n := range d.snapshot() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		peers, err := n.peers(ctx)
		cancel()
		if err == nil {
			if peers == nil {
				peers = []peerInfo{}
			}
			out[n.App().Slug] = peers
		}
	}
	return out
}

func (d *daemon) syncLoop() {
	time.Sleep(15 * time.Second) // let the nodes come up first
	for {
		d.syncOnce()
		time.Sleep(reconcileEvery)
	}
}

// syncOnce reconciles the guest records with the tailnet's device list.
func (d *daemon) syncOnce() string {
	// Records an unpublished app gained after its reload (an invite minted meanwhile) go
	// here; the reconcile and spent keys below take care of their devices and keys.
	d.retireUnpublished()
	// No credential isn't a failed sync: status says there is none, and a fresh install
	// has none until sharing is set up.
	setErr := func(err error) string {
		msg := withAuthHint(err).Error()
		none, kept := errors.Is(err, tsapi.ErrNoCredentials), msg
		if none {
			kept = ""
		}
		d.sh.mu.Lock()
		first := d.sh.syncErr != kept
		d.sh.syncErr = kept
		d.sh.mu.Unlock()
		if first && !none {
			log.Printf("guest sync: %s", msg)
		}
		return msg
	}
	api, err := d.api()
	if err != nil {
		return setErr(err)
	}
	ctx, cancel := apiContext()
	defer cancel()
	fetched := time.Now()
	devices, err := api.Devices(ctx)
	if err != nil {
		return setErr(err)
	}
	if len(devices) == 0 {
		return setErr(errors.New("the tailnet's device list came back empty; not trusting it"))
	}
	var apps []string
	for _, app := range d.config().Apps {
		apps = append(apps, app.Slug)
	}
	d.sh.mu.Lock()
	res := d.sh.st.reconcile(devices, apps, fetched)
	d.sh.unclaimed, d.sh.lastSync, d.sh.syncErr = res.Unclaimed, time.Now(), ""
	var saveErr error
	if res.changed() {
		saveErr = d.sh.saveLocked()
	}
	spent := d.sh.st.spentKeys()
	d.sh.mu.Unlock()
	if saveErr != nil {
		log.Printf("guest sync: saving: %v", saveErr)
	}
	for _, r := range res.Removed {
		log.Printf("guest sync: %s is gone from the tailnet; removed", r)
	}
	for _, e := range res.Expired {
		log.Printf("guest sync: invite %s expired unused", e)
	}
	d.dropRemovedGuests()
	_, _, errs := d.retag(ctx, api, res.Devices)
	for _, e := range errs {
		log.Printf("guest sync: %s", e)
	}
	for _, inv := range spent {
		d.deleteInviteKey(ctx, api, inv)
	}
	return fmt.Sprintf("synced with %d devices: %d guests removed, %d invites expired, %d unclaimed",
		len(devices), len(res.Removed), len(res.Expired), len(res.Unclaimed))
}

// dropRemovedGuests ends open connections of guests who are no longer admitted.
func (d *daemon) dropRemovedGuests() {
	d.sh.mu.Lock()
	var removed []Guest
	for _, g := range d.sh.st.Guests {
		if !g.Active() && d.sh.st.activeGuest(g.DeviceID, g.App) == nil {
			removed = append(removed, g)
		}
	}
	d.sh.mu.Unlock()
	for _, g := range removed {
		if n := d.node(g.App); n != nil {
			n.dropGuest(g.DeviceID)
		}
	}
}
