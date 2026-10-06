package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// The connector can keep an app's own process running, so the app outlives the terminal
// it was started in. The connector is a LaunchAgent, so the apps run while it does:
// whenever the person is logged in to this Mac, including after a restart once they log
// in. Each app with a Run command gets a supervisor: it starts the command in the app's
// directory with sh, in the environment the user's login shell sets up (see loginEnv)
// with PORT and HOST, keeps its output in a log of its own, and starts it again when it exits,
// waiting longer each time unless the run served. The process and everything it starts
// form one process group, which stopping ends as a whole. Each running group is recorded
// in <slug>.pid beside the log, so a connector restarted after a crash ends what the last
// one left running (see endOrphans).

// envMark comes before the login shell's environment in what loginEnv reads, after
// whatever the profile prints.
const envMark = "ovenlight-env\n"

// env0Command is the hidden command the login shell runs this binary with, in loginEnv,
// to print envMark and then its environment NUL-separated. A path in -c would need
// quoting each shell reads alike, and fish has no $0, so the path goes in exeVar.
const env0Command, exeVar = "__env0", "OVENLIGHT_EXE"

// printEnv0 is env0Command.
func printEnv0() { os.Stdout.WriteString(envMark + strings.Join(os.Environ(), "\x00")) }

// superviseTimings are the supervisor's waits; tests shorten them.
type superviseTimings struct {
	minBackoff time.Duration // before the first restart; doubles after each exit but a working run's
	maxBackoff time.Duration
	// A run is working when it was up stableAfter and was seen accepting connections on
	// its port, which it is asked every dialEvery until it is: then the wait starts over.
	// A command that builds for minutes and then fails doesn't count.
	stableAfter time.Duration
	dialEvery   time.Duration
	grace       time.Duration // between SIGTERM and SIGKILL when stopping
	// envRetry is how long a start again waits after the last read of the login shell's
	// environment before it reads it once more, while none has worked (see readEnvAgain).
	envRetry time.Duration
}

var defaultTimings = superviseTimings{minBackoff: time.Second, maxBackoff: time.Minute, stableAfter: time.Minute, dialEvery: 2 * time.Second, grace: 5 * time.Second,
	envRetry: 5 * time.Minute}

// maxAppLog is where an app's log moves to <slug>.log.1, so an app keeps at most twice it.
const maxAppLog = 5 << 20

// appLogDir holds the supervised apps' output: beside the connector's own log for the
// installed connector, in the state directory for any other.
func appLogDir(stateDir string) string {
	if runtime.GOOS == "darwin" && filepath.Clean(stateDir) == filepath.Clean(defaultStateDir()) {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, "Library", "Logs", "ovenlight")
	}
	return filepath.Join(stateDir, "logs")
}

func appLogPath(stateDir, slug string) string {
	return filepath.Join(appLogDir(stateDir), slug+".log")
}

// ProcessStatus is a supervised app's process, as status reports it.
type ProcessStatus struct {
	Running   bool      `json:"running"`
	PID       int       `json:"pid,omitempty"`
	StartedAt time.Time `json:"startedAt,omitzero"`
	Restarts  int       `json:"restarts"`
	// LastExit is how the last run ended, such as "exit status 1" or "signal: killed", or
	// why the command couldn't start.
	LastExit   string    `json:"lastExit,omitempty"`
	LastExitAt time.Time `json:"lastExitAt,omitzero"`
	// EnvError is why the running process has the connector's own environment: the login
	// shell's couldn't be read when it started. It keeps that one until it starts again.
	EnvError string `json:"envError,omitempty"`
}

// supervisors are the running apps' processes, by slug.
type supervisors struct {
	logDir   string
	timings  superviseTimings
	command  func(run string) *exec.Cmd // shellCommand
	startCmd func(*exec.Cmd) error      // (*exec.Cmd).Start
	probe    func(dir string)           // probeFolder; tests hold it up
	findEnv  func() ([]string, error)   // the login shell's environment, from the connector's

	orphans sync.Once // endOrphans, before the first apply starts anything

	mu        sync.Mutex
	procs     map[string]*supervisor
	env       []string  // the commands' environment, as findEnv last gave it; nil until it does
	envErr    string    // why findEnv failed, while it never has given one
	envRead   time.Time // when the last read ended
	rereading bool      // readEnvAgain is reading it
	closed    bool
}

func newSupervisors(logDir string) *supervisors {
	return &supervisors{logDir: logDir, timings: defaultTimings, command: shellCommand, startCmd: (*exec.Cmd).Start, probe: probeFolder,
		findEnv: func() ([]string, error) { return loginEnv(os.Environ()) }, procs: map[string]*supervisor{}}
}

// readEnv reads the login shell's environment again, for the commands started from now
// on, including those of the processes running now when they start again. When it can't
// be read, they keep the one read last, or run with the connector's own while none has
// been read, and the next apply or restart reads it again, as does each start of a
// command again while none has been (see readEnvAgain).
func (s *supervisors) readEnv() {
	env, err := s.findEnv()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.envRead = time.Now()
	switch {
	case err == nil:
		s.env, s.envErr = env, ""
	case s.env != nil:
		log.Printf("the apps' commands keep the environment of the login shell as it was read last, since it can't be read now: %v", err)
	default:
		s.envErr = err.Error()
		log.Printf("the apps' commands run with the connector's own environment, with PATH %s, since the login shell's can't be read: %v", os.Getenv("PATH"), err)
	}
}

// readEnvAgain reads the login shell's environment before a command starts again, while
// no read has worked, so one that failed at login heals by itself. It reads it once
// envRetry after the last read at most, and one start at a time, so apps that keep
// exiting don't each run a profile that hangs. Once a read has worked, only apply and
// restart read it.
func (s *supervisors) readEnvAgain() {
	s.mu.Lock()
	due := s.env == nil && !s.rereading && time.Since(s.envRead) >= s.timings.envRetry
	s.rereading = s.rereading || due
	s.mu.Unlock()
	if !due {
		return
	}
	s.readEnv()
	s.mu.Lock()
	s.rereading = false
	s.mu.Unlock()
}

// commandEnv is the environment a command starts with: the login shell's as read last, or
// the connector's own while none has been read, with why none has when a read failed.
func (s *supervisors) commandEnv() (env []string, failed string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.env == nil {
		return os.Environ(), s.envErr
	}
	return slices.Clone(s.env), ""
}

// envError is why the login shell's environment can't be read, while it never has been,
// or "".
func (s *supervisors) envError() string {
	if s == nil {
		return ""
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.envErr
}

// envWarning is what status and doctor say while apps' commands run with the connector's
// own environment: while the login shell's can't be read (envErr), and once a read works,
// for the processes that started before, until they start again. runSlugs are the apps
// the connector runs. ok is false when no command runs with the connector's own.
func envWarning(envErr string, procs map[string]ProcessStatus, runSlugs []string) (c Check, ok bool) {
	c = Check{ID: "login-environment", Status: statusWarn}
	if envErr != "" {
		c.Actor = actorPerson
		c.Message = "the apps' commands run with the connector's own environment, PATH included, since the login shell's can't be read: " + envErr
		c.Fix = envFix(runSlugs)
		return c, true
	}
	var started []string
	for slug, st := range procs {
		if st.EnvError != "" {
			started = append(started, slug)
		}
	}
	if len(started) == 0 {
		return c, false
	}
	slices.Sort(started)
	var fixes []string
	for _, slug := range started {
		fixes = append(fixes, "ovenlight restart "+slug)
	}
	who, verb := "the command of "+started[0], "runs"
	if len(started) > 1 {
		who, verb = "the commands of "+strings.Join(started, ", "), "run"
	}
	c.Actor = actorAgent
	c.Message = fmt.Sprintf("the login shell's environment can be read now, but %s started while it couldn't be, and still %s with the connector's own, PATH included: %s",
		who, verb, procs[started[0]].EnvError)
	c.Fix = strings.Join(fixes, "; ")
	return c, true
}

// envFix is how the person lets the connector read the login shell's environment, for
// the apps with these slugs. Only the person changes their profile.
func envFix(slugs []string) string {
	slug, restart := "<slug>", "ovenlight restart <slug> for each app the connector runs"
	if len(slugs) == 1 {
		slug, restart = slugs[0], "ovenlight restart "+slugs[0]
	}
	if shell := filepath.Base(loginShell()); slices.Contains(unreadShells, shell) {
		return fmt.Sprintf("The login shell, %s, isn't supported for reading the environment, so put what the command needs in the command itself: ovenlight publish --slug %s --run 'PATH=<dir>:$PATH <command>'.", shell, slug)
	}
	return fmt.Sprintf("Make %s finish within 10 s, without `exit` or `exec`ing another shell, then run %s.", profileName(loginShell()), restart)
}

// profileName is the profile a login shell reads, as a person finds it.
func profileName(shell string) string {
	switch filepath.Base(shell) {
	case "zsh":
		return "~/.zprofile"
	case "bash":
		return "~/.bash_profile"
	case "fish":
		return "~/.config/fish/config.fish"
	case "csh", "tcsh":
		return "~/.login"
	}
	return "~/.profile"
}

// rcName is the file a terminal's shell reads and a login shell doesn't, as a person
// finds it, or "" for a shell without one.
func rcName(shell string) string {
	switch filepath.Base(shell) {
	case "zsh":
		return "~/.zshrc"
	case "bash":
		return "~/.bashrc"
	}
	return ""
}

// unreadShells are the login shells whose environment loginEnv doesn't read: it can't
// have them run a command as a login shell, or print their environment as sh would.
var unreadShells = []string{"nu", "xonsh", "elvish"}

// apply makes the processes match the apps: it stops those whose app is gone, lost its
// command, or changed its command, directory or port, then starts the new ones. It waits
// for the old processes to end, so a restarted app finds its port free. When it starts
// any, it reads the login shell's environment again (see readEnv) first; a reload that
// starts none doesn't run the profile. A nil supervisors (a test daemon) runs nothing.
func (s *supervisors) apply(apps []App) {
	if s == nil {
		return
	}
	s.orphans.Do(s.endOrphans)
	want := map[string]App{}
	for _, app := range apps {
		if app.Run != "" {
			want[app.Slug] = app
		}
	}
	s.mu.Lock()
	if len(want) == 0 {
		s.envErr = "" // no command runs with the connector's own environment
	}
	var old []*supervisor
	for slug, p := range s.procs {
		if app, ok := want[slug]; !ok || app.Run != p.app.Run || app.Dir != p.app.Dir || app.Port != p.app.Port {
			old = append(old, p)
			delete(s.procs, slug)
		}
	}
	starts := len(want) > len(s.procs) // each kept process is one of want
	s.mu.Unlock()
	if starts {
		s.readEnv()
	}
	stopEach(old)

	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	for slug, app := range want {
		if s.procs[slug] == nil {
			s.procs[slug] = s.start(app)
		}
	}
}

// restart stops the app's process and starts a fresh one, whose waits start over, with
// the login shell's environment read again.
func (s *supervisors) restart(app App) {
	if s == nil {
		return
	}
	s.readEnv()
	s.mu.Lock()
	old := s.procs[app.Slug]
	delete(s.procs, app.Slug)
	s.mu.Unlock()
	if old != nil {
		old.stop()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed {
		s.procs[app.Slug] = s.start(app)
	}
}

// close stops every process, for shutdown; apply starts none after it.
func (s *supervisors) close() {
	if s == nil {
		return
	}
	s.mu.Lock()
	s.closed = true
	var all []*supervisor
	for _, p := range s.procs {
		all = append(all, p)
	}
	s.procs = map[string]*supervisor{}
	s.mu.Unlock()
	stopEach(all)
}

func (s *supervisors) status() map[string]ProcessStatus {
	if s == nil {
		return map[string]ProcessStatus{}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]ProcessStatus{}
	for slug, p := range s.procs {
		p.mu.Lock()
		out[slug] = p.st
		p.mu.Unlock()
	}
	return out
}

// stopEach stops the processes at once, so shutdown takes one grace period, not one each.
func stopEach(procs []*supervisor) {
	var wg sync.WaitGroup
	for _, p := range procs {
		wg.Go(p.stop)
	}
	wg.Wait()
}

// supervisor keeps one app's command running until stop.
type supervisor struct {
	app     App
	t       superviseTimings
	env     func() ([]string, string) // supervisors.commandEnv
	again   func()                    // supervisors.readEnvAgain, before each start but the first
	command func(run string) *exec.Cmd
	start   func(*exec.Cmd) error
	probe   func(dir string)
	out     io.Writer // the app's log
	record  string    // <slug>.pid, which names the running process group
	logf    func(format string, args ...any)

	quit chan struct{}
	done chan struct{}

	mu sync.Mutex
	st ProcessStatus
	// starting is set while the command starts. That waits for the person when the app's
	// folder is one macOS protects (see protectedFolder), until they click Allow on the
	// Mac's screen; probeFolder takes that wait before the command's process exists.
	// abandoned is set once stop stops waiting.
	starting, abandoned bool
}

func (s *supervisors) start(app App) *supervisor {
	p := &supervisor{app: app, t: s.timings, env: s.commandEnv, again: s.readEnvAgain, command: s.command, start: s.startCmd, probe: s.probe, out: io.Discard, record: filepath.Join(s.logDir, app.Slug+".pid"),
		quit: make(chan struct{}), done: make(chan struct{})}
	prefix := "[" + app.Slug + "] "
	p.logf = func(format string, args ...any) { log.Printf(prefix+format, args...) }
	var closeLog func() error
	if err := os.MkdirAll(s.logDir, 0o700); err != nil {
		p.logf("can't keep the app's output: %v", err)
	} else {
		f := &restartingFile{path: filepath.Join(s.logDir, app.Slug+".log"), max: maxAppLog}
		if err := f.open(0); err != nil {
			p.logf("can't keep the app's output: %v", err)
		} else {
			p.out, closeLog = keepWriting{f}, f.Close
		}
	}
	go func() {
		p.loop()
		if closeLog != nil {
			closeLog()
		}
		close(p.done)
	}()
	return p
}

// keepWriting drops what the log can't take, so a full disk never stops the copy from the
// app's output pipe, which would leave the app blocked on its next write.
type keepWriting struct{ w io.Writer }

func (k keepWriting) Write(p []byte) (int, error) {
	k.w.Write(p)
	return len(p), nil
}

// stop ends the process and waits for it to end, which endGroup bounds. A command that is
// still starting can take any time, waiting for the person to let the connector into its
// folder, and holding up whoever stops it, a reload or shutdown. So stop waits for it
// twice the grace at most, and then runOnce doesn't start it.
func (p *supervisor) stop() {
	close(p.quit)
	limit := time.NewTimer(2 * p.t.grace)
	defer limit.Stop()
	select {
	case <-p.done:
		return
	case <-limit.C:
	}
	p.mu.Lock()
	p.abandoned = p.starting
	abandoned := p.abandoned
	p.mu.Unlock()
	if !abandoned {
		<-p.done
		return
	}
	p.logf("stopped without waiting for the app's command, which hasn't started yet: macOS may be asking on this Mac's screen "+
		"to let the connector use %s. Once it answers, this copy doesn't start", p.app.Dir)
}

// note writes a line of the connector's own into the app's log, among the app's output,
// so whoever reads it sees when the app started and why it ended.
func (p *supervisor) note(format string, args ...any) {
	fmt.Fprintf(p.out, "[ovenlight %s] %s\n", time.Now().Format(time.DateTime), fmt.Sprintf(format, args...))
}

func (p *supervisor) loop() {
	wait := p.t.minBackoff
	for {
		started := time.Now()
		why, served, stopped := p.runOnce()
		if stopped {
			return
		}
		if served && time.Since(started) >= p.t.stableAfter {
			wait = p.t.minBackoff
		}
		p.note("%s; starting it again in %s", why, wait)
		p.logf("the app %s; starting it again in %s", why, wait)
		select {
		case <-p.quit:
			return
		case <-time.After(wait):
		}
		p.again()
		wait = min(wait*2, p.t.maxBackoff)
		p.mu.Lock()
		p.st.Restarts++
		p.mu.Unlock()
	}
}

// runOnce runs the command until it exits or stop is called, and ends whatever it left
// running in its process group. It returns how the process ended, whether it served (was
// seen accepting connections on its port), and whether stop ended it.
func (p *supervisor) runOnce() (why string, served, stopped bool) {
	cmd := p.command(p.app.Run)
	cmd.Dir = p.app.Dir
	env, failed := p.env()
	cmd.Env = append(env, "PWD="+p.app.Dir, "PORT="+strconv.Itoa(p.app.Port), "HOST=127.0.0.1", // the last of a name wins
		"NPM_CONFIG_UPDATE_NOTIFIER=false") // keeps npm's update notice out of the app's log
	cmd.Stdout, cmd.Stderr = p.out, p.out
	cmd.WaitDelay = time.Second // what it started may hold the output pipe after it exits
	newProcessGroup(cmd)
	p.mu.Lock()
	p.starting = true
	p.mu.Unlock()
	p.probe(p.app.Dir)
	select {
	case <-p.quit: // after starting is set, so stop either sees it set or this sees quit
		p.mu.Lock()
		p.starting = false
		p.mu.Unlock()
		return "", false, true
	default:
	}
	p.note("starting %q in %s (PORT=%d)", p.app.Run, p.app.Dir, p.app.Port) // before the command can print
	if failed != "" {
		p.note("this runs with the connector's own environment, with PATH %s, since the login shell's can't be read: %s. For the person to fix: %s",
			os.Getenv("PATH"), failed, envFix([]string{p.app.Slug}))
	}
	// Something answering on the port that lsof can't name, which watchServing would then
	// take for this copy (see copyServes).
	_, lsofErr := listeners(p.app.Port)
	held := lsofErr != nil && accepts("127.0.0.1", p.app.Port)
	startErr := p.start(cmd)
	p.mu.Lock()
	p.starting = false
	abandoned := p.abandoned
	p.mu.Unlock()
	if startErr != nil {
		p.ended("couldn't start: " + startErr.Error())
		return fmt.Sprintf("couldn't start (%v)", startErr), false, abandoned
	}
	pid := cmd.Process.Pid
	holdGroup(pid)
	if abandoned {
		// The supervisor that replaced this one, if any, owns the record and the status.
		p.logf("the app's command started after the connector stopped it (pid %d); ending it", pid)
	} else {
		if err := writeGroupRecord(p.record, pid); err != nil {
			p.logf("can't record the app's process group, so a connector restarted after a crash won't end it: %v", err)
		}
		p.mu.Lock()
		p.st.Running, p.st.PID, p.st.StartedAt, p.st.EnvError = true, pid, time.Now(), failed
		p.mu.Unlock()
	}

	exited := make(chan struct{})
	var err error
	go func() {
		err = cmd.Wait()
		close(exited)
	}()
	var serving atomic.Bool
	if !held {
		go p.watchServing(&serving, pid, exited)
	}
	select {
	case <-exited:
	case <-p.quit:
		stopped = true
	}
	// Ends the rest of the group too: a dev server's child can outlive its parent and keep
	// the port, so the next start would fail.
	endGroup(pid, p.t.grace, exited)
	if abandoned {
		return "", false, true
	}
	os.Remove(p.record)
	why = "exit status 0"
	if err != nil {
		why = err.Error()
	}
	p.ended(why)
	if stopped {
		p.note("stopped (%s)", why)
	}
	return "exited (" + why + ")", serving.Load(), stopped
}

// watchServing sets serving once the copy whose leader is leader serves on its port (see
// copyServes), asking every dialEvery until it does or exits. runOnce doesn't ask it
// while something lsof can't name answered on the port when the command started, so a
// copy that fails there backs off.
func (p *supervisor) watchServing(serving *atomic.Bool, leader int, exited <-chan struct{}) {
	tick := time.NewTicker(p.t.dialEvery)
	defer tick.Stop()
	for {
		select {
		case <-exited:
			return
		case <-tick.C:
			if copyServes(p.app.Port, leader) {
				serving.Store(true)
				return
			}
		}
	}
}

// copyServes reports whether the copy whose leader is leader accepts connections on
// 127.0.0.1 at port: something does, and what a connection there reaches is in the copy
// (see inCopy), a listener on 127.0.0.1 or, with none there, on every address. Another
// program doesn't count, such as AirPlay Receiver, which answers from *:5000 and *:7000
// before the app binds, so a slow build that then fails there still backs off. When lsof
// can't say what listens, an answer is enough.
func copyServes(port, leader int) bool {
	if !accepts("127.0.0.1", port) {
		return false
	}
	all, err := listeners(port)
	if err != nil {
		return true
	}
	host := "127.0.0.1"
	if !slices.ContainsFunc(all, func(l listener) bool { return l.host() == host }) {
		host = "*"
	}
	return slices.ContainsFunc(all, func(l listener) bool { return l.host() == host && inCopy(l.pid, leader) })
}

// probeFolder reads the app's folder in the connector's own process, so that when macOS
// holds it until the person allows the connector into the folder, the wait comes before
// the command's process exists: a process forked during it would run unsupervised if the
// connector exited before the person answered. Whether it can read the folder doesn't
// matter here; starting the command reports that.
func probeFolder(dir string) {
	if f, err := os.Open(dir); err == nil {
		f.Readdirnames(1)
		f.Close()
	}
}

func (p *supervisor) ended(why string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.st.Running, p.st.PID, p.st.LastExit, p.st.LastExitAt, p.st.EnvError = false, 0, why, time.Now(), ""
}

// endGroup asks the process group to exit, and kills what is left of it after grace. It
// returns once the leader has exited, so its exit status is known, and the group is
// empty, or a second grace after the kill if something in it outlives even that.
func endGroup(pgid int, grace time.Duration, exited <-chan struct{}) {
	signalGroup(pgid, false)
	timeout := time.NewTimer(grace)
	defer timeout.Stop()
	killed := false
	for {
		select {
		case <-exited:
			if !groupAlive(pgid) {
				return
			}
		default:
		}
		select {
		case <-timeout.C:
			if killed {
				releaseGroup(pgid) // what is left can't be ended
				return
			}
			signalGroup(pgid, true)
			killed = true
			timeout.Reset(grace)
			<-exited // SIGKILL can't be caught, so the leader exits now
		case <-time.After(20 * time.Millisecond):
		}
	}
}

// groupRecord is what <slug>.pid holds: the process group of an app's process, which is
// its leader's pid, and when the leader started, which tells it from a later process
// given the same pid.
type groupRecord struct {
	PGID  int   `json:"pgid"`
	Start int64 `json:"start"` // as processStart gives it
}

func writeGroupRecord(path string, pid int) error {
	start, err := processStart(pid)
	if err != nil {
		return err
	}
	data, _ := json.Marshal(groupRecord{PGID: pid, Start: start})
	return os.WriteFile(path, data, 0o600)
}

// endOrphans ends the process groups a connector that didn't stop cleanly (a panic, a
// SIGKILL) left running. Its apps ran in groups of their own, so they outlived it, and
// each would keep its port and serve the code it started with while this connector's
// copy failed to start.
//
// A group is ended only when its leader is still the process that was recorded: the same
// pid, started at the same time, still leading the group. When the leader is gone the
// group is left alone, though some of it may be running: a pid, and so a group ID, can
// belong to an unrelated process by now, and its group would be indistinguishable from
// the app's. That misses little, since the leader is the sh that runs the app's command or
// the app itself, which usually runs as long as the rest of its group.
func (s *supervisors) endOrphans() {
	records, _ := filepath.Glob(filepath.Join(s.logDir, "*.pid"))
	var wg sync.WaitGroup
	defer wg.Wait()
	for _, path := range records {
		slug := strings.TrimSuffix(filepath.Base(path), ".pid")
		data, err := os.ReadFile(path)
		var rec groupRecord
		if err == nil {
			err = json.Unmarshal(data, &rec)
		}
		if err == nil && rec.PGID > 1 {
			holdGroup(rec.PGID) // so its pid can't change hands while it is checked and ended
		}
		switch {
		case err != nil || rec.PGID <= 1:
			log.Printf("[%s] ignoring %s, which doesn't name a process group", slug, path)
		case !leads(rec):
			// Gone, or another process with its pid.
			releaseGroup(rec.PGID)
		default:
			log.Printf("[%s] ending process group %d, which a connector that didn't stop cleanly left running", slug, rec.PGID)
			wg.Go(func() { endStrayGroup(rec.PGID, s.timings.grace) })
		}
		os.Remove(path)
	}
}

// strayGroup is the process group that <slug>.pid in logDir records, while its leader
// still runs, or 0: what a connector that didn't stop cleanly left running.
func strayGroup(logDir, slug string) int {
	data, err := os.ReadFile(filepath.Join(logDir, slug+".pid"))
	var rec groupRecord
	if err != nil || json.Unmarshal(data, &rec) != nil || rec.PGID <= 1 || !leads(rec) {
		return 0
	}
	return rec.PGID
}

// leads reports whether the recorded process is still running and leads its group.
func leads(rec groupRecord) bool {
	start, err := processStart(rec.PGID)
	return err == nil && start == rec.Start && processGroup(rec.PGID) == rec.PGID
}

// endStrayGroup is endGroup for a group whose leader isn't this process's child.
func endStrayGroup(pgid int, grace time.Duration) {
	defer releaseGroup(pgid)
	for _, kill := range []bool{false, true} {
		signalGroup(pgid, kill)
		for deadline := time.Now().Add(grace); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
			if !groupAlive(pgid) {
				return
			}
		}
	}
}
