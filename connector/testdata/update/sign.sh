#!/bin/sh
# Signs index.json for the verifier's tests (sshsig_test.go) with ssh-keygen -Y sign: the
# test key in each namespace the tests check, and with sha256, and each mock release key in
# ../releasekey only in ovenlight-test, which nothing that ships accepts. The real keys'
# index.json.release-*.sig are made as docs/development.md says. They're of index.json byte
# for byte, so it never changes. The key swap takes the mock keys out of this script.
#
# index.json.ovenlight-latest.sk.sig is from a Secure Enclave key that was deleted after
# signing, so it stays as it is.
set -eu
cd "$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'rm -r "$tmp"' EXIT
cp index.json "$tmp/index.json"

# sign <key> <namespace> <out> [ssh-keygen options]: ssh-keygen refuses a key file others
# can read, so it signs with a private copy.
sign() {
  key="$1" ns="$2" out="$3"
  shift 3
  install -m 600 "$key" "$tmp/key"
  ssh-keygen -Y sign -f "$tmp/key" -n "$ns" "$@" "$tmp/index.json" < /dev/null
  mv "$tmp/index.json.sig" "$out"
  rm "$tmp/key"
}

for ns in ovenlight-latest ovenlight-canary file; do
  sign ../releasekey/test-key "$ns" "index.json.$ns.sig"
done
sign ../releasekey/test-key ovenlight-latest index.json.ovenlight-latest.sha256.sig -O hashalg=sha256

rm -f index.json.release-*.sig
sign ../releasekey/mock-touchid ovenlight-test index.json.release-1.sig
sign ../releasekey/mock-backup ovenlight-test index.json.release-2.sig
