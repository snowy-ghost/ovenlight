// Package tsapitest is an in-memory stand-in for the Tailscale API, for tests. It
// implements the endpoints package tsapi calls, checks authentication, enforces
// If-Match on policy writes and records every request.
package tsapitest

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// Fake is a fake Tailscale API server. Lock Mu to read or change its fields while the
// server is running.
type Fake struct {
	Server *httptest.Server

	Mu           sync.Mutex
	Token        string // accepted API access token
	ClientID     string // accepted OAuth client
	ClientSecret string
	TokenTTL     time.Duration     // lifetime of issued OAuth tokens
	TokenUser    string            // user ID behind Token; keys minted with it belong to them
	Users        map[string]string // user ID -> login name

	Policy  []byte
	ETag    string
	Devices []tsapi.Device
	Keys    []FakeKey

	// Validate, when set, judges a policy: a non-empty answer is the refusal message.
	Validate func(policy []byte) string
	// BeforeSetPolicy, when set, runs before a policy write and may fail it with a status.
	BeforeSetPolicy func(policy []byte) int
	// AfterSetPolicy, when set, may rewrite what is stored (to simulate a server that
	// saved something other than what was sent).
	AfterSetPolicy func(policy []byte) []byte
	// BeforeCreateKey, when set, runs before a key is minted, without Mu held, so it may
	// block. Set it before the server gets requests.
	BeforeCreateKey func()
	// ForceReusable makes every minted key reusable, and DropKeyTags mints them
	// untagged, as a misbehaving server would.
	ForceReusable, DropKeyTags bool
	// ListKeyIDsOnly makes the key list give only IDs, as older API versions do.
	ListKeyIDsOnly bool

	Requests     []string // "METHOD /path" for every authenticated request
	PolicyWrites [][]byte
	TagWrites    []string // "<device> <tag>,<tag>" for every tag change
	tokens       map[string]time.Time
	etagN        int
	expiryOff    map[string]bool // device ID -> key expiry disabled
}

// FakeKey is an auth key the fake minted.
type FakeKey struct {
	tsapi.Key
	Secret  string
	Deleted bool
}

// New starts a fake with an API access token "tskey-api-test" and a default policy.
func New() *Fake {
	f := &Fake{
		Token:     "tskey-api-test",
		TokenTTL:  time.Hour,
		TokenUser: "u1",
		Users:     map[string]string{"u1": "alex@example.com"},
		Policy:    []byte(`{"acls": [{"action": "accept", "src": ["*"], "dst": ["*:*"]}]}`),
		tokens:    map[string]time.Time{},
		expiryOff: map[string]bool{},
	}
	f.newETag()
	f.Server = httptest.NewServer(http.HandlerFunc(f.serve))
	return f
}

func (f *Fake) Close() { f.Server.Close() }

// URL is the base URL to put in tsapi.Credentials.BaseURL.
func (f *Fake) URL() string { return f.Server.URL }

// TokenCredentials are credentials the fake accepts.
func (f *Fake) TokenCredentials() tsapi.Credentials {
	return tsapi.Credentials{Type: tsapi.TypeToken, Token: f.Token, BaseURL: f.URL()}
}

func (f *Fake) newETag() {
	f.etagN++
	f.ETag = fmt.Sprintf(`"etag-%d"`, f.etagN)
}

// SetPolicy replaces the stored policy, as an edit in the admin console would.
func (f *Fake) SetPolicy(p []byte) {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	f.Policy = p
	f.newETag()
}

// KeyExpiryDisabled reports whether a device's key expiry was turned off.
func (f *Fake) KeyExpiryDisabled(id string) bool {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	return f.expiryOff[id]
}

// IssuedTokens counts the OAuth access tokens handed out.
func (f *Fake) IssuedTokens() int {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	return len(f.tokens)
}

// ForgetTokens invalidates every OAuth access token, as an expiry would.
func (f *Fake) ForgetTokens() {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	f.tokens = map[string]time.Time{}
}

// RequestCount counts recorded requests with this "METHOD /path" prefix.
func (f *Fake) RequestCount(prefix string) int {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	n := 0
	for _, r := range f.Requests {
		if strings.HasPrefix(r, prefix) {
			n++
		}
	}
	return n
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"message": msg})
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (f *Fake) serve(w http.ResponseWriter, r *http.Request) {
	if f.BeforeCreateKey != nil && r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/keys") {
		f.BeforeCreateKey()
	}
	f.Mu.Lock()
	defer f.Mu.Unlock()
	body, _ := io.ReadAll(r.Body)

	if r.URL.Path == "/api/v2/oauth/token" {
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		r.ParseForm()
		if f.ClientID == "" || r.PostForm.Get("client_id") != f.ClientID || r.PostForm.Get("client_secret") != f.ClientSecret {
			fail(w, http.StatusUnauthorized, "invalid client")
			return
		}
		tok := "oauth-" + randHex(8)
		f.tokens[tok] = time.Now().Add(f.TokenTTL)
		writeJSON(w, http.StatusOK, map[string]any{"access_token": tok, "token_type": "Bearer", "expires_in": int(f.TokenTTL / time.Second)})
		return
	}

	auth := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	exp, isOAuth := f.tokens[auth]
	if !(auth != "" && auth == f.Token) && !(isOAuth && time.Now().Before(exp)) {
		fail(w, http.StatusUnauthorized, "API token invalid")
		return
	}
	f.Requests = append(f.Requests, r.Method+" "+r.URL.Path)

	const tn = "/api/v2/tailnet/-"
	switch path := r.URL.Path; {
	case path == tn+"/acl" && r.Method == http.MethodGet:
		if !strings.Contains(r.Header.Get("Accept"), "application/hujson") {
			fail(w, http.StatusBadRequest, "fake only serves hujson")
			return
		}
		w.Header().Set("ETag", f.ETag)
		w.Header().Set("Content-Type", "application/hujson")
		w.Write(f.Policy)

	case path == tn+"/acl/validate" && r.Method == http.MethodPost:
		if f.Validate != nil {
			if msg := f.Validate(body); msg != "" {
				writeJSON(w, http.StatusOK, map[string]any{"message": msg, "data": []map[string]any{{"user": "test", "errors": []string{msg}}}})
				return
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{})

	case path == tn+"/acl" && r.Method == http.MethodPost:
		if m := r.Header.Get("If-Match"); m != "" && m != f.ETag {
			fail(w, http.StatusPreconditionFailed, "precondition failed, invalid old hash")
			return
		}
		if f.Validate != nil {
			if msg := f.Validate(body); msg != "" {
				fail(w, http.StatusBadRequest, msg)
				return
			}
		}
		if f.BeforeSetPolicy != nil {
			if status := f.BeforeSetPolicy(body); status != 0 {
				fail(w, status, "injected failure")
				return
			}
		}
		stored := slices.Clone(body)
		if f.AfterSetPolicy != nil {
			stored = f.AfterSetPolicy(stored)
		}
		f.PolicyWrites = append(f.PolicyWrites, slices.Clone(body))
		f.Policy = stored
		f.newETag()
		w.Header().Set("ETag", f.ETag)
		w.Write(f.Policy)

	case path == tn+"/devices" && r.Method == http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{"devices": f.Devices})

	case strings.HasPrefix(path, "/api/v2/device/") && strings.HasSuffix(path, "/key") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/api/v2/device/"), "/key")
		var req struct {
			KeyExpiryDisabled *bool `json:"keyExpiryDisabled"`
		}
		if err := json.Unmarshal(body, &req); err != nil || req.KeyExpiryDisabled == nil {
			fail(w, http.StatusBadRequest, "send {\"keyExpiryDisabled\": true|false}")
			return
		}
		for i := range f.Devices {
			if f.Devices[i].ID == id || f.Devices[i].NodeID == id {
				f.expiryOff[f.Devices[i].ID] = *req.KeyExpiryDisabled
				w.WriteHeader(http.StatusOK)
				return
			}
		}
		fail(w, http.StatusNotFound, "device not found")

	case strings.HasPrefix(path, "/api/v2/users/") && r.Method == http.MethodGet:
		id := strings.TrimPrefix(path, "/api/v2/users/")
		if login, ok := f.Users[id]; ok {
			writeJSON(w, http.StatusOK, map[string]string{"id": id, "loginName": login})
			return
		}
		fail(w, http.StatusNotFound, "user not found")

	case strings.HasPrefix(path, "/api/v2/device/") && strings.HasSuffix(path, "/tags") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/api/v2/device/"), "/tags")
		var req struct {
			Tags []string `json:"tags"`
		}
		if err := json.Unmarshal(body, &req); err != nil || req.Tags == nil {
			fail(w, http.StatusBadRequest, "send {\"tags\": [...]}")
			return
		}
		for i := range f.Devices {
			if f.Devices[i].ID == id || f.Devices[i].NodeID == id {
				f.Devices[i].Tags = slices.Clone(req.Tags)
				f.TagWrites = append(f.TagWrites, id+" "+strings.Join(req.Tags, ","))
				w.WriteHeader(http.StatusOK)
				return
			}
		}
		fail(w, http.StatusNotFound, "device not found")

	case strings.HasPrefix(path, "/api/v2/device/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(path, "/api/v2/device/")
		for i := range f.Devices {
			if f.Devices[i].ID == id || f.Devices[i].NodeID == id {
				f.Devices = slices.Delete(f.Devices, i, i+1)
				w.WriteHeader(http.StatusOK)
				return
			}
		}
		fail(w, http.StatusNotFound, "device not found")

	case path == tn+"/keys" && r.Method == http.MethodPost:
		var req struct {
			Capabilities struct {
				Devices struct {
					Create struct {
						Reusable, Preauthorized bool
						Tags                    []string
					} `json:"create"`
				} `json:"devices"`
			} `json:"capabilities"`
			ExpirySeconds int64  `json:"expirySeconds"`
			Description   string `json:"description"`
		}
		if err := json.Unmarshal(body, &req); err != nil {
			fail(w, http.StatusBadRequest, err.Error())
			return
		}
		c := req.Capabilities.Devices.Create
		if f.ForceReusable {
			c.Reusable = true
		}
		if f.DropKeyTags {
			c.Tags = nil
		}
		userID := ""
		if !isOAuth {
			userID = f.TokenUser
		} else if len(c.Tags) == 0 {
			fail(w, http.StatusBadRequest, "keys made through OAuth must have tags")
			return
		}
		now := time.Now().UTC().Truncate(time.Second)
		k := FakeKey{
			Key: tsapi.Key{ID: "k" + randHex(6), Description: req.Description, Created: now, UserID: userID,
				Expires: now.Add(time.Duration(req.ExpirySeconds) * time.Second), Tags: c.Tags, Reusable: c.Reusable, Preauthorized: c.Preauthorized},
			Secret: "tskey-auth-" + randHex(24),
		}
		f.Keys = append(f.Keys, k)
		writeJSON(w, http.StatusOK, keyAnswer(k, true))

	case path == tn+"/keys" && r.Method == http.MethodGet:
		var out []any
		for _, k := range f.Keys {
			switch {
			case k.Deleted:
			case f.ListKeyIDsOnly:
				out = append(out, map[string]any{"id": k.ID})
			default:
				out = append(out, keyAnswer(k, false))
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{"keys": out})

	case strings.HasPrefix(path, tn+"/keys/") && r.Method == http.MethodGet:
		id := strings.TrimPrefix(path, tn+"/keys/")
		for _, k := range f.Keys {
			if k.ID == id && !k.Deleted {
				writeJSON(w, http.StatusOK, keyAnswer(k, false))
				return
			}
		}
		fail(w, http.StatusNotFound, "key not found")

	case strings.HasPrefix(path, tn+"/keys/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(path, tn+"/keys/")
		for i := range f.Keys {
			if f.Keys[i].ID == id && !f.Keys[i].Deleted {
				f.Keys[i].Deleted = true
				w.WriteHeader(http.StatusOK)
				return
			}
		}
		fail(w, http.StatusNotFound, "key not found")

	default:
		fail(w, http.StatusNotFound, "not in the fake: "+r.Method+" "+path)
	}
}

func keyAnswer(k FakeKey, withSecret bool) map[string]any {
	keyType := k.KeyType
	if keyType == "" {
		keyType = "auth"
	}
	out := map[string]any{"id": k.ID, "keyType": keyType, "description": k.Description, "created": k.Created, "expires": k.Expires}
	if k.UserID != "" {
		out["userId"] = k.UserID
	}
	if keyType == "auth" {
		out["capabilities"] = map[string]any{"devices": map[string]any{"create": map[string]any{
			"reusable": k.Reusable, "preauthorized": k.Preauthorized, "tags": k.Tags}}}
	}
	if withSecret {
		out["key"] = k.Secret
	}
	return out
}
