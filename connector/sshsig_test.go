package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/crypto/ssh"
)

// The signatures in testdata/update are ssh-keygen -Y sign's, of index.json. Its sign.sh
// makes the test key's, in three namespaces and with sha256. Each release key's is made as
// docs/development.md says, or by sign.sh while the release keys are mock. One is from a
// key in a Mac's Secure Enclave (sk-ecdsa-sha2-nistp256@openssh.com, through
// /usr/lib/ssh-keychain.dylib), the kind the release key is, made for this and then deleted.
func TestVerifySig(t *testing.T) {
	read := func(name string) []byte { return readUpdateTestdata(t, name) }
	testKey := mustParseKeys(string(read("../releasekey/test-key.pub")))
	skKey := mustParseKeys(string(read("sk-key.pub")))
	defer func(k []ssh.PublicKey) { releaseKeys = k }(releaseKeys)
	releaseKeys = append(testKey, skKey...)

	index := read("index.json")
	latest, canary, file := read("index.json.ovenlight-latest.sig"), read("index.json.ovenlight-canary.sig"), read("index.json.file.sig")
	sk := read("index.json.ovenlight-latest.sk.sig")
	for _, c := range []struct {
		sig []byte
		ns  string
	}{{latest, nsLatest}, {canary, nsCanary}, {file, "file"}, {sk, nsLatest}} {
		if err := verifySig(index, c.sig, c.ns); err != nil {
			t.Errorf("%s: %v", c.ns, err)
		}
	}

	changed := bytes.Replace(index, []byte("1.1.1"), []byte("1.1.2"), 1)
	for name, c := range map[string]struct {
		msg, sig []byte
		ns       string
	}{
		"canary as latest":        {index, canary, nsLatest},
		"latest as canary":        {index, latest, nsCanary},
		"file as latest":          {index, file, nsLatest},
		"file as canary":          {index, file, nsCanary},
		"sha256":                  {index, read("index.json.ovenlight-latest.sha256.sig"), nsLatest},
		"changed index":           {changed, latest, nsLatest},
		"changed index, sk key":   {changed, sk, nsLatest},
		"no signature":            {index, nil, nsLatest},
		"not armored":             {index, []byte("U1NIU0lH"), nsLatest},
		"cut short":               {index, latest[:len(latest)/2], nsLatest},
		"followed by another one": {index, append(append([]byte{}, latest...), latest...), nsLatest},
	} {
		if err := verifySig(c.msg, c.sig, c.ns); err == nil {
			t.Errorf("%s verified", name)
		}
	}

	// Another key: each signature verifies only while its key is built in.
	for _, c := range []struct {
		keys []ssh.PublicKey
		sig  []byte
	}{{skKey, latest}, {testKey, sk}, {nil, latest}} {
		releaseKeys = c.keys
		if err := verifySig(index, c.sig, nsLatest); err == nil {
			t.Errorf("verified with only %d other keys built in", len(c.keys))
		}
	}
}

func readUpdateTestdata(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("testdata/update/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// Each built-in release key signed index.json once, in ovenlight-test, which nothing that
// ships accepts, and the keys this build holds verify each signature as they will a
// release's.
func TestReleaseKeySignatures(t *testing.T) {
	index := readUpdateTestdata(t, "index.json")
	names, _ := filepath.Glob("testdata/update/index.json.release-*.sig")
	keys := mustParseKeys(releaseKeysFile)
	if len(keys) == 0 || len(names) != len(keys) {
		t.Fatalf("%d release keys, %d signatures: see Changing a release key in docs/development.md", len(keys), len(names))
	}
	var sigs [][]byte
	for _, name := range names {
		sig := readUpdateTestdata(t, filepath.Base(name))
		if err := verifySig(index, sig, "ovenlight-test"); err != nil {
			t.Errorf("%s: %v", name, err)
		}
		if verifySig(index, sig, nsLatest) == nil || verifySig(index, sig, nsCanary) == nil {
			t.Errorf("%s verified in a namespace that ships", name)
		}
		sigs = append(sigs, sig)
	}
	defer func(k []ssh.PublicKey) { releaseKeys = k }(releaseKeys)
	for _, k := range keys {
		releaseKeys = []ssh.PublicKey{k}
		n := 0
		for _, sig := range sigs {
			if verifySig(index, sig, "ovenlight-test") == nil {
				n++
			}
		}
		if n != 1 {
			t.Errorf("%s made %d of the signatures", ssh.FingerprintSHA256(k), n)
		}
	}
}

// Only a test-key build trusts the test key.
func TestTestKeySignature(t *testing.T) {
	index, _ := os.ReadFile("testdata/update/index.json")
	sig, _ := os.ReadFile("testdata/update/index.json.ovenlight-latest.sig")
	if err := verifySig(index, sig, nsLatest); (err == nil) != (len(testKeys) > 0) {
		t.Errorf("test-key build %v: %v", len(testKeys) > 0, err)
	}
}
