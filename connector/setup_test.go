package main

import (
	"bufio"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi/tsapitest"
)

const defaultPolicy = `// Example/default ACLs for unrestricted connections.
{
	"acls": [
		// Allow all connections.
		{"action": "accept", "src": ["*"], "dst": ["*:*"]},
	],
}
`

// setupFixture is a fake tailnet with the owner's laptop and phone online and one
// untagged app node, plus a connector whose app node sees the laptop.
func setupFixture(t *testing.T, answer string) (*tsapitest.Fake, *setupRun, *strings.Builder) {
	t.Helper()
	f := tsapitest.New()
	t.Cleanup(f.Close)
	f.SetPolicy([]byte(defaultPolicy))
	f.Devices = []tsapi.Device{
		{ID: "1", NodeID: "nMBP", Name: "mbp.tail1.ts.net", Hostname: "mbp", User: "alex@example.com", Authorized: true},
		{ID: "2", NodeID: "nPhone", Name: "iphone.tail1.ts.net", Hostname: "iphone", User: "alex@example.com", Authorized: true},
		{ID: "3", NodeID: "nCoach", Name: "coach.tail1.ts.net", Hostname: "coach", User: "alex@example.com", Authorized: true},
	}
	client, err := tsapi.New(f.TokenCredentials(), nil)
	if err != nil {
		t.Fatal(err)
	}
	out := &strings.Builder{}
	run := &setupRun{api: client, in: bufio.NewReader(strings.NewReader(answer)), out: out, stateDir: t.TempDir(),
		owner: "alex@example.com", settleMin: 0, settleMax: 200 * time.Millisecond, poll: 20 * time.Millisecond,
		health: func() (map[string][]peerInfo, error) {
			return map[string][]peerInfo{"coach": {{ID: "nMBP", Login: "alex@example.com", Online: true}}}, nil
		}}
	return f, run, out
}

func TestSetupSharingAppliesAfterYes(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	changed, err := run.apply(t.Context())
	if err != nil || !changed {
		t.Fatalf("apply: changed=%v err=%v\n%s", changed, err, out)
	}
	if len(f.PolicyWrites) != 1 {
		t.Fatalf("%d policy writes, want 1", len(f.PolicyWrites))
	}
	want, _, _, _ := policy.Plan([]byte(defaultPolicy), policyDevices(f.Devices), policy.Config{Owner: "alex@example.com"})
	if string(f.Policy) != string(want) {
		t.Errorf("stored policy isn't the plan:\n%s", f.Policy)
	}
	for _, s := range []string{`"dst": ["autogroup:member:*"]`, "Safety report", "never your devices", "Type yes", "Done:"} {
		if !strings.Contains(out.String(), s) {
			t.Errorf("output lacks %q:\n%s", s, out)
		}
	}
	// The backup is the original policy, readable only by the owner.
	path, body, err := latestBackup(run.stateDir)
	if err != nil || string(body) != defaultPolicy {
		t.Fatalf("backup: %v %q", err, body)
	}
	for _, p := range []string{path, filepath.Dir(path)} {
		if err := jsonfile.CheckPrivate(p); err != nil {
			t.Error(err)
		}
	}
	if f.RequestCount("POST /api/v2/tailnet/-/acl/validate") != 1 {
		t.Error("the policy wasn't validated before writing")
	}

	// Running again finds nothing to do and writes nothing.
	_, run2, _ := setupFixture(t, "")
	run2.api = run.api
	if changed, err := run2.apply(t.Context()); err != nil || changed || len(f.PolicyWrites) != 1 {
		t.Errorf("second run: changed=%v err=%v writes=%d", changed, err, len(f.PolicyWrites))
	}
}

func TestSetupSharingNeedsTheWordYes(t *testing.T) {
	for _, answer := range []string{"", "y\n", "no\n", "YES please\n"} {
		f, run, _ := setupFixture(t, answer)
		if _, err := run.apply(t.Context()); !errors.Is(err, errNotConfirmed) {
			t.Errorf("answer %q: err = %v", answer, err)
		}
		if len(f.PolicyWrites) != 0 || f.RequestCount("POST /api/v2/tailnet/-/acl/validate") != 0 {
			t.Errorf("answer %q changed something", answer)
		}
	}
}

func TestSetupSharingConflictChangesNothing(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\n")
	// Someone saves the policy in the admin console while the owner reads the diff.
	f.Validate = func([]byte) string {
		f.Policy = []byte(`{"acls": []}`)
		f.ETag = `"edited-elsewhere"`
		return ""
	}
	_, err := run.apply(t.Context())
	if err == nil || !strings.Contains(err.Error(), "changed since it was read") {
		t.Fatalf("err = %v", err)
	}
	if len(f.PolicyWrites) != 0 || string(f.Policy) != `{"acls": []}` {
		t.Errorf("the other edit was overwritten: %s", f.Policy)
	}
}

func TestSetupSharingValidationFailureChangesNothing(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\n")
	f.Validate = func([]byte) string { return "tests failed: sam@example.com can't reach tag:x" }
	_, err := run.apply(t.Context())
	var v *tsapi.ValidationError
	if !errors.As(err, &v) || len(f.PolicyWrites) != 0 {
		t.Fatalf("err = %v, writes %d", err, len(f.PolicyWrites))
	}
}

func TestSetupSharingRollsBackWhenReadBackDiffers(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	calls := 0
	f.AfterSetPolicy = func(p []byte) []byte {
		calls++
		if calls == 1 {
			return []byte(`{"acls": [{"action": "accept", "src": ["*"], "dst": ["*:*"]}], "tagOwners": {}}`)
		}
		return p
	}
	_, err := run.apply(t.Context())
	if err == nil || !strings.Contains(err.Error(), "rolled back") {
		t.Fatalf("err = %v\n%s", err, out)
	}
	if string(f.Policy) != defaultPolicy || len(f.PolicyWrites) != 2 {
		t.Errorf("policy after rollback (%d writes):\n%s", len(f.PolicyWrites), f.Policy)
	}
}

// Without the connector, only the read-back is checked, and the output says so.
func TestSetupSharingWithoutConnector(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	run.health = nil
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatalf("err = %v\n%s", err, out)
	}
	if len(f.PolicyWrites) != 1 || !strings.Contains(out.String(), "Done: the policy reads back as written; the connector isn't running, so that is the only check") {
		t.Errorf("%d writes\n%s", len(f.PolicyWrites), out)
	}
}

// The owner may take most of the time reading the diff: the API calls after the yes get
// a fresh timeout, and a rollback runs even when the one before it ran out.
func TestSetupSharingTimeoutStartsAfterTheYes(t *testing.T) {
	f, run, out := setupFixture(t, "")
	run.apiTimeout = 100 * time.Millisecond
	run.in = bufio.NewReader(slowReader{wait: 200 * time.Millisecond, text: "yes\n"})
	if _, err := run.apply(t.Context()); err != nil || len(f.PolicyWrites) != 1 {
		t.Fatalf("err = %v, writes %d\n%s", err, len(f.PolicyWrites), out)
	}

	f, run, out = setupFixture(t, "yes\n")
	run.settleMax = 5 * time.Second
	run.health = func() (map[string][]peerInfo, error) {
		if len(f.PolicyWrites) == 0 {
			return map[string][]peerInfo{"coach": {{ID: "nMBP", Login: "alex@example.com", Online: true}}}, nil
		}
		return map[string][]peerInfo{"coach": {}}, nil // the app node lost the owner
	}
	ctx, cancel := context.WithTimeout(t.Context(), 300*time.Millisecond) // runs out during the check
	defer cancel()
	if _, err := run.apply(ctx); err == nil || !strings.Contains(err.Error(), "rolled back") {
		t.Fatalf("err = %v\n%s", err, out)
	}
	if string(f.Policy) != defaultPolicy {
		t.Errorf("not rolled back:\n%s", f.Policy)
	}
}

type slowReader struct {
	wait time.Duration
	text string
}

func (r slowReader) Read(p []byte) (int, error) {
	time.Sleep(r.wait)
	return copy(p, r.text), io.EOF
}

// setPolicyAPI changes what a policy write answers.
type setPolicyAPI struct {
	policyAPI
	set func(real policyAPI, ctx context.Context, body []byte, etag string) (tsapi.Policy, error)
}

func (a setPolicyAPI) SetPolicy(ctx context.Context, body []byte, etag string) (tsapi.Policy, error) {
	return a.set(a.policyAPI, ctx, body, etag)
}

var gatewayTimeout = &tsapi.APIError{Method: "POST", Path: "/acl", StatusCode: 504, Message: "gateway timeout"}

// A rollback works without an ETag in the write's answer (it takes the live one while
// the live policy is still the change), and when its own answer is lost it retries and
// trusts the live policy.
func TestSetupSharingRollbackWithoutETagOrAnswer(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	writes := 0
	run.api = setPolicyAPI{run.api, func(real policyAPI, ctx context.Context, body []byte, etag string) (tsapi.Policy, error) {
		writes++
		p, err := real.SetPolicy(ctx, body, etag)
		if writes == 2 && err == nil {
			return tsapi.Policy{}, gatewayTimeout // the rollback lands, its answer doesn't
		}
		p.ETag = ""
		return p, err
	}}
	calls := 0
	run.health = func() (map[string][]peerInfo, error) {
		if calls++; calls == 1 {
			return map[string][]peerInfo{"coach": {{ID: "nMBP", Login: "alex@example.com", Online: true}}}, nil
		}
		return map[string][]peerInfo{"coach": {}}, nil
	}
	_, err := run.apply(t.Context())
	if err == nil || !strings.HasPrefix(err.Error(), "the change was rolled back") {
		t.Fatalf("err = %v\n%s", err, out)
	}
	if string(f.Policy) != defaultPolicy || writes != 3 || len(f.PolicyWrites) != 2 {
		t.Errorf("tries %d, writes %d, policy:\n%s", writes, len(f.PolicyWrites), f.Policy)
	}
}

// A write that fails without a clear answer may have landed: the live policy says
// whether it did.
func TestSetupSharingWriteWithLostAnswer(t *testing.T) {
	// It landed: the change goes on to the check, and the backup is kept.
	f, run, out := setupFixture(t, "yes\n")
	run.api = setPolicyAPI{run.api, func(real policyAPI, ctx context.Context, body []byte, etag string) (tsapi.Policy, error) {
		real.SetPolicy(ctx, body, etag)
		return tsapi.Policy{}, gatewayTimeout
	}}
	if changed, err := run.apply(t.Context()); err != nil || !changed || !strings.Contains(out.String(), "Done:") {
		t.Fatalf("landed: changed %v, err = %v\n%s", changed, err, out)
	}
	if _, body, err := latestBackup(run.stateDir); err != nil || string(body) != defaultPolicy {
		t.Errorf("landed: backup %v %q", err, body)
	}

	// It didn't: nothing changed, and no backup is filed.
	f, run, out = setupFixture(t, "yes\n")
	f.BeforeSetPolicy = func([]byte) int { return 502 }
	if changed, err := run.apply(t.Context()); changed || err == nil || !strings.Contains(err.Error(), "nothing changed") {
		t.Fatalf("not landed: changed %v, err = %v\n%s", changed, err, out)
	}
	if entries, _ := os.ReadDir(backupDir(run.stateDir)); len(entries) != 0 || string(f.Policy) != defaultPolicy {
		t.Errorf("not landed: backups %v, policy:\n%s", entries, f.Policy)
	}

	// Someone else's edit is live: it says so and leaves it alone.
	f, run, out = setupFixture(t, "yes\n")
	f.BeforeSetPolicy = func([]byte) int {
		f.Policy = []byte(`{"acls": []}`)
		return 502
	}
	_, err := run.apply(t.Context())
	if err == nil || !strings.Contains(err.Error(), "neither the old one nor Ovenlight's change") || !strings.Contains(out.String(), `-		{"action": "accept", "src": ["*"], "dst": ["*:*"]},`) {
		t.Fatalf("someone else: err = %v\n%s", err, out)
	}
	if string(f.Policy) != `{"acls": []}` {
		t.Errorf("someone else's edit was overwritten:\n%s", f.Policy)
	}
}

// A change that never landed leaves the last real backup the newest, so --rollback can
// still undo the change before it.
func TestSetupSharingFailedAttemptKeepsTheBackup(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\nyes\nyes\n")
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatal(err)
	}
	time.Sleep(5 * time.Millisecond) // a later backup name
	f.Validate = func([]byte) string { return "tests failed" }
	run.apps = []string{"coach"}
	if _, err := run.apply(t.Context()); err == nil {
		t.Fatal("want a validation error")
	}
	f.Validate = nil
	if err := run.restore(t.Context()); err != nil || string(f.Policy) != defaultPolicy {
		t.Errorf("restore: %v, policy:\n%s", err, f.Policy)
	}
}

func TestSetupSharingRollsBackWhenAppNodesLoseTheOwner(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\n")
	before := true
	run.health = func() (map[string][]peerInfo, error) {
		if before {
			before = false
			return map[string][]peerInfo{"coach": {{ID: "nMBP", Login: "alex@example.com", Online: true}}}, nil
		}
		return map[string][]peerInfo{"coach": {}}, nil
	}
	_, err := run.apply(t.Context())
	if err == nil || !strings.Contains(err.Error(), "app node coach no longer sees any of your devices") {
		t.Fatalf("err = %v", err)
	}
	if string(f.Policy) != defaultPolicy {
		t.Errorf("not rolled back:\n%s", f.Policy)
	}
}

func TestSetupSharingRefusesTaggedTailnet(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	f.Devices = append(f.Devices, tsapi.Device{NodeID: "nNAS", Name: "nas.tail1.ts.net", Hostname: "nas", Tags: []string{"tag:server"}})
	if _, err := run.apply(t.Context()); !errors.Is(err, errRefused) {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(out.String(), `["autogroup:member", "tag:server"]`) || len(f.PolicyWrites) != 0 {
		t.Errorf("refusal output:\n%s", out)
	}
}

func TestSetupSharingRestoreBackup(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\nyes\n")
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := run.restore(t.Context()); err != nil {
		t.Fatal(err)
	}
	if string(f.Policy) != defaultPolicy {
		t.Errorf("restore left:\n%s", f.Policy)
	}
	// Nothing to restore now; and no backups at all is an error.
	if err := run.restore(t.Context()); err != nil {
		t.Errorf("restore when already equal: %v", err)
	}
	run.stateDir = t.TempDir()
	if err := run.restore(t.Context()); err == nil {
		t.Error("restore without a backup should fail")
	}
}

// A rollback writes only over Ovenlight's own change: an edit made after it stays.
func TestSetupSharingRollbackLeavesALaterEditAlone(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	calls := 0
	run.health = func() (map[string][]peerInfo, error) {
		if calls++; calls == 1 {
			return map[string][]peerInfo{"coach": {{ID: "nMBP", Login: "alex@example.com", Online: true}}}, nil
		}
		f.SetPolicy([]byte(`{"acls": []}`)) // someone saves in the admin console meanwhile
		return map[string][]peerInfo{"coach": {}}, nil
	}
	_, err := run.apply(t.Context())
	backup, _, _ := latestBackup(run.stateDir)
	if err == nil || !strings.Contains(err.Error(), "someone else changed the policy") || !strings.Contains(err.Error(), backup) {
		t.Fatalf("err = %v\n%s", err, out)
	}
	if string(f.Policy) != `{"acls": []}` || len(f.PolicyWrites) != 1 {
		t.Errorf("the later edit was overwritten (%d writes):\n%s", len(f.PolicyWrites), f.Policy)
	}
}

// --rollback won't restore a backup that lets guest devices reach more than their apps.
func TestSetupSharingRestoreRefusesWhatGuestsWouldReach(t *testing.T) {
	f, run, out := setupFixture(t, "yes\nyes\n")
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatal(err)
	}
	applied := string(f.Policy)
	f.Devices = append(f.Devices, tsapi.Device{NodeID: "nSam", Name: "sam.tail1.ts.net", Hostname: "sam", Tags: []string{policy.GuestTag("coach")}})
	if err := run.restore(t.Context()); !errors.Is(err, errRefused) {
		t.Fatalf("err = %v\n%s", err, out)
	}
	if string(f.Policy) != applied || !strings.Contains(out.String(), `acls rule 1 lets "*" reach ["*:*"]`) {
		t.Errorf("restore output:\n%s", out)
	}
}

// An open invite's key counts as a guest to come: --rollback waits until it is gone.
// An expired key doesn't count.
func TestSetupSharingRestoreWaitsForOpenInvites(t *testing.T) {
	f, run, out := setupFixture(t, "yes\nyes\n")
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	f.Keys = []tsapitest.FakeKey{
		{Key: tsapi.Key{ID: "kOld", Expires: now.Add(-time.Hour), Tags: []string{policy.GuestTag("notes")}}},
		{Key: tsapi.Key{ID: "kOpen", Expires: now.Add(time.Hour), Tags: []string{policy.GuestTag("coach")}}},
	}
	if err := run.restore(t.Context()); !errors.Is(err, errRefused) || !strings.Contains(out.String(), "cancel their open invites") {
		t.Fatalf("err = %v\n%s", err, out)
	}
	f.Keys[1].Deleted = true
	if err := run.restore(t.Context()); err != nil || string(f.Policy) != defaultPolicy {
		t.Fatalf("err = %v, policy:\n%s", err, f.Policy)
	}
}

func TestChooseOwner(t *testing.T) {
	reply := &controlReply{Apps: []NodeStatus{{NodeUser: "alex@example.com", OwnerName: "Alex Rivera"}, {Tags: []string{appTag}}}}
	owner, label, err := chooseOwner("", "", &Config{}, reply)
	if err != nil || owner != "alex@example.com" || label != "Alex Rivera" {
		t.Errorf("from nodes: %q %q %v", owner, label, err)
	}
	owner, label, _ = chooseOwner("", "", &Config{Owner: "me@example.com"}, nil)
	if owner != "me@example.com" || label != "me" {
		t.Errorf("from config: %q %q", owner, label)
	}
	if _, _, err := chooseOwner("", "", &Config{}, nil); err == nil {
		t.Error("no way to know the owner should be an error")
	}
	two := &controlReply{Apps: []NodeStatus{{NodeUser: "a@example.com"}, {NodeUser: "b@example.com"}}}
	if _, _, err := chooseOwner("", "", &Config{}, two); err == nil {
		t.Error("two node users should need --owner")
	}
}

// publish --shareable: the delta for one app, applied only after yes, with the same
// validate, If-Match, read-back and health check; unpublishing takes it out again.
func TestPublishShareableAppliesTheAppsDelta(t *testing.T) {
	f, run, out := setupFixture(t, "yes\n")
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatal(err)
	}
	base := string(f.Policy)

	_, run, out = setupFixture(t, "no\n")
	run.api, run.apps = mustClient(t, f), []string{"coach"}
	if _, err := run.apply(t.Context()); !errors.Is(err, errNotConfirmed) || string(f.Policy) != base {
		t.Fatalf("answered no: err %v, policy changed %v", err, string(f.Policy) != base)
	}
	for _, s := range []string{"tag:ovenlight-guest-coach", "tag:ovenlight-app-coach", "never your devices"} {
		if !strings.Contains(out.String(), s) {
			t.Errorf("preview lacks %q:\n%s", s, out)
		}
	}

	_, run, out = setupFixture(t, "yes\n")
	run.api, run.apps = mustClient(t, f), []string{"coach"}
	validations := f.RequestCount("POST /api/v2/tailnet/-/acl/validate")
	if changed, err := run.apply(t.Context()); err != nil || !changed {
		t.Fatalf("apply: %v %v\n%s", changed, err, out)
	}
	want, _, _, _ := policy.Plan([]byte(base), policyDevices(f.Devices), policy.Config{Owner: "alex@example.com", Apps: []string{"coach"}})
	if string(f.Policy) != string(want) || f.RequestCount("POST /api/v2/tailnet/-/acl/validate") != validations+1 {
		t.Errorf("stored policy isn't the plan, or wasn't validated:\n%s", f.Policy)
	}
	if !strings.Contains(string(f.Policy), `{"src": ["tag:ovenlight-guest-coach"], "dst": ["tag:ovenlight-app-coach"], "ip": ["tcp:443"]}`) {
		t.Errorf("no guest rule for coach:\n%s", f.Policy)
	}

	// Unpublishing it takes the app's tags and rule out, back to the base policy.
	_, run, _ = setupFixture(t, "yes\n")
	run.api, run.remove = mustClient(t, f), []string{"coach"}
	if _, err := run.apply(t.Context()); err != nil {
		t.Fatal(err)
	}
	if ok, _ := policy.Equivalent(f.Policy, []byte(base)); !ok || strings.Contains(string(f.Policy), "coach") {
		t.Errorf("after unpublish:\n%s", f.Policy)
	}
}

func TestPublishShareableRollsBackWhenAppNodesLoseTheOwner(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\n")
	run.apply(t.Context())
	base := string(f.Policy)
	_, run, _ = setupFixture(t, "yes\n")
	run.api, run.apps = mustClient(t, f), []string{"coach"}
	before := true
	run.health = func() (map[string][]peerInfo, error) {
		if before {
			before = false
			return map[string][]peerInfo{"coach": {{ID: "nMBP", Login: "alex@example.com", Online: true}}}, nil
		}
		return map[string][]peerInfo{"coach": {}}, nil
	}
	if _, err := run.apply(t.Context()); err == nil || !strings.Contains(err.Error(), "rolled back") {
		t.Fatalf("err = %v", err)
	}
	if string(f.Policy) != base {
		t.Errorf("not rolled back:\n%s", f.Policy)
	}
}

// A first publish --shareable chooses the owner, asks for a yes to it, and records it
// only once the policy change is applied.
func TestPublishShareableRecordsTheFirstOwner(t *testing.T) {
	reply := &controlReply{Apps: []NodeStatus{{NodeUser: "alex@example.com", OwnerName: "Alex Rivera"}}}
	first := func(answers string) (*tsapitest.Fake, *setupRun, *paths, *strings.Builder) {
		f, run, out := setupFixture(t, answers)
		run.owner, run.apps = "", []string{"coach"}
		return f, run, &paths{config: filepath.Join(t.TempDir(), "config.json"), state: run.stateDir}, out
	}
	recorded := func(p *paths) *Config {
		cfg, err := LoadConfig(p.config)
		if err != nil {
			t.Fatal(err)
		}
		return cfg
	}

	f, run, p, out := first("yes\nyes\n")
	if err := run.applyRecordingOwner(t.Context(), p, &Config{}, "", "", reply, false); err != nil || len(f.PolicyWrites) != 1 {
		t.Fatalf("err = %v, writes %d\n%s", err, len(f.PolicyWrites), out)
	}
	if cfg := recorded(p); cfg.Owner != "alex@example.com" || cfg.OwnerLabel != "Alex Rivera" {
		t.Errorf("recorded %q %q", cfg.Owner, cfg.OwnerLabel)
	}
	if !strings.Contains(out.String(), `Owner: alex@example.com, guests see you as "Alex Rivera". Type yes to record`) {
		t.Errorf("no owner prompt:\n%s", out)
	}

	// A no to either prompt, a refusal or a failed validation records nobody.
	for name, tc := range map[string]struct {
		answers string
		tweak   func(*tsapitest.Fake)
		want    error
	}{
		"no to the owner":  {"no\n", nil, errNotConfirmed},
		"no to the change": {"yes\nno\n", nil, errNotConfirmed},
		"refused": {"yes\nyes\n", func(f *tsapitest.Fake) {
			f.Devices = append(f.Devices, tsapi.Device{NodeID: "nNAS", Name: "nas.tail1.ts.net", Hostname: "nas", Tags: []string{"tag:server"}})
		}, errRefused},
		"invalid": {"yes\nyes\n", func(f *tsapitest.Fake) { f.Validate = func([]byte) string { return "tests failed" } }, nil},
	} {
		f, run, p, _ := first(tc.answers)
		if tc.tweak != nil {
			tc.tweak(f)
		}
		err := run.applyRecordingOwner(t.Context(), p, &Config{}, "", "", reply, false)
		if err == nil || (tc.want != nil && !errors.Is(err, tc.want)) || len(f.PolicyWrites) != 0 || recorded(p).Owner != "" {
			t.Errorf("%s: err = %v, writes %d, owner %q", name, err, len(f.PolicyWrites), recorded(p).Owner)
		}
	}

	// With the connector down, only --owner says who the owner is.
	f, run, p, _ = first("yes\nyes\n")
	if err := run.applyRecordingOwner(t.Context(), p, &Config{}, "", "", nil, false); err == nil || !strings.Contains(err.Error(), "--owner") || f.RequestCount("GET /api/v2/tailnet/-/acl") != 0 {
		t.Errorf("connector down: err = %v", err)
	}
	if err := run.applyRecordingOwner(t.Context(), p, &Config{}, "alex@example.com", "", nil, false); err != nil || recorded(p).Owner != "alex@example.com" {
		t.Errorf("connector down, --owner: err = %v, owner %q", err, recorded(p).Owner)
	}

	// Against a development server, the owner is recorded right after its yes, with no
	// policy step, into the config as it is then (an app published meanwhile stays).
	f, run, p, out = first("yes\n")
	if err := (&Config{Apps: []App{{Name: "Notes", Slug: "notes", Port: 4400}}}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	if err := run.applyRecordingOwner(t.Context(), p, &Config{}, "", "", reply, true); err != nil || len(f.Requests) != 0 {
		t.Fatalf("dev: err = %v, requests %v", err, f.Requests)
	}
	if cfg := recorded(p); cfg.Owner != "alex@example.com" || len(cfg.Apps) != 1 || strings.Contains(out.String(), "apply this change") {
		t.Errorf("dev: recorded %+v\n%s", cfg, out)
	}

	// With an owner recorded, there is no owner prompt and the config isn't saved.
	f, run, p, out = first("yes\n")
	run.owner = "alex@example.com"
	if err := run.applyRecordingOwner(t.Context(), p, &Config{Owner: run.owner}, "", "", reply, false); err != nil || len(f.PolicyWrites) != 1 {
		t.Fatalf("recorded owner: err = %v, writes %d\n%s", err, len(f.PolicyWrites), out)
	}
	if _, err := os.Stat(p.config); !errors.Is(err, os.ErrNotExist) || strings.Contains(out.String(), "Type yes to record") {
		t.Errorf("recorded owner: config %v\n%s", err, out)
	}
}

// An owner being recorded or changed must have an untagged device in the tailnet, so a
// mistyped --owner is refused before anything changes. An owner already recorded isn't
// checked again.
func TestOwnerNeedsADevice(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\nyes\n")
	// A tagged device can carry the login of whoever tagged it.
	f.Devices = append(f.Devices, tsapi.Device{NodeID: "nNAS", Hostname: "nas", User: "alx@example.com", Tags: []string{"tag:server"}})
	run.owner = ""
	p := &paths{config: filepath.Join(t.TempDir(), "config.json"), state: run.stateDir}
	err := run.applyRecordingOwner(t.Context(), p, &Config{}, "alx@example.com", "", nil, false)
	if err == nil || !strings.Contains(err.Error(), "no device in this tailnet belongs to alx@example.com") || !strings.Contains(err.Error(), "Use My Own Computers") {
		t.Fatalf("mistyped owner: err = %v", err)
	}
	if cfg, _ := LoadConfig(p.config); len(f.PolicyWrites) != 0 || cfg.Owner != "" {
		t.Errorf("mistyped owner: writes %d, owner %q", len(f.PolicyWrites), cfg.Owner)
	}

	f, run, out := setupFixture(t, "yes\n")
	run.owner, run.newOwner = "Alex@Example.com", true
	if changed, err := run.apply(t.Context()); err != nil || !changed {
		t.Fatalf("owner in other case: %v %v\n%s", changed, err, out)
	}
	f, run, _ = setupFixture(t, "yes\n")
	run.owner, run.apps = "gone@example.com", []string{"coach"}
	if changed, err := run.apply(t.Context()); err != nil || !changed || len(f.PolicyWrites) != 1 {
		t.Errorf("recorded owner: %v %v", changed, err)
	}
}

func mustClient(t *testing.T, f *tsapitest.Fake) *tsapi.Client {
	t.Helper()
	c, err := tsapi.New(f.TokenCredentials(), nil)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// publish --shareable refuses a tailnet where a device carries the tag of an app this
// connector doesn't publish: another connector's.
func TestPublishShareableRefusesASecondConnector(t *testing.T) {
	f, run, _ := setupFixture(t, "yes\n")
	f.Devices = append(f.Devices, tsapi.Device{NodeID: "nNotes", Name: "notes.tail1.ts.net", Hostname: "notes", Tags: []string{policy.AppTag("notes")}})
	run.apps, run.known = []string{"coach"}, []string{"coach"}
	if _, err := run.apply(t.Context()); err == nil || !strings.Contains(err.Error(), "one sharing connector per tailnet") ||
		!strings.Contains(err.Error(), policy.AppTag("notes")) {
		t.Fatalf("err = %v", err)
	}
	if len(f.PolicyWrites) != 0 {
		t.Error("the policy changed")
	}
	run.apps, run.known = []string{"coach", "notes"}, []string{"coach", "notes"}
	if _, err := run.apply(t.Context()); err != nil || len(f.PolicyWrites) != 1 {
		t.Errorf("with notes published here: %v, %d writes", err, len(f.PolicyWrites))
	}
}

// yesThen answers yes after running change, as if it happened while the owner read.
type yesThen struct{ change func() }

func (r yesThen) Read(p []byte) (int, error) {
	r.change()
	return copy(p, "yes\n"), io.EOF
}

// A device that changes while the owner reads the plan stops the change.
func TestSetupSharingReplansAfterTheYes(t *testing.T) {
	f, run, _ := setupFixture(t, "")
	run.in = bufio.NewReader(yesThen{func() {
		f.Mu.Lock()
		defer f.Mu.Unlock()
		f.Devices = append(f.Devices, tsapi.Device{NodeID: "nNAS", Name: "nas.tail1.ts.net", Hostname: "nas", Tags: []string{"tag:server"}})
	}})
	if _, err := run.apply(t.Context()); err == nil || !strings.Contains(err.Error(), "devices changed") {
		t.Fatalf("err = %v", err)
	}
	if len(f.PolicyWrites) != 0 || f.RequestCount("POST /api/v2/tailnet/-/acl/validate") != 0 {
		t.Error("the policy changed")
	}
}

// Unpublishing a shareable app deletes its node with the API credential, so publish
// --shareable takes another app afterwards; without one the node stays, and the owner
// is told to delete it.
func TestUnpublishShareableDeletesItsNode(t *testing.T) {
	f := tsapitest.New()
	t.Cleanup(f.Close)
	f.Devices = []tsapi.Device{
		{NodeID: "nMBP", Hostname: "mbp", User: "alex@example.com"},
		{NodeID: "nCoach", Hostname: "coach", Tags: []string{policy.AppTag("coach")}},
		{NodeID: "nSam", Hostname: "sam-iphone", Tags: []string{policy.GuestTag("coach")}},
	}
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config", "config.json"), state: filepath.Join(dir, "state")}
	if err := os.MkdirAll(nodeDir(p.state, "coach"), 0o700); err != nil {
		t.Fatal(err)
	}
	devices := func() []tsapi.Device { f.Mu.Lock(); defer f.Mu.Unlock(); return slices.Clone(f.Devices) }
	refusal := func() error { return oneConnector(devices(), []string{"notes"}) }
	if err := refusal(); err == nil || !strings.Contains(err.Error(), "isn't in this connector's config") || strings.Contains(err.Error(), "another connector shares apps here") {
		t.Fatalf("with coach's node left: %v", err)
	}

	if msg := forgetAppNode(p, "coach"); !strings.Contains(msg, "stays in your tailnet") || !strings.Contains(msg, "admin console") {
		t.Errorf("without a credential: %s", msg)
	}
	if len(devices()) != 3 || refusal() == nil {
		t.Error("the node went without a credential")
	}
	if _, err := os.Stat(nodeDir(p.state, "coach")); err != nil {
		t.Errorf("the state of a node still in the tailnet: %v", err)
	}

	if err := tsapi.SaveCredentials(credentialsPath(p.config), f.TokenCredentials()); err != nil {
		t.Fatal(err)
	}
	if msg := forgetAppNode(p, "coach"); !strings.Contains(msg, "deleted from your tailnet") {
		t.Errorf("with a credential: %s", msg)
	}
	if left := devices(); len(left) != 2 || slices.ContainsFunc(left, func(d tsapi.Device) bool { return d.NodeID == "nCoach" }) {
		t.Errorf("devices left: %+v", left)
	}
	if err := refusal(); err != nil {
		t.Error(err)
	}
	if _, err := os.Stat(nodeDir(p.state, "coach")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("the deleted node's state: %v", err)
	}
}
