//go:build unix

package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"time"
)

// shellCommand runs an app's command with sh. The supervisor gives it the environment of
// the user's login shell (see loginEnv), not the login shell itself: what a login shell's
// profile starts, such as an ssh-agent, would escape the app's process group, and a start
// each time would leave one more running.
func shellCommand(run string) *exec.Cmd {
	return exec.Command("/bin/sh", "-c", run)
}

// loginEnvTimeout is how long the login shell has to print its environment; tests
// shorten it.
var loginEnvTimeout = 10 * time.Second

// loginEnv is the environment the user's login shell sets up from env, reading
// ~/.zprofile but not ~/.zshrc, which only an interactive shell reads, so commands get
// what the profile exports, and find what a terminal finds of Homebrew and the like: a
// launchd agent's PATH is /usr/bin:/bin:/usr/sbin:/sbin. The shell runs this binary to
// print what it exports, so fish, which keeps PATH as a list, exports it joined with
// colons too, and nothing depends on the system's env. Left out are the variables that
// describe the shell that read it, and tsnetEnv, which the connector clears from its own.
//
// The shell runs in a process group of its own, which is ended as a whole once the read
// is done or times out, so nothing the profile starts or hangs in is left running.
func loginEnv(env []string) ([]string, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	shell, line := loginShell(), `exec "$`+exeVar+`" `+env0Command
	if slices.Contains(unreadShells, filepath.Base(shell)) {
		return nil, errors.New(shell + ": reading this login shell's environment isn't supported")
	}
	ctx, cancel := context.WithTimeout(context.Background(), loginEnvTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, shell, "-lc", line)
	if base := filepath.Base(shell); base == "csh" || base == "tcsh" { // -c can't follow -l, so the line comes on stdin
		cmd.Args = []string{shell, "-l"}
		cmd.Stdin = strings.NewReader(line + "\n")
	}
	cmd.Env = append(slices.Clip(env), exeVar+"="+exe)
	newProcessGroup(cmd)
	cmd.Cancel = func() error {
		signalGroup(cmd.Process.Pid, true)
		return nil
	}
	cmd.WaitDelay = time.Second // what the profile started may hold the output pipe
	out, err := cmd.Output()
	if cmd.Process != nil {
		endStrayGroup(cmd.Process.Pid, 500*time.Millisecond)
	}
	if ctx.Err() != nil {
		return nil, fmt.Errorf("%s took over %v to start", shell, loginEnvTimeout)
	}
	_, vars, ok := strings.Cut(string(out), envMark)
	if err == nil && !ok {
		err = errors.New("it didn't print its environment")
	}
	var login []string
	hasPath := false
	for _, v := range strings.Split(vars, "\x00") {
		name, value, ok := strings.Cut(v, "=")
		if !ok || slices.Contains([]string{"PWD", "OLDPWD", "SHLVL", "_", exeVar}, name) || slices.Contains(tsnetEnv, name) {
			continue
		}
		hasPath = hasPath || (name == "PATH" && value != "")
		login = append(login, v)
	}
	if !hasPath {
		if err == nil {
			err = errors.New("its environment has no PATH")
		}
		return nil, errors.New(shell + ": " + err.Error())
	}
	return login, nil
}

// loginShell is $SHELL, or else zsh, the macOS default, or else sh.
func loginShell() string {
	for _, sh := range []string{os.Getenv("SHELL"), "/bin/zsh", "/bin/sh"} {
		if info, err := os.Stat(sh); err == nil && filepath.IsAbs(sh) && info.Mode().IsRegular() && info.Mode()&0o111 != 0 {
			return sh
		}
	}
	return "/bin/sh"
}

// newProcessGroup puts the command in a process group of its own, which signalGroup
// reaches as a whole.
func newProcessGroup(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

// signalGroup sends SIGTERM, or SIGKILL with kill, to every process in the group.
func signalGroup(pgid int, kill bool) {
	sig := syscall.SIGTERM
	if kill {
		sig = syscall.SIGKILL
	}
	syscall.Kill(-pgid, sig)
}

// groupAlive reports whether any process is left in the group.
func groupAlive(pgid int) bool {
	return !errors.Is(syscall.Kill(-pgid, 0), syscall.ESRCH)
}

// holdGroup and releaseGroup need nothing on Unix: a group's ID isn't reused while any
// of it runs.
func holdGroup(int)    {}
func releaseGroup(int) {}

// processGroup is the process's group ID, or -1 when there is no such process.
func processGroup(pid int) int {
	if pid <= 0 {
		return -1 // pid 0 is the caller's own group, not "no such process"
	}
	pgid, err := syscall.Getpgid(pid)
	if err != nil {
		return -1
	}
	return pgid
}
