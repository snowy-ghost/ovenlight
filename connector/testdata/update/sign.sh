#!/bin/sh
# Signs index.json for the verifier's tests (sshsig_test.go) with ssh-keygen -Y sign.
#
#   sign.sh [<release key> ...]
#
# The test key signs it in each namespace the tests check, and with sha256. Each release
# key signs it only in ovenlight-test, which nothing that ships accepts, so a real key's
# signature here can never be published as an index. With no arguments the release keys
# are the mock ones in ../releasekey. After the real keys go into connector/release_keys,
# run it on the Air with their private key files, in the same order, such as
#
#   connector/testdata/update/sign.sh ~/.ssh/id_ecdsa_sk_rk_ovenlight-release <paper key>
#
# A Touch ID handle signs through /usr/lib/ssh-keychain.dylib. index.json.ovenlight-latest.sk.sig
# is from a Secure Enclave key that was deleted after signing, so it stays as it is.
set -eu
cd "$(dirname "$0")"
[ -e /usr/lib/ssh-keychain.dylib ] && export SSH_SK_PROVIDER="${SSH_SK_PROVIDER:-/usr/lib/ssh-keychain.dylib}"
[ $# -gt 0 ] || set -- ../releasekey/mock-touchid ../releasekey/mock-backup
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
n=1
for key in "$@"; do
  sign "$key" ovenlight-test "index.json.release-$n.sig"
  n=$((n + 1))
done
