//go:build ovenlight_testkey

package main

// testKeyBuild marks a build that trusts the test release key, for the end-to-end tests.
// Its private half is public in testdata/releasekey. Only such a build may fetch updates
// from somewhere other than downloads.ovenlight.app or run on shorter intervals, and
// scripts/release-connector.sh refuses a binary that contains the key.
const testKeyBuild = true

var testKeys = mustParseKeys(testKey)

const testKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFRYuu6sp1blc6PV9kSNS2YDhXeNBrwverfKrfyn8Pdv ovenlight test release key, public on purpose"
