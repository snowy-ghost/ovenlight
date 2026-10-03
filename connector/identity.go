package main

import (
	"mime"
	"net/http"
	"slices"
	"strings"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
)

// Identity is who is calling, as the tailnet's WhoIs reports it.
type Identity struct {
	Login      string   // for example alice@example.com; empty or meaningless when tagged
	Name       string   // display name
	Tagged     bool     // a tagged device has no user behind it
	Tags       []string // the device's tags
	DeviceID   string   // stable node ID
	DeviceName string
}

// Role is what the caller may do.
type Role string

const (
	RoleOwner Role = "owner"
	RoleGuest Role = "guest"
	// RoleUnclaimed is a guest device that holds no invite for this app (never claimed
	// one, or was removed). It may only claim an invite.
	RoleUnclaimed Role = "unclaimed"
	RoleDenied    Role = ""
)

// decideRole maps a caller to a role for one app. The owner is an untagged device of
// the configured owner login; a guest is a device tagged as a guest of this app, with
// an active claim on it. Anything unknown fails closed.
func decideRole(caller Identity, ownerLogin string, guest *Guest, app string) Role {
	if caller.Tagged {
		if !slices.Contains(caller.Tags, policy.GuestTag(app)) {
			return RoleDenied
		}
		if guest != nil && guest.Active() && guest.DeviceID == caller.DeviceID && caller.DeviceID != "" {
			return RoleGuest
		}
		return RoleUnclaimed
	}
	if ownerLogin == "" || caller.Login == "" {
		return RoleDenied
	}
	if policy.EqualFoldASCII(caller.Login, ownerLogin) {
		return RoleOwner
	}
	return RoleDenied
}

// reservedPrefixes and reservedNames are headers only the connector may set: identity
// and client address. Apps trust them, so any copy arriving from the network is removed
// before the request is proxied.
var (
	reservedPrefixes = []string{"tailscale-", "ovenlight-", "x-ovenlight-", "x-forwarded-"}
	reservedNames    = []string{"forwarded", "x-real-ip"}
)

// isReservedHeader matches case-insensitively and treats "_" as "-", because some
// frameworks (CGI, WSGI, PHP) map both to the same variable name.
func isReservedHeader(name string) bool {
	n := strings.ToLower(strings.ReplaceAll(name, "_", "-"))
	for _, prefix := range reservedPrefixes {
		if strings.HasPrefix(n, prefix) {
			return true
		}
	}
	return slices.Contains(reservedNames, n)
}

func stripReservedHeaders(h http.Header) {
	for name := range h {
		if isReservedHeader(name) {
			delete(h, name)
		}
	}
}

// applyIdentity replaces every reserved header with the caller's verified identity.
// Owners get Tailscale's own headers too; guests have no Tailscale user, so they get
// only the Ovenlight-* ones, with the name the owner gave them.
func applyIdentity(h http.Header, c caller) {
	stripReservedHeaders(h)
	switch c.Role {
	case RoleOwner:
		display := c.Name
		if display == "" {
			display = c.Login
		}
		h.Set("Tailscale-User-Login", headerValue(c.Login))
		h.Set("Tailscale-User-Name", headerValue(c.Name))
		h.Set("Ovenlight-User", headerValue(display))
		h.Set("Ovenlight-User-Id", headerValue(c.Login))
	case RoleGuest:
		if c.Guest == nil {
			return
		}
		h.Set("Ovenlight-User", headerValue(c.Guest.Name))
		h.Set("Ovenlight-User-Id", headerValue(c.Guest.UserID()))
	default:
		return
	}
	h.Set("Ovenlight-Role", string(c.Role))
}

// headerValue keeps plain ASCII as is and RFC 2047-encodes anything else, as
// `tailscale serve` does, so a display name like "Zoë" can't produce an invalid header.
func headerValue(s string) string {
	for _, r := range s {
		if r < 0x20 || r > 0x7e {
			return mime.QEncoding.Encode("utf-8", s)
		}
	}
	return s
}
