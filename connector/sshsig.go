package main

import (
	"bytes"
	"crypto/sha512"
	"encoding/pem"
	"errors"

	"golang.org/x/crypto/ssh"
)

// The namespaces an index is signed in: nsLatest for the published one, latest.json, and
// nsCanary for a release's canary index, which only update now --index and the trial
// accept. A release's SHA256SUMS is signed in "file", which the updater never accepts: it
// checks an archive against the index.
const (
	nsLatest = "ovenlight-latest"
	nsCanary = "ovenlight-canary"
)

// verifySig checks an armored SSHSIG signature of msg, as ssh-keygen -Y sign makes it, in
// namespace and by one of releaseKeys. SSHSIG carries no identity, so the public key in the
// signature must equal a built-in key byte for byte. Only sha512 is accepted, which is what
// ssh-keygen uses. A security-key signature must assert user presence, as x/crypto/ssh
// requires.
func verifySig(msg, armored []byte, namespace string) error {
	block, rest := pem.Decode(armored)
	if block == nil || block.Type != "SSH SIGNATURE" || len(bytes.TrimSpace(rest)) != 0 {
		return errors.New("the signature isn't an SSH signature")
	}
	var s struct {
		Magic     [6]byte
		Version   uint32
		PublicKey []byte
		Namespace string
		Reserved  []byte
		Hash      string
		Signature []byte
	}
	if err := ssh.Unmarshal(block.Bytes, &s); err != nil || string(s.Magic[:]) != "SSHSIG" || s.Version != 1 {
		return errors.New("the signature isn't SSHSIG version 1")
	}
	if s.Namespace != namespace {
		return errors.New("the signature is for " + s.Namespace + ", not " + namespace)
	}
	if s.Hash != "sha512" {
		return errors.New("the signature uses " + s.Hash + ", not sha512")
	}
	var key ssh.PublicKey
	for _, k := range releaseKeys {
		if bytes.Equal(k.Marshal(), s.PublicKey) {
			key = k
		}
	}
	if key == nil {
		return errors.New("the signature isn't from a release key")
	}
	var sig ssh.Signature
	if err := ssh.Unmarshal(s.Signature, &sig); err != nil {
		return errors.New("the signature can't be read")
	}
	digest := sha512.Sum512(msg)
	signed := append([]byte("SSHSIG"), ssh.Marshal(struct {
		Namespace string
		Reserved  []byte
		Hash      string
		Digest    []byte
	}{namespace, s.Reserved, "sha512", digest[:]})...)
	if err := key.Verify(signed, &sig); err != nil {
		return errors.New("the signature doesn't verify")
	}
	return nil
}
