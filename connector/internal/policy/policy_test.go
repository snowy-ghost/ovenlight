package policy

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"

	"github.com/tailscale/hujson"
)

var update = flag.Bool("update", false, "rewrite the golden files")

const owner = "alex@example.com"

var untaggedDevices = []Device{{Name: "alex-laptop"}, {Name: "alex-phone"}, {Name: "interview-coach"}}

func with(extra ...Device) []Device { return append(slices.Clone(untaggedDevices), extra...) }

var (
	oneApp  = []string{"coach"}
	twoApps = []string{"coach", "notes"}
)

// goldenCases are the policies in testdata/<in>.hujson (in defaults to name); each
// one's expected plan is testdata/<name>.golden.
var goldenCases = []struct {
	name, in string
	devices  []Device
	apps     []string
	remove   []string
	refuse   bool
}{
	// setup-sharing before any app is shareable, then with one and two.
	{name: "default-grants", devices: untaggedDevices},
	{name: "default-grants-one-app", in: "default-grants", devices: untaggedDevices, apps: oneApp},
	{name: "default-grants-two-apps", in: "default-grants", devices: untaggedDevices, apps: twoApps},
	{name: "default-acls", devices: untaggedDevices},
	{name: "default-acls-one-app", in: "default-acls", devices: untaggedDevices, apps: oneApp},
	{name: "default-acls-two-apps", in: "default-acls", devices: untaggedDevices, apps: twoApps},
	// publish --shareable for a second app, and unpublish, on an applied policy.
	{name: "already-applied", devices: with(Device{Name: "coach", Tags: []string{AppTag("coach")}}), apps: oneApp},
	{name: "publish-second-app", in: "already-applied", devices: untaggedDevices, apps: twoApps},
	{name: "unpublish-app", in: "already-applied-two", devices: untaggedDevices, apps: oneApp, remove: []string{"notes"}},
	{name: "unpublish-last-app", in: "already-applied", devices: untaggedDevices, remove: []string{"coach"}},
	// Unpublishing the last app when tagOwners and grants are the last two members, and the
	// owner's comment inside a grants section that otherwise goes.
	{name: "compact-unpublish-last", devices: untaggedDevices, remove: oneApp},
	{name: "no-trailing-commas", devices: untaggedDevices, remove: oneApp},
	{name: "grants-comments", devices: untaggedDevices, remove: oneApp},
	// A new owner gets a rule of their own; the earlier owner's stays theirs, so
	// unpublishing refuses while it uses a tag that would go.
	{name: "owner-changed", devices: untaggedDevices, apps: twoApps},
	{name: "owner-changed-unpublish", in: "owner-changed", devices: untaggedDevices, apps: oneApp, remove: []string{"notes"}, refuse: true},
	// The rules an earlier version wrote into acls are managed where they are.
	{name: "applied-acls", devices: untaggedDevices, apps: oneApp},
	{name: "applied-acls-second-app", in: "applied-acls", devices: untaggedDevices, apps: twoApps},
	{name: "applied-acls-unpublish", in: "applied-acls", devices: untaggedDevices, remove: oneApp},
	// What blocks narrowing.
	{name: "routes", in: "default-grants", devices: with(Device{Name: "nas", Routes: []string{"192.168.1.0/24"}}), apps: oneApp, refuse: true},
	{name: "exit-node", in: "default-grants", devices: with(Device{Name: "exit", Routes: []string{"0.0.0.0/0", "::/0"}}), apps: oneApp, refuse: true},
	{name: "tagged-devices", devices: with(Device{Name: "nas", Tags: []string{"tag:server"}}), apps: oneApp, refuse: true},
	{name: "tag-source", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "wildcard-port", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "autogroup-tagged", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "cidr-source", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "unknown-autogroup", devices: untaggedDevices, apps: oneApp, refuse: true},
	// Beyond the access rules: node attributes, route approvals, tag owners.
	{name: "guest-attrs", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "tag-owners-not-admin", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "mullvad", devices: untaggedDevices, apps: oneApp},
	{name: "app-connectors", devices: untaggedDevices, apps: oneApp},
	{name: "guest-owns-tag", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "remove-with-hazard", devices: untaggedDevices, remove: []string{"coach"}}, // no app left: the hazard is a note
	{name: "node-attrs-mixed", devices: untaggedDevices, apps: oneApp, refuse: true},
	// Other shapes of policy.
	{name: "groups-ssh", devices: untaggedDevices, apps: oneApp},
	{name: "grants-custom", devices: with(Device{Name: "build-1", Tags: []string{"tag:server"}}), apps: twoApps},
	{name: "comments", devices: untaggedDevices, apps: oneApp},
	{name: "both-sections", devices: untaggedDevices, apps: oneApp},
	{name: "compact", devices: untaggedDevices, apps: oneApp},
	{name: "wildcard-to-members", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "compact-unpublish", devices: untaggedDevices, remove: []string{"notes"}, apps: oneApp},
	{name: "strict-json", devices: untaggedDevices, apps: oneApp},
	{name: "unknown-destinations", devices: untaggedDevices, apps: oneApp},
	{name: "wildcard-source-unknown-destinations", devices: untaggedDevices, apps: oneApp, refuse: true},
	{name: "empty", apps: oneApp},
}

func caseConfig(apps, remove []string) Config {
	return Config{Owner: owner, Apps: apps, Remove: remove}
}

func readCase(t *testing.T, name, in string) []byte {
	t.Helper()
	if in == "" {
		in = name
	}
	b, err := os.ReadFile(filepath.Join("testdata", in+".hujson"))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestGolden(t *testing.T) {
	for _, c := range goldenCases {
		t.Run(c.name, func(t *testing.T) {
			proposed, diff, report, err := Plan(readCase(t, c.name, c.in), c.devices, caseConfig(c.apps, c.remove))
			var got strings.Builder
			var refusal *Refusal
			switch {
			case errors.As(err, &refusal):
				if !c.refuse {
					t.Fatalf("unexpected refusal: %v", err)
				}
				got.WriteString("== refusal\n" + err.Error() + "\n")
			case err != nil:
				t.Fatal(err)
			default:
				if c.refuse {
					t.Fatalf("expected a refusal, got changes %v", report.Changes)
				}
				got.WriteString("== report\n")
				for _, ch := range report.Changes {
					got.WriteString("change: " + ch + "\n")
				}
				for _, n := range report.Notes {
					got.WriteString("note: " + n + "\n")
				}
				got.WriteString("== diff\n" + diff + "== proposed\n" + string(proposed))
			}
			path := filepath.Join("testdata", c.name+".golden")
			if *update {
				if err := os.WriteFile(path, []byte(got.String()), 0o644); err != nil {
					t.Fatal(err)
				}
				return
			}
			want, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("%v (run go test -update to create it)", err)
			}
			if got.String() != string(want) {
				t.Errorf("plan differs from %s (go test -update rewrites it):\n%s", path, Diff(string(want), got.String()))
			}
		})
	}
}

// Every plan applies cleanly and is idempotent: planning again on the proposed policy,
// with the app nodes and guests tagged, changes nothing, byte for byte.
func TestPlanIsIdempotent(t *testing.T) {
	for _, c := range goldenCases {
		if c.refuse {
			continue
		}
		cfg := caseConfig(c.apps, c.remove)
		proposed, _, _, err := Plan(readCase(t, c.name, c.in), c.devices, cfg)
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		devices := slices.Clone(c.devices)
		for _, slug := range c.apps {
			devices = append(devices, Device{Name: slug, Tags: []string{AppTag(slug)}}, Device{Name: "guest-" + slug, Tags: []string{GuestTag(slug)}})
		}
		again, diff, report, err := Plan(proposed, devices, cfg)
		if err != nil {
			t.Fatalf("%s: second plan: %v", c.name, err)
		}
		if report.Changed() || diff != "" || string(again) != string(proposed) {
			t.Errorf("%s: second plan still changes %v\n%s", c.name, report.Changes, diff)
		}
	}
}

// The proposed policy means what the report says, checked on the standardized JSON
// rather than the text: each shared app has its two tags and one guest rule, no rule
// lets every device in, one owner rule lets the owner reach every app node on both
// ports (an earlier owner's may stay beside it), and no rule Ovenlight narrowed lets
// your devices reach guest devices.
func TestProposedPolicySemantics(t *testing.T) {
	for _, c := range goldenCases {
		if c.refuse {
			continue
		}
		cfg := caseConfig(c.apps, c.remove)
		proposed, _, _, err := Plan(readCase(t, c.name, c.in), c.devices, cfg)
		if err != nil {
			t.Fatal(err)
		}
		std, err := hujson.Standardize(slices.Clone(proposed))
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		var p map[string]any
		if err := json.Unmarshal(std, &p); err != nil {
			t.Fatalf("%s: proposed policy isn't JSON: %v", c.name, err)
		}
		owners, _ := p["tagOwners"].(map[string]any)
		for _, slug := range c.apps {
			for _, tag := range []string{AppTag(slug), GuestTag(slug)} {
				if o, ok := owners[tag].([]any); !ok || len(o) != 1 || o[0] != DefaultTagOwner {
					t.Errorf("%s: tagOwners[%s] = %v", c.name, tag, owners[tag])
				}
			}
		}
		for tag := range owners {
			if slices.ContainsFunc(c.remove, func(s string) bool { return tag == AppTag(s) || tag == GuestTag(s) }) {
				t.Errorf("%s: %s is still defined", c.name, tag)
			}
		}
		acls, _ := p["acls"].([]any)
		grants, _ := p["grants"].([]any)
		found := map[string]int{}
		for i, r := range slices.Concat(acls, grants) {
			b, _ := json.Marshal(r)
			for _, slug := range append(slices.Clone(c.apps), c.remove...) {
				if !strings.Contains(string(b), `"src":["`+GuestTag(slug)+`"]`) {
					continue
				}
				found[slug]++
				want := `{"dst":["` + AppTag(slug) + `"],"ip":["tcp:443"],"src":["` + GuestTag(slug) + `"]}`
				if i < len(acls) {
					want = `{"action":"accept","dst":["` + AppTag(slug) + `:443"],"src":["` + GuestTag(slug) + `"]}`
				}
				if string(b) != want {
					t.Errorf("%s: guest rule = %s, want %s", c.name, b, want)
				}
			}
			if strings.Contains(string(b), `"src":["*"]`) {
				t.Errorf("%s: a rule still has \"*\" as its source: %s", c.name, b)
			}
			if strings.Contains(string(b), `"src":["autogroup:member"]`) && (strings.Contains(string(b), `"dst":["*"]`) || strings.Contains(string(b), `"dst":["*:*"]`)) {
				t.Errorf("%s: your devices still reach guest devices: %s", c.name, b)
			}
			if len(c.apps) > 0 && strings.Contains(string(b), `"src":["autogroup:member"]`) && strings.Contains(string(b), AppTagPrefix) {
				t.Errorf("%s: every member still reaches app nodes: %s", c.name, b)
			}
		}
		for _, slug := range c.apps {
			if found[slug] != 1 {
				t.Errorf("%s: %d guest rules for %s, want 1", c.name, found[slug], slug)
			}
		}
		for _, slug := range c.remove {
			if found[slug] != 0 {
				t.Errorf("%s: the guest rule for %s is still there", c.name, slug)
			}
		}
		if len(c.apps) > 0 {
			var ownerRules []string
			d, _ := parseDoc(proposed)
			for _, r := range d.rules {
				if isOwnRule(r, cfg.withDefaults()) {
					var hosts []string
					for _, dst := range r.dst {
						host, _ := hostOf(r.section, dst)
						hosts = append(hosts, host)
					}
					ownerRules = append(ownerRules, fmt.Sprintf("%s %v", r.src[0].value, hosts))
				}
			}
			want := fmt.Sprintf("%s %v", owner, cfg.withDefaults().appTags())
			if len(ownerRules) != 1 || ownerRules[0] != want {
				t.Errorf("%s: owner rules %q, want [%q]", c.name, ownerRules, want)
			}
		}
	}
}

var commentPattern = regexp.MustCompile(`(?s)//[^\n]*|/\*.*?\*/`)

func TestCommentsAndOrderPreserved(t *testing.T) {
	for _, name := range []string{"comments", "default-grants", "default-acls", "grants-custom", "already-applied-two"} {
		in := readCase(t, name, "")
		proposed, _, _, err := Plan(in, untaggedDevices, caseConfig(twoApps, nil))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		// Every original comment is still there, in the same order.
		rest := string(proposed)
		for _, comment := range commentPattern.FindAllString(string(in), -1) {
			i := strings.Index(rest, comment)
			if i < 0 {
				t.Errorf("%s: comment lost or reordered: %q", name, comment)
				continue
			}
			rest = rest[i+len(comment):]
		}
		// Every original line survives except the ones a narrowing rewrote.
		for _, line := range strings.Split(string(in), "\n") {
			if strings.Contains(line, `["*"]`) || strings.Contains(line, `["*:*"]`) {
				continue
			}
			if !strings.Contains(string(proposed), line) {
				t.Errorf("%s: line changed: %q", name, line)
			}
		}
	}
}

func TestNarrowingIgnoresOvenlightTags(t *testing.T) {
	in := readCase(t, "default-acls", "")
	devices := with(Device{Name: "coach", Tags: []string{AppTag("coach")}}, Device{Name: "sam", Tags: []string{GuestTag("coach")}})
	_, _, report, err := Plan(in, devices, caseConfig(oneApp, nil))
	if err != nil {
		t.Fatalf("Ovenlight's own tags must not block narrowing: %v", err)
	}
	if len(report.Changes) != 4 {
		t.Errorf("changes = %v", report.Changes)
	}
}

func TestRefusalExplainsAndSuggests(t *testing.T) {
	in := readCase(t, "tagged-devices", "")
	devices := []Device{{Name: "laptop"}, {Name: "nas", Tags: []string{"tag:server"}, Routes: []string{"10.0.0.0/24"}}, {Name: "ci", Tags: []string{"tag:ci", "tag:server"}}, {Name: "exit", Routes: []string{"0.0.0.0/0"}}}
	_, _, _, err := Plan(in, devices, caseConfig(oneApp, nil))
	var refusal *Refusal
	if !errors.As(err, &refusal) {
		t.Fatalf("err = %v, want a refusal", err)
	}
	for _, want := range []string{
		"tagged devices (ci, nas)", "subnet routes (advertised by nas)", "exit nodes (exit)",
		`source: replace "*" with ["autogroup:member", "tag:ci", "tag:server"]`,
		`destination: ["autogroup:member:*", "tag:ci:*", "tag:server:*", "10.0.0.0/24:*", "autogroup:internet:*"]`,
		"ovenlight command again",
	} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("refusal doesn't mention %q:\n%s", want, err)
		}
	}
}

// Only the allow-all rule is narrowed on its own. Any other rule with a "*" source is
// refused, and the suggestion replaces its source.
func TestOnlyAllowAllIsNarrowed(t *testing.T) {
	for _, in := range []string{
		`{"acls": [{"action": "accept", "src": ["*"], "dst": ["*:*"]}]}`,
		`{"grants": [{"src": ["*"], "dst": ["*"], "ip": ["*"]}]}`,
		`{"grants": [{"src": ["*"], "dst": ["*"]}]}`,
	} {
		if _, _, report, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil)); err != nil || !strings.HasPrefix(report.Changes[1], "Narrow ") {
			t.Errorf("%s: err = %v, changes %v", in, err, report.Changes)
		}
	}
	for _, in := range []string{
		`{"acls": [{"action": "accept", "src": ["*"], "dst": ["*:22"]}]}`,
		`{"acls": [{"action": "accept", "src": ["*"], "dst": ["autogroup:member:*"]}]}`,
		`{"acls": [{"action": "accept", "src": ["*"], "dst": ["tag:ovenlight-guest-coach:22"]}]}`,
		`{"acls": [{"action": "accept", "src": ["*"], "dst": ["100.64.0.0/10:22"]}]}`,
		`{"acls": [{"action": "accept", "src": ["*", "group:x"], "dst": ["*:*"]}]}`,
		`{"grants": [{"src": ["*"], "dst": ["*"], "ip": ["tcp:443"]}]}`,
		`{"grants": [{"src": ["*"], "dst": ["autogroup:member"], "ip": ["*"]}]}`,
	} {
		_, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil))
		var refusal *Refusal
		if !errors.As(err, &refusal) {
			t.Errorf("%s: err = %v, want a refusal", in, err)
			continue
		}
		for _, w := range []string{"rule 1 lets [\"*\"", "Ovenlight narrows on its own only a rule that lets everything reach everything", `source: replace "*" with ["autogroup:member"]`} {
			if !strings.Contains(err.Error(), w) {
				t.Errorf("%s: refusal doesn't mention %q:\n%s", in, w, err)
			}
		}
		if strings.Contains(err.Error(), "destination:") {
			t.Errorf("%s: the suggestion rewrites the destination:\n%s", in, err)
		}
	}
}

// A rule of the owner's own, from autogroup:member to autogroup:member, gets no app tags.
func TestMemberRuleStaysAsWritten(t *testing.T) {
	in := `{"grants": [{"src": ["autogroup:member"], "dst": ["autogroup:member"], "ip": ["tcp:22"]}]}`
	proposed, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(proposed), `{"src": ["autogroup:member"], "dst": ["autogroup:member"], "ip": ["tcp:22"]}`) {
		t.Errorf("the member rule changed:\n%s", proposed)
	}
}

// One tailnet address may be a guest device (Tailscale reuses a deleted device's
// address), so as a source it is refused; an address outside the tailnet is not.
func TestSingleTailnetAddressSource(t *testing.T) {
	for src, refuse := range map[string]bool{
		"100.101.102.103": true, "100.101.102.103/32": true, "fd7a:115c:a1e0::1": true, "nas": true,
		"192.168.1.5": false, "2001:db8::1": false,
	} {
		in := fmt.Sprintf(`{"hosts": {"nas": "100.101.102.103"}, "acls": [{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:member:*"]}, {"action": "accept", "src": [%q], "dst": ["autogroup:member:22"]}]}`, src)
		_, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil))
		var refusal *Refusal
		if errors.As(err, &refusal) != refuse || (err != nil && refusal == nil) {
			t.Errorf("Plan with source %s: err = %v, want refusal %v", src, err, refuse)
		} else if refuse && !strings.Contains(err.Error(), fmt.Sprintf("acls rule 2 lets %q reach", src)) {
			t.Errorf("Plan with source %s: refusal doesn't name the rule:\n%s", src, err)
		}
		err = CheckRestore([]byte(in), with(Device{Name: "sam", Tags: []string{GuestTag("coach")}}), nil)
		if errors.As(err, &refusal) != refuse || (err != nil && refusal == nil) {
			t.Errorf("CheckRestore with source %s: err = %v, want refusal %v", src, err, refuse)
		}
	}
}

// A tag that goes can't stay in use elsewhere: the policy would refer to a tag it no
// longer defines.
func TestRemovalRefusesTagUsedElsewhere(t *testing.T) {
	in := readCase(t, "already-applied-two", "")
	in = []byte(strings.Replace(string(in), `"ssh": [`, `"ssh": [
		{"action": "accept", "src": ["autogroup:admin"], "dst": ["tag:ovenlight-app-notes"], "users": ["root"]},`, 1))
	_, _, _, err := Plan(in, untaggedDevices, caseConfig(oneApp, []string{"notes"}))
	var refusal *Refusal
	if !errors.As(err, &refusal) || !strings.Contains(err.Error(), "tag:ovenlight-app-notes would go") {
		t.Fatalf("err = %v", err)
	}
}

// A backup restored as is may not let guest devices reach more than their app, once a
// guest may be on the tailnet.
func TestCheckRestore(t *testing.T) {
	guest := with(Device{Name: "sam", Tags: []string{GuestTag("coach")}})
	invite := []string{GuestTag("coach")} // an open invite's key
	for _, c := range []struct {
		in      string
		devices []Device
		keyTags []string
		refuse  bool
	}{
		{"default-acls", untaggedDevices, nil, false},
		{"default-acls", guest, nil, true},
		{"default-acls", untaggedDevices, invite, true},
		{"default-acls", untaggedDevices, []string{"tag:server"}, false},
		{"already-applied", guest, invite, false},
		{"applied-acls", guest, invite, false}, // Ovenlight's rules as an earlier version wrote them
		{"tag-owners-not-admin", untaggedDevices, invite, true},
	} {
		err := CheckRestore(readCase(t, c.in, ""), c.devices, c.keyTags)
		var refusal *Refusal
		if errors.As(err, &refusal) != c.refuse || (err != nil && refusal == nil) {
			t.Errorf("%s with %d devices and keys %v: err = %v, want refusal %v", c.in, len(c.devices), c.keyTags, err, c.refuse)
		}
	}
}

func TestPlanRejectsBadInput(t *testing.T) {
	for _, in := range []string{`[]`, `{"acls": {}}`, `{"acls": [], "ACLs": []}`, `{"acls": [{"src": "*"}]}`, `{`,
		`{"tagOwners": {"tag:x": "sam@example.com"}}`, `{"nodeAttrs": [{"target": "*"}]}`, `{"autoApprovers": {"exitNode": "tag:x"}}`} {
		if _, _, _, err := Plan([]byte(in), nil, Config{}); err == nil {
			t.Errorf("%s: expected an error", in)
		}
	}
	if _, _, _, err := Plan([]byte(`{}`), nil, Config{Apps: oneApp}); err == nil {
		t.Error("apps without an owner: expected an error")
	}
}

// Unpublishing the last app on an acls policy leaves it as setup-sharing left it: the
// grants section Ovenlight added goes too.
func TestUnpublishRestoresTheSetupPolicy(t *testing.T) {
	for _, name := range []string{"default-acls", "default-grants"} {
		setup, _, _, err := Plan(readCase(t, name, ""), untaggedDevices, caseConfig(nil, nil))
		if err != nil {
			t.Fatal(err)
		}
		shared, _, _, err := Plan(setup, untaggedDevices, caseConfig(twoApps, nil))
		if err != nil {
			t.Fatal(err)
		}
		unshared, _, _, err := Plan(shared, untaggedDevices, caseConfig(nil, twoApps))
		if err != nil {
			t.Fatal(err)
		}
		if ok, _ := Equivalent(unshared, setup); !ok {
			t.Errorf("%s after unpublishing:\n%s", name, unshared)
		}
	}
}

func TestClassifySource(t *testing.T) {
	hosts := map[string]string{"nas": "100.101.102.103", "tailnet": "100.64.0.0/10", "lan": "192.168.0.0/16"}
	cases := map[string]sourceKind{
		"*": srcWildcard, "tag:ovenlight-guest-coach": srcGuests, "autogroup:tagged": srcGuests, "autogroup:danger-all": srcGuests,
		"autogroup:member": srcNoGuests, "group:x": srcNoGuests, "sam@example.com": srcNoGuests, "tag:server": srcNoGuests, "tag:ovenlight-app-coach": srcNoGuests,
		"nas": srcGuests, "tailnet": srcGuests, "lan": srcNoGuests, "100.64.0.0/10": srcGuests, "100.100.0.0/16": srcGuests,
		"fd7a:115c:a1e0::/48": srcGuests, "10.0.0.0/8": srcNoGuests, "100.101.102.103/32": srcGuests, "100.101.102.103": srcGuests, "fd7a:115c:a1e0::1": srcGuests, "192.168.1.5": srcNoGuests, "192.168.1.5/32": srcNoGuests, "ipset:x": srcUnknown, "mystery": srcUnknown,
		"autogroup:self": srcNoGuests, "autogroup:internet": srcNoGuests, "autogroup:future": srcUnknown, "TAG:ovenlight-guest-coach": srcGuests,
		"::ffff:100.100.1.2": srcGuests, "::ffff:100.64.0.0/106": srcGuests, "::/80": srcGuests, "::/0": srcGuests,
		"::ffff:10.0.0.0/104": srcNoGuests, "2001:db8::/32": srcNoGuests,
	}
	for s, want := range cases {
		if got := classifySource(s, hosts); got != want {
			t.Errorf("classifySource(%q) = %v, want %v", s, got, want)
		}
	}
}

// An owner-shaped rule of someone other than the owner is theirs, such as an earlier
// owner's: it isn't handed to a new owner, who gets a rule of their own, or stripped when
// an app stops being shared. The owner's own rule is managed where it is, acls included.
func TestOtherUsersOwnerShapedRulesStay(t *testing.T) {
	const tags = `"tagOwners": {"tag:ovenlight-app-coach": ["autogroup:admin"], "tag:ovenlight-guest-coach": ["autogroup:admin"]},`
	const guests = `{"src": ["tag:ovenlight-guest-coach"], "dst": ["tag:ovenlight-app-coach"], "ip": ["tcp:443"]},`
	const members = `{"src": ["autogroup:member"], "dst": ["autogroup:member"], "ip": ["*"]}, `
	bobACL := `{"action": "accept", "src": ["bob@example.com"], "dst": ["tag:ovenlight-app-coach:443,8443"]}`
	ownACL := `{"action": "accept", "src": ["alex@example.com"], "dst": ["tag:ovenlight-app-coach:443,8443"]}`
	bobGrant := `{"src": ["bob@example.com"], "dst": ["tag:ovenlight-app-coach"], "ip": ["tcp:443", "tcp:8443"]}`
	aliceGrant := `{"src": ["alice@example.com"], "dst": ["tag:ovenlight-app-coach"], "ip": ["tcp:443", "tcp:8443"]}`
	ownGrant := `{"src": ["alex@example.com"], "dst": ["tag:ovenlight-app-coach"], "ip": ["tcp:443", "tcp:8443"]}`
	carol := Config{Owner: "carol@example.com", Apps: oneApp}
	for _, c := range []struct {
		name, in string
		cfg      Config
		keep     []string // rules the plan leaves as they are
		added    bool     // the plan adds an owner rule for cfg.Owner
		refuse   bool
	}{
		{"bob's acls rule", `{` + tags + `"grants": [` + ownGrant + `,` + guests + `], "acls": [{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:member:*"]}, ` + bobACL + `]}`,
			caseConfig(twoApps, nil), []string{bobACL}, false, false},
		{"one earlier owner", `{` + tags + `"grants": [` + members + bobGrant + `, ` + guests + `]}`, carol, []string{bobGrant}, true, false},
		{"two earlier owners", `{` + tags + `"grants": [` + members + bobGrant + `, ` + aliceGrant + `, ` + guests + `]}`, carol, []string{bobGrant, aliceGrant}, true, false},
		{"the owner's acls rule", `{` + tags + `"grants": [` + bobGrant + `, ` + guests + `], "acls": [` + ownACL + `]}`,
			caseConfig(oneApp, nil), []string{bobGrant, ownACL}, false, false},
		{"the earlier owner's rule in acls", `{` + tags + `"grants": [` + bobGrant + `, ` + guests + `], "acls": [{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:member:*"]}, ` + ownACL + `]}`,
			carol, []string{bobGrant, ownACL}, true, false},
		{"unpublish", `{` + tags + `"grants": [` + ownGrant + `, ` + bobGrant + `, ` + guests + `]}`,
			caseConfig(nil, oneApp), nil, false, true},
	} {
		proposed, _, report, err := Plan([]byte(c.in), untaggedDevices, c.cfg)
		var refusal *Refusal
		if c.refuse {
			if !errors.As(err, &refusal) || !strings.Contains(err.Error(), "tag:ovenlight-app-coach would go") {
				t.Errorf("%s: err = %v, want a refusal over tag:ovenlight-app-coach", c.name, err)
			}
			continue
		}
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		for _, r := range c.keep {
			if !strings.Contains(string(proposed), r) {
				t.Errorf("%s: %s changed or went:\n%s", c.name, r, proposed)
			}
		}
		added := slices.ContainsFunc(report.Changes, func(ch string) bool { return strings.HasPrefix(ch, "Add one rule to grants: "+c.cfg.Owner) })
		if want := ownerRule(c.cfg.withDefaults()); added != c.added || (added && !strings.Contains(string(proposed), want)) {
			t.Errorf("%s: changes %q, want an owner rule added: %v:\n%s", c.name, report.Changes, c.added, proposed)
		}
	}
}

// A rule of Ovenlight's that the owner added a key to, such as srcPosture, stays as they
// wrote it: it doesn't go with its app.
func TestRulesWithOwnersKeysStay(t *testing.T) {
	const tags = `"tagOwners": {"tag:ovenlight-app-coach": ["autogroup:admin"], "tag:ovenlight-guest-coach": ["autogroup:admin"]},`
	const ownGrant = `{"src": ["alex@example.com"], "dst": ["tag:ovenlight-app-coach"], "ip": ["tcp:443", "tcp:8443"]}`
	guestACL := `{"action": "accept", "src": ["tag:ovenlight-guest-coach"], "dst": ["tag:ovenlight-app-coach:443"], "srcPosture": ["posture:latestMac"]}`
	in := `{` + tags + `"acls": [{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:member:*"]}, ` + guestACL + `], "grants": [` + ownGrant + `]}`
	proposed, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(proposed), guestACL) {
		t.Errorf("the guest rule with a posture check changed:\n%s", proposed)
	}
	_, _, _, err = Plan([]byte(in), untaggedDevices, caseConfig(nil, oneApp))
	var refusal *Refusal
	if !errors.As(err, &refusal) || !strings.Contains(err.Error(), "would go") {
		t.Errorf("unpublishing: err = %v, want a refusal over the tags that would go", err)
	}
}

func TestEquivalent(t *testing.T) {
	a := []byte("{\n\t// c\n\t\"acls\": [ {\"src\": [\"*\"]}, ],\n}")
	b := []byte(`{"acls":[{"src":["*"]}]}`)
	if ok, err := Equivalent(a, b); err != nil || !ok {
		t.Errorf("Equivalent = %v, %v", ok, err)
	}
	if ok, _ := Equivalent(a, []byte(`{"acls":[]}`)); ok {
		t.Error("different policies reported equivalent")
	}
}

func TestDiff(t *testing.T) {
	if Diff("a\nb\n", "a\nb\n") != "" {
		t.Error("equal texts should have no diff")
	}
	got := Diff("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n", "1\n2\n3\n4\nnew\n5\n6\n7\n8\n9\n10\n")
	want := "--- current policy\n+++ proposed policy\n@@ -2,6 +2,7 @@\n 2\n 3\n 4\n+new\n 5\n 6\n 7\n"
	if got != want {
		t.Errorf("diff:\n%s\nwant:\n%s", got, want)
	}
}

// Tailscale reads tag names as lowercase, so a tagOwners key in other case is the same
// tag: its owners count, it isn't added again, and it goes with its app.
func TestTagNamesIgnoreCase(t *testing.T) {
	in := strings.Replace(string(readCase(t, "already-applied", "")), `"tag:ovenlight-guest-coach": ["autogroup:admin"],`,
		`"tag:ovenlight-guest-coach": ["autogroup:admin"],
		"tag:Ovenlight-App-coach": ["group:family"],`, 1)
	_, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(twoApps, nil))
	var refusal *Refusal
	if !errors.As(err, &refusal) || !strings.Contains(err.Error(), `tagOwners lets "autogroup:admin", "group:family" apply tag:ovenlight-app-coach`) ||
		!strings.Contains(err.Error(), `also lists it as "tag:Ovenlight-App-coach"`) {
		t.Errorf("err = %v, want a refusal over group:family", err)
	}
	if err := CheckRestore([]byte(in), with(Device{Name: "sam", Tags: []string{GuestTag("coach")}}), nil); !errors.As(err, &refusal) {
		t.Errorf("CheckRestore: err = %v, want a refusal", err)
	}

	in = `{"tagOwners": {"TAG:OVENLIGHT-APP-COACH": ["autogroup:admin"]}, "grants": [{"src": ["autogroup:member"], "dst": ["autogroup:member"], "ip": ["*"]}]}`
	proposed, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil))
	if err != nil {
		t.Fatal(err)
	}
	if n := strings.Count(strings.ToLower(string(proposed)), `"`+AppTag("coach")+`": [`); n != 1 {
		t.Errorf("%s is in tagOwners %d times:\n%s", AppTag("coach"), n, proposed)
	}
	unshared, _, _, err := Plan(proposed, untaggedDevices, caseConfig(nil, oneApp))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(strings.ToLower(string(unshared)), "ovenlight") {
		t.Errorf("after unpublishing:\n%s", unshared)
	}

	// A tag that goes can't stay in use in other case either.
	in = strings.Replace(string(readCase(t, "already-applied-two", "")), `"ssh": [`, `"ssh": [
		{"action": "accept", "src": ["autogroup:admin"], "dst": ["tag:Ovenlight-App-notes"], "users": ["root"]},`, 1)
	if _, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, []string{"notes"})); !errors.As(err, &refusal) || !strings.Contains(err.Error(), "tag:ovenlight-app-notes would go") {
		t.Errorf("err = %v, want a refusal over tag:ovenlight-app-notes", err)
	}
}

// Go's JSON decoding matches keys without regard to Unicode case ("ſrc" is src), which
// this planner doesn't, so a key outside ASCII is refused wherever it is.
func TestPlanRejectsNonASCIIKeys(t *testing.T) {
	for _, in := range []string{
		`{"grants": [{"src": ["autogroup:member"], "ſrc": ["*"], "dst": ["*"], "ip": ["*"]}]}`,
		`{"aclſ": [{"action": "accept", "src": ["*"], "dst": ["*:*"]}]}`,
		`{"grants": [{"src": ["autogroup:member"], "dst": ["autogroup:member"], "ip": ["*"]}], "tagOwners": {"tag:K": ["autogroup:admin"]}}`,
		`{"grants": [{"src": ["autogroup:member"], "ſrc": ["*"], "dst": ["*"], "ip": ["*"]}]}`,
	} {
		_, _, _, err := Plan([]byte(in), untaggedDevices, caseConfig(oneApp, nil))
		if err == nil || !strings.Contains(err.Error(), "isn't plain ASCII") {
			t.Errorf("%s: err = %v, want a refusal over the key", in, err)
		}
		if err := CheckRestore([]byte(in), nil, nil); err == nil {
			t.Errorf("CheckRestore %s: no error", in)
		}
	}
}
