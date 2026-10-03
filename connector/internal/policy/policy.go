// Package policy plans the changes Ovenlight needs in a tailnet policy file. Each
// shareable app gets its own pair of tags: tag:ovenlight-app-<slug> for its node and
// tag:ovenlight-guest-<slug> for the guests invited to it, with one rule letting those
// guests reach only that node on port 443, and one rule letting the owner reach the app
// nodes. Ovenlight writes new rules to grants, and manages the ones earlier versions
// wrote into acls where they are. On a tailnet that still lets everything
// reach everything, the plan also narrows that rule to "your devices reach your
// devices", so guests neither inherit it nor see the owner's devices.
//
// Plan is pure: it reads the current policy and device list and returns the proposed
// file. It edits the original text in place (inserting, replacing and deleting byte
// ranges found with tailscale's hujson parser) instead of re-serializing, so comments,
// ordering and formatting survive.
package policy

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"reflect"
	"slices"
	"sort"
	"strconv"
	"strings"

	"github.com/tailscale/hujson"
)

const (
	AppTagPrefix     = "tag:ovenlight-app-"
	GuestTagPrefix   = "tag:ovenlight-guest-"
	DefaultTagOwner  = "autogroup:admin"
	DefaultPort      = 443
	DefaultAdminPort = 8443

	// member replaces "*" in the allow-all rule, as its source and its destination. It
	// covers every user's own devices and no tagged device. Tailscale allows it both as
	// a src and as a dst: see the autogroups table in
	// https://tailscale.com/kb/1337/policy-syntax#autogroups and the dst selectors in
	// https://tailscale.com/kb/1538/grants-syntax.
	member = "autogroup:member"
)

// AppTag is the tag of a shareable app's node.
func AppTag(slug string) string { return AppTagPrefix + slug }

// GuestTag is the tag of the devices of guests invited to the app.
func GuestTag(slug string) string { return GuestTagPrefix + slug }

// IsAppTag reports an Ovenlight app tag.
func IsAppTag(t string) bool { return strings.HasPrefix(t, AppTagPrefix) }

// IsGuestTag reports an Ovenlight guest tag.
func IsGuestTag(t string) bool { return strings.HasPrefix(t, GuestTagPrefix) }

// isOvenlightTag reports an Ovenlight tag in any case: Tailscale reads tag names as
// lowercase.
func isOvenlightTag(t string) bool {
	t = strings.ToLower(t)
	return IsAppTag(t) || IsGuestTag(t)
}

// Config says what the policy should allow. Zero fields mean the defaults above.
type Config struct {
	TagOwner  string
	Port      int // guests and the owner open apps here
	AdminPort int // the owner's admin API; guests never reach it
	// Owner is the login of the person who owns the apps, needed with Apps: the plan
	// lets them reach every shareable app's node on both ports.
	Owner string
	// Apps are the slugs of the shareable apps: their tags and rules are added.
	Apps []string
	// Remove are slugs of apps no longer shared: their tags and rules are removed.
	Remove []string
}

func (c Config) withDefaults() Config {
	if c.TagOwner == "" {
		c.TagOwner = DefaultTagOwner
	}
	if c.Port == 0 {
		c.Port = DefaultPort
	}
	if c.AdminPort == 0 {
		c.AdminPort = DefaultAdminPort
	}
	c.Apps = slices.Compact(slices.Sorted(slices.Values(c.Apps)))
	var remove []string
	for _, s := range slices.Compact(slices.Sorted(slices.Values(c.Remove))) {
		if !slices.Contains(c.Apps, s) {
			remove = append(remove, s)
		}
	}
	c.Remove = remove
	return c
}

func (c Config) appTags() []string {
	out := make([]string, len(c.Apps))
	for i, s := range c.Apps {
		out[i] = AppTag(s)
	}
	return out
}

// Device is the part of a tailnet device the planner needs.
type Device struct {
	Name   string
	Tags   []string
	Routes []string // subnet routes it advertises or has approved; 0.0.0.0/0 or ::/0 make it an exit node
}

// Report explains a plan. Changes is empty when the policy already has everything.
type Report struct {
	Changes []string // what the proposed policy changes, one sentence each
	Notes   []string // what the owner should know either way
	// Exposed are rules that let some of the owner's devices reach guest devices, so
	// guests see those devices as peers.
	Exposed []string
}

// Changed reports whether the plan changes the policy.
func (r Report) Changed() bool { return len(r.Changes) > 0 }

// Refusal is returned when the policy can't be changed safely without a person
// deciding something. Suggestion is a manual edit that would unblock the plan.
type Refusal struct {
	Reasons    []string
	Suggestion string
}

func (r *Refusal) Error() string {
	var b strings.Builder
	b.WriteString("Ovenlight won't change this policy on its own:\n")
	for _, reason := range r.Reasons {
		b.WriteString("  - " + reason + "\n")
	}
	if r.Suggestion != "" {
		b.WriteString("\nSuggested manual edit:\n" + r.Suggestion)
	}
	return strings.TrimRight(b.String(), "\n")
}

// Plan returns the proposed policy, a unified diff from the current one, and a report.
// On a policy that already has everything the proposed policy is the current one, byte
// for byte, and the report has no changes. A *Refusal error means the policy needs a
// manual edit first; other errors mean the policy couldn't be read.
func Plan(current []byte, devices []Device, cfg Config) ([]byte, string, Report, error) {
	cfg = cfg.withDefaults()
	if len(cfg.Apps) > 0 && cfg.Owner == "" {
		return nil, "", Report{}, errors.New("sharing apps needs their owner's login")
	}
	proposed, report, err := plan(current, devices, cfg)
	if err != nil {
		return nil, "", report, err
	}
	if !report.Changed() {
		return slices.Clone(current), "", report, nil
	}
	// The proposed policy must parse and need nothing more; anything else is a bug here.
	again, check, err := plan(proposed, devices, cfg)
	if err != nil {
		return nil, "", report, fmt.Errorf("internal error: the proposed policy: %w", err)
	}
	if check.Changed() || !bytes.Equal(again, proposed) {
		return nil, "", report, fmt.Errorf("internal error: the proposed policy still needs changes: %v", check.Changes)
	}
	return proposed, Diff(string(current), string(proposed)), report, nil
}

// plan runs in two passes over the text: first it removes what goes (apps no longer
// shared), then, on the result, it adds what is missing. Each pass's
// edits never touch the same bytes.
func plan(current []byte, devices []Device, cfg Config) ([]byte, Report, error) {
	d, err := parseDoc(current)
	if err != nil {
		return nil, Report{}, err
	}
	removedTags := d.presentTags(cfg)
	src, changes, err := d.planRemovals(cfg)
	if err != nil {
		return nil, Report{}, err
	}
	if len(changes) > 0 {
		if d, err = parseDoc(src); err != nil {
			return nil, Report{}, fmt.Errorf("internal error: the policy doesn't parse after removals: %w", err)
		}
	}
	out, report, err := d.planAdditions(devices, cfg)
	report.Changes = append(changes, report.Changes...)
	if err != nil {
		return nil, report, err
	}
	// A removed tag used anywhere else, in any case, would leave the policy referring to
	// a tag it no longer defines.
	std, err := hujson.Standardize(slices.Clone(out))
	if err != nil {
		return nil, report, fmt.Errorf("internal error: the proposed policy doesn't parse: %w", err)
	}
	std = bytes.ToLower(std)
	var still []string
	for _, tag := range removedTags {
		if bytes.Contains(std, []byte(`"`+tag+`"`)) || bytes.Contains(std, []byte(`"`+tag+`:`)) {
			still = append(still, tag)
		}
	}
	if len(still) > 0 {
		return nil, report, &Refusal{
			Reasons:    []string{fmt.Sprintf("%s would go, but the policy still uses it outside the rules Ovenlight manages", strings.Join(still, " and "))},
			Suggestion: "  Remove those uses in the admin console (Access controls), then run the command again.",
		}
	}
	return out, report, nil
}

// Equivalent reports whether two policy files mean the same thing, ignoring comments,
// whitespace and trailing commas.
func Equivalent(a, b []byte) (bool, error) {
	var va, vb any
	for i, in := range [][]byte{a, b} {
		std, err := hujson.Standardize(slices.Clone(in))
		if err != nil {
			return false, err
		}
		target := &va
		if i == 1 {
			target = &vb
		}
		if err := json.Unmarshal(std, target); err != nil {
			return false, err
		}
	}
	return reflect.DeepEqual(va, vb), nil
}

// ---------- reading the policy ----------

// strEntry is one string in a JSON array, with the byte range of its quoted literal.
type strEntry struct {
	value      string
	start, end int
}

type rule struct {
	section  string // acls or grants
	index    int
	src      []strEntry
	dst      []string
	dstVal   *hujson.Value // the destination list, for rewriting it
	dstLists int           // how many destination lists the rule has (dst and ports)
	ip       []string      // grants only
	proto    string        // acls only
	action   string        // acls only
	extra    bool          // it has a key Ovenlight doesn't write, such as srcPosture
}

func (r rule) label() string { return fmt.Sprintf("%s rule %d", r.section, r.index+1) }

func (r rule) srcValues() []string { return values(r.src) }

func (r rule) dstSummary() string {
	s := quoteList(r.dst)
	if r.section == "grants" && len(r.ip) > 0 {
		s += " (ip " + strings.Join(r.ip, ", ") + ")"
	}
	return s
}

type doc struct {
	src        []byte
	root       *hujson.Value
	sections   map[string]*hujson.Value // "acls", "grants" -> the array value
	tagOwners  *hujson.Value
	owners     map[string][]string // tag, in lowercase -> owners
	spelled    map[string][]string // tag, in lowercase -> its tagOwners keys in other case
	hosts      map[string]string
	rules      []rule
	sshSources []string
	nodeAttrs  []nodeAttr
	approvers  []approverList
}

// nodeAttr is one nodeAttrs entry: the devices it targets and what it gives them, each
// attribute as written, each app capability as "app:<name>", and "ipPool" or any other
// key by its name.
type nodeAttr struct {
	index      int
	target     []string
	gives      []string
	connectors []string // the devices its App Connectors run on
}

func (n nodeAttr) label() string { return fmt.Sprintf("nodeAttrs entry %d", n.index+1) }

// approverList is one list of autoApprovers, labeled by where it sits in the policy, such
// as `autoApprovers.routes["10.0.0.0/24"]` or `autoApprovers.exitNode`.
type approverList struct {
	label     string
	approvers []string
}

func parseDoc(b []byte) (*doc, error) {
	v, err := hujson.Parse(b)
	if err != nil {
		return nil, fmt.Errorf("the policy file isn't valid HuJSON: %w", err)
	}
	obj, ok := v.Value.(*hujson.Object)
	if !ok {
		return nil, errors.New("the policy file isn't a JSON object")
	}
	// JSON readers such as Go's match keys without regard to Unicode case ("ſrc" is src),
	// and this planner matches them in ASCII case only.
	for x := range v.All() {
		if o, ok := x.Value.(*hujson.Object); ok {
			for i := range o.Members {
				if name := memberName(&o.Members[i]); strings.ContainsFunc(name, func(r rune) bool { return r >= 0x80 }) {
					return nil, fmt.Errorf("the policy file has the key %q, which isn't plain ASCII: a JSON reader may take it for another key, so Ovenlight won't read the file. Rename or remove it in the admin console (Access controls)", name)
				}
			}
		}
	}
	d := &doc{src: b, root: &v, sections: map[string]*hujson.Value{}, owners: map[string][]string{}, spelled: map[string][]string{}, hosts: map[string]string{}}
	seen := map[string]bool{}
	for i := range obj.Members {
		m := &obj.Members[i]
		name := strings.ToLower(memberName(m))
		// Tailscale matches top-level keys without regard to case.
		switch name {
		case "acls", "grants", "tagowners", "hosts", "ssh", "nodeattrs", "autoapprovers":
			if seen[name] {
				return nil, fmt.Errorf("the policy file has %q twice", memberName(m))
			}
			seen[name] = true
		}
		switch name {
		case "acls", "grants":
			arr, ok := m.Value.Value.(*hujson.Array)
			if !ok {
				return nil, fmt.Errorf("%q must be a list", memberName(m))
			}
			d.sections[name] = &m.Value
			for j := range arr.Elements {
				r, err := parseRule(name, j, &arr.Elements[j])
				if err != nil {
					return nil, err
				}
				d.rules = append(d.rules, r)
			}
		case "tagowners":
			tobj, ok := m.Value.Value.(*hujson.Object)
			if !ok {
				return nil, errors.New(`"tagOwners" must be an object`)
			}
			d.tagOwners = &m.Value
			for j := range tobj.Members {
				name := memberName(&tobj.Members[j])
				owners, ok := stringList(&tobj.Members[j].Value)
				if !ok {
					return nil, fmt.Errorf("tagOwners %q must be a list of strings", name)
				}
				// Tailscale reads tag names as lowercase: keys that differ only in case are one
				// tag, with the owners of each.
				tag := strings.ToLower(name)
				if name != tag {
					d.spelled[tag] = append(d.spelled[tag], name)
				}
				all := d.owners[tag]
				for _, o := range values(owners) {
					if !slices.Contains(all, o) {
						all = append(all, o)
					}
				}
				d.owners[tag] = all
			}
		case "hosts":
			if hobj, ok := m.Value.Value.(*hujson.Object); ok {
				for j := range hobj.Members {
					if lit, ok := hobj.Members[j].Value.Value.(hujson.Literal); ok {
						d.hosts[memberName(&hobj.Members[j])] = lit.String()
					}
				}
			}
		case "nodeattrs":
			arr, ok := m.Value.Value.(*hujson.Array)
			if !ok {
				return nil, fmt.Errorf("%q must be a list", memberName(m))
			}
			for j := range arr.Elements {
				n, err := parseNodeAttr(j, &arr.Elements[j])
				if err != nil {
					return nil, err
				}
				d.nodeAttrs = append(d.nodeAttrs, n)
			}
		case "autoapprovers":
			if _, ok := m.Value.Value.(*hujson.Object); !ok {
				return nil, fmt.Errorf("%q must be an object", memberName(m))
			}
			if err := collectApprovers(memberName(m), &m.Value, &d.approvers); err != nil {
				return nil, err
			}
		case "ssh":
			if arr, ok := m.Value.Value.(*hujson.Array); ok {
				for j := range arr.Elements {
					if robj, ok := arr.Elements[j].Value.(*hujson.Object); ok {
						for k := range robj.Members {
							if strings.EqualFold(memberName(&robj.Members[k]), "src") {
								src, _ := stringList(&robj.Members[k].Value)
								d.sshSources = append(d.sshSources, values(src)...)
							}
						}
					}
				}
			}
		}
	}
	return d, nil
}

func parseRule(section string, index int, v *hujson.Value) (rule, error) {
	r := rule{section: section, index: index}
	obj, ok := v.Value.(*hujson.Object)
	if !ok {
		return r, fmt.Errorf("%s rule %d isn't an object", section, index+1)
	}
	for i := range obj.Members {
		m := &obj.Members[i]
		key := strings.ToLower(memberName(m))
		list, isList := stringList(&m.Value)
		switch {
		// "users" and "ports" are the old names of "src" and "dst" in acls.
		case key == "src" || (section == "acls" && key == "users"):
			if !isList {
				return r, fmt.Errorf("%s rule %d: %q must be a list of strings", section, index+1, memberName(m))
			}
			r.src = append(r.src, list...)
		case key == "dst" || (section == "acls" && key == "ports"):
			if !isList {
				return r, fmt.Errorf("%s rule %d: %q must be a list of strings", section, index+1, memberName(m))
			}
			r.dst = append(r.dst, values(list)...)
			r.dstVal = &m.Value
			r.dstLists++
		case section == "grants" && key == "ip":
			if !isList {
				return r, fmt.Errorf("grants rule %d: \"ip\" must be a list of strings", index+1)
			}
			r.ip = values(list)
		case section == "acls" && key == "proto":
			if lit, ok := m.Value.Value.(hujson.Literal); ok {
				r.proto = lit.String()
			}
		case section == "acls" && key == "action":
			if lit, ok := m.Value.Value.(hujson.Literal); ok {
				r.action = lit.String()
			}
		default:
			r.extra = true
		}
	}
	return r, nil
}

func parseNodeAttr(index int, v *hujson.Value) (nodeAttr, error) {
	n := nodeAttr{index: index}
	obj, ok := v.Value.(*hujson.Object)
	if !ok {
		return n, fmt.Errorf("%s isn't an object", n.label())
	}
	for i := range obj.Members {
		m := &obj.Members[i]
		list, isList := stringList(&m.Value)
		switch strings.ToLower(memberName(m)) {
		case "target":
			if !isList {
				return n, fmt.Errorf("%s: %q must be a list of strings", n.label(), memberName(m))
			}
			n.target = append(n.target, values(list)...)
		case "attr":
			if !isList {
				return n, fmt.Errorf("%s: %q must be a list of strings", n.label(), memberName(m))
			}
			n.gives = append(n.gives, values(list)...)
		case "app":
			app, ok := m.Value.Value.(*hujson.Object)
			if !ok {
				return n, fmt.Errorf("%s: %q must be an object", n.label(), memberName(m))
			}
			for j := range app.Members {
				name := memberName(&app.Members[j])
				n.gives = append(n.gives, "app:"+name)
				if name == appConnectors {
					c, err := parseConnectors(n, &app.Members[j].Value)
					if err != nil {
						return n, err
					}
					n.connectors = append(n.connectors, c...)
				}
			}
		default:
			n.gives = append(n.gives, memberName(m))
		}
	}
	return n, nil
}

const appConnectors = "tailscale.com/app-connectors"

// parseConnectors reads the connectors lists of an App Connector capability: the
// devices that route its domains.
func parseConnectors(n nodeAttr, v *hujson.Value) ([]string, error) {
	bad := fmt.Errorf("%s: %q must be a list of objects with a list of connectors", n.label(), appConnectors)
	arr, ok := v.Value.(*hujson.Array)
	if !ok {
		return nil, bad
	}
	var out []string
	for i := range arr.Elements {
		obj, ok := arr.Elements[i].Value.(*hujson.Object)
		if !ok {
			return nil, bad
		}
		for j := range obj.Members {
			if strings.EqualFold(memberName(&obj.Members[j]), "connectors") {
				list, ok := stringList(&obj.Members[j].Value)
				if !ok {
					return nil, bad
				}
				out = append(out, values(list)...)
			}
		}
	}
	return out, nil
}

// clientOnly reports what a nodeAttrs entry may give guest devices, because it changes
// only how a device itself behaves: DNS through NextDNS, its own port, captive portal
// checks, and the App Connector domains (which devices run the connectors is checked
// apart). Anything else (funnel, drive sharing, mullvad, which spends a Mullvad device
// slot per guest, ipPool, unknown ones) is not.
func clientOnly(give string) bool {
	return strings.HasPrefix(give, "nextdns:") || give == "randomize-client-port" ||
		give == "disable-captive-portal-detection" || give == "app:"+appConnectors
}

// collectApprovers gathers every list of approvers under autoApprovers, whatever its
// key (routes, exitNode, or one this planner doesn't know), so none escapes the check.
func collectApprovers(label string, v *hujson.Value, out *[]approverList) error {
	switch x := v.Value.(type) {
	case *hujson.Object:
		for i := range x.Members {
			name := memberName(&x.Members[i])
			sub := label + "." + name
			if strings.ContainsAny(name, "./:") {
				sub = fmt.Sprintf("%s[%q]", label, name)
			}
			if err := collectApprovers(sub, &x.Members[i].Value, out); err != nil {
				return err
			}
		}
		return nil
	case *hujson.Array:
		list, ok := stringList(v)
		if !ok {
			return fmt.Errorf("%s must be a list of strings", label)
		}
		*out = append(*out, approverList{label: label, approvers: values(list)})
		return nil
	}
	return fmt.Errorf("%s must be a list of strings", label)
}

func memberName(m *hujson.ObjectMember) string {
	if lit, ok := m.Name.Value.(hujson.Literal); ok {
		return lit.String()
	}
	return ""
}

// stringList reads an array of strings. It reports false for anything else.
func stringList(v *hujson.Value) ([]strEntry, bool) {
	arr, ok := v.Value.(*hujson.Array)
	if !ok {
		return nil, false
	}
	out := make([]strEntry, 0, len(arr.Elements))
	for i := range arr.Elements {
		el := &arr.Elements[i]
		lit, ok := el.Value.(hujson.Literal)
		if !ok || lit.Kind() != '"' {
			return nil, false
		}
		out = append(out, strEntry{value: lit.String(), start: el.StartOffset, end: el.EndOffset})
	}
	return out, true
}

func values(entries []strEntry) []string {
	out := make([]string, len(entries))
	for i, e := range entries {
		out[i] = e.value
	}
	return out
}

// ---------- the rules Ovenlight manages ----------

// hostOf is a destination's host: the entry itself in grants, the part before the port
// list in acls, without the brackets around an IPv6 address.
func hostOf(section, dst string) (host, ports string) {
	if section == "grants" {
		return dst, ""
	}
	i := strings.LastIndex(dst, ":")
	if i < 0 {
		return dst, ""
	}
	host, ports = dst[:i], dst[i+1:]
	if len(host) > 2 && host[0] == '[' && host[len(host)-1] == ']' {
		host = host[1 : len(host)-1]
	}
	return host, ports
}

// onlyPort reports whether the rule's one destination is host on port and nothing else.
func onlyPort(r rule, host string, port int) bool {
	if len(r.dst) != 1 {
		return false
	}
	p := strconv.Itoa(port)
	if r.section == "grants" {
		if r.dst[0] != host || len(r.ip) == 0 {
			return false
		}
		for _, ip := range r.ip {
			if ip != p && ip != "tcp:"+p && ip != "6:"+p {
				return false
			}
		}
		return true
	}
	return (r.proto == "" || r.proto == "tcp" || r.proto == "6") && r.dst[0] == host+":"+p
}

// guestRuleSlug recognizes the rule letting an app's guests reach its node: source the
// app's guest tag, destination the app's tag on the shared port. Earlier versions wrote
// it into acls on a policy that used acls, and it is recognized there too.
func guestRuleSlug(r rule, cfg Config) (string, bool) {
	if len(r.src) != 1 || !strings.HasPrefix(r.src[0].value, GuestTagPrefix) {
		return "", false
	}
	slug := strings.TrimPrefix(r.src[0].value, GuestTagPrefix)
	return slug, onlyPort(r, AppTag(slug), cfg.Port)
}

// allPorts is host on every port, as a destination in section.
func allPorts(section, host string) string {
	if section == "acls" {
		return host + ":*"
	}
	return host
}

// isOwnerRule recognizes a rule shaped like the one letting the owner reach the app nodes
// on both ports, whoever its source: one user as the source, only app tags as
// destinations, tcp on the two ports, in grants or, as earlier versions wrote it, in acls.
func isOwnerRule(r rule, cfg Config) bool {
	if len(r.src) != 1 || !isUser(r.src[0].value) || len(r.dst) == 0 {
		return false
	}
	ports := []string{strconv.Itoa(cfg.Port), strconv.Itoa(cfg.AdminPort)}
	for _, d := range r.dst {
		host, p := hostOf(r.section, d)
		if !IsAppTag(host) || (r.section == "acls" && p != strings.Join(ports, ",")) {
			return false
		}
	}
	if r.section == "acls" {
		return r.proto == ""
	}
	want := []string{"tcp:" + ports[0], "tcp:" + ports[1]}
	return slices.Equal(slices.Sorted(slices.Values(r.ip)), slices.Sorted(slices.Values(want)))
}

// isOwnRule reports the owner rule of the current owner.
func isOwnRule(r rule, cfg Config) bool {
	return isOwnerRule(r, cfg) && EqualFoldASCII(r.src[0].value, cfg.Owner)
}

// ownerRuleAt is the owner rule the additions pass extends, as an index into d.rules, or
// -1: the owner's own, in grants if there is one there. A rule of anyone else, such as
// an earlier owner, stays its author's.
func (d *doc) ownerRuleAt(cfg Config) int {
	if at := slices.IndexFunc(d.rules, func(r rule) bool { return r.section == "grants" && isOwnRule(r, cfg) }); at >= 0 {
		return at
	}
	return slices.IndexFunc(d.rules, func(r rule) bool { return isOwnRule(r, cfg) })
}

// ownerDst is an app tag as a destination of the owner rule in section.
func ownerDst(section, tag string, cfg Config) string {
	if section == "acls" {
		return fmt.Sprintf("%s:%d,%d", tag, cfg.Port, cfg.AdminPort)
	}
	return tag
}

// isUser reports a login, as a rule source.
func isUser(s string) bool { return strings.Contains(s, "@") && !strings.Contains(s, ":") }

// presentTags are the tags the removal pass takes out that the policy defines now.
func (d *doc) presentTags(cfg Config) []string {
	var out []string
	for _, tag := range removedTags(cfg) {
		if _, ok := d.owners[tag]; ok {
			out = append(out, tag)
		}
	}
	return out
}

func removedTags(cfg Config) []string {
	var out []string
	for _, s := range cfg.Remove {
		out = append(out, AppTag(s), GuestTag(s))
	}
	return out
}

// ---------- removals ----------

// planRemovals takes out the tags and rules of apps no longer shared, wherever
// Ovenlight's rules are.
func (d *doc) planRemovals(cfg Config) ([]byte, []string, error) {
	gone := map[string]bool{}
	for _, t := range removedTags(cfg) {
		gone[t] = true
	}
	l := newLayout(d.src, d.root)
	var edits []edit
	var changes []string
	deleted := map[string]map[int]bool{} // section -> rule indexes
	del := func(r rule) {
		if deleted[r.section] == nil {
			deleted[r.section] = map[int]bool{}
		}
		deleted[r.section][r.index] = true
	}
	removedFrom := map[string][]string{} // tag -> rule labels
	for _, r := range d.rules {
		// A rule the owner added a key to, such as srcPosture, is theirs: it doesn't go, and
		// if it then uses a tag that goes, the plan refuses.
		if slug, ok := guestRuleSlug(r, cfg); ok && !r.extra && slices.Contains(cfg.Remove, slug) {
			del(r)
			continue
		}
		// So is an owner-shaped rule of anyone but the owner, such as an earlier owner.
		if !isOwnRule(r, cfg) {
			continue
		}
		var keep []string
		for _, dst := range r.dst {
			host, _ := hostOf(r.section, dst)
			if host = strings.ToLower(host); gone[host] {
				removedFrom[host] = append(removedFrom[host], r.label())
			} else {
				keep = append(keep, dst)
			}
		}
		switch {
		case len(keep) == len(r.dst):
		case len(keep) == 0:
			del(r)
		default:
			e, err := d.rewriteDst(r, keep)
			if err != nil {
				return nil, nil, err
			}
			edits = append(edits, e)
		}
	}
	// Root members that go are deleted in one pass, so two adjacent ones don't both take
	// the comma between them.
	rootDel := map[int]bool{}
	for section, idx := range deleted {
		sec := d.sections[section]
		// A grants section left empty goes when the file keeps its rules in acls, as it did
		// before Ovenlight added grants, unless the owner's comments are inside.
		if section == "grants" && len(idx) == len(sec.Value.(*hujson.Array).Elements) && d.sections["acls"] != nil &&
			onlyOvenlightComments(d.src, sec) {
			rootDel[memberIndex(d.root, sec)] = true
		} else {
			edits = append(edits, l.deleteElements(sec, idx)...)
		}
	}
	if d.tagOwners != nil {
		idx := map[int]bool{}
		members := d.tagOwners.Value.(*hujson.Object).Members
		for i, m := range members {
			if gone[strings.ToLower(memberName(&m))] {
				idx[i] = true
			}
		}
		if len(idx) > 0 && len(idx) == len(members) {
			// Nothing but Ovenlight's tags: the whole tagOwners goes.
			rootDel[memberIndex(d.root, d.tagOwners)] = true
		} else {
			edits = append(edits, l.deleteElements(d.tagOwners, idx)...)
		}
	}
	edits = append(edits, l.deleteElements(d.root, rootDel)...)
	if len(edits) == 0 {
		return d.src, nil, nil
	}

	for _, slug := range cfg.Remove {
		_, a := d.owners[AppTag(slug)]
		_, g := d.owners[GuestTag(slug)]
		rule := false
		for _, r := range d.rules {
			if s, ok := guestRuleSlug(r, cfg); ok && s == slug {
				rule = true
			}
		}
		labels := removedFrom[AppTag(slug)]
		if !a && !g && !rule && len(labels) == 0 {
			continue
		}
		msg := fmt.Sprintf("Stop sharing %s: remove %s and %s, the rule between them", slug, AppTag(slug), GuestTag(slug))
		if len(labels) > 0 {
			msg += ", and " + AppTag(slug) + " from " + strings.Join(labels, " and ")
		}
		changes = append(changes, msg+".")
	}
	return applyEdits(d.src, edits), changes, nil
}

// rewriteDst replaces a rule's destination list.
func (d *doc) rewriteDst(r rule, dst []string) (edit, error) {
	if r.dstVal == nil || r.dstLists != 1 {
		return edit{}, &Refusal{Reasons: []string{fmt.Sprintf("%s has more than one destination list, so Ovenlight can't rewrite it", r.label())}}
	}
	old := string(d.src[r.dstVal.StartOffset:r.dstVal.EndOffset])
	if strings.Contains(old, "//") || strings.Contains(old, "/*") {
		return edit{}, &Refusal{Reasons: []string{fmt.Sprintf("the destination list of %s has comments inside, so Ovenlight won't rewrite it", r.label())},
			Suggestion: fmt.Sprintf("  Set the destination of %s to %s by hand, then run the command again.", r.label(), quoteList(dst))}
	}
	return edit{pos: r.dstVal.StartOffset, del: r.dstVal.EndOffset - r.dstVal.StartOffset, text: quoteList(dst)}, nil
}

// ---------- additions ----------

// sourceKind says whether a rule source can match a guest device.
type sourceKind int

const (
	srcNoGuests sourceKind = iota // users, groups, most autogroups, other tags, addresses outside the tailnet
	srcWildcard                   // "*"
	srcGuests                     // guest tags, autogroup:tagged, autogroup:danger-all, tailnet addresses and ranges
	srcUnknown                    // something this planner can't prove either way
)

// tailnetRanges are the address ranges Tailscale assigns devices from; the IPv4 one is
// listed again in its IPv4-mapped IPv6 form, which may name the same devices.
var tailnetRanges = []netip.Prefix{netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
	netip.MustParsePrefix("::ffff:100.64.0.0/106")}

// noGuestAutogroups are the autogroups that never include a tagged device, and so no
// guest device. Any other autogroup is unknown.
var noGuestAutogroups = []string{
	"autogroup:member", "autogroup:admin", "autogroup:owner", "autogroup:it-admin", "autogroup:network-admin",
	"autogroup:billing-admin", "autogroup:auditor", "autogroup:shared", "autogroup:self", "autogroup:internet",
}

func classifySource(s string, hosts map[string]string) sourceKind {
	switch {
	case s == "*":
		return srcWildcard
	// A tag spelled in other case is treated as the guest tag it may stand for.
	case IsGuestTag(strings.ToLower(s)), s == "autogroup:tagged", s == "autogroup:danger-all":
		return srcGuests
	case slices.Contains(noGuestAutogroups, s):
		return srcNoGuests
	case strings.HasPrefix(s, "autogroup:"):
		return srcUnknown
	case strings.HasPrefix(s, "tag:"), strings.HasPrefix(s, "group:"):
		return srcNoGuests
	case strings.Contains(s, "@"):
		return srcNoGuests // a user
	}
	if target, ok := hosts[s]; ok {
		s = target
	}
	// Even one tailnet address may be a guest's: Tailscale can hand a deleted device's
	// address to a new one.
	if a, err := netip.ParseAddr(s); err == nil {
		s = netip.PrefixFrom(a, a.BitLen()).String()
	}
	if p, err := netip.ParsePrefix(s); err == nil {
		for _, r := range tailnetRanges {
			if p.Overlaps(r) {
				return srcGuests
			}
		}
		return srcNoGuests
	}
	return srcUnknown
}

// coversGuests reports whether a destination host may be a guest device: anything this
// planner can't prove excludes them, such as an IP set, an address range or a bracketed
// IPv6 address.
func coversGuests(host string, hosts map[string]string) bool {
	return classifySource(host, hosts) != srcNoGuests
}

// isAllowAll reports the rule a new tailnet starts with: "*" reaching "*" on every port.
func isAllowAll(r rule) bool {
	if len(r.src) != 1 || r.src[0].value != "*" || len(r.dst) != 1 {
		return false
	}
	if r.section == "acls" {
		return r.dst[0] == "*:*"
	}
	return r.dst[0] == "*" && (r.ip == nil || slices.Equal(r.ip, []string{"*"}))
}

// narrowing is a rule with a "*" source. The allow-all rule becomes autogroup:member
// reaching autogroup:member; any other is refused, and the suggestion shows how to
// narrow it by hand.
type narrowing struct {
	r        rule
	allowAll bool
	blocked  bool // narrowing would cut off something the owner has
}

// planAdditions adds what is missing.
func (d *doc) planAdditions(devices []Device, cfg Config) ([]byte, Report, error) {
	var report Report
	var unsafe []string

	var missingTags []string
	for _, slug := range cfg.Apps {
		for _, tag := range []string{AppTag(slug), GuestTag(slug)} {
			owners, ok := d.owners[tag]
			if !ok {
				missingTags = append(missingTags, tag)
				continue
			}
			if !slices.Contains(owners, cfg.TagOwner) && adminOnly(owners, cfg) {
				report.Notes = append(report.Notes, fmt.Sprintf("%s is already owned by %s; left as is. The account behind the connector's API credential must be allowed to apply it.", tag, strings.Join(owners, ", ")))
			}
		}
	}

	// With no app shared, no guest may be on the tailnet, and a plan that removes apps
	// only takes from guests: what could favor them is a note, not a reason to refuse.
	hazards := d.hazards(cfg)
	if len(cfg.Apps) == 0 {
		report.Notes = append(report.Notes, hazards...)
		hazards = nil
	}

	guestRules := map[string]bool{}
	var narrows []narrowing
	for _, r := range d.rules {
		src, isGuestRule, err := d.guestSources(r, cfg)
		switch {
		case err != nil:
			unsafe = append(unsafe, err.Error())
			continue
		case isGuestRule:
			if slug, ok := guestRuleSlug(r, cfg); ok {
				guestRules[slug] = true
			}
			continue
		}
		switch unknown, guests := src[srcUnknown], src[srcGuests]; {
		case len(unknown) > 0:
			unsafe = append(unsafe, fmt.Sprintf("%s has the source %s, and Ovenlight can't tell whether that includes guest devices", r.label(), quoteAll(unknown)))
		case len(guests) > 0:
			unsafe = append(unsafe, fmt.Sprintf("%s lets %s reach %s; a guest device may reach only its own app's node, on port %d", r.label(), quoteAll(guests), r.dstSummary(), cfg.Port))
		case len(src[srcWildcard]) > 0:
			n := narrowing{r: r, allowAll: isAllowAll(r)}
			if !n.allowAll {
				unsafe = append(unsafe, fmt.Sprintf("%s lets %s reach %s, guest devices included; Ovenlight narrows on its own only a rule that lets everything reach everything", r.label(), quoteList(r.srcValues()), r.dstSummary()))
			}
			narrows = append(narrows, n)
		}
	}

	unsafe = append(unsafe, narrowingBlockers(d, devices, narrows)...)
	if len(hazards)+len(unsafe) > 0 {
		return nil, report, &Refusal{Reasons: append(hazards, unsafe...), Suggestion: suggestion(d, devices, narrows, len(unsafe) > 0)}
	}

	var edits []edit
	narrowed := map[string]bool{} // rule labels
	for _, n := range narrows {
		s := n.r.src[0]
		edits = append(edits, edit{pos: s.start, del: s.end - s.start, text: strconv.Quote(member)})
		e, err := d.rewriteDst(n.r, []string{allPorts(n.r.section, member)})
		if err != nil {
			return nil, report, err
		}
		edits = append(edits, e)
		narrowed[n.r.label()] = true
		report.Changes = append(report.Changes, fmt.Sprintf("Narrow %s from everything reaching everything to %s reaching %s, so guest devices neither inherit it nor see your devices.", n.r.label(), member, member))
	}

	// The owner reaches every shared app node on both ports through their own owner rule.
	// A new owner gets a rule of their own.
	var newRules []item
	if len(cfg.Apps) > 0 {
		if at := d.ownerRuleAt(cfg); at < 0 {
			newRules = append(newRules, item{
				comment: fmt.Sprintf("// Ovenlight: you reach your shared app nodes, and their admin port %d (ovenlight setup-sharing).", cfg.AdminPort),
				text:    ownerRule(cfg),
			})
			report.Changes = append(report.Changes, fmt.Sprintf("Add one rule to grants: %s may reach %s on ports %d and %d, as a tagged node is no longer one of your own devices.", cfg.Owner, strings.Join(cfg.appTags(), ", "), cfg.Port, cfg.AdminPort))
		} else {
			r := d.rules[at]
			if dst, added := union(r, cfg.appTags(), cfg); len(added) > 0 {
				e, err := d.rewriteDst(r, dst)
				if err != nil {
					return nil, report, err
				}
				edits = append(edits, e)
				report.Changes = append(report.Changes, fmt.Sprintf("Let %s reach %s on ports %d and %d through %s.", cfg.Owner, strings.Join(added, " and "), cfg.Port, cfg.AdminPort, r.label()))
			}
		}
		for _, r := range d.rules {
			if isOwnerRule(r, cfg) && !isOwnRule(r, cfg) {
				report.Notes = append(report.Notes, fmt.Sprintf("%s lets %s reach app nodes on ports %d and %d, and Ovenlight leaves it as it is (the apps still let in only you and their guests). Remove it in the admin console if they no longer need that.", r.label(), r.src[0].value, cfg.Port, cfg.AdminPort))
			}
		}
	}

	for _, slug := range cfg.Apps {
		if guestRules[slug] {
			continue
		}
		newRules = append(newRules, item{
			comment: fmt.Sprintf("// Ovenlight: guests invited to %s reach only its node, on port %d.", slug, cfg.Port),
			text:    guestRule(slug, cfg),
		})
		report.Changes = append(report.Changes, fmt.Sprintf("Add one rule to grants: %s may reach only %s:%d.", GuestTag(slug), AppTag(slug), cfg.Port))
	}

	l := newLayout(d.src, d.root)
	var newMembers []item // root members to add, in order
	var ownerEntries []item
	for _, tag := range missingTags {
		ownerEntries = append(ownerEntries, item{text: tagOwnerEntry(tag, cfg)})
	}
	if len(missingTags) > 0 {
		if d.tagOwners == nil {
			newMembers = append(newMembers, l.tagOwnersMember(ownerEntries))
		} else {
			edits = append(edits, l.appendTo(d.tagOwners, ownerEntries)...)
		}
		report.Changes = append([]string{fmt.Sprintf("Add tagOwners for %s (owner %s).", strings.Join(missingTags, ", "), cfg.TagOwner)}, report.Changes...)
	}
	if len(newRules) > 0 {
		if sec := d.sections["grants"]; sec != nil {
			edits = append(edits, l.appendTo(sec, newRules)...)
		} else {
			newMembers = append(newMembers, l.sectionMember("grants", newRules))
		}
	}
	if len(newMembers) > 0 {
		edits = append(edits, l.insertRootMembers(d.root, newMembers)...)
	}

	for _, n := range narrows {
		if slices.ContainsFunc(d.nodeAttrs, nodeAttr.mullvad) {
			report.Notes = append(report.Notes, fmt.Sprintf("nodeAttrs gives devices Mullvad exit nodes, which the destination \"*\" of %s reached; narrowing it cuts them off. To keep them, add %q to its destination.", n.r.label(), allPorts(n.r.section, "autogroup:internet")))
		}
	}
	if len(narrows) > 0 {
		report.Notes = append(report.Notes, fmt.Sprintf("\"*\" also covered people you share devices with from their own tailnets (autogroup:shared); they aren't in %s. If you share devices that way, add their access back explicitly.", member))
	}
	var seeing []string
	for _, r := range d.rules {
		if _, ok := guestRuleSlug(r, cfg); ok || narrowed[r.label()] {
			continue
		}
		for _, dst := range r.dst {
			if host, _ := hostOf(r.section, dst); coversGuests(host, d.hosts) {
				seeing = append(seeing, r.label())
				break
			}
		}
	}
	report.Exposed = seeing
	if len(seeing) > 0 {
		verb, its := "lets its source", "its destination"
		if len(seeing) > 1 {
			verb, its = "let their sources", "their destinations"
		}
		report.Notes = append(report.Notes, fmt.Sprintf("%s %s reach guest devices, so guests see those devices' names and addresses as peers (they still can't connect to them). Narrow %s if that matters to you.", strings.Join(seeing, " and "), verb, its))
	}
	for _, s := range d.sshSources {
		if IsGuestTag(strings.ToLower(s)) || s == "autogroup:tagged" {
			report.Notes = append(report.Notes, fmt.Sprintf("An ssh rule lists %q as a source; guest devices have no business with SSH, so consider removing it.", s))
		}
	}

	if len(edits) == 0 {
		return d.src, report, nil
	}
	return applyEdits(d.src, edits), report, nil
}

// adminOnly reports whether only admins may apply a tag with these owners. An empty list
// leaves it to admins alone.
func adminOnly(owners []string, cfg Config) bool {
	for _, o := range owners {
		if o != cfg.TagOwner && o != "autogroup:admin" && o != "autogroup:owner" {
			return false
		}
	}
	return true
}

func (n nodeAttr) mullvad() bool {
	return slices.ContainsFunc(n.gives, func(a string) bool { return strings.EqualFold(a, "mullvad") })
}

// hazards lists what, outside the access rules, could hand a guest device more than its
// app: Ovenlight tags someone other than an admin may apply, node attributes that may
// target guest devices, and route approvals guest devices may get.
func (d *doc) hazards(cfg Config) []string {
	var out []string
	var tags []string
	for tag := range d.owners {
		if isOvenlightTag(tag) {
			tags = append(tags, tag)
		}
	}
	sort.Strings(tags)
	for _, tag := range tags {
		if owners := d.owners[tag]; !adminOnly(owners, cfg) {
			role := "a guest of that app"
			if IsAppTag(tag) {
				role = "that app's node and receive its guests"
			}
			fix := fmt.Sprintf("set its owners to [%q]", cfg.TagOwner)
			if s := d.spelled[tag]; len(s) > 0 {
				// Tailscale reads tag names as lowercase.
				fix = fmt.Sprintf("tagOwners also lists it as %s; list it once, in lowercase, with the owners [%q]", quoteAll(s), cfg.TagOwner)
			}
			out = append(out, fmt.Sprintf("tagOwners lets %s apply %s, so they could tag a device as %s; %s", quoteAll(owners), tag, role, fix))
		}
	}
	// A device may take on any tag its own tags own, and a guest holds its key.
	var others []string
	for tag := range d.owners {
		if !isOvenlightTag(tag) {
			others = append(others, tag)
		}
	}
	sort.Strings(others)
	for _, tag := range others {
		if guests := mayBeGuests(d.owners[tag], d.hosts); len(guests) > 0 {
			out = append(out, fmt.Sprintf("tagOwners lets %s apply %s, and a device can take on the tags its own tags own, so a guest device could become %s; remove %s from its owners", quoteAll(guests), tag, tag, quoteAll(guests)))
		}
	}
	for _, n := range d.nodeAttrs {
		if guests := mayBeGuests(n.connectors, d.hosts); len(guests) > 0 {
			out = append(out, fmt.Sprintf("%s runs App Connectors on %s, which may include guest devices, so a guest device could carry your traffic to those domains; list only the tags of your own connectors there", n.label(), quoteAll(guests)))
		}
		guests := mayBeGuests(n.target, d.hosts)
		var risky []string
		for _, g := range n.gives {
			if !clientOnly(g) {
				risky = append(risky, g)
			}
		}
		if len(guests) > 0 && len(risky) > 0 {
			out = append(out, fmt.Sprintf("%s gives %s to %s, which may include guest devices; change its target to autogroup:member, or the users, groups and tags that need it", n.label(), quoteList(risky), quoteAll(guests)))
		}
	}
	for _, a := range d.approvers {
		if guests := mayBeGuests(a.approvers, d.hosts); len(guests) > 0 {
			out = append(out, fmt.Sprintf("%s lists %s, which may include guest devices, so a guest device could have what it advertises (routes, an exit node) approved automatically and carry your traffic; list only the users, groups or tags of your own devices there", a.label, quoteAll(guests)))
		}
	}
	return out
}

// guestSources sorts a rule's sources by whether they may be guest devices. It returns
// an error for an action this planner doesn't know, and isGuestRule, with no sources, for
// the rule letting an app's guests reach only its own node.
func (d *doc) guestSources(r rule, cfg Config) (src map[sourceKind][]string, isGuestRule bool, err error) {
	if r.section == "acls" && r.action != "" && !strings.EqualFold(r.action, "accept") {
		return nil, false, fmt.Errorf("%s has action %q, which this planner doesn't know", r.label(), r.action)
	}
	if _, ok := guestRuleSlug(r, cfg); ok {
		return nil, true, nil
	}
	src = map[sourceKind][]string{}
	for _, s := range r.src {
		kind := classifySource(s.value, d.hosts)
		src[kind] = append(src[kind], s.value)
	}
	return src, false, nil
}

// mayBeGuests are the entries that may stand for a guest device.
func mayBeGuests(entries []string, hosts map[string]string) []string {
	var out []string
	for _, e := range entries {
		if classifySource(e, hosts) != srcNoGuests {
			out = append(out, e)
		}
	}
	return out
}

// CheckRestore refuses to restore a backup as it is when it would let guest devices
// reach more than their own app's node on the shared port, or get tags, node attributes
// or route approvals, while guests may be on the tailnet: a device carries a guest tag,
// or an unexpired auth key does (keyTags are the tags of those keys: an open invite).
func CheckRestore(backup []byte, devices []Device, keyTags []string) error {
	d, err := parseDoc(backup)
	if err != nil {
		return err
	}
	cfg := Config{}.withDefaults()
	guests := slices.ContainsFunc(keyTags, IsGuestTag)
	for _, dev := range devices {
		guests = guests || slices.ContainsFunc(dev.Tags, IsGuestTag)
	}
	if !guests {
		return nil
	}
	reasons := d.hazards(cfg)
	for _, r := range d.rules {
		src, _, err := d.guestSources(r, cfg)
		if err != nil {
			reasons = append(reasons, err.Error())
			continue
		}
		if may := slices.Concat(src[srcWildcard], src[srcGuests], src[srcUnknown]); len(may) > 0 {
			reasons = append(reasons, fmt.Sprintf("%s lets %s reach %s, guest devices included", r.label(), quoteAll(may), r.dstSummary()))
		}
	}
	if len(reasons) == 0 {
		return nil
	}
	return &Refusal{Reasons: reasons, Suggestion: "  Guest devices may reach only their own app's node, on port " + strconv.Itoa(cfg.Port) + ".\n  Revoke the guests (`ovenlight revoke <name>`) and cancel their open invites\n  (`ovenlight share --cancel <id>`; `ovenlight guests` lists both). Guest devices that\n  never claimed an invite can't be revoked: delete them in the Tailscale admin console\n  (Machines). Then run it again,\n  or edit the backup's rules like that and restore it by hand in the admin console (Access controls)."}
}

// union appends to an owner rule's destinations the tags it lacks, and returns the list
// and the tags added.
func union(r rule, tags []string, cfg Config) ([]string, []string) {
	out := slices.Clone(r.dst)
	var added []string
	for _, tag := range tags {
		if !slices.ContainsFunc(r.dst, func(d string) bool { host, _ := hostOf(r.section, d); return EqualFoldASCII(host, tag) }) {
			out = append(out, ownerDst(r.section, tag, cfg))
			added = append(added, tag)
		}
	}
	return out, added
}

// otherTags are the tags on devices, and used as rule sources, that aren't Ovenlight's.
func otherTags(d *doc, devices []Device) (tagged []string, tagRules []string, tags []string) {
	set := map[string]bool{}
	for _, dev := range devices {
		own := false
		for _, tag := range dev.Tags {
			if !isOvenlightTag(tag) {
				set[tag] = true
				own = true
			}
		}
		if own {
			tagged = append(tagged, dev.Name)
		}
	}
	for _, r := range d.rules {
		for _, s := range r.src {
			if strings.HasPrefix(s.value, "tag:") && !isOvenlightTag(s.value) {
				tagRules = append(tagRules, r.label())
				set[s.value] = true
				break
			}
		}
	}
	sort.Strings(tagged)
	for t := range set {
		tags = append(tags, t)
	}
	sort.Strings(tags)
	return tagged, tagRules, tags
}

// routes lists the devices' subnet routes and exit nodes, which a destination of "*"
// covers and autogroup:member doesn't.
func routes(devices []Device) (subnets []string, routers []string, exitNodes []string) {
	for _, dev := range devices {
		isRouter, isExit := false, false
		for _, r := range dev.Routes {
			if r == "0.0.0.0/0" || r == "::/0" {
				isExit = true
			} else {
				isRouter = true
				if !slices.Contains(subnets, r) {
					subnets = append(subnets, r)
				}
			}
		}
		if isRouter {
			routers = append(routers, dev.Name)
		}
		if isExit {
			exitNodes = append(exitNodes, dev.Name)
		}
	}
	sort.Strings(subnets)
	sort.Strings(routers)
	sort.Strings(exitNodes)
	return subnets, routers, exitNodes
}

// narrowingBlockers lists what narrowing the allow-all rule would cut off, as refusal
// reasons: tagged devices, rules with tag sources, subnet routes and exit nodes.
func narrowingBlockers(d *doc, devices []Device, narrows []narrowing) []string {
	tagged, tagRules, _ := otherTags(d, devices)
	_, routers, exitNodes := routes(devices)
	var cut []string
	if len(tagged) > 0 {
		cut = append(cut, fmt.Sprintf("tagged devices (%s)", strings.Join(tagged, ", ")))
	}
	if len(tagRules) > 0 {
		cut = append(cut, fmt.Sprintf("rules that use tags as a source (%s)", strings.Join(tagRules, ", ")))
	}
	if len(routers) > 0 {
		cut = append(cut, fmt.Sprintf("subnet routes (advertised by %s)", strings.Join(routers, ", ")))
	}
	if len(exitNodes) > 0 {
		cut = append(cut, fmt.Sprintf("exit nodes (%s)", strings.Join(exitNodes, ", ")))
	}
	var reasons []string
	for i, n := range narrows {
		if !n.allowAll || len(cut) == 0 {
			continue
		}
		narrows[i].blocked = true
		reasons = append(reasons, fmt.Sprintf("%s lets %s reach %s, guest devices included; narrowing it to %s reaching %s would also cut off %s",
			n.r.label(), quoteList(n.r.srcValues()), n.r.dstSummary(), member, member, strings.Join(cut, " and ")))
	}
	return reasons
}

// suggestion is the manual edit that lets Plan proceed after a refusal.
// rules says whether any reason is about an access rule.
func suggestion(d *doc, devices []Device, narrows []narrowing, rules bool) string {
	var b strings.Builder
	_, _, tags := otherTags(d, devices)
	subnets, _, exitNodes := routes(devices)
	for _, n := range narrows {
		if n.allowAll && !n.blocked {
			continue
		}
		src := append([]string{member}, tags...)
		fmt.Fprintf(&b, "  In the admin console (Access controls), change %s:\n    source: replace \"*\" with %s\n", n.r.label(), quoteList(src))
		if n.allowAll {
			dst := slices.Concat([]string{member}, tags, subnets)
			if len(exitNodes) > 0 {
				dst = append(dst, "autogroup:internet")
			}
			for i := range dst {
				dst[i] = allPorts(n.r.section, dst[i])
			}
			fmt.Fprintf(&b, "    destination: %s\n", quoteList(dst))
		}
		b.WriteString("  so your own devices, tagged devices and routes keep their access and guest devices get none.\n")
	}
	if rules {
		b.WriteString("  If a rule above lets guest devices or unknown sources reach more, remove or narrow it.\n")
	} else {
		b.WriteString("  Make the changes above in the admin console (Access controls).\n")
	}
	b.WriteString("  Then run the same ovenlight command again.")
	return b.String()
}
func quoteAll(ss []string) string {
	q := make([]string, len(ss))
	for i, s := range ss {
		q[i] = strconv.Quote(s)
	}
	return strings.Join(q, ", ")
}

func quoteList(ss []string) string { return "[" + quoteAll(ss) + "]" }

// ---------- new policy text ----------

func tagOwnerEntry(tag string, cfg Config) string {
	return fmt.Sprintf("%s: [%s]", strconv.Quote(tag), strconv.Quote(cfg.TagOwner))
}

func guestRule(slug string, cfg Config) string {
	return fmt.Sprintf(`{"src": [%q], "dst": [%q], "ip": ["tcp:%d"]}`, GuestTag(slug), AppTag(slug), cfg.Port)
}

func ownerRule(cfg Config) string {
	return fmt.Sprintf(`{"src": [%q], "dst": %s, "ip": ["tcp:%d", "tcp:%d"]}`, cfg.Owner, quoteList(cfg.appTags()), cfg.Port, cfg.AdminPort)
}

// EqualFoldASCII compares ignoring the case of A to Z only. strings.EqualFold also
// matches Unicode look-alikes (the Kelvin sign for k), which a login or host must not.
func EqualFoldASCII(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range len(a) {
		x, y := a[i], b[i]
		if 'A' <= x && x <= 'Z' {
			x += 'a' - 'A'
		}
		if 'A' <= y && y <= 'Z' {
			y += 'a' - 'A'
		}
		if x != y {
			return false
		}
	}
	return true
}
