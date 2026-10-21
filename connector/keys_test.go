package main

import (
	"os"
	"strings"
	"testing"

	"golang.org/x/crypto/ssh"
)

// Each release key is in docs/allowed_signers, for both namespaces a release's
// signatures use, so an owner can check what the updater checks.
func TestReleaseKeysAreAllowedSigners(t *testing.T) {
	signers, err := os.ReadFile("../docs/allowed_signers")
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range mustParseKeys(releaseKeysFile) {
		key := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(k)))
		found := false
		for _, line := range strings.Split(string(signers), "\n") {
			line = strings.TrimSpace(line) // a Windows checkout has CRLF
			if strings.HasSuffix(line, " "+key) && strings.Contains(line, `namespaces="file,ovenlight-latest"`) {
				found = true
			}
		}
		if !found {
			t.Errorf("%s isn't in docs/allowed_signers with namespaces \"file,ovenlight-latest\"", key)
		}
	}
}

func TestParseKeys(t *testing.T) {
	pub, err := os.ReadFile("testdata/releasekey/test-key.pub")
	if err != nil {
		t.Fatal(err)
	}
	if keys, err := parseKeys("\n" + string(pub) + "\n\n" + string(pub)); err != nil || len(keys) != 2 {
		t.Errorf("two keys and blank lines: %d keys, %v", len(keys), err)
	}
	for _, bad := range []string{"not a key", `no-pty ` + string(pub), strings.TrimSpace(string(pub)) + "\nssh-ed25519 AAAA"} {
		if _, err := parseKeys(bad); err == nil {
			t.Errorf("%q parsed", bad)
		}
	}
}
