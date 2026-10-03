package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
	"syscall"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"github.com/snowy-ghost/ovenlight/connector/internal/policy"
)

// daemon runs one node per published app, keeps the sharing records (invites, guests,
// feedback) and answers the CLI over a unix socket in the state directory.
type daemon struct {
	configPath string
	stateDir   string
	dev        devOptions
	sh         *sharing
	procs      *supervisors // the apps' own processes, for apps published with --run

	opMu  sync.Mutex // serializes reloads and node conversions
	tagMu sync.Mutex // serializes guest device tag changes; see retag

	// stop is closed when shutdown begins, so a conversion waiting on a login gives up.
	stopOnce sync.Once
	stop     chan struct{}

	mu       sync.Mutex
	nodes    map[string]*appNode
	cfg      Config
	loaded   bool // cfg was read from the config file, so apps missing from it are unpublished
	noConfig bool // the last reload found no config file (logged once)

	apiMu     sync.Mutex
	apiClient controlAPI
	apiFinger string
}

func socketPath(stateDir string) string { return filepath.Join(stateDir, "ovenlight.sock") }

// maxSocketPath is the longest path a unix socket can have: the address holds 108 bytes
// on Linux and 104 on macOS and the BSDs, the NUL that ends the path included.
func maxSocketPath() int {
	if runtime.GOOS == "linux" {
		return 107
	}
	return 103
}

func runDaemon(configPath, stateDir string, dev devOptions) error {
	if n := len(socketPath(stateDir)); n > maxSocketPath() {
		return fmt.Errorf("the state directory's path is too long for its control socket (%d bytes, at most %d); use a shorter --state", n, maxSocketPath())
	}
	// Signals are caught from the start, so a SIGHUP while starting doesn't kill it.
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGHUP, syscall.SIGINT, syscall.SIGTERM)
	if err := jsonfile.MkdirPrivate(stateDir); err != nil {
		return err
	}
	lock, err := lockStateDir(stateDir)
	if err != nil {
		return err
	}
	defer lock.Close()
	ln, err := listenControl(socketPath(stateDir))
	if err != nil {
		return err
	}
	sh, err := openSharing(stateDir)
	if err != nil {
		ln.Close()
		return err
	}
	d := &daemon{configPath: configPath, stateDir: stateDir, dev: dev, sh: sh, nodes: map[string]*appNode{},
		procs: newSupervisors(appLogDir(stateDir))}
	log.Printf("Ovenlight connector starting (config %s, state %s)", configPath, stateDir)
	if dev.ControlURL != "" {
		log.Printf("DEV MODE: control server %s", dev.ControlURL)
	}
	go d.serveControl(ln)
	go d.syncLoop()
	// Reloads run beside the signal loop, so SIGTERM never waits for one to finish
	// starting every node; opMu keeps them in order.
	reload := func(what string) {
		if err := d.reload(); err != nil {
			log.Printf("%s: %v", what, err)
		}
	}
	go reload("config")
	for sig := range signals {
		if sig == syscall.SIGHUP {
			go reload("reload")
			continue
		}
		log.Printf("%v: shutting down", sig)
		ln.Close()
		d.shutdown()
		return nil
	}
	return nil
}

// errLocked is lockFile's answer when another process holds the lock.
var errLocked = errors.New("locked by another process")

// lockStateDir takes the state directory for this daemon until the returned file is
// closed, or the process ends. A second daemon gets an error.
func lockStateDir(stateDir string) (*os.File, error) {
	lock, err := os.OpenFile(filepath.Join(stateDir, "ovenlight.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err := lockFile(lock, false); err != nil {
		lock.Close()
		if errors.Is(err, errLocked) {
			return nil, errors.New("another Ovenlight connector is already running")
		}
		return nil, err
	}
	return lock, nil
}

// shutdown closes every node and stops the apps' processes. It takes opMu first, as the
// other mutators do, so it waits for a reload or conversion that is still starting a node.
func (d *daemon) shutdown() {
	close(d.stopping())
	d.opMu.Lock()
	defer d.opMu.Unlock()
	var procs sync.WaitGroup
	procs.Go(d.procs.close) // beside the nodes, which can take seconds to close
	d.mu.Lock()
	for _, n := range d.nodes {
		n.close()
	}
	d.mu.Unlock()
	procs.Wait()
}

func (d *daemon) stopping() chan struct{} {
	d.stopOnce.Do(func() { d.stop = make(chan struct{}) })
	return d.stop
}

func (d *daemon) isStopping() bool {
	select {
	case <-d.stopping():
		return true
	default:
		return false
	}
}

// reload makes the running nodes and the apps' processes match the config: new apps get
// a node, removed apps lose theirs, and changed ports take effect without restarting the
// node (a supervised app's process restarts with the new PORT). The config is read under
// opMu, so reloads apply in order; nodes start and stop outside mu, so requests (and a
// revoke cutting off a guest) never wait on the network.
func (d *daemon) reload() error {
	d.opMu.Lock()
	defer d.opMu.Unlock()
	if d.isStopping() {
		return nil
	}
	cfg, found, err := loadConfigFile(d.configPath)
	if err != nil {
		return err
	}
	want := map[string]App{}
	for _, app := range cfg.Apps {
		want[app.Slug] = app
	}
	var gone []*appNode
	var added []App
	d.mu.Lock()
	// No config file says nothing about which apps were unpublished, so it retires none.
	logMissing := !found && !d.noConfig
	d.cfg, d.loaded, d.noConfig = *cfg, found, !found
	for slug, n := range d.nodes {
		if _, ok := want[slug]; !ok {
			gone = append(gone, n)
			delete(d.nodes, slug)
		}
	}
	for slug, app := range want {
		if n, ok := d.nodes[slug]; ok {
			n.setOwner(cfg.Owner)
			n.setApp(app)
			continue
		}
		added = append(added, app)
	}
	owner := d.appOwner(*cfg)
	d.mu.Unlock()
	if logMissing {
		log.Printf("no config at %s: serving no apps, and keeping every app's guests until there is one", d.configPath)
	}

	// Guests of apps no longer published lose them now, whether the app went in this
	// reload or while the connector was down; a sync then deletes their keys and retags
	// or deletes their devices, and retries what fails.
	if d.retireUnpublished() {
		go d.syncOnce()
	}
	for _, n := range gone {
		n.close()
		log.Printf("[%s] unpublished", n.App().Slug)
		go n.dropUnusedKey()
	}
	// The apps' own processes start before the new nodes, whose first login can take a while.
	d.procs.apply(cfg.Apps)
	for _, app := range added {
		if d.isStopping() {
			break // shutdown is waiting on opMu
		}
		n := newAppNode(app, d.stateDir, d.dev, d)
		n.setOwner(cfg.Owner)
		d.firstLoginKey(n, owner)
		if err := n.start(); err != nil {
			log.Printf("[%s] can't start its node: %v", app.Slug, err)
			n.dropUnusedKey()
			continue
		}
		d.mu.Lock()
		d.nodes[app.Slug] = n
		d.mu.Unlock()
		log.Printf("[%s] published %q -> 127.0.0.1:%d", app.Slug, app.Name, app.Port)
	}
	return nil
}

// firstLoginKey gives a node that never logged in a key for its first login, when the
// API credential can make one. A shareable node's key is tagged, so it never belongs to
// a user; an unshared node's is untagged and logs it in as owner (see appOwner). Without
// a key the node falls back to an interactive login, which for a shareable node still
// asks for the tag.
func (d *daemon) firstLoginKey(n *appNode, owner string) {
	app := n.App()
	if n.hasState() {
		return
	}
	if app.Shareable {
		if key, err := d.mintAppKey(app.Slug); err != nil {
			log.Printf("[%s] no tagged key for the first login (%v); it will ask for an interactive login instead", app.Slug, err)
		} else {
			n.authKey, n.keyID, n.keyMinted = key.Key, key.ID, time.Now()
		}
		return
	}
	if key, err := d.mintOwnerKey(app.Slug, owner); err != nil {
		log.Printf("[%s] no key for the first login (%v); it will ask for an interactive login instead", app.Slug, err)
	} else if key.Key != "" {
		n.authKey, n.keyID, n.keyMinted, n.untaggedKey = key.Key, key.ID, time.Now(), true
	}
}

// appOwner is who an untagged app node must belong to: the recorded owner, or else the
// one user behind the untagged app nodes already running. Call with d.mu held.
func (d *daemon) appOwner(cfg Config) string {
	if cfg.Owner != "" {
		return cfg.Owner
	}
	owner := ""
	for _, n := range d.nodes {
		n.mu.Lock()
		user := n.owner
		n.mu.Unlock()
		if user == "" {
			continue
		}
		if owner != "" && !policy.EqualFoldASCII(user, owner) {
			return ""
		}
		owner = user
	}
	return owner
}

// restartApp stops the app's process and starts it again, as published.
func (d *daemon) restartApp(slug string) error {
	d.opMu.Lock()
	defer d.opMu.Unlock()
	if d.isStopping() {
		return errors.New("the connector is shutting down")
	}
	cfg := d.config()
	app, err := cfg.Published(slug)
	switch {
	case err != nil:
		return err
	case app.Run == "":
		return fmt.Errorf("the connector doesn't run %s, so it has nothing to restart", app.Name)
	}
	d.procs.restart(app)
	return nil
}

func (d *daemon) snapshot() []*appNode {
	d.mu.Lock()
	defer d.mu.Unlock()
	nodes := make([]*appNode, 0, len(d.nodes))
	for _, n := range d.nodes {
		nodes = append(nodes, n)
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].App().Slug < nodes[j].App().Slug })
	return nodes
}

func (d *daemon) config() Config {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.cfg
}

func (d *daemon) node(slug string) *appNode {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.nodes[slug]
}

// Control protocol: the client sends one JSON request line and reads one JSON reply.

type controlRequest struct {
	Cmd      string `json:"cmd"` // see handleControl
	Slug     string `json:"slug,omitempty"`
	guestRef        // share: who the invite is for; revoke: Person, for --person
	ID       string `json:"id,omitempty"`
	App      string `json:"app,omitempty"`
	// Review asks share for a review invite (terminal only).
	Review bool `json:"review,omitempty"`
}

type controlReply struct {
	OK      bool              `json:"ok"`
	Error   string            `json:"error,omitempty"`
	PID     int               `json:"pid,omitempty"`
	Version string            `json:"version,omitempty"`
	Dev     bool              `json:"dev,omitempty"`
	Apps    []NodeStatus      `json:"apps,omitempty"`
	Certs   map[string]string `json:"certs,omitempty"` // slug -> error, "" when obtainable

	Share   *shareResult          `json:"share,omitempty"`
	Invite  *Invite               `json:"invite,omitempty"`
	Guests  *guestList            `json:"guests,omitempty"`
	Revoke  *revokeResult         `json:"revoke,omitempty"`
	Health  map[string][]peerInfo `json:"health,omitempty"`
	Message string                `json:"message,omitempty"`
	// SyncError is why the last guest sync with the tailnet failed, "" once one works.
	SyncError string `json:"syncError,omitempty"`
	// Processes are the supervised apps' processes, by slug, in every status reply: a
	// connector from before --run sends none (see predatesRun).
	Processes map[string]ProcessStatus `json:"processes"`
	// EnvError is why the login shell's environment can't be read for the apps' commands,
	// while it never has been, so they run with the connector's own.
	EnvError string `json:"envError,omitempty"`
}

// listenControl clears a stale socket and listens. The caller holds lockStateDir, so no
// other daemon is using it.
func listenControl(path string) (net.Listener, error) {
	os.Remove(path)
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		ln.Close()
		return nil, err
	}
	return ln, nil
}

func (d *daemon) serveControl(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		if err := checkPeer(conn); err != nil {
			log.Printf("control: refused a connection: %v", err)
			conn.Close()
			continue
		}
		go d.handleControl(conn)
	}
}

func (d *daemon) handleControl(conn net.Conn) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Minute)) // make-shareable waits for a login
	var req controlRequest
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&req); err != nil {
		return
	}
	reply := controlReply{OK: true, PID: os.Getpid(), Version: version(), Dev: d.dev.ControlURL != ""}
	switch req.Cmd {
	case "reload":
		if err := d.reload(); err != nil {
			reply = controlReply{Error: err.Error()}
		}
	case "restart":
		if err := d.restartApp(req.Slug); err != nil {
			reply = controlReply{Error: err.Error()}
		}
	case "status":
		d.sh.mu.Lock()
		reply.SyncError = d.sh.syncErr
		d.sh.mu.Unlock()
		reply.Processes = d.procs.status()
		reply.EnvError = d.procs.envError()
	case "share", "share-cancel", "guests", "revoke", "make-shareable", "health", "sync":
		reply = d.handleSharingControl(req, reply)
	case "certs":
		// At once, so that a few slow ones still answer within doctor's wait.
		reply.Certs = map[string]string{}
		var mu sync.Mutex
		var wg sync.WaitGroup
		for _, n := range d.snapshot() {
			wg.Go(func() {
				ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
				defer cancel()
				msg := ""
				if err := n.checkCertificate(ctx); err != nil {
					msg = err.Error()
				}
				mu.Lock()
				reply.Certs[n.App().Slug] = msg
				mu.Unlock()
			})
		}
		wg.Wait()
	default:
		reply = controlReply{Error: fmt.Sprintf("unknown command %q", req.Cmd)}
	}
	for _, n := range d.snapshot() {
		reply.Apps = append(reply.Apps, n.status())
	}
	json.NewEncoder(conn).Encode(reply)
}

// callDaemon sends one control request. It returns errDaemonDown when nothing listens.
func callDaemon(stateDir, cmd string, timeout time.Duration) (*controlReply, error) {
	return callDaemonWith(stateDir, controlRequest{Cmd: cmd}, timeout)
}

func callDaemonWith(stateDir string, req controlRequest, timeout time.Duration) (*controlReply, error) {
	conn, err := net.DialTimeout("unix", socketPath(stateDir), time.Second)
	if err != nil {
		return nil, errDaemonDown
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(timeout))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return nil, err
	}
	var reply controlReply
	if err := json.NewDecoder(conn).Decode(&reply); err != nil {
		return nil, fmt.Errorf("the connector didn't answer: %w", err)
	}
	if !reply.OK {
		return &reply, errors.New(reply.Error)
	}
	return &reply, nil
}

var errDaemonDown = errors.New("the Ovenlight connector isn't running")
