//go:build unix

// The supervisor's tests drive sh scripts and Unix signals; supervise_windows_test.go
// covers what Windows does its own way.

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

// testSupervisors runs commands with this process's environment, skipping the login
// shell's startup files, and with short waits. dir is for the app to run in; the processes stop
// before it goes.
func testSupervisors(t *testing.T, timings superviseTimings) (s *supervisors, dir string) {
	t.Helper()
	s, dir = newSupervisors(t.TempDir()), t.TempDir()
	s.timings = timings
	s.findEnv = func() ([]string, error) { return os.Environ(), nil }
	t.Cleanup(s.close)
	return s, dir
}

var quick = superviseTimings{minBackoff: 20 * time.Millisecond, maxBackoff: 80 * time.Millisecond, stableAfter: time.Hour, dialEvery: 10 * time.Millisecond, grace: 2 * time.Second}

func procStatus(s *supervisors, slug string) (ProcessStatus, bool) {
	st, ok := s.status()[slug]
	return st, ok
}

func appLog(s *supervisors, slug string) string {
	b, _ := os.ReadFile(filepath.Join(s.logDir, slug+".log"))
	return string(b)
}

// gone reports whether no process has this pid any more.
func gone(pid int) bool { return errors.Is(syscall.Kill(pid, 0), syscall.ESRCH) }

// readPID waits for the command to write a pid to the file.
func readPID(t *testing.T, path string) int {
	t.Helper()
	var pid int
	waitFor(t, func() bool {
		b, _ := os.ReadFile(path)
		pid, _ = strconv.Atoi(strings.TrimSpace(string(b)))
		return pid > 0
	}, "a pid in "+path)
	return pid
}

func TestSupervisorRunsTheCommandWithPortHostAndDir(t *testing.T) {
	s, dir := testSupervisors(t, quick)
	s.apply([]App{{Name: "Coach", Slug: "coach", Port: 4317, Run: `echo "port=$PORT host=$HOST dir=$(pwd -P)"; echo "stderr=$PORT" >&2; sleep 30`, Dir: dir}})
	real, _ := filepath.EvalSymlinks(dir)
	waitFor(t, func() bool { return strings.Contains(appLog(s, "coach"), "stderr=4317") }, "the app's output")
	out := appLog(s, "coach")
	if !strings.Contains(out, "port=4317 host=127.0.0.1 dir="+real+"\n") {
		t.Errorf("log:\n%s", out)
	}
	if !strings.Contains(out, "[ovenlight ") || !strings.Contains(out, "starting ") {
		t.Errorf("no start line in the log:\n%s", out)
	}
	if info, err := os.Stat(filepath.Join(s.logDir, "coach.log")); err != nil || info.Mode().Perm() != 0o600 {
		t.Errorf("log file: %v %v", info.Mode(), err)
	}
	st, ok := procStatus(s, "coach")
	if !ok || !st.Running || st.PID == 0 || st.Restarts != 0 || st.StartedAt.IsZero() {
		t.Fatalf("status = %+v", st)
	}
	start := time.Now()
	s.close()
	if time.Since(start) > 3*time.Second { // a loaded -race run is slow
		t.Errorf("stopping a process that takes SIGTERM took %v", time.Since(start))
	}
	if !gone(st.PID) {
		t.Error("the process outlived the stop")
	}
	if !strings.Contains(appLog(s, "coach"), "stopped (signal: terminated)") {
		t.Errorf("log:\n%s", appLog(s, "coach"))
	}
}

// A process that exits at once is started again after a wait that doubles up to the
// cap, never in a hot loop.
func TestSupervisorBacksOff(t *testing.T) {
	s, dir := testSupervisors(t, quick)
	s.apply([]App{{Name: "Coach", Slug: "coach", Port: 4317, Run: "exit 3", Dir: dir}})
	waitFor(t, func() bool { st, _ := procStatus(s, "coach"); return st.Restarts >= 4 }, "four restarts")
	out := appLog(s, "coach")
	for _, want := range []string{"exited (exit status 3); starting it again in 20ms", "in 40ms", "in 80ms"} {
		if !strings.Contains(out, want) {
			t.Errorf("log lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "in 160ms") {
		t.Errorf("the wait went past the cap:\n%s", out)
	}
	if st, _ := procStatus(s, "coach"); st.LastExit != "exit status 3" || st.LastExitAt.IsZero() {
		t.Errorf("status = %+v", st)
	}
}

// A process that stayed up a while and served on its port is working, so its next exit
// waits the least again. One that stayed up as long without serving, such as a build
// that fails before the server starts, waits longer each time, also while another
// process holds the port.
func TestSupervisorBackoffStartsOverAfterAGoodRun(t *testing.T) {
	timings := quick
	timings.stableAfter = 50 * time.Millisecond
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	exe, _ := os.Executable()
	s, dir := testSupervisors(t, timings)
	s.apply([]App{{Name: "Coach", Slug: "coach", Port: port, Run: shellQuote(exe) + " " + serveCommand, Dir: dir}})
	waitFor(t, func() bool { st, _ := procStatus(s, "coach"); return st.Restarts >= 2 }, "two restarts")
	if out := appLog(s, "coach"); strings.Contains(out, "in 40ms") || !strings.Contains(out, "in 20ms") {
		t.Errorf("serving: log:\n%s", out)
	}
	s.close()

	ln, err = net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(port)) // another process, as far as the dial can tell
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	s, dir = testSupervisors(t, timings)
	s.apply([]App{{Name: "Coach", Slug: "coach", Port: port, Run: "sleep 0.1; exit 1", Dir: dir}})
	waitFor(t, func() bool { st, _ := procStatus(s, "coach"); return st.Restarts >= 2 }, "two restarts")
	if out := appLog(s, "coach"); !strings.Contains(out, "in 20ms") || !strings.Contains(out, "in 40ms") {
		t.Errorf("not serving: log:\n%s", out)
	}
}

// The copy serves only from a listener of its own on 127.0.0.1: another program that
// answers there, such as AirPlay Receiver on 5000, doesn't count.
func TestCopyServes(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	if all, err := listeners(port); err != nil || !slices.ContainsFunc(all, func(l listener) bool { return l.pid == os.Getpid() }) {
		t.Skipf("lsof doesn't show this process listening (%v): %v", err, all)
	}
	other := exec.Command("sleep", "30") // a copy that hasn't bound yet
	newProcessGroup(other)
	if err := other.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { other.Process.Kill(); other.Wait() }()
	if copyServes(port, other.Process.Pid) {
		t.Error("another process's listener counts as the copy's")
	}
	if !copyServes(port, processGroup(os.Getpid())) {
		t.Error("the copy's own listener doesn't count")
	}
	ln.Close()
	if copyServes(port, processGroup(os.Getpid())) {
		t.Error("nothing listens, yet it serves")
	}
}

// The command runs with sh in the login shell's environment. While no read has worked,
// the connector's own serves, which the app's log and envError say, with the fix, and each
// start reads it again, so a read that failed at login heals by itself. Once one has
// worked, it is read only at an apply that starts a command and at a restart: a process
// started again gets the one read last, and a failed read keeps it.
func TestSupervisorRunsInTheLoginShellsEnvironment(t *testing.T) {
	t.Setenv("SHELL", "/bin/sh")
	s, dir := testSupervisors(t, quick)
	var reads atomic.Int32
	var login atomic.Pointer[[]string] // nil: the read fails
	s.findEnv = func() ([]string, error) {
		reads.Add(1)
		if env := login.Load(); env != nil {
			return *env, nil
		}
		return nil, errors.New("/bin/zsh took over 10s to start")
	}
	setLogin := func(vars ...string) {
		env := append([]string{"PATH=" + os.Getenv("PATH"), "PORT=1", "HOST=0.0.0.0", "PWD=/"}, vars...)
		login.Store(&env)
	}
	t.Setenv("OWN", "connector")
	app := App{Name: "Coach", Slug: "coach", Port: 4317, Run: `echo "foo=$FOO own=$OWN port=$PORT host=$HOST pwd=$PWD"; exit 1`, Dir: dir}
	since := 0
	logged := func(line string) bool {
		return strings.Contains(appLog(s, "coach")[since:], line+" port=4317 host=127.0.0.1 pwd="+dir+"\n")
	}
	restarts := func() int { st, _ := procStatus(s, "coach"); return st.Restarts }
	s.apply([]App{app})
	waitFor(t, func() bool { return restarts() >= 2 && logged("foo= own=connector") }, "the connector's own environment")
	if n := reads.Load(); n < 3 {
		t.Errorf("read the environment %d times for 3 starts", n)
	}
	const note = "since the login shell's can't be read: /bin/zsh took over 10s to start. For the person to fix: Make ~/.profile finish within 10 s, without `exit` or `exec`ing another shell, then run ovenlight restart coach."
	if got := s.envError(); got != "/bin/zsh took over 10s to start" || !strings.Contains(appLog(s, "coach"), note) {
		t.Errorf("envError %q, log:\n%s", got, appLog(s, "coach"))
	}

	setLogin("FOO=one")
	since = len(appLog(s, "coach"))
	waitFor(t, func() bool { return logged("foo=one own=") }, "the environment a start read once it could")
	n, after := reads.Load(), restarts()
	waitFor(t, func() bool { return restarts() >= after+2 }, "more restarts")
	if reads.Load() != n || s.envError() != "" {
		t.Errorf("read the environment %d more times once a read worked, envError %q", reads.Load()-n, s.envError())
	}
	notes := App{Name: "Notes", Slug: "notes", Port: 4400, Run: "exec sleep 30", Dir: dir}
	if s.apply([]App{app}); reads.Load() != n {
		t.Errorf("read the environment %d more times; with nothing to start an apply needn't", reads.Load()-n)
	}
	setLogin("FOO=two")
	since = len(appLog(s, "coach"))
	s.apply([]App{app, notes}) // starts notes; coach's next start gets FOO too
	waitFor(t, func() bool { return logged("foo=two own=") }, "the environment the next apply read")
	if s.apply(nil); reads.Load() != n+1 {
		t.Errorf("read the environment %d more times", reads.Load()-n)
	}

	login.Store(nil)
	since = len(appLog(s, "coach"))
	s.restart(app)
	waitFor(t, func() bool { return restarts() >= 1 && logged("foo=two own=") }, "the environment read last")
	if out := appLog(s, "coach")[since:]; strings.Contains(out, "own=connector") || strings.Contains(out, "can't be read") || reads.Load() != n+2 || s.envError() != "" {
		t.Errorf("a failed read after a good one, %d more reads, envError %q:\n%s", reads.Load()-n, s.envError(), out)
	}

	setLogin("FOO=three")
	since = len(appLog(s, "coach"))
	s.restart(app)
	waitFor(t, func() bool { return logged("foo=three own=") }, "the environment restart read")
}

// Stopping ends the whole process group, killing what ignores SIGTERM once the grace
// period is over.
func TestSupervisorKillsWhatIgnoresSIGTERM(t *testing.T) {
	timings := quick
	timings.grace = 200 * time.Millisecond
	s, dir := testSupervisors(t, timings)
	s.apply([]App{{Name: "Coach", Slug: "coach", Port: 4317, Run: `trap '' TERM; sleep 30 & echo $! > child; wait`, Dir: dir}})
	child := readPID(t, filepath.Join(dir, "child"))
	st, _ := procStatus(s, "coach")
	start := time.Now()
	s.apply(nil)
	if took := time.Since(start); took < timings.grace || took > timings.grace+3*time.Second {
		t.Errorf("stop took %v, want the grace period, %v, and a little", took, timings.grace)
	}
	if _, ok := procStatus(s, "coach"); ok {
		t.Error("an app with no command is still supervised")
	}
	waitFor(t, func() bool { return gone(st.PID) && gone(child) }, "the group to be gone")
	if out := appLog(s, "coach"); !strings.Contains(out, "stopped (signal: killed)") {
		t.Errorf("log:\n%s", out)
	}
}

// What the app started is ended when the app exits, so it can't keep the port from the
// next start.
func TestSupervisorEndsWhatTheAppLeftBehind(t *testing.T) {
	s, dir := testSupervisors(t, quick)
	s.apply([]App{{Name: "Coach", Slug: "coach", Port: 4317, Run: `[ -e child ] && sleep 30; sleep 30 & echo $! > child; exit 1`, Dir: dir}})
	child := readPID(t, filepath.Join(dir, "child"))
	waitFor(t, func() bool { return gone(child) }, "the left-behind child to be ended")
	waitFor(t, func() bool { st, _ := procStatus(s, "coach"); return st.Restarts == 1 && st.Running }, "the restart")
}

// A changed command, directory or port restarts the process; a renamed app keeps it.
func TestSupervisorsApplyRestartsOnlyOnChange(t *testing.T) {
	s, dir := testSupervisors(t, quick)
	app := App{Name: "Coach", Slug: "coach", Port: 4317, Run: `echo "port $PORT"; exec sleep 30`, Dir: dir}
	pid := func() int {
		t.Helper()
		var st ProcessStatus
		waitFor(t, func() bool { st, _ = procStatus(s, "coach"); return st.Running }, "the process")
		return st.PID
	}
	s.apply([]App{app})
	first := pid()
	app.Name = "Interview Coach"
	s.apply([]App{app, {Name: "Notes", Slug: "notes", Port: 4400}})
	if got := pid(); got != first {
		t.Errorf("renaming restarted it: pid %d, then %d", first, got)
	}
	if _, ok := procStatus(s, "notes"); ok {
		t.Error("an app without a command got a process")
	}
	app.Port = 4318
	s.apply([]App{app})
	if got := pid(); got == first || !gone(first) {
		t.Errorf("a new port kept pid %d (now %d)", first, got)
	}
	waitFor(t, func() bool { return strings.Contains(appLog(s, "coach"), "port 4318") }, "the new PORT")
}

func TestLoginShell(t *testing.T) {
	t.Setenv("SHELL", "/bin/sh")
	if got := loginShell(); got != "/bin/sh" {
		t.Errorf("SHELL=/bin/sh: %s", got)
	}
	for _, bad := range []string{"", "sh", "/nonexistent/shell", "/bin"} {
		t.Setenv("SHELL", bad)
		if got := loginShell(); got != "/bin/zsh" && got != "/bin/sh" {
			t.Errorf("SHELL=%q: %s", bad, got)
		}
	}
	t.Setenv("SHELL", "/bin/sh")
	out, err := shellCommand(`echo "$0 ran"`).Output()
	if err != nil || !strings.Contains(string(out), "ran") {
		t.Errorf("%q %v", out, err)
	}
}

// serveCommand runs the test binary as an app that listens on $PORT for 300 ms, then
// exits 1.
const serveCommand = "__serve"

// TestMain lets loginEnv's login shell run the test binary as it runs the connector, and
// apps run it as serveCommand.
func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == env0Command {
		printEnv0()
		return
	}
	if len(os.Args) > 1 && os.Args[1] == serveCommand {
		if _, err := net.Listen("tcp", "127.0.0.1:"+os.Getenv("PORT")); err == nil {
			time.Sleep(300 * time.Millisecond) // long enough for watchServing's lsof to see it
		}
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// The login shell's environment comes from its profile, past whatever the profile prints,
// without what describes the shell itself or would log the connector's nodes in.
func TestLoginEnv(t *testing.T) {
	home := t.TempDir()
	env := []string{"HOME=" + home, "PATH=/usr/bin:/bin", "TS_AUTHKEY=connector"}
	profiles := map[string]struct{ file, line string }{
		"/bin/sh":   {".profile", "echo welcome; PATH=/profile/bin:$PATH; FOO='a b'; TS_CONTROL_URL=x; export PATH FOO TS_CONTROL_URL\n"},
		"/bin/bash": {".bash_profile", "echo welcome; export PATH=/profile/bin:$PATH FOO='a b' TS_CONTROL_URL=x\n"},
		"/bin/zsh":  {".zprofile", "echo welcome; export PATH=/profile/bin:$PATH FOO='a b' TS_CONTROL_URL=x\n"},
		"fish":      {".config/fish/config.fish", "echo welcome; set -gx PATH /profile/bin $PATH; set -gx FOO 'a b'; set -gx TS_CONTROL_URL x\n"},
		"/bin/tcsh": {".login", "echo welcome; setenv PATH /profile/bin:$PATH; setenv FOO 'a b'; setenv TS_CONTROL_URL x\n"},
	}
	for shell, profile := range profiles {
		path, err := exec.LookPath(shell)
		if err != nil {
			continue
		}
		t.Setenv("SHELL", path)
		file := filepath.Join(home, profile.file)
		os.MkdirAll(filepath.Dir(file), 0o755)
		os.WriteFile(file, []byte(profile.line), 0o644)
		got, err := loginEnv(env)
		vars := map[string]string{}
		for _, v := range got {
			name, value, _ := strings.Cut(v, "=")
			vars[name] = value
		}
		if err != nil || !strings.HasPrefix(vars["PATH"], "/profile/bin:") || !strings.Contains(vars["PATH"], "/usr/bin") || vars["FOO"] != "a b" || vars["HOME"] != home {
			t.Errorf("%s: %q, %v", shell, got, err)
		}
		for _, name := range []string{"PWD", "OLDPWD", "SHLVL", "_", exeVar, "TS_AUTHKEY", "TS_CONTROL_URL"} {
			if _, ok := vars[name]; ok {
				t.Errorf("%s: %s in %q", shell, name, got)
			}
		}
		if strings.Contains(strings.Join(got, "\x00"), "welcome") {
			t.Errorf("%s: the profile's output in %q", shell, got)
		}
		os.Remove(file)
	}
	t.Setenv("SHELL", "/bin/sh")
	os.WriteFile(filepath.Join(home, ".profile"), []byte("exit 1\n"), 0o644)
	if got, err := loginEnv(env); err == nil {
		t.Errorf("a profile that exits: %q", got)
	}

	nu := filepath.Join(home, "nu") // a shell loginEnv can't drive says so, not where to look
	os.WriteFile(nu, []byte("#!/bin/sh\n"), 0o755)
	t.Setenv("SHELL", nu)
	if got, err := loginEnv(env); err == nil || err.Error() != nu+": reading this login shell's environment isn't supported" {
		t.Errorf("nu: %q, %v", got, err)
	}
	if fix := envFix([]string{"coach"}); fix != "The login shell, nu, isn't supported for reading the environment, so put what the command needs in the command itself: ovenlight publish --slug coach --run 'PATH=<dir>:$PATH <command>'." {
		t.Errorf("nu: %s", fix)
	}
}

// A profile that hangs leaves nothing running once the read times out.
func TestLoginEnvEndsAProfileThatHangs(t *testing.T) {
	home := t.TempDir()
	t.Setenv("SHELL", "/bin/sh")
	timeout := loginEnvTimeout
	loginEnvTimeout = 300 * time.Millisecond
	t.Cleanup(func() { loginEnvTimeout = timeout })
	child := filepath.Join(home, "child")
	os.WriteFile(filepath.Join(home, ".profile"), []byte(`sleep 30 & echo $! > "$HOME/child"; wait`+"\n"), 0o644)
	if got, err := loginEnv([]string{"HOME=" + home, "PATH=/usr/bin:/bin"}); err == nil || !strings.HasSuffix(err.Error(), "took over 300ms to start") {
		t.Errorf("%q, %v", got, err)
	}
	pid := readPID(t, child)
	waitFor(t, func() bool { return gone(pid) }, "the profile's sleep to be ended")
}

// What the profile starts in the background is ended once the read is done.
func TestLoginEnvEndsWhatTheProfileStarts(t *testing.T) {
	home := t.TempDir()
	t.Setenv("SHELL", "/bin/sh")
	child := filepath.Join(home, "child")
	os.WriteFile(filepath.Join(home, ".profile"), []byte(`sleep 30 & echo $! > "$HOME/child"`+"\n"), 0o644)
	if got, err := loginEnv([]string{"HOME=" + home, "PATH=/usr/bin:/bin"}); err != nil {
		t.Errorf("%q, %v", got, err)
	}
	pid := readPID(t, child)
	waitFor(t, func() bool { return gone(pid) }, "the profile's sleep to be ended")
}

// While no read of the login shell's environment has worked, apps that keep exiting read
// it again once envRetry after the last read at most, and one at a time.
func TestReadingTheEnvironmentAgainIsLimited(t *testing.T) {
	var reads, reading, most atomic.Int32
	failing := func() ([]string, error) {
		reads.Add(1)
		if n := reading.Add(1); n > most.Load() {
			most.Store(n)
		}
		time.Sleep(30 * time.Millisecond)
		reading.Add(-1)
		return nil, errors.New("/bin/zsh took over 10s to start")
	}
	apps := func(dir string) []App {
		return []App{{Name: "Coach", Slug: "coach", Port: 4317, Run: "exit 1", Dir: dir}, {Name: "Notes", Slug: "notes", Port: 4400, Run: "exit 1", Dir: dir}}
	}
	restarts := func(s *supervisors) int {
		a, _ := procStatus(s, "coach")
		b, _ := procStatus(s, "notes")
		return min(a.Restarts, b.Restarts)
	}

	timings := quick
	timings.envRetry = time.Hour
	s, dir := testSupervisors(t, timings)
	s.findEnv = failing
	s.apply(apps(dir))
	waitFor(t, func() bool { return restarts(s) >= 3 }, "restarts")
	if n := reads.Load(); n != 1 {
		t.Errorf("read the environment %d times within envRetry, want the apply's", n)
	}
	s.close()

	reads.Store(0)
	s, dir = testSupervisors(t, quick)
	s.findEnv = failing
	s.apply(apps(dir))
	waitFor(t, func() bool { return restarts(s) >= 3 }, "restarts")
	if reads.Load() < 3 || most.Load() != 1 {
		t.Errorf("%d reads, %d at once", reads.Load(), most.Load())
	}
}

// A process started with the connector's own environment says so in its status until it
// starts again, after a read has worked too, and status and doctor name it.
func TestAProcessKeepsTheEnvironmentItStartedWith(t *testing.T) {
	t.Setenv("SHELL", "/bin/sh")
	s, dir := testSupervisors(t, quick)
	var works atomic.Bool
	s.findEnv = func() ([]string, error) {
		if works.Load() {
			return os.Environ(), nil
		}
		return nil, errors.New("/bin/zsh took over 10s to start")
	}
	notes := App{Name: "Notes", Slug: "notes", Port: 4400, Run: "exec sleep 30", Dir: dir}
	coach := App{Name: "Coach", Slug: "coach", Port: 4317, Run: "exec sleep 30", Dir: dir}
	running := func(slug string) ProcessStatus {
		var st ProcessStatus
		waitFor(t, func() bool { st, _ = procStatus(s, slug); return st.Running }, slug+" running")
		return st
	}
	s.apply([]App{notes})
	if st := running("notes"); st.EnvError != "/bin/zsh took over 10s to start" {
		t.Errorf("started on the connector's environment: %+v", st)
	}
	if c, ok := envWarning(s.envError(), s.status(), []string{"notes"}); !ok || c.Actor != actorPerson {
		t.Errorf("unreadable: %+v", c)
	}

	works.Store(true)
	s.apply([]App{notes, coach}) // reads it, to start coach
	if st := running("coach"); st.EnvError != "" || s.envError() != "" {
		t.Errorf("coach: %+v, envError %q", st, s.envError())
	}
	c, ok := envWarning(s.envError(), s.status(), []string{"coach", "notes"})
	if !ok || c.Actor != actorAgent || c.Fix != "ovenlight restart notes" ||
		c.Message != "the login shell's environment can be read now, but the command of notes started while it couldn't be, and still runs with the connector's own, PATH included: /bin/zsh took over 10s to start" {
		t.Errorf("healed: %+v", c)
	}
	s.restart(notes)
	if st := running("notes"); st.EnvError != "" {
		t.Errorf("restarted: %+v", st)
	}
	if c, ok := envWarning(s.envError(), s.status(), []string{"coach", "notes"}); ok {
		t.Errorf("all restarted: %+v", c)
	}

	s.close()
	works.Store(false)
	s, dir = testSupervisors(t, quick)
	s.findEnv = func() ([]string, error) { return nil, errors.New("/bin/zsh took over 10s to start") }
	if s.apply([]App{notes}); s.envError() == "" {
		t.Error("no envError")
	}
	if s.apply(nil); s.envError() != "" {
		t.Errorf("no command runs, envError %q", s.envError())
	}
}

func TestResolveRun(t *testing.T) {
	cwd, _ := os.Getwd()
	other := t.TempDir()
	file := filepath.Join(other, "file")
	os.WriteFile(file, nil, 0o600)
	running := App{Slug: "coach", Run: "npm start", Dir: other}
	str := func(s string) *string { return &s }
	cases := []struct {
		name          string
		existing      App
		run, dir      *string
		wantRun, want string
		err           string
	}{
		{"new app, no command", App{}, nil, nil, "", "", ""},
		{"new command runs here", App{}, str(" npm start "), nil, "npm start", cwd, ""},
		{"new command and dir", App{}, str("npm start"), str(other), "npm start", other, ""},
		{"republish keeps it", running, nil, nil, "npm start", other, ""},
		{"dir moves it", running, nil, str("."), "npm start", cwd, ""},
		{"a changed command keeps its dir", running, str("npm run dev"), nil, "npm run dev", other, ""},
		{"a changed command and dir", running, str("npm run dev"), str("."), "npm run dev", cwd, ""},
		{"a first command runs here", App{Slug: "coach"}, str("npm start"), nil, "npm start", cwd, ""},
		{"empty run removes it", running, str(""), nil, "", "", ""},
		{"dir without a command", App{}, nil, str(other), "", "", "give one with --run"},
		{"missing dir", App{}, str("x"), str(filepath.Join(other, "nope")), "", "", "no such file"},
		{"dir is a file", App{}, str("x"), str(file), "", "", "isn't a directory"},
	}
	for _, c := range cases {
		run, dir, err := resolveRun(c.existing, c.run, c.dir)
		if c.err != "" {
			if err == nil || !strings.Contains(err.Error(), c.err) {
				t.Errorf("%s: err = %v", c.name, err)
			}
			continue
		}
		if err != nil || run != c.wantRun || dir != c.want {
			t.Errorf("%s: %q in %q, %v", c.name, run, dir, err)
		}
	}
	if (App{Name: "X", Slug: "x", Port: 80, Run: "x", Dir: "rel"}).Validate() == nil {
		t.Error("a relative dir is valid")
	}
	if (App{Name: "X", Slug: "x", Port: 80, Dir: other}).Validate() == nil {
		t.Error("a dir without a command is valid")
	}
}

func TestAppLogMovesToDotOneAndTails(t *testing.T) {
	path := filepath.Join(t.TempDir(), "coach.log")
	f := &restartingFile{path: path, max: 100}
	if err := f.open(0); err != nil {
		t.Fatal(err)
	}
	for i := range 12 {
		f.Write([]byte("line " + strconv.Itoa(i) + " " + strings.Repeat("x", 10) + "\n"))
	}
	f.Close()
	// Five 18-byte lines fill a file, so lines 0 to 4 are gone, 5 to 9 are in .1.
	if older := mustRead(t, path+".1"); !strings.HasPrefix(older, "line 5 ") {
		t.Errorf(".1 is %q", older)
	}
	got, err := tailLog(path, 4)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSuffix(got, "\n"), "\n")
	if len(lines) != 4 || !strings.HasPrefix(lines[0], "line 8 ") || !strings.HasPrefix(lines[3], "line 11 ") {
		t.Errorf("tail 4 = %q", got)
	}
	if got, _ := tailLog(path, 100); !strings.HasPrefix(got, "line 5 ") || strings.Count(got, "\n") != 7 {
		t.Errorf("tail 100 = %q", got)
	}
	if got, _ := tailLog(path, 2); !strings.HasPrefix(got, "line 10 ") {
		t.Errorf("tail 2 = %q", got)
	}
}

func TestFollowLogAcrossAMove(t *testing.T) {
	path := filepath.Join(t.TempDir(), "coach.log")
	os.WriteFile(path, []byte("before\n"), 0o600)
	var out lockedBuffer
	stop := make(chan struct{})
	done := make(chan error)
	go func() { done <- followLog(path, &out, 5*time.Millisecond, stop) }()
	appendTo := func(p, s string) {
		f, _ := os.OpenFile(p, os.O_APPEND|os.O_WRONLY|os.O_CREATE, 0o600)
		f.WriteString(s)
		f.Close()
	}
	time.Sleep(20 * time.Millisecond)
	appendTo(path, "one\n")
	waitFor(t, func() bool { return strings.Contains(out.String(), "one") }, "the first line")
	os.Rename(path, path+".1")
	appendTo(path, "two\n")
	waitFor(t, func() bool { return strings.Contains(out.String(), "two") }, "a line in the new file")
	close(stop)
	if err := <-done; err != nil || out.String() != "one\ntwo\n" {
		t.Errorf("%q %v", out.String(), err)
	}
}

// A command runs as the person, so the MCP server sets none: it keeps the one an app
// has, and says how to set one in a terminal.
func TestMCPCannotSetACommand(t *testing.T) {
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	// Ports nothing listens on, so the checks ask nothing else on this computer.
	free := func() int {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer ln.Close()
		return ln.Addr().(*net.TCPAddr).Port
	}
	coach := App{Name: "Coach", Slug: "coach", Port: free(), Shareable: true, Run: "npm start", Dir: dir}
	if err := (&Config{Apps: []App{coach}, Owner: "alex@example.com"}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	for _, args := range []string{`"slug": "coach", "run": "nc -l 4317"`, `"slug": "coach", "run": ""`, `"slug": "coach", "dir": "/"`, `"slug": "notes", "run": "npm start"`} {
		_, err := callTool("publish", json.RawMessage(fmt.Sprintf(`{"port": %d, "name": "Coach", %s}`, coach.Port, args)), p)
		if err == nil || !strings.Contains(err.Error(), "can't set the command") || !strings.Contains(err.Error(), "--run '<command>'") {
			t.Errorf("%s: err = %v", args, err)
		}
	}
	if got, _ := LoadConfig(p.config); len(got.Apps) != 1 || got.Apps[0] != coach {
		t.Errorf("the apps changed: %+v", got.Apps)
	}
	// Leaving run out keeps it, so renaming the app still works.
	out, err := callTool("publish", json.RawMessage(fmt.Sprintf(`{"port": %d, "name": "Interview Coach", "slug": "coach"}`, coach.Port)), p)
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := LoadConfig(p.config); got.Apps[0].Run != "npm start" || got.Apps[0].Name != "Interview Coach" {
		t.Errorf("the app is %+v", got.Apps[0])
	}
	if note := out.(*publishResult).Note; strings.Contains(note, "--run") {
		t.Errorf("an app with a command: note = %q", note)
	}
	// An existing app keeps its port when it is left out, as the schema says.
	if _, err := callTool("publish", json.RawMessage(`{"name": "Coach", "slug": "coach"}`), p); err != nil {
		t.Fatal(err)
	}
	if got, _ := LoadConfig(p.config); got.Apps[0].Port != coach.Port || got.Apps[0].Name != "Coach" {
		t.Errorf("renamed without a port: %+v", got.Apps[0])
	}
	if tool := mcpTools[slices.IndexFunc(mcpTools, func(t mcpTool) bool { return t.Name == "publish" })]; !reflect.DeepEqual(tool.InputSchema["required"], []string{"name"}) {
		t.Errorf("publish requires %v", tool.InputSchema["required"])
	}
	// A new app needs a port.
	if _, err := callTool("publish", json.RawMessage(`{"name": "Lists"}`), p); err == nil || err.Error() != "port is required for a new app; to change an existing one, pass its slug" {
		t.Errorf("no port: %v", err)
	}
	// An app without one says how to set it.
	out, err = callTool("publish", json.RawMessage(fmt.Sprintf(`{"port": %d, "name": "Notes"}`, free())), p)
	if err != nil {
		t.Fatal(err)
	}
	res := out.(*publishResult)
	exe, _ := os.Executable() // not the ovenlight on PATH, so the note names it in full
	if !strings.Contains(res.Note, shellQuote(exe)+" publish --config "+shellQuote(p.config)+" --state "+shellQuote(p.state)+" --slug notes --dir '<folder>' --run '<command>'") ||
		!strings.Contains(res.Note, "No connector is running for --config") {
		t.Errorf("note = %q", res.Note)
	}
	// Nothing listens, and this server runs no commands: starting it is the person's.
	if c := findCheck(t, res.Problems, "port-listening"); c.Actor != actorPerson || !strings.Contains(c.Fix, "command in note") {
		t.Errorf("port-listening = %+v", c)
	}
}

// lockedBuffer is a strings.Builder that one goroutine writes while another reads.
type lockedBuffer struct {
	mu sync.Mutex
	b  strings.Builder
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func TestMCPLogs(t *testing.T) {
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	apps := []App{{Name: "Coach", Slug: "coach", Port: 4317, Run: "npm start", Dir: dir}, {Name: "Notes", Slug: "notes", Port: 4400}}
	if err := (&Config{Apps: apps}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	if _, err := callTool("logs", json.RawMessage(`{"slug": "coach"}`), p); err == nil || !strings.Contains(err.Error(), "no output from Coach yet: the connector, which runs it, isn't running") {
		t.Errorf("before any output: %v", err)
	}
	if _, err := callTool("logs", json.RawMessage(`{"slug": "notes"}`), p); err == nil || !strings.Contains(err.Error(), "--slug notes --dir '<folder>' --run '<command>'") {
		t.Errorf("an app without a command: %v", err)
	}
	if _, err := callTool("logs", json.RawMessage(`{"slug": "../../coach"}`), p); err == nil || !strings.Contains(err.Error(), "no published app") {
		t.Errorf("a path for a slug: %v", err)
	}
	os.MkdirAll(appLogDir(p.state), 0o700)
	var b strings.Builder
	for i := range 1500 {
		b.WriteString("line " + strconv.Itoa(i) + "\n")
	}
	os.WriteFile(appLogPath(p.state, "coach"), []byte(b.String()), 0o600)
	// The output is fenced: it holds what requests sent the app, which others choose.
	unfenced := func(out any) string {
		t.Helper()
		m := out.(map[string]string)
		fence := regexp.MustCompile(`<untrusted-([0-9a-f]+)>`).FindStringSubmatch(m["notice"])
		if fence == nil {
			t.Fatalf("no fence in the notice: %q", m["notice"])
		}
		open, end := "<untrusted-"+fence[1]+">\n", "</untrusted-"+fence[1]+">"
		if !strings.HasPrefix(m["output"], open) || !strings.HasSuffix(m["output"], end) {
			t.Fatalf("the output isn't fenced in %s: %q", fence[0], m["output"][:min(len(m["output"]), 80)])
		}
		return strings.TrimSuffix(strings.TrimPrefix(m["output"], open), end)
	}
	out, err := callTool("logs", json.RawMessage(`{"slug": "coach"}`), p)
	if err != nil {
		t.Fatal(err)
	}
	if got := unfenced(out); strings.Count(got, "\n") != 100 || !strings.HasSuffix(got, "line 1499\n") {
		t.Errorf("default: %d lines", strings.Count(got, "\n"))
	}
	out, _ = callTool("logs", json.RawMessage(`{"slug": "coach", "lines": 5000}`), p)
	if got := unfenced(out); strings.Count(got, "\n") != maxMCPLogLines {
		t.Errorf("%d lines past the cap", strings.Count(got, "\n"))
	}
}

// restart stops the app's process and starts a fresh one, whose count starts over; the
// group it runs in is recorded while it runs.
func TestRestartStartsAFreshProcess(t *testing.T) {
	d, _ := testDaemon(t)
	s, dir := testSupervisors(t, quick)
	d.procs = s
	if err := d.restartApp("coach"); err == nil || !strings.Contains(err.Error(), "doesn't run Interview Coach") {
		t.Errorf("an app without a command: %v", err)
	}
	if err := d.restartApp("nope"); err == nil || !strings.Contains(err.Error(), `no published app has the slug "nope"`) {
		t.Errorf("an unknown slug: %v", err)
	}
	d.cfg.Apps[0].Run, d.cfg.Apps[0].Dir = "sleep 0.05; exit 1", dir
	s.apply(d.cfg.Apps)
	waitFor(t, func() bool { st, _ := procStatus(s, "coach"); return st.Restarts >= 2 }, "a few restarts")
	d.cfg.Apps[0].Run = "exec sleep 30"
	if err := d.restartApp("coach"); err != nil {
		t.Fatal(err)
	}
	var st ProcessStatus
	waitFor(t, func() bool { st, _ = procStatus(s, "coach"); return st.Running }, "the fresh process")
	if st.Restarts != 0 || st.LastExit != "" {
		t.Errorf("the fresh process inherited %+v", st)
	}
	var rec groupRecord
	data, err := os.ReadFile(filepath.Join(s.logDir, "coach.pid"))
	if err == nil {
		err = json.Unmarshal(data, &rec)
	}
	if err != nil || rec.PGID != st.PID || rec.Start == 0 {
		t.Errorf("the record is %s (%v), the process %d", data, err, st.PID)
	}
	s.close()
	if _, err := os.Stat(filepath.Join(s.logDir, "coach.pid")); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("stopping kept the record: %v", err)
	}
}

// A connector that didn't stop cleanly leaves its apps' process groups running. The next
// one ends those whose leader is still the recorded process, and only those.
func TestEndOrphans(t *testing.T) {
	logDir := t.TempDir()
	group := func() int {
		t.Helper()
		cmd := exec.Command("/bin/sh", "-c", "sleep 30 & wait")
		newProcessGroup(cmd)
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		exited := make(chan struct{})
		go func() { cmd.Wait(); close(exited) }()
		t.Cleanup(func() { signalGroup(cmd.Process.Pid, true); <-exited })
		return cmd.Process.Pid
	}
	orphan, other := group(), group()
	if err := writeGroupRecord(filepath.Join(logDir, "coach.pid"), orphan); err != nil {
		t.Fatal(err)
	}
	// Another start time: a later process given the recorded pid.
	data, _ := json.Marshal(groupRecord{PGID: other, Start: 1})
	os.WriteFile(filepath.Join(logDir, "notes.pid"), data, 0o600)
	os.WriteFile(filepath.Join(logDir, "junk.pid"), []byte("{"), 0o600)
	// While no connector runs, status says what the last one left running.
	if got := []int{strayGroup(logDir, "coach"), strayGroup(logDir, "notes"), strayGroup(logDir, "junk"), strayGroup(logDir, "none")}; !reflect.DeepEqual(got, []int{orphan, 0, 0, 0}) {
		t.Errorf("strayGroup: %v, want the orphan, %d, alone", got, orphan)
	}
	if got := processLine(appStatus{UnsupervisedPID: orphan}); got != fmt.Sprintf("still running from the last connector (pid %d), unsupervised", orphan) {
		t.Errorf("status: %s", got)
	}

	s := newSupervisors(logDir)
	s.timings = quick
	s.apply(nil)
	if groupAlive(orphan) {
		t.Error("the recorded group is still running")
	}
	if !groupAlive(other) {
		t.Error("a group whose leader isn't the recorded process was ended")
	}
	if left, _ := filepath.Glob(filepath.Join(logDir, "*.pid")); len(left) != 0 {
		t.Errorf("records left: %v", left)
	}
}

// Publishing under a new slug on another app's port would run a second copy of it.
func TestPublishRefusesAnotherAppsPort(t *testing.T) {
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	if err := (&Config{Apps: []App{{Name: "Coach", Slug: "coach", Port: 4317}}}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	_, err := publishApp(p, App{Name: "Interview Coach", Slug: "interview-coach", Port: 4317}, nil, nil, false)
	if err == nil || !strings.Contains(err.Error(), "publish with --slug coach") {
		t.Errorf("CLI: %v", err)
	}
	_, err = callTool("publish", json.RawMessage(`{"port": 4317, "name": "Interview Coach"}`), p)
	if err == nil || !strings.Contains(err.Error(), `publish with slug "coach"`) {
		t.Errorf("MCP: %v", err)
	}
	// The app itself, renamed on its port, then moved to a free one.
	for _, port := range []int{4317, 4318} {
		if _, err := publishApp(p, App{Name: "Interview Coach", Slug: "coach", Port: port}, nil, nil, false); err != nil {
			t.Errorf("port %d: %v", port, err)
		}
	}
	if cfg, _ := LoadConfig(p.config); len(cfg.Apps) != 1 || cfg.Apps[0].Port != 4318 {
		t.Errorf("apps = %+v", cfg.Apps)
	}
}

// Something already on the port, such as the app started in a terminal, keeps the
// connector's copy from starting, though the port answers.
func TestPublishRunSaysWhenThePortIsTaken(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // publish checks the command with the login shell, which reads no profile of the developer's
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	// Publishing again with the same command still finds it: the config doesn't say
	// what listens.
	for _, run := range []string{"npm start", "npm start", "npm run dev"} {
		res, err := publishApp(p, App{Name: "Coach", Slug: "coach", Port: port}, &run, &dir, false)
		if err != nil {
			t.Fatal(err)
		}
		c := findCheck(t, res.Problems, "port-in-use")
		if c.Status != statusFail || !strings.Contains(c.Message, fmt.Sprintf("127.0.0.1:%d", port)) ||
			!strings.Contains(c.Fix, "once the connector runs") || strings.Contains(c.Fix, "within a minute") {
			t.Errorf("%s: port-in-use %+v", run, c)
		}
	}
	// Through MCP, which can neither stop a process nor move the folder, both are the person's.
	out, err := callTool("publish", json.RawMessage(`{"name": "Coach", "slug": "coach"}`), p)
	if err != nil {
		t.Fatal(err)
	}
	res := out.(*publishResult)
	if c := findCheck(t, res.Problems, "port-in-use"); c.Actor != actorPerson || !strings.Contains(c.Fix, "Ctrl-C") {
		t.Errorf("MCP port-in-use %+v", c)
	}
	if c := findID(res.Problems, "temporary-folder"); runtime.GOOS == "darwin" && (c == nil || c.Actor != actorPerson || !strings.Contains(c.Fix, "--slug coach --dir '<folder>'")) {
		t.Errorf("MCP temporary-folder %+v", c)
	}
	if strings.Contains(res.Note, "already answers") {
		t.Errorf("an app with a command: note = %q", res.Note)
	}
	// An app without a command: what answers is most likely the person's own copy.
	if err := UpdateConfig(p.config, func(cfg *Config) error { cfg.Apps[0].Run, cfg.Apps[0].Dir = "", ""; return nil }); err != nil {
		t.Fatal(err)
	}
	for _, tool := range []string{"publish", "check_app"} {
		out, err := callTool(tool, json.RawMessage(`{"name": "Coach", "slug": "coach"}`), p)
		if tool == "check_app" {
			out, err = callTool(tool, json.RawMessage(`{"slug": "coach"}`), p)
		}
		if err != nil {
			t.Fatal(err)
		}
		var note string
		if res, ok := out.(*publishResult); ok {
			note = res.Note
		} else {
			note, _ = out.(map[string]any)["note"].(string)
		}
		if want := fmt.Sprintf("Something already answers on port %d (", port); !strings.HasPrefix(note, want) ||
			!strings.Contains(note, fmt.Sprintf(", pid %d), probably the person's own copy", os.Getpid())) || !strings.Contains(note, "--run '<command>'") {
			t.Errorf("%s: note = %q", tool, note)
		}
	}
}

// What listens on the port is the connector's copy only when it is in the process group
// the connector started; anything else on its loopback port fails, whether the copy is
// up or not, but for one on ::1 beside the copy, which warns.
func TestPortHeldCheck(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	app := App{Name: "Coach", Slug: "coach", Port: ln.Addr().(*net.TCPAddr).Port, Run: "npm start"}
	if all, err := listeners(app.Port); err != nil || !slices.ContainsFunc(all, func(l listener) bool { return l.pid == os.Getpid() }) {
		t.Skipf("lsof doesn't show this process listening (%v): %v", err, all)
	}
	copyOf := func(pid, restarts int) *controlReply {
		return &controlReply{Processes: map[string]ProcessStatus{"coach": {Running: true, PID: pid, Restarts: restarts, StartedAt: time.Now()}}}
	}
	if c := portHeldCheck(app, copyOf(processGroup(os.Getpid()), 0)); c != nil {
		t.Errorf("the connector's own copy: %+v", c)
	}
	held := fmt.Sprintf("(pid %d) already listens", os.Getpid())
	for name, status := range map[string]*controlReply{"no copy running": {}, "a copy restarting": copyOf(1, 3), "a copy up": copyOf(1, 0)} {
		if c := portHeldCheck(app, status); c == nil || c.Status != statusFail || !strings.Contains(c.Message, held) ||
			!strings.Contains(c.Fix, "ovenlight publish --slug coach --port <n>") || !strings.Contains(c.Fix, "within a minute") {
			t.Errorf("%s: %+v", name, c)
		}
	}
	// Moving a shareable app to another port takes a terminal, so that part is the person's.
	shared := app
	shared.Shareable = true
	if c := portHeldCheck(shared, &controlReply{}); c == nil || !strings.Contains(c.Fix, "ask the person to give this app a port of its own, in a terminal") {
		t.Errorf("shareable: %+v", c)
	}
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	for _, a := range []App{app, shared} {
		checks := []Check{{ID: "port-in-use", App: "coach", Status: statusFail, Actor: actorAgent}}
		personFixes(p, checks, a, "")
		want := "(the publish tool's port)"
		if a.Shareable {
			want = "in a terminal: " + republish(p, "coach") + " --port <n>"
		}
		if checks[0].Actor != actorPerson || !strings.Contains(checks[0].Fix, want) {
			t.Errorf("MCP, shareable %v: %+v", a.Shareable, checks[0])
		}
	}
	ln.Close()
	if c := portHeldCheck(app, nil); c != nil {
		t.Errorf("nothing listens: %+v", c)
	}
	// AirPlay Receiver on every address holds the port only while nothing is on 127.0.0.1,
	// and a listener on ::1 only while the copy isn't on 127.0.0.1, which macOS lets it bind
	// beside one; beside the copy, it is a second copy a browser on the computer reaches.
	inCopy := func(pid int) bool { return pid == 1 }
	airplay := listener{2, "ControlCenter", "*:5000"}
	v6 := listener{3, "node", "[::1]:5000"}
	for _, tc := range []struct {
		name   string
		all    []listener
		want   *listener
		beside bool
	}{
		{"beside the copy", []listener{{1, "node", "127.0.0.1:5000"}, airplay}, nil, false},
		{"alone", []listener{airplay}, nil, false}, // the copy binds 127.0.0.1 beside it
		{"on ::1 beside the copy", []listener{{1, "node", "127.0.0.1:5000"}, v6}, &v6, true},
		{"on ::1 with the copy down", []listener{v6}, &v6, false},
		{"on the LAN", []listener{{3, "node", "192.168.1.20:5000"}}, nil, false},
	} {
		if got, beside := heldBy(tc.all, inCopy); !reflect.DeepEqual(got, tc.want) || beside != tc.beside {
			t.Errorf("%s: %v, beside %v", tc.name, got, beside)
		}
	}
	// One beside the copy is a warning, which publish and restart don't fail on, and
	// over MCP stopping it is the person's.
	c := besideCopyCheck(app, v6)
	if c.Status != statusWarn || !checksOK([]Check{*c}) || !strings.Contains(c.Message, "node (pid 3) also listens on [::1]:5000 beside the connector's copy") ||
		!strings.Contains(c.Message, "reaches it at localhost") || !strings.Contains(c.Fix, "Ctrl-C") || !strings.Contains(c.Fix, "never pkill by name") {
		t.Errorf("beside the copy: %+v", c)
	}
	checks := []Check{*c}
	personFixes(p, checks, app, "")
	if checks[0].Actor != actorPerson || !strings.Contains(checks[0].Fix, "Ctrl-C") || !strings.Contains(checks[0].Fix, "share the data file") {
		t.Errorf("MCP beside the copy: %+v", checks[0])
	}
}

// After starting the app, publish and restart fail while something else holds its port,
// and while the copy restarts without listening; a copy that runs on without listening
// yet, as one that builds first does, only warns.
func TestPublishChecksTheCopyItStarted(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // publish checks the command with the login shell, which reads no profile of the developer's
	defer func(wait time.Duration) { startWait = wait }(startWait)
	startWait = 2 * time.Second
	held := appServer(t, func(http.ResponseWriter, *http.Request) {}).Port // the app started in a terminal
	if all, err := listeners(held); err != nil || !slices.ContainsFunc(all, func(l listener) bool { return l.pid == os.Getpid() }) {
		t.Skipf("lsof doesn't show this process listening (%v): %v", err, all)
	}
	free, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := free.Addr().(*net.TCPAddr).Port
	free.Close()
	// A socket path has to be short, shorter than t.TempDir's.
	state, err := os.MkdirTemp("", "ol")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(state) })
	s, dir := testSupervisors(t, quick)
	p := &paths{config: filepath.Join(dir, "config.json"), state: state}
	control, err := listenControl(socketPath(state))
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	go func() {
		for {
			conn, err := control.Accept()
			if err != nil {
				return
			}
			var req controlRequest
			json.NewDecoder(conn).Decode(&req)
			cfg, _ := LoadConfig(p.config)
			switch req.Cmd {
			case "reload":
				s.apply(cfg.Apps)
			case "restart":
				app, _ := cfg.Find(req.Slug)
				s.restart(app)
			}
			json.NewEncoder(conn).Encode(controlReply{OK: true, Apps: []NodeStatus{{appInfo: appInfo{Slug: "coach"}, State: "serving"}}, Processes: s.status()})
			conn.Close()
		}
	}()
	for _, tc := range []struct {
		port                  int
		run, id, status, want string
	}{
		// Compound, so publish doesn't read the login environment, which takes a -race test binary a second to start.
		{held, "true; exec sleep 30", "port-in-use", statusFail, "can't start while it does"},
		{port, "exec sleep 30", "port-listening", statusWarn, "still starting"},
		{port, "sleep 0.05; exit 1", "port-listening", statusFail, "nothing is listening on 127.0.0.1:" + strconv.Itoa(port) + ": its command ended (exit status 1)"},
	} {
		began := time.Now()
		res, err := publishApp(p, App{Name: "Coach", Slug: "coach", Port: tc.port}, &tc.run, &dir, false)
		if err != nil {
			t.Fatal(err)
		}
		if took := time.Since(began); tc.status == statusFail && took > startWait-500*time.Millisecond {
			t.Errorf("publish %q waited %v for a copy that ended", tc.run, took)
		}
		if c := findCheck(t, res.Problems, tc.id); c.Status != tc.status || !strings.Contains(c.Message, tc.want) ||
			(c.Status == statusWarn && !strings.Contains(c.Fix, "ovenlight check coach")) {
			t.Errorf("publish %q: %+v", tc.run, c)
		}
		restarted, err := restartProcess(p, "coach")
		if err != nil {
			t.Fatal(err)
		}
		if c := findCheck(t, restarted.Problems, tc.id); c.Status != tc.status || !strings.Contains(c.Message, tc.want) {
			t.Errorf("restart %q: %+v", tc.run, c)
		}
	}
	// A connector from before --run reports no command; status shows the config's.
	if out, err := collectStatus(p); err != nil || out.Apps[0].Run != "sleep 0.05; exit 1" || out.Apps[0].Dir != dir {
		t.Errorf("status: %+v, %v", out, err)
	}
}

// 5000 and 7000 are AirPlay Receiver's on macOS, so publish suggests another port.
func TestPortChoiceCheck(t *testing.T) {
	for _, port := range []int{5000, 7000} {
		if c := portChoiceCheck(App{Slug: "coach", Port: port}); c == nil || c.Status != statusWarn || !strings.Contains(c.Fix, "20000 to 29999") || !strings.Contains(c.Fix, "listening there") {
			t.Errorf("%d: %+v", port, c)
		}
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	if c := portChoiceCheck(App{Slug: "coach", Port: ln.Addr().(*net.TCPAddr).Port}); c != nil {
		t.Errorf("a port of its own: %+v", c)
	}
}

// pid 0, which Linux gives another user's listener, names no group: Getpgid(0) would be
// the caller's own.
func TestProcessGroupOfPidZero(t *testing.T) {
	if g := processGroup(0); g != -1 {
		t.Errorf("processGroup(0) = %d", g)
	}
}

// The installed connector is the person's background service; one for other paths isn't.
func TestStartFix(t *testing.T) {
	if note := runNote(&paths{config: defaultConfigPath(), state: defaultStateDir()}, "coach"); strings.Contains(note, "--config") || !strings.Contains(note, " publish --slug coach --dir") {
		t.Errorf("runNote, default paths: %s", note)
	}
	fix := startFix(&paths{config: defaultConfigPath(), state: defaultStateDir()})
	if !strings.Contains(fix, "install script again") || strings.Contains(fix, "ovenlight run") {
		t.Errorf("default paths: %s", fix)
	}
	fix = startFix(&paths{config: "/tmp/c.json", state: "/tmp/s"})
	if strings.Contains(fix, "install script") || !strings.Contains(fix, "--config /tmp/c.json and --state /tmp/s") {
		t.Errorf("other paths: %s", fix)
	}
}

// publish exits 1 when the app fails a check, though it is saved, and a new app needs a
// port.
func TestCLIPublishFailures(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // publish checks the command with the login shell, which reads no profile of the developer's
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	stdout := os.Stdout
	os.Stdout, _ = os.OpenFile(os.DevNull, os.O_WRONLY, 0)
	t.Cleanup(func() { os.Stdout.Close(); os.Stdout = stdout })
	dir := t.TempDir()
	flags := []string{"--config", filepath.Join(dir, "config.json"), "--state", filepath.Join(dir, "state")}
	port := strconv.Itoa(ln.Addr().(*net.TCPAddr).Port)
	err = cmdPublish(append([]string{"--name", "Dinner Vote", "--port", port, "--run", "npm start", "--dir", dir}, flags...))
	if err == nil || !strings.Contains(err.Error(), "saved in the config, but it fails a check") {
		t.Errorf("a failing check: %v", err)
	}
	if cfg, _ := LoadConfig(flags[1]); len(cfg.Apps) != 1 {
		t.Errorf("apps = %+v", cfg.Apps)
	}
	err = cmdPublish(append([]string{"--name", "Dinner Poll"}, flags...))
	if err == nil || err.Error() != `--port is required for a new app; to change an existing one, pass its --slug` {
		t.Errorf("no port: %v", err)
	}
	err = cmdPublish(append([]string{"--name", "dinner vote", "--slug", "vote"}, flags...))
	if err == nil || !strings.Contains(err.Error(), `("Dinner Vote" is published as dinner-vote)`) {
		t.Errorf("no port, by name: %v", err)
	}
}

// A command still starting, such as one macOS holds until the person lets the connector
// into its folder, doesn't hold up stopping it. Once macOS answers, it doesn't start, and
// the copy started after it keeps running, with its record.
func TestStopDoesNotWaitForAStartThatHangs(t *testing.T) {
	timings := quick
	timings.grace = 100 * time.Millisecond
	s, dir := testSupervisors(t, timings)
	release := make(chan struct{})
	var probes, starts atomic.Int32
	s.probe = func(string) {
		if probes.Add(1) == 1 {
			<-release // macOS asking about the folder
		}
	}
	s.startCmd = func(cmd *exec.Cmd) error {
		starts.Add(1)
		return cmd.Start()
	}
	app := App{Name: "Coach", Slug: "coach", Port: 4317, Run: "exec sleep 30", Dir: dir}
	s.apply([]App{app})
	waitFor(t, func() bool { return probes.Load() == 1 }, "the first probe")
	s.mu.Lock()
	first := s.procs["coach"]
	s.mu.Unlock()
	begin := time.Now()
	s.restart(app)
	if took := time.Since(begin); took < 2*timings.grace || took > 2*timings.grace+time.Second {
		t.Errorf("restart took %v, want twice the grace, %v, and a little", took, 2*timings.grace)
	}
	var st ProcessStatus
	waitFor(t, func() bool { st, _ = procStatus(s, "coach"); return st.Running }, "the second copy")
	close(release)
	select {
	case <-first.done:
	case <-time.After(5 * time.Second):
		t.Fatal("the first supervisor didn't end once macOS answered")
	}
	if n := starts.Load(); n != 1 {
		t.Errorf("%d copies started, want only the second", n)
	}
	if gone(st.PID) {
		t.Error("the second copy was ended")
	}
	var rec groupRecord
	data, _ := os.ReadFile(filepath.Join(s.logDir, "coach.pid"))
	if json.Unmarshal(data, &rec) != nil || rec.PGID != st.PID {
		t.Errorf("the record is %s, the second copy %d", data, st.PID)
	}
}

func TestDescends(t *testing.T) {
	if parentPID(os.Getpid()) != os.Getppid() {
		t.Skip("no parent process to read here")
	}
	if !descends(os.Getpid(), os.Getppid()) {
		t.Error("this process doesn't descend from its parent")
	}
	if descends(os.Getppid(), os.Getpid()) || descends(os.Getpid(), 1) || descends(os.Getpid(), 0) {
		t.Error("descends from a child, launchd or nothing")
	}
}

func TestProtectedFolder(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("macOS protects these folders")
	}
	home, _ := os.UserHomeDir()
	for dir, want := range map[string]string{
		filepath.Join(home, "Desktop", "coach"):                            "Desktop",
		filepath.Join(home, "documents"):                                   "Documents",
		filepath.Join(home, "Library", "Mobile Documents", "x", "coach"):   "iCloud Drive",
		filepath.Join(home, "Library", "CloudStorage", "Dropbox", "coach"): "a cloud storage folder",
		"/Volumes/Work/coach":                                              "a volume other than the startup disk",
		filepath.Join(home, "src", "coach"):                                "",
		filepath.Join(home, "Desktopper"):                                  "",
		"":                                                                 "",
	} {
		if got := protectedFolder(dir); got != want {
			t.Errorf("%q: %q, want %q", dir, got, want)
		}
	}
	app := App{Name: "Coach", Slug: "coach", Run: "npm start", Dir: filepath.Join(home, "Downloads", "coach")}
	if c := protectedFolderCheck(app); c == nil || c.Actor != actorPerson || !strings.Contains(c.Message, "clicks Allow") || !strings.Contains(c.Fix, "Files and Folders") {
		t.Errorf("check: %+v", c)
	}
}

// macOS empties /tmp and $TMPDIR when the Mac restarts, taking a project kept there.
func TestTemporaryFolder(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("macOS clears these folders")
	}
	for dir, want := range map[string]bool{
		"/tmp/coach":                         true,
		"/private/tmp/coach":                 true,
		filepath.Join(os.TempDir(), "coach"): true,
		t.TempDir():                          true,
		"/Users/sam/src/coach":               false, // not $HOME, which a test may set under /tmp
		"/tmpfoo":                            false,
		"":                                   false,
	} {
		if got := temporaryFolder(dir); got != want {
			t.Errorf("%q: %v, want %v", dir, got, want)
		}
	}
	app := App{Name: "Coach", Slug: "coach", Run: "npm start", Dir: "/tmp/coach"}
	if c := temporaryFolderCheck(app); c == nil || c.Status != statusWarn || c.Actor != actorAgent || !strings.Contains(c.Fix, "~/src") {
		t.Errorf("check: %+v", c)
	}
	if app.Run = ""; temporaryFolderCheck(app) != nil {
		t.Error("an app the connector doesn't run")
	}
}

func TestCommandWord(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // the login shell reads no profile of the developer's
	for run, want := range map[string]string{
		"npm start":                              "npm",
		".venv/bin/uvicorn app:app --port $PORT": ".venv/bin/uvicorn",
		"exec node server.js":                    "exec",
		// Only a simple command is checked: logs shows why another doesn't start.
		"NODE_ENV=production node .":      "",
		"cd web && npm start":             "",
		"npm run build; npm start":        "",
		"python -m http.server | tee log": "",
		"(cd web; npm start)":             "",
		"npm start &":                     "",
		"`which node` .":                  "",
		"npm ci\nnpm start":               "",
		`"my app" --port 1`:               "",
		"$HOME/bin/serve":                 "",
		"~/bin/serve":                     "",
		"":                                "",
	} {
		if got := commandWord(run); got != want {
			t.Errorf("%q: %q, want %q", run, got, want)
		}
	}
	// Each login shell reads the question alike.
	for shell, rc := range map[string]string{"/bin/sh": "", "/bin/zsh": "~/.zshrc", "/bin/bash": "~/.bashrc", "fish": ""} {
		path, err := exec.LookPath(shell)
		if err != nil {
			continue
		}
		t.Setenv("SHELL", path)
		if c := commandFoundCheck(App{Slug: "coach", Run: "sh -c true", Dir: t.TempDir()}); c != nil {
			t.Errorf("%s, sh: %+v", shell, c)
		}
		c := commandFoundCheck(App{Slug: "coach", Run: "uvicorn-not-here", Dir: t.TempDir()})
		if c == nil {
			t.Errorf("%s, missing: found", shell)
		} else if rc != "" && (!strings.Contains(c.Message, "(not what "+rc+" sets up)") || !strings.Contains(c.Fix, "PATH set only in "+rc+" ")) {
			t.Errorf("%s, missing: the warning doesn't name %s: %+v", shell, rc, c)
		} else if rc == "" && (strings.Contains(c.Message, "(not what") || !strings.Contains(c.Fix, "PATH that only a terminal's shell sets up ")) {
			t.Errorf("%s, missing: the warning names an rc file: %+v", shell, c)
		}
	}
	t.Setenv("SHELL", "/bin/sh")
	dir := t.TempDir()
	c := commandFoundCheck(App{Slug: "coach", Run: "uvicorn-not-here app:app", Dir: dir})
	if c == nil || !strings.Contains(c.Message, "uvicorn-not-here isn't found in "+dir+" with the PATH") || !strings.HasPrefix(c.Fix, "If a terminal finds uvicorn-not-here through PATH") ||
		!strings.Contains(c.Fix, "uv run uvicorn-not-here") {
		t.Errorf("missing: %+v", c)
	}
	// Node and Python themselves are no project's tools.
	if c := commandFoundCheck(App{Slug: "coach", Run: "npm start", Dir: dir}); c != nil && strings.Contains(c.Fix, "uv run") {
		t.Errorf("npm: %+v", c)
	}
	if c := commandFoundCheck(App{Slug: "coach", Run: "true && uvicorn-not-here app:app", Dir: dir}); c != nil {
		t.Errorf("a compound command: %+v", c)
	}
	os.WriteFile(filepath.Join(dir, "serve"), []byte("#!/bin/sh\n"), 0o755)
	if c := commandFoundCheck(App{Slug: "coach", Run: "./serve", Dir: dir}); c != nil {
		t.Errorf("./serve in the app's dir: %+v", c)
	}
	// A path gets no advice about PATH.
	c = commandFoundCheck(App{Slug: "coach", Run: ".venv/bin/uvicorn app:app", Dir: dir})
	if c == nil || !strings.Contains(c.Message, "no executable at "+filepath.Join(dir, ".venv/bin/uvicorn")) || strings.Contains(c.Fix, "npx") {
		t.Errorf(".venv/bin/uvicorn: %+v", c)
	}
	// On this PATH only, as an activated virtual environment or nvm puts it: launchd's isn't.
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	if c := commandFoundCheck(App{Slug: "coach", Run: "serve", Dir: t.TempDir()}); c == nil || !strings.Contains(c.Fix, "PATH=<dir>:$PATH") {
		t.Errorf("serve on the caller's PATH: %+v", c)
	}
}

// Taking an app's command away stops the connector's copy, so publish checks the app
// after the reload, not before.
func TestPublishWithoutACommandChecksAfterTheReload(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	// A socket path has to be short, shorter than t.TempDir's.
	state, err := os.MkdirTemp("", "ol")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(state) })
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: state}
	if err := (&Config{Apps: []App{{Name: "Coach", Slug: "coach", Port: port, Run: "npm start", Dir: dir}}}).Save(p.config); err != nil {
		t.Fatal(err)
	}
	control, err := listenControl(socketPath(state))
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	go func() {
		for {
			conn, err := control.Accept()
			if err != nil {
				return
			}
			var req controlRequest
			json.NewDecoder(conn).Decode(&req)
			if req.Cmd == "reload" {
				ln.Close() // the connector stops its copy
			}
			json.NewEncoder(conn).Encode(controlReply{OK: true, Apps: []NodeStatus{{appInfo: appInfo{Slug: "coach"}, State: "serving"}}})
			conn.Close()
		}
	}()
	res, err := publishApp(p, App{Name: "Coach", Slug: "coach", Port: port}, new(""), nil, false)
	if err != nil {
		t.Fatal(err)
	}
	if c := findCheck(t, res.Problems, "port-listening"); c.Status != statusWarn || !strings.Contains(c.Message, "the connector no longer runs Coach's command") {
		t.Errorf("port-listening %+v", c)
	}
}

// The MCP tools refuse arguments they don't take, rather than ignoring them.
func TestMCPRefusesUnknownArguments(t *testing.T) {
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: filepath.Join(dir, "state")}
	for tool, c := range map[string]struct{ args, want string }{
		"publish": {`{"port": 4317, "name": "Coach", "command": "npm start"}`, `publish takes only name, port, slug, not "command". This server can't set the command`},
		"logs":    {`{"slug": "coach", "port": 1}`, `logs takes only lines, slug, not "port"`},
		"guide":   {`{"verbose": true}`, `guide takes no arguments, not "verbose"`},
	} {
		_, err := callTool(tool, json.RawMessage(c.args), p)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v", tool, err)
		}
	}
	if _, err := callTool("publish", json.RawMessage(`{"port": 4317, "name": "Coach", "cmd": "x"}`), p); err == nil || !strings.Contains(err.Error(), "--slug coach --dir '<folder>' --run '<command>'") {
		t.Errorf("publish cmd: %v", err)
	}
	if _, err := callTool("nope", nil, p); !errors.Is(err, errUnknownTool) {
		t.Errorf("an unknown tool: %v", err)
	}
	if _, err := os.Stat(p.config); !errors.Is(err, fs.ErrNotExist) {
		t.Error("a refused publish wrote the config")
	}
}

// Doctor checks the connector even with no apps published.
func TestDoctorWithoutApps(t *testing.T) {
	dir := t.TempDir()
	checks := runDoctor(&Config{}, filepath.Join(dir, "state"), filepath.Join(dir, "config.json"))
	if c := findCheck(t, checks, "daemon"); c.Status != statusFail || c.Actor != actorPerson {
		t.Errorf("daemon: %+v", c)
	}
	if c := findCheck(t, checks, "apps"); c.Status != statusWarn {
		t.Errorf("apps: %+v", c)
	}
}

// The bind fix names the flag in the app's own command that gives every address.
func TestWildcardFlag(t *testing.T) {
	for run, want := range map[string]string{
		"flask run --host 0.0.0.0 --port $PORT":   "--host 0.0.0.0",
		"next dev -H 0.0.0.0 -p $PORT":            "-H 0.0.0.0",
		`gunicorn --bind "0.0.0.0:$PORT" app:app`: `--bind "0.0.0.0:$PORT"`,
		"uvicorn app:app --host=:: --port $PORT":  "--host=::",
		"vite --host 127.0.0.1":                   "",
		"vite --host":                             "",
		"npm start":                               "",
	} {
		if got := wildcardFlag(run); got != want {
			t.Errorf("%q: %q, want %q", run, got, want)
		}
	}
}

// A flag with nothing after it is what a $PORT in double quotes leaves of itself.
func TestEmptyFlagCheck(t *testing.T) {
	for run, flag := range map[string]string{
		"node s.js --port":                      "--port",
		"vite --port --strictPort":              "--port",
		"next start -p -H 127.0.0.1":            "-p",
		"uvicorn app:app --port=":               "--port",
		"python manage.py runserver 127.0.0.1:": "the address 127.0.0.1:",
		"gunicorn --bind 127.0.0.1: app:app":    "the address 127.0.0.1:",
		"gunicorn --bind=0.0.0.0: app:app":      "the address 0.0.0.0:",
		"gunicorn -b 127.0.0.1:8000 app:app":    "",
		"node s.js --port $PORT":                "",
		"vite --port 5173 --strictPort":         "",
		"next start -H 127.0.0.1 -p 3000":       "",
		"npm start":                             "",
	} {
		c := emptyFlagCheck(App{Slug: "coach", Run: run})
		if flag == "" {
			if c != nil {
				t.Errorf("%q: %+v", run, c)
			}
			continue
		}
		if c == nil || !strings.Contains(c.Message, flag+" has no value") || !strings.Contains(c.Fix, "single quotes") {
			t.Errorf("%q: %+v", run, c)
		}
	}
}

// Two apps an older config put on one port can still be updated, and doctor names them;
// no app moves onto another's port.
func TestAppsSharingAPort(t *testing.T) {
	cfg := &Config{Apps: []App{{Name: "Coach", Slug: "coach", Port: 4317}, {Name: "Notes", Slug: "notes", Port: 4317}, {Name: "Lists", Slug: "lists", Port: 4318}}}
	if err := portTaken(cfg, App{Name: "Notes 2", Slug: "notes", Port: 4317}); err != nil {
		t.Errorf("republishing: %v", err)
	}
	cfg.Apps[0].Run = "npm start"
	if err := portTaken(cfg, App{Name: "Notes", Slug: "notes", Port: 4317}); err != nil {
		t.Errorf("republishing without a command: %v", err)
	}
	if err := portTaken(cfg, App{Name: "Notes", Slug: "notes", Port: 4317, Run: "npm start"}); err == nil || !strings.Contains(err.Error(), "two apps on one port") {
		t.Errorf("a second command: %v", err)
	}
	cfg.Apps[0].Run = ""
	for _, app := range []App{{Slug: "new", Port: 4317}, {Slug: "lists", Port: 4317}} {
		if err := portTaken(cfg, app); err == nil {
			t.Errorf("%s onto 4317: no error", app.Slug)
		}
	}
	checks := sharedPortChecks(cfg)
	if len(checks) != 1 || checks[0].Status != statusWarn || !strings.Contains(checks[0].Message, "Coach (coach) and Notes (notes)") {
		t.Errorf("%+v", checks)
	}
}

// With the connector down, check and doctor find a copy started by hand, as publish does.
func TestHeldByOtherWhileTheConnectorIsDown(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	app := App{Name: "Coach", Slug: "coach", Port: ln.Addr().(*net.TCPAddr).Port, Run: "npm start"}
	if c := heldByOther(app, nil, true); c == nil || c.Status != statusFail || !strings.Contains(c.Message, fmt.Sprintf("listens on 127.0.0.1:%d", app.Port)) {
		t.Errorf("down: %+v", c)
	}
	if c := heldByOther(app, nil, false); c != nil {
		t.Errorf("no status, though not down: %+v", c)
	}
}

// While the connector has never read the login shell's environment, status and doctor
// say why, and what the person does about it; once it has, they name the apps still
// running with the connector's own until they restart.
func TestLoginEnvFailureIsReported(t *testing.T) {
	t.Setenv("SHELL", "/bin/sh")
	state, err := os.MkdirTemp("", "ol")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(state) })
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: state}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	cfg := &Config{Apps: []App{{Name: "Coach", Slug: "coach", Port: port, Run: "npm start", Dir: dir}}}
	if err := cfg.Save(p.config); err != nil {
		t.Fatal(err)
	}
	control, err := listenControl(socketPath(state))
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	const why = "/bin/zsh took over 10s to start"
	var reply atomic.Pointer[controlReply]
	reply.Store(&controlReply{OK: true, EnvError: why, Processes: map[string]ProcessStatus{}})
	go func() {
		for {
			conn, err := control.Accept()
			if err != nil {
				return
			}
			json.NewDecoder(conn).Decode(&controlRequest{})
			json.NewEncoder(conn).Encode(reply.Load())
			conn.Close()
		}
	}()
	status := func() string {
		t.Helper()
		stdout := os.Stdout
		f, _ := os.Create(filepath.Join(dir, "out"))
		os.Stdout = f
		err := cmdStatus([]string{"--config", p.config, "--state", p.state})
		os.Stdout = stdout
		f.Close()
		text, _ := os.ReadFile(f.Name())
		if err != nil {
			t.Error(err)
		}
		return string(text)
	}
	const fix = "Make ~/.profile finish within 10 s, without `exit` or `exec`ing another shell, then run ovenlight restart coach."
	if out, err := collectStatus(p); err != nil || out.LoginEnvError != why {
		t.Errorf("status: %+v, %v", out, err)
	}
	if text := status(); !strings.Contains(text, "the login shell's can't be read: "+why+"\n  Fix (the person): "+fix) {
		t.Errorf("status text:\n%s", text)
	}
	if c := findCheck(t, runDoctor(cfg, p.state, p.config), "login-environment"); c.Status != statusWarn || c.Actor != actorPerson || !strings.HasSuffix(c.Message, why) || c.Fix != fix {
		t.Errorf("doctor: %+v", c)
	}

	reply.Store(&controlReply{OK: true, Processes: map[string]ProcessStatus{"coach": {Running: true, PID: 1, EnvError: why}}})
	if text := status(); !strings.Contains(text, "the command of coach started while it couldn't be, and still runs with the connector's own, PATH included: "+why+"\n  Fix: ovenlight restart coach\n") {
		t.Errorf("status text, healed:\n%s", text)
	}
	if c := findCheck(t, runDoctor(cfg, p.state, p.config), "login-environment"); c.Actor != actorAgent || c.Fix != "ovenlight restart coach" {
		t.Errorf("doctor, healed: %+v", c)
	}
}

// A connector from before --run, still running after the command was upgraded, reports
// no processes and doesn't know restart: check, doctor, status, publish and restart say it
// doesn't run commands, and how to restart it, without pointing at the app's output or
// at install.sh for a connector on other paths than the installed one's.
func TestConnectorFromBeforeRun(t *testing.T) {
	t.Setenv("SHELL", "/bin/sh")
	t.Setenv("HOME", t.TempDir())
	state, err := os.MkdirTemp("", "ol")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(state) })
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: state}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	app := App{Name: "Coach", Slug: "coach", Port: port, Run: "exec sleep 30", Dir: dir}
	cfg := &Config{Apps: []App{app}}
	if err := cfg.Save(p.config); err != nil {
		t.Fatal(err)
	}
	control, err := listenControl(socketPath(state))
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	go func() {
		for {
			conn, err := control.Accept()
			if err != nil {
				return
			}
			var req controlRequest
			json.NewDecoder(conn).Decode(&req)
			reply := map[string]any{"ok": true, "pid": 1, "version": "devel", "apps": []map[string]string{{"slug": "coach", "state": "serving"}}}
			if req.Cmd == "restart" {
				reply = map[string]any{"error": `unknown command "restart"`}
			}
			json.NewEncoder(conn).Encode(reply)
			conn.Close()
		}
	}()
	const old = "the running connector predates --run and doesn't run commands"
	fix := restartFix(p)
	if strings.Contains(fix, "install.sh again") || !strings.Contains(restartFix(&paths{config: defaultConfigPath(), state: defaultStateDir()}), "install.sh again") {
		t.Errorf("restartFix for other paths: %s", fix)
	}
	predates := func(name string, checks []Check) {
		t.Helper()
		if c := findCheck(t, checks, "connector-version"); c.Status != statusFail || c.Actor != actorPerson || !strings.HasPrefix(c.Message, old) || c.Fix != fix {
			t.Errorf("%s: %+v", name, c)
		}
		if c := findID(checks, "port-listening"); c != nil && strings.Contains(c.Fix, "ovenlight logs") {
			t.Errorf("%s: port-listening %+v", name, c)
		}
	}
	checks, err := checkPublished(p, "coach")
	if err != nil {
		t.Fatal(err)
	}
	predates("check", checks)
	predates("doctor", runDoctor(cfg, p.state, p.config))
	if out, err := collectStatus(p); err != nil || !out.PredatesRun {
		t.Errorf("status: %+v, %v", out, err)
	}
	stdout := os.Stdout
	f, _ := os.Create(filepath.Join(dir, "out"))
	os.Stdout = f
	err = cmdStatus([]string{"--config", p.config, "--state", p.state})
	os.Stdout = stdout
	f.Close()
	if text, _ := os.ReadFile(f.Name()); err != nil || !strings.Contains(string(text), "The running connector predates --run and doesn't run commands; restart it.\n  Fix (the person): "+fix) {
		t.Errorf("status text: %v\n%s", err, text)
	}
	res, err := publishApp(p, app, nil, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	predates("publish", res.Problems)
	if _, err := restartProcess(p, "coach"); err == nil || err.Error() != old+"; restart it. "+fix {
		t.Errorf("restart: %v", err)
	}
}

// A unix socket's path has a limit, which a long --state would pass.
func TestDaemonRefusesALongSocketPath(t *testing.T) {
	state := filepath.Join(t.TempDir(), strings.Repeat("s", 100))
	err := runDaemon(filepath.Join(state, "config.json"), state, devOptions{})
	want := fmt.Sprintf("the state directory's path is too long for its control socket (%d bytes, at most %d); use a shorter --state", len(socketPath(state)), maxSocketPath())
	if err == nil || err.Error() != want {
		t.Errorf("err = %v", err)
	}
	if _, err := os.Stat(state); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("made the state directory: %v", err)
	}
}
