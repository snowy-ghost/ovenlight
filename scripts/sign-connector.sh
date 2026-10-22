#!/bin/bash
# Signs a connector release on the Air, with the release key behind Touch ID:
#
#   scripts/sign-connector.sh [--key <file>] <version>
#       the build scripts/release-connector.sh left in build/release on the mini: its
#       SHA256SUMS (namespace file), and its index as a canary index, canary.json (namespace
#       ovenlight-canary), which only ovenlight update now --index takes
#   scripts/sign-connector.sh [--key <file>] --promote <version>
#       its index for latest.json (namespace ovenlight-latest), with the same key
#   scripts/sign-connector.sh [--key <file>] --pull <version>
#       the live release's pull: the index of the newest promoted release before it that
#       wasn't pulled, with the key that signed the live index if that release lists it, or
#       one every release a computer it reaches may run lists: the paper key, or a new Touch
#       ID key once its four weeks and four days are over
#   scripts/sign-connector.sh [--key <file>] --resign
#       the live index again, with a key the live release lists, as does every pulled
#       release above it
#   scripts/sign-connector.sh --check
#       checks latest.json, as each of the others does first
#   scripts/sign-connector.sh --import <keys>
#       after a lost Air, rebuilds its history from what those keys signed in the mini's
#       records
#
# Each index gets the time now as its serial. What it signs goes in this Air's history,
# ~/.ssh/ovenlight-release-keys/history/, the only record it reads, and is sent with scp to
# the release's folder in the mini's ~/Sync/ovenlight/releases/, which
# scripts/publish-connector.sh publishes from. Touch ID shows a prompt and not what it signs,
# so this prints that first, and refuses before it whatever publish-connector.sh would
# refuse. A release is signed and promoted only when its connector/release_keys is exactly
# the two keys in ~/.ssh/ovenlight-release-keys/ plus the key signing it, so the keys a
# release trusts never come from the commit alone. The paper key signs only a release that
# replaces it, so its .pub has left that folder.
#
# Each run first fetches latest.json, which must be an index in the history, never one below
# an index it has been (1.0.9's unsigned one until then), and marks it seen: it's the live
# index. A pull signed above it and never seen blocks promotes and resigns until it's
# published or a fresh pull is signed, and a promote or resign never seen is superseded by the
# next index. After signing an index for latest.json, it waits for latest.json to be it.
#
# --key signs with a key other than this Air's Touch ID handle, such as the paper backup or
# another Touch ID key's handle, with its public half beside it as <file>.pub.
set -euo pipefail

HANDLE="$HOME/.ssh/id_ecdsa_sk_rk_ovenlight-release"

usage="usage: sign-connector.sh [--key <file>] [--promote | --pull] <version>, [--key <file>] --resign, --check or --import <keys>"
mode=release
key=
while [[ "${1:-}" == --* ]]; do
  case "$1" in
  --key) key="${2:?$usage}"; shift ;;
  --import) mode=import importkeys="${2:?$usage}"; shift ;;
  --promote | --pull | --resign | --check) mode="${1#--}" ;;
  *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done
version=
case "$mode" in release | promote | pull) version="${1:?$usage}" ;; esac

root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC2034 # the lib reads it
air=1
# shellcheck source=connector-release-lib.sh
. "$root/scripts/connector-release-lib.sh"
[ -z "$version" ] || canonical "$version" || refuse "versions look like 1.2.3, with no leading zeros or suffix, not $version"

# fetchlive: latest.json, in $tmp/live.json.
fetchlive() {
  if [ -n "$dry" ]; then cp "$dry/bucket/connector/latest.json" "$tmp/live.json"
  else curl -fsS --max-filesize 4096 -o "$tmp/live.json" "https://downloads.ovenlight.app/connector/latest.json?$(date +%s)"; fi
}

# observe: the header's check of latest.json. pending is a pull signed above it and never seen.
observe() {
  local id found=
  fetchlive || refuse "couldn't fetch latest.json"
  if [ -z "$(field serial "$tmp/live.json")" ]; then
    [ -z "$newest" ] || refuse "latest.json is unsigned, and it was $newest: publish $newest again on the mini"
    pending=
    return
  fi
  for id in $signed; do
    if cmp -s "$tmp/live.json" "$records/$id.json"; then found="$id"; fi
  done
  [ -n "$found" ] || refuse "latest.json isn't an index in $records. After a planned move, copy the other Air's history in first. After a lost Air, delete it and run --import."
  if [ -n "$newest" ] && [ "$(serialof "$found")" -lt "$(serialof "$newest")" ]; then
    refuse "latest.json is $found, older than $newest, which the history marks seen: publish $newest on the mini"
  fi
  [ -f "$records/$found.seen" ] || date +%s > "$records/$found.seen"
  readrecords
  pending=
  for id in $signed; do
    case "$id" in */pull-*) if [ "$(serialof "$id")" -gt "$(serialof "$newest")" ]; then pending="$id"; fi ;; esac
  done
  echo "latest.json is $newest, so $live is live"
}

# import: rebuilds the history from the mini's records: each release whose SHA256SUMS and
# canary index the keys signed, with connector/release_keys at its commit on master, and each
# index they signed that fits its name. Every pull, and every index the records mark
# published, counts as seen. Then latest.json must be in it.
import() {
  local h="$tmp/history" d v c id f s t
  [ ! -e "$records" ] || refuse "$records exists: --import rebuilds it once it's deleted, on an Air whose --check doesn't find latest.json in it"
  [ -n "$dry" ] || git -C "$root" fetch -q origin master
  # This Air's keys made since the loss, which no release before it lists.
  { cat /dev/null "$keydir"/*.pub; [ -n "$dry" ] || cat "$HANDLE.pub"; } |
    awk 'NR == FNR { old[$2] = 1; next } NF >= 2 && !old[$2]' "$importkeys" - > "$tmp/new"
  for d in "$sync"/*; do
    v="${d##*/}"
    canonical "$v" || continue
    if ! { verify "$d/SHA256SUMS" "$d/SHA256SUMS.sig" file "$importkeys" &&
      verify "$d/canary.json" "$d/canary.json.sig" ovenlight-canary "$importkeys" &&
      [ "$(field latest "$d/canary.json")" = "$v" ]; }; then
      echo "skipped $v: not signed by those keys"
      continue
    fi
    read -r c _ < "$d/commit"
    [[ "$c" =~ ^[0-9a-f]{40}$ ]] || refuse "$v's commit file names $c, not a commit's full hash"
    git -C "$root" merge-base --is-ancestor "$c" "$master" || refuse "$v was built from $c, which isn't on $master"
    mkdir -p "$h/$v"
    git -C "$root" show "$c:connector/release_keys" > "$h/$v/release_keys"
    while read -r _ f _; do
      if haskey "$f" "$h/$v/release_keys"; then refuse "connector/release_keys at $c, $v's commit, lists one of this Air's new keys"; fi
    done < "$tmp/new"
    echo "$c" > "$h/$v/commit"
    cp "$d"/{SHA256SUMS,SHA256SUMS.sig,canary.json,canary.json.sig} "$h/$v/"
    for f in "$d"/promote-*.json "$d"/pull-*.json "$d"/resign-*.json; do
      id="${f%.json}"
      s="$(serialof "$id")"
      if ! { [[ "${id##*/}" =~ ^(promote|pull|resign)-[0-9]+$ ]] && verify "$f" "$f.sig" ovenlight-latest "$importkeys" &&
        [ "$(field serial "$f")" = "$s" ] &&
        case "$id" in */pull-*) below "$(field latest "$f")" "$v" ;; *) [ "$(field latest "$f")" = "$v" ] ;; esac; }; then
        echo "skipped $v/${id##*/}: not signed by those keys for $v"
        continue
      fi
      cp "$f" "$f.sig" "$h/$v/"
      case "$id" in */pull-*) ;; *) [ -f "$id.published" ] || continue ;; esac
      # When latest.json began naming it, which newkeywait never takes as before it was signed.
      t="$(cat "$id.live" 2> /dev/null || true)"
      [[ "$t" =~ ^[1-9][0-9]{9}$ ]] || t="$s"
      echo "$t" > "$h/$v/${id##*/}.seen"
    done
  done
  records="$h"
  readrecords
  echo "the history, from $sync:"
  for id in $signed; do
    if [ -f "$h/$id.seen" ]; then s=published; else s="never published"; fi
    echo "  $id, signed $(date -r "$(serialof "$id")" '+%Y-%m-%d %H:%M'), naming $(field latest "$h/$id.json"), $s"
  done
  echo "  promoted: ${promoted//$'\n'/ }"
  echo "  pulled: ${pulled//$'\n'/ }"
  [ -z "$newest" ] || [ "$(field latest "$h/$newest.json")" = "$live" ] ||
    refuse "the newest index published, $newest, names $(field latest "$h/$newest.json"), not $live, which the others make live: find out who changed the records"
  observe
  mkdir -p "$keydir"
  mv "$h" "$keydir/history"
  echo "imported: check that list against the releases you published before signing anything"
}

case "$mode" in
check) observe; exit ;;
import) import; exit ;;
esac

if [ -z "$key" ]; then
  [ -z "$dry" ] || refuse "a dry run signs with --key and a fixture key, never the Touch ID key"
  key="$HANDLE"
fi
# A Touch ID handle, the default one or another given with --key, signs through macOS's
# ssh-keychain.dylib.
if [ -e /usr/lib/ssh-keychain.dylib ]; then export SSH_SK_PROVIDER=/usr/lib/ssh-keychain.dylib; fi
[ -f "$key" ] && [ -f "$key.pub" ] || refuse "no $key, or no $key.pub beside it"
awk 'NF >= 2 { print $1, $2; exit }' "$key.pub" > "$tmp/signer"
kblob="$(awk '{ print $2 }' "$tmp/signer")"
trusted "$tmp/signer"

# One serial from the future would block every later index, so the clock that sets it is
# checked against the mini's. The mini's answer must be a number before any arithmetic,
# since bash runs commands in an array subscript there.
now="$(date +%s)"
if [ -n "$dry" ]; then mini="$now"; else mini="$(ssh mini date +%s)"; fi
[[ "$mini" =~ ^[0-9]+$ ]] || refuse "the mini's clock answered \"$mini\", not a number of seconds"
[ "$now" -le $((mini + 3600)) ] || refuse "this clock is more than an hour ahead of the mini's"

# sign <namespace> <file>: writes <file>.sig with the key, and checks it.
sign() {
  ssh-keygen -Y sign -q -f "$key" -n "$1" < "$2" > "$2.sig"
  verify "$2" "$2.sig" "$1" "$tmp/signer" || refuse "the signature of $2 doesn't verify with $key.pub"
}

# rulehas <version> [<own release_keys>]: refuses a key the one rule won't accept for it.
rulehas() {
  local k
  for k in $(rulekeys "$@"); do
    [ -f "$k" ] || refuse "no $k"
    haskey "$kblob" "$k" || refuse "$k doesn't list this key, and updaters installing $1 hold those keys"
  done
}

# folderkeys <release_keys> <what they are>: refuses keys that aren't the two in the key
# folder plus the one signing.
folderkeys() {
  local f n=0
  for f in "$keydir"/*.pub; do n=$((n + 1)); done
  [ "$n" -eq 2 ] || refuse "$keydir holds $n .pub files, not the two release keys"
  cat "$keydir"/*.pub > "$tmp/folder"
  trusted "$tmp/folder"
  cat "$tmp/folder" "$tmp/signer" | awk 'NF >= 2 { print $2 }' | sort -u > "$tmp/want"
  awk 'NF >= 2 { print $2 }' "$1" | sort -u > "$tmp/have"
  if ! cmp -s "$tmp/want" "$tmp/have"; then
    refuse "$2 isn't the two keys in $keydir plus the one signing:
$(diff "$tmp/want" "$tmp/have" | sed -n 's/^</  missing/p; s/^>/  extra/p')"
  fi
}

# keep <version> <file>...: puts the signed files in the release's folder of the history,
# and sends them to the mini's records.
keep() {
  local v="$1"
  shift
  mkdir -p "$records/$v" "$tmp/send/releases/$v"
  cp "$@" "$records/$v/"
  cp "$@" "$tmp/send/releases/$v/"
  scp -rq "$tmp/send/releases" "$minisync/"
}

# await <id>: waits for latest.json to be the index, and marks it seen. A dry run leaves that
# to the next run, as Ctrl-C does.
await() {
  [ -z "$dry" ] || return 0
  echo "waiting for latest.json to be it (Ctrl-C stops waiting)"
  until fetchlive 2> /dev/null && cmp -s "$tmp/live.json" "$records/$1.json"; do sleep 10; done
  date +%s > "$records/$1.seen"
  echo "latest.json is $1"
}

echo "signing with $(ssh-keygen -lf "$key.pub")"
observe
if [ -n "$pending" ] && [ "$mode" != release ] && [ "$mode" != pull ]; then
  refuse "$pending, a pull signed after the live index, isn't published: publish it on the mini, or sign a fresh pull"
fi
case "$mode" in
release)
  b="$tmp/build"
  mkdir "$b"
  scp -q "$minirelease/SHA256SUMS" "$minirelease/index.json" "$minirelease/commit" "$b/"
  read -r commit tag < "$b/commit"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || refuse "the build's commit file names $commit, not a commit's full hash"
  if [ -n "$dry" ]; then
    [ "$tag" = dry-run ] || refuse "a dry run signs only a dry run's build"
  else
    [ -z "$tag" ] || refuse "build/release on the mini is a dry run's"
  fi
  [ "$(field latest "$b/index.json")" = "$version" ] ||
    refuse "build/release on the mini holds $(field latest "$b/index.json"), not $version"
  secure="$(field secure "$b/index.json")"
  reinstall="$(field reinstallBelow "$b/index.json")"
  index "$version" "$secure" "$reinstall" 0 "$b/SHA256SUMS" | cmp -s - "$b/index.json" ||
    refuse "build/release's index.json doesn't match its SHA256SUMS"
  abovereleased "$version"
  keepsraised "$version" "$secure" "$reinstall"
  if [ -f "$records/$version/SHA256SUMS" ] && ! cmp -s "$records/$version/SHA256SUMS" "$b/SHA256SUMS"; then
    refuse "another build of $version is signed already: a new build takes a new version"
  fi

  [ -n "$dry" ] || git -C "$root" fetch -q origin master
  git -C "$root" merge-base --is-ancestor "$commit" "$master" || refuse "$commit isn't on $master"
  git -C "$root" show "$commit:connector/release_keys" > "$b/release_keys"
  folderkeys "$b/release_keys" "connector/release_keys at $commit"
  # The paper key, the one ssh-ed25519 key, signs only the release that replaces it.
  if [ "$(awk '{ print $1 }' "$tmp/signer")" = ssh-ed25519 ] && haskey "$kblob" "$tmp/folder"; then
    refuse "$keydir still holds this key: the paper key signs only the release that brings in its successor"
  fi
  rulehas "$version" "$b/release_keys"

  echo "connector $version, built from $commit"
  echo "  latest $version, secure $secure, reinstallBelow $reinstall"
  echo "release_keys:"
  ssh-keygen -lf "$b/release_keys" | sed 's/^/  /'
  # abovereleased put every promoted release below this one.
  prev="$(echo "$promoted" | tail -1)"
  if [ -n "$prev" ]; then
    echo "connector changes since $prev:"
    git -C "$root" log --oneline "$(cat "$records/$prev/commit")..$commit" -- connector | sed 's/^/  /'
  else
    echo "no release promoted before $version"
  fi
  echo "SHA256SUMS:"
  sed 's/^/  /' "$b/SHA256SUMS"

  sign file "$b/SHA256SUMS"
  index "$version" "$secure" "$reinstall" "$now" "$b/SHA256SUMS" > "$b/canary.json"
  sign ovenlight-canary "$b/canary.json"
  echo "$commit" > "$b/commit"
  keep "$version" "$b"/{SHA256SUMS,SHA256SUMS.sig,index.json,commit,release_keys,canary.json,canary.json.sig}
  echo "signed: on the mini, scripts/publish-connector.sh $version, then the canary with $sync/$version/canary.json"
  ;;

promote)
  d="$records/$version"
  [ -f "$d/SHA256SUMS.sig" ] || refuse "$version isn't signed: scripts/sign-connector.sh $version first"
  verify "$d/SHA256SUMS" "$d/SHA256SUMS.sig" file "$tmp/signer" ||
    refuse "$version's SHA256SUMS was signed with another key, which signs its promote too"
  abovereleased "$version"
  ordered "$now"
  folderkeys "$d/release_keys" "$version's release_keys"
  rulehas "$version"
  releaseindex "$version" "$now" > "$tmp/promote-$now.json"
  keepsraised "$version" "$(field secure "$d/canary.json")" "$(field reinstallBelow "$d/canary.json")"
  newkeywait "$kblob" "$now"
  echo "promoting $version: latest.json becomes"
  echo "  $(cat "$tmp/promote-$now.json")"
  sign ovenlight-latest "$tmp/promote-$now.json"
  echo "$newest" > "$tmp/promote-$now.base"
  keep "$version" "$tmp/promote-$now.json" "$tmp/promote-$now.json.sig" "$tmp/promote-$now.base"
  echo "signed: on the mini, scripts/publish-connector.sh --promote $version"
  await "$version/promote-$now"
  ;;

pull)
  [ "$version" = "$live" ] ||
    refuse "$version isn't the live release (${live:-nothing is promoted}): only the live one is pulled, and one that's no longer the latest is fixed forward"
  target="$(keptbelow "$version")"
  [ -n "$target" ] || refuse "nothing promoted before $version to go back to"
  pullsigned "$tmp/signer" "$newest" "$version"
  verifyrule "$target" "$records/$target/SHA256SUMS" "$records/$target/SHA256SUMS.sig" file
  ordered "$now"
  releaseindex "$target" "$now" > "$tmp/pull-$now.json"
  nodrop "$tmp/pull-$now.json"
  echo "pulling $version: latest.json goes back to $target, built on $newest"
  echo "  $(cat "$tmp/pull-$now.json")"
  sign ovenlight-latest "$tmp/pull-$now.json"
  echo "$newest" > "$tmp/pull-$now.base"
  keep "$version" "$tmp/pull-$now.json" "$tmp/pull-$now.json.sig" "$tmp/pull-$now.base"
  echo "signed: on the mini, scripts/publish-connector.sh --pull $version"
  await "$version/pull-$now"
  ;;

resign)
  [ -n "$newest" ] || refuse "no index is published yet"
  # One signed after the live index on an older one superseded it before it went up.
  for id in $signed; do
    if [ "$(serialof "$id")" -gt "$(serialof "$newest")" ] && [ -f "$records/$id.base" ] && [ "$(cat "$records/$id.base")" != "$newest" ]; then
      refuse "latest.json became $newest after $id superseded it: pull $live or promote the next release, rather than re-sign it"
    fi
  done
  for k in $(livekeys); do
    haskey "$kblob" "$k" || refuse "$k doesn't list this key, so computers on that release can't check the index"
  done
  newkeywait "$kblob" "$now"
  ordered "$now"
  releaseindex "$live" "$now" > "$tmp/resign-$now.json"
  echo "re-signing the live index, $newest, for $live:"
  echo "  $(cat "$tmp/resign-$now.json")"
  sign ovenlight-latest "$tmp/resign-$now.json"
  echo "$newest" > "$tmp/resign-$now.base"
  keep "$live" "$tmp/resign-$now.json" "$tmp/resign-$now.json.sig" "$tmp/resign-$now.base"
  echo "signed: on the mini, scripts/publish-connector.sh --resign"
  await "$live/resign-$now"
  ;;
esac
