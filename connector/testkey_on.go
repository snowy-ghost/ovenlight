//go:build ovenlight_testkey

package main

import (
	"cmp"
	"os"
	"time"
)

// testKeyBuild marks a build that trusts the test release key, for the end-to-end tests.
// Its private half is public in testdata/releasekey. Only such a build may fetch updates
// from somewhere other than downloads.ovenlight.app or run on shorter intervals, and
// scripts/release-connector.sh refuses a binary that contains the key.
const testKeyBuild = true

var testKeys = mustParseKeys(testKey)

// testCrash, set with -ldflags -X main.testCrash=1, makes a release that crashes each
// time the service starts it, right after its trial counts the start: the end-to-end
// tests' rollback.
var testCrash string

const testKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFRYuu6sp1blc6PV9kSNS2YDhXeNBrwverfKrfyn8Pdv ovenlight test release key, public on purpose"

// updateBase and updateHour come from the environment here, so the end-to-end tests can
// serve releases themselves and run the updater's days in minutes. OVENLIGHT_UPDATE_BASE
// replaces releaseBase, and OVENLIGHT_UPDATE_HOUR, a duration that divides an hour (such
// as 5s), is the length of the updater's hour. Every interval in its schedule is a
// multiple or a fraction of that hour.
var (
	updateBase = cmp.Or(os.Getenv("OVENLIGHT_UPDATE_BASE"), releaseBase)
	updateHour = testHour(os.Getenv("OVENLIGHT_UPDATE_HOUR"))
)

func testHour(s string) time.Duration {
	if s == "" {
		return time.Hour
	}
	d, err := time.ParseDuration(s)
	if err != nil || d <= 0 || time.Hour%d != 0 {
		panic("OVENLIGHT_UPDATE_HOUR isn't a duration that divides an hour")
	}
	return d
}
