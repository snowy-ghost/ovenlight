#!/bin/bash
# Builds a connector release into build/release/:
#   ovenlight-connector-macos.tar.gz          a universal (arm64 + x86_64) binary, signed
#                                             with the Developer ID and notarized
#   ovenlight-connector-linux-<arch>.tar.gz   amd64 and arm64
#   ovenlight-connector-windows-<arch>.zip    amd64 and arm64, the binary and the PowerShell
#                                             scripts signed with Azure Artifact Signing
#   SHA256SUMS, SHA256SUMS.sig                their checksums, signed with ssh-keygen
# Each archive holds an ovenlight-connector-<version> folder with the binary, what installs
# it, the licenses and the notices of what it links. The archive names carry no version,
# so the latest release has stable URLs; scripts/publish-connector.sh publishes it.
#
#   scripts/release-connector.sh [--unsigned] <version>     e.g. 1.0.0
#
# It builds with the Go version on connector/go.mod's go line, as CI does, and only a
# clean HEAD on origin/master, checking that each binary carries that commit (go1.26
# stamps none in a git worktree, so run it in a clone). --unsigned skips all signing and
# notarization, and those checks, to test everything else. Notarization uses an App Store
# Connect API key named by ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH in the environment.
# Over SSH, unlock the login keychain first. Windows signing uses jsign (brew install jsign)
# and the Azure CLI, signed in (az login) as someone with the Artifact Signing Certificate
# Profile Signer role on the profile below. SHA256SUMS is signed with git's SSH signing key
# (user.signingkey), which must be listed in docs/allowed_signers.
set -euo pipefail

IDENTITY="Developer ID Application: Snowy Ghost LLC (A84T8TANK8)"
LABEL=com.snowyghost.ovenlight.connector
# Azure Artifact Signing: the account's regional endpoint, and <account>/<certificate profile>.
WIN_ENDPOINT=eus.codesigning.azure.net
WIN_PROFILE=snowyghost/ovenlight
# Go's own minimum. cgo compiles and links with clang, which would otherwise target the
# build machine's macOS. Flags rather than MACOSX_DEPLOYMENT_TARGET: the build cache keys
# on them, so cached objects built for another target aren't reused.
MACOS_MIN=13.0
# ts_omit_webclient leaves out Tailscale's web client, which the connector doesn't use,
# and the JavaScript bundle it embeds. The notices are listed with the same tags.
BUILD_TAGS=ts_omit_webclient

unsigned=false
while [[ "${1:-}" == --* ]]; do
  case "$1" in
  --unsigned) unsigned=true ;;
  *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done
version="${1:?usage: release-connector.sh [--unsigned] <version>, e.g. 1.0.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "version should look like 1.0.0, not $version" >&2; exit 1; }

root="$(cd "$(dirname "$0")/.." && pwd)"
conn="$root/connector"
name="ovenlight-connector-$version"
release="$root/build/release"
signers="$root/docs/allowed_signers"

# An Artifact Signing access token. az may return a cached one with minutes left, so it's
# fetched again just before signing.
wintoken() { az account get-access-token --resource https://codesigning.azure.net --query accessToken -o tsv; }

# CI's Go (setup-go reads go-version-file: connector/go.mod), downloaded if need be.
GOTOOLCHAIN="go$(awk '$1 == "go" { print $2; exit }' "$conn/go.mod")"
export GOTOOLCHAIN

# Check the tree and the credentials before minutes of building.
if ! $unsigned; then
  if [ -n "$(git -C "$root" status --porcelain)" ]; then
    echo "the tree has uncommitted changes; commit them first (or pass --unsigned to test)" >&2
    exit 1
  fi
  git -C "$root" fetch -q origin master
  if ! git -C "$root" merge-base --is-ancestor HEAD origin/master; then
    echo "HEAD isn't on origin/master; push it to master first (or pass --unsigned to test)" >&2
    exit 1
  fi
  head="$(git -C "$root" rev-parse HEAD)"
  if ! security find-identity -v -p codesigning | grep -qF "\"$IDENTITY\""; then
    cat >&2 <<EOF
No "$IDENTITY" signing identity in the keychain.
The Account Holder creates a Developer ID Application certificate (developer.apple.com >
Certificates, or Xcode > Settings > Accounts > Manage Certificates), then it is imported
here with its private key. To build without signing and notarizing, pass --unsigned.
EOF
    exit 1
  fi
  : "${ASC_KEY_ID:?set ASC_KEY_ID to the App Store Connect API key ID}"
  : "${ASC_ISSUER_ID:?set ASC_ISSUER_ID to the issuer ID of that key}"
  : "${ASC_KEY_PATH:?set ASC_KEY_PATH to the .p8 file of that key}"
  [ -f "$ASC_KEY_PATH" ] || { echo "API key not found: $ASC_KEY_PATH" >&2; exit 1; }
  sshkey="$(git -C "$root" config user.signingkey || true)"
  sshkey="${sshkey/#\~/$HOME}"
  if [ ! -f "$sshkey" ] || ! grep -qF " $(awk 'NR == 1 { print $2 }' "$sshkey")" "$signers"; then
    echo "git's SSH signing key (user.signingkey, now \"$sshkey\") isn't in docs/allowed_signers" >&2
    exit 1
  fi
  command -v jsign > /dev/null || { echo "no jsign, to sign for Windows: brew install jsign" >&2; exit 1; }
  if ! wintoken > /dev/null; then
    echo "the Azure CLI can't sign in to Artifact Signing: brew install azure-cli, then az login" >&2
    exit 1
  fi
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# Archives wait here until every one is built, signed and notarized, so a failed run
# leaves nothing in build/release.
out="$tmp/out"
mkdir -p "$out"

# pack <parent> <archive>: adds the licenses to <parent>/$name, then archives that folder.
pack() {
  cp "$root/LICENSE" "$root/NOTICE" "$1/$name/"
  if [[ "$2" == *.zip ]]; then
    (cd "$1" && zip -qrX "$out/$2" "$name")
  else
    # No AppleDouble (._) files or extended attributes: the signature is inside the
    # binary, and GNU tar warns about them. root owns every file, not whoever built it.
    COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs --uid 0 --gid 0 --uname root --gname root \
      -C "$1" -czf "$out/$2" "$name"
  fi
}

# checkstamp <binary>: refuses a binary that go didn't stamp as built from HEAD unchanged.
checkstamp() {
  local info
  info="$(go version -m "$1")"
  if ! grep -qF "vcs.revision=$head" <<< "$info" || ! grep -qF vcs.modified=false <<< "$info"; then
    cat >&2 <<EOF
go version -m doesn't show this build as $head with no changes.
Release from a clone, not a git worktree (go1.26 stamps no commit there), and leave the
tree alone while it builds.
EOF
    exit 1
  fi
}

# Linux and Windows: static binaries, no cgo.
for os in linux windows; do
  for arch in amd64 arm64; do
    echo "building ovenlight $version for $os/$arch"
    dir="$tmp/$os-$arch/$name"
    mkdir -p "$dir"
    exe=ovenlight
    [ "$os" = windows ] && exe=ovenlight.exe
    (cd "$conn" && CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" \
      go build -trimpath -tags "$BUILD_TAGS" -ldflags "-X main.releaseVersion=$version" -o "$dir/$exe" .)
    $unsigned || checkstamp "$dir/$exe"
    CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" "$root/scripts/third-party-notices.sh" "$conn" -tags "$BUILD_TAGS" > "$dir/THIRD_PARTY_NOTICES.txt"
    if [ "$os" = linux ]; then
      mkdir -p "$dir/systemd"
      cp "$conn/install.sh" "$conn/uninstall.sh" "$dir/"
      cp "$conn/systemd/ovenlight.service" "$dir/systemd/"
      pack "$tmp/$os-$arch" "ovenlight-connector-linux-$arch.tar.gz"
    else
      cp "$conn/install.ps1" "$conn/uninstall.ps1" "$dir/"
      # Smart App Control refuses an unsigned binary, and an App Control policy that
      # enforces scripts runs an unsigned script in Constrained Language Mode. The
      # certificates last days, so the timestamp is what keeps a signature valid.
      $unsigned || AZURE_SIGNING_TOKEN="$(wintoken)" jsign --storetype TRUSTEDSIGNING \
        --keystore "$WIN_ENDPOINT" --storepass env:AZURE_SIGNING_TOKEN \
        --alias "$WIN_PROFILE" --tsaurl http://timestamp.acs.microsoft.com --tsmode RFC3161 \
        "$dir/$exe" "$dir/install.ps1" "$dir/uninstall.ps1"
      pack "$tmp/$os-$arch" "ovenlight-connector-windows-$arch.zip"
    fi
  done
done

# macOS
pkg="$tmp/macos/$name"
mkdir -p "$pkg/launchd"

for arch in arm64 amd64; do
  echo "building ovenlight $version for $arch"
  (cd "$conn" && CGO_ENABLED=1 GOOS=darwin GOARCH="$arch" \
    CGO_CFLAGS="-O2 -g -mmacosx-version-min=$MACOS_MIN" CGO_LDFLAGS="-mmacosx-version-min=$MACOS_MIN" \
    go build -trimpath -tags "$BUILD_TAGS" -ldflags "-X main.releaseVersion=$version" -o "$tmp/ovenlight-$arch" .)
  $unsigned || checkstamp "$tmp/ovenlight-$arch"
  CGO_ENABLED=1 GOOS=darwin GOARCH="$arch" "$root/scripts/third-party-notices.sh" "$conn" -tags "$BUILD_TAGS" > "$tmp/notices-$arch.txt"
done
lipo -create -output "$pkg/ovenlight" "$tmp/ovenlight-arm64" "$tmp/ovenlight-amd64"
cmp -s "$tmp/notices-arm64.txt" "$tmp/notices-amd64.txt" ||
  { echo "arm64 and amd64 link different modules: merge their notices" >&2; exit 1; }
cp "$tmp/notices-arm64.txt" "$pkg/THIRD_PARTY_NOTICES.txt"

if $unsigned; then
  echo "not signing or notarizing (--unsigned)"
else
  codesign --force --options runtime --timestamp --identifier "$LABEL" --sign "$IDENTITY" "$pkg/ovenlight"
  codesign --verify --strict --verbose=2 "$pkg/ovenlight"
  # notarytool takes a zip, not a bare binary; ditto keeps the signature intact.
  ditto -c -k --keepParent "$pkg/ovenlight" "$tmp/ovenlight.zip"
  echo "notarizing (a few minutes)"
  xcrun notarytool submit "$tmp/ovenlight.zip" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" \
    --issuer "$ASC_ISSUER_ID" --wait --output-format json > "$tmp/notary.json"
  status="$(plutil -extract status raw -o - "$tmp/notary.json")"
  if [ "$status" != Accepted ]; then
    id="$(plutil -extract id raw -o - "$tmp/notary.json")"
    echo "notarization $status; see: xcrun notarytool log $id --key ... --key-id ... --issuer ..." >&2
    exit 1
  fi
  # A bare binary can't be stapled (stapler takes apps, disk images and installer
  # packages), so Gatekeeper looks the ticket up online the first time it checks it.
fi

cp "$conn/install.sh" "$conn/uninstall.sh" "$pkg/"
cp "$conn/launchd/$LABEL.plist" "$pkg/launchd/"
pack "$tmp/macos" "ovenlight-connector-macos.tar.gz"

(cd "$out" && shasum -a 256 ovenlight-connector-* > SHA256SUMS)
cat "$out/SHA256SUMS"
if ! $unsigned; then
  ssh-keygen -Y sign -q -f "$sshkey" -n file "$out/SHA256SUMS"
  ssh-keygen -Y verify -f "$signers" -I team@snowyghost.com -n file -s "$out/SHA256SUMS.sig" < "$out/SHA256SUMS"
fi

# Only this release, so nothing older is published with it.
rm -rf "$release"
mkdir -p "$release"
mv "$out"/* "$release/"
echo "in $release"
