package main

import (
	"fmt"
	"io"
	"net/url"
	"strings"

	"rsc.io/qr"
)

// InviteLink is what a guest's Ovenlight needs to join the owner's tailnet and claim the
// invite. It travels as a universal link,
// https://ovenlight.app/join#v=1&control=...&key=...&owner=...&app=...&invite=...&host=...&name=...&to=...
// or, when that doesn't open the app, as ovenlight://join? with the same query.
type InviteLink struct {
	Control string // control server URL
	Key     string // single-use auth key; the link is a credential until it's used
	Owner   string // owner label, for "Sam shared ... with you"
	App     string // app slug
	Invite  string // invite ID
	Host    string // the app node's MagicDNS name, where Ovenlight claims and opens the app
	Name    string // app name
	To      string // the guest's name as the owner typed it
}

// joinPage is the universal link's page. iOS opens Ovenlight for it when Ovenlight is
// installed; otherwise the page offers the app. The fields ride in the fragment, which
// browsers never send to a server, so the key never reaches the website.
const joinPage = "https://ovenlight.app/join"

func (l InviteLink) query() string {
	q := url.Values{}
	q.Set("v", "1")
	q.Set("control", l.Control)
	q.Set("key", l.Key)
	q.Set("owner", l.Owner)
	q.Set("app", l.App)
	q.Set("invite", l.Invite)
	q.Set("host", l.Host)
	q.Set("name", l.Name)
	q.Set("to", l.To)
	return q.Encode()
}

// String is the universal link, the one to send.
func (l InviteLink) String() string { return joinPage + "#" + l.query() }

// AppLink opens Ovenlight directly, for when the universal link opens the web page instead.
func (l InviteLink) AppLink() string { return "ovenlight://join?" + l.query() }

// writeQR draws the text as a QR code with half-block characters, two modules per
// character row, in explicit black on white so it scans on dark and light terminals.
func writeQR(w io.Writer, text string) error {
	code, err := qr.Encode(text, qr.L)
	if err != nil {
		return err
	}
	const quiet = 2
	size := code.Size
	dark := func(x, y int) bool {
		if x < 0 || y < 0 || x >= size || y >= size {
			return false
		}
		return code.Black(x, y)
	}
	var b strings.Builder
	for y := -quiet; y < size+quiet; y += 2 {
		b.WriteString("  ")
		for x := -quiet; x < size+quiet; x++ {
			top, bottom := dark(x, y), dark(x, y+1)
			fg, bg := "97", "107" // white
			if top {
				fg = "30"
			}
			if bottom {
				bg = "40"
			}
			fmt.Fprintf(&b, "\x1b[%s;%sm▀", fg, bg)
		}
		b.WriteString("\x1b[0m\n")
	}
	_, err = io.WriteString(w, b.String())
	return err
}
