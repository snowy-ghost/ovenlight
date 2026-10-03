package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// controlAPI is what the daemon needs from the tailnet's control server: *tsapi.Client
// for Tailscale, headscaleAPI for development against a local Headscale.
type controlAPI interface {
	Devices(ctx context.Context) ([]tsapi.Device, error)
	DeleteDevice(ctx context.Context, id string) error
	SetDeviceTags(ctx context.Context, id string, tags []string) error
	DisableKeyExpiry(ctx context.Context, id string) error
	UserLogin(ctx context.Context, id string) (string, error)
	CreateAuthKey(ctx context.Context, r tsapi.KeyRequest) (tsapi.Key, error)
	DeleteKey(ctx context.Context, id string) error
}

// headscaleAPI drives a local Headscale's CLI, which has no Tailscale-compatible API
// for keys and devices. Development only (OVENLIGHT_DEV=1 and --dev-headscale).
type headscaleAPI struct {
	bin, config string
}

func (h headscaleAPI) run(ctx context.Context, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, h.bin, append([]string{"-c", h.config}, args...)...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("headscale %s: %v: %s", strings.Join(args[:min(2, len(args))], " "), err, strings.TrimSpace(stderr.String()))
	}
	return out, nil
}

type headscaleNode struct {
	ID         json.Number `json:"id"`
	Name       string      `json:"name"`
	GivenName  string      `json:"given_name"`
	Tags       []string    `json:"tags"`
	ForcedTags []string    `json:"forced_tags"`
	ValidTags  []string    `json:"valid_tags"`
	User       *struct {
		Name string `json:"name"`
	} `json:"user"`
	IPAddresses []string `json:"ip_addresses"`
}

func (h headscaleAPI) Devices(ctx context.Context) ([]tsapi.Device, error) {
	out, err := h.run(ctx, "nodes", "list", "-o", "json")
	if err != nil {
		return nil, err
	}
	var nodes []headscaleNode
	if err := json.Unmarshal(out, &nodes); err != nil {
		return nil, fmt.Errorf("headscale nodes list: %w", err)
	}
	var devices []tsapi.Device
	for _, n := range nodes {
		var tags []string
		for _, t := range slices.Concat(n.Tags, n.ForcedTags, n.ValidTags) {
			if !slices.Contains(tags, t) {
				tags = append(tags, t)
			}
		}
		d := tsapi.Device{ID: n.ID.String(), NodeID: n.ID.String(), Name: n.GivenName, Hostname: n.Name,
			Tags: tags, Authorized: true, Addresses: n.IPAddresses}
		if n.User != nil && len(tags) == 0 {
			d.User = n.User.Name
		}
		devices = append(devices, d)
	}
	return devices, nil
}

func (h headscaleAPI) DeleteDevice(ctx context.Context, id string) error {
	if _, err := strconv.ParseUint(id, 10, 64); err != nil {
		return fmt.Errorf("headscale node IDs are numbers, not %q", id)
	}
	_, err := h.run(ctx, "nodes", "delete", "-i", id, "--force")
	return err
}

// SetDeviceTags replaces the node's tags (`headscale nodes tag` sets the whole list).
func (h headscaleAPI) SetDeviceTags(ctx context.Context, id string, tags []string) error {
	if _, err := strconv.ParseUint(id, 10, 64); err != nil {
		return fmt.Errorf("headscale node IDs are numbers, not %q", id)
	}
	_, err := h.run(ctx, "nodes", "tag", "-i", id, "-t", strings.Join(tags, ","))
	return err
}

func (h headscaleAPI) DisableKeyExpiry(context.Context, string) error {
	return errors.New("headscale has no per-device key expiry")
}

func (h headscaleAPI) UserLogin(context.Context, string) (string, error) {
	return "", errors.New("headscale keys don't name their user")
}

func (h headscaleAPI) CreateAuthKey(ctx context.Context, r tsapi.KeyRequest) (tsapi.Key, error) {
	args := []string{"preauthkeys", "create", "-e", r.Expiry.String(), "-o", "json"}
	if len(r.Tags) > 0 {
		args = append(args, "--tags", strings.Join(r.Tags, ","))
	}
	out, err := h.run(ctx, args...)
	if err != nil {
		return tsapi.Key{}, err
	}
	var k struct {
		ID         json.Number `json:"id"`
		Key        string      `json:"key"`
		Reusable   bool        `json:"reusable"`
		Expiration struct {
			Seconds int64 `json:"seconds"`
		} `json:"expiration"`
	}
	if err := json.Unmarshal(out, &k); err != nil || k.Key == "" {
		return tsapi.Key{}, fmt.Errorf("headscale preauthkeys create: unexpected answer: %v", err)
	}
	if k.Reusable {
		return tsapi.Key{}, errors.New("headscale made a reusable key")
	}
	return tsapi.Key{ID: k.ID.String(), Key: k.Key, Created: time.Now(), Expires: time.Unix(k.Expiration.Seconds, 0),
		Tags: r.Tags, Preauthorized: true}, nil
}

func (h headscaleAPI) DeleteKey(ctx context.Context, id string) error {
	_, err := h.run(ctx, "preauthkeys", "expire", "-i", id, "--force")
	return err
}
