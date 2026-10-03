package tsapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"strings"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

// Credential types.
const (
	TypeToken = "token" // an API access token (tskey-api-...)
	TypeOAuth = "oauth" // an OAuth client (ID plus tskey-client-... secret)
)

// Credentials are what the connector uses to call the API. They live in one file,
// readable only by the owner, and never appear in logs: String and GoString redact.
type Credentials struct {
	Type         string `json:"type"`
	Token        string `json:"token,omitempty"`
	ClientID     string `json:"clientId,omitempty"`
	ClientSecret string `json:"clientSecret,omitempty"`
	Tailnet      string `json:"tailnet,omitempty"` // default "-": the credential's own tailnet
	BaseURL      string `json:"baseURL,omitempty"` // default DefaultBaseURL
}

// ErrNoCredentials means `ovenlight auth set` hasn't been run.
var ErrNoCredentials = errors.New("no Tailscale API credential is set; run `ovenlight auth set` first")

func (c Credentials) String() string {
	return fmt.Sprintf("tsapi.Credentials{%s %s}", c.Type, c.Fingerprint())
}

func (c Credentials) GoString() string { return c.String() }

func (c Credentials) secret() string {
	if c.Type == TypeOAuth {
		return c.ClientSecret
	}
	return c.Token
}

// Fingerprint identifies the secret without revealing it: the start of its SHA-256.
func (c Credentials) Fingerprint() string {
	if c.secret() == "" {
		return "none"
	}
	sum := sha256.Sum256([]byte(c.secret()))
	return "sha256:" + hex.EncodeToString(sum[:])[:16]
}

// Validate checks the credential is complete. It doesn't call the API.
func (c Credentials) Validate() error {
	switch c.Type {
	case TypeToken:
		if strings.TrimSpace(c.Token) == "" {
			return errors.New("the API access token is empty")
		}
	case TypeOAuth:
		if strings.TrimSpace(c.ClientID) == "" || strings.TrimSpace(c.ClientSecret) == "" {
			return errors.New("an OAuth client needs both its client ID and its secret")
		}
	default:
		return fmt.Errorf("unknown credential type %q", c.Type)
	}
	if c.BaseURL != "" {
		u, err := url.Parse(c.BaseURL)
		if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
			return fmt.Errorf("API URL %q isn't an http(s) URL", c.BaseURL)
		}
	}
	return nil
}

// LoadCredentials reads the credential file. It refuses one other users could read.
func LoadCredentials(path string) (Credentials, error) {
	_, err := os.Stat(path)
	if errors.Is(err, fs.ErrNotExist) {
		return Credentials{}, ErrNoCredentials
	}
	if err != nil {
		return Credentials{}, err
	}
	if err := jsonfile.CheckPrivate(path); err != nil {
		return Credentials{}, fmt.Errorf("%w; replace the credential and store the new one with `ovenlight auth set`, which saves it readable only by you", err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Credentials{}, err
	}
	var c Credentials
	if err := json.Unmarshal(data, &c); err != nil {
		return Credentials{}, fmt.Errorf("%s: %w", path, err)
	}
	if err := c.Validate(); err != nil {
		return Credentials{}, fmt.Errorf("%s: %w", path, err)
	}
	return c, nil
}

// SaveCredentials writes the credential file atomically, readable only by the owner, in
// a directory only the owner can enter.
func SaveCredentials(path string, c Credentials) error {
	if err := c.Validate(); err != nil {
		return err
	}
	return jsonfile.Save(path, c)
}
