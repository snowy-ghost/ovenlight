package main

import (
	"os"
	"regexp"
	"slices"
	"strings"
	"testing"

	"golang.org/x/crypto/ssh"
)

// Each release key is in docs/allowed_signers, under the principal owners check with and
// for both namespaces a release's signatures use, so an owner can check what the updater
// checks.
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
			if line == `team@snowyghost.com namespaces="file,ovenlight-latest" `+key {
				found = true
			}
		}
		if !found {
			t.Errorf("%s isn't in docs/allowed_signers as team@snowyghost.com with namespaces \"file,ovenlight-latest\"", key)
		}
	}
}

// The one-line installers on the site check latest.json with the keys in
// docs/allowed_signers, as the verified steps do, so they list those keys exactly.
func TestInstallersListAllowedSigners(t *testing.T) {
	signers, err := os.ReadFile("../docs/allowed_signers")
	if err != nil {
		t.Fatal(err)
	}
	signer := regexp.MustCompile(`team@snowyghost\.com namespaces="[^"]*" \S+ [A-Za-z0-9+/]+=*`)
	want := signer.FindAllString(string(signers), -1)
	if len(want) == 0 {
		t.Fatal("no keys in docs/allowed_signers")
	}
	for _, name := range []string{"../site/install.sh", "../site/install.ps1"} {
		script, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		if got := signer.FindAllString(string(script), -1); !slices.Equal(got, want) {
			t.Errorf("%s lists %q, not docs/allowed_signers' %q", name, got, want)
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
