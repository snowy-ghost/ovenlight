#!/bin/bash
# Builds a connector release into build/release/, each archive with a .sha256 beside it:
#   ovenlight-connector-<version>-macos.tar.gz      a universal (arm64 + x86_64) binary,
#                                                   signed with the Developer ID and notarized
#   ovenlight-connector-<version>-linux-<arch>.tar.gz    amd64 and arm64
#   ovenlight-connector-<version>-windows-<arch>.zip     amd64 and arm64, not code-signed
# Each holds the binary, what installs it, the licenses and the notices of what it links.
#
#   scripts/release-connector.sh [--unsigned] <version>     e.g. 1.0.0
#
# It builds with the Go version on connector/go.mod's go line, as CI does, and refuses a
# tree with uncommitted changes. --unsigned skips signing and notarization, and the
# clean-tree check, to test everything else. Notarization uses an App Store Connect API
# key named by ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH in the environment. Over SSH,
# unlock the login keychain first.
set -euo pipefail

IDENTITY="Developer ID Application: Snowy Ghost LLC (A84T8TANK8)"
LABEL=com.snowyghost.ovenlight.connector
# Go's own minimum. cgo compiles and links with clang, which would otherwise target the
# build machine's macOS. Flags rather than MACOSX_DEPLOYMENT_TARGET: the build cache keys
# on them, so cached objects built for another target aren't reused.
MACOS_MIN=13.0

unsigned=false
if [ "${1:-}" = "--unsigned" ]; then
  unsigned=true
  shift
fi
version="${1:?usage: release-connector.sh [--unsigned] <version>, e.g. 1.0.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "version should look like 1.0.0, not $version" >&2; exit 1; }

root="$(cd "$(dirname "$0")/.." && pwd)"
conn="$root/connector"
name="ovenlight-connector-$version"
release="$root/build/release"

# CI's Go (setup-go reads go-version-file: connector/go.mod), downloaded if need be.
GOTOOLCHAIN="go$(awk '$1 == "go" { print $2; exit }' "$conn/go.mod")"
export GOTOOLCHAIN

# Check the tree and the credentials before minutes of building.
if ! $unsigned; then
  if ! git -C "$root" diff --quiet HEAD || [ -n "$(git -C "$root" ls-files --others --exclude-standard -- connector)" ]; then
    echo "the tree has uncommitted changes; commit them first (or pass --unsigned to test)" >&2
    exit 1
  fi
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
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# Archives wait here until every one is built, signed and notarized, so a failed run
# leaves nothing in build/release.
out="$tmp/out"
mkdir -p "$out"

# pack <parent> <archive>: adds the licenses to <parent>/$name, then archives that
# folder, with its checksum beside it.
pack() {
  cp "$root/LICENSE" "$root/NOTICE" "$1/$name/"
  if [[ "$2" == *.zip ]]; then
    (cd "$1" && zip -qrX "$out/$2" "$name")
  else
    # No AppleDouble (._) files or extended attributes: the signature is inside the
    # binary, and GNU tar warns about them.
    COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -C "$1" -czf "$out/$2" "$name"
  fi
  (cd "$out" && shasum -a 256 "$2" > "$2.sha256")
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
      go build -trimpath -ldflags "-X main.releaseVersion=$version" -o "$dir/$exe" .)
    CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" "$root/scripts/third-party-notices.sh" "$conn" > "$dir/THIRD_PARTY_NOTICES.txt"
    if [ "$os" = linux ]; then
      mkdir -p "$dir/systemd"
      cp "$conn/install.sh" "$conn/uninstall.sh" "$dir/"
      cp "$conn/systemd/ovenlight.service" "$dir/systemd/"
      pack "$tmp/$os-$arch" "$name-linux-$arch.tar.gz"
    else
      # Not code-signed yet, so Windows may warn about it or block it. Authenticode
      # signing (Azure Trusted Signing, or a certificate with signtool) goes here, on
      # "$dir/$exe", before it's packed.
      cp "$conn/install.ps1" "$conn/uninstall.ps1" "$dir/"
      pack "$tmp/$os-$arch" "$name-windows-$arch.zip"
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
    go build -trimpath -ldflags "-X main.releaseVersion=$version" -o "$tmp/ovenlight-$arch" .)
  CGO_ENABLED=1 GOOS=darwin GOARCH="$arch" "$root/scripts/third-party-notices.sh" "$conn" > "$tmp/notices-$arch.txt"
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
pack "$tmp/macos" "$name-macos.tar.gz"

mkdir -p "$release"
cat "$out"/*.sha256
mv -f "$out"/* "$release/"
echo "in $release"
