#!/bin/bash
# Publishes a connector release to https://downloads.ovenlight.app, the R2 bucket
# ovenlight-downloads, from what scripts/sign-connector.sh signed on the Air:
#   connector/<version>/<file>   each file of the release, kept for good
#   connector/latest/<file>      the same files of the latest release
#   connector/latest.json        its signed index, which the updater reads, as an older
#   connector/latest.json.sig    release's doctor does, and its signature
#
#   scripts/publish-connector.sh <version>             the version's folder, from build/release
#   scripts/publish-connector.sh --promote <version>   makes it the latest
#   scripts/publish-connector.sh --pull <version>      takes the live release back out
#   scripts/publish-connector.sh --resign              the live index, signed again
#
# Signatures are checked by the one rule (connector-release-lib.sh). The folder goes up
# alone, once its connector-v<version> tag is the commit it was built from, here and on
# origin: nothing points at it until its promote. A promote, pull or resign goes up only
# when it's the newest index signed, checked against the records as they were when it was
# signed, so one that fails partway is finished by running it again, and dropped by signing
# the next one.
# latest/ and latest.json are uploaded fresh, never copied with a version's year-long cache.
# Uploads go through wrangler, signed in with `npx wrangler@4.147.0 login` or
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

# checkarchive <archive>: refuses one the updater would, over the cap or with no binary in
# ovenlight-connector-$version/, and one holding anything outside that folder, since an
# install by hand unpacks every entry with its first folder stripped.
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
# that master's docs/allowed_signers doesn't verify. Owners check latest.json with it, and
# 1.0.x's docs have them check latest/SHA256SUMS with it.
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

# isindex <version>: refuses the index unless it's the release's, with its serial, after
# releaseindex's own refusals.
isindex() {
  releaseindex "$1" "$(serialof "$id")" > "$tmp/want.json"
  cmp -s "$tmp/want.json" "$idx" || refuse "$id isn't $1's index"
}

# newestsigned: refuses another mode or version than the newest index signed has, since
# only it goes up, and reads the records as they were when it was signed.
newestsigned() {
  local cmd="--${top#*/}"
  [ -n "$top" ] || refuse "no promote, pull or resign is signed in $records"
  cmd="${cmd%-*}"
  [ "$cmd" = --resign ] || cmd="$cmd ${top%%/*}"
  # shellcheck disable=SC2254 # the pattern is a glob
  case "$top" in ${version:-*}/$mode-*) ;; *) refuse "$top is the newest index signed here, and only it goes up. If the Air holds a newer one, signed since or lost here, scripts/sign-connector.sh --check there sends it here. If not, scripts/publish-connector.sh $cmd" ;; esac
  id="$top" idx="$records/$top.json"
  [ -f "$idx.sig" ] || refuse "$id has no .sig in $records: scripts/sign-connector.sh --check on the Air sends it again"
  baseof "$id"
  readchain "$base"
}

# goes <id> <version>: publishes the index, after the files of the version it names.
goes() {
  local f
  for f in $files; do
    put "connector/latest/$f" "$tmp/files/$f" "public, max-age=300"
  done
  put connector/latest.json.sig "$records/$1.json.sig" "public, max-age=300"
  put connector/latest.json "$records/$1.json" "public, max-age=300"
  date +%s > "$records/$1.live"
  echo "published $1: $SITE/connector/latest.json names $2"
}

case "$mode" in
folder)
  [ -d "$release" ] || refuse "no build/release; run scripts/release-connector.sh $version"
  cd "$release"
  [ -f SHA256SUMS ] || refuse "no SHA256SUMS in build/release: build $version"
  sig="$records/$version/SHA256SUMS.sig"
  [ -f "$sig" ] || refuse "no $sig: sign $version on the Air"
  # Owners check a release with the allowed_signers at its tag, on origin.
  built="$(cat "$records/$version/commit")"
  [ -n "$dry" ] || git -C "$root" fetch -q origin tag "connector-v$version" ||
    refuse "connector-v$version isn't pushed to origin as it is here: push it"
  [ "$(git -C "$root" rev-parse -q --verify "connector-v$version^{commit}")" = "$built" ] ||
    refuse "connector-v$version isn't tagged on $built, the commit $version was built from: tag it"
  # The release is what the signed SHA256SUMS lists, with or without Windows.
  files="$(awk '{ print $2 }' SHA256SUMS) SHA256SUMS"
  while read -r _ f; do checkarchive "$f"; done < SHA256SUMS
  shasum -a 256 -c --quiet SHA256SUMS
  cmp -s SHA256SUMS "$records/$version/SHA256SUMS" || refuse "build/release isn't the build of $version that was signed"
  verifyrule "$version" SHA256SUMS "$sig" file
  # A version's files are cached as immutable, so they're never replaced by other ones. The
  # same files again finish a publish that failed partway.
  if fetch "connector/$version/SHA256SUMS" "$tmp/SHA256SUMS" && ! cmp -s "$tmp/SHA256SUMS" SHA256SUMS; then
    refuse "another build of $version is already published"
  fi
  signin
  for f in $files; do
    put "connector/$version/$f" "$f" "public, max-age=31536000, immutable"
  done
  put "connector/$version/SHA256SUMS.sig" "$sig" "public, max-age=31536000, immutable"
  echo "published $SITE/connector/$version/; latest.json names it once it's promoted"
  ;;

promote)
  newestsigned
  isindex "$version"
  # Its key signed the release's SHA256SUMS, so the one rule, accepting it, accepts both.
  k="$(signer "$idx" "$idx.sig" ovenlight-latest)"
  [ -n "$k" ] || refuse "$id isn't signed by a key any release lists"
  echo "$k" > "$tmp/signer"
  verify "$records/$version/SHA256SUMS" "$records/$version/SHA256SUMS.sig" file "$tmp/signer" ||
    refuse "$id and $version's SHA256SUMS are signed with different keys"
  verifyrule "$version" "$idx" "$idx.sig" ovenlight-latest
  fetchrelease "$version"
  documented "$version" "$idx"
  abovereleased "$version"
  nodrop "$idx"
  newkeywait "${k#* }" "$(date +%s)"
  signin
  goes "$id" "$version"
  ;;

pull)
  newestsigned
  target="$(field latest "$idx")"
  isindex "$target"
  signer "$idx" "$idx.sig" ovenlight-latest > "$tmp/signer"
  [ -s "$tmp/signer" ] || refuse "$id isn't signed by a key any release lists"
  pullsigned "$tmp/signer" "$newest" "$version"
  verifyrule "$target" "$records/$target/SHA256SUMS" "$records/$target/SHA256SUMS.sig" file
  fetchrelease "$target"
  documented "$target" "$idx"
  [ "$version" = "$live" ] || refuse "$version isn't the live release (${live:-nothing is promoted})"
  [ "$target" = "$(keptbelow "$version")" ] || refuse "$id goes back to $target, not $(keptbelow "$version")"
  nodrop "$idx"
  signin
  goes "$id" "$target"
  ;;

resign)
  newestsigned
  version="${id%%/*}"
  isindex "$version"
  # Checked against the keys of the live release, and of the pulled ones above it.
  for f in $(livekeys); do
    verify "$idx" "$idx.sig" ovenlight-latest "$f" || refuse "$id isn't signed by a key $f lists"
  done
  k="$(signer "$idx" "$idx.sig" ovenlight-latest)"
  # latest/ goes up again, since a promote cut short and then dropped may have left its files.
  fetchrelease "$version"
  documented "$version" "$idx"
  [ "$version" = "$live" ] || refuse "$id is for $version, and the live release is ${live:-none}"
  newkeywait "${k#* }" "$(date +%s)"
  signin
  goes "$id" "$version"
  ;;
esac
