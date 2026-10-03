package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// latestURL names the newest connector release and the oldest without a known security
// hole: {"latest": "1.2.0", "secure": "1.1.3"}. doctor reads it; nothing else does.
var latestURL = "https://ovenlight.app/connector/latest.json"

// updateCheck says when a newer release is out, and fails when this one has a security
// hole fixed since. A development build, or a file it can't read, checks nothing.
func updateCheck() *Check {
	v, ok := semver(releaseVersion)
	if !ok {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, latestURL, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	var latest struct{ Latest, Secure string }
	if resp.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&latest) != nil {
		return nil
	}
	newest, okN := semver(latest.Latest)
	secure, okS := semver(latest.Secure)
	fix := "Install " + latest.Latest + " from the connector's download page (https://ovenlight.app/support#own-computer) with its install script; your apps and node state are kept"
	switch {
	case okS && less(v, secure):
		return &Check{ID: "update", Status: statusFail, Actor: actorPerson,
			Message: "this connector, " + releaseVersion + ", has a security problem fixed in " + latest.Secure, Fix: fix}
	case okN && less(v, newest):
		return &Check{ID: "update", Status: statusWarn, Actor: actorPerson,
			Message: latest.Latest + " is out (this is " + releaseVersion + ")", Fix: fix}
	}
	return &Check{ID: "update", Status: statusOK, Message: releaseVersion + " is the latest"}
}

// semver reads "1.2.3", without a pre-release part.
func semver(s string) ([3]int, bool) {
	var v [3]int
	parts := strings.Split(s, ".")
	if len(parts) != 3 {
		return v, false
	}
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return v, false
		}
		v[i] = n
	}
	return v, true
}

func less(a, b [3]int) bool {
	for i := range a {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return false
}
