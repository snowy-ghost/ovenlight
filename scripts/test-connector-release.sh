#!/bin/bash
# Dry runs of the connector release scripts through each case the updater's release process
# must handle: promotes, pulls, re-signs, a publish that fails partway, a lost Air and a key
# rotation. Each scenario gets a scratch folder (OVENLIGHT_RELEASE_DRY) standing in for the
# bucket, ~/Sync/ovenlight, ~/.ssh/ovenlight-release-keys and build/release on the mini, and
# a scratch repo holding this tree's release scripts, committed whenever a release's keys
# change. Every release is built (with stand-in binaries), signed with the fixture keys in
# scripts/testdata/release-keys, and published before the next is built. The four weeks a
# new key waits pass by backdating the scratch records.
#
#   scripts/test-connector-release.sh
#
# It touches nothing outside its scratch folder, which it deletes when it's done.
set -euo pipefail
shopt -s nullglob

root="$(cd "$(dirname "$0")/.." && pwd)"
fixtures="$root/scripts/testdata/release-keys"
tmp="$(mktemp -d)"
trap 'rm -r "$tmp"' EXIT
checks=0

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { checks=$((checks + 1)); echo "  ok: $*"; }
field() { sed -En "s/.*\"$1\": \"?([^\",]*).*/\1/p" "$2"; }

# fresh <folder> <what it tries>: a new scenario, with no records, a bucket that holds
# 1.0.9's unsigned latest.json, and a scratch repo.
fresh() {
  echo "$2"
  dry="$tmp/$1"
  repo="$dry/repo"
  mkdir -p "$dry"/{build,sync/releases,keys,private,bucket/connector/latest,headers/connector/latest} "$repo"
  touch "$dry/.release-dry-run"
  export OVENLIGHT_RELEASE_DRY="$dry"
  echo '{"latest": "1.0.9", "secure": "1.0.6"}' > "$dry/bucket/connector/latest.json"
  echo "1.0.9's" > "$dry/bucket/connector/latest/SHA256SUMS"
  printf 'Content-Type: application/json\nCache-Control: public, max-age=300\n' > "$dry/headers/connector/latest.json"
  cp "$dry/bucket/connector/latest.json" "$dry/1.0.9.json"
  (cd "$root" && tar cf - scripts/*-connector.sh scripts/connector-release-lib.sh scripts/testdata \
    connector/go.mod connector/keys.go connector/install.sh connector/uninstall.sh \
    connector/install.ps1 connector/uninstall.ps1 connector/systemd connector/launchd \
    connector/testdata/releasekey/*.pub LICENSE NOTICE) | (cd "$repo" && tar xf -)
  mkdir -p "$repo/docs"
  : > "$repo/docs/allowed_signers"
  git -C "$repo" init -q -b master
  # Never sign the scratch commits with a real key.
  git -C "$repo" config user.name "dry run"
  git -C "$repo" config user.email dry-run@example.invalid
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config tag.gpgsign false
  for k in "$fixtures"/*.pub; do
    k="$(basename "$k" .pub)"
    cp "$fixtures/$k" "$fixtures/$k.pub" "$dry/private/"
    chmod 600 "$dry/private/$k"
  done
}

commit() { git -C "$repo" add -A && git -C "$repo" commit -q --allow-empty -m "$1"; }

# keys <name>...: a commit whose connector/release_keys holds those fixture keys, which
# docs/allowed_signers also lists, keeping the keys it had.
keys() {
  local k
  : > "$repo/connector/release_keys"
  for k in "$@"; do
    cat "$fixtures/$k.pub" >> "$repo/connector/release_keys"
    grep -qF "$(awk '{ print $2 }' "$fixtures/$k.pub")" "$repo/docs/allowed_signers" ||
      echo "team@snowyghost.com namespaces=\"file,ovenlight-latest\" $(awk '{ print $1, $2 }' "$fixtures/$k.pub")" >> "$repo/docs/allowed_signers"
  done
  commit "release keys: $*"
}

# signers <name>...: a commit whose docs/allowed_signers lists exactly those keys.
signers() {
  local k
  for k in "$@"; do
    echo "team@snowyghost.com namespaces=\"file,ovenlight-latest\" $(awk '{ print $1, $2 }' "$fixtures/$k.pub")"
  done > "$repo/docs/allowed_signers"
  commit "allowed signers: $*"
}

# keyfolder <name>...: the Air's ~/.ssh/ovenlight-release-keys holds those keys' .pub files,
# and the ones they replace move into its retired/.
keyfolder() {
  local k
  mkdir -p "$dry/keys/retired"
  for k in "$dry"/keys/*.pub; do mv "$k" "$dry/keys/retired/"; done
  for k in "$@"; do cp "$fixtures/$k.pub" "$dry/keys/"; done
}

# run <command>...: runs it, and fails with its output if it fails.
run() {
  "$@" > "$dry/log" 2>&1 || { cat "$dry/log" >&2; fail "$*"; }
}

# refused <message> <command>...: runs it, and fails unless it's refused with that message.
refused() {
  local want="$1"
  shift
  if "$@" > "$dry/log" 2>&1; then cat "$dry/log" >&2; fail "not refused: $*"; fi
  grep -qF -- "$want" "$dry/log" || { cat "$dry/log" >&2; fail "refused, but not with \"$want\": $*"; }
  pass "refused: $(grep -F -- "$want" "$dry/log" | head -1)"
}

release() { "$repo/scripts/release-connector.sh" --unsigned "$@"; }
sign() { local k="$1"; shift; "$repo/scripts/sign-connector.sh" --key "$dry/private/$k" "$@"; }
publish() { "$repo/scripts/publish-connector.sh" "$@"; }

# Serials are seconds, and each must be above the last one signed.
tick() { sleep 1; }

# build <version> <secure> [<reinstallBelow>]: built and tagged; signfolder <version> <key>:
# the folder signed and published.
build() {
  run release "$@"
  git -C "$repo" tag -f -m "Connector $1" "connector-v$1" > /dev/null
}
signfolder() {
  run sign "$2" "$1"
  run publish "$1"
  folder_is "$1"
}
promote() {
  tick
  run sign "$2" --promote "$1"
  run publish --promote "$1"
  latest_is "$1" "$2"
}
# pull <version> <key> <back to>
pull() {
  tick
  run sign "$2" --pull "$1"
  run publish --pull "$1"
  latest_is "$3" "$2"
}
# resign <key> <live>
resign() {
  tick
  run sign "$1" --resign
  run publish --resign
  latest_is "$2" "$1"
}
# ship <version> <secure> <key> [<reinstallBelow>]: built, signed, published and promoted.
ship() {
  build "$1" "$2" ${4:+"$4"}
  signfolder "$1" "$3"
  promote "$1" "$3"
}

# newestpublished: the newest index the records say was published.
newestpublished() {
  local d="$dry/sync/releases" f
  for f in "$d"/*/*.published; do f="${f#"$d/"}"; echo "${f%.published}"; done | sort -t- -k2,2n | tail -1
}
# lastchecked: the owner's step when the Air's last.json isn't the newest index published,
# once latest.json is checked against the tags: that index becomes the last one signed.
lastchecked() { cp "$dry/sync/releases/$(newestpublished).json" "$dry/keys/last.json"; }

# forge <version> <kind> <key> <release>: an index signed straight with ssh-keygen, for a
# publish to refuse on its own, as sign-connector.sh would have first: <release>'s index,
# with the time now as its serial, built on the newest index published.
forge() {
  local d="$dry/sync/releases" s f
  tick
  s="$(date +%s)"
  f="$d/$1/$2-$s"
  sed "s/\"serial\": 0,/\"serial\": $s,/" "$d/$4/index.json" > "$f.json"
  ssh-keygen -Y sign -q -f "$dry/private/$3" -n ovenlight-latest < "$f.json" > "$f.json.sig"
  newestpublished > "$f.base"
}

# header <key> <cache-control>: the object's cache, as uploaded.
header() {
  grep -qxF "Cache-Control: $2" "$dry/headers/$1" || fail "$1 isn't cached as \"$2\": $(cat "$dry/headers/$1" 2>&1)"
}
# filesof <version>: the files of its folder.
filesof() { echo "$(awk '{ print $2 }' "$dry/sync/releases/$1/SHA256SUMS") SHA256SUMS SHA256SUMS.sig"; }
folder_is() {
  local f
  for f in $(filesof "$1"); do
    [ -f "$dry/bucket/connector/$1/$f" ] || fail "no connector/$1/$f"
    header "connector/$1/$f" "public, max-age=31536000, immutable"
  done
  pass "connector/$1/ holds its files, cached for a year"
}
# latest_is <version> <key>: latest.json names it, signed with that key, and latest/ holds
# its files, each uploaded with a short cache.
latest_is() {
  local f j="$dry/bucket/connector/latest.json"
  [ "$(field latest "$j")" = "$1" ] || fail "latest.json names $(field latest "$j"), not $1"
  ssh-keygen -Y verify -f <(awk '{ print "k", $1, $2 }' "$fixtures/$2.pub") -I k -n ovenlight-latest \
    -s "$j.sig" < "$j" > /dev/null 2>&1 || fail "latest.json isn't signed by $2"
  for f in $(filesof "$1"); do
    cmp -s "$dry/bucket/connector/latest/$f" "$dry/bucket/connector/$1/$f" || fail "latest/$f isn't $1's"
    header "connector/latest/$f" "public, max-age=300"
  done
  header connector/latest.json "public, max-age=300"
  header connector/latest.json.sig "public, max-age=300"
  pass "latest.json names $1, signed by $2, and latest/ holds $1's files, both cached for 5 minutes"
}
untouched() {
  cmp -s "$dry/bucket/connector/latest.json" "$dry/1.0.9.json" || fail "latest.json changed"
  [ "$(cat "$dry/bucket/connector/latest/SHA256SUMS")" = "1.0.9's" ] || fail "latest/ changed"
  pass "latest.json and latest/ are still 1.0.9's"
}
# backdate <days>: every promote published that many days earlier.
backdate() {
  local f
  for f in "$dry"/sync/releases/*/promote-*.live; do
    echo $(($(cat "$f") - $1 * 86400)) > "$f"
  done
}

fresh phase7 "the updater release U and U+1, over 1.0.9's latest.json, then U+1 pulled back to U"
keyfolder air1 paper1
keys air1 paper1
build 1.1.0 1.0.6 1.1.0
signfolder 1.1.0 air1
untouched
build 1.1.1 1.0.6
[ "$(field reinstallBelow "$dry/build/index.json")" = 1.1.0 ] || fail "U+1's reinstallBelow isn't U's"
pass "U+1 takes U's reinstallBelow"
signfolder 1.1.1 air1
untouched
# What a promote publishes comes from the canary index signed on the Air.
sed -i '' 's/"secure": "1.0.6"/"secure": "1.1.0"/' "$dry/sync/releases/1.1.0/index.json"
promote 1.1.0 air1
[ "$(field secure "$dry/bucket/connector/latest.json")" = 1.0.6 ] || fail "the promote took secure from index.json"
pass "the promote takes secure from the signed canary index, not index.json"
promote 1.1.1 air1
pull 1.1.1 air1 1.1.0
build 1.1.1 1.0.6
refused "1.1.1 was promoted already" sign air1 1.1.1
tick
refused "nothing promoted before 1.1.0" sign air1 --pull 1.1.0
refused "with no leading zeros or suffix, not 1.01.0" release 1.01.0 1.0.6
refused "with no leading zeros or suffix, not 01.1.0" publish --promote 01.1.0

fresh canary "a failed canary before U's promote"
keyfolder air1 paper1
keys air1 paper1
build 1.1.0 1.0.6 1.1.0
run sign air1 1.1.0
# The folder goes up only once its tag is the commit it was built from.
git -C "$repo" tag -d connector-v1.1.0 > /dev/null
refused "the commit 1.1.0 was built from" publish 1.1.0
commit "after 1.1.0"
git -C "$repo" tag -m "Connector 1.1.0" connector-v1.1.0
refused "the commit 1.1.0 was built from" publish 1.1.0
git -C "$repo" tag -f -m "Connector 1.1.0" connector-v1.1.0 HEAD~1 > /dev/null
# Outside a dry run, the tag must be on origin as it is here, with defaults that point into
# the scratch folder all the same. Past that check, a fixture key stops it before anything
# is fetched or uploaded.
mkdir -p "$dry/home/Sync/ovenlight" "$repo/build"
ln -s "$dry/sync/releases" "$dry/home/Sync/ovenlight/releases"
ln -s "$dry/build" "$repo/build/release"
realpublish() { env -u OVENLIGHT_RELEASE_DRY HOME="$dry/home" "$repo/scripts/publish-connector.sh" "$@"; }
refused "connector-v1.1.0 isn't pushed to origin as it is here" realpublish 1.1.0
git init -q --bare "$dry/origin.git"
git -C "$repo" remote add origin "$dry/origin.git"
git -C "$repo" push -q origin HEAD:refs/tags/connector-v1.1.0
refused "connector-v1.1.0 isn't pushed to origin as it is here" realpublish 1.1.0
git -C "$repo" push -q -f origin connector-v1.1.0
refused "is a dry-run fixture key" realpublish 1.1.0
run publish 1.1.0
folder_is 1.1.0
refused "no index matching 1.1.0/promote-*" publish --promote 1.1.0
refused "isn't the live release (nothing is promoted)" sign air1 --pull 1.1.0
refused "no index matching 1.1.0/pull-*" publish --pull 1.1.0
refused "no index is published yet" sign air1 --resign
untouched

fresh pullback "B promoted, C's canary failing, then B pulled back to A"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
ship 1.1.1 1.0.6 air1
build 1.1.2 1.0.6
signfolder 1.1.2 air1
latest_is 1.1.1 air1
# An agent holding ~/Sync copies 1.1.2's canary index, which failed, into the live release's
# folder.
d="$dry/sync/releases"
cp "$d"/1.1.1/canary.json* "$dry/"
cp "$d"/1.1.2/canary.json* "$d/1.1.1/"
tick
refused "is 1.1.2's index, not 1.1.1's" sign air1 --resign
cp "$dry"/canary.json* "$d/1.1.1/"
refused "1.1.2 isn't the live release (1.1.1)" sign air1 --pull 1.1.2
forge 1.1.2 pull air1 1.1.1
refused "1.1.2 isn't the live release (1.1.1)" publish --pull 1.1.2
pull 1.1.1 air1 1.1.0
refused "nothing promoted before 1.1.0" sign air1 --pull 1.1.0
# An agent holding ~/Sync deletes the pull's records, so a resign would bring 1.1.1 back, or
# makes up a promote of 1.1.2, which was never promoted.
mkdir "$dry/hidden"
mv "$dry"/sync/releases/1.1.1/pull-* "$dry/hidden/"
tick
refused "the last one this Air signed" sign air1 --resign
mv "$dry"/hidden/* "$dry/sync/releases/1.1.1/"
forge 1.1.2 promote air2 1.1.2
for f in "$dry"/sync/releases/1.1.2/promote-*.json; do date +%s > "${f%.json}.published"; done
refused "isn't signed by a key in" sign air1 --resign

fresh stale "a pull gone stale before it's published"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
ship 1.1.1 1.0.6 air1
tick
run sign air1 --pull 1.1.1
# The pull is signed and never published, so the Air signs again only once the owner checks.
tick
refused "the last one this Air signed" sign air1 --resign
lastchecked
resign air1 1.1.1
refused "sign a fresh one" publish --pull 1.1.1
pull 1.1.1 air1 1.1.0
# An agent holding ~/Sync swaps an archive's hash, signs SHA256SUMS and the canary index to
# match with a key of its own, and lists that key in every release's release_keys.
d="$dry/sync/releases/1.1.0"
h="$(head -c 8 "$d/SHA256SUMS")"
sed -i '' "s/$h/deadbeef/" "$d/SHA256SUMS" "$d/canary.json"
ssh-keygen -Y sign -q -f "$dry/private/air2" -n file < "$d/SHA256SUMS" > "$d/SHA256SUMS.sig"
ssh-keygen -Y sign -q -f "$dry/private/air2" -n ovenlight-canary < "$d/canary.json" > "$d/canary.json.sig"
for f in "$dry"/sync/releases/*/release_keys; do cat "$fixtures/air2.pub" >> "$f"; done
tick
refused "1.1.0 isn't signed by a key in" sign air1 --resign

fresh early "a promote signed before a pull"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
ship 1.1.1 1.0.6 air1
build 1.1.2 1.0.6
signfolder 1.1.2 air1
tick
run sign air1 --promote 1.1.2
lastchecked
pull 1.1.1 air1 1.1.0
refused "sign a fresh one" publish --promote 1.1.2
promote 1.1.2 air1

fresh rerun "a publish that fails after writing its record, finished by running it again"
keyfolder air1 paper1
keys air1 paper1
build 1.1.0 1.0.6 1.1.0
signfolder 1.1.0 air1
tick
run sign air1 --promote 1.1.0
chmod 444 "$dry/bucket/connector/latest.json"
if publish --promote 1.1.0 > "$dry/log" 2>&1; then fail "the upload of latest.json didn't fail"; fi
chmod 644 "$dry/bucket/connector/latest.json"
records=("$dry"/sync/releases/1.1.0/promote-*.published)
[ ${#records[@]} -eq 1 ] || fail "no record of the failed publish"
cmp -s "$dry/bucket/connector/latest.json" "$dry/1.0.9.json" || fail "latest.json changed"
pass "the failed publish left its record, and latest.json as it was"
# Another promote signed meanwhile, on an Air the record hadn't reached yet.
forge 1.1.0 promote air1 1.1.0
run publish --promote 1.1.0
grep -q "^finishing the publish of 1.1.0/promote-" "$dry/log" || fail "the rerun didn't finish the publish"
pass "the rerun finished it: $(head -1 "$dry/log")"
latest_is 1.1.0 air1

fresh lost "a lost Air: the backup key brings in two new keys, and signs a pull"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
# The replacement Air makes two new keys, and the release lists them and the paper key. It
# has no last.json until the owner checks.
keyfolder air2 paper2
rm "$dry/keys/last.json" "$dry/keys/published"
keys air2 paper2 paper1
build 1.1.1 1.0.6
refused "isn't the two keys in" sign air2 1.1.1
signfolder 1.1.1 paper1
tick
refused "the last one this Air signed" sign paper1 --promote 1.1.1
lastchecked
promote 1.1.1 paper1
pull 1.1.1 paper1 1.1.0
# The pulled rotation leaves owners on the old keys, so the next release repeats it.
build 1.1.2 1.0.6
signfolder 1.1.2 paper1
promote 1.1.2 paper1
# An agent holding ~/Sync deletes the pull's record of being published, so the next pull
# would go back to the release it pulled.
rm "$dry"/sync/releases/1.1.1/pull-*.published
tick
refused "no longer published: 1.1.1/pull-" sign paper1 --pull 1.1.2

fresh lostpaper "a lost paper key, then a lost Air: the Air's promotes don't restart the new paper key's wait"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
# R lists the Air's key and a new paper key, whose wait starts at R's promote.
keyfolder air1 paper2
keys air1 paper2
signers air1 paper2
ship 1.1.1 1.0.6 air1
tick
refused "four weeks and four days" sign paper2 --resign
# A release that mustn't ship goes out, and the new paper key pulls it, as it would after a
# lost Air, without waiting.
ship 1.1.2 1.0.6 air1
pull 1.1.2 paper2 1.1.1
backdate 33
ship 1.1.4 1.0.6 air1
# The Air is lost, and the new paper key signs what the next R needs at once, with paper1
# standing in for the paper key after it.
keyfolder air2 paper1
keys air2 paper1 paper2
build 1.1.5 1.0.6
signfolder 1.1.5 paper2
resign paper2 1.1.4
promote 1.1.5 paper2

fresh paperpull "the Air that signed the live index lost before its pull, which the paper key signs"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
ship 1.1.1 1.0.6 air1
tick
refused "1.1.0/release_keys doesn't list its key" sign paper2 --pull 1.1.1
forge 1.1.1 pull paper2 1.1.0
refused "isn't signed by a key any release lists" publish --pull 1.1.1
# A key that only a release not yet promoted lists can't sign it either.
keyfolder air2 paper1
keys air2 paper1 air1
build 1.1.2 1.0.6
signfolder 1.1.2 air1
forge 1.1.1 pull air2 1.1.0
refused "the index it replaces, and" publish --pull 1.1.1
pull 1.1.1 paper1 1.1.0

fresh move "a planned move to a new Air, with releases inside and after its four weeks"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
# The new Air's key replaces the old one's in the old Air's key folder, and the old Air signs.
keyfolder air2 paper1
keys air2 paper1 air1
build 1.1.1 1.0.6
signfolder 1.1.1 air1
promote 1.1.1 air1
# Needed within the four weeks: signed with the old key, listing it, and pulled with it.
build 1.1.2 1.0.6
signfolder 1.1.2 air1
promote 1.1.2 air1
# Computers asleep on 1.1.0 hold only the old keys, so the new key can't sign the pull yet.
tick
refused "four weeks and four days" sign air2 --pull 1.1.2
pull 1.1.2 air1 1.1.1
# The release after drops the old key, and the new key alone waits four weeks and four days.
keys air2 paper1
build 1.1.3 1.0.6
signfolder 1.1.3 air2
tick
refused "four weeks and four days" sign air2 --promote 1.1.3
tick
refused "four weeks and four days" sign air2 --resign
forge 1.1.3 promote air2 1.1.3
refused "four weeks and four days" publish --promote 1.1.3
forge 1.1.1 resign air2 1.1.1
refused "four weeks and four days" publish --resign
backdate 33
# The live index re-signed with the new key before the canary, once master's
# docs/allowed_signers, which owners check latest.json with, lists that key. Then the promote
# and a pull.
signers air1 paper1
tick
run sign air2 --resign
refused "which owners check latest.json with" publish --resign
signers air1 paper1 air2
run publish --resign
latest_is 1.1.1 air2
promote 1.1.3 air2
signers air2 paper1
tick
run sign air2 --pull 1.1.3
refused "add its key back first" publish --pull 1.1.3
signers air2 paper1 air1
run publish --pull 1.1.3
latest_is 1.1.1 air2
# Computers that took 1.1.3 keep it, so the live index can't be signed again with the key it
# dropped.
tick
refused "1.1.3/release_keys doesn't list this key" sign air1 --resign
forge 1.1.1 resign air1 1.1.1
refused "1.1.3/release_keys lists" publish --resign
# 1.1.3, which dropped the old key, was pulled, so the next release is checked against its
# keys too: the old key can't sign it.
keys air2 paper1 air1
build 1.1.4 1.0.6
refused "1.1.3/release_keys doesn't list this key" sign air1 1.1.4
keys air2 paper1
build 1.1.4 1.0.6
signfolder 1.1.4 air2
promote 1.1.4 air2

fresh rule "a release signed while its key was trusted, then promoted after a pull took that trust away"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
keyfolder air2 paper1
keys air2 paper1 air1
ship 1.1.1 1.0.6 air1
# Signed while 1.1.1, which lists air2, is live. 1.1.0, which doesn't, is live again after the
# pull, so the computers that would install 1.1.2 can't check air2.
keys air2 paper1
build 1.1.2 1.0.6
signfolder 1.1.2 air2
pull 1.1.1 air1 1.1.0
tick
refused "1.1.0/release_keys doesn't list this key" sign air2 --promote 1.1.2
forge 1.1.2 promote air2 1.1.2
refused "1.1.0/release_keys, which updaters installing 1.1.2 hold" publish --promote 1.1.2

fresh secure "a signed promote that raises secure and mustn't ship, superseded with --resign"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
ship 1.1.1 1.0.6 air1
build 1.1.2 1.1.2
signfolder 1.1.2 air1
tick
run sign air1 --promote 1.1.2
lastchecked
resign air1 1.1.1
[ "$(field secure "$dry/bucket/connector/latest.json")" = 1.0.6 ] || fail "secure went up"
pass "secure is still 1.0.6"
refused "sign a fresh one" publish --promote 1.1.2
# An agent holding ~/Sync marks that promote published, so a resign would sign it again.
for f in "$dry"/sync/releases/1.1.2/promote-*.json; do date +%s > "${f%.json}.published"; done
tick
refused "newly published: 1.1.2/promote-" sign air1 --resign
rm "$dry"/sync/releases/1.1.2/promote-*.published
refused "1.1.3 can't go below either" release 1.1.3 1.0.5
forge 1.1.0 promote air1 1.1.0
refused "1.1.0 was promoted already" publish --promote 1.1.0
build 1.1.4 1.1.2
signfolder 1.1.4 air1
forge 1.1.4 promote paper1 1.1.4
refused "are signed with different keys" publish --promote 1.1.4

fresh lower "a release signed but not promoted keeps what it raised"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
# 1.1.2 is signed before 1.1.1 raises secure and reinstallBelow, so only its promote refuses it.
build 1.1.2 1.0.6
signfolder 1.1.2 air1
# A 1.1.3 built before 1.1.1 is signed is refused when it's signed, and one built after it,
# before it's built.
build 1.1.3 1.0.6
mv "$dry/build" "$dry/build-1.1.3"
build 1.1.1 1.1.1 1.1.1
signfolder 1.1.1 air1
rm -r "$dry/build"
mv "$dry/build-1.1.3" "$dry/build"
refused "1.1.3 can't go below either" sign air1 1.1.3
refused "1.1.3 can't go below either" release 1.1.3 1.0.6
refused "1.1.3 can't go below either" release 1.1.3 1.1.1 1.1.0
promote 1.1.1 air1
tick
refused "secure would go down" sign air1 --promote 1.1.2
forge 1.1.2 promote air1 1.1.2
refused "secure would go down" publish --promote 1.1.2

fresh repull "a rotation pulled back and repeated: the new key's wait starts again"
keyfolder air1 paper1
keys air1 paper1
ship 1.1.0 1.0.6 air1 1.1.0
keyfolder air2 paper1
keys air2 paper1 air1
ship 1.1.1 1.0.6 air1
backdate 33
pull 1.1.1 air1 1.1.0
ship 1.1.2 1.0.6 air1
tick
refused "four weeks and four days" sign air2 --resign

fresh gates "what no release gets past"
keyfolder air1 paper1
ssh-keygen -q -t ed25519 -N '' -C stranger -f "$dry/private/stranger"
{ cat "$fixtures/air1.pub" "$fixtures/paper1.pub" "$root/connector/testdata/releasekey/test-key.pub"; } > "$repo/connector/release_keys"
commit "the test key"
refused "holds a release key whose private half is public" release 1.1.0 1.0.6 1.1.0
# A dry run builds with the mock release keys, which stand in for the real ones until they're
# made, and a real build refuses them before it builds anything. Its defaults point into the
# scratch folder all the same. The mock keys stay after the key swap.
mocks=("$root"/connector/testdata/releasekey/mock-{touchid,backup}.pub)
cat "$fixtures/air1.pub" "$fixtures/paper1.pub" "${mocks[@]}" > "$repo/connector/release_keys"
commit "the mock keys"
build 1.1.0 1.0.6 1.1.0
pass "a dry run builds with the mock release keys"
mkdir -p "$dry/home"
refused "holds a release key whose private half is public" env -u OVENLIGHT_RELEASE_DRY HOME="$dry/home" \
  "$repo/scripts/release-connector.sh" --unsigned 1.1.0 1.0.6 1.1.0
# A key swap that leaves a mock key in docs/allowed_signers would let owners' own check pass
# what its public private half signs, so a real build refuses that too.
cat "$dry/private/stranger.pub" > "$repo/connector/release_keys"
awk '{ print "team@snowyghost.com namespaces=\"file,ovenlight-latest\"", $1, $2 }' "${mocks[@]}" >> "$repo/docs/allowed_signers"
commit "a mock key left in allowed_signers"
refused "docs/allowed_signers holds a release key whose private half is public" env -u OVENLIGHT_RELEASE_DRY \
  HOME="$dry/home" "$repo/scripts/release-connector.sh" --unsigned 1.1.0 1.0.6 1.1.0
# So does one left with a dry run's fixture key, whose private half is public too.
cat "$dry/private/stranger.pub" > "$repo/connector/release_keys"
signers air1
refused "docs/allowed_signers holds a release key whose private half is public" env -u OVENLIGHT_RELEASE_DRY \
  HOME="$dry/home" "$repo/scripts/release-connector.sh" --unsigned 1.1.0 1.0.6 1.1.0
# And one left with a key in another namespace, such as an old git key. Comments and blank
# lines aren't keys, so with them alone beside a release key it goes on, to a later refusal.
{ echo "# a comment"; echo; awk '{ print "team@snowyghost.com namespaces=\"file\"", $1, $2 }' "$dry/private/stranger.pub"; } > "$repo/docs/allowed_signers"
commit "an old git key left in allowed_signers"
refused "lists a key in a namespace other than the release keys'" release 1.1.0 1.0.6 1.1.0
{ echo "# a comment"; echo; awk '{ print "team@snowyghost.com namespaces=\"file,ovenlight-latest\"", $1, $2 }' "$dry/private/stranger.pub"; } > "$repo/docs/allowed_signers"
commit "a release key and a comment"
refused "secure (1.2.0) is newer than the release (1.1.0)" release 1.1.0 1.2.0 1.1.0
signers air1 paper1
keys air1 paper1
refused "a dry run builds only with --unsigned" "$repo/scripts/release-connector.sh" 1.1.0 1.0.6 1.1.0
build 1.1.0 1.0.6 1.1.0
refused "a dry run trusts only the fixture keys" sign stranger 1.1.0
refused "a dry run signs with --key" env OVENLIGHT_RELEASE_DRY="$dry" "$repo/scripts/sign-connector.sh" 1.1.0
refused "isn't a folder" env OVENLIGHT_RELEASE_DRY= "$repo/scripts/sign-connector.sh" --key "$dry/private/air1" 1.1.0
mkdir -p "$dry/home/Sync/ovenlight" "$dry/home/.ssh"
refused "can't be in ~/Sync or ~/.ssh" env HOME="$dry/home" OVENLIGHT_RELEASE_DRY="$dry/home/Sync/ovenlight" "$repo/scripts/sign-connector.sh" --key "$dry/private/air1" 1.1.0
refused "can't be in ~/Sync or ~/.ssh" env HOME="$dry/home" OVENLIGHT_RELEASE_DRY="$dry/home/.ssh" "$repo/scripts/publish-connector.sh" 1.1.0
refused "has no .release-dry-run" env OVENLIGHT_RELEASE_DRY="$repo" "$repo/scripts/release-connector.sh" --unsigned 1.1.0 1.0.6 1.1.0
# Outside a dry run, with defaults that point into the scratch folder all the same, a fixture
# key never signs. It's refused before the mini is asked the time.
refused "is a dry-run fixture key" env -u OVENLIGHT_RELEASE_DRY HOME="$dry/home" \
  "$repo/scripts/sign-connector.sh" --key "$dry/private/air1" 1.1.0
refused "isn't the two keys in" sign paper2 1.1.0
refused "the paper key signs only the release that brings in its successor" sign paper1 1.1.0
# An archive with another version's folder in it.
a=ovenlight-connector-linux-amd64.tar.gz
old="$(awk -v a="$a" '$2 == a { print $1 }' "$dry/build/SHA256SUMS")"
mkdir -p "$dry/other/ovenlight-connector-1.0.9"
echo stand-in > "$dry/other/ovenlight-connector-1.0.9/ovenlight"
tar czf "$dry/build/$a" -C "$dry/other" ovenlight-connector-1.0.9
(cd "$dry/build" && shasum -a 256 ovenlight-connector-* > SHA256SUMS)
new="$(awk -v a="$a" '$2 == a { print $1 }' "$dry/build/SHA256SUMS")"
sed -i '' "s/$old/$new/" "$dry/build/index.json"
run sign air1 1.1.0
refused "holds more than an ovenlight-connector-1.1.0/ folder" publish 1.1.0
# A build over the updater's cap, which publish reads from connector/keys.go.
sed -i '' 's|^const maxArchiveBytes = .*|const maxArchiveBytes = 1 << 10 // 1 KiB|' "$repo/connector/keys.go"
commit "a 1 KiB cap"
build 1.1.1 1.0.6
run sign air1 1.1.1
refused "over the updater's cap of 1024 bytes" publish 1.1.1
build 1.1.0 1.0.6 1.1.0
refused "another build of 1.1.0 is signed already" sign air1 1.1.0

echo "all $checks checks passed"
