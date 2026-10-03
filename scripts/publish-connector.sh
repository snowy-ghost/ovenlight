#!/bin/bash
# Publishes the release that scripts/release-connector.sh left in build/release/ to
# https://downloads.ovenlight.app, the R2 bucket ovenlight-downloads:
#   connector/<version>/<file>   each file of the release, kept for good
#   connector/latest/<file>      the same files, replaced by the next release
#   connector/latest.json        {"latest": "<version>", "secure": "<secure>"}, which
#                                ovenlight doctor reads
#
#   scripts/publish-connector.sh <version> <secure>     e.g. 1.0.1 1.0.0
#
# <secure> is the oldest version without a known security problem: doctor fails on any
# older one, so raise it with a release that fixes one. Uploads go through wrangler, signed
# in with `npx wrangler login` or CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID.
set -euo pipefail

BUCKET=ovenlight-downloads
SITE=https://downloads.ovenlight.app
FILES=(ovenlight-connector-macos.tar.gz ovenlight-connector-linux-amd64.tar.gz
  ovenlight-connector-linux-arm64.tar.gz ovenlight-connector-windows-amd64.zip
  ovenlight-connector-windows-arm64.zip SHA256SUMS SHA256SUMS.sig)

usage="usage: publish-connector.sh <version> <secure>, e.g. 1.0.1 1.0.0"
version="${1:?$usage}"
secure="${2:?$usage}"
for v in "$version" "$secure"; do
  # doctor reads only plain versions.
  [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "versions look like 1.0.0, not $v" >&2; exit 1; }
done
# atleast <a> <b>: whether version b is a or later.
atleast() { [ "$(printf '%s\n' "$1" "$2" | sort -V | tail -1)" = "$2" ]; }
atleast "$secure" "$version" || { echo "secure ($secure) is newer than the release ($version)" >&2; exit 1; }

root="$(cd "$(dirname "$0")/.." && pwd)"
release="$root/build/release"
signers="$root/docs/allowed_signers"

[ -d "$release" ] || { echo "no build/release; run scripts/release-connector.sh $version" >&2; exit 1; }
cd "$release"
for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "no $f in build/release; run scripts/release-connector.sh $version" >&2; exit 1; }
done
[ "$(tar tzf ovenlight-connector-linux-amd64.tar.gz | head -1)" = "ovenlight-connector-$version/" ] ||
  { echo "build/release holds another version than $version" >&2; exit 1; }
shasum -a 256 -c --quiet SHA256SUMS
ssh-keygen -Y verify -f "$signers" -I team@snowyghost.com -n file -s SHA256SUMS.sig < SHA256SUMS
# Neither latest nor secure goes back.
if current="$(curl -fs "$SITE/connector/latest.json")"; then
  field() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" <<< "$current"; }
  atleast "$(field latest)" "$version" || { echo "$(field latest) is already out" >&2; exit 1; }
  atleast "$(field secure)" "$secure" || { echo "secure is already $(field secure)" >&2; exit 1; }
fi
# A version's files are cached as immutable, so they're never replaced by other ones. The
# same files again finish a publish that failed partway.
if published="$(curl -fs "$SITE/connector/$version/SHA256SUMS")" && [ "$published" != "$(cat SHA256SUMS)" ]; then
  echo "another build of $version is already published" >&2
  exit 1
fi
npx wrangler r2 bucket info "$BUCKET" > /dev/null

# put <key> <file> <cache-control>
put() {
  local type="text/plain; charset=utf-8"
  case "$1" in
    *.tar.gz) type=application/gzip ;;
    *.zip) type=application/zip ;;
    *.json) type=application/json ;;
  esac
  npx wrangler r2 object put "$BUCKET/$1" --file "$2" --content-type "$type" --cache-control "$3" --remote
}

for f in "${FILES[@]}"; do
  put "connector/$version/$f" "$f" "public, max-age=31536000, immutable"
done
# The latest names change with each release, so caches keep them only briefly.
for f in "${FILES[@]}"; do
  put "connector/latest/$f" "$f" "public, max-age=300"
done
printf '{"latest": "%s", "secure": "%s"}\n' "$version" "$secure" > "$root/build/latest.json"
put connector/latest.json "$root/build/latest.json" "public, max-age=300"
echo "published $version: $SITE/connector/latest/"
