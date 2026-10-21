#!/bin/bash
# Publishes a connector release to https://downloads.ovenlight.app, the R2 bucket
# ovenlight-downloads, from what scripts/sign-connector.sh signed on the Air:
#   connector/<version>/<file>   each file of the release, kept for good
#   connector/latest/<file>      the same files of the latest release
#   connector/latest.json        its signed index, which the updater and ovenlight doctor
#   connector/latest.json.sig    read, and its signature
#
#   scripts/publish-connector.sh <version>             the version's folder, from build/release
#   scripts/publish-connector.sh --promote <version>   makes it the latest
#   scripts/publish-connector.sh --pull <version>      takes the live release back out
#   scripts/publish-connector.sh --resign              the live index, signed again
#
# Signatures are checked by the one rule (connector-release-lib.sh). The folder goes up
# alone: nothing points at it until its promote. A promote, pull or resign records itself in
# ~/Sync/ovenlight/releases/ before it uploads anything, so one that fails partway is
# finished by running it again, and it goes up only when its serial is the highest signed
# so far. latest/ and latest.json are uploaded fresh, never copied with a version's year-long
# cache. Uploads go through wrangler, signed in with `npx wrangler@4.147.0 login` or
# CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID.
set -euo pipefail

BUCKET=ovenlight-downloads
SITE=https://downloads.ovenlight.app

usage="usage: publish-connector.sh [--promote | --pull] <version>, or --resign"
mode=folder
if [[ "${1:-}" == --* ]]; then
  case "$1" in
  --promote | --pull | --resign) mode="${1#--}" ;;
  *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
fi
version=
if [ "$mode" != resign ]; then
  version="${1:?$usage}"
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=connector-release-lib.sh
. "$root/scripts/connector-release-lib.sh"
[ -z "$version" ] || canonical "$version" || refuse "versions look like 1.2.3, with no leading zeros or suffix, not $version"

# The updater's cap on an archive, and on the binary it takes out of one.
cap="$(sed -En 's|^const maxArchiveBytes = ([0-9][0-9 <]*)(//.*)?$|\1|p' "$root/connector/keys.go")"
[ -n "$cap" ] || refuse "no maxArchiveBytes in connector/keys.go"
cap=$((cap))

# fetch <key> <file>: saves an object of the bucket, or fails when it doesn't exist (404).
# Any other answer, or none, stops the publish, since it would skip a guard.
fetch() {
  local code
  if [ -n "$dry" ]; then
    [ -f "$dry/bucket/$1" ] || return 1
    cp "$dry/bucket/$1" "$2"
    return
  fi
  code="$(curl -sS -o "$2" -w '%{http_code}' "$SITE/$1")" || refuse "couldn't fetch $SITE/$1"
  case "$code" in
  200) ;;
  404) return 1 ;;
  *) refuse "$SITE/$1 answered HTTP $code" ;;
  esac
}

# put <key> <file> <cache-control>: uploads the file, so the object takes that cache.
put() {
  local type="text/plain; charset=utf-8"
  case "$1" in
  *.tar.gz) type=application/gzip ;;
  *.zip) type=application/zip ;;
  *.json) type=application/json ;;
  esac
  if [ -n "$dry" ]; then
    mkdir -p "$(dirname "$dry/bucket/$1")" "$(dirname "$dry/headers/$1")"
    cp "$2" "$dry/bucket/$1"
    printf 'Content-Type: %s\nCache-Control: %s\n' "$type" "$3" > "$dry/headers/$1"
    return
  fi
  npx wrangler@4.147.0 r2 object put "$BUCKET/$1" --file "$2" --content-type "$type" --cache-control "$3" --remote
}
signin() {
  if [ -z "$dry" ]; then npx wrangler@4.147.0 r2 bucket info "$BUCKET" > /dev/null; fi
}

# checkarchive <archive>: refuses one the updater would: anything outside
# ovenlight-connector-$version/, no binary there, or over the cap.
checkarchive() {
  local top="ovenlight-connector-$version/" exe=ovenlight list n
  [ $(($(wc -c < "$1"))) -le "$cap" ] || refuse "$1 is over the updater's cap of $cap bytes"
  if [[ "$1" == *.zip ]]; then
    exe=ovenlight.exe
    list="$(unzip -Z1 "$1")"
  else
    list="$(tar tzf "$1")"
  fi
  awk -v top="$top" 'index($0, top) != 1 { bad = 1 } END { exit bad }' <<< "$list" ||
    refuse "$1 holds more than an $top folder"
  grep -qxF "$top$exe" <<< "$list" || refuse "$1 has no $top$exe"
  if [[ "$1" == *.zip ]]; then
    n=$(($(unzip -p "$1" "$top$exe" | wc -c)))
  else
    n=$(($(tar -xOzf "$1" "$top$exe" | wc -c)))
  fi
  [ "$n" -le "$cap" ] || refuse "$1's $exe is over the updater's cap of $cap bytes"
}

# fetchrelease <version>: its files from connector/<version>/ into $tmp/files, checked
# against the SHA256SUMS signed for it.
fetchrelease() {
  local d="$records/$1" f
  files="$(awk '{ print $2 }' "$d/SHA256SUMS") SHA256SUMS SHA256SUMS.sig"
  mkdir -p "$tmp/files"
  for f in $files; do
    fetch "connector/$1/$f" "$tmp/files/$f" || refuse "no connector/$1/$f in the bucket: publish its folder first"
  done
  cmp -s "$tmp/files/SHA256SUMS" "$d/SHA256SUMS" || refuse "connector/$1/SHA256SUMS isn't the one signed for $1"
  (cd "$tmp/files" && shasum -a 256 -c --quiet SHA256SUMS) || refuse "connector/$1/ doesn't match its SHA256SUMS"
}

# documented <version> <index>: refuses files for latest/, and the index for latest.json,
# that master's docs/allowed_signers, which owners check them with, doesn't verify.
documented() {
  if [ -z "$dry" ]; then git -C "$root" fetch -q origin master; fi
  git -C "$root" show "$master:docs/allowed_signers" > "$tmp/allowed_signers"
  ssh-keygen -Y verify -f "$tmp/allowed_signers" -I team@snowyghost.com -n file \
    -s "$tmp/files/SHA256SUMS.sig" < "$tmp/files/SHA256SUMS" > /dev/null 2>&1 ||
    refuse "$master's docs/allowed_signers doesn't verify $1's SHA256SUMS, which owners check latest/ with: add its key back first"
  ssh-keygen -Y verify -f "$tmp/allowed_signers" -I team@snowyghost.com -n ovenlight-latest \
    -s "$2.sig" < "$2" > /dev/null 2>&1 ||
    refuse "$master's docs/allowed_signers doesn't verify $2, which owners check latest.json with: add its key first"
}

# newestof <pattern>: the newest index signed whose name matches.
newestof() {
  local id found=
  for id in $signed; do
    # shellcheck disable=SC2254 # the pattern is a glob
    case "$id" in $1) found="$id" ;; esac
  done
  [ -n "$found" ] || refuse "no index matching $1 is signed in $records"
  echo "$found"
}

# begin <id>: whether this finishes a publish of that index that failed partway, which the
# record it wrote first says. Only the newest published can be finished.
begin() {
  if [ ! -f "$records/$1.published" ]; then return 1; fi
  [ "$1" = "$newest" ] || refuse "$1 was published, and $newest after it"
  echo "finishing the publish of $1 begun $(date -r "$(cat "$records/$1.published")")"
}

# based <id>: refuses an index built on one that's no longer the newest published.
based() {
  [ "$(cat "$records/$1.base")" = "$newest" ] ||
    refuse "$1 was built on $(cat "$records/$1.base"), and $newest is published now: sign a fresh one"
}

# goes <id> <version>: publishes the index, after the files of the version it names.
goes() {
  local f
  for f in $files; do
    put "connector/latest/$f" "$tmp/files/$f" "public, max-age=300"
  done
  put connector/latest.json.sig "$records/$1.json.sig" "public, max-age=300"
  put connector/latest.json "$records/$1.json" "public, max-age=300"
  echo "published $1: $SITE/connector/latest.json names $2"
}

case "$mode" in
folder)
  [ -d "$release" ] || refuse "no build/release; run scripts/release-connector.sh $version"
  cd "$release"
  for f in SHA256SUMS SHA256SUMS.sig; do
    [ -f "$f" ] || refuse "no $f in build/release: build $version, then sign it"
  done
  # The release is what the signed SHA256SUMS lists, with or without Windows.
  files="$(awk '{ print $2 }' SHA256SUMS) SHA256SUMS SHA256SUMS.sig"
  while read -r _ f; do checkarchive "$f"; done < SHA256SUMS
  shasum -a 256 -c --quiet SHA256SUMS
  { [ -f "$records/$version/SHA256SUMS" ] && cmp -s SHA256SUMS "$records/$version/SHA256SUMS"; } ||
    refuse "build/release isn't the build of $version that was signed"
  verifyrule "$version" SHA256SUMS SHA256SUMS.sig file
  # A version's files are cached as immutable, so they're never replaced by other ones. The
  # same files again finish a publish that failed partway.
  if fetch "connector/$version/SHA256SUMS" "$tmp/SHA256SUMS" && ! cmp -s "$tmp/SHA256SUMS" SHA256SUMS; then
    refuse "another build of $version is already published"
  fi
  signin
  for f in $files; do
    put "connector/$version/$f" "$f" "public, max-age=31536000, immutable"
  done
  echo "published $SITE/connector/$version/; latest.json names it once it's promoted"
  ;;

promote)
  # The last index published, when it's this version's promote, may be one that failed
  # partway: it's finished, rather than a newer signature that has no record.
  case "$newest" in
  "$version"/promote-*) id="$newest" ;;
  *) id="$(newestof "$version/promote-*")" ;;
  esac
  idx="$records/$id.json"
  releaseindex "$version" "$(serialof "$id")" | cmp -s - "$idx" || refuse "$id isn't $version's index"
  # Its key signed the release's SHA256SUMS, and the one rule accepts both.
  k="$(signer "$idx" "$idx.sig" ovenlight-latest)"
  [ -n "$k" ] || refuse "$id isn't signed by a key any release lists"
  echo "$k" > "$tmp/signer"
  verify "$records/$version/SHA256SUMS" "$records/$version/SHA256SUMS.sig" file "$tmp/signer" ||
    refuse "$id and $version's SHA256SUMS are signed with different keys"
  verifyrule "$version" "$records/$version/SHA256SUMS" "$records/$version/SHA256SUMS.sig" file
  verifyrule "$version" "$idx" "$idx.sig" ovenlight-latest
  fetchrelease "$version"
  documented "$version" "$idx"
  if ! begin "$id"; then
    abovereleased "$version"
    ordered "$(serialof "$id")" "$id"
    nodrop "$idx"
    newkeywait "${k#* }" "$(date +%s)"
    signin
    date +%s > "$records/$id.published"
  fi
  goes "$id" "$version"
  ;;

pull)
  id="$(newestof "$version/pull-*")"
  idx="$records/$id.json"
  target="$(field latest "$idx")"
  [ "$target" != "$version" ] || refuse "$id names $version, the release it pulls"
  releaseindex "$target" "$(serialof "$id")" | cmp -s - "$idx" || refuse "$id isn't $target's index"
  # Signed with the key of the index it replaces, so every computer that took that one can
  # check it, or with one every computer it reaches holds (pullkeys).
  base="$(cat "$records/$id.base")"
  k="$(signer "$idx" "$idx.sig" ovenlight-latest)"
  [ -n "$k" ] || refuse "$id isn't signed by a key any release lists"
  echo "$k" > "$tmp/signer"
  if ! verify "$records/$base.json" "$records/$base.json.sig" ovenlight-latest "$tmp/signer"; then
    for f in $(pullkeys "$version"); do
      haskey "${k#* }" "$f" || refuse "$id isn't signed with the key of $base, the index it replaces, or one $f lists"
    done
  fi
  verifyrule "$target" "$records/$target/SHA256SUMS" "$records/$target/SHA256SUMS.sig" file
  fetchrelease "$target"
  documented "$target" "$idx"
  if ! begin "$id"; then
    [ "$version" = "$live" ] || refuse "$version isn't the live release (${live:-nothing is promoted})"
    [ "$target" = "$(keptbelow "$version")" ] || refuse "$id goes back to $target, not $(keptbelow "$version")"
    based "$id"
    ordered "$(serialof "$id")" "$id"
    nodrop "$idx"
    signin
    date +%s > "$records/$id.published"
  fi
  goes "$id" "$target"
  ;;

resign)
  id="$(newestof "*/resign-*")"
  idx="$records/$id.json"
  version="${id%%/*}"
  releaseindex "$version" "$(serialof "$id")" | cmp -s - "$idx" || refuse "$id isn't $version's index"
  # Checked against the keys of the live release, and of the pulled ones above it.
  for f in $(livekeys); do
    verify "$idx" "$idx.sig" ovenlight-latest "$f" || refuse "$id isn't signed by a key $f lists"
  done
  k="$(signer "$idx" "$idx.sig" ovenlight-latest)"
  # latest/ goes up again, so a promote cut short before it can't leave a mix of releases.
  fetchrelease "$version"
  documented "$version" "$idx"
  if ! begin "$id"; then
    [ "$version" = "$live" ] || refuse "$id is for $version, and the live release is ${live:-none}"
    based "$id"
    ordered "$(serialof "$id")" "$id"
    newkeywait "${k#* }" "$(date +%s)"
    signin
    date +%s > "$records/$id.published"
  fi
  goes "$id" "$version"
  ;;
esac
