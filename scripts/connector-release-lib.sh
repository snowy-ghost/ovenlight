# shellcheck shell=bash disable=SC2034,SC2154
# Sourced by release-connector.sh, sign-connector.sh and publish-connector.sh, after they set
# $root: where a release's files and records live, what the records say, and the rules every
# signature check follows.
#
# Every promote, pull and resign index sign-connector.sh signs sits in its release's folder
# as <kind>-<serial>.json and its .sig, and here it's named <version>/<kind>-<serial>. Each
# also notes, in <kind>-<serial>.base, the index it was built on, and those notes are the
# books: what's published is the head, the newest index signed once it's marked published,
# else the one that index was built on, and every index back from it through its bases. One
# left off was superseded by the index signed after it. Each side reads only its own
# records. The Air's are its history, ~/.ssh/ovenlight-release-keys/history/, which only
# sign-connector.sh writes, marking an index with <kind>-<serial>.seen, the time it first
# saw latest.json be that index. It sends what it signs with rsync to the mini's records,
# ~/Sync/ovenlight/releases/ there, and never writes to the Air's own ~/Sync, where a link
# an agent syncs would write into its files. There publish-connector.sh marks an index with
# <kind>-<serial>.live, the time latest.json began naming it, and where an index has none,
# as when records the mini lost come back in the books, the Air's .seen they carry counts.
# A script that sets air=1 before sourcing this reads the Air's.
#
# OVENLIGHT_RELEASE_DRY=<folder> makes a dry run, which scripts/test-connector-release.sh does
# in folders it marks with a .release-dry-run file, which this requires: that folder stands
# in for everything outside the repo (build/release on the mini, the records, the release
# key folder and the bucket), no real one is reachable, and only fixture keys are trusted:
# those in scripts/testdata/release-keys and connector/testdata/releasekey, whose private
# halves are public. Outside a dry run they never are.
shopt -s nullglob

refuse() { echo "$*" >&2; exit 1; }

if [ -n "${OVENLIGHT_RELEASE_DRY+set}" ]; then
  [ -d "$OVENLIGHT_RELEASE_DRY" ] || refuse "OVENLIGHT_RELEASE_DRY (\"$OVENLIGHT_RELEASE_DRY\") isn't a folder"
  dry="$(cd "$OVENLIGHT_RELEASE_DRY" && pwd -P)"
  home="$(cd ~ && pwd -P)"
  case "$dry/" in
  "$home"/Sync/* | "$home"/.ssh/*) refuse "a dry run's folder can't be in ~/Sync or ~/.ssh" ;;
  esac
  [ -f "$dry/.release-dry-run" ] || refuse "$dry isn't a dry run's folder: it has no .release-dry-run"
  release="$dry/build"         # build/release, on the mini
  minirelease="$dry/build"     # the same, from the Air
  sync="$dry/sync/releases"    # ~/Sync/ovenlight/releases
  minisync="$dry/mini"         # ~/Sync/ovenlight on the mini, from the Air
  keydir="$dry/keys"           # ~/.ssh/ovenlight-release-keys
  rsync=rsync                  # the one on PATH, which the harness can make fail
  master=master
else
  dry=
  release="$root/build/release"
  minirelease="mini:src/ovenlight/build/release"
  sync="$HOME/Sync/ovenlight/releases"
  minisync="mini:Sync/ovenlight"
  keydir="$HOME/.ssh/ovenlight-release-keys"
  rsync=/usr/bin/rsync         # macOS's, which its updates keep current, never one on PATH
  master=origin/master
fi
# The records, and what marks an index published in them with the time latest.json began
# naming it.
if [ -n "${air:-}" ]; then
  records="$keydir/history" since=seen
else
  records="$sync" since=live
fi

tmp="$(mktemp -d)"
trap 'rm -r "$tmp"' EXIT

# atleast <a> <b>: whether version b is a or later. below <a> <b>: whether a is older than b.
atleast() { [ "$(printf '%s\n' "$1" "$2" | sort -V | tail -1)" = "$2" ]; }
below() { [ "$1" != "$2" ] && atleast "$1" "$2"; }

# field <name> <index>: a string or number from an index.
field() { sed -En "s/.*\"$1\": \"?([^\",]*).*/\1/p" "$2"; }

# canonical <version>: whether it's a version as an index holds it: 1.2.3, with no leading
# zeros and no suffix.
canonical() { [[ "$1" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; }

# index <latest> <secure> <reinstallBelow> <serial> <SHA256SUMS>: a release's index, with the
# SHA-256 of each archive, refused when the updater would refuse it. Its serial is above 0
# once signed. It stays under 4 KB, which is all the updater and an older release's doctor
# read.
index() {
  local s v
  for v in "$1" "$2" "$3"; do
    canonical "$v" || refuse "versions look like 1.2.3, with no leading zeros or suffix, not $v"
  done
  { atleast "$2" "$1" && atleast "$3" "$1"; } ||
    refuse "an index's latest ($1) can't be below its secure ($2) or reinstallBelow ($3)"
  [[ "$4" =~ ^[0-9]+$ ]] || refuse "an index's serial is a number of seconds, not \"$4\""
  ! grep -Evq '^[0-9a-f]{64}  ovenlight-connector-[a-z0-9-]+\.(tar\.gz|zip)$' "$5" ||
    refuse "$5 holds a line that isn't an archive's SHA-256 and name"
  s="$(printf '{"latest": "%s", "secure": "%s", "reinstallBelow": "%s", "serial": %s, "archives": {' "$1" "$2" "$3" "$4"
    awk 'NR > 1 { printf ", " } { printf "\"%s\": \"%s\"", $2, $1 }' "$5"
    printf '}}')"
  [ ${#s} -lt 4096 ] || refuse "the index is over 4 KB"
  echo "$s"
}

# releaseindex <version> <serial>: the canary index signed for the release, with that serial,
# so nothing unsigned in its folder goes into it.
releaseindex() {
  local f="$records/$1/canary.json"
  [ -f "$f" ] || refuse "no $f: $1 isn't signed"
  verifyrule "$1" "$f" "$f.sig" ovenlight-canary
  [ "$(field latest "$f")" = "$1" ] || refuse "$f is $(field latest "$f")'s index, not $1's"
  sed -E "s/(\"serial\": )[0-9]+/\1$2/" "$f"
}

serialof() { echo "${1##*-}"; }
# marked <id>: when latest.json began naming the index, by its .$since, else the .seen the
# Air's books carry, and never before it was signed. Fails when it has neither.
marked() {
  local t
  t="$(cat "$records/$1.$since" 2> /dev/null || cat "$records/$1.seen" 2> /dev/null)" || return 1
  [ "$t" -ge "$(serialof "$1")" ] 2> /dev/null || t="$(serialof "$1")"
  echo "$t"
}
# baseof <id>: sets base to the index it was built on, refusing a .base that's missing,
# empty, as a write cut short leaves it (built on nothing is a blank line), or doesn't name
# an index signed before it.
baseof() {
  local fix="scripts/sign-connector.sh --check on the Air sends the books again, or after a lost Air, sign the next index there"
  { [ -s "$records/$1.base" ] && base="$(cat "$records/$1.base")"; } || refuse "$1 has no .base in $records: $fix"
  [ -z "$base" ] || { grep -qxF "$base" <<< "$signed" && [ "$(serialof "$base")" -lt "$(serialof "$1")" ]; } ||
    refuse "$records/$1.base names $base, not an index signed before it: $fix"
}
# readrecords: what the records say, from the head the header names.
readrecords() {
  signed="$(for f in "$records"/*/promote-*.json "$records"/*/pull-*.json "$records"/*/resign-*.json; do
    f="${f#"$records/"}"
    echo "${f%.json}"
  done | sort -t- -k2,2n)"
  top="${signed##*$'\n'}" # the newest signed
  if [ -n "$top" ] && ! marked "$top" > /dev/null; then
    baseof "$top"
    readchain "$base"
  else
    readchain "$top"
  fi
}
# readchain <head>: what the records say, from that head.
readchain() {
  local v id="$1"
  newest="$1" published='' # the live index, and those published up to it, oldest first
  while [ -n "$id" ]; do
    published="$id"$'\n'"$published"
    baseof "$id"
    id="$base"
  done
  promoted="$(for id in $published; do case "$id" in (*/promote-*) echo "${id%%/*}" ;; esac; done | sort -uV)"
  pulled="$(for id in $published; do case "$id" in (*/pull-*) echo "${id%%/*}" ;; esac; done | sort -uV)"
  kept=  # the promoted releases that weren't pulled, oldest first
  for v in $promoted; do
    if ! grep -qxF "$v" <<< "$pulled"; then kept="$kept $v"; fi
  done
  live="${kept##* }" # the newest of them
  [ -z "$newest" ] || [ "$(field latest "$records/$newest.json")" = "$live" ] ||
    refuse "the newest index published, $newest, names $(field latest "$records/$newest.json"), not $live, which the others make live: find out who changed the records"
}
readrecords

# keptbelow <version>: the newest promoted release below it that wasn't pulled.
keptbelow() {
  local v k=
  for v in $kept; do
    if below "$v" "$1"; then k="$v"; fi
  done
  echo "$k"
}

# abovereleased <version>: refuses a version at or below one promoted or pulled.
abovereleased() {
  local v
  for v in $promoted $pulled; do
    if atleast "$1" "$v"; then refuse "$v was promoted already: a release goes out under a newer version"; fi
  done
}

# keepsraised <version> <secure> <reinstallBelow>: refuses a secure or reinstallBelow below
# that of a lower version already signed. That one may not be promoted yet, and this one's
# promote may skip it, so this one keeps what it raised.
keepsraised() {
  local f v
  for f in "$records"/*/canary.json; do
    v="$(basename "$(dirname "$f")")"
    if below "$v" "$1" && { below "$2" "$(field secure "$f")" || below "$3" "$(field reinstallBelow "$f")"; }; then
      refuse "$v is signed with secure $(field secure "$f") and reinstallBelow $(field reinstallBelow "$f"): $1 can't go below either"
    fi
  done
}

# ordered <serial>: refuses a serial that isn't above the newest index signed, so the index
# signed next is the newest, the one the mini publishes.
ordered() {
  [ -z "$top" ] || [ "$(serialof "$top")" -lt "$1" ] ||
    refuse "$top is signed with a serial at or above $1: sign a fresh one after $(date -r "$(serialof "$top")")"
}

# nodrop <index>: refuses an index that would lower the published secure or reinstallBelow.
nodrop() {
  local f
  if [ -z "$newest" ]; then return 0; fi
  for f in secure reinstallBelow; do
    if below "$(field "$f" "$1")" "$(field "$f" "$records/$newest.json")"; then
      refuse "$f would go down to $(field "$f" "$1") from $(field "$f" "$records/$newest.json") ($newest): fix it forward"
    fi
  done
}

# The fixture keys: a dry run's, the connector's test key and the mock release keys.
cat /dev/null "$root"/scripts/testdata/release-keys/*.pub "$root"/connector/testdata/releasekey/*.pub > "$tmp/fixtures"

# trusted <keys>: refuses a key a run of this kind mustn't trust: in a dry run any but the
# fixtures, otherwise any fixture.
trusted() {
  local t b
  while read -r t b _; do
    if [ -z "$b" ]; then continue; fi
    if haskey "$b" "$tmp/fixtures"; then
      [ -n "$dry" ] || refuse "$t $b is a dry-run fixture key"
    elif [ -n "$dry" ]; then
      refuse "a dry run trusts only the fixture keys, not $t $b"
    fi
  done < "$1"
}

# haskey <blob> <keys>: whether the keys (one OpenSSH public key per line) hold that one.
haskey() { awk -v b="$1" '$2 == b { found = 1 } END { exit !found }' "$2"; }

# verify <file> <sig> <namespace> <keys>: whether one of the keys made the signature.
verify() {
  trusted "$4"
  awk 'NF >= 2 { print "release", $1, $2 }' "$4" > "$tmp/allowed"
  ssh-keygen -Y verify -f "$tmp/allowed" -I release -n "$3" -s "$2" < "$1" > /dev/null 2>&1
}

# signer <file> <sig> <namespace>: the key, of those any release lists, that made the signature.
signer() {
  local t b
  awk 'NF >= 2 && !seen[$2]++ { print $1, $2 }' /dev/null "$records"/*/release_keys > "$tmp/candidates"
  while read -r t b; do
    echo "$t $b" > "$tmp/candidate"
    if verify "$1" "$2" "$3" "$tmp/candidate"; then
      echo "$t $b"
      return
    fi
  done < "$tmp/candidates"
}

# rulekeys <version> [<own release_keys>]: the one rule. A release's signatures verify against
# the release_keys of the newest promoted release before it that wasn't pulled, and of every
# pulled release promoted after that one, since computers that took a pulled release keep it:
# those are the keys the updaters installing it hold, so each set must verify. A release with
# none before it, the updater release, uses its own.
rulekeys() {
  local p w
  p="$(keptbelow "$1")"
  if [ -z "$p" ]; then
    echo "${2:-$records/$1/release_keys}"
    return
  fi
  echo "$records/$p/release_keys"
  for w in $pulled; do
    if below "$p" "$w" && below "$w" "$1"; then echo "$records/$w/release_keys"; fi
  done
}

# livekeys: the release_keys of the live release, and of every pulled release above it, which
# computers that took one keep. Each must list a key that signs the live index again.
livekeys() {
  local w
  echo "$records/$live/release_keys"
  for w in $pulled; do
    if below "$live" "$w"; then echo "$records/$w/release_keys"; fi
  done
}

# pullsigned <signer> <base> <version>: refuses a key for the pull of the live release that
# computers that never took it don't hold (rulekeys), or that didn't sign base, the index the
# pull replaces, unless those that took it or a pulled release above it hold it too
# (livekeys). That's the paper key once the Air that signed the live index is lost.
pullsigned() {
  local f
  for f in $(rulekeys "$3"); do
    haskey "$(awk '{ print $2 }' "$1")" "$f" || refuse "$f doesn't list the pull's key, and computers that never took $3 check the pull with those keys"
  done
  verify "$records/$2.json" "$records/$2.json.sig" ovenlight-latest "$1" && return
  for f in $(livekeys); do
    haskey "$(awk '{ print $2 }' "$1")" "$f" || refuse "the pull isn't signed with the key of $2, the index it replaces, and $f doesn't list its key"
  done
  # Computers asleep on an older release may not hold a new Touch ID key yet. The paper key,
  # which every release of its time lists, signs a pull at once after the Air is lost.
  [ "$(awk '{ print $1 }' "$1")" = ssh-ed25519 ] ||
    newkeywait "$(awk '{ print $2 }' "$1")" "$(date +%s)" ". Or sign the pull with the key that signed $2, or with the paper key"
}

# verifyrule <version> <file> <sig> <namespace>: refuses a signature the one rule doesn't accept.
verifyrule() {
  local k
  for k in $(rulekeys "$1"); do
    [ -f "$k" ] || refuse "no $k"
    verify "$2" "$3" "$4" "$k" || refuse "$2 isn't signed by a key in $k, which updaters installing $1 hold"
  done
}

# TAKE is the longest a computer on most days takes from an index going up to its swap, with a
# day to spare. One off at night, at weekends and for up to four days at a time takes up to
# thirteen: off on a Thursday and Friday, it sees a Wednesday index on Monday, waits two days
# and its one-day cap, and swaps the next morning it's on, which with the next Friday and
# Monday off is Tuesday. Off for longer, it counts with computers that sleep. WAIT is four
# weeks for those, then TAKE.
TAKE=$((14 * 86400)) WAIT=$((28 * 86400 + TAKE))

# newkeywait <blob> <now> [<what else signs>]: refuses a key that computers still on an older
# release can't check yet. It waits until WAIT after latest.json last began naming releases
# that list it, so computers asleep on an older release have taken one. A pull back to one
# that doesn't list it starts the wait again, and a promote signed by another key doesn't.
# Each change to latest.json starts the two-day wait of computers that haven't taken one
# again, so until the key has signed an index published since, it also waits TAKE after the
# last. With nothing published in the records, it counts what's signed.
newkeywait() {
  local id v t old='' from='' after=''
  for id in ${published:-$signed}; do
    v="$(field latest "$records/$id.json")"
    if ! haskey "$1" "$records/$v/release_keys"; then
      old="$v" from='' after=''
      continue
    fi
    after="$after $id"
    if [ -z "$from" ]; then from="$(marked "$id")" || from=''; fi
  done
  if [ -z "$old" ]; then return 0; fi
  from="${from:-$2}"
  if [ "$2" -lt $((from + WAIT)) ]; then
    refuse "$old doesn't list this key, and computers asleep on it may not have updated yet: not before $(date -r $((from + WAIT))), $((WAIT / 86400)) days after latest.json began naming releases that list it${3:-}"
  fi
  if [ -z "$newest" ]; then return 0; fi
  awk -v b="$1" '$2 == b { print $1, $2; exit }' /dev/null "$records"/*/release_keys > "$tmp/newkey"
  for id in $after; do
    if verify "$records/$id.json" "$records/$id.json.sig" ovenlight-latest "$tmp/newkey"; then return 0; fi
  done
  t="$(marked "$newest")" || t="$(serialof "$newest")"
  if [ "$2" -lt $((t + TAKE)) ]; then
    refuse "$old doesn't list this key, and each change to latest.json starts the two-day wait of computers still on it again: not before $(date -r $((t + TAKE))), $((TAKE / 86400)) days after $newest went up${3:-}"
  fi
}
