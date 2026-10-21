package main

import (
	"bytes"
	"os"
	"testing"

	"golang.org/x/crypto/ssh"
)

// The signatures in testdata/update are ssh-keygen -Y sign's, of index.json: the test key's
// in three namespaces and with sha256, and one from a key in a Mac's Secure Enclave
// (sk-ecdsa-sha2-nistp256@openssh.com, through /usr/lib/ssh-keychain.dylib), the kind the
// release key is. That key was made for this and then deleted.
func TestVerifySig(t *testing.T) {
	read := func(name string) []byte {
		b, err := os.ReadFile("testdata/update/" + name)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
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

// Only a test-key build trusts the test key.
func TestTestKeySignature(t *testing.T) {
	index, _ := os.ReadFile("testdata/update/index.json")
	sig, _ := os.ReadFile("testdata/update/index.json.ovenlight-latest.sig")
	if err := verifySig(index, sig, nsLatest); (err == nil) != testKeyBuild {
		t.Errorf("test-key build %v: %v", testKeyBuild, err)
	}
}
