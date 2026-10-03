#!/bin/bash
# Builds TailscaleKit.xcframework (device + simulator) from tailscale/libtailscale at a
# pinned commit into Frameworks/, which is gitignored. Cached: a second run with the
# same pin does nothing. Pass --force to rebuild.
set -euo pipefail

# Pinned upstream commit (libtailscale main, 2026-09). Bump deliberately: rerun, then
# test the embedded node end to end before shipping.
LIBTAILSCALE_REPO=https://github.com/tailscale/libtailscale.git
LIBTAILSCALE_COMMIT=59d4bb82744915815178e0f0776d60026a397ee7
# libtailscale's tailscale.com dependency doesn't build with Go 1.27 yet. Keep the
# latest 1.26 patch release: it carries the standard library's security fixes.
GO_TOOLCHAIN=go1.26.6
# Modules raised past libtailscale's go.mod for security fixes (govulncheck). Drop an
# entry once the pinned go.mod reaches it: go get would otherwise move it back down.
GO_UPGRADES="golang.org/x/net@v0.56.0 golang.org/x/text@v0.39.0"
# ts_omit_debug drops the node's debug LocalAPI, pprof and debug HTTP handlers, which
# Ovenlight never calls. libtailscale's Makefile has no knob for tags, so it is edited.
BUILD_TAGS=ios,ts_omit_debug
# Every node would otherwise upload its logs to log.tailscale.com, guests' phones
# included. The knob is read in Go, so it is set from a Go init: a setenv from Swift
# comes after the Go runtime has copied the environment.
NO_LOGS_FILE=ovenlight_nologs.go
# The licenses of libtailscale and everything it links, in each framework slice, for the
# app's Acknowledgements screen.
NOTICES=THIRD_PARTY_NOTICES.txt

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/Frameworks/TailscaleKit.xcframework"
stamp="$root/Frameworks/.tailscalekit-pin"
src="$root/build/libtailscale"
want="$LIBTAILSCALE_COMMIT $GO_TOOLCHAIN $BUILD_TAGS $GO_UPGRADES $NO_LOGS_FILE $NOTICES"

if [ "${1:-}" != "--force" ] && [ -d "$out" ] && [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$want" ]; then
  echo "TailscaleKit.xcframework is current ($LIBTAILSCALE_COMMIT)"
  exit 0
fi

if [ ! -d "$src/.git" ]; then
  git clone --quiet "$LIBTAILSCALE_REPO" "$src"
fi
if ! git -C "$src" cat-file -e "$LIBTAILSCALE_COMMIT^{commit}" 2>/dev/null; then
  git -C "$src" fetch --quiet origin
fi
git -C "$src" checkout --quiet --force "$LIBTAILSCALE_COMMIT"
git -C "$src" clean -fdxq
sed -i '' "s/-tags ios /-tags $BUILD_TAGS /" "$src/Makefile"
grep -q -- "-tags $BUILD_TAGS " "$src/Makefile" || { echo "couldn't set build tags in libtailscale's Makefile" >&2; exit 1; }
cat > "$src/$NO_LOGS_FILE" <<'EOF'
package main

import "tailscale.com/envknob"

func init() { envknob.SetNoLogsNoSupport() }
EOF
# shellcheck disable=SC2086
(cd "$src" && tsbefore="$(GOTOOLCHAIN="$GO_TOOLCHAIN" go list -m tailscale.com)" &&
  GOTOOLCHAIN="$GO_TOOLCHAIN" go get $GO_UPGRADES &&
  [ "$(GOTOOLCHAIN="$GO_TOOLCHAIN" go list -m tailscale.com)" = "$tsbefore" ]) ||
  { echo "raising $GO_UPGRADES failed or moved tailscale.com; check the pins" >&2; exit 1; }

# The device slice is arm64 and the simulator's arm64 and x86_64; they link the same modules.
for arch in arm64 amd64; do
  (cd "$src" && GOOS=ios GOARCH="$arch" CGO_ENABLED=1 GOTOOLCHAIN="$GO_TOOLCHAIN" \
    "$root/scripts/third-party-notices.sh" . -tags "$BUILD_TAGS") > "$src/notices-$arch.txt"
done
cmp -s "$src/notices-arm64.txt" "$src/notices-amd64.txt" ||
  { echo "arm64 and amd64 link different modules: merge their notices" >&2; exit 1; }
{
  title="github.com/tailscale/libtailscale $LIBTAILSCALE_COMMIT"
  printf '%s\n%s\n\n' "$title" "${title//?/=}"
  cat "$src/LICENSE"
  printf '\n\n'
  cat "$src/notices-arm64.txt"
} > "$src/$NOTICES"

echo "building TailscaleKit at $LIBTAILSCALE_COMMIT with $GO_TOOLCHAIN, tags $BUILD_TAGS (several minutes)"
# Apple's tools first: libtailscale's scripts expect Xcode's clang and rsync.
(cd "$src/swift" && PATH="/usr/bin:/bin:/usr/sbin:/sbin:$PATH" GOTOOLCHAIN="$GO_TOOLCHAIN" make ios-fat)

built="$src/swift/build/Build/Products/Release-iphonefat/TailscaleKit.xcframework"
[ -d "$built" ] || { echo "no xcframework at $built" >&2; exit 1; }
rm -rf "$out"
mkdir -p "$(dirname "$out")"
cp -R "$built" "$out"
# Apple wants a privacy manifest in each framework that calls a required-reason API; the
# app's own doesn't cover it. Go's runtime and os package use mach_absolute_time and the
# stat family.
for fw in "$out"/*/TailscaleKit.framework; do
  cp "$src/$NOTICES" "$fw/$NOTICES"
  cat > "$fw/PrivacyInfo.xcprivacy" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>NSPrivacyTracking</key>
	<false/>
	<key>NSPrivacyTrackingDomains</key>
	<array/>
	<key>NSPrivacyCollectedDataTypes</key>
	<array/>
	<key>NSPrivacyAccessedAPITypes</key>
	<array>
		<dict>
			<key>NSPrivacyAccessedAPIType</key>
			<string>NSPrivacyAccessedAPICategorySystemBootTime</string>
			<key>NSPrivacyAccessedAPITypeReasons</key>
			<array>
				<string>35F9.1</string>
			</array>
		</dict>
		<dict>
			<key>NSPrivacyAccessedAPIType</key>
			<string>NSPrivacyAccessedAPICategoryFileTimestamp</string>
			<key>NSPrivacyAccessedAPITypeReasons</key>
			<array>
				<string>C617.1</string>
			</array>
		</dict>
	</array>
</dict>
</plist>
EOF
done
echo "$want" > "$stamp"
echo "TailscaleKit.xcframework -> $out"
