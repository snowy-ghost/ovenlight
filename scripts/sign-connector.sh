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
#       wasn't pulled, with the key that signed the live index
#   scripts/sign-connector.sh [--key <file>] --resign
#       the live index again, with a key the live release lists
#
# Each index gets the time now as its serial. What it signs goes in the release's folder in
# ~/Sync/ovenlight/releases/, which scripts/publish-connector.sh publishes from on the mini.
# Touch ID shows a prompt and not what it signs, so this prints that first, and refuses
# before it whatever publish-connector.sh would refuse. A release is signed only when its
# connector/release_keys is exactly the two keys in ~/.ssh/ovenlight-release-keys/ plus the
# key signing it, so the keys a release trusts never come from the commit alone.
#
# --key signs with a key other than the Touch ID handle, such as the paper backup, with its
# public half beside it as <file>.pub.
set -euo pipefail

HANDLE="$HOME/.ssh/id_ecdsa_sk_rk_ovenlight-release"

usage="usage: sign-connector.sh [--key <file>] [--promote | --pull] <version>, or [--key <file>] --resign"
mode=release
key=
while [[ "${1:-}" == --* ]]; do
  case "$1" in
  --key) key="${2:?$usage}"; shift ;;
  --promote | --pull | --resign) mode="${1#--}" ;;
  *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done
version=
if [ "$mode" != resign ]; then
  version="${1:?$usage}"
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "versions look like 1.0.0, not $version" >&2; exit 1; }
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=connector-release-lib.sh
. "$root/scripts/connector-release-lib.sh"

if [ -z "$key" ]; then
  [ -z "$dry" ] || refuse "a dry run signs with --key and a fixture key, never the Touch ID key"
  key="$HANDLE"
  export SSH_SK_PROVIDER=/usr/lib/ssh-keychain.dylib
fi
[ -f "$key" ] && [ -f "$key.pub" ] || refuse "no $key, or no $key.pub beside it"
awk 'NF >= 2 { print $1, $2; exit }' "$key.pub" > "$tmp/signer"
kblob="$(awk '{ print $2 }' "$tmp/signer")"
trusted "$tmp/signer"

# One serial from the future would block every later index, so the clock that sets it is
# checked against the mini's.
now="$(date +%s)"
if [ -n "$dry" ]; then mini="$now"; else mini="$(ssh mini date +%s)"; fi
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

# keep <file> <name>...: puts the signed files in the release's folder.
keep() {
  local d="$records/$1"
  shift
  mkdir -p "$d"
  cp "$@" "$d/"
}

echo "signing with $(ssh-keygen -lf "$key.pub")"
case "$mode" in
release)
  b="$tmp/build"
  mkdir "$b"
  if [ -n "$dry" ]; then
    cp "$minirelease/SHA256SUMS" "$minirelease/index.json" "$minirelease/commit" "$b/"
  else
    scp -q "$minirelease/SHA256SUMS" "$minirelease/index.json" "$minirelease/commit" "$b/"
  fi
  read -r commit tag < "$b/commit"
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
  if [ -f "$records/$version/SHA256SUMS" ] && ! cmp -s "$records/$version/SHA256SUMS" "$b/SHA256SUMS"; then
    refuse "another build of $version is signed already: a new build takes a new version"
  fi

  # The keys at the commit are the ones in the key folder, plus this one.
  [ -n "$dry" ] || git -C "$root" fetch -q origin master
  git -C "$root" merge-base --is-ancestor "$commit" "$master" || refuse "$commit isn't on $master"
  git -C "$root" show "$commit:connector/release_keys" > "$b/release_keys"
  trusted "$b/release_keys"
  n=0
  for f in "$keydir"/*.pub; do n=$((n + 1)); done
  [ "$n" -eq 2 ] || refuse "$keydir holds $n .pub files, not the two release keys"
  cat "$keydir"/*.pub > "$tmp/folder"
  trusted "$tmp/folder"
  cat "$tmp/folder" "$tmp/signer" | awk 'NF >= 2 { print $2 }' | sort -u > "$tmp/want"
  awk 'NF >= 2 { print $2 }' "$b/release_keys" | sort -u > "$tmp/have"
  if ! cmp -s "$tmp/want" "$tmp/have"; then
    refuse "connector/release_keys at $commit isn't the two keys in $keydir plus the one signing:
$(diff "$tmp/want" "$tmp/have" | sed -n 's/^</  missing/p; s/^>/  extra/p')"
  fi
  rulehas "$version" "$b/release_keys"

  echo "connector $version, built from $commit"
  echo "  latest $version, secure $secure, reinstallBelow $reinstall"
  prev=
  for d in "$records"/*/commit; do
    v="$(basename "$(dirname "$d")")"
    if below "$v" "$version" && { [ -z "$prev" ] || below "$prev" "$v"; }; then prev="$v"; fi
  done
  if [ -n "$prev" ]; then
    echo "connector changes since $prev:"
    git -C "$root" log --oneline "$(cat "$records/$prev/commit")..$commit" -- connector | sed 's/^/  /'
  else
    echo "no earlier release in $records"
  fi
  echo "SHA256SUMS:"
  sed 's/^/  /' "$b/SHA256SUMS"

  sign file "$b/SHA256SUMS"
  index "$version" "$secure" "$reinstall" "$now" "$b/SHA256SUMS" > "$b/canary.json"
  sign ovenlight-canary "$b/canary.json"
  echo "$commit" > "$b/commit"
  keep "$version" "$b"/{SHA256SUMS,SHA256SUMS.sig,index.json,commit,release_keys,canary.json,canary.json.sig}
  if [ -n "$dry" ]; then
    cp "$b/SHA256SUMS.sig" "$minirelease/"
  else
    scp -q "$b/SHA256SUMS.sig" "$minirelease/"
  fi
  echo "signed: on the mini, scripts/publish-connector.sh $version, then the canary with $records/$version/canary.json"
  ;;

promote)
  d="$records/$version"
  [ -f "$d/SHA256SUMS.sig" ] || refuse "$version isn't signed: scripts/sign-connector.sh $version first"
  verify "$d/SHA256SUMS" "$d/SHA256SUMS.sig" file "$tmp/signer" ||
    refuse "$version's SHA256SUMS was signed with another key, which signs its promote too"
  abovereleased "$version"
  ordered "$now"
  releaseindex "$version" "$now" > "$tmp/promote-$now.json"
  nodrop "$tmp/promote-$now.json"
  rulehas "$version"
  newkeywait "$kblob" "$now"
  echo "promoting $version: latest.json becomes"
  echo "  $(cat "$tmp/promote-$now.json")"
  sign ovenlight-latest "$tmp/promote-$now.json"
  keep "$version" "$tmp/promote-$now.json" "$tmp/promote-$now.json.sig"
  echo "signed: on the mini, scripts/publish-connector.sh --promote $version"
  ;;

pull)
  [ "$version" = "$live" ] ||
    refuse "$version isn't the live release (${live:-nothing is promoted}): only the live one is pulled, and one that's no longer the latest is fixed forward"
  target="$(keptbelow "$version")"
  [ -n "$target" ] || refuse "nothing promoted before $version to go back to"
  # Computers that took the live index can check a pull signed with its key.
  verify "$records/$newest.json" "$records/$newest.json.sig" ovenlight-latest "$tmp/signer" ||
    refuse "the live index, $newest, was signed with another key, which signs its pull too"
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
  ;;

resign)
  [ -n "$newest" ] || refuse "no index is published yet"
  haskey "$kblob" "$records/$live/release_keys" ||
    refuse "the live release, $live, doesn't list this key, so it can't check the index"
  newkeywait "$kblob" "$now"
  ordered "$now"
  releaseindex "$live" "$now" > "$tmp/resign-$now.json"
  echo "re-signing the live index, $newest, for $live:"
  echo "  $(cat "$tmp/resign-$now.json")"
  sign ovenlight-latest "$tmp/resign-$now.json"
  echo "$newest" > "$tmp/resign-$now.base"
  keep "$live" "$tmp/resign-$now.json" "$tmp/resign-$now.json.sig" "$tmp/resign-$now.base"
  echo "signed: on the mini, scripts/publish-connector.sh --resign"
  ;;
esac
