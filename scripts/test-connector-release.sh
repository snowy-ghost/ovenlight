#!/bin/bash
# Dry runs of the connector release scripts through each case the updater's release process
# must handle: promotes, pulls, re-signs, a publish that fails partway, a lost Air and a key
# rotation. Each scenario gets a scratch folder (OVENLIGHT_RELEASE_DRY) standing in for the
# bucket, ~/Sync/ovenlight, ~/.ssh/ovenlight-release-keys and build/release on the mini, and
# a scratch repo holding this tree's release scripts, committed whenever a release's keys
# change. Every release is built (with stand-in binaries), signed with the fixture keys in
# scripts/testdata/release-keys, and published before the next is built. The four weeks a
# new key waits pass on a clock of the scenario's own.
#
#   scripts/test-connector-release.sh [<scenario>...]
#
# runs those scenarios (the names after scenario_ below), or every one, each in a process of
# its own, $JOBS at a time (4 unless set).
#
# It touches nothing outside its scratch folder, which it deletes when it's done.
set -euo pipefail
shopt -s nullglob

root="$(cd "$(dirname "$0")/.." && pwd)"
# A fixture key is made with ssh-keygen -q -N '' -t ecdsa -b 256 (-t ed25519 for a paper key)
# -C 'ovenlight dry-run fixture key <name>, public on purpose' -f <its file in there>.
fixtures="$root/scripts/testdata/release-keys"
tmp="$(mktemp -d)"
trap 'rm -r "$tmp"' EXIT
# Every date +%s reads the scenario's clock, which later sets ahead of the real one.
mkdir "$tmp/bin"
cat > "$tmp/bin/date" <<'EOF'
#!/bin/sh
if [ "$1" = +%s ]; then echo $(($(/bin/date +%s) + ${ahead:-0})); else exec /bin/date "$@"; fi
EOF
# rsync fails while the scenario's folder holds a nosend file, as when the Air can't reach the
# mini.
cat > "$tmp/bin/rsync" <<'EOF'
#!/bin/sh
if [ -e "$OVENLIGHT_RELEASE_DRY/nosend" ]; then exit 1; fi
exec /usr/bin/rsync "$@"
EOF
chmod +x "$tmp/bin/date" "$tmp/bin/rsync"
export PATH="$tmp/bin:$PATH"
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
  mkdir -p "$dry"/{build,sync/releases,mini,keys,private,bucket/connector/latest,headers/connector/latest} "$repo"
  touch "$dry/.release-dry-run"
  export OVENLIGHT_RELEASE_DRY="$dry" ahead=0
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

# keyfolder <Touch ID key> <paper key>: the Air's ~/.ssh/ovenlight-release-keys holds those
# fixture keys as touchid.pub and backup.pub.
keyfolder() {
  cp "$fixtures/$1.pub" "$dry/keys/touchid.pub"
  cp "$fixtures/$2.pub" "$dry/keys/backup.pub"
}

# swapair: in a planned move, the other Air's key folder takes this one's place. carry: that
# Air's history is merged into this one's. newair: a replacement Air, with an empty key folder.
swapair() { mv "$dry/keys" "$dry/air"; mv "$dry/otherair" "$dry/keys"; mv "$dry/air" "$dry/otherair"; }
# macOS's cp -n exits 1 when it skips a file.
carry() { cp -Rn "$dry/otherair/history/" "$dry/keys/history" || true; }
newair() { mv "$dry/keys" "$dry/lostair"; mkdir "$dry/keys"; }

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
# nokey <option>...: sign-connector.sh on the Air, which never writes to its ~/Sync, so no
# link an agent syncs there can redirect a write. What it sends the mini, in mini/, is then
# filed in the records, which stand in for the mini's own folder.
nokey() {
  local s=0 d f t
  chmod -R a-w "$dry/sync"
  "$repo/scripts/sign-connector.sh" "$@" || s=$?
  chmod -R u+w "$dry/sync"
  for d in "$dry"/mini/releases/*; do
    t="$dry/sync/releases/${d##*/}"
    mkdir -p "$t"
    for f in "$d"/*; do mv "$f" "$t/"; done
    rmdir "$d"
  done
  return $s
}
# sign <key> <option>...: signed with that fixture key.
sign() {
  local k="$1"
  shift
  nokey --key "$dry/private/$k" "$@"
}
publish() { "$repo/scripts/publish-connector.sh" "$@"; }
# land <option>...: published on the mini, then seen by --check on the Air, which stands in
# for the wait a dry run skips.
land() {
  run publish "$@"
  run nokey --check
}

# Serials are seconds, and each must be above the last one signed, so the scenario's clock
# steps a second on.
tick() { ahead=$((ahead + 1)); }

# build <version> <secure> [<reinstallBelow>]: built; tagbuilt <version>: tagged on the
# build's commit, as development.md says once it's signed, and never moved, as a pushed
# tag can't be; signfolder <version> <key>: the folder signed, tagged and published.
build() { run release "$@"; }
tagbuilt() {
  local c
  read -r c _ < "$dry/build/commit"
  git -C "$repo" tag -m "Connector $1" "connector-v$1" "$c" > /dev/null || fail "connector-v$1 is tagged already"
}
signfolder() {
  run sign "$2" "$1"
  tagbuilt "$1"
  run publish "$1"
  folder_is "$1"
}
promote() {
  tick
  run sign "$2" --promote "$1"
  land --promote "$1"
  latest_is "$1" "$2"
}
# pull <version> <key> <back to>
pull() {
  tick
  run sign "$2" --pull "$1"
  land --pull "$1"
  latest_is "$3" "$2"
}
# resign <key> <live>
resign() {
  tick
  run sign "$1" --resign
  land --resign
  latest_is "$2" "$1"
}
# ship <version> <secure> <key> [<reinstallBelow>]: built, signed, published and promoted.
ship() {
  build "$1" "$2" ${4:+"$4"}
  signfolder "$1" "$3"
  promote "$1" "$3"
}

# newestlive: the newest index the mini's records mark live.
newestlive() {
  local d="$dry/sync/releases" f
  for f in "$d"/*/*.live; do f="${f#"$d/"}"; echo "${f%.live}"; done | sort -t- -k2,2n | tail -1
}
# lastsigned <version>: the index this Air signed last names that release.
lastsigned() {
  local f last=
  for f in "$dry"/keys/history/*/*-[0-9]*.json; do
    if [ -z "$last" ] || [[ "${f##*-}" > "${last##*-}" ]]; then last="$f"; fi
  done
  [ "$(field latest "$last")" = "$1" ] || fail "the Air signed an index naming $(field latest "$last"), not $1"
  pass "the Air signed an index naming $1"
}

# forge <version> <kind> <key> <release>: an index signed straight with ssh-keygen, for a
# publish to refuse on its own, as sign-connector.sh would have first: <release>'s index,
# with the time now as its serial, built on the newest index published. It's in $forged.
forge() {
  local d="$dry/sync/releases" s
  tick
  s="$(date +%s)"
  forged="$d/$1/$2-$s"
  sed -E "s/(\"serial\": )[0-9]+/\1$s/" "$d/$4/canary.json" > "$forged.json"
  ssh-keygen -Y sign -q -f "$dry/private/$3" -n ovenlight-latest < "$forged.json" > "$forged.json.sig"
  newestlive > "$forged.base"
}
# cutshort <key> <option>...: signed on the Air, and its publish fails at latest.json.
cutshort() {
  local k="$1"
  shift
  tick
  run sign "$k" "$@"
  chmod 444 "$dry/bucket/connector/latest.json"
  if publish "$@" > "$dry/log" 2>&1; then fail "the upload of latest.json didn't fail"; fi
  chmod 644 "$dry/bucket/connector/latest.json"
}
# show <version>/<kind>: latest.json is that index, as a publish of it landing late makes it,
# or whoever holds the bucket.
show() {
  local p=("$dry/sync/releases/$1"-*.json)
  cp "${p[0]}" "$dry/bucket/connector/latest.json"
  cp "${p[0]}.sig" "$dry/bucket/connector/latest.json.sig"
}
# checked <text>: --check says it.
checked() {
  run nokey --check
  grep -qF -- "$1" "$dry/log" || { cat "$dry/log" >&2; fail "--check didn't say \"$1\""; }
  pass "--check: $(tail -1 "$dry/log")"
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
# realpublish <option>...: publish-connector.sh outside a dry run, with a home of the scenario's.
realpublish() { env -u OVENLIGHT_RELEASE_DRY HOME="$dry/home" "$repo/scripts/publish-connector.sh" "$@"; }
# later <days>: the scenario's clock moves that many days on.
later() { ahead=$((ahead + $1 * 86400)); }

scenario_phase7() {
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
  promote 1.1.0 air1
  promote 1.1.1 air1
  pull 1.1.1 air1 1.1.0
  # Whoever holds the bucket rolls latest.json back to the promote before, which the Air saw.
  d="$dry/sync/releases"
  cp "$d"/1.1.1/promote-*.json "$dry/bucket/connector/latest.json"
  cp "$d"/1.1.1/promote-*.json.sig "$dry/bucket/connector/latest.json.sig"
  refused "older than 1.1.1/pull-" nokey --check
  land --pull 1.1.1
  latest_is 1.1.0 air1
  build 1.1.1 1.0.6
  refused "1.1.1 was promoted already" sign air1 1.1.1
  tick
  refused "nothing promoted before 1.1.0" sign air1 --pull 1.1.0
  refused "with no leading zeros or suffix, not 1.01.0" release 1.01.0 1.0.6 1.1.0
  refused "with no leading zeros or suffix, not 01.1.0" publish --promote 01.1.0
}

scenario_canary() {
  fresh canary "a failed canary before U's promote"
  keyfolder air1 paper1
  keys air1 paper1
  build 1.1.0 1.0.6 1.1.0
  run sign air1 1.1.0
  # The folder goes up only once its tag is the commit it was built from.
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
  refused "connector-v1.1.0 isn't pushed to origin as it is here" realpublish 1.1.0
  git init -q --bare "$dry/origin.git"
  git -C "$repo" remote add origin "$dry/origin.git"
  git -C "$repo" push -q origin HEAD:refs/tags/connector-v1.1.0
  refused "connector-v1.1.0 isn't pushed to origin as it is here" realpublish 1.1.0
  git -C "$repo" push -q -f origin connector-v1.1.0
  refused "is a dry-run fixture key" realpublish 1.1.0
  run publish 1.1.0
  folder_is 1.1.0
  refused "no promote, pull or resign is signed" publish --promote 1.1.0
  refused "isn't the live release (nothing is promoted)" sign air1 --pull 1.1.0
  refused "no promote, pull or resign is signed" publish --pull 1.1.0
  refused "no index is published yet" sign air1 --resign
  untouched
}

scenario_pullback() {
  fresh pullback "B promoted, C's canary failing, then B pulled back to A"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  latest_is 1.1.1 air1
  # An agent holding ~/Sync copies 1.1.2's canary index, which failed, into the live release's
  # folder, and renames the live promote into 1.1.2's, so a resign would make 1.1.2 live. The
  # Air reads neither.
  d="$dry/sync/releases"
  mkdir "$dry/hidden"
  cp "$d"/1.1.1/canary.json* "$dry/hidden/"
  cp "$d"/1.1.2/canary.json* "$d/1.1.1/"
  n="$(newestlive)"
  for f in "$d/$n".*; do mv "$f" "$d/1.1.2/${f#"$d/1.1.1/"}"; done
  tick
  run sign air1 --resign
  lastsigned 1.1.1
  mv "$dry"/hidden/* "$d/1.1.1/"
  for f in "$d"/1.1.2/promote-*; do mv "$f" "$d/1.1.1/"; done
  refused "1.1.2 isn't the live release (1.1.1)" sign air1 --pull 1.1.2
  forge 1.1.2 pull air1 1.1.1
  refused "1.1.2 isn't the live release (1.1.1)" publish --pull 1.1.2
  pull 1.1.1 air1 1.1.0
  # It deletes the pull's records, so a resign would bring 1.1.1 back.
  mv "$d"/1.1.1/pull-* "$dry/hidden/"
  tick
  run sign air1 --resign
  lastsigned 1.1.0
}

scenario_stale() {
  fresh stale "a pull signed and not yet published"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  tick
  run sign air1 --pull 1.1.1
  # Until it's published, or a fresh pull is, the Air signs no promote or resign.
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  tick
  refused "isn't published: publish it on the mini, or sign a fresh pull" sign air1 --promote 1.1.2
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
  run sign air1 --resign
  ! grep -q deadbeef "$dry"/keys/history/1.1.0/resign-*.json || fail "the resign took the hash from ~/Sync"
  pass "the resign takes 1.1.0's index from the Air's history"
}

scenario_early() {
  fresh early "a promote signed before a pull"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  tick
  run sign air1 --promote 1.1.2
  pull 1.1.1 air1 1.1.0
  refused "If not, scripts/publish-connector.sh --pull 1.1.1" publish --promote 1.1.2
  promote 1.1.2 air1
}

scenario_rerun() {
  fresh rerun "a publish cut short or uploaded already, finished or uploaded again by running it again"
  keyfolder air1 paper1
  keys air1 paper1
  build 1.1.0 1.0.6 1.1.0
  signfolder 1.1.0 air1
  cutshort air1 --promote 1.1.0
  cmp -s "$dry/bucket/connector/latest.json" "$dry/1.0.9.json" || fail "latest.json changed"
  pass "the publish cut short left latest.json as it was"
  land --promote 1.1.0
  latest_is 1.1.0 air1
  run publish --promote 1.1.0
  latest_is 1.1.0 air1
  ship 1.1.1 1.0.6 air1
  cutshort air1 --pull 1.1.1
  run publish --pull 1.1.1
  land --pull 1.1.1
  latest_is 1.1.0 air1
  cutshort air1 --resign
  land --resign
  latest_is 1.1.0 air1
  # A send from the Air cut short can leave the mini a promote without its .base, or with an
  # empty one, and --check on the Air sends it again.
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  tick
  run sign air1 --promote 1.1.2
  rm "$dry"/sync/releases/1.1.2/promote-*.base
  refused "--check on the Air sends the books again" publish --promote 1.1.2
  p=("$dry"/sync/releases/1.1.2/promote-*.json)
  : > "${p[0]%.json}.base"
  refused "--check on the Air sends the books again" publish --promote 1.1.2
  run nokey --check
  land --promote 1.1.2
  latest_is 1.1.2 air1
}

scenario_dropped() {
  fresh dropped "a promote, pull and resign each cut short and dropped by the next signature, which supersedes them"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  # The promote cut short didn't go out, so a resign drops it.
  cutshort air1 --promote 1.1.1
  checked "latest.json is 1.1.0/promote-"
  tick
  run sign air1 --resign
  refused "If not, scripts/publish-connector.sh --resign" publish --promote 1.1.1
  # Its publish lands late, before the resign goes up, or whoever holds the bucket shows it
  # after. Either way the Air never counts it.
  show 1.1.1/promote
  refused "which 1.1.0/resign-" nokey --check
  run publish --resign
  checked "so 1.1.0 is live"
  latest_is 1.1.0 air1
  # So the next release's pull goes back to 1.1.0.
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  promote 1.1.2 air1
  # A pull cut short is dropped by a fresh pull, and a resign by a fresh resign.
  cutshort air1 --pull 1.1.2
  checked "latest.json is 1.1.2/promote-"
  pull 1.1.2 air1 1.1.0
  cutshort air1 --resign
  checked "latest.json is 1.1.2/pull-"
  resign air1 1.1.0
}

scenario_unsent() {
  fresh unsent "a resign dropping a promote cut short, whose send to the mini fails, sent again by the Air"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  cutshort air1 --promote 1.1.1
  checked "latest.json is 1.1.0/promote-"
  tick
  touch "$dry/nosend"
  refused "couldn't send what it signed to the mini: run this again" sign air1 --resign
  rm "$dry/nosend"
  # The mini still holds the promote as the newest, and points back to the Air before it.
  refused "If the Air holds a newer one, signed since or lost here, scripts/sign-connector.sh --check there sends it here. If not, scripts/publish-connector.sh --promote 1.1.1" publish --resign
  # The promote's publish lands late: --check refuses it, and sends the resign.
  show 1.1.1/promote
  refused "superseded before this Air saw it go up: publish 1.1.0/resign-" nokey --check
  land --resign
  latest_is 1.1.0 air1
}

scenario_mended() {
  fresh mended "published indexes the mini lost or holds changed, sent again by the Air"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  p=("$dry"/sync/releases/1.1.1/promote-*.json)
  rm "${p[0]}" "${p[0]}.sig" "${p[0]%.json}.base" "${p[0]%.json}.live"
  p=("$dry"/sync/releases/1.1.0/promote-*.json)
  : > "${p[0]%.json}.base"
  refused "--check on the Air sends the books again" publish --resign
  run nokey --check
  resign air1 1.1.1
  pull 1.1.1 air1 1.1.0
}

scenario_movedout() {
  fresh movedout "a promoted release's folder the mini lost, sent again by the Air, then the records reset"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  tick
  run sign air1 --resign
  rm "$dry"/sync/releases/1.1.0/{release_keys,canary.json,canary.json.sig}
  if publish --resign > "$dry/log" 2>&1; then fail "published with 1.1.0's signed files gone"; fi
  tail -1 "$dry/log" | grep -qF "/sync/releases/1.1.0/release_keys" || { cat "$dry/log" >&2; fail "the refusal doesn't end on the missing file"; }
  pass "refused: $(tail -1 "$dry/log")"
  rm "$dry"/sync/releases/1.1.1/resign-*.json.sig
  refused "has no .sig in" publish --resign
  mv "$dry/sync/releases/1.1.0" "$dry/lost"
  refused "--check on the Air sends the books again" publish --resign
  run nokey --check
  land --resign
  latest_is 1.1.1 air1
  pull 1.1.1 air1 1.1.0
  # An Air whose history doesn't hold latest.json sends the mini none of its books.
  mv "$dry/sync/releases" "$dry/reset"
  mkdir "$dry/sync/releases"
  cp "$dry/1.0.9.json" "$dry/bucket/connector/latest.json"
  refused "latest.json is unsigned, and it was 1.1.1/pull-" nokey --check
  [ -z "$(ls "$dry/sync/releases")" ] || fail "the Air sent its books into the reset records"
  pass "the reset records are still empty"
}

scenario_behind() {
  fresh behind "a release folder the mini lost before its promote, then the mini's newest indexes lost"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  # Its promote takes the folder's signed files along.
  mv "$dry/sync/releases/1.1.1" "$dry/lost"
  promote 1.1.1 air1
  # Records that lack the index latest.json is, as an older backup restored leaves them,
  # never take latest.json back.
  p=("$dry"/sync/releases/1.1.1/promote-*)
  rm "${p[@]}"
  refused "latest.json was signed after 1.1.0/promote-" publish --promote 1.1.0
  run nokey --check
  resign air1 1.1.1
}

scenario_droppull() {
  fresh droppull "a promote cut short and dropped by a pull of the release before it, then shown to the Air"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  cutshort air1 --promote 1.1.2
  checked "latest.json is 1.1.1/promote-"
  tick
  run sign air1 --pull 1.1.1
  run publish --pull 1.1.1
  show 1.1.2/promote
  refused "which 1.1.1/pull-" nokey --check
  show 1.1.1/pull
  checked "so 1.1.0 is live"
  tick
  refused "1.1.2 isn't the live release (1.1.0)" sign air1 --pull 1.1.2
  run sign air1 --resign
  lastsigned 1.1.0
}

scenario_dropfirst() {
  fresh dropfirst "the first promote cut short and dropped by promoting the next release, then shown to the Air"
  keyfolder air1 paper1
  keys air1 paper1
  build 1.1.0 1.0.6 1.1.0
  signfolder 1.1.0 air1
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  cutshort air1 --promote 1.1.0
  checked "latest.json is unsigned, so nothing is promoted yet"
  tick
  run sign air1 --promote 1.1.1
  run publish --promote 1.1.1
  show 1.1.0/promote
  refused "which 1.1.1/promote-" nokey --check
  show 1.1.1/promote
  checked "so 1.1.1 is live"
  tick
  refused "nothing promoted before 1.1.1" sign air1 --pull 1.1.1
}

scenario_seenresign() {
  fresh seenresign "a promote cut short, then shown to the Air by the resign that would drop it"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  cutshort air1 --promote 1.1.1
  checked "latest.json is 1.1.0/promote-"
  show 1.1.1/promote
  tick
  refused "went up to 1.1.1/promote-" sign air1 --resign
  checked "so 1.1.1 is live"
  pull 1.1.1 air1 1.1.0
}

scenario_seenfirst() {
  fresh seenfirst "the first promote cut short, then shown to the Air by the next release's promote"
  keyfolder air1 paper1
  keys air1 paper1
  build 1.1.0 1.0.6 1.1.0
  signfolder 1.1.0 air1
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  cutshort air1 --promote 1.1.0
  run nokey --check
  show 1.1.0/promote
  tick
  refused "went up to 1.1.0/promote-" sign air1 --promote 1.1.1
  checked "so 1.1.0 is live"
  tick
  refused "fix it forward with the next release" sign air1 --pull 1.1.0
  promote 1.1.1 air1
  pull 1.1.1 air1 1.1.0
}

scenario_droplost() {
  fresh droplost "a promote dropped by a resign signed on an Air lost before it's published"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  cutshort air1 --promote 1.1.1
  checked "latest.json is 1.1.0/promote-"
  tick
  run sign air1 --resign
  newair
  cp "$fixtures/air2.pub" "$dry/keys/touchid.pub"
  cat "$fixtures/air1.pub" "$fixtures/paper1.pub" > "$dry/import"
  run nokey --import "$dry/import"
  grep -q "^  1.1.1/promote-.*, naming 1.1.1, superseded$" "$dry/log" || { cat "$dry/log" >&2; fail "the import didn't show the promote superseded"; }
  grep -q "^  1.1.0/resign-.*, naming 1.1.0, not published yet$" "$dry/log" || { cat "$dry/log" >&2; fail "the import didn't show the resign unpublished"; }
  grep -qx "  promoted: 1.1.0" "$dry/log" || { cat "$dry/log" >&2; fail "the import counts the promote"; }
  pass "the import shows the chain to 1.1.0, the promote superseded and the resign not published yet"
  run publish --resign
  latest_is 1.1.0 air1
  show 1.1.1/promote
  refused "which 1.1.0/resign-" nokey --check
  show 1.1.0/resign
  checked "so 1.1.0 is live"
  tick
  run sign paper1 --resign
  lastsigned 1.1.0
}

scenario_dropmove() {
  fresh dropmove "a promote dropped during a planned move, then shown to each Air"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  mkdir "$dry/otherair"
  cp "$dry"/keys/{touchid.pub,backup.pub} "$dry/otherair/"
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  cutshort air1 --promote 1.1.1
  checked "latest.json is 1.1.0/promote-"
  tick
  run sign air1 --resign
  run publish --resign
  show 1.1.1/promote
  # The other Air, even with a .seen for it that one with an older history could have written.
  swapair
  p=("$dry"/otherair/history/1.1.1/promote-*.json)
  date +%s > "${p[0]%.json}.seen"
  carry
  refused "which 1.1.0/resign-" nokey --check
  show 1.1.0/resign
  checked "so 1.1.0 is live"
  tick
  run sign air1 --resign
  lastsigned 1.1.0
}

scenario_lost() {
  fresh lost "a lost Air: its history imported, then the backup key brings in two new keys, and signs a pull"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  cp -R "$dry/keys/history" "$dry/stale"
  ship 1.1.1 1.0.6 air1
  pull 1.1.1 air1 1.1.0
  # The replacement Air starts with an empty key folder, and imports its history with the keys
  # checked against the paper page. Its Touch ID key's .pub stands in for the handle.
  newair
  cp "$fixtures/air2.pub" "$dry/keys/touchid.pub"
  cat "$fixtures/air1.pub" "$fixtures/paper1.pub" > "$dry/import"
  # A backup of the history from before the last promote is deleted, then imported.
  cp -R "$dry/stale" "$dry/keys/history"
  refused "After a lost Air, delete it and run --import" nokey --check
  refused "once it's deleted" nokey --import "$dry/import"
  rm -r "$dry/keys/history"
  # A pull renamed by an agent holding ~/Sync, so it sorts before the promote it pulls, is
  # skipped, and latest.json, which is that pull, isn't in what's imported.
  d="$dry/sync/releases/1.1.1"
  for f in "$d"/pull-*; do mv "$f" "$d/pull-0-${f##*/pull-}"; done
  refused "skipped 1.1.1/pull-0-" nokey --import "$dry/import"
  for f in "$d"/pull-0-*; do mv "$f" "$d/pull-${f##*/pull-0-}"; done
  # An agent holding ~/Sync makes up a promote of 1.1.1 signed with another key, points 1.1.0's
  # commit at one whose release_keys lists the new key, and builds the pull on the promote.
  keys air2 paper2 paper1
  d="$dry/sync/releases"
  forge 1.1.1 promote paper2 1.1.1
  cp "$d/1.1.0/commit" "$dry/commit"
  git -C "$repo" rev-parse HEAD > "$d/1.1.0/commit"
  refused "1.1.0's commit, lists one of this Air's new keys" nokey --import "$dry/import"
  cp "$dry/commit" "$d/1.1.0/commit"
  q=("$d"/1.1.1/pull-*.base)
  cp "${q[0]}" "$dry/base"
  echo "1.1.1/${forged##*/}" > "${q[0]}"
  refused "its .base doesn't name an index signed before it" nokey --import "$dry/import"
  cp "$dry/base" "${q[0]}"
  run nokey --import "$dry/import"
  grep -q "^skipped 1.1.1/promote-" "$dry/log" || { cat "$dry/log" >&2; fail "the import took the made-up promote"; }
  grep -qx "  pulled: 1.1.1" "$dry/log" || { cat "$dry/log" >&2; fail "the import lost the pull"; }
  grep -q "^latest.json is 1.1.1/pull-" "$dry/log" || { cat "$dry/log" >&2; fail "the import didn't find latest.json"; }
  pass "the import skips the made-up promote, keeps the pull, and finds latest.json in what it imported"
  rm "$forged".*
  refused "exists" nokey --import "$dry/import"
  # Key setup makes the two new keys, and the release lists them and the paper key.
  keyfolder air2 paper2
  build 1.1.2 1.0.6
  refused "isn't the two keys in" sign air2 1.1.2
  signfolder 1.1.2 paper1
  promote 1.1.2 paper1
  pull 1.1.2 paper1 1.1.0
  # The pulled rotation leaves owners on the old keys, so the next release repeats it.
  build 1.1.3 1.0.6
  signfolder 1.1.3 paper1
  promote 1.1.3 paper1
}

scenario_lostpaper() {
  fresh lostpaper "a lost paper key, then a lost Air: the Air's promotes don't restart the new paper key's wait"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  build 1.1.3 1.0.6
  signfolder 1.1.3 air1
  # R lists the Air's key and a new paper key, whose wait starts at R's promote.
  rm "$dry/keys/backup.pub"
  keyfolder air1 paper2
  keys air1 paper2
  signers air1 paper2
  ship 1.1.1 1.0.6 air1
  tick
  # 1.1.3, signed before, lists the lost key, so it's never promoted, even once an agent holding
  # ~/Sync edits its release_keys there.
  cat "$fixtures/air1.pub" "$fixtures/paper2.pub" > "$dry/sync/releases/1.1.3/release_keys"
  refused "1.1.3's release_keys isn't the two keys in" sign air1 --promote 1.1.3
  refused "four weeks and four days" sign paper2 --resign
  # A release that mustn't ship goes out, and the new paper key pulls it, as it would after a
  # lost Air, without waiting.
  ship 1.1.2 1.0.6 air1
  pull 1.1.2 paper2 1.1.1
  later 33
  ship 1.1.4 1.0.6 air1
  # The Air is lost, and the new paper key signs what the next R needs at once, with paper1
  # standing in for the paper key after it. The lost paper key signed nothing, so the import
  # doesn't trust it.
  newair
  cp "$fixtures/air2.pub" "$dry/keys/touchid.pub"
  cat "$fixtures/air1.pub" "$fixtures/paper2.pub" > "$dry/import"
  run nokey --import "$dry/import"
  keyfolder air2 paper1
  keys air2 paper1 paper2
  build 1.1.5 1.0.6
  signfolder 1.1.5 paper2
  resign paper2 1.1.4
  promote 1.1.5 paper2
}

scenario_paperpull() {
  fresh paperpull "the Air that signed the live index lost before its pull, which the paper key signs"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  forge 1.1.1 pull paper2 1.1.0
  refused "isn't signed by a key any release lists" publish --pull 1.1.1
  # A key that only a release not yet promoted lists can't sign it either.
  keyfolder air2 paper1
  keys air2 paper1 air1
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  forge 1.1.1 pull air2 1.1.0
  refused "1.1.0/release_keys doesn't list the pull's key" publish --pull 1.1.1
  pull 1.1.1 paper1 1.1.0
}

scenario_move() {
  fresh move "a planned move to a new Air, with releases inside and after its four weeks"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  # The new Air's key replaces the old one's in the old Air's key folder, and the old Air signs.
  # The new Air's folder gets the same .pub files.
  keyfolder air2 paper1
  mkdir "$dry/otherair"
  cp "$dry"/keys/{touchid.pub,backup.pub} "$dry/otherair/"
  keys air2 paper1 air1
  build 1.1.1 1.0.6
  signfolder 1.1.1 air1
  promote 1.1.1 air1
  # Needed within the four weeks: signed with the old key, listing it, and pulled with it.
  build 1.1.2 1.0.6
  signfolder 1.1.2 air1
  promote 1.1.2 air1
  # The release after drops the old key, and the new Air signs it once it has the old Air's
  # history.
  keys air2 paper1
  build 1.1.3 1.0.6
  swapair
  refused "After a planned move, copy the other Air's history in first" sign air2 1.1.3
  carry
  signfolder 1.1.3 air2
  # Computers asleep on 1.1.0 hold only the old keys, so the new key can't sign the pull yet,
  # and the old Air signs it. Until it's published, the new Air, with that history, signs no
  # promote or resign.
  tick
  refused "Or sign the pull with the key that signed" sign air2 --pull 1.1.2
  swapair
  carry
  tick
  run sign air1 --pull 1.1.2
  swapair
  carry
  tick
  refused "1.1.2/pull-" sign air2 --resign
  land --pull 1.1.2
  latest_is 1.1.1 air1
  # The new key alone waits four weeks and four days.
  tick
  refused "four weeks and four days" sign air2 --promote 1.1.3
  # An agent holding ~/Sync sets back when latest.json began naming 1.1.1, so the wait would
  # seem over.
  for f in "$dry"/sync/releases/1.1.1/promote-*.live; do echo $(($(cat "$f") - 33 * 86400)) > "$f"; done
  refused "four weeks and four days" sign air2 --promote 1.1.3
  tick
  refused "four weeks and four days" sign air2 --resign
  forge 1.1.3 promote air2 1.1.3
  refused "four weeks and four days" publish --promote 1.1.3
  forge 1.1.1 resign air2 1.1.1
  refused "four weeks and four days" publish --resign
  later 33
  # The live index re-signed with the new key before the canary, once master's
  # docs/allowed_signers, which owners check latest.json with, lists that key. Then the promote
  # and a pull.
  signers air1 paper1
  tick
  run sign air2 --resign
  refused "which owners check latest.json with" publish --resign
  signers air1 paper1 air2
  land --resign
  latest_is 1.1.1 air2
  # Computers on 1.1.0 can't check the new key, so until 1.1.3 is promoted the old Air signs a
  # pull of 1.1.1 too.
  tick
  refused "1.1.0/release_keys doesn't list the pull's key" sign air2 --pull 1.1.1
  promote 1.1.3 air2
  signers air2 paper1
  tick
  run sign air2 --pull 1.1.3
  refused "add its key back first" publish --pull 1.1.3
  signers air2 paper1 air1
  land --pull 1.1.3
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
}

scenario_moved() {
  fresh moved "a planned move, then the new Air lost and restored from a backup of it"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  keyfolder air2 paper1
  keys air2 paper1 air1
  ship 1.1.1 1.0.6 air1
  later 33
  keys air2 paper1
  build 1.1.2 1.0.6
  signfolder 1.1.2 air2
  signers air1 paper1 air2
  resign air2 1.1.1
  promote 1.1.2 air2
  # The replacement deletes the key folder a backup an agent could reach brought back, and
  # imports the history with the old Air's key, the lost one and the paper key. Key setup
  # makes air3 and paper2.
  newair
  cp "$fixtures/air3.pub" "$dry/keys/touchid.pub"
  cat "$fixtures/air1.pub" "$fixtures/air2.pub" "$fixtures/paper1.pub" > "$dry/import"
  run nokey --import "$dry/import"
  keyfolder air3 paper2
  keys air3 paper2 paper1
  build 1.1.3 1.0.6
  signfolder 1.1.3 paper1
  resign paper1 1.1.2
  promote 1.1.3 paper1
}

scenario_rule() {
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
}

scenario_secure() {
  fresh secure "a signed promote that raises secure and mustn't ship, superseded with --resign"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  ship 1.1.1 1.0.6 air1
  build 1.1.2 1.1.2
  signfolder 1.1.2 air1
  tick
  run sign air1 --promote 1.1.2
  # A resign supersedes it with no other step, even once an agent holding ~/Sync marks the
  # promote live there, so the resign would make 1.1.2 the live release.
  for f in "$dry"/sync/releases/1.1.2/promote-*.json; do date +%s > "${f%.json}.live"; done
  tick
  run sign air1 --resign
  lastsigned 1.1.1
  land --resign
  latest_is 1.1.1 air1
  [ "$(field secure "$dry/bucket/connector/latest.json")" = 1.0.6 ] || fail "secure went up"
  pass "secure is still 1.0.6"
  refused "If not, scripts/publish-connector.sh --resign" publish --promote 1.1.2
  refused "1.1.3 can't go below either" release 1.1.3 1.0.5
  forge 1.1.0 promote air1 1.1.0
  refused "1.1.0 was promoted already" publish --promote 1.1.0
  build 1.1.4 1.1.2
  signfolder 1.1.4 air1
  forge 1.1.4 promote paper1 1.1.4
  refused "are signed with different keys" publish --promote 1.1.4
}

scenario_lower() {
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
  # Even once an agent holding ~/Sync lowers 1.1.1's raise there.
  cp "$dry/sync/releases/1.1.1/canary.json" "$dry/"
  sed -i '' 's/"secure": "1.1.1", "reinstallBelow": "1.1.1"/"secure": "1.0.6", "reinstallBelow": "1.1.0"/' "$dry/sync/releases/1.1.1/canary.json"
  refused "1.1.3 can't go below either" sign air1 1.1.3
  cp "$dry/canary.json" "$dry/sync/releases/1.1.1/"
  refused "1.1.3 can't go below either" release 1.1.3 1.0.6
  refused "1.1.3 can't go below either" release 1.1.3 1.1.1 1.1.0
  # Even when whoever holds the bucket shows the Air the index before 1.1.1's promote.
  j="$dry/bucket/connector/latest.json"
  cp "$j" "$dry/before.json"
  cp "$j.sig" "$dry/before.json.sig"
  tick
  run sign air1 --promote 1.1.1
  run publish --promote 1.1.1
  cp "$j" "$dry/raised.json"
  cp "$j.sig" "$dry/raised.json.sig"
  cp "$dry/before.json" "$j"
  cp "$dry/before.json.sig" "$j.sig"
  tick
  refused "1.1.2 can't go below either" sign air1 --promote 1.1.2
  cp "$dry/raised.json" "$j"
  cp "$dry/raised.json.sig" "$j.sig"
  run nokey --check
  latest_is 1.1.1 air1
  tick
  forge 1.1.2 promote air1 1.1.2
  refused "secure would go down" publish --promote 1.1.2
}

scenario_repull() {
  fresh repull "a rotation pulled back and repeated: the new key's wait starts again"
  keyfolder air1 paper1
  keys air1 paper1
  ship 1.1.0 1.0.6 air1 1.1.0
  keyfolder air2 paper1
  keys air2 paper1 air1
  ship 1.1.1 1.0.6 air1
  later 33
  pull 1.1.1 air1 1.1.0
  ship 1.1.2 1.0.6 air1
  tick
  refused "four weeks and four days" sign air2 --resign
}

scenario_gates() {
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
  signers air1 paper1
  awk '{ print "team@snowyghost.com namespaces=\"file,ovenlight-latest\"", $1, $2 }' "${mocks[@]}" >> "$repo/docs/allowed_signers"
  commit "the mock keys"
  build 1.1.0 1.0.6 1.1.0
  pass "a dry run builds with the mock release keys"
  mkdir -p "$dry/home"
  refused "holds a release key whose private half is public" env -u OVENLIGHT_RELEASE_DRY HOME="$dry/home" \
    "$repo/scripts/release-connector.sh" --unsigned 1.1.0 1.0.6 1.1.0
  # A key swap that leaves a mock key in docs/allowed_signers would let owners' own check pass
  # what its public private half signs, so a real build refuses that too.
  cat "$dry/private/stranger.pub" > "$repo/connector/release_keys"
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
  refused "latest (1.1.0) can't be below its secure (1.2.0)" release 1.1.0 1.2.0 1.1.0
  # Owners check a release with docs/allowed_signers at its tag, so a signature their check
  # would fail there, with the key only in a comment or under another principal, is kept
  # nowhere, and the version builds again from a fixed commit, since it's tagged only once
  # it's signed.
  signers paper1
  echo "# team@snowyghost.com namespaces=\"file,ovenlight-latest\" $(awk '{ print $1, $2 }' "$fixtures/air1.pub")" >> "$repo/docs/allowed_signers"
  echo "team@snowyghost.co namespaces=\"file,ovenlight-latest\" $(awk '{ print $1, $2 }' "$fixtures/air1.pub")" >> "$repo/docs/allowed_signers"
  cat "$fixtures/air1.pub" "$fixtures/paper1.pub" > "$repo/connector/release_keys"
  commit "a release key missing from allowed_signers"
  build 1.1.0 1.0.6 1.1.0
  refused "doesn't verify SHA256SUMS.sig, so owners couldn't check 1.1.0" sign air1 1.1.0
  [ ! -e "$dry/sync/releases/1.1.0" ] || fail "kept a release owners couldn't check"
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
  # A SHA256SUMS line that names no archive, here one that would add a secure of its own to
  # the index, which Go would take over the first.
  cp "$dry/build/SHA256SUMS" "$dry/"
  echo "$(printf 'f%.0s' {1..64})  x\",\"secure\":\"1.0.0\",\"z" >> "$dry/build/SHA256SUMS"
  refused "holds a line that isn't an archive's SHA-256 and name" sign air1 1.1.0
  cp "$dry/SHA256SUMS" "$dry/build/"
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
  tagbuilt 1.1.0
  refused "holds more than an ovenlight-connector-1.1.0/ folder" publish 1.1.0
  # A build over the updater's cap, which publish reads from connector/keys.go.
  sed -i '' 's|^const maxArchiveBytes = .*|const maxArchiveBytes = 1 << 10 // 1 KiB|' "$repo/connector/keys.go"
  commit "a 1 KiB cap"
  build 1.1.1 1.0.6
  run sign air1 1.1.1
  tagbuilt 1.1.1
  refused "over the updater's cap of 1024 bytes" publish 1.1.1
  build 1.1.0 1.0.6 1.1.0
  refused "another build of 1.1.0 is signed already" sign air1 1.1.0
}

# Sourced, as a state walk does for the helpers above, it stops here.
[ "${BASH_SOURCE[0]}" = "$0" ] || return 0

# --scenario <name> <folder>: one scenario, its count of checks then written in the folder.
if [ "${1:-}" = --scenario ]; then
  "scenario_$2"
  echo "$checks" > "$3/$2.checks"
  exit
fi
# Each scenario runs in a process of its own, $JOBS at a time.
scenarios=("$@")
[ $# -gt 0 ] || while read -r s; do scenarios+=("$s"); done < <(sed -n 's/^scenario_\([a-z0-9]*\)() {$/\1/p' "$0")
for s in "${scenarios[@]}"; do declare -F "scenario_$s" > /dev/null || fail "no scenario $s"; done
mkdir "$tmp/out"
# shellcheck disable=SC2016 # bash -c expands them
printf '%s\n' "${scenarios[@]}" |
  xargs -P "${JOBS:-4}" -I{} bash -c '"$0" --scenario "$1" "$2" > "$2/$1.log" 2>&1' "$0" {} "$tmp/out" || true
failed=
for s in "${scenarios[@]}"; do
  cat "$tmp/out/$s.log"
  if [ -f "$tmp/out/$s.checks" ]; then checks=$((checks + $(cat "$tmp/out/$s.checks"))); else failed="$failed $s"; fi
done
[ -z "$failed" ] || fail "failed:$failed"
echo "all $checks checks passed"
