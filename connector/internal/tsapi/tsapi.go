// Package tsapi is the small part of Tailscale's API (v2) that sharing needs: the
// policy file, devices and auth keys. It authenticates with an API
// access token or an OAuth client (client-credentials flow) and never puts a secret in
// an error or log line.
package tsapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
)

// DefaultBaseURL is Tailscale's API server.
const DefaultBaseURL = "https://api.tailscale.com"

const maxResponse = 10 << 20

// Client calls the Tailscale API for one tailnet.
type Client struct {
	baseURL string
	tailnet string
	http    *http.Client
	creds   Credentials

	mu       sync.Mutex
	token    string // OAuth access token
	tokenExp time.Time
}

// New returns a client for the credentials. hc may be nil.
func New(creds Credentials, hc *http.Client) (*Client, error) {
	if err := creds.Validate(); err != nil {
		return nil, err
	}
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	base := strings.TrimRight(creds.BaseURL, "/")
	if base == "" {
		base = DefaultBaseURL
	}
	tailnet := creds.Tailnet
	if tailnet == "" {
		tailnet = "-" // the tailnet the credential belongs to
	}
	return &Client{baseURL: base, tailnet: tailnet, http: hc, creds: creds}, nil
}

// APIError is a non-2xx answer from the API.
type APIError struct {
	Method, Path string
	StatusCode   int
	Message      string
}

func (e *APIError) Error() string {
	msg := fmt.Sprintf("tailscale api: %s %s: %d %s", e.Method, e.Path, e.StatusCode, http.StatusText(e.StatusCode))
	if e.Message != "" {
		msg += ": " + e.Message
	}
	return msg
}

// IsConflict reports a policy write rejected because the policy changed since it was
// read (412 on If-Match).
func IsConflict(err error) bool {
	var e *APIError
	return errors.As(err, &e) && e.StatusCode == http.StatusPreconditionFailed
}

// IsUnauthorized reports a 401: the credential expired or was revoked.
func IsUnauthorized(err error) bool {
	var e *APIError
	return errors.As(err, &e) && e.StatusCode == http.StatusUnauthorized
}

// IsNotFound reports a 404, for example deleting a device that is already gone.
func IsNotFound(err error) bool {
	var e *APIError
	return errors.As(err, &e) && e.StatusCode == http.StatusNotFound
}

// ValidationError is a policy the API refused, with its explanation.
type ValidationError struct {
	Message string
	Details []string
}

func (e *ValidationError) Error() string {
	if len(e.Details) == 0 {
		return "tailscale rejected the policy: " + e.Message
	}
	return "tailscale rejected the policy: " + e.Message + "\n  " + strings.Join(e.Details, "\n  ")
}

func (c *Client) tailnetPath(rest string) string {
	return "/api/v2/tailnet/" + url.PathEscape(c.tailnet) + rest
}

// do sends one request. With an OAuth client it fetches or refreshes the access token,
// and retries once if the API says the cached one is no longer valid.
func (c *Client) do(ctx context.Context, method, path string, body []byte, header http.Header) (*http.Response, []byte, error) {
	for attempt := 0; ; attempt++ {
		auth, err := c.authorization(ctx)
		if err != nil {
			return nil, nil, err
		}
		var rd io.Reader
		if body != nil {
			rd = bytes.NewReader(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, rd)
		if err != nil {
			return nil, nil, err
		}
		for k, v := range header {
			req.Header[k] = v
		}
		req.Header.Set("Authorization", auth)
		req.Header.Set("User-Agent", "ovenlight-connector")
		resp, err := c.http.Do(req)
		if err != nil {
			return nil, nil, fmt.Errorf("tailscale api: %s %s: %w", method, path, redactURLError(err))
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponse))
		resp.Body.Close()
		if err != nil {
			return nil, nil, fmt.Errorf("tailscale api: %s %s: reading the answer: %w", method, path, err)
		}
		if resp.StatusCode == http.StatusUnauthorized && c.creds.Type == TypeOAuth && attempt == 0 {
			c.mu.Lock()
			c.token = ""
			c.mu.Unlock()
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode > 299 {
			return resp, data, &APIError{Method: method, Path: path, StatusCode: resp.StatusCode, Message: errorMessage(data)}
		}
		return resp, data, nil
	}
}

func errorMessage(data []byte) string {
	var body struct {
		Message string `json:"message"`
	}
	if json.Unmarshal(data, &body) == nil && body.Message != "" {
		return body.Message
	}
	s := strings.TrimSpace(string(data))
	if len(s) > 300 {
		s = s[:300] + "..."
	}
	return s
}

// redactURLError drops the URL from transport errors; it carries no secret today, but
// query strings are where secrets end up.
func redactURLError(err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Err
	}
	return err
}

func (c *Client) authorization(ctx context.Context) (string, error) {
	if c.creds.Type == TypeToken {
		return "Bearer " + c.creds.Token, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.token != "" && time.Now().Before(c.tokenExp) {
		return "Bearer " + c.token, nil
	}
	form := url.Values{"client_id": {c.creds.ClientID}, "client_secret": {c.creds.ClientSecret}, "grant_type": {"client_credentials"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/v2/oauth/token", strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("tailscale api: getting an OAuth token: %w", redactURLError(err))
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, maxResponse))
	if resp.StatusCode != http.StatusOK {
		return "", &APIError{Method: http.MethodPost, Path: "/api/v2/oauth/token", StatusCode: resp.StatusCode, Message: errorMessage(data)}
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(data, &tok); err != nil || tok.AccessToken == "" {
		return "", errors.New("tailscale api: the OAuth token answer had no access token")
	}
	lifetime := time.Duration(tok.ExpiresIn) * time.Second
	if lifetime <= 0 {
		lifetime = time.Hour
	}
	c.token = tok.AccessToken
	c.tokenExp = time.Now().Add(lifetime - min(lifetime/10, time.Minute)) // refresh a little early
	return "Bearer " + c.token, nil
}

// ---------- policy ----------

// Policy is the tailnet policy file as written (HuJSON) and its version tag.
type Policy struct {
	Body []byte
	ETag string
}

var hujsonHeader = http.Header{"Accept": {"application/hujson"}}

// GetPolicy reads the policy file with its comments.
func (c *Client) GetPolicy(ctx context.Context) (Policy, error) {
	resp, data, err := c.do(ctx, http.MethodGet, c.tailnetPath("/acl"), nil, hujsonHeader)
	if err != nil {
		return Policy{}, err
	}
	etag := resp.Header.Get("ETag")
	if etag == "" {
		return Policy{}, errors.New("tailscale api: the policy came without an ETag, so it can't be updated safely")
	}
	return Policy{Body: data, ETag: etag}, nil
}

// ValidatePolicy asks Tailscale whether the policy is acceptable, without saving it.
// A refusal is a *ValidationError.
func (c *Client) ValidatePolicy(ctx context.Context, body []byte) error {
	_, data, err := c.do(ctx, http.MethodPost, c.tailnetPath("/acl/validate"), body, http.Header{"Content-Type": {"application/hujson"}})
	if err != nil {
		var apiErr *APIError
		if errors.As(err, &apiErr) && apiErr.StatusCode == http.StatusBadRequest {
			return &ValidationError{Message: apiErr.Message}
		}
		return err
	}
	var result struct {
		Message string            `json:"message"`
		Data    []json.RawMessage `json:"data"`
	}
	if len(bytes.TrimSpace(data)) > 0 {
		if err := json.Unmarshal(data, &result); err != nil {
			return fmt.Errorf("tailscale api: unexpected validation answer: %w", err)
		}
	}
	if result.Message == "" && len(result.Data) == 0 {
		return nil
	}
	v := &ValidationError{Message: result.Message}
	for _, d := range result.Data {
		v.Details = append(v.Details, strings.TrimSpace(string(d)))
	}
	if v.Message == "" {
		v.Message = "policy tests failed"
	}
	return v
}

// SetPolicy replaces the policy, but only if it is still at etag (If-Match). A policy
// changed in the meantime fails with an error for which IsConflict is true.
func (c *Client) SetPolicy(ctx context.Context, body []byte, etag string) (Policy, error) {
	if etag == "" {
		return Policy{}, errors.New("tailscale api: refusing to write the policy without the ETag it was read with")
	}
	h := http.Header{"Content-Type": {"application/hujson"}, "Accept": {"application/hujson"}, "If-Match": {etag}}
	resp, data, err := c.do(ctx, http.MethodPost, c.tailnetPath("/acl"), body, h)
	if err != nil {
		var apiErr *APIError
		if errors.As(err, &apiErr) && apiErr.StatusCode == http.StatusBadRequest {
			return Policy{}, &ValidationError{Message: apiErr.Message}
		}
		return Policy{}, err
	}
	return Policy{Body: data, ETag: resp.Header.Get("ETag")}, nil
}

// ---------- devices ----------

// Device is one device in the tailnet.
type Device struct {
	ID               string   `json:"id"`     // legacy numeric ID
	NodeID           string   `json:"nodeId"` // stable ID, as WhoIs reports it
	Name             string   `json:"name"`   // MagicDNS name
	Hostname         string   `json:"hostname"`
	User             string   `json:"user"`
	Tags             []string `json:"tags"`
	Authorized       bool     `json:"authorized"`
	Created          string   `json:"created"`
	OS               string   `json:"os"`
	Addresses        []string `json:"addresses"`
	AdvertisedRoutes []string `json:"advertisedRoutes,omitempty"` // subnet routes it asks to expose
	EnabledRoutes    []string `json:"enabledRoutes,omitempty"`    // the ones an admin approved
}

// Tagged reports whether the device has any tag (and so no user behind it).
func (d Device) Tagged() bool { return len(d.Tags) > 0 }

// HasTag reports whether the device carries tag.
func (d Device) HasTag(tag string) bool { return slices.Contains(d.Tags, tag) }

// Devices lists every device in the tailnet.
func (c *Client) Devices(ctx context.Context) ([]Device, error) {
	_, data, err := c.do(ctx, http.MethodGet, c.tailnetPath("/devices?fields=all"), nil, nil)
	if err != nil {
		return nil, err
	}
	var body struct {
		Devices []Device `json:"devices"`
	}
	if err := json.Unmarshal(data, &body); err != nil {
		return nil, fmt.Errorf("tailscale api: unexpected device list: %w", err)
	}
	return body.Devices, nil
}

// DeleteDevice removes a device from the tailnet; it has to log in again to come back.
func (c *Client) DeleteDevice(ctx context.Context, id string) error {
	_, _, err := c.do(ctx, http.MethodDelete, "/api/v2/device/"+url.PathEscape(id), nil, nil)
	return err
}

// SetDeviceTags replaces a device's tags (POST /api/v2/device/{id}/tags, "The new list
// of tags for the device", OAuth scope devices:core; operationId setDeviceTags in
// https://api.tailscale.com/api/v2?outputOpenapiSchema=true). It works on a device that
// is already tagged: "A single node can have multiple tags assigned."
func (c *Client) SetDeviceTags(ctx context.Context, id string, tags []string) error {
	if tags == nil {
		tags = []string{}
	}
	body, _ := json.Marshal(map[string][]string{"tags": tags})
	_, _, err := c.do(ctx, http.MethodPost, "/api/v2/device/"+url.PathEscape(id)+"/tags", body, http.Header{"Content-Type": {"application/json"}})
	return err
}

// DisableKeyExpiry stops a device's node key from expiring (POST /api/v2/device/{id}/key
// with keyExpiryDisabled, OAuth scope devices:core; operationId updateDeviceKey).
func (c *Client) DisableKeyExpiry(ctx context.Context, id string) error {
	body, _ := json.Marshal(map[string]bool{"keyExpiryDisabled": true})
	_, _, err := c.do(ctx, http.MethodPost, "/api/v2/device/"+url.PathEscape(id)+"/key", body, http.Header{"Content-Type": {"application/json"}})
	return err
}

// ---------- auth keys ----------

// KeyRequest describes a single-use auth key to create.
type KeyRequest struct {
	Tags          []string
	Preauthorized bool
	Expiry        time.Duration
	Description   string
}

// Key is an auth key. Key (the secret) is only set on the answer to CreateAuthKey.
type Key struct {
	ID            string    `json:"id"`
	KeyType       string    `json:"keyType,omitempty"` // "auth", or an API token or OAuth client
	Key           string    `json:"key,omitempty"`
	Description   string    `json:"description,omitempty"`
	Created       time.Time `json:"created"`
	Expires       time.Time `json:"expires"`
	Revoked       time.Time `json:"revoked,omitzero"`
	Invalid       bool      `json:"invalid,omitempty"`
	UserID        string    `json:"userId,omitempty"` // who made it; empty for keys made through OAuth
	Tags          []string  `json:"-"`
	Reusable      bool      `json:"-"`
	Preauthorized bool      `json:"-"`
}

type keyCapabilities struct {
	Devices struct {
		Create struct {
			Reusable      bool     `json:"reusable"`
			Preauthorized bool     `json:"preauthorized"`
			Tags          []string `json:"tags,omitempty"`
		} `json:"create"`
	} `json:"devices"`
}

type keyJSON struct {
	Key
	Capabilities *keyCapabilities `json:"capabilities"`
}

func (k keyJSON) key() Key {
	out := k.Key
	if k.Capabilities == nil {
		return out
	}
	out.Tags = k.Capabilities.Devices.Create.Tags
	out.Reusable = k.Capabilities.Devices.Create.Reusable
	out.Preauthorized = k.Capabilities.Devices.Create.Preauthorized
	return out
}

var descriptionUnsafe = regexp.MustCompile(`[^A-Za-z0-9 _-]+`)

// CreateAuthKey mints a single-use auth key. It refuses, and deletes, a key the API made
// reusable or tagged otherwise: a key without its tags would register the device as the
// credential owner's own.
func (c *Client) CreateAuthKey(ctx context.Context, r KeyRequest) (Key, error) {
	var caps keyCapabilities
	caps.Devices.Create.Preauthorized = r.Preauthorized
	caps.Devices.Create.Tags = r.Tags
	desc := descriptionUnsafe.ReplaceAllString(r.Description, "-")
	if len(desc) > 50 {
		desc = desc[:50]
	}
	body, _ := json.Marshal(struct {
		Capabilities  keyCapabilities `json:"capabilities"`
		ExpirySeconds int64           `json:"expirySeconds"`
		Description   string          `json:"description,omitempty"`
	}{caps, int64(r.Expiry / time.Second), desc})
	_, data, err := c.do(ctx, http.MethodPost, c.tailnetPath("/keys"), body, http.Header{"Content-Type": {"application/json"}})
	if err != nil {
		return Key{}, err
	}
	var k keyJSON
	if err := json.Unmarshal(data, &k); err != nil || k.Key.Key == "" || k.ID == "" {
		return Key{}, errors.New("tailscale api: the new auth key answer had no key")
	}
	key := k.key()
	switch {
	case key.Reusable:
		c.DeleteKey(ctx, key.ID)
		return Key{}, errors.New("tailscale api: asked for a single-use key but got a reusable one; deleted it")
	case !slices.Equal(slices.Sorted(slices.Values(key.Tags)), slices.Sorted(slices.Values(r.Tags))):
		c.DeleteKey(ctx, key.ID)
		return Key{}, fmt.Errorf("tailscale api: asked for a key tagged %v but got %v; deleted it", r.Tags, key.Tags)
	}
	return key, nil
}

// Keys lists every auth key in the tailnet, not only the credential's user's, with its
// tags and state; API tokens and OAuth clients are left out. An entry that comes without
// its capabilities (older API versions list only IDs) is read with Key, and one deleted
// in between is left out.
func (c *Client) Keys(ctx context.Context) ([]Key, error) {
	_, data, err := c.do(ctx, http.MethodGet, c.tailnetPath("/keys?all=true"), nil, nil)
	if err != nil {
		return nil, err
	}
	var body struct {
		Keys []keyJSON `json:"keys"`
	}
	if err := json.Unmarshal(data, &body); err != nil {
		return nil, fmt.Errorf("tailscale api: unexpected key list: %w", err)
	}
	var out []Key
	for _, k := range body.Keys {
		switch {
		case k.KeyType != "" && k.KeyType != "auth":
		case k.Capabilities != nil:
			out = append(out, k.key())
		default:
			full, err := c.Key(ctx, k.ID)
			if IsNotFound(err) {
				continue
			}
			if err != nil {
				return nil, err
			}
			out = append(out, full)
		}
	}
	return out, nil
}

// Key reads one auth key's details (its tags, expiry and state), without its secret.
func (c *Client) Key(ctx context.Context, id string) (Key, error) {
	_, data, err := c.do(ctx, http.MethodGet, c.tailnetPath("/keys/"+url.PathEscape(id)), nil, nil)
	if err != nil {
		return Key{}, err
	}
	var k keyJSON
	if err := json.Unmarshal(data, &k); err != nil {
		return Key{}, fmt.Errorf("tailscale api: unexpected key: %w", err)
	}
	return k.key(), nil
}

// DeleteKey revokes an auth key.
func (c *Client) DeleteKey(ctx context.Context, id string) error {
	_, _, err := c.do(ctx, http.MethodDelete, c.tailnetPath("/keys/"+url.PathEscape(id)), nil, nil)
	return err
}

// UserLogin returns a user's login name (GET /api/v2/users/{id}, operationId getUser).
func (c *Client) UserLogin(ctx context.Context, id string) (string, error) {
	_, data, err := c.do(ctx, http.MethodGet, "/api/v2/users/"+url.PathEscape(id), nil, nil)
	if err != nil {
		return "", err
	}
	var u struct {
		LoginName string `json:"loginName"`
	}
	if err := json.Unmarshal(data, &u); err != nil || u.LoginName == "" {
		return "", errors.New("tailscale api: the user answer had no login name")
	}
	return u.LoginName, nil
}
