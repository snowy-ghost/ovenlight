package main

import (
	_ "embed"
	"fmt"
	"strings"

	"golang.org/x/crypto/ssh"
)

// releaseKeysFile holds the public keys that sign a release, one OpenSSH key per line:
// the release key in the Air's Secure Enclave, behind Touch ID, and the backup key kept
// on paper. They're the only thing an update trusts, never TLS or the download host.
// docs/allowed_signers lists the same keys, which a test checks, since go:embed can't
// reach docs/.
//
//go:embed release_keys
var releaseKeysFile string

// releaseKeys are parsed at startup, so a malformed file fails every run. A build with
// the ovenlight_testkey tag trusts the test key too.
var releaseKeys = append(mustParseKeys(releaseKeysFile), testKeys...)

// maxArchiveBytes caps a release archive the updater downloads, and the binary it takes
// out of one. The macOS archive is about 35 MB, and its universal binary 66 MB. Never raise
// it: an updater already installed downloads every later release under its own cap, while
// publish-connector.sh checks archives against this one.
const maxArchiveBytes = 128 << 20

func mustParseKeys(file string) []ssh.PublicKey {
	keys, err := parseKeys(file)
	if err != nil {
		panic(err)
	}
	return keys
}

// parseKeys reads one OpenSSH public key per line, skipping blank lines.
func parseKeys(file string) ([]ssh.PublicKey, error) {
	var keys []ssh.PublicKey
	for i, line := range strings.Split(file, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		k, _, options, rest, err := ssh.ParseAuthorizedKey([]byte(line))
		if err != nil || len(options) != 0 || len(rest) != 0 {
			return nil, fmt.Errorf("release key on line %d isn't one OpenSSH public key", i+1)
		}
		keys = append(keys, k)
	}
	return keys, nil
}
