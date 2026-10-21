# shellcheck shell=bash disable=SC2034,SC2154
# Sourced by release-connector.sh, sign-connector.sh and publish-connector.sh, after they set
# $root: where a release's files and records live, what the records say, and the rules every
# signature check follows.
#
# Every promote, pull and resign index sign-connector.sh signs sits in its release's folder of
# the records, ~/Sync/ovenlight/releases/<version>/, as <kind>-<serial>.json and its .sig, and
# here it's named <version>/<kind>-<serial>. publish-connector.sh adds <kind>-<serial>.published,
# holding the time it was published, before it uploads it. A pull or resign also notes, in
# <kind>-<serial>.base, the published index it was built on. Those records are the only history
# the scripts read, never the bucket.
#
# OVENLIGHT_RELEASE_DRY=<folder> makes a dry run, which scripts/test-connector-release.sh does:
# that folder stands in for everything outside the repo (build/release on the mini, the records,
# the release key folder and the bucket), no real one is reachable, and only the fixture keys in
# scripts/testdata/release-keys are trusted. Outside a dry run they never are.
shopt -s nullglob

refuse() { echo "$*" >&2; exit 1; }

if [ -n "${OVENLIGHT_RELEASE_DRY+set}" ]; then
  [ -d "$OVENLIGHT_RELEASE_DRY" ] || refuse "OVENLIGHT_RELEASE_DRY (\"$OVENLIGHT_RELEASE_DRY\") isn't a folder"
  dry="$(cd "$OVENLIGHT_RELEASE_DRY" && pwd -P)"
  home="$(cd ~ && pwd -P)"
  case "$dry/" in
  "$home"/Sync/* | "$home"/.ssh/*) refuse "a dry run's folder can't be in ~/Sync or ~/.ssh" ;;
  esac
  release="$dry/build"         # build/release, on the mini
  minirelease="$dry/build"     # the same, from the Air
  records="$dry/sync/releases" # ~/Sync/ovenlight/releases
  keydir="$dry/keys"           # ~/.ssh/ovenlight-release-keys
  master=master
else
  dry=
  release="$root/build/release"
  minirelease="mini:src/ovenlight/build/release"
  records="$HOME/Sync/ovenlight/releases"
  keydir="$HOME/.ssh/ovenlight-release-keys"
  master=origin/master
fi

tmp="$(mktemp -d)"
trap 'rm -r "$tmp"' EXIT

# atleast <a> <b>: whether version b is a or later. below <a> <b>: whether a is older than b.
atleast() { [ "$(printf '%s\n' "$1" "$2" | sort -V | tail -1)" = "$2" ]; }
below() { [ "$1" != "$2" ] && atleast "$1" "$2"; }

# field <name> <index>: a string or number from an index.
field() { sed -En "s/.*\"$1\": \"?([^\",]*).*/\1/p" "$2"; }

# index <latest> <secure> <reinstallBelow> <serial> <SHA256SUMS>: a release's index, with the
# SHA-256 of each archive. It stays under 4 KB, which is all ovenlight doctor reads.
index() {
  local s
  s="$(printf '{"latest": "%s", "secure": "%s", "reinstallBelow": "%s", "serial": %s, "archives": {' "$1" "$2" "$3" "$4"
    awk 'NR > 1 { printf ", " } { printf "\"%s\": \"%s\"", $2, $1 }' "$5"
    printf '}}')"
  [ ${#s} -lt 4096 ] || refuse "the index is over 4 KB"
  echo "$s"
}

# releaseindex <version> <serial>: the release's index from its folder, with that serial.
releaseindex() {
  local f="$records/$1/index.json"
  [ -f "$f" ] || refuse "no $f: $1 isn't signed"
  index "$1" "$(field secure "$f")" "$(field reinstallBelow "$f")" "$2" "$records/$1/SHA256SUMS"
}

# What the records say, read once.
serialof() { echo "${1##*-}"; }
signed="$(for f in "$records"/*/promote-*.json "$records"/*/pull-*.json "$records"/*/resign-*.json; do
  f="${f#"$records/"}"
  echo "${f%.json}"
done | sort -t- -k2,2n)"
published="$(for f in "$records"/*/*.published; do
  f="${f#"$records/"}"
  echo "${f%.published}"
done | sort -t- -k2,2n)"
newest="$(echo "$published" | tail -1)" # the live index
promoted="$(for id in $published; do case "$id" in (*/promote-*) echo "${id%%/*}" ;; esac; done | sort -uV)"
pulled="$(for id in $published; do case "$id" in (*/pull-*) echo "${id%%/*}" ;; esac; done | sort -uV)"
has() {
  local w
  for w in $2; do
    if [ "$w" = "$1" ]; then return 0; fi
  done
  return 1
}
kept=  # the promoted releases that weren't pulled, oldest first
for v in $promoted; do
  if ! has "$v" "$pulled"; then kept="$kept $v"; fi
done
live="${kept##* }" # the newest of them

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
  for v in $promoted; do
    if atleast "$1" "$v"; then refuse "$v was promoted already: a release goes out under a newer version"; fi
  done
}

# ordered <serial> [<index>]: refuses a serial that isn't above every index signed so far but
# that one, so a signature left unpublished always sits below a published one.
ordered() {
  local id
  for id in $signed; do
    if [ "$id" != "${2:-}" ] && [ "$(serialof "$id")" -ge "$1" ]; then
      refuse "$id is signed with a serial at or above $1: sign a fresh one"
    fi
  done
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

# fixture <blob>: whether a key is a dry-run fixture or the connector's test key.
fixture() {
  cat "$root"/scripts/testdata/release-keys/*.pub "$root/connector/testdata/releasekey/test-key.pub" |
    awk -v b="$1" '$2 == b { found = 1 } END { exit !found }'
}

# trusted <keys>: refuses a key a run of this kind mustn't trust: in a dry run any but the
# fixtures, otherwise any fixture.
trusted() {
  local t b
  while read -r t b _; do
    if [ -z "$b" ]; then continue; fi
    if fixture "$b"; then
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
  cat /dev/null "$records"/*/release_keys | awk 'NF >= 2 && !seen[$2]++ { print $1, $2 }' > "$tmp/candidates"
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

# verifyrule <version> <file> <sig> <namespace>: refuses a signature the one rule doesn't accept.
verifyrule() {
  local k
  for k in $(rulekeys "$1"); do
    [ -f "$k" ] || refuse "no $k"
    verify "$2" "$3" "$4" "$k" || refuse "$2 isn't signed by a key in $k, which updaters installing $1 hold"
  done
}

# WAIT is four weeks for computers that sleep, and four days for the two-day wait, its one-day
# cap and the night it then waits for.
WAIT=$((32 * 86400))

# newkeywait <blob> <now>: refuses a key that computers still on an older release can't check
# yet. The newest promoted release that doesn't list the key was replaced by releases signed
# with a key it does list, and the key waits until four weeks and four days after the last of
# those promotes, so computers asleep through it have taken one.
newkeywait() {
  local v old='' id s t last=0
  for v in $promoted; do
    if ! haskey "$1" "$records/$v/release_keys"; then old="$v"; fi
  done
  if [ -z "$old" ]; then return 0; fi
  for id in $published; do
    case "$id" in */promote-*) ;; *) continue ;; esac
    s="$(signer "$records/$id.json" "$records/$id.json.sig" ovenlight-latest)"
    t="$(cat "$records/$id.published")"
    if [ -n "$s" ] && haskey "${s#* }" "$records/$old/release_keys" && [ "$t" -gt "$last" ]; then last="$t"; fi
  done
  if [ "$2" -lt $((last + WAIT)) ]; then
    refuse "$old doesn't list this key, and computers asleep on it may not have updated yet: not before $(date -r $((last + WAIT))), four weeks and four days after the last promote signed with a key it lists"
  fi
}
