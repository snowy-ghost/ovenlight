package tsapi_test

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi/tsapitest"
)

func newClient(t *testing.T, f *tsapitest.Fake, creds tsapi.Credentials) *tsapi.Client {
	t.Helper()
	c, err := tsapi.New(creds, nil)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestPolicyReadValidateWriteWithETag(t *testing.T) {
	f := tsapitest.New()
	defer f.Close()
	c := newClient(t, f, f.TokenCredentials())
	ctx := t.Context()

	p, err := c.GetPolicy(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if p.ETag != `"etag-1"` || !strings.Contains(string(p.Body), `"*:*"`) {
		t.Fatalf("policy = %+v", p)
	}
	next := []byte("// with a comment\n{\"acls\": []}")
	if err := c.ValidatePolicy(ctx, next); err != nil {
		t.Fatal(err)
	}
	written, err := c.SetPolicy(ctx, next, p.ETag)
	if err != nil {
		t.Fatal(err)
	}
	if string(written.Body) != string(next) || written.ETag == p.ETag {
		t.Errorf("write answered %+v", written)
	}

	// A write with the old ETag is a conflict, and changes nothing.
	_, err = c.SetPolicy(ctx, []byte(`{}`), p.ETag)
	if !tsapi.IsConflict(err) {
		t.Fatalf("stale ETag: err = %v, want a conflict", err)
	}
	if got, _ := c.GetPolicy(ctx); string(got.Body) != string(next) {
		t.Errorf("policy changed by a rejected write: %s", got.Body)
	}
	// Writing without an ETag is refused before any request.
	before := f.RequestCount("POST /api/v2/tailnet/-/acl")
	if _, err := c.SetPolicy(ctx, next, ""); err == nil || f.RequestCount("POST /api/v2/tailnet/-/acl") != before {
		t.Errorf("write without ETag: err = %v", err)
	}
}

func TestPolicyValidationErrors(t *testing.T) {
	f := tsapitest.New()
	defer f.Close()
	f.Validate = func(p []byte) string {
		if strings.Contains(string(p), "tag:unknown") {
			return `tag "tag:unknown" is not defined in tagOwners`
		}
		return ""
	}
	c := newClient(t, f, f.TokenCredentials())
	bad := []byte(`{"acls": [{"action": "accept", "src": ["tag:unknown"], "dst": ["*:*"]}]}`)

	err := c.ValidatePolicy(t.Context(), bad)
	var v *tsapi.ValidationError
	if !errors.As(err, &v) || !strings.Contains(v.Message, "tag:unknown") || len(v.Details) != 1 {
		t.Fatalf("validate: err = %#v", err)
	}
	p, _ := c.GetPolicy(t.Context())
	_, err = c.SetPolicy(t.Context(), bad, p.ETag)
	if !errors.As(err, &v) {
		t.Fatalf("set: err = %v, want a validation error", err)
	}
	if len(f.PolicyWrites) != 0 {
		t.Error("an invalid policy was stored")
	}
}

func TestDevicesTagDeleteAndAuthorize(t *testing.T) {
	f := tsapitest.New()
	defer f.Close()
	f.Devices = []tsapi.Device{
		{ID: "1", NodeID: "nOwnerCNTRL", Name: "mbp.tail1.ts.net", User: "alex@example.com", Authorized: true},
		{ID: "2", NodeID: "nGuestCNTRL", Name: "sam-iphone.tail1.ts.net", Tags: []string{"tag:ovenlight-guest-coach"}, AdvertisedRoutes: []string{"10.0.0.0/24"}},
	}
	c := newClient(t, f, f.TokenCredentials())
	devices, err := c.Devices(t.Context())
	if err != nil || len(devices) != 2 {
		t.Fatalf("devices = %v, %v", devices, err)
	}
	if !devices[1].HasTag("tag:ovenlight-guest-coach") || devices[0].Tagged() || len(devices[1].AdvertisedRoutes) != 1 {
		t.Errorf("device helpers wrong: %+v", devices)
	}
	// Tags are replaced as a whole on a device that is already tagged.
	if err := c.SetDeviceTags(t.Context(), "nGuestCNTRL", []string{"tag:ovenlight-guest-coach", "tag:ovenlight-guest-notes"}); err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(f.Devices[1].Tags, ","); got != "tag:ovenlight-guest-coach,tag:ovenlight-guest-notes" {
		t.Errorf("tags = %s", got)
	}
	if err := c.SetDeviceTags(t.Context(), "nGone", []string{"tag:x"}); !tsapi.IsNotFound(err) {
		t.Errorf("tagging a missing device: %v", err)
	}
	if err := c.DeleteDevice(t.Context(), "nGuestCNTRL"); err != nil {
		t.Fatal(err)
	}
	if err := c.DeleteDevice(t.Context(), "nGuestCNTRL"); !tsapi.IsNotFound(err) {
		t.Errorf("second delete: err = %v, want not found", err)
	}
	if len(f.Devices) != 1 {
		t.Errorf("devices left: %v", f.Devices)
	}
}

func TestAuthKeys(t *testing.T) {
	f := tsapitest.New()
	defer f.Close()
	c := newClient(t, f, f.TokenCredentials())
	k, err := c.CreateAuthKey(t.Context(), tsapi.KeyRequest{Tags: []string{"tag:ovenlight-guest"}, Expiry: 24 * time.Hour, Description: "ovenlight invite for Sam (ab12)!"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(k.Key, "tskey-auth-") || k.Reusable || k.Preauthorized || len(k.Tags) != 1 {
		t.Errorf("key = %+v", k)
	}
	if d := k.Expires.Sub(k.Created); d != 24*time.Hour {
		t.Errorf("expiry = %v", d)
	}
	if got := f.Keys[0].Description; got != "ovenlight invite for Sam -ab12-" {
		t.Errorf("description sent as %q", got)
	}
	f.ListKeyIDsOnly = true // each key is then read on its own
	keys, err := c.Keys(t.Context())
	if err != nil || len(keys) != 1 || keys[0].ID != k.ID || !slices.Equal(keys[0].Tags, k.Tags) {
		t.Fatalf("keys listed by ID = %+v, %v", keys, err)
	}
	f.ListKeyIDsOnly = false
	f.Keys = append(f.Keys, tsapitest.FakeKey{Key: tsapi.Key{ID: "kApi", KeyType: "api"}}) // an API token: not listed
	keys, err = c.Keys(t.Context())
	if err != nil || len(keys) != 1 || keys[0].ID != k.ID || keys[0].Key != "" || !slices.Equal(keys[0].Tags, k.Tags) {
		t.Fatalf("keys = %+v, %v", keys, err)
	}
	if got, err := c.Key(t.Context(), k.ID); err != nil || got.Key != "" || !slices.Equal(got.Tags, k.Tags) || !got.Expires.Equal(k.Expires) {
		t.Errorf("key = %+v, %v", got, err)
	}
	if err := c.DeleteKey(t.Context(), k.ID); err != nil {
		t.Fatal(err)
	}
	if keys, _ := c.Keys(t.Context()); len(keys) != 0 {
		t.Errorf("key still listed after delete")
	}
}

// A key the API made reusable, or without the tags asked for, is refused and deleted.
func TestMisbehavingKeyIsRefused(t *testing.T) {
	for name, set := range map[string]func(*tsapitest.Fake){
		"reusable": func(f *tsapitest.Fake) { f.ForceReusable = true },
		"untagged": func(f *tsapitest.Fake) { f.DropKeyTags = true },
	} {
		f := tsapitest.New()
		defer f.Close()
		set(f)
		c := newClient(t, f, f.TokenCredentials())
		if _, err := c.CreateAuthKey(t.Context(), tsapi.KeyRequest{Tags: []string{"tag:x"}, Expiry: time.Hour}); err == nil {
			t.Errorf("%s: the key was accepted", name)
		}
		if !f.Keys[0].Deleted {
			t.Errorf("%s: the key wasn't deleted", name)
		}
	}
}

func TestOAuthClientCredentials(t *testing.T) {
	f := tsapitest.New()
	defer f.Close()
	f.ClientID, f.ClientSecret = "kClient1", "tskey-client-kClient1-secret"
	f.Token = "" // only OAuth works
	c := newClient(t, f, tsapi.Credentials{Type: tsapi.TypeOAuth, ClientID: f.ClientID, ClientSecret: f.ClientSecret, BaseURL: f.URL()})
	for range 3 {
		if _, err := c.Devices(t.Context()); err != nil {
			t.Fatal(err)
		}
	}
	if n := f.IssuedTokens(); n != 1 {
		t.Errorf("issued %d tokens for 3 calls, want 1 (cached)", n)
	}
	// The server forgets the token: the client gets a new one and retries once.
	f.ForgetTokens()
	if _, err := c.Devices(t.Context()); err != nil {
		t.Fatalf("after token loss: %v", err)
	}
	// A wrong secret is an error that doesn't contain the secret.
	bad := newClient(t, f, tsapi.Credentials{Type: tsapi.TypeOAuth, ClientID: "kClient1", ClientSecret: "tskey-client-wrong-SECRET", BaseURL: f.URL()})
	_, err := bad.Devices(t.Context())
	if err == nil || strings.Contains(err.Error(), "SECRET") {
		t.Errorf("bad secret: err = %v", err)
	}
}

func TestErrorsNeverContainTheToken(t *testing.T) {
	f := tsapitest.New()
	defer f.Close()
	c := newClient(t, f, tsapi.Credentials{Type: tsapi.TypeToken, Token: "tskey-api-WRONG-TOKEN", BaseURL: f.URL()})
	_, err := c.Devices(t.Context())
	var apiErr *tsapi.APIError
	if !errors.As(err, &apiErr) || apiErr.StatusCode != 401 {
		t.Fatalf("err = %v", err)
	}
	if strings.Contains(err.Error(), "WRONG-TOKEN") {
		t.Errorf("error leaks the token: %v", err)
	}
	// Unreachable server: the transport error doesn't carry the token either.
	down := newClient(t, f, tsapi.Credentials{Type: tsapi.TypeToken, Token: "tskey-api-WRONG-TOKEN", BaseURL: "http://127.0.0.1:1"})
	if _, err := down.Devices(t.Context()); err == nil || strings.Contains(err.Error(), "WRONG-TOKEN") {
		t.Errorf("transport error: %v", err)
	}
}

func TestCredentialsFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg", "credentials.json")
	if _, err := tsapi.LoadCredentials(path); !errors.Is(err, tsapi.ErrNoCredentials) {
		t.Fatalf("missing file: err = %v", err)
	}
	creds := tsapi.Credentials{Type: tsapi.TypeToken, Token: "tskey-api-abc123-SECRETPART"} // gitleaks:allow (a made-up test value)
	if err := tsapi.SaveCredentials(path, creds); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{path, filepath.Dir(path)} {
		if err := jsonfile.CheckPrivate(p); err != nil {
			t.Error(err)
		}
	}
	got, err := tsapi.LoadCredentials(path)
	if err != nil || got != creds {
		t.Fatalf("round trip: %v %v", got, err)
	}
	for _, s := range []string{fmt.Sprint(got), fmt.Sprintf("%+v", got), fmt.Sprintf("%#v", got), got.Fingerprint()} {
		if strings.Contains(s, "SECRETPART") {
			t.Errorf("secret printed: %s", s)
		}
	}
	if !strings.HasPrefix(got.Fingerprint(), "sha256:") || len(got.Fingerprint()) != 23 {
		t.Errorf("fingerprint = %q", got.Fingerprint())
	}
	if runtime.GOOS != "windows" { // jsonfile's tests cover Windows access lists
		os.Chmod(path, 0o644)
		if _, err := tsapi.LoadCredentials(path); err == nil || !strings.Contains(err.Error(), "ovenlight auth set") {
			t.Errorf("world-readable file accepted: %v", err)
		}
	}
	for _, bad := range []tsapi.Credentials{{Type: tsapi.TypeToken}, {Type: tsapi.TypeOAuth, ClientSecret: "x"}, {Type: "password", Token: "x"}, {Type: tsapi.TypeToken, Token: "x", BaseURL: "ftp://x"}} {
		if bad.Validate() == nil {
			t.Errorf("%v should be invalid", bad)
		}
	}
}
