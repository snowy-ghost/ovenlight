// Command ovenlight publishes local web apps to your own tailnet, one tailnet node per
// app, so Ovenlight on your iPhone can open them over HTTPS with verified identity headers,
// and shares single apps with friends as guests.
package main

import (
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"slices"
	"strings"
	"sync"
	"text/tabwriter"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

const usage = `ovenlight publishes your local web apps to your tailnet, for the Ovenlight iPhone app.

Usage:
  ovenlight run                                   run the connector (its background service does this)
  ovenlight new "<App Name>" [--dir <parent>] [--port <n>]   a starter app to build on, and how to publish it
  ovenlight publish --port <n> --name "<App>" [--slug <slug>] [--shareable]
  ovenlight publish --slug <slug> --shareable     make a published app shareable
  ovenlight publish ... --run '<command>' [--dir <path>]   keep the app running (--run '' stops)
  ovenlight logs <slug> [-n <lines>] [-f]         output of an app published with --run
  ovenlight restart <slug>                        stop an app published with --run and start it again
  ovenlight unpublish <slug>
  ovenlight status [--json]
  ovenlight doctor [--json]
  ovenlight check <slug> [--json]                 how one app will look and work in Ovenlight
  ovenlight version
  ovenlight guide                                 how to build apps for Ovenlight, for coding agents

Sharing (one-time setup: auth set, then publish --slug <slug> --shareable, which records you as the owner):
  ovenlight auth set [--oauth-client-id <id>]     reads the secret from stdin
  ovenlight auth status [--check]
  ovenlight auth remove
  ovenlight publish --slug <slug> --shareable [--owner <login>] [--owner-label <name>]
  ovenlight setup-sharing --owner <login> [--owner-label <name>]   change the recorded owner
  ovenlight setup-sharing --rollback              restore the policy saved before the last change
  ovenlight setup-sharing --remove <slug>         take an unshared app's tags out of the policy
  ovenlight share <slug> --to "<Name>"            invite link and QR code
  ovenlight share <slug> --to "<Name>" --existing  another app or device for someone you share with
  ovenlight share <slug> --person <ID>            the same, by person ID (see guests)
  ovenlight share <slug> --to "<Name>" --review   invite for Apple's App Review (90 days)
  ovenlight share --cancel <id>                   withdraw an unused invite
  ovenlight guests [--all] [--sync] [--json]
  ovenlight revoke <name | device ID | invite ID> [--app <slug>]
  ovenlight revoke --person <ID> [--app <slug>]   every device and open invite of that person
  ovenlight feedback [--app <slug>] [-n <count>] [--json]
  ovenlight mcp                                   MCP server on stdio, for coding agents

Every command also takes --config <file> and --state <dir>.
`

// startFix says how to start the connector, a fix for the person: the installed one runs
// as a background service of theirs. An agent's `ovenlight run` would log in to the
// tailnet from the agent's shell and stop with it. A connector for other paths than the
// default ones isn't the one the service starts.
func startFix(p *paths) string {
	if !p.installed() {
		return fmt.Sprintf("No connector is running for --config %s and --state %s. The connector the person installed uses the default paths, "+
			"so nothing for these paths is served until the person runs a connector with them; an agent must not start one itself", p.config, p.state)
	}
	return "Start it by running its install script again (install.sh, or install.ps1 on Windows); if it starts but keeps stopping, " +
		"the reason is at the end of " + defaultLogPath()
}

type paths struct {
	config string
	state  string
}

// installed reports whether these are the installed connector's paths, the default ones.
func (p *paths) installed() bool {
	return filepath.Clean(p.config) == filepath.Clean(defaultConfigPath()) && filepath.Clean(p.state) == filepath.Clean(defaultStateDir())
}

func newFlags(name string) (*flag.FlagSet, *paths) {
	fs := flag.NewFlagSet(name, flag.ExitOnError)
	p := &paths{}
	fs.StringVar(&p.config, "config", defaultConfigPath(), "config file")
	fs.StringVar(&p.state, "state", defaultStateDir(), "state directory (nodes, control socket)")
	fs.Usage = func() {
		fmt.Fprintf(fs.Output(), "Usage:\n%s\nFlags:\n", commandUsage(name))
		fs.PrintDefaults()
	}
	return fs, p
}

// commandUsage is the lines of usage for one command, such as "share" or "auth set".
func commandUsage(name string) string {
	var b strings.Builder
	for _, line := range strings.Split(usage, "\n") {
		rest, ok := strings.CutPrefix(strings.TrimSpace(line), "ovenlight "+name)
		if ok && (rest == "" || rest[0] == ' ') {
			b.WriteString(line + "\n")
		}
	}
	return b.String()
}

func main() {
	log.SetFlags(log.LstdFlags)
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	commands := map[string]func([]string) error{
		"run": cmdRun, "publish": cmdPublish, "unpublish": cmdUnpublish, "status": cmdStatus, "doctor": cmdDoctor, "check": cmdCheck,
		"auth": cmdAuth, "setup-sharing": cmdSetupSharing, "share": cmdShare, "guests": cmdGuests,
		"revoke": cmdRevoke, "feedback": cmdFeedback, "mcp": cmdMCP, "logs": cmdLogs,
		"new": cmdNew, "guide": cmdGuide, "restart": cmdRestart,
		"version": func([]string) error { fmt.Println(version()); return nil },
	}
	cmd, args := os.Args[1], os.Args[2:]
	if cmd == env0Command {
		printEnv0()
		return
	}
	if cmd == "help" || cmd == "-h" || cmd == "--help" {
		fmt.Print(usage)
		return
	}
	run, ok := commands[cmd]
	if !ok {
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n%s", cmd, usage)
		os.Exit(2)
	}
	if err := run(args); err != nil {
		fmt.Fprintln(os.Stderr, "ovenlight:", err)
		os.Exit(1)
	}
}

// versionHeader carries version() on the admin API's answers and whoami.
const versionHeader = "Ovenlight-Connector-Version"

// releaseVersion is a release build's version, such as "1.0.0", set by
// scripts/release-connector.sh with -ldflags -X.
var releaseVersion string

// version names this build: its releaseVersion, or else from what the go command stamped
// into it, the module version or the commit and whether the tree had changes, as in
// "devel-9cd108a0f732-modified".
var version = sync.OnceValue(func() string {
	if releaseVersion != "" {
		return releaseVersion
	}
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	if v := info.Main.Version; v != "" && v != "(devel)" {
		return v // a release or pseudo-version, which names its commit
	}
	v := "devel"
	for _, s := range info.Settings {
		switch {
		case s.Key == "vcs.revision":
			v += "-" + s.Value[:min(12, len(s.Value))]
		case s.Key == "vcs.modified" && s.Value == "true":
			v += "-modified"
		}
	}
	return v
})

// maxLog is where the connector's own log moves to <log>.1.
const maxLog = 20 << 20

func cmdRun(args []string) error {
	fs, p := newFlags("run")
	logFile := fs.String("log", "", "write the log to this file, moved to <file>.1 at 20 MB, instead of standard error")
	controlURL := fs.String("control-url", "", "development only: control server URL (Headscale)")
	authKey := fs.String("auth-key", "", "development only: preauth key for every node")
	certFile := fs.String("dev-tls-cert", "", "development only: TLS certificate to serve instead of the ts.net one")
	keyFile := fs.String("dev-tls-key", "", "development only: TLS key for --dev-tls-cert")
	headscale := fs.String("dev-headscale", "", "development only: headscale binary, used instead of the Tailscale API")
	headscaleConfig := fs.String("dev-headscale-config", "", "development only: config file for --dev-headscale")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	if *logFile != "" {
		// Standard error, kept by the service manager, gets what bypasses the log, such as a panic.
		f := &restartingFile{path: *logFile, max: maxLog}
		if err := f.open(0); err != nil {
			return err
		}
		log.SetOutput(f)
		if runtime.GOOS == "windows" {
			// A scheduled task's standard error goes nowhere, so a crash is kept beside the log.
			crash, err := os.OpenFile(filepath.Join(filepath.Dir(*logFile), "ovenlight.stderr.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
			if err != nil {
				return err
			}
			err = debug.SetCrashOutput(crash, debug.CrashOptions{}) // keeps its own copy of the file
			crash.Close()
			if err != nil {
				return err
			}
		}
	}
	log.Printf("ovenlight %s", version())
	if os.Getenv("OVENLIGHT_DEV") != "1" {
		for _, name := range clearTSNetEnv() {
			log.Printf("ignoring %s from the environment (it takes OVENLIGHT_DEV=1)", name)
		}
	}

	var dev devOptions
	if *controlURL != "" || *authKey != "" || *certFile != "" || *headscale != "" {
		if os.Getenv("OVENLIGHT_DEV") != "1" {
			return errors.New("--control-url, --auth-key, --dev-tls-* and --dev-headscale are for development against a local control server; set OVENLIGHT_DEV=1 to use them")
		}
		dev.ControlURL, dev.AuthKey = *controlURL, *authKey
		dev.Headscale, dev.HeadscaleConfig = *headscale, *headscaleConfig
		if *certFile != "" {
			cert, err := tls.LoadX509KeyPair(*certFile, *keyFile)
			if err != nil {
				return err
			}
			dev.TLSCert = &cert
		}
	}
	err := runDaemon(p.config, p.state, dev)
	if err != nil && *logFile != "" {
		log.Printf("stopped: %v", err) // main prints it to standard error too
	}
	return err
}

// tsnetEnv are the variables tsnet reads when a Server field is empty: an auth key or
// OAuth client that would log every node in to someone's tailnet, another control
// server, a forced re-login, or another log server.
var tsnetEnv = []string{"TS_AUTHKEY", "TS_AUTH_KEY", "TS_CLIENT_SECRET", "TS_CLIENT_ID", "TS_ID_TOKEN", "TS_AUDIENCE",
	"TS_CONTROL_URL", "TSNET_FORCE_LOGIN", "TS_LOG_TARGET"}

// clearTSNetEnv unsets them and returns the ones that were set.
func clearTSNetEnv() []string {
	var cleared []string
	for _, name := range tsnetEnv {
		if _, ok := os.LookupEnv(name); ok {
			os.Unsetenv(name)
			cleared = append(cleared, name)
		}
	}
	return cleared
}

// publishResult is what publishing did, for the CLI and the MCP tool.
type publishResult struct {
	App       appInfo `json:"app"`
	Replaced  bool    `json:"replaced"`
	Problems  []Check `json:"problems,omitempty"`
	Daemon    bool    `json:"daemonRunning"`
	State     string  `json:"state,omitempty"`
	URL       string  `json:"url,omitempty"`
	LoginURL  string  `json:"loginURL,omitempty"`
	Shareable string  `json:"shareable,omitempty"`
	Note      string  `json:"note,omitempty"`

	node *NodeStatus // the node as last read, for the CLI
}

// runsTagged reports whether the connector says slug's node is running with its app tag,
// so publish --shareable has nothing to replace.
func runsTagged(p *paths, slug string) bool {
	reply, err := callDaemon(p.state, "status", 10*time.Second)
	if err != nil {
		return false
	}
	for _, st := range reply.Apps {
		if st.Slug == slug {
			// The connector's own test (appNode.isTagged): logged in, carrying the tag.
			return st.Backend == "Running" && slices.Contains(st.Tags, policy.AppTag(slug))
		}
	}
	return false
}

// portTakenError refuses to put an app on a port another app is published on. An agent
// renaming an app by publishing it under a new name would otherwise leave two apps on one
// port, each with its own copy of the command. running is set when both apps already
// share the port and each has a command, two copies the port can't both have.
type portTakenError struct {
	other   App
	running bool
}

func (e portTakenError) Error() string { return e.message(false) }

// message names the app to pass as --slug, or for MCP as slug.
func (e portTakenError) message(mcp bool) string {
	if e.running {
		return fmt.Sprintf("%s (%s) is on port %d too and the connector runs its command, so it would start two apps on one port. "+
			"Give this app a port of its own, or stop the other's command: ovenlight publish --slug %s --run ''", e.other.Name, e.other.Slug, e.other.Port, e.other.Slug)
	}
	arg := "--slug " + e.other.Slug
	if mcp {
		arg = fmt.Sprintf("slug %q", e.other.Slug)
	}
	return fmt.Sprintf("%s is already published on port %d, as %s. To rename or update it, publish with %s; another app needs a port of its own",
		e.other.Name, e.other.Port, e.other.Slug, arg)
}

// portTaken returns a portTakenError when an app other than app is published on its
// port, and app isn't on that port already: two apps an older config put on one port can
// still be updated (doctor warns of them), unless both would have a command.
func portTaken(cfg *Config, app App) error {
	if existing, ok := cfg.Find(app.Slug); ok && existing.Port == app.Port {
		for _, other := range cfg.Apps {
			if app.Run != "" && other.Port == app.Port && other.Slug != app.Slug && other.Run != "" {
				return portTakenError{other, true}
			}
		}
		return nil
	}
	for _, other := range cfg.Apps {
		if other.Port == app.Port && other.Slug != app.Slug {
			return portTakenError{other: other}
		}
	}
	return nil
}

// publishApp saves the app to the config and has the daemon serve it. Its command and
// directory are run and dir applied to the app as saved when the config is locked (see
// resolveRun; nil keeps what it has), so a change made meanwhile isn't written back.
// shareable makes it a tagged app node; an app that is already shareable stays shareable.
func publishApp(p *paths, app App, run, dir *string, shareable bool) (*publishResult, error) {
	app.Run, app.Dir = "", ""
	if err := app.Validate(); err != nil {
		return nil, err
	}
	res := &publishResult{}
	hadRun, changedRun := false, false
	err := UpdateConfig(p.config, func(cfg *Config) error {
		existing, ok := cfg.Find(app.Slug)
		var err error
		if app.Run, app.Dir, err = resolveRun(existing, run, dir); err != nil {
			return err
		}
		if err := portTaken(cfg, app); err != nil {
			return err
		}
		hadRun, changedRun = existing.Run != "", existing.Run != app.Run || existing.Dir != app.Dir
		if !ok {
			if err := checkRetired(p, app.Slug); err != nil {
				return err
			}
		}
		if ok && existing.Shareable {
			app.Shareable = true // a tagged node can't go back to belonging to you
			if existing.Port != app.Port && !fromTerminal() {
				return fmt.Errorf("%s is shareable, so its port can only be changed in a terminal: ovenlight publish --slug %s --port %d", existing.Name, app.Slug, app.Port)
			}
			// What runs decides what guests reach, as the port does.
			if (existing.Run != app.Run || existing.Dir != app.Dir) && !fromTerminal() {
				return fmt.Errorf("%s is shareable, so the command that runs it can only be changed in a terminal: ovenlight publish --slug %s --run '<command>'", existing.Name, app.Slug)
			}
		}
		if shareable {
			if cfg.Owner == "" {
				return fmt.Errorf("sharing isn't set up: run `ovenlight auth set`, then `ovenlight publish --slug %s --shareable` in a terminal", app.Slug)
			}
			app.Shareable = true
		}
		res.App, res.Replaced = app.view(), cfg.Upsert(app)
		return nil
	})
	if err != nil {
		return nil, err
	}
	// add adds a finding, when there is one.
	add := func(c *Check) {
		if c != nil {
			res.Problems = append(res.Problems, *c)
		}
	}
	check := func() {
		for _, c := range withPortChoice(appChecks(app, ""), app) {
			if c.Status != statusOK {
				res.Problems = append(res.Problems, c)
			}
		}
	}
	if c := protectedFolderCheck(app); c != nil {
		add(c)
	} else if app.Run != "" && changedRun {
		add(commandFoundCheck(app))
	}
	if app.Run != "" && changedRun {
		add(emptyFlagCheck(app))
	}
	add(temporaryFolderCheck(app))
	// Else the reload starts or stops the connector's copy first.
	if app.Run == "" && !hadRun {
		check()
	}
	began := time.Now()
	before, _ := listeners(app.Port) // what listens before the copy starts
	// A new app's node may first need a login key minted, which can take apiContext's 45 s.
	if _, err := callDaemon(p.state, "reload", 2*time.Minute); err != nil {
		if errors.Is(err, errDaemonDown) {
			if app.Run != "" {
				add(portHeldCheck(app, nil)) // the connector starts it once it runs
				add(portChoiceCheck(app))
			} else if hadRun {
				check()
			}
			return res, nil
		}
		return res, err
	}
	res.Daemon = true
	switch {
	case app.Run != "": // the reload has just started it
		if status, err := callDaemon(p.state, "status", 10*time.Second); err == nil && predatesRun(status) {
			res.Problems = append(res.Problems, predatesRunCheck(p, app))
			break
		}
		waitListening(p, app, before, began)
		status, err := callDaemon(p.state, "status", 10*time.Second)
		if err != nil {
			status = nil
		} else {
			add(portHeldCheck(app, status))
		}
		check()
		stillStarting(res.Problems, app, status, began)
	case hadRun: // the reload has just stopped the connector's copy
		check()
		for i, c := range res.Problems {
			if c.ID == "port-listening" && strings.HasPrefix(c.Message, "nothing is listening") {
				res.Problems[i].Status = statusWarn
				res.Problems[i].Message = fmt.Sprintf("the connector no longer runs %s's command, so nothing listens on 127.0.0.1:%d", app.Name, app.Port)
				res.Problems[i].Fix = fmt.Sprintf("Start %s, or have the connector run it again: ovenlight publish --slug %s --run '<command>'", app.Name, app.Slug)
			}
		}
	}
	if app.Shareable {
		reply, err := callDaemonWith(p.state, controlRequest{Cmd: "make-shareable", Slug: app.Slug}, 3*time.Minute)
		if err != nil {
			return res, fmt.Errorf("saved, but making the node shareable failed: %w", err)
		}
		res.Shareable = reply.Message
	}
	// A new node asks for a login within a few seconds; report the link right away.
	for i := 0; i < 20; i++ {
		reply, err := callDaemon(p.state, "status", 10*time.Second)
		if err != nil {
			return res, err
		}
		for _, st := range reply.Apps {
			if st.Slug != app.Slug {
				continue
			}
			res.State, res.URL, res.LoginURL, res.node = st.State, st.URL, st.LoginURL, &st
			if c := noLinkCheck(st, p); c != nil {
				res.Problems = append(res.Problems, *c)
				return res, nil
			}
			if (st.State == "needs-login" && (st.LoginURL != "" || st.Shareable)) || st.State == "serving" {
				return res, nil
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	return res, nil
}

// predatesRun reports whether the connector that sent a status reply is from before
// --run, and so runs no app's command: one that runs them reports its processes, if none.
func predatesRun(status *controlReply) bool { return status != nil && status.Processes == nil }

const predatesRunMessage = "the running connector predates --run and doesn't run commands"

// predatesRunCheck is the finding that the running connector doesn't start app, when it
// predates --run.
func predatesRunCheck(p *paths, app App) Check {
	return Check{ID: "connector-version", App: app.Slug, Status: statusFail, Actor: actorPerson,
		Message: predatesRunMessage + ", so nothing starts " + app.Name + "; restart it", Fix: restartFix(p)}
}

// predatesRunChecks adds predatesRunCheck to an app's checks, for check and doctor, when
// it has a command and the connector that sent status predates --run, and has a failing
// port-listening say why rather than point at the app's output.
func predatesRunChecks(checks []Check, p *paths, app App, status *controlReply) []Check {
	if app.Run == "" || !predatesRun(status) {
		return checks
	}
	for i, c := range checks {
		if c.ID == "port-listening" && c.Status != statusOK {
			checks[i].Fix = fmt.Sprintf("%s isn't running because the running connector doesn't run commands (see the connector-version finding).", app.Name)
			checks[i].Actor = actorPerson
		}
	}
	return append([]Check{predatesRunCheck(p, app)}, checks...)
}

// restartFix says how to restart the connector with this version, a fix for the person.
// install.sh restarts the installed one, which uses the default paths, not one for others.
func restartFix(p *paths) string {
	if !p.installed() {
		return fmt.Sprintf("The connector running for --config %s and --state %s isn't the installed one, so install.sh doesn't restart it: "+
			"whoever started it stops it and starts it again with this version of ovenlight; an agent must not start one itself.", p.config, p.state)
	}
	return "Run install.sh again (from the release folder, or connector/install.sh in the repository), which restarts the connector with this version."
}

// restartLoginFix restarts the connector so a node that lost its login asks for a new
// login link, which tsnet does only as it starts; a fix for the person.
func restartLoginFix(p *paths) string {
	if !p.installed() {
		return fmt.Sprintf("The connector running for --config %s and --state %s isn't the installed one: whoever started it stops it and starts it again; "+
			"an agent must not start one itself. Then ovenlight status shows the link", p.config, p.state)
	}
	return "Restart the connector by running its install script again (install.sh, or install.ps1 on Windows); then ovenlight status shows the link"
}

// checkRetired refuses to publish a slug the config doesn't list while sharing.json still
// has guests or open invites for it: the connector retires those when it loads a config
// without the slug (see retireUnpublished), and with the slug back it never would.
// Without a config file nothing was unpublished, and the connector retires nothing.
func checkRetired(p *paths, slug string) error {
	if _, err := os.Stat(p.config); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	st, err := loadSharingState(sharingPath(p.state))
	if err != nil {
		return err
	}
	if slices.ContainsFunc(st.Guests, func(g Guest) bool { return g.Active() && g.App == slug }) ||
		slices.ContainsFunc(st.Invites, func(inv Invite) bool { return inv.open() && inv.App == slug }) {
		return fmt.Errorf("the connector hasn't removed %s's earlier guests yet. Start it (or wait for it) and run this again", slug)
	}
	return nil
}

func cmdPublish(args []string) error {
	fs, p := newFlags("publish")
	port := fs.Int("port", 0, "local port the app listens on (127.0.0.1)")
	name := fs.String("name", "", "app name shown in Ovenlight")
	slug := fs.String("slug", "", "tailnet hostname (default: derived from the name)")
	shareable := fs.Bool("shareable", false, "run the app as a tagged node that guests can be invited to")
	ownerFlag := fs.String("owner", "", "with no owner recorded: your Tailscale login (default: the user who owns the app nodes)")
	labelFlag := fs.String("owner-label", "", `with no owner recorded: how guests see you, for example "Sam"`)
	runFlag := fs.String("run", "", `shell command that serves the app on $PORT, which the connector keeps running ('' stops that)`)
	dirFlag := fs.String("dir", "", "directory to run the command in (default: the one it runs in now, or for an app's first command the current one)")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	var run, dir *string // nil when not given, so republishing keeps the command
	fs.Visit(func(f *flag.Flag) {
		switch f.Name {
		case "run":
			run = runFlag
		case "dir":
			dir = dirFlag
		}
	})
	if *slug == "" {
		*slug = Slugify(*name)
	}
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return err
	}
	inherited := false
	existing, found := cfg.Find(*slug)
	if found {
		// Republishing an app keeps what isn't given: `publish --slug x --shareable`.
		if *port == 0 {
			*port, inherited = existing.Port, true
		}
		if strings.TrimSpace(*name) == "" {
			*name = existing.Name
		}
	} else if *port == 0 {
		msg := "--port is required for a new app; to change an existing one, pass its --slug"
		hint := cfg.publishedAs(*name)
		if hint == "" {
			hint = cfg.publishedAs(*slug)
		}
		if hint != "" {
			msg += " (" + hint + ")"
		}
		return errors.New(msg)
	}
	app := App{Name: strings.TrimSpace(*name), Slug: *slug, Port: *port}
	// For what the owner is shown; publishApp applies --run and --dir again, under the lock.
	if app.Run, app.Dir, err = resolveRun(existing, run, dir); err != nil {
		return err
	}
	if (*ownerFlag != "" || *labelFlag != "") && (!*shareable || cfg.Owner != "") {
		return errors.New("--owner and --owner-label record the first owner, with --shareable; `ovenlight setup-sharing --owner` changes a recorded one")
	}
	if found && existing.Shareable && existing.Port != app.Port && !*shareable && fromTerminal() {
		fmt.Printf("Guests you invite to %s will reach whatever listens on 127.0.0.1:%d%s.\n", app.Name, app.Port, startedWith(app))
	}
	if *shareable {
		if err := app.Validate(); err != nil {
			return err
		}
		if !found {
			if err := checkRetired(p, app.Slug); err != nil {
				return err // before the policy change, which would be for nothing
			}
		}
		if err := portTaken(cfg, app); err != nil {
			return err
		}
		// Guests will reach whatever listens on the port, and an agent may have set it.
		from := ""
		if inherited {
			from = " (the port it is published with now)"
		}
		fmt.Printf("Guests you invite to %s will reach whatever listens on 127.0.0.1:%d%s%s.\n", app.Name, app.Port, from, startedWith(app))
		// The policy has to define the app's tags before its node can carry one.
		err := changeAppPolicy(p, cfg, "publish --shareable", shareableSlugs(cfg, app.Slug), nil, *ownerFlag, *labelFlag)
		if errors.Is(err, errNotConfirmed) {
			return nil
		}
		if err != nil {
			return err
		}
		if !runsTagged(p, app.Slug) {
			fmt.Printf("\nMaking %s shareable: replacing its node with one tagged %s (same name) and logging it in, which can take up to %s.\n",
				app.Slug, policy.AppTag(app.Slug), humanDuration(tagWait))
		}
	}
	res, err := publishApp(p, app, run, dir, *shareable)
	if res != nil {
		verb := "Published"
		if res.Replaced {
			verb = "Updated"
		}
		fmt.Printf("%s %s as %q -> 127.0.0.1:%d\n", verb, res.App.Name, res.App.Slug, res.App.Port)
		if res.App.Run != "" && findID(res.Problems, "connector-version") == nil {
			fmt.Printf("The connector runs `%s` in %s and starts it again when it stops; its output: ovenlight logs %s\n", res.App.Run, res.App.Dir, res.App.Slug)
		}
		printChecks(res.Problems, true)
		if res.Shareable != "" {
			fmt.Println(res.Shareable)
		}
	}
	if err != nil {
		return err
	}
	switch {
	case !res.Daemon:
		fmt.Printf("\nThe connector isn't running, so nothing is served yet. %s.\n", startFix(p))
	case res.node != nil && noLinkCheck(*res.node, p) != nil: // a problem above
	case res.State == "needs-login" && res.App.Shareable:
		fmt.Printf("\nIts node needs to log in again as a shareable app node: %s\n", reshareCommand(res.App.Slug))
	case res.State == "needs-login" && res.LoginURL != "":
		fmt.Printf("\nLog this app's node in to your tailnet (one time):\n  %s\n", res.LoginURL)
	case res.State == "needs-approval":
		fmt.Printf("\nApprove this app's node, %s, in the Tailscale admin console: %s\n", res.App.Slug, consoleMachines)
	case res.State == "serving":
		fmt.Printf("\nServing at %s\n", res.URL)
	default:
		fmt.Println("\nThe node is still starting; run `ovenlight status` in a moment.")
	}
	// Without a credential the connector can't turn off a new node's key expiry, and
	// only doctor warns, a month before it runs out.
	if _, err := tsapi.LoadCredentials(credentialsPath(p.config)); err != nil && !res.Replaced && res.node != nil {
		switch {
		case res.node.KeyExpiry != nil:
			fmt.Println()
			printChecks([]Check{keyExpiryCheck(app.Slug, res.node.DNSName, *res.node.KeyExpiry)}, true)
		case res.LoginURL != "":
			fmt.Println()
			printChecks([]Check{{ID: "key-expiry", App: app.Slug, Status: statusWarn,
				Message: "once signed in, the node's key expires as your tailnet's key expiry says (180 days by default), and then it needs a browser sign-in",
				Fix:     keyExpiryFix(app.Slug)}}, true)
		}
	}
	if !checksOK(res.Problems) {
		return fmt.Errorf("%s is saved in the config, but it fails a check above", res.App.Name)
	}
	return nil
}

// startedWith says what the connector starts on the app's port, for the line that tells
// the owner what guests will reach.
func startedWith(app App) string {
	if app.Run == "" {
		return ""
	}
	return fmt.Sprintf(", which the connector starts with `%s` in %s", app.Run, app.Dir)
}

// unpublishApp takes the app out of the config and has the connector reload. err is
// set only when nothing changed; reloadErr is why the connector didn't confirm the
// reload (errDaemonDown when it isn't running), which leaves the app's guests to be
// removed when it next loads the config (see retireUnpublished).
func unpublishApp(p *paths, slug string) (reloadErr, err error) {
	err = UpdateConfig(p.config, func(cfg *Config) error {
		app, err := cfg.Published(slug)
		if err != nil {
			return err
		}
		// It removes the guests and deletes their devices, which only the owner decides.
		if app.Shareable && !fromTerminal() {
			return fmt.Errorf("%s is shareable, so it can only be unpublished in a terminal: ovenlight unpublish %s", app.Name, slug)
		}
		cfg.Remove(slug)
		return nil
	})
	if err != nil {
		return nil, err
	}
	_, reloadErr = callDaemon(p.state, "reload", 30*time.Second)
	return reloadErr, nil
}

func cmdUnpublish(args []string) error {
	fs, p := newFlags("unpublish")
	pos, err := parseArgs(fs, args, 1)
	if err != nil {
		return err
	}
	if len(pos) != 1 {
		return errors.New("usage: ovenlight unpublish <slug>")
	}
	slug := pos[0]
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return err
	}
	app, _ := cfg.Find(slug)
	reloadErr, err := unpublishApp(p, slug)
	if err != nil {
		return err
	}
	stuck := reloadErr != nil && !errors.Is(reloadErr, errDaemonDown)
	if !app.Shareable {
		if stuck {
			fmt.Printf("Unpublished %s. The connector didn't confirm it (%v); it stops serving the app once it does, or when it next starts.\n", slug, reloadErr)
			return nil
		}
		if !nodeLoggedIn(p.state, slug) {
			fmt.Printf("Unpublished %s.\n", slug)
			return nil
		}
		fmt.Printf("Unpublished %s. Its node stays in your tailnet, offline, so publishing it again needs no new login; remove it in the Tailscale admin console to forget it.\n", slug)
		return nil
	}
	later := fmt.Sprintf("Its tags and guest rule stay in your tailnet policy; `ovenlight setup-sharing --remove %s` takes them out later.", slug)
	guests := "Its guests are removed."
	switch {
	case stuck:
		// The policy change would wait on the connector too, so it's left for later.
		fmt.Printf("Unpublished %s. The connector didn't confirm it (%v); its guests are removed once it does, or when it next starts. %s\n%s\n",
			slug, reloadErr, forgetAppNode(p, slug), later)
		return nil
	case reloadErr != nil:
		guests = "Its guests are removed when the connector next starts."
	}
	fmt.Printf("Unpublished %s. %s %s\nIts tags and guest rule can go from your tailnet policy too.\n\n", slug, guests, forgetAppNode(p, slug))
	cfg.Remove(slug)
	// Stopping sharing never records an owner, so with none recorded it leaves this for later.
	if cfg.Owner == "" {
		fmt.Println(later)
		return nil
	}
	err = changeAppPolicy(p, cfg, "removing its tags", shareableSlugs(cfg), []string{slug}, "", "")
	if errors.Is(err, errNotConfirmed) {
		fmt.Println(later)
		return nil
	}
	return err
}

// forgetAppNode deletes an unpublished shareable app's node from the tailnet, and its
// state here, and says what became of it. Its guests and invites are retired already,
// and only this connector's nodes carry the app's tag (see oneConnector); left there,
// the node would keep publish --shareable from making another app shareable.
func forgetAppNode(p *paths, slug string) string {
	if err := deleteAppNodes(p.config, slug); err != nil {
		return fmt.Sprintf("Its node stays in your tailnet, offline, as Ovenlight couldn't delete it (%v): delete it in the Tailscale admin console (Machines), "+
			"since no other app can be made shareable while it's there.", err)
	}
	if err := os.RemoveAll(nodeDir(p.state, slug)); err != nil {
		return fmt.Sprintf("Its node is deleted from your tailnet, but not its state here (%v): delete %s before publishing it again.", err, nodeDir(p.state, slug))
	}
	return "Its node is deleted from your tailnet; publishing it again logs in a new one."
}

// deleteAppNodes deletes the devices tagged as the app's node, with the stored API credential.
func deleteAppNodes(configPath, slug string) error {
	creds, err := tsapi.LoadCredentials(credentialsPath(configPath))
	if err != nil {
		return err
	}
	client, err := tsapi.New(creds, nil)
	if err != nil {
		return err
	}
	ctx, cancel := apiContext()
	defer cancel()
	devices, err := client.Devices(ctx)
	if err != nil {
		return err
	}
	for _, d := range devices {
		if d.HasTag(policy.AppTag(slug)) {
			if err := client.DeleteDevice(ctx, d.NodeID); err != nil && !tsapi.IsNotFound(err) {
				return err
			}
		}
	}
	return nil
}

type upstreamStatus struct {
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

type appStatus struct {
	NodeStatus
	Upstream upstreamStatus `json:"upstream"`
	// Process is the app's own process, for an app published with --run, while the
	// connector runs it.
	Process *ProcessStatus `json:"process,omitempty"`
	// UnsupervisedPID leads the process group of the app's command that a connector that
	// didn't stop cleanly left running, while no connector runs.
	UnsupervisedPID int `json:"unsupervisedPid,omitempty"`
}

// sharingSummary is the sharing part of `status`.
type sharingSummary struct {
	Owner          string         `json:"owner,omitempty"`
	OwnerLabel     string         `json:"ownerLabel,omitempty"`
	Credential     string         `json:"credential,omitempty"` // type and fingerprint, never the secret
	Guests         int            `json:"guests"`
	Invites        int            `json:"pendingInvites"`
	ReviewInvites  int            `json:"reviewInvites"` // of the invites out, those for App Review
	Feedback       int            `json:"feedback"`
	LatestFeedback []feedbackView `json:"latestFeedback,omitempty"`
	SyncError      string         `json:"syncError,omitempty"`  // the running connector's last guest sync
	StateError     string         `json:"stateError,omitempty"` // sharing.json couldn't be read
}

type statusOut struct {
	Version       string         `json:"version"` // this binary's
	Daemon        bool           `json:"daemonRunning"`
	DaemonVersion string         `json:"daemonVersion,omitempty"`
	PredatesRun   bool           `json:"daemonPredatesRun,omitempty"` // the running connector doesn't run apps' commands
	PID           int            `json:"pid,omitempty"`
	Dev           bool           `json:"dev,omitempty"`
	LoginEnvError string         `json:"loginEnvError,omitempty"` // see controlReply.EnvError
	Apps          []appStatus    `json:"apps"`
	Sharing       sharingSummary `json:"sharing"`
}

func collectStatus(p *paths) (*statusOut, error) {
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return nil, err
	}
	reply, daemonErr := callDaemon(p.state, "status", 10*time.Second)
	if daemonErr != nil && !errors.Is(daemonErr, errDaemonDown) {
		return nil, daemonErr
	}
	out := &statusOut{Version: version(), Daemon: reply != nil, Apps: []appStatus{}}
	live := map[string]NodeStatus{}
	var processes map[string]ProcessStatus
	if reply != nil {
		processes = reply.Processes
		out.PID, out.Dev, out.DaemonVersion, out.LoginEnvError = reply.PID, reply.Dev, reply.Version, reply.EnvError
		out.PredatesRun = predatesRun(reply)
		out.Sharing.SyncError = reply.SyncError
		for _, st := range reply.Apps {
			live[st.Slug] = st
		}
	}
	for _, app := range cfg.Apps {
		st, ok := live[app.Slug]
		if !ok {
			st = NodeStatus{appInfo: app.view(), State: "not running"}
		}
		st.Run, st.Dir = app.Run, app.Dir // a connector from before --run doesn't report them
		up := upstreamCheck(withOwner(app, cfg, reply), st.DNSName)
		a := appStatus{NodeStatus: st, Upstream: upstreamStatus{OK: up.Status == statusOK, Message: up.Message}}
		if pr, ok := processes[app.Slug]; ok {
			a.Process = &pr
		} else if reply == nil && app.Run != "" {
			a.UnsupervisedPID = strayGroup(appLogDir(p.state), app.Slug)
		}
		out.Apps = append(out.Apps, a)
	}
	s := &out.Sharing
	s.Owner, s.OwnerLabel = cfg.Owner, cfg.OwnerLabel
	if creds, err := tsapi.LoadCredentials(credentialsPath(p.config)); err == nil {
		s.Credential = creds.Type + " " + creds.Fingerprint()
	}
	if st, err := loadSharingState(sharingPath(p.state)); err != nil {
		s.StateError = err.Error()
	} else {
		people := map[string]bool{}
		for _, g := range st.Guests {
			if g.Active() {
				people[g.Person] = true
			}
		}
		s.Guests = len(people)
		for _, inv := range st.Invites {
			if inv.State == inviteSent {
				s.Invites++
				if inv.Review {
					s.ReviewInvites++
				}
			}
		}
		s.Feedback = len(st.Feedback)
		for i := len(st.Feedback) - 1; i >= 0 && len(s.LatestFeedback) < 3; i-- {
			s.LatestFeedback = append(s.LatestFeedback, st.Feedback[i].view())
		}
	}
	return out, nil
}

// runSlugs are the slugs of the apps the connector runs.
func runSlugs(apps []appStatus) []string {
	var slugs []string
	for _, a := range apps {
		if a.Run != "" {
			slugs = append(slugs, a.Slug)
		}
	}
	return slugs
}

func cmdStatus(args []string) error {
	fs, p := newFlags("status")
	asJSON := fs.Bool("json", false, "machine-readable output")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	out, err := collectStatus(p)
	if err != nil {
		return err
	}
	if *asJSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(out)
	}
	if !out.Daemon {
		fmt.Printf("Connector: not running. %s.\n", startFix(p))
	} else {
		mode := ""
		if out.Dev {
			mode = " (DEV control server)"
		}
		fmt.Printf("Connector: running, pid %d, version %s%s\n", out.PID, orDash(out.DaemonVersion), mode)
		if out.PredatesRun && len(runSlugs(out.Apps)) > 0 {
			fmt.Printf("  The running connector predates --run and doesn't run commands; restart it.\n  Fix (the person): %s\n", restartFix(p))
		}
		processes := map[string]ProcessStatus{}
		for _, a := range out.Apps {
			if a.Process != nil {
				processes[a.Slug] = *a.Process
			}
		}
		if c, ok := envWarning(out.LoginEnvError, processes, runSlugs(out.Apps)); ok {
			who := ""
			if c.Actor == actorPerson {
				who = " (the person)"
			}
			fmt.Printf("  %s%s\n  Fix%s: %s\n", strings.ToUpper(c.Message[:1]), c.Message[1:], who, c.Fix)
		}
	}
	if len(out.Apps) == 0 {
		fmt.Println(`No apps published. Publish one with: ovenlight publish --port <n> --name "<App Name>"`)
	} else {
		w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
		fmt.Fprintln(w, "\nAPP\tSLUG\tPORT\tNODE\tSHARE\tURL\tOWNER\tUPSTREAM")
		for _, a := range out.Apps {
			upText := "ok"
			if !a.Upstream.OK {
				upText = a.Upstream.Message
			}
			share := "-"
			if a.Shareable {
				share = "yes"
			}
			fmt.Fprintf(w, "%s\t%s\t%d\t%s\t%s\t%s\t%s\t%s\n", a.Name, a.Slug, a.Port, a.State, share, orDash(a.URL), orDash(a.Owner), upText)
		}
		w.Flush()
		sep := "\n"
		for _, a := range out.Apps {
			if a.Run != "" {
				fmt.Printf("%s%s runs `%s`: %s (ovenlight logs %s)\n", sep, a.Name, a.Run, processLine(a), a.Slug)
				sep = ""
			}
		}
		for _, a := range out.Apps {
			switch {
			case a.NoServer:
				fmt.Printf("\nNO LOGIN LINK for %s: its node %s. Check this computer's network connection, and any VPN or firewall.\n", a.Name, unreachableText(a.LoginErr))
			case a.State == "needs-login" && a.Shareable:
				fmt.Printf("\nLOGIN NEEDED for %s: run `%s`\n", a.Name, reshareCommand(a.Slug))
			case a.Restart:
				fmt.Printf("\nLOGIN NEEDED for %s: its node lost its login.\n  Fix (the person): %s.\n", a.Name, restartLoginFix(p))
			case a.LoginErr != "":
				fmt.Printf("\nLOGIN FAILED for %s: %s\n", a.Name, a.LoginErr)
			case a.LoginURL != "":
				fmt.Printf("\nLOGIN NEEDED for %s: open this link and sign in to your tailnet\n  %s\n", a.Name, a.LoginURL)
			case a.State == "needs-approval":
				fmt.Printf("\nAPPROVAL NEEDED for %s: approve its node, %s, in the Tailscale admin console: %s\n", a.Name, a.Slug, consoleMachines)
			}
			if a.Error != "" && a.State != "needs-login" {
				fmt.Printf("\n%s: %s\n", a.Name, a.Error)
			}
		}
	}
	s := out.Sharing
	fmt.Println()
	if s.Owner == "" {
		fmt.Println("Sharing: not set up (ovenlight auth set, then ovenlight publish --slug <app> --shareable).")
	} else {
		fmt.Printf("Sharing: owner %s (%q), %d guests, %d invites out", s.Owner, s.OwnerLabel, s.Guests, s.Invites)
		if s.ReviewInvites > 0 {
			fmt.Printf(" (%d for App Review; revoke after the review)", s.ReviewInvites)
		}
		fmt.Println(".")
		if s.Credential == "" {
			fmt.Println("  No Tailscale API credential: invites and revoking need one (ovenlight auth set).")
		} else if s.SyncError != "" {
			fmt.Printf("  Not synced with the tailnet: %s\n", s.SyncError)
		}
	}
	if s.StateError != "" {
		fmt.Printf("  Can't read the guest records: %s\n", s.StateError)
	}
	fmt.Printf("Feedback: %d", s.Feedback)
	for i, f := range s.LatestFeedback {
		if i == 0 {
			fmt.Print(", latest:")
		}
		note := strings.ReplaceAll(f.Note, "\n", " ")
		if len([]rune(note)) > 60 {
			note = string([]rune(note)[:60]) + "..."
		}
		fmt.Printf("\n  %s %s (%s): %s", f.At.Local().Format("2 Jan 15:04"), f.From, f.App, note)
	}
	fmt.Println()
	return nil
}

func cmdDoctor(args []string) error {
	fs, p := newFlags("doctor")
	asJSON := fs.Bool("json", false, "machine-readable output")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return err
	}
	reportChecks(runDoctor(cfg, p.state, p.config), *asJSON)
	return nil
}

// printChecks prints findings; with onlyProblems it stays quiet about passing checks.
func printChecks(checks []Check, onlyProblems bool) {
	for _, c := range checks {
		if onlyProblems && c.Status == statusOK {
			continue
		}
		label := map[string]string{statusOK: "ok  ", statusWarn: "WARN", statusFail: "FAIL"}[c.Status]
		scope := c.ID
		if c.App != "" {
			scope = c.App + " " + c.ID
		}
		fmt.Printf("%s  %s: %s\n", label, scope, c.Message)
		switch {
		case c.Fix != "" && c.Actor == actorPerson:
			fmt.Printf("      fix (the person): %s\n", c.Fix)
		case c.Fix != "":
			fmt.Printf("      fix: %s\n", c.Fix)
		}
		if c.URL != "" && !strings.Contains(c.Fix, c.URL) {
			fmt.Printf("      at: %s\n", c.URL)
		}
	}
}
