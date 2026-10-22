package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

// cmdUpdate handles `ovenlight update off|on|now --index <file>`. off and on only create
// and remove updates-off, which the connector reads before every download and swap.
func cmdUpdate(args []string) error {
	if len(args) == 0 {
		return errors.New("usage: ovenlight update off|on|now --index <file>")
	}
	sub, rest := args[0], args[1:]
	fs, p := newFlags("update")
	var index *string
	if sub == "now" {
		index = fs.String("index", "", "a release's canary index, signed in <file>.sig")
	}
	if _, err := parseArgs(fs, rest, 0); err != nil {
		return err
	}
	off := filepath.Join(p.state, updatesOffFile)
	switch sub {
	case "off":
		if err := jsonfile.MkdirPrivate(p.state); err != nil {
			return err
		}
		if err := os.WriteFile(off, []byte("Updates are off: ovenlight update on turns them back on.\n"), 0o600); err != nil {
			return err
		}
		msg := "Updates are off: `ovenlight update on` turns them back on."
		if _, ok := semver(version()); ok {
			msg += " The connector still checks for them daily, and says when its release has a security problem."
		}
		fmt.Println(msg)
	case "on":
		if err := os.Remove(off); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		msg := "Updates are on."
		if _, ok := semver(version()); ok {
			msg += " The connector installs a new release on its own two days after it first sees it, at a quiet moment in the night, and a security fix sooner. A release that's overdue goes in within minutes, busy or not."
		}
		fmt.Println(msg)
	case "now":
		if *index == "" {
			return errors.New("usage: ovenlight update now --index <file>")
		}
		data, err := os.ReadFile(*index)
		if err != nil {
			return err
		}
		sig, err := os.ReadFile(*index + ".sig")
		if err != nil {
			return err
		}
		reply, err := callDaemonWith(p.state, controlRequest{Cmd: "update-now", Index: data, Sig: sig}, updateNowWait)
		if errors.Is(err, errDaemonDown) {
			return fmt.Errorf("%w. %s", err, startFix(p))
		}
		if err != nil {
			return err
		}
		fmt.Println(reply.Message)
	default:
		return fmt.Errorf("unknown update command %q; use off, on or now", sub)
	}
	return nil
}

// updateLines say what status shows, at now, of the updater of a connector at version v.
func updateLines(u *updateView, v string, now time.Time) []string {
	if _, ok := semver(v); !ok || u == nil {
		return nil // a development build
	}
	var lines []string
	checked := "no check has worked yet"
	if !u.LastCheck.IsZero() {
		checked = "last checked " + when(u.LastCheck)
	}
	switch {
	case !u.SelfUpdating:
		lines = append(lines, "Updates: none, since this connector isn't the installed service.")
	case u.Off:
		lines = append(lines, "Updates: off (ovenlight update on turns them back on); "+checked+".")
	default:
		lines = append(lines, "Updates: on; "+checked+".")
	}
	if u.CheckError != "" {
		lines = append(lines, "The last check failed: "+u.CheckError)
	}
	if u.BelowSecure {
		lines = append(lines, fmt.Sprintf("SECURITY PROBLEM: %s has one, fixed in %s.", v, u.Secure))
	}
	switch {
	case u.NeedsScript:
		lines = append(lines, fmt.Sprintf("%s needs its install script, since an update replaces only the binary: install it with the verified steps in %s", u.Latest, installDocs))
	case u.Pending != "" && u.SelfUpdating && !u.Off:
		lines = append(lines, u.Pending+" "+u.installs(now)+".")
	case u.Pending != "":
		lines = append(lines, u.Pending+" is out.")
	}
	if e := u.LastUpdate; e != nil {
		lines = append(lines, fmt.Sprintf("Last update: %s to %s, on %s.", e.From, e.To, when(e.At)))
	}
	if f := u.Failure; f != nil {
		lines = append(lines, fmt.Sprintf("UPDATE FAILED: %s, on %s (%d in a row): %s", f.Version, when(f.At), f.Count, f.Error))
	}
	return lines
}
