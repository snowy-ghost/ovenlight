//go:build !ovenlight_testkey

package main

import (
	"time"

	"golang.org/x/crypto/ssh"
)

const testKeyBuild = false

var testKeys []ssh.PublicKey

// updateBase is where the updater fetches releases, and updateHour the length of its
// hour. Only a test-key build may change either.
const (
	updateBase = releaseBase
	updateHour = time.Hour
)
