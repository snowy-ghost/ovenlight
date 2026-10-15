package main

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
)

// adminHeader must accompany every request that changes something. Browsers can't add
// it to a cross-site request without a CORS preflight, which the admin API never
// grants, so a web page open on the owner's laptop can't drive the API.
const adminHeader = "X-Ovenlight-Request"

// appView is one app as the owner's Ovenlight lists it.
type appView struct {
	Slug      string `json:"slug"`
	Name      string `json:"name"`
	URL       string `json:"url,omitempty"`
	State     string `json:"state"`
	Online    bool   `json:"online"` // the app answers on its local port
	Shareable bool   `json:"shareable"`
	Guests    int    `json:"guests"` // people, however many devices each
}

// adminHandler serves the owner's admin API on :8443 of every app node: JSON, owner
// only (an untagged device of the configured owner, by WhoIs). Guests can't reach the
// port at all under the policy setup-sharing writes; this check is the second wall.
func (n *appNode) adminHandler() http.Handler {
	d := n.host
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/apps", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, d.appViews())
	})
	mux.HandleFunc("POST /v1/invites", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			guestRef
			App string `json:"app"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&body); err != nil {
			writeError(w, http.StatusBadRequest, codeBadRequest, `send {"to": "Name", "app": "slug"} or {"person": "id", "app": "slug"}`)
			return
		}
		res, err := d.createInvite(body.guestRef, body.App, "ovenlight", false)
		if err != nil {
			writeError(w, http.StatusBadRequest, codeBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, res.view())
	})
	mux.HandleFunc("DELETE /v1/invites/{id}", func(w http.ResponseWriter, r *http.Request) {
		inv, err := d.cancelInvite(r.PathValue("id"))
		if errors.Is(err, errNoInvite) {
			writeError(w, http.StatusNotFound, codeNotFound, err.Error())
			return
		} else if err != nil {
			n.logf("canceling invite %s: %v", r.PathValue("id"), err)
			writeError(w, http.StatusInternalServerError, codeInternal, "the connector couldn't record the cancel; try again in a moment")
			return
		}
		writeJSON(w, http.StatusOK, inv.view())
	})
	mux.HandleFunc("GET /v1/guests", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, d.guestList().view())
	})
	mux.HandleFunc("DELETE /v1/guests/{ref}", func(w http.ResponseWriter, r *http.Request) {
		res, err := d.revoke(r.PathValue("ref"), r.URL.Query().Get("app"), r.URL.Query().Get("by") == "person")
		if err != nil {
			writeError(w, http.StatusNotFound, codeNotFound, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, res.view())
	})
	mux.HandleFunc("GET /v1/feedback", func(w http.ResponseWriter, r *http.Request) {
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		fb, err := readFeedback(d.stateDir, r.URL.Query().Get("app"), limit)
		if err != nil {
			writeError(w, http.StatusInternalServerError, codeInternal, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, views(fb, Feedback.view))
	})
	mux.HandleFunc("GET /v1/feedback/{id}/screenshot", func(w http.ResponseWriter, r *http.Request) {
		fb, _ := readFeedback(d.stateDir, "", 0)
		for _, f := range fb {
			if f.ID != r.PathValue("id") {
				continue
			}
			if path, ok := screenshotPath(d.stateDir, f); ok {
				if data, err := os.ReadFile(path); err == nil {
					w.Header().Set("Content-Type", "image/png")
					w.Header().Set("X-Content-Type-Options", "nosniff")
					w.Header().Set("Cache-Control", "private, max-age=3600")
					w.Write(data)
					return
				}
			}
		}
		writeError(w, http.StatusNotFound, codeNotFound, "no such screenshot")
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { // any other path or method
		writeError(w, http.StatusNotFound, codeNotFound, "no such endpoint")
	})

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		c, ok := n.identify(r)
		defer func() {
			n.logf("admin %s %s %d %s user=%s role=%s", r.Method, logPath(r.URL.Path), rec.code(), time.Since(start).Round(time.Millisecond),
				orDash(c.logName()), orDash(string(c.Role)))
		}()
		if !ok || c.Role != RoleOwner {
			writeError(rec, http.StatusForbidden, codeOwnerOnly, "the admin API is for the owner only")
			return
		}
		rec.Header().Set(versionHeader, version())
		switch {
		case !sameHost(r.Host, n.DNSName()):
			writeError(rec, http.StatusMisdirectedRequest, codeBadRequest, "use this node's tailnet name")
		case r.Method != http.MethodGet && r.Header.Get(adminHeader) != "1":
			writeError(rec, http.StatusForbidden, codeBadRequest, "send the header "+adminHeader+": 1")
		case d == nil:
			writeError(rec, http.StatusServiceUnavailable, codeUnavailable, "not available")
		default:
			mux.ServeHTTP(rec, r)
		}
	})
}

// sameHost compares a Host header with the node's MagicDNS name, ignoring the port and
// ASCII case. It blocks DNS rebinding: another name pointed at the node's address.
func sameHost(hostHeader, dnsName string) bool {
	host := hostHeader
	if h, _, err := net.SplitHostPort(hostHeader); err == nil {
		host = h
	}
	return dnsName != "" && policy.EqualFoldASCII(strings.TrimSuffix(host, "."), dnsName)
}

func (d *daemon) appViews() []appView {
	cfg := d.config()
	d.sh.mu.Lock()
	guests := map[string]int{}
	counted := map[[2]string]bool{}
	for _, g := range d.sh.st.Guests {
		if key := [2]string{g.App, g.Person}; g.Active() && !counted[key] {
			counted[key] = true
			guests[g.App]++
		}
	}
	d.sh.mu.Unlock()
	out := []appView{}
	for _, app := range cfg.Apps {
		v := appView{Slug: app.Slug, Name: app.Name, State: "not running", Shareable: app.Shareable, Guests: guests[app.Slug]}
		if n := d.node(app.Slug); n != nil {
			st := n.status()
			v.State, v.URL = st.State, st.URL
		}
		if conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(app.Port)), time.Second); err == nil {
			conn.Close()
			v.Online = true
		}
		out = append(out, v)
	}
	return out
}
