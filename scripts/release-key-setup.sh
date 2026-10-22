#!/bin/zsh
# Key setup, on the Air, in Terminal (not over ssh): makes the paper backup key, prints it
# twice, checks a typed-back copy, and files both public keys and a test index's signature by
# each key in the key folder, never in ~/Sync, where a link an agent syncs would write into
# this Air's files. Only public keys and signatures leave this script; the backup's private
# key exists only in a temporary folder that's deleted at the end, and on paper.
set -euo pipefail

touchid=~/.ssh/id_ecdsa_sk_rk_ovenlight-release
keys=~/.ssh/ovenlight-release-keys
[[ -f $touchid && -f $touchid.pub ]] || { echo "No Touch ID key handle at $touchid"; exit 1; }
[[ -e $keys/backup.pub ]] && { echo "$keys/backup.pub already exists; stopping so nothing is replaced"; exit 1; }

work=$(mktemp -d)
job=
trap 'rm -r "$work"; [[ -n $job ]] && cancel -x "$job" 2>/dev/null' EXIT
trap 'exit 130' INT TERM HUP # zsh skips the EXIT trap on a signal otherwise
mkdir -p "$keys"
chmod 700 "$keys"

print -P "\n%B1. The backup key.%b Choose a strong passphrase you'll remember. It isn't printed."
ssh-keygen -q -t ed25519 -C 'ovenlight release backup key' -f "$work/backup"
if ssh-keygen -y -P '' -f "$work/backup" >/dev/null 2>&1; then
  echo "The key has no passphrase. Run the script again and set one."; exit 1
fi
pub=$(<"$work/backup.pub")

{
  echo "OVENLIGHT RELEASE BACKUP KEY        made $(date '+%Y-%m-%d')"
  echo "Keep offline. Two copies, in two places. Never store it on a computer."
  echo
  echo "Fingerprint: $(ssh-keygen -lf "$work/backup.pub")"
  echo "This Air's Touch ID key, to check a lost Air's key against the records:"
  echo "  $(ssh-keygen -lf "$touchid.pub")"
  # The keys earlier releases list, from this Air's history, but this Air's own.
  cat /dev/null $keys/history/*/release_keys(N) | awk -v t="$(cut -d' ' -f2 "$touchid.pub")" 'NF >= 2 && $2 != t && !seen[$2]++' > "$work/earlier"
  if [[ -s $work/earlier ]]; then
    echo "Keys earlier releases list:"
    ssh-keygen -lf "$work/earlier" | sed 's/^/  /'
  fi
  echo "Public key:"
  print -r -- "$pub" | fold -w 72
  echo
  echo "Private key, protected by its passphrase. To use it: type it into a file,"
  echo "chmod 600 the file, and check ssh-keygen -y -f <file> prints the public key."
  echo
  cat "$work/backup"
} > "$work/page.txt"

print -P "\n%B2. Printing two copies%b on $(lpstat -d | sed 's/.*: //')."
job=$(lp -n 2 -o cpi=12 -o lpi=7 -t ovenlight-backup "$work/page.txt" | awk '{print $4}')
read -r "?Press Return once both copies are out of the printer: "
cancel -x "$job" 2>/dev/null || true # drop the spooled copy

print -P "\n%B3. Check one printed copy.%b Type or scan the private key block back in,"
echo "from -----BEGIN to -----END, then press Ctrl-D on a new line."
while true; do
  : > "$work/typed"
  chmod 600 "$work/typed"
  cat > "$work/typed"
  if diff -q <(tr -d ' \t\r' < "$work/backup") <(tr -d ' \t\r' < "$work/typed") >/dev/null; then
    echo "Matches the key. Now its passphrase:"
    if [[ "$(ssh-keygen -y -f "$work/typed" | cut -d' ' -f1-2)" == "$(cut -d' ' -f1-2 <<< "$pub")" ]]; then
      break
    fi
    echo "That passphrase didn't open it. Paste the block again to retry."
  else
    echo "Differs from the key on these lines (yours on the right):"
    diff <(tr -d ' \t\r' < "$work/backup") <(tr -d ' \t\r' < "$work/typed") || true
    echo "Fix them on paper if the print is unclear, then paste the whole block again."
  fi
done

print -P "\n%B4. Test signatures%b in namespace ovenlight-test, which nothing that ships accepts."
# Byte for byte connector/testdata/update/index.json, so these signatures go straight into
# the repo at the key swap as index.json.release-1.sig and -2.sig.
cat > "$work/index.json" <<'EOF'
{
 "latest": "1.1.1",
 "secure": "1.0.6",
 "reinstallBelow": "1.1.0",
 "serial": 1793000000,
 "archives": {
  "ovenlight-connector-macos.tar.gz": "7e49a95cbf5be6e0e0eaa4530d1e934ec5288ba9049847ee91308f725b7be4e8",
  "ovenlight-connector-linux-amd64.tar.gz": "87cd25eec014a456ef176e6cc246ec258d02e5a7b9a0cd3622d8dcabcbd2b505",
  "ovenlight-connector-linux-arm64.tar.gz": "c5f02acdea05745565d6bda66ea755ba36fc5d53385ebe2f7efc173ed97e69e2",
  "ovenlight-connector-windows-amd64.zip": "4ca88ea604418655a9fc0b33ce451232db04d93fc5fff0aa96afd84a2f8b5805",
  "ovenlight-connector-windows-arm64.zip": "d04a9c20bc9457f0e67445b4cbf80dd25356c0e1b5f35aafc11e613973448382"
 }
}
EOF
[[ "$(shasum -a 256 "$work/index.json" | cut -d' ' -f1)" == 0b4192fb8466eea4a36aaac23c16febb1ae06fed1f26073fc60ed40d508437dc ]] || { echo "The test index differs from the repo's"; exit 1; }
echo "Touch ID will ask once."
until SSH_SK_PROVIDER=/usr/lib/ssh-keychain.dylib ssh-keygen -q -Y sign -f "$touchid" -n ovenlight-test "$work/index.json"; do
  echo "That didn't sign. Try again."
done
mv "$work/index.json.sig" "$work/index.json.release-1.sig"
echo "Now the backup key's passphrase, signing with the copy typed from paper:"
until ssh-keygen -q -Y sign -f "$work/typed" -n ovenlight-test "$work/index.json"; do
  echo "That didn't sign (a mistyped passphrase?). Try again."
done
mv "$work/index.json.sig" "$work/index.json.release-2.sig"

n=1
for k in "$touchid.pub" "$work/backup.pub"; do
  echo "test $(<"$k")" > "$work/signers"
  ssh-keygen -Y verify -f "$work/signers" -I test -n ovenlight-test -s "$work/index.json.release-$n.sig" < "$work/index.json"
  n=$((n + 1))
done
# Filed only now, so a run that stops early leaves nothing to block a rerun.
cp "$work/backup.pub" "$keys/backup.pub"
cp "$touchid.pub" "$keys/touchid.pub"
cp "$work/index.json.release-1.sig" "$work/index.json.release-2.sig" "$keys/"

print -P "\n%BDone.%b Public keys and test signatures in $keys (the key swap copies the signatures into the repo)."
echo "The temporary folder with the backup's private key is deleted now."
echo "Put the two printed copies in two different places."
