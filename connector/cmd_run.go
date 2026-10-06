package main

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"
)

// The CLI and MCP side of apps the connector runs (supervise.go): publish's --run and
// --dir, restarting them, and reading their output.

// resolveRun applies publish's --run and --dir, each nil when not given, to the app's
// command (existing is the zero App for a new app). The command runs in dir when it is
// given; else an app keeps the directory it runs in, and an app's first command runs in
// the current directory. An empty run removes the command.
func resolveRun(existing App, run, dir *string) (string, string, error) {
	cmd, cwd := existing.Run, existing.Dir
	if run != nil {
		cmd = strings.TrimSpace(*run)
	}
	if cmd == "" {
		if dir != nil && *dir != "" {
			return "", "", errors.New("--dir is where the app's command runs, and it has none: give one with --run")
		}
		return "", "", nil
	}
	if dir != nil || cwd == "" {
		path := ""
		if dir != nil {
			path = *dir
		}
		abs, err := filepath.Abs(path) // "" is the current directory
		if err != nil {
			return "", "", err
		}
		if info, err := os.Stat(abs); err != nil {
			return "", "", err
		} else if !info.IsDir() {
			return "", "", fmt.Errorf("%s isn't a directory", abs)
		}
		cwd = abs
	}
	return cmd, cwd, nil
}

// protectedFolder names the folder macOS protects that dir is in, such as Desktop, or is
// "" when it is in none. Before the connector, a LaunchAgent, can use one, macOS asks on
// the Mac's screen, and the app's command doesn't start until the person answers.
func protectedFolder(dir string) string {
	if runtime.GOOS != "darwin" || dir == "" {
		return ""
	}
	home, _ := os.UserHomeDir()
	folders := []struct{ path, name string }{
		{filepath.Join(home, "Desktop"), "Desktop"},
		{filepath.Join(home, "Documents"), "Documents"},
		{filepath.Join(home, "Downloads"), "Downloads"},
		{filepath.Join(home, "Library", "Mobile Documents"), "iCloud Drive"},
		{filepath.Join(home, "Library", "CloudStorage"), "a cloud storage folder"},
		{"/Volumes", "a volume other than the startup disk"},
	}
	for _, f := range folders {
		if inFolder(dir, f.path) {
			return f.name
		}
	}
	return ""
}

// inFolder reports whether dir is folder or in it, as given or with symbolic links
// resolved, such as /tmp to /private/tmp.
func inFolder(dir, folder string) bool {
	if real, err := filepath.EvalSymlinks(dir); err == nil {
		dir = real
	}
	real, err := filepath.EvalSymlinks(folder)
	if err != nil {
		real = folder
	}
	for _, f := range []string{folder, real} {
		// The Mac's file system ignores case, as does macOS asking.
		rel, err := filepath.Rel(strings.ToLower(f), strings.ToLower(dir))
		if err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return true
		}
	}
	return false
}

// temporaryFolder reports whether dir is in a folder macOS empties when the Mac
// restarts: /tmp or the user's own temporary folder, $TMPDIR.
func temporaryFolder(dir string) bool {
	if runtime.GOOS != "darwin" || dir == "" {
		return false
	}
	for _, folder := range []string{"/tmp", os.TempDir()} { // /tmp is /private/tmp
		if inFolder(dir, folder) {
			return true
		}
	}
	return false
}

// temporaryFolderCheck warns when the app's command runs in a temporary folder.
func temporaryFolderCheck(app App) *Check {
	if app.Run == "" || !temporaryFolder(app.Dir) {
		return nil
	}
	return &Check{ID: "temporary-folder", App: app.Slug, Status: statusWarn, Actor: actorAgent,
		Message: fmt.Sprintf("%s runs in %s, a temporary folder, so the app and its data go when the Mac restarts and macOS clears its temporary folders", app.Name, app.Dir),
		Fix:     "Keep the project in a lasting folder, such as ~/src, and publish it from there."}
}

// protectedFolderCheck warns when the app's command runs in a folder macOS protects.
func protectedFolderCheck(app App) *Check {
	folder := protectedFolder(app.Dir)
	if app.Run == "" || folder == "" {
		return nil
	}
	return &Check{ID: "protected-folder", App: app.Slug, Status: statusWarn, Actor: actorPerson,
		Message: fmt.Sprintf("%s runs in %s, in %s, so macOS asks on this Mac's screen to let the connector use that folder, and the app doesn't start until the person clicks Allow", app.Name, app.Dir, folder),
		Fix: "Click Allow when macOS asks; after Don't Allow, turn the connector on in System Settings > Privacy & Security > Files and Folders. " +
			"Keeping the project elsewhere, such as in ~/src, avoids the question."}
}

// commandWord is the program the command starts when it is one simple command, its first
// word, or "" when it isn't: it has ;, &, |, (, a backtick or a newline, starts with an
// assignment, or the shell would expand or unquote its first word.
func commandWord(run string) string {
	fields := strings.Fields(run)
	if len(fields) == 0 || strings.ContainsAny(run, ";&|()`\n") || strings.ContainsAny(fields[0], `=$"'\~*?[{`) {
		return ""
	}
	return fields[0]
}

// commandFoundCheck warns when the program a simple command starts (see commandWord)
// isn't found in the app's directory in the environment the connector runs it with, the
// login shell's: a tool a terminal finds through an activated virtual environment,
// node_modules/.bin or a PATH set in .zshrc or .bashrc, which a login shell doesn't
// read. It reads that environment as the connector does, from the environment launchd
// or systemd gives the connector, not the caller's, which has all of those. Other
// commands go unchecked; the app's output in logs says what didn't start.
func commandFoundCheck(app App) *Check {
	word := commandWord(app.Run)
	if word == "" {
		return nil
	}
	home, _ := os.UserHomeDir()
	env := []string{"HOME=" + home, "USER=" + os.Getenv("USER"), "LOGNAME=" + os.Getenv("LOGNAME"), "SHELL=" + loginShell(),
		"TMPDIR=" + os.TempDir(), "PATH=/usr/bin:/bin:/usr/sbin:/sbin"}
	env, err := loginEnv(env)
	if err != nil {
		return nil // the connector falls back to its own environment, and the log says why
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// sh runs the command; the word goes in single quotes, which commandWord leaves out of it.
	cmd := exec.CommandContext(ctx, "/bin/sh", "-c", "command -v '"+word+"'")
	cmd.Dir = app.Dir
	cmd.Env = env
	var exitErr *exec.ExitError
	if err := cmd.Run(); ctx.Err() != nil || !errors.As(err, &exitErr) {
		return nil // found, or the shell couldn't tell
	}
	if strings.Contains(word, "/") { // a path, which PATH doesn't come into
		path := word
		if !filepath.IsAbs(path) {
			path = filepath.Join(app.Dir, path)
		}
		return &Check{ID: "command-not-found", App: app.Slug, Status: statusWarn, Actor: actorAgent,
			Message: fmt.Sprintf("there's no executable at %s, so the app's command may not start", path),
			Fix:     fmt.Sprintf("Check the path (a relative one starts from %s) and that what it names exists, such as a virtual environment, then publish again with the corrected --run.", app.Dir)}
	}
	where, leftOut := "that only a terminal's shell sets up", ""
	if rc := rcName(loginShell()); rc != "" {
		where, leftOut = "set only in "+rc, " (not what "+rc+" sets up)"
	}
	fix := fmt.Sprintf("If a terminal finds %s through PATH %s (nvm, pyenv), put its directory first on PATH in the command: --run 'PATH=<dir>:$PATH <command>', with <dir> from command -v %s. ", word, where, word)
	if !slices.Contains([]string{"node", "npm", "npx", "python", "python3"}, word) {
		fix += fmt.Sprintf("If it is a project's own tool, run it as .venv/bin/%s or uv run %s (Python), or through npx or an npm script (Node). ", word, word)
	}
	return &Check{ID: "command-not-found", App: app.Slug, Status: statusWarn, Actor: actorAgent,
		Message: fmt.Sprintf("%s isn't found in %s with the PATH the connector runs the command with, the one your login shell sets%s, so the app's command may not start", word, app.Dir, leftOut),
		Fix:     fix + "Then publish again with the corrected --run."}
}

// valueFlags are the flags a server's command gives the port or the address with.
var valueFlags = []string{"--port", "--host", "-p", "-H", "--bind"}

// emptyPort matches an address whose port is missing, as in 127.0.0.1: or
// --bind=0.0.0.0:, which is what "127.0.0.1:$PORT" in double quotes leaves.
var emptyPort = regexp.MustCompile(`^(?:-[-\w]*=)?((?:\d{1,3}(?:\.\d{1,3}){3}|localhost|\[[0-9A-Fa-f:]*\]):)$`)

// emptyFlagCheck warns when the command has one of valueFlags with no value: at the end,
// followed by another flag, or as --port= with nothing after it, or an address with no
// port after its colon. That is what a --run in double quotes leaves of $PORT when the
// shell that ran publish has none.
func emptyFlagCheck(app App) *Check {
	words := strings.Fields(app.Run)
	for i, word := range words {
		name, empty := strings.CutSuffix(word, "=")
		if m := emptyPort.FindStringSubmatch(word); m != nil {
			name = "the address " + m[1]
		} else if !slices.Contains(valueFlags, name) || (!empty && i+1 < len(words) && !strings.HasPrefix(words[i+1], "-")) {
			continue
		}
		return &Check{ID: "flag-value", App: app.Slug, Status: statusWarn, Actor: actorAgent,
			Message: fmt.Sprintf("in the command `%s`, %s has no value, which is what a $PORT in double quotes leaves when the shell that ran publish has none", app.Run, name),
			Fix:     fmt.Sprintf("Publish again with the command in single quotes, so the connector's shell fills in $PORT when it starts the app: ovenlight publish --slug %s --run '<command>'", app.Slug)}
	}
	return nil
}

// startWait is how long publish and restart give an app the connector just started to
// open its port, so they don't report it as down while it boots.
var startWait = 15 * time.Second

// waitListening waits up to startWait for the app the connector started at began to
// listen on its port, or for the connector to say its command ended. A listener on every
// address that was there before the start (before), such as AirPlay Receiver's on 5000,
// answers on 127.0.0.1 too, but isn't the app.
func waitListening(p *paths, app App, before []listener, began time.Time) {
	for deadline := time.Now().Add(startWait); time.Now().Before(deadline); time.Sleep(250 * time.Millisecond) {
		all, err := listeners(app.Port)
		if accepts("127.0.0.1", app.Port) && (err != nil || len(all) == 0 || slices.ContainsFunc(all, func(l listener) bool { return l.host() != "*" || !slices.Contains(before, l) })) {
			return
		}
		if status, err := callDaemon(p.state, "status", 10*time.Second); err == nil && status.Processes[app.Slug].LastExitAt.After(began) {
			return
		}
	}
}

// heldBy is the first listener outside the connector's copy of an app (ours says which
// processes are in it) that listens where the copy would: on 127.0.0.1, on ::1 while the
// copy doesn't listen on 127.0.0.1 (one that binds localhost fails on ::1 too), or on
// every address while nothing listens on 127.0.0.1, but AirPlay Receiver. Failing that,
// it is one on ::1 beside the copy, with beside set: macOS lets the copy bind 127.0.0.1
// beside it, but a browser on the Mac reaches it at localhost. It is nil when there is
// neither.
func heldBy(all []listener, ours func(pid int) bool) (held *listener, beside bool) {
	loopback := slices.ContainsFunc(all, func(l listener) bool { return l.host() == "127.0.0.1" })
	copyUp := slices.ContainsFunc(all, func(l listener) bool { return l.host() == "127.0.0.1" && ours(l.pid) })
	for _, l := range all {
		// AirPlay Receiver doesn't keep the copy from binding 127.0.0.1 beside it, with
		// SO_REUSEADDR, as Go, Node and Python do; portChoiceCheck warns of it.
		if ours(l.pid) || airPlay(l) {
			continue
		}
		switch host := l.host(); {
		case host == "127.0.0.1" || (host == "::1" && !copyUp) || (host == "*" && !loopback):
			return &l, false
		case host == "::1" && held == nil:
			held = &l
		}
	}
	return held, held != nil
}

// portHeldCheck fails when something outside the connector's copy of an app it runs, and
// the copy's descendants, holds the app's port (see heldBy), such as the app started in a
// terminal: the copy can't start while it does. It warns of one on ::1 beside the copy
// (see besideCopyCheck). status is the connector's status reply, nil when it isn't running.
func portHeldCheck(app App, status *controlReply) *Check {
	group := 0 // the process group of the connector's copy, which its leader's pid names
	if status != nil && status.Processes[app.Slug].Running {
		group = status.Processes[app.Slug].PID
	}
	all, _ := listeners(app.Port)
	what, where := "", net.JoinHostPort("127.0.0.1", strconv.Itoa(app.Port))
	l, beside := heldBy(all, func(pid int) bool {
		return pid > 0 && (processGroup(pid) == -1 || inCopy(pid, group)) // -1: it has just exited
	})
	if beside {
		return besideCopyCheck(app, *l)
	} else if l != nil && l.pid == 0 {
		what, where = "another user's process", l.addr // Linux names no pid it can't look into
	} else if l != nil {
		what, where = fmt.Sprintf("%s (pid %d)", cmp.Or(clip(l.name, 40), "a process"), l.pid), l.addr
	} else if len(all) == 0 && group == 0 && accepts("127.0.0.1", app.Port) {
		// lsof can't see it, such as a process of another user: only the connector's copy
		// can be ours.
		what = "something else"
	}
	if what == "" {
		return nil
	}
	other := "give this app a port of its own"
	if app.Shareable {
		other = "ask the person to give this app a port of its own, in a terminal, since an agent can't move a shareable app"
	}
	fix := "If it's a copy you started, stop it by that pid (never pkill by name); if it's the person's own copy, ask them to stop it (Ctrl-C in its terminal); " +
		"if it's another program, " + other + ": ovenlight publish --slug " + app.Slug + " --port <n>"
	if l != nil && l.pid == 0 {
		fix = "It isn't this user's to stop, so " + other + ": ovenlight publish --slug " + app.Slug + " --port <n>"
	}
	c := &Check{ID: "port-in-use", App: app.Slug, Status: statusFail, Actor: actorAgent,
		Message: fmt.Sprintf("%s already listens on %s, so the connector's copy of %s can't start while it does", what, where, app.Name),
		Fix:     fmt.Sprintf("%s. Once nothing else holds the port, the connector starts its own within a minute; if it doesn't, ovenlight logs %s shows why.", fix, app.Slug)}
	if status == nil {
		c.Fix = fix + ", so the connector's own can start once the connector runs."
	}
	return c
}

// besideCopyCheck warns of l, a listener on ::1 beside the connector's copy of app on
// 127.0.0.1, most likely the person's own copy started in a terminal, which binds
// localhost: both copies run, sharing the app's data, and a browser on the Mac reaches
// that one.
func besideCopyCheck(app App, l listener) *Check {
	what := fmt.Sprintf("%s (pid %d)", cmp.Or(clip(l.name, 40), "a process"), l.pid)
	fix := "If it's the person's own copy, ask them to stop it (Ctrl-C in its terminal), since two copies share the data file; " +
		"if it's a copy you started, stop it by that pid (never pkill by name)."
	if l.pid == 0 { // Linux names no pid it can't look into
		what, fix = "another user's process", "It isn't this user's to stop: ask the person who runs it to, since two copies share the data file."
	}
	return &Check{ID: "port-beside-copy", App: app.Slug, Status: statusWarn, Actor: actorAgent,
		Message: fmt.Sprintf("%s also listens on %s beside the connector's copy of %s; a browser on this computer reaches it at localhost:%d",
			what, l.addr, app.Name, app.Port),
		Fix: fix}
}

// portChoiceCheck warns of a port another program listens on too, on every address:
// 5000 and 7000, where macOS's AirPlay Receiver (ControlCenter) does, or one where a
// program other than the app does (see appListeners).
func portChoiceCheck(app App) *Check {
	all, _ := listeners(app.Port)
	_, others := appListeners(all)
	what := "macOS's AirPlay Receiver (ControlCenter) listens on every address while it is on"
	if i := slices.IndexFunc(others, func(l listener) bool { return l.host() == "*" && !airPlay(l) }); i >= 0 {
		what = fmt.Sprintf("%s (pid %d) listens on %s too", cmp.Or(clip(others[i].name, 40), "a process"), others[i].pid, others[i].addr)
	} else if app.Port != 5000 && app.Port != 7000 && !slices.ContainsFunc(others, airPlay) {
		return nil
	}
	return &Check{ID: "port-choice", App: app.Slug, Status: statusWarn, Actor: actorAgent, Fix: otherPortFix(app),
		Message: fmt.Sprintf("on port %d, %s, so an app that binds every address can't start there, and while the app is down the phone reaches that program instead", app.Port, what)}
}

// otherPortFix moves the app to a port of its own.
func otherPortFix(app App) string {
	fix := fmt.Sprintf("Pick another port, such as one from 20000 to 29999 as ovenlight new does, and publish with it: ovenlight publish --slug %s --port <n>", app.Slug)
	if app.Run == "" {
		fix += ", with the app listening there"
	}
	return fix + "."
}

// withPortChoice adds portChoiceCheck to an app's checks, for check and doctor, unless
// they say already that AirPlay Receiver answers on its port.
func withPortChoice(checks []Check, app App) []Check {
	if c := findID(checks, "port-listening"); c != nil && strings.Contains(c.Message, "AirPlay Receiver") {
		return checks
	}
	if c := portChoiceCheck(app); c != nil {
		checks = append(checks, *c)
	}
	return checks
}

// stillStarting makes a failing port-listening among checks a warning while the copy the
// connector started at began or later runs without having restarted, as one does while
// it builds before it serves (npm run build && next start). When the copy ended since,
// the failure says how. status is the connector's status reply, nil when it didn't give
// one.
func stillStarting(checks []Check, app App, status *controlReply, began time.Time) {
	if status == nil {
		return
	}
	pr := status.Processes[app.Slug]
	for i, c := range checks {
		if c.ID != "port-listening" || c.Status != statusFail {
			continue
		}
		switch {
		case pr.LastExit != "" && pr.LastExitAt.After(began):
			checks[i].Message = fmt.Sprintf("%s: its command ended (%s), and the connector starts it again after a wait that grows each time", c.Message, pr.LastExit)
		case pr.Running && pr.Restarts == 0 && !pr.StartedAt.Before(began):
			checks[i].Status = statusWarn
			checks[i].Message = fmt.Sprintf("%s is still starting (pid %d): %s", app.Name, pr.PID, c.Message)
			checks[i].Fix = fmt.Sprintf("Run `ovenlight check %s` once `ovenlight logs %s` shows it listening.", app.Slug, app.Slug)
		}
	}
}

// inCopy reports whether pid is in the connector's copy of an app whose leader is leader:
// in its process group, which the leader's pid names, or descended from the leader.
func inCopy(pid, leader int) bool {
	return processGroup(pid) == leader || descends(pid, leader)
}

// descends reports whether pid is a descendant of leader, such as a server the app's
// command started in a process group of its own. A process whose parent exited is
// adopted by launchd (pid 1), so the chain no longer reaches the leader.
func descends(pid, leader int) bool {
	if leader <= 1 {
		return false
	}
	for range 32 {
		if pid = parentPID(pid); pid <= 1 {
			return false
		}
		if pid == leader {
			return true
		}
	}
	return false
}

// restartResult is what restarting an app did, for the CLI and the MCP tool.
type restartResult struct {
	Restarted string  `json:"restarted"`
	Problems  []Check `json:"problems,omitempty"`
}

// restartProcess has the connector stop the app's process and start it again, then
// checks it (see restartChecks).
func restartProcess(p *paths, slug string) (*restartResult, error) {
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return nil, err
	}
	app, err := cfg.Published(slug)
	switch {
	case err != nil:
		return nil, err
	case app.Run == "":
		return nil, fmt.Errorf("the connector doesn't run %s, so it has nothing to restart. %s", app.Name, runNote(p, slug))
	}
	began := time.Now()
	before, _ := listeners(app.Port) // what listens before the copy starts
	// Stopping waits up to twice the grace for the old process to end.
	if _, err := callDaemonWith(p.state, controlRequest{Cmd: "restart", Slug: slug}, time.Minute); err != nil {
		switch {
		case errors.Is(err, errDaemonDown):
			return nil, fmt.Errorf("the Ovenlight connector isn't running, so it runs no apps. %s", startFix(p))
		case err.Error() == `unknown command "restart"`:
			return nil, fmt.Errorf("%s; restart it. %s", predatesRunMessage, restartFix(p))
		}
		return nil, err
	}
	waitListening(p, app, before, began)
	res := &restartResult{Restarted: slug}
	status, err := callDaemon(p.state, "status", 10*time.Second)
	if err != nil {
		status = nil
	} else if c := portHeldCheck(app, status); c != nil {
		res.Problems = append(res.Problems, *c)
	}
	for _, c := range restartChecks(app) {
		if c.Status != statusOK {
			res.Problems = append(res.Problems, c)
		}
	}
	stillStarting(res.Problems, app, status, began)
	return res, nil
}

// restartChecks are the checks of an app back from a restart: whether it answers and,
// once it does, whether it serves its folder's files or lets other websites read it,
// which a change to its code can do.
func restartChecks(app App) []Check {
	checks := withPortChoice(appChecks(app, ""), app)
	if up := findID(checks, "upstream"); !reached(checks) || up != nil && up.unanswered != "" {
		return checks
	}
	page, body, _ := fetchHome(app, "")
	for _, c := range []Check{servedFilesCheck(app, "", page, body), corsCheck(app)} {
		c.App = app.Slug
		checks = append(checks, c)
	}
	return checks
}

func cmdRestart(args []string) error {
	fs, p := newFlags("restart")
	pos, err := parseArgs(fs, args, 1)
	if err != nil {
		return err
	}
	if len(pos) != 1 {
		return errors.New("usage: ovenlight restart <slug>")
	}
	res, err := restartProcess(p, pos[0])
	if err != nil {
		return err
	}
	fmt.Printf("Restarted %s; its output: ovenlight logs %s\n", res.Restarted, res.Restarted)
	printChecks(res.Problems, true)
	if !checksOK(res.Problems) {
		return fmt.Errorf("%s restarted, but it fails a check above", res.Restarted)
	}
	return nil
}

// maxTail bounds what logs returns, however long the lines, and maxMCPLogLines how many
// lines the MCP tool returns.
const (
	maxTail        = 256 << 10
	maxMCPLogLines = 1000
)

// appOutput returns the last n lines of a supervised app's output. mcp names the MCP
// server's tools rather than the CLI's commands.
func appOutput(p *paths, slug string, n int, mcp bool) (string, error) {
	if slugPattern.MatchString(slug) { // else it isn't a slug, and it names a file
		out, err := tailLog(appLogPath(p.state, slug), n)
		if !errors.Is(err, fs.ErrNotExist) {
			return out, err
		}
	}
	cfg, err := LoadConfig(p.config)
	if err != nil {
		return "", err
	}
	app, err := cfg.Published(slug)
	switch {
	case err != nil:
		return "", err
	case app.Run == "":
		return "", fmt.Errorf("the connector doesn't run %s, so it keeps no output for it. %s", app.Name, runNote(p, slug))
	}
	if _, err := callDaemon(p.state, "status", 10*time.Second); errors.Is(err, errDaemonDown) {
		return "", fmt.Errorf("no output from %s yet: the connector, which runs it, isn't running. %s", app.Name, startFix(p))
	}
	status := "`ovenlight status`"
	if mcp {
		status = "the status tool"
	}
	return "", fmt.Errorf("no output from %s yet; %s shows whether the connector has started it", app.Name, status)
}

// tailLog returns the last n lines of the log, reaching into <path>.1 when the log
// itself has fewer.
func tailLog(path string, n int) (string, error) {
	data, err := readEnd(path, maxTail)
	if err != nil {
		return "", err
	}
	if strings.Count(string(data), "\n") < n && len(data) < maxTail {
		if older, err := readEnd(path+".1", maxTail-len(data)); err == nil {
			data = append(older, data...)
		}
	}
	text := strings.TrimSuffix(string(data), "\n")
	if text == "" || n <= 0 {
		return "", nil
	}
	lines := strings.Split(text, "\n")
	return strings.Join(lines[max(0, len(lines)-n):], "\n") + "\n", nil
}

// readEnd reads the last limit bytes of a file, from the first whole line in them.
func readEnd(path string, limit int) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	from := info.Size() - int64(limit)
	if from <= 0 {
		return io.ReadAll(f)
	}
	data := make([]byte, limit)
	if _, err := f.ReadAt(data, from); err != nil && err != io.EOF {
		return nil, err
	}
	if i := strings.IndexByte(string(data), '\n'); i >= 0 {
		return data[i+1:], nil
	}
	return nil, nil
}

// followLog copies what is added to the log to w until stop closes, moving on to the new
// file when the log moves to <path>.1.
func followLog(path string, w io.Writer, every time.Duration, stop <-chan struct{}) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer func() { f.Close() }()
	if _, err := f.Seek(0, io.SeekEnd); err != nil {
		return err
	}
	for {
		if _, err := io.Copy(w, f); err != nil {
			return err
		}
		select {
		case <-stop:
			return nil
		case <-time.After(every):
		}
		cur, err1 := f.Stat()
		now, err2 := os.Stat(path)
		if err1 == nil && err2 == nil && !os.SameFile(cur, now) {
			io.Copy(w, f) // the end of the old file
			next, err := os.Open(path)
			if err != nil {
				return err
			}
			f.Close()
			f = next
		}
	}
}

func cmdLogs(args []string) error {
	fs, p := newFlags("logs")
	lines := fs.Int("n", 100, "how many of the last lines to show")
	follow := fs.Bool("f", false, "keep showing what the app writes, until Control-C")
	pos, err := parseArgs(fs, args, 1)
	if err != nil {
		return err
	}
	if len(pos) != 1 {
		return errors.New("usage: ovenlight logs <slug> [-n <lines>] [-f]")
	}
	out, err := appOutput(p, pos[0], max(*lines, 0), false)
	if err != nil {
		return err
	}
	fmt.Print(out)
	if !*follow {
		return nil
	}
	return followLog(appLogPath(p.state, pos[0]), os.Stdout, 500*time.Millisecond, nil)
}

// processLine is a supervised app's process in a few words, for status.
func processLine(app appStatus) string {
	pr := app.Process
	switch {
	case pr == nil && app.UnsupervisedPID != 0:
		return fmt.Sprintf("still running from the last connector (pid %d), unsupervised", app.UnsupervisedPID)
	case pr == nil:
		return "not running"
	}
	var b strings.Builder
	if pr.Running {
		fmt.Fprintf(&b, "running, pid %d", pr.PID)
	} else {
		b.WriteString("not running, starting again shortly")
	}
	if pr.Restarts > 0 {
		fmt.Fprintf(&b, ", restarted %d times", pr.Restarts)
	}
	if pr.LastExit != "" {
		fmt.Fprintf(&b, ", last exit (%s) at %s", pr.LastExit, pr.LastExitAt.Local().Format("2 Jan 15:04"))
	}
	return b.String()
}
