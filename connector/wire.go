package main

import (
	"net/http"
	"slices"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// What Ovenlight reads from the connector: the admin API's answers, the claim's,
// feedback's and whoami's, and errors. Storage types (Invite, Guest, Feedback, tsapi.Device) change
// freely; these views are the contract, pinned by the files in testdata/wire.

// Error codes, sent as "code" next to the error text and in the Ovenlight-Error header,
// so Ovenlight can tell refusals apart without matching words.
const (
	codeNotInvited    = "not_invited"
	codeUsedElsewhere = "used_elsewhere" // the invite's key was used on another device
	codeOtherPerson   = "other_person"   // the device belongs to another guest
	codeOwnerOnly     = "owner_only"
	codeNotFound      = "not_found"
	codeBadRequest    = "bad_request"
	codeRateLimited   = "rate_limited"
	codeTooLarge      = "too_large"
	codeUnknownCaller = "unknown_caller" // the node doesn't know the device yet; worth another try
	codeInternal      = "internal"
	codeUnavailable   = "unavailable"
)

const errorHeader = "Ovenlight-Error"

type errorView struct {
	Error string `json:"error"`
	Code  string `json:"code"`
}

// writeError answers {"error": msg, "code": code}.
func writeError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set(errorHeader, code)
	writeJSON(w, status, errorView{Error: msg, Code: code})
}

// textError is http.Error with the code in the Ovenlight-Error header, for refusals a
// browser shows as a page.
func textError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set(errorHeader, code)
	http.Error(w, msg, status)
}

type inviteView struct {
	ID          string     `json:"id"`
	To          string     `json:"to"`
	Person      string     `json:"person"`
	App         string     `json:"app"`
	State       string     `json:"state"`
	RequestedBy string     `json:"requestedBy"`
	Created     time.Time  `json:"created"`
	Expires     time.Time  `json:"expires"`
	Review      bool       `json:"review,omitempty"`
	ClaimedBy   string     `json:"claimedBy,omitempty"`
	ClaimedAt   *time.Time `json:"claimedAt,omitempty"`
}

func (inv Invite) view() inviteView {
	return inviteView{ID: inv.ID, To: inv.To, Person: inv.Person, App: inv.App, State: inv.State, RequestedBy: inv.RequestedBy,
		Created: inv.Created, Expires: inv.Expires, Review: inv.Review,
		ClaimedBy: inv.ClaimedBy, ClaimedAt: inv.ClaimedAt}
}

type guestView struct {
	DeviceID   string     `json:"deviceId"`
	Person     string     `json:"person"`
	Name       string     `json:"name"`
	App        string     `json:"app"`
	DeviceName string     `json:"deviceName"`
	InviteID   string     `json:"inviteId"`
	ClaimedAt  time.Time  `json:"claimedAt"`
	RemovedAt  *time.Time `json:"removedAt,omitempty"`
	RemovedWhy string     `json:"removedWhy,omitempty"`
	DeviceGone bool       `json:"deviceGone,omitempty"`
}

func (g Guest) view() guestView {
	return guestView{DeviceID: g.DeviceID, Person: g.Person, Name: g.Name, App: g.App, DeviceName: g.DeviceName, InviteID: g.InviteID,
		ClaimedAt: g.ClaimedAt, RemovedAt: g.RemovedAt, RemovedWhy: g.RemovedWhy, DeviceGone: g.DeviceGone}
}

// deviceView is a guest device in the tailnet that claimed no invite.
type deviceView struct {
	ID         string   `json:"id"`
	NodeID     string   `json:"nodeId"`
	Name       string   `json:"name"`
	Hostname   string   `json:"hostname"`
	Authorized bool     `json:"authorized"`
	Created    string   `json:"created"`
	OS         string   `json:"os"`
	Tags       []string `json:"tags"`
}

type guestListView struct {
	Guests    []guestView  `json:"guests"`
	Invites   []inviteView `json:"invites"` // waiting for the guest
	Unclaimed []deviceView `json:"unclaimed"`
	LastSync  time.Time    `json:"lastSync,omitzero"`
	SyncError string       `json:"syncError,omitempty"`
}

type shareView struct {
	Invite    inviteView `json:"invite"`
	Link      string     `json:"link,omitempty"`
	AppLink   string     `json:"appLink,omitempty"`
	AppName   string     `json:"appName,omitempty"`
	Owner     string     `json:"owner,omitempty"`
	Devices   []string   `json:"devices,omitempty"`
	TagErrors []string   `json:"tagErrors,omitempty"`
	Message   string     `json:"message,omitempty"` // what the owner sends: inviteMessage
}

type revokeView struct {
	Removed  []guestView  `json:"removed"`
	Deleted  []string     `json:"deletedDevices"`
	Retagged []string     `json:"retaggedDevices,omitempty"`
	Canceled []inviteView `json:"canceledInvites,omitempty"`
	Errors   []string     `json:"errors,omitempty"`
}

type feedbackView struct {
	ID              string    `json:"id"`
	At              time.Time `json:"at"`
	App             string    `json:"app"`
	From            string    `json:"from"`
	Role            string    `json:"role"`
	UserID          string    `json:"userId"`
	Device          string    `json:"device"`
	PageURL         string    `json:"pageUrl,omitempty"`
	Note            string    `json:"note"`
	Screenshot      string    `json:"screenshot,omitempty"` // set when there is one: GET /v1/feedback/{id}/screenshot
	ScreenshotBytes int       `json:"screenshotBytes,omitempty"`
}

func (f Feedback) view() feedbackView {
	return feedbackView{ID: f.ID, At: f.At, App: f.App, From: f.From, Role: f.Role, UserID: f.UserID, Device: f.Device,
		PageURL: f.PageURL, Note: f.Note, Screenshot: f.Screenshot, ScreenshotBytes: f.ScreenshotBytes}
}

// feedbackSentView answers feedback sent to feedbackPath.
type feedbackSentView struct {
	ID string `json:"id"`
}

// claimView answers a guest's claim.
type claimView struct {
	Name    string `json:"name"`
	UserID  string `json:"userId"`
	App     string `json:"app"`
	AppName string `json:"appName"`
	Owner   string `json:"owner"`
}

type whoamiView struct {
	Role    Role   `json:"role"`
	User    string `json:"user"`
	UserID  string `json:"userId"`
	App     string `json:"app"`
	AppName string `json:"appName"`
	Owner   string `json:"owner"`
}

// views maps a slice, never to null.
func views[T, V any](in []T, view func(T) V) []V {
	out := make([]V, 0, len(in))
	for _, v := range in {
		out = append(out, view(v))
	}
	return out
}

func deviceViewOf(d tsapi.Device) deviceView {
	return deviceView{ID: d.ID, NodeID: d.NodeID, Name: d.Name, Hostname: d.Hostname, Authorized: d.Authorized, Created: d.Created,
		OS: d.OS, Tags: slices.Clone(d.Tags)}
}

func (l guestList) view() guestListView {
	return guestListView{Guests: views(l.Guests, Guest.view), Invites: views(l.Invites, Invite.view),
		Unclaimed: views(l.Unclaimed, deviceViewOf), LastSync: l.LastSync, SyncError: l.SyncError}
}

func (r shareResult) view() shareView {
	return shareView{Invite: r.Invite.view(), Link: r.Link, AppLink: r.AppLink, AppName: r.AppName, Owner: r.Owner,
		Devices: r.Devices, TagErrors: r.TagErrors, Message: r.Message}
}

func (r revokeResult) view() revokeView {
	v := revokeView{Removed: views(r.Removed, Guest.view), Deleted: r.Deleted, Retagged: r.Retagged, Errors: r.Errors}
	if v.Deleted == nil {
		v.Deleted = []string{}
	}
	if len(r.Canceled) > 0 {
		v.Canceled = views(r.Canceled, Invite.view)
	}
	return v
}
