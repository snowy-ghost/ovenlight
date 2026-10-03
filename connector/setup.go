package main

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"reflect"
	"slices"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// policyAPI is the part of the Tailscale API setup-sharing uses.
type policyAPI interface {
	GetPolicy(ctx context.Context) (tsapi.Policy, error)
	ValidatePolicy(ctx context.Context, body []byte) error
	SetPolicy(ctx context.Context, body []byte, etag string) (tsapi.Policy, error)
	Devices(ctx context.Context) ([]tsapi.Device, error)
	Keys(ctx context.Context) ([]tsapi.Key, error)
}

var (
	errNotConfirmed = errors.New("nothing changed")
	errRefused      = errors.New("the policy needs a manual edit first")
)

// setupRun is one policy change, by setup-sharing or publish --shareable. Everything it
// touches is injected, so the whole flow, rollback included, runs in tests against the
// fake API.
type setupRun struct {
	api      policyAPI
	health   func() (map[string][]peerInfo, error) // what the app nodes see; nil when the daemon isn't running
	in       *bufio.Reader
	out      io.Writer
	stateDir string
	owner    string
	apps     []string // shareable apps: the policy gets their tags and rules
	remove   []string // apps no longer shared: their tags and rules go
	// known, set by publish --shareable, is every app this connector publishes: the
	// change is refused while a device carries another app's tag (see oneConnector).
	known []string

	newOwner           bool          // the owner is being recorded or changed: refused unless a device in the tailnet is theirs
	confirmOwnerChange bool          // the recorded owner changes, so confirm even without a policy change
	apiTimeout         time.Duration // bounds the API calls on each side of the owner's yes (default setupTimeout)
	settleMin          time.Duration // wait at least this long before trusting the health check
	settleMax          time.Duration // give up (and roll back) after this long
	poll               time.Duration // between health checks, and between tries of a call that got no answer
}

const (
	setupTimeout    = 5 * time.Minute
	rollbackTimeout = 2 * time.Minute
)

// withTimeout starts the clock for API calls. It starts again after the owner's yes, so
// the time spent reading the diff doesn't count against the write, the check or the
// rollback.
func (s *setupRun) withTimeout(parent context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(parent, cmp.Or(s.apiTimeout, setupTimeout))
}

func (s *setupRun) printf(format string, args ...any) { fmt.Fprintf(s.out, format, args...) }

// confirm asks for the word yes. Anything else, or end of input, is a no.
func (s *setupRun) confirm(prompt string) bool {
	s.printf("%s", prompt)
	line, _ := s.in.ReadString('\n')
	return strings.EqualFold(strings.TrimSpace(line), "yes")
}

func backupDir(stateDir string) string { return filepath.Join(stateDir, "policy-backups") }

const pendingSuffix = ".pending"

// saveBackup writes the policy as it was before a change aside, readable only by the
// owner, so a full disk stops the change before it is written. keepBackup files it
// where --rollback looks only once the change may be live: a change that never landed
// leaves the last real backup the newest.
func saveBackup(stateDir string, body []byte, now time.Time) (string, error) {
	dir := backupDir(stateDir)
	if err := jsonfile.MkdirPrivate(dir); err != nil {
		return "", err
	}
	path := filepath.Join(dir, now.UTC().Format("20060102T150405.000Z")+".hujson"+pendingSuffix)
	if err := os.WriteFile(path, body, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

// keepBackup returns where the backup is kept: where --rollback finds it, or, if moving
// it there fails, where it was written.
func keepBackup(pending string) string {
	path := strings.TrimSuffix(pending, pendingSuffix)
	if os.Rename(pending, path) != nil {
		return pending
	}
	return path
}

// latestBackup is the newest saved policy.
func latestBackup(stateDir string) (string, []byte, error) {
	entries, err := os.ReadDir(backupDir(stateDir))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", nil, err
	}
	var names []string
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".hujson") {
			names = append(names, e.Name())
		}
	}
	if len(names) == 0 {
		return "", nil, errors.New("no policy backup yet; Ovenlight saves one before it changes the policy")
	}
	sort.Strings(names)
	path := filepath.Join(backupDir(stateDir), names[len(names)-1])
	body, err := os.ReadFile(path)
	return path, body, err
}

func policyDevices(devices []tsapi.Device) []policy.Device {
	out := make([]policy.Device, len(devices))
	for i, d := range devices {
		name := d.Hostname
		if name == "" {
			name = d.Name
		}
		out[i] = policy.Device{Name: name, Tags: d.Tags, Routes: slices.Concat(d.AdvertisedRoutes, d.EnabledRoutes)}
	}
	return out
}

// oneConnector refuses a tailnet where a device carries the tag of an app this
// connector doesn't publish, as another connector's app node does, or one left from an
// app unpublished without an API credential. Each connector keeps its own guest records
// while tags and a guest's phone reach across the tailnet, so two would mix up each
// other's guests.
func oneConnector(devices []tsapi.Device, known []string) error {
	for _, d := range devices {
		for _, tag := range d.Tags {
			if slug, ok := strings.CutPrefix(tag, policy.AppTagPrefix); ok && !slices.Contains(known, slug) {
				name := cmp.Or(d.Hostname, d.Name)
				return fmt.Errorf("%s in your tailnet is a node for %s (tagged %s), which isn't in this connector's config. "+
					"If you unpublished %s, delete %s in the Tailscale admin console (Machines) and run this again. "+
					"If another connector shares %s, make apps shareable on that one: Ovenlight supports one sharing connector per tailnet", name, slug, tag, slug, name, slug)
			}
		}
	}
	return nil
}

// ownsADevice reports whether an untagged device in the tailnet belongs to login. A
// tagged device can name whoever tagged it as its user, so it doesn't count.
func ownsADevice(devices []tsapi.Device, login string) bool {
	return slices.ContainsFunc(devices, func(d tsapi.Device) bool { return !d.Tagged() && policy.EqualFoldASCII(d.User, login) })
}

func countOwnerPeers(peers []peerInfo, owner string) int {
	n := 0
	for _, p := range peers {
		if p.Login != "" && policy.EqualFoldASCII(p.Login, owner) {
			n++
		}
	}
	return n
}

// apply plans the change, shows it, and applies it only after the owner types yes:
// validate, write with If-Match, keep the old policy as a backup, read back, check the
// app nodes, and roll back to the backup if any step after the write fails. It reports
// whether the policy changed.
func (s *setupRun) apply(parent context.Context) (bool, error) {
	ctx, cancel := s.withTimeout(parent)
	defer cancel()
	pol, err := s.api.GetPolicy(ctx)
	if err != nil {
		return false, err
	}
	devices, err := s.api.Devices(ctx)
	if err != nil {
		return false, err
	}
	if s.known != nil {
		if err := oneConnector(devices, s.known); err != nil {
			return false, err
		}
	}
	// A mistyped owner would be refused on every app, and the real one with them.
	if s.newOwner && !ownsADevice(devices, s.owner) {
		return false, fmt.Errorf("no device in this tailnet belongs to %s, so nothing changed. Check the spelling, or, if that login has no device here yet, "+
			"sign in with it to Ovenlight on the iPhone first (Use My Own Computers, then Connect) and run this again", s.owner)
	}
	cfg := policy.Config{Owner: s.owner, Apps: s.apps, Remove: s.remove}
	proposed, diff, report, err := policy.Plan(pol.Body, policyDevices(devices), cfg)
	var refusal *policy.Refusal
	if errors.As(err, &refusal) {
		s.printf("%s\n", refusal.Error())
		return false, errRefused
	}
	if err != nil {
		return false, err
	}

	s.printf("Owner: %s\n\n", s.owner)
	if !report.Changed() {
		s.printf("Your tailnet policy already has what sharing needs%s; nothing to change.\n", appList(s.apps))
		for _, n := range report.Notes {
			s.printf("Note: %s\n", n)
		}
		if s.confirmOwnerChange && !s.confirm("\nType yes to record "+s.owner+" as the owner: ") {
			return false, errNotConfirmed
		}
		return false, nil
	}
	s.printf("Proposed change to your tailnet policy:\n")
	for _, c := range report.Changes {
		s.printf("  - %s\n", c)
	}
	s.printf("\n%s\n", diff)
	s.printf("Safety report:\n")
	s.printf("  - Each shareable app has its own tags: guests invited to an app (%s) reach only that app's node (%s),\n    on port %d: not your devices, not your other apps, not the admin port %d.\n",
		policy.GuestTag("<app>"), policy.AppTag("<app>"), policy.DefaultPort, adminPort)
	if len(report.Exposed) == 0 {
		s.printf("  - Guests see only the app nodes they may reach, never your devices.\n")
	} else {
		s.printf("  - Guests see the devices that %s let reach them (names and addresses), but can't connect to them; see the note below.\n", strings.Join(report.Exposed, " and "))
	}
	s.printf("  - The connector still checks each guest's invite for each app, as a second lock.\n")
	s.printf("  - Nothing else in the policy changes. The old policy is kept as a backup, and the change is rolled back automatically if Tailscale\n    doesn't return it as written or your app nodes stop seeing your devices. `ovenlight setup-sharing --rollback` restores the backup later.\n")
	for _, n := range report.Notes {
		s.printf("  - Note: %s\n", n)
	}
	if !s.confirm("\nType yes to apply this change: ") {
		s.printf("Nothing changed.\n")
		return false, errNotConfirmed
	}
	ctx, cancel = s.withTimeout(parent)
	defer cancel()
	// A device may have changed while the owner read the plan (a policy edit fails If-Match).
	if devices, err = s.api.Devices(ctx); err != nil {
		return false, err
	}
	again, _, againReport, err := policy.Plan(pol.Body, policyDevices(devices), cfg)
	if err != nil || !bytes.Equal(again, proposed) || !reflect.DeepEqual(againReport, report) {
		return false, errors.New("your tailnet's devices changed while you read the plan, so nothing changed; run this again to see the new one")
	}

	pending, err := saveBackup(s.stateDir, pol.Body, time.Now())
	if err != nil {
		return false, fmt.Errorf("couldn't save a backup, so nothing changed: %w", err)
	}
	if err := s.api.ValidatePolicy(ctx, proposed); err != nil {
		os.Remove(pending)
		return false, fmt.Errorf("nothing changed: %w", err)
	}
	// Only an app node that sees the owner's devices now can show the change cut them off.
	var watched []string
	unchecked := "the connector isn't running, so that is the only check"
	if s.health != nil {
		before, err := s.health()
		for slug, peers := range before {
			if countOwnerPeers(peers, s.owner) > 0 {
				watched = append(watched, slug)
			}
		}
		slices.Sort(watched)
		if err != nil {
			unchecked = "the connector didn't say what its app nodes see (" + err.Error() + "), so that is the only check"
		} else {
			unchecked = "no app node saw your devices before the change, so that is the only check"
		}
	}

	// Until the backup is filed or removed, a Ctrl-C or closed terminal would hide it from --rollback.
	signal.Ignore(os.Interrupt, syscall.SIGHUP)
	written, err := s.api.SetPolicy(ctx, proposed, pol.ETag)
	if err != nil {
		var changed bool
		if written, changed, err = s.afterFailedWrite(ctx, pol, proposed, pending, err); err != nil {
			signal.Reset(os.Interrupt, syscall.SIGHUP)
			return changed, err
		}
	}
	backup := keepBackup(pending)
	signal.Reset(os.Interrupt, syscall.SIGHUP)
	s.printf("Applied; the old policy is saved in %s. Checking...\n", backup)

	got, err := s.livePolicy(ctx)
	if err != nil {
		return true, s.rollbackTo(ctx, pol.Body, proposed, written.ETag, backup, "couldn't read the policy back: "+err.Error())
	}
	if same, err := policy.Equivalent(got.Body, proposed); err != nil || !same {
		return true, s.rollbackTo(ctx, pol.Body, proposed, written.ETag, backup, "Tailscale returned a different policy than the one written")
	}
	if len(watched) == 0 {
		s.printf("Done: the policy reads back as written; %s.\n", unchecked)
		return true, nil
	}
	if err := s.healthCheck(ctx, watched); err != nil {
		return true, s.rollbackTo(ctx, pol.Body, proposed, written.ETag, backup, err.Error())
	}
	s.printf("Done: the policy reads back as written, and your app nodes still see your devices.\n")
	return true, nil
}

// afterFailedWrite works out what a failed write of proposed over pol did. A conflict or
// a refusal changed nothing. Any other failure (no answer, a server error) may have come
// after the write landed, so the live policy decides: if it is the change, apply goes
// on with it; if it is the old policy, nothing changed; otherwise it says what is live
// and keeps the backup. changed reports whether the policy may have changed.
func (s *setupRun) afterFailedWrite(ctx context.Context, pol tsapi.Policy, proposed []byte, pending string, writeErr error) (live tsapi.Policy, changed bool, err error) {
	var invalid *tsapi.ValidationError
	if tsapi.IsConflict(writeErr) {
		os.Remove(pending)
		return live, false, errors.New("the policy changed since it was read (someone edited it), so nothing changed; run it again to see the new diff")
	}
	if errors.As(writeErr, &invalid) {
		os.Remove(pending)
		return live, false, fmt.Errorf("writing the policy failed, nothing changed: %w", writeErr)
	}
	if live, err = s.livePolicy(ctx); err != nil {
		return live, true, fmt.Errorf("writing the policy failed (%v), and reading it back failed too (%v), so Ovenlight can't tell whether it changed: check it in the admin console (Access controls); the old policy is saved in %s", writeErr, err, keepBackup(pending))
	}
	if same, _ := policy.Equivalent(live.Body, pol.Body); same {
		os.Remove(pending)
		return live, false, fmt.Errorf("writing the policy failed, nothing changed: %w", writeErr)
	}
	if same, _ := policy.Equivalent(live.Body, proposed); !same {
		s.printf("The live policy differs from the one read before like this:\n\n%s\n", policy.Diff(string(pol.Body), string(live.Body)))
		return live, true, fmt.Errorf("writing the policy failed (%v), and the live policy is now neither the old one nor Ovenlight's change (the diff above), so someone else changed it; Ovenlight left it alone. The old policy is saved in %s", writeErr, keepBackup(pending))
	}
	s.printf("The answer to the write was lost (%v), but the policy reads back as written.\n", writeErr)
	return live, true, nil
}

// healthCheck waits for the change to settle, then checks that every app node in
// watched (they saw the owner's devices before) still sees at least one of them.
func (s *setupRun) healthCheck(ctx context.Context, watched []string) error {
	start := time.Now()
	var problems []string
	for {
		if time.Since(start) >= s.settleMin {
			problems = nil
			peersNow, err := s.health()
			if err != nil {
				problems = append(problems, "couldn't ask the connector what its nodes see: "+err.Error())
			}
			for _, slug := range watched {
				if err == nil && countOwnerPeers(peersNow[slug], s.owner) == 0 {
					problems = append(problems, fmt.Sprintf("the app node %s no longer sees any of your devices", slug))
				}
			}
			if len(problems) == 0 {
				return nil
			}
		}
		if time.Since(start) >= s.settleMax {
			return errors.New(strings.Join(problems, "; "))
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(s.poll):
		}
	}
}

// retryable reports an error another try may not repeat: no answer, a server error or
// a rate limit. A refusal would be the same next time.
func retryable(err error) bool {
	var apiErr *tsapi.APIError
	if errors.As(err, &apiErr) {
		return apiErr.StatusCode >= 500 || apiErr.StatusCode == http.StatusTooManyRequests || apiErr.StatusCode == http.StatusRequestTimeout
	}
	var invalid *tsapi.ValidationError
	return !errors.As(err, &invalid)
}

// retry runs call up to four times while it fails in a way another try may not repeat.
func (s *setupRun) retry(ctx context.Context, call func() error) error {
	for try := 1; ; try++ {
		err := call()
		if err == nil || try == 4 || !retryable(err) {
			return err
		}
		select {
		case <-ctx.Done():
			return err
		case <-time.After(s.poll):
		}
	}
}

func (s *setupRun) livePolicy(ctx context.Context) (tsapi.Policy, error) {
	var live tsapi.Policy
	err := s.retry(ctx, func() (err error) {
		live, err = s.api.GetPolicy(ctx)
		return err
	})
	return live, err
}

// rollbackTo writes the backup back over Ovenlight's own change, and only over it: the
// write is conditional on etag, the version that change produced, so an edit someone
// made since is left alone. Without one (the write's answer carried none), it takes the
// live policy's, but only while that is still the change. It runs on its own clock, as
// what brought it here may have run out of time, and retries a write that got no answer:
// If-Match makes a repeat that lands on its own earlier write fail rather than overwrite.
// The live policy has the last word, since a write whose answer was lost may have landed.
func (s *setupRun) rollbackTo(ctx context.Context, backup, proposed []byte, etag, backupPath, reason string) error {
	s.printf("Problem: %s\nRolling back to the saved policy...\n", reason)
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), rollbackTimeout)
	defer cancel()
	var err error
	if etag == "" {
		var live tsapi.Policy
		if live, err = s.livePolicy(ctx); err == nil {
			if same, _ := policy.Equivalent(live.Body, proposed); same {
				etag = live.ETag
			}
		}
	}
	if etag != "" {
		err = s.retry(ctx, func() error {
			_, err := s.api.SetPolicy(ctx, backup, etag)
			return err
		})
	}
	live, getErr := s.livePolicy(ctx)
	if getErr == nil {
		if same, _ := policy.Equivalent(live.Body, backup); same {
			return fmt.Errorf("the change was rolled back: %s", reason)
		}
	}
	if tsapi.IsConflict(err) || (err == nil && etag == "") {
		return fmt.Errorf("%s, and someone else changed the policy after Ovenlight did, so Ovenlight didn't roll back over their edit: compare it with the backup %s and restore by hand in the admin console (Access controls) if needed", reason, backupPath)
	}
	if err == nil {
		err = cmp.Or(getErr, errors.New("the policy read back isn't the backup"))
	}
	return fmt.Errorf("%s, and the rollback failed (%v): restore %s by hand in the admin console (Access controls)", reason, err, backupPath)
}

// restore puts the latest backup back, after the owner types yes.
func (s *setupRun) restore(parent context.Context) error {
	path, backup, err := latestBackup(s.stateDir)
	if err != nil {
		return err
	}
	ctx, cancel := s.withTimeout(parent)
	defer cancel()
	cur, err := s.api.GetPolicy(ctx)
	if err != nil {
		return err
	}
	if same, _ := policy.Equivalent(cur.Body, backup); same {
		s.printf("The live policy already matches the last backup (%s). Nothing to do.\n", path)
		return nil
	}
	devices, err := s.api.Devices(ctx)
	if err != nil {
		return err
	}
	keyTags, err := s.openKeyTags(ctx)
	if err != nil {
		return err
	}
	var refusal *policy.Refusal
	if err := policy.CheckRestore(backup, policyDevices(devices), keyTags); errors.As(err, &refusal) {
		s.printf("Not restoring %s: guests or open invites are on your tailnet, and it would let guest devices reach more than their apps.\n%s\n", path, refusal.Error())
		return errRefused
	} else if err != nil {
		return err
	}
	s.printf("Restoring %s would change the live policy like this:\n\n%s\n", path, policy.Diff(string(cur.Body), string(backup)))
	s.printf("Shareable app nodes and guests stop working without the Ovenlight rules.\n")
	if !s.confirm("Type yes to restore the backup: ") {
		s.printf("Nothing changed.\n")
		return errNotConfirmed
	}
	ctx, cancel = s.withTimeout(parent)
	defer cancel()
	if err := s.api.ValidatePolicy(ctx, backup); err != nil {
		return fmt.Errorf("nothing changed: %w", err)
	}
	if _, err := s.api.SetPolicy(ctx, backup, cur.ETag); err != nil {
		if tsapi.IsConflict(err) {
			return errors.New("the policy changed while you were deciding, so nothing changed; run it again")
		}
		return err
	}
	got, err := s.api.GetPolicy(ctx)
	if err != nil {
		return fmt.Errorf("restored, but reading it back failed: %w", err)
	}
	if same, _ := policy.Equivalent(got.Body, backup); !same {
		return errors.New("restored, but the policy read back differs from the backup; check it in the admin console")
	}
	s.printf("Restored %s.\n", path)
	return nil
}

// openKeyTags are the tags of the tailnet's auth keys that can still be used.
func (s *setupRun) openKeyTags(ctx context.Context) ([]string, error) {
	keys, err := s.api.Keys(ctx)
	if err != nil {
		return nil, fmt.Errorf("listing auth keys failed, so nothing changed: %w", err)
	}
	var tags []string
	for _, k := range keys {
		if !k.Invalid && k.Revoked.IsZero() && (k.Expires.IsZero() || k.Expires.After(time.Now())) {
			tags = append(tags, k.Tags...)
		}
	}
	return tags, nil
}

// cmdSetupSharing is `ovenlight setup-sharing [--owner login] [--owner-label name] [--remove slug] [--rollback]`.
// It only runs in a terminal and only applies after the owner types yes: there is no
// --yes, so no script or agent can change the tailnet policy on its own.
func cmdSetupSharing(args []string) error {
	fs, p := newFlags("setup-sharing")
	ownerFlag := fs.String("owner", "", "your Tailscale login (default: the user who owns the app nodes)")
	labelFlag := fs.String("owner-label", "", `how guests see you, for example "Sam"`)
	rollback := fs.Bool("rollback", false, "restore the policy saved before the last change")
	remove := fs.String("remove", "", "take the tags and rules of an app no longer shared out of the policy")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	if !fromTerminal() {
		return errors.New("setup-sharing asks you to type yes before it changes your tailnet policy, so run it in a terminal (there is no --yes)")
	}
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return err
	}
	if app, ok := cfg.Find(*remove); ok && app.Shareable {
		return fmt.Errorf("%s is still shareable; `ovenlight unpublish %s` stops sharing it", app.Name, app.Slug)
	}
	run, reply, err := newSetupRun(p, "")
	if err != nil {
		return err
	}
	ctx := context.Background() // each step times its own API calls, starting after the owner's yes
	if *rollback {
		err := run.restore(ctx)
		if errors.Is(err, errNotConfirmed) {
			return nil
		}
		return err
	}

	owner, label, err := chooseOwner(*ownerFlag, *labelFlag, cfg, reply)
	if err != nil {
		return err
	}
	run.owner = owner
	run.apps = shareableSlugs(cfg)
	if *remove != "" {
		run.remove = []string{*remove}
	}
	run.newOwner = !policy.EqualFoldASCII(cfg.Owner, owner)
	run.confirmOwnerChange = cfg.Owner != "" && run.newOwner

	_, err = run.apply(ctx)
	if errors.Is(err, errNotConfirmed) {
		return nil
	}
	if err != nil {
		return err
	}
	if cfg.Owner != owner || cfg.OwnerLabel != label {
		if err := run.recordOwner(p, owner, label); err != nil {
			return err
		}
	}
	fmt.Printf("\nNext: make an app shareable, then invite someone:\n  ovenlight publish --slug <app> --shareable\n  ovenlight share <app> --to \"Name\"\n")
	return nil
}

// recordOwner saves the owner to the config and has the daemon reload it. It rereads
// the config first, so an app published meanwhile (the change can take a minute) stays.
func (s *setupRun) recordOwner(p *paths, owner, label string) error {
	if err := UpdateConfig(p.config, func(c *Config) error {
		c.Owner, c.OwnerLabel = owner, label
		return nil
	}); err != nil {
		return err
	}
	if _, err := callDaemon(p.state, "reload", 30*time.Second); err != nil && !errors.Is(err, errDaemonDown) {
		return err
	}
	s.printf("Recorded %s as the owner; guests see you as %q.\n", owner, label)
	return nil
}

// chooseOwner picks the owner login and label: the flags, else the config, else the
// user who owns the running (untagged) app nodes.
func chooseOwner(ownerFlag, labelFlag string, cfg *Config, reply *controlReply) (string, string, error) {
	owner, label := strings.TrimSpace(ownerFlag), strings.TrimSpace(labelFlag)
	nodeUsers := map[string]string{} // login -> display name
	if reply != nil {
		for _, a := range reply.Apps {
			if a.NodeUser != "" {
				nodeUsers[a.NodeUser] = a.OwnerName
			}
		}
	}
	if owner == "" {
		owner = cfg.Owner
	}
	if owner == "" {
		if reply == nil {
			return "", "", errors.New("the connector isn't running, so Ovenlight can't tell who owns the apps; pass --owner with your Tailscale login, for example --owner you@example.com")
		}
		if len(nodeUsers) != 1 {
			return "", "", errors.New("couldn't tell who owns the apps; pass --owner with your Tailscale login, for example --owner you@example.com")
		}
		for login := range nodeUsers {
			owner = login
		}
	}
	if label == "" {
		label = cfg.OwnerLabel
	}
	if label == "" {
		label = nodeUsers[owner]
	}
	if label == "" {
		label, _, _ = strings.Cut(owner, "@")
	}
	return owner, label, nil
}

func appList(apps []string) string {
	if len(apps) == 0 {
		return ""
	}
	return " (shareable: " + strings.Join(apps, ", ") + ")"
}

// shareableSlugs lists the apps marked shareable in the config, plus extra.
func shareableSlugs(cfg *Config, extra ...string) []string {
	var out []string
	for _, a := range cfg.Apps {
		if a.Shareable {
			out = append(out, a.Slug)
		}
	}
	return slices.Compact(slices.Sorted(slices.Values(append(out, extra...))))
}

// newSetupRun connects to the Tailscale API with the stored credential, reading
// answers from the terminal. When the connector runs, the check after a change also
// asks it what its app nodes see.
func newSetupRun(p *paths, owner string) (*setupRun, *controlReply, error) {
	creds, err := tsapi.LoadCredentials(credentialsPath(p.config))
	if err != nil {
		return nil, nil, err
	}
	client, err := tsapi.New(creds, nil)
	if err != nil {
		return nil, nil, err
	}
	run := &setupRun{api: client, in: bufio.NewReader(os.Stdin), out: os.Stdout, stateDir: p.state, owner: owner,
		settleMin: 10 * time.Second, settleMax: 60 * time.Second, poll: 3 * time.Second}
	reply, daemonErr := callDaemon(p.state, "status", 10*time.Second)
	if daemonErr == nil {
		run.health = func() (map[string][]peerInfo, error) {
			r, err := callDaemon(p.state, "health", 30*time.Second)
			if err != nil {
				return nil, err
			}
			return r.Health, nil
		}
	} else {
		fmt.Printf("(The connector isn't running, so after a change Ovenlight can check only that the policy reads back as written, not that your app nodes still see your devices.)\n")
	}
	return run, reply, nil
}

// changeAppPolicy shows the policy change one app's sharing needs (apps are the
// shareable apps after it, remove the app that stops being shared) and applies it
// after the owner types yes. With no owner recorded, it first chooses one (see
// applyRecordingOwner). Against a development control server (Headscale) the policy is
// a file, so it changes nothing there.
func changeAppPolicy(p *paths, cfg *Config, what string, apps, remove []string, ownerFlag, labelFlag string) error {
	reply, _ := callDaemon(p.state, "status", 10*time.Second)
	dev := reply != nil && reply.Dev
	if dev {
		fmt.Println("(Development control server: its policy is a file you edit, so Ovenlight leaves it alone.)")
		if cfg.Owner != "" {
			return nil
		}
	}
	if !fromTerminal() {
		change := "changes your tailnet policy"
		if dev {
			change = "records the owner"
		}
		return fmt.Errorf("%s %s, so a person has to run it in a terminal and type yes (there is no --yes)", what, change)
	}
	run := &setupRun{in: bufio.NewReader(os.Stdin), out: os.Stdout, stateDir: p.state, owner: cfg.Owner}
	if !dev {
		var err error
		if run, reply, err = newSetupRun(p, cfg.Owner); err != nil {
			return err
		}
	}
	run.apps, run.remove = apps, remove
	if len(remove) == 0 { // publish --shareable
		for _, a := range cfg.Apps {
			run.known = append(run.known, a.Slug)
		}
		run.known = append(run.known, apps...)
	}
	// apply times its own API calls, starting after the owner's yes.
	return run.applyRecordingOwner(context.Background(), p, cfg, ownerFlag, labelFlag, reply, dev)
}

// applyRecordingOwner applies the change, except against a development server. With no
// owner recorded, it first chooses one and asks for a yes to it, and records it only
// once the change is applied.
func (s *setupRun) applyRecordingOwner(ctx context.Context, p *paths, cfg *Config, ownerFlag, labelFlag string, reply *controlReply, dev bool) error {
	label := ""
	if cfg.Owner == "" {
		var err error
		if s.owner, label, err = chooseOwner(ownerFlag, labelFlag, cfg, reply); err != nil {
			return err
		}
		if !s.confirm(fmt.Sprintf("Owner: %s, guests see you as %q. Type yes to record: ", s.owner, label)) {
			s.printf("Nothing changed.\n")
			return errNotConfirmed
		}
		s.newOwner = true
	}
	if !dev {
		if _, err := s.apply(ctx); err != nil {
			return err
		}
	}
	if cfg.Owner != "" {
		return nil
	}
	return s.recordOwner(p, s.owner, label)
}
