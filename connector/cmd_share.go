package main

import (
	"cmp"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"golang.org/x/term"
)

// parseInterspersed parses flags that may come after positional arguments
// (`share coach --to Sam`) and returns the positional ones, with where each is in args.
func parseInterspersed(fs *flag.FlagSet, args []string) (pos []string, at []int) {
	rest := args
	for {
		fs.Parse(rest)
		rest = fs.Args()
		if len(rest) == 0 {
			return pos, at
		}
		pos, at = append(pos, rest[0]), append(at, len(args)-len(rest))
		rest = rest[1:]
	}
}

// parseArgs parses flags anywhere in args and returns the positional arguments, refusing
// any past the first n before the command does anything. An unquoted value with a space,
// as in --name Brand New, leaves such a stray word, and the flag package would stop at it
// and drop the flags after it, such as --config.
func parseArgs(fs *flag.FlagSet, args []string, n int) ([]string, error) {
	pos, at := parseInterspersed(fs, args)
	if len(pos) > n {
		return nil, strayArgument(fs, args, at[n])
	}
	return pos, nil
}

// strayArgument refuses args[i], naming the flag whose value comes before it when there
// is one.
func strayArgument(fs *flag.FlagSet, args []string, i int) error {
	word, name, value := args[i], "", ""
	switch {
	case i >= 1 && strings.HasPrefix(args[i-1], "-") && strings.Contains(args[i-1], "="):
		name, value, _ = strings.Cut(args[i-1], "=") // --name=Brand New
	case afterValue(fs, args, i):
		name, value = args[i-2], args[i-1] // --name Brand New
	}
	if stringFlag(fs, name) {
		// Values as typed, in plain quotes: %q would double a Windows path's backslashes.
		return fmt.Errorf(`unexpected argument %q after %s "%s": put a value with spaces in quotes, such as %s "%s"`, word, name, value, name, value+" "+word)
	}
	return fmt.Errorf(`unexpected argument %q: put a value with spaces in quotes, such as --name "Brand New" or --run 'npm start'`, word)
}

// afterValue reports whether args[i] directly follows a string flag's value given
// without =, as Projects does in --dir My Projects.
func afterValue(fs *flag.FlagSet, args []string, i int) bool {
	return i >= 2 && strings.HasPrefix(args[i-2], "-") && !strings.Contains(args[i-2], "=") && !strings.HasPrefix(args[i-1], "-") && stringFlag(fs, args[i-2])
}

// stringFlag reports whether the flag, such as --name, takes a string.
func stringFlag(fs *flag.FlagSet, name string) bool {
	f := fs.Lookup(strings.TrimLeft(name, "-"))
	if f == nil {
		return false
	}
	g, ok := f.Value.(flag.Getter)
	if !ok {
		return false
	}
	_, isString := g.Get().(string)
	return isString
}

// requireTerminal keeps access grants in a person's hands. It is a guard against
// accidents, not a sandbox.
func requireTerminal(what string) error {
	if !fromTerminal() {
		return fmt.Errorf("%s grants access, so a person has to run it in a terminal", what)
	}
	return nil
}

func daemonRequired(p *paths, err error) error {
	if errors.Is(err, errDaemonDown) {
		return fmt.Errorf("%w. %s", err, startFix(p))
	}
	return err
}

func cmdShare(args []string) error {
	fs, p := newFlags("share")
	to := fs.String("to", "", "who the invite is for, as you'd call them")
	existing := fs.Bool("existing", false, "the invite is for someone you already share with, named by --to")
	person := fs.String("person", "", "the invite is for someone you already share with, by person ID (see `ovenlight guests`)")
	cancelID := fs.String("cancel", "", "withdraw an unused invite, by its ID")
	review := fs.Bool("review", false, "an invite for Apple's App Review: valid 90 days")
	pos, err := parseArgs(fs, args, 1)
	if err != nil {
		return err
	}

	if *cancelID != "" {
		if len(pos) > 0 {
			return errors.New("usage: ovenlight share --cancel <id>")
		}
		reply, err := callDaemonWith(p.state, controlRequest{Cmd: "share-cancel", ID: *cancelID}, 30*time.Second)
		if err != nil {
			return daemonRequired(p, err)
		}
		fmt.Printf("Canceled invite %s for %s%s.\n", reply.Invite.ID, reply.Invite.To, keyFate(*reply.Invite))
		return nil
	}

	if len(pos) != 1 || *person != "" && (*to != "" || *existing) {
		return errors.New(`usage: ovenlight share <slug> (--to "Name" [--existing] | --person <id>) [--review]`)
	}
	if err := requireTerminal("share"); err != nil {
		return err
	}
	reply, err := callDaemonWith(p.state, controlRequest{Cmd: "share", Slug: pos[0], guestRef: guestRef{To: *to, Person: *person, Existing: *existing}, Review: *review}, time.Minute)
	if err != nil {
		return daemonRequired(p, err)
	}
	if *review {
		printReviewInvite(reply.Share)
	} else {
		printInvite(reply.Share)
	}
	return nil
}

// printReviewInvite shows an invite for App Review: the link, how to take it back, and
// notes to paste into App Store Connect (App Review Information, Notes).
func printReviewInvite(res *shareResult) {
	if res == nil {
		return
	}
	inv := res.Invite
	fmt.Printf("Review invite %s: %s can open %s. Single use, expires %s.\n",
		inv.ID, inv.To, res.AppName, inv.Expires.Local().Format("Mon 2 Jan 15:04"))
	fmt.Printf("Revoke it once the review is done: ovenlight revoke %q (or ovenlight share --cancel %s if it was never used).\n", inv.To, inv.ID)
	for _, e := range res.TagErrors {
		fmt.Printf("Warning: %s\n", e)
	}
	fmt.Printf("\nLink (it's a credential until it's used):\n  %s\n", res.Link)
	printAppLink(res)
	fmt.Printf("\nFor App Store Connect, App Review Information, Notes:\n\n%s\n", reviewNotes(res))
}

// reviewNotes is the paste-ready text telling App Review how to open the shared app.
func reviewNotes(res *shareResult) string {
	app := res.AppName
	return fmt.Sprintf(`Ovenlight opens web apps that their owner runs on their own computer and shares by private invite. There is no account or sign-in.

To open a shared app:
1. Open Ovenlight and tap Join with Invite.
2. Copy the invite link below, then tap Paste (or type it into the field and tap Continue):
%s
3. Tap Join. When it says "%s Is Ready", tap Open %s.

The invite works once, on one device, until %s. If it has run out, reply in App Store Connect and we'll send a new one.

To report a problem with a shared app or stop seeing it: open the app, tap its name at the top of the screen (tap the small handle there if the bar is hidden), then Report a Problem, or Leave %s's Apps.`,
		res.Link, app, app, res.Invite.Expires.UTC().Format("Mon 2 Jan 2006 15:04 MST"), cmp.Or(res.Owner, "the owner"))
}

// printInvite shows a minted invite: link, QR code and a message to send.
func printInvite(res *shareResult) {
	if res == nil {
		return
	}
	inv := res.Invite
	fmt.Printf("Invite %s: %s can open %s. Single use, expires %s.\n", inv.ID, inv.To, res.AppName, inv.Expires.Local().Format("Mon 2 Jan 15:04"))
	if len(res.Devices) > 0 {
		fmt.Printf("%s already has %s in your tailnet for another of your apps; it can reach %s now, and adds it when the link is opened there.\n",
			inv.To, strings.Join(res.Devices, ", "), res.AppName)
	}
	for _, e := range res.TagErrors {
		fmt.Printf("Warning: %s\n", e)
	}
	if term.IsTerminal(int(os.Stdout.Fd())) {
		fmt.Printf("\nScan with the iPhone camera:\n")
		if err := writeQR(os.Stdout, res.Link); err != nil {
			fmt.Printf("(no QR code: %v)\n", err)
		}
	}
	fmt.Printf("\nLink (it's a credential until it's used, so send it privately):\n  %s\n", res.Link)
	printAppLink(res)
	fmt.Printf("\nTo send:\n  %s\n", inviteMessage(res))
}

// printAppLink shows the ovenlight:// form, for a phone where the link opens the web
// page instead of Ovenlight.
func printAppLink(res *shareResult) {
	if res.AppLink != "" {
		fmt.Printf("If the link doesn't open Ovenlight (it has to be installed), open this one on the iPhone instead:\n  %s\n", res.AppLink)
	}
}

// inviteMessage is what the owner sends, in a terminal or from Ovenlight's Send Invite.
func inviteMessage(res *shareResult) string {
	// The owner sends this from their own phone or computer, so it speaks as them: a
	// third-person template next to a long link reads like phishing.
	return fmt.Sprintf("I'm sharing %s with you in Ovenlight, an iPhone app. Open this link on your iPhone within %s to join: %s",
		res.AppName, humanDuration(res.Invite.lifetime()), res.Link)
}

func cmdGuests(args []string) error {
	fs, p := newFlags("guests")
	asJSON := fs.Bool("json", false, "machine-readable output")
	all := fs.Bool("all", false, "include removed guests")
	sync := fs.Bool("sync", false, "check the tailnet's device list first instead of waiting for the next sync")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	if *sync {
		reply, err := callDaemonWith(p.state, controlRequest{Cmd: "sync"}, time.Minute)
		if err != nil {
			return daemonRequired(p, err)
		}
		fmt.Fprintln(os.Stderr, reply.Message)
	}
	var list guestList
	reply, err := callDaemonWith(p.state, controlRequest{Cmd: "guests"}, 30*time.Second)
	switch {
	case err == nil:
		list = *reply.Guests
	case errors.Is(err, errDaemonDown):
		st, err := loadSharingState(sharingPath(p.state))
		if err != nil {
			return err
		}
		list = buildGuestList(st, nil, time.Time{}, "the connector isn't running")
	default:
		return err
	}
	if *asJSON {
		return writeGuestsJSON(os.Stdout, list)
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	shown := 0
	for _, g := range list.Guests {
		if !g.Active() && !*all {
			continue
		}
		if shown == 0 {
			fmt.Fprintln(w, "GUEST\tPERSON\tAPP\tDEVICE\tDEVICE ID\tSINCE\tSTATUS")
		}
		shown++
		status := "active"
		if !g.Active() {
			status = "removed: " + g.RemovedWhy
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", g.Name, g.Person, g.App, orDash(g.DeviceName), g.DeviceID, g.ClaimedAt.Local().Format("2 Jan 15:04"), status)
	}
	w.Flush()
	if shown == 0 {
		fmt.Println("No guests yet.")
	}
	if len(list.Invites) > 0 {
		fmt.Println()
		w = tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
		fmt.Fprintln(w, "INVITE\tFOR\tPERSON\tAPP\tSTATE\tEXPIRES\tASKED BY")
		for _, inv := range list.Invites {
			state := inv.State
			if inv.Review {
				state += " (review)"
			}
			fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", inv.ID, inv.To, inv.Person, inv.App, state, inv.Expires.Local().Format("2 Jan 15:04"), inv.RequestedBy)
		}
		w.Flush()
	}
	if len(list.Unclaimed) > 0 {
		fmt.Println("\nGuest devices in your tailnet that never claimed an invite (the connector refuses them; remove any you don't expect in the Tailscale admin console, Machines):")
		for _, d := range list.Unclaimed {
			fmt.Printf("  %s (%s, joined %s, tagged %s)\n", d.Name, d.NodeID, orDash(d.Created), strings.Join(d.Tags, ", "))
		}
	}
	if list.SyncError != "" {
		fmt.Printf("\nNot synced with the tailnet: %s\n", list.SyncError)
	}
	return nil
}

// writeGuestsJSON writes the list as GET /v1/guests answers it, without the stored
// records' key hashes and tailnet details.
func writeGuestsJSON(w io.Writer, list guestList) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(list.view())
}

// keyFate ends the line about a canceled invite: whether its key was deleted.
func keyFate(inv Invite) string {
	if inv.KeyID != "" {
		return " here; the key stays usable until Ovenlight can delete it (it retries on each sync)"
	}
	return "; its key no longer works"
}

func cmdRevoke(args []string) error {
	fs, p := newFlags("revoke")
	app := fs.String("app", "", "remove the guest from this app only")
	person := fs.String("person", "", "remove every device and open invite of this person, by person ID (see `ovenlight guests`)")
	pos, err := parseArgs(fs, args, 1)
	if err != nil {
		return err
	}
	if len(pos)+min(len(*person), 1) != 1 {
		return errors.New("usage: ovenlight revoke (<name | device ID | invite ID> | --person <ID>) [--app <slug>]")
	}
	req := controlRequest{Cmd: "revoke", App: *app, guestRef: guestRef{Person: *person}}
	if len(pos) == 1 {
		req.ID = pos[0]
	}
	reply, err := callDaemonWith(p.state, req, time.Minute)
	if err != nil {
		return daemonRequired(p, err)
	}
	for _, g := range reply.Revoke.Removed {
		fmt.Printf("Removed %s from %s (device %s). The connector refuses them from now on.\n", g.Name, g.App, orDash(g.DeviceName))
	}
	for _, inv := range reply.Revoke.Canceled {
		fmt.Printf("Canceled invite %s for %s to %s%s.\n", inv.ID, inv.To, inv.App, keyFate(inv))
	}
	for _, d := range reply.Revoke.Deleted {
		fmt.Printf("Deleted device %s from your tailnet.\n", d)
	}
	for _, d := range reply.Revoke.Retagged {
		fmt.Printf("Device %s keeps your other apps; its tags now reach only those.\n", d)
	}
	for _, e := range reply.Revoke.Errors {
		fmt.Printf("Warning: %s\n", e)
	}
	return nil
}

func cmdFeedback(args []string) error {
	fs, p := newFlags("feedback")
	asJSON := fs.Bool("json", false, "machine-readable output")
	app := fs.String("app", "", "only this app")
	limit := fs.Int("n", 20, "how many, newest first (0 for all)")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	items, err := readFeedback(p.state, *app, *limit)
	if err != nil {
		return err
	}
	if *asJSON {
		type item struct {
			feedbackView
			ScreenshotPath string `json:"screenshotPath,omitempty"`
		}
		out := []item{}
		for _, f := range items {
			path, _ := screenshotPath(p.state, f)
			out = append(out, item{f.view(), path})
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(out)
	}
	if len(items) == 0 {
		fmt.Println("No feedback yet. People send it from an app's menu in Ovenlight: Send Feedback.")
		return nil
	}
	for _, f := range items {
		fmt.Printf("%s  %s  %s (%s)  %s\n", f.At.Local().Format("2 Jan 15:04"), f.App, f.From, f.Role, f.ID)
		if f.Note != "" {
			for _, line := range strings.Split(f.Note, "\n") {
				fmt.Printf("    %s\n", line)
			}
		}
		if f.PageURL != "" {
			fmt.Printf("    page: %s\n", f.PageURL)
		}
		if path, ok := screenshotPath(p.state, f); ok {
			fmt.Printf("    screenshot: %s\n", path)
		}
	}
	return nil
}
