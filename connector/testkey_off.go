//go:build !ovenlight_testkey

package main

import "golang.org/x/crypto/ssh"

const testKeyBuild = false

var testKeys []ssh.PublicKey
