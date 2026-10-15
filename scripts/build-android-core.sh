#!/bin/bash
# Builds the Android app's Go core (android/core, over tsnet) with gomobile into
# android/app/libs/ovenlight-core.aar, which is gitignored, with the licenses of what it
# links beside it. Cached: a second run with the same inputs does nothing. Gradle runs it
# before every build.
#
#   scripts/build-android-core.sh [--dev] [--force]
#
# --dev builds arm64-v8a only, for the mini's emulators; otherwise it builds the release
# set. --force rebuilds.
set -euo pipefail

# tailscale.com and Go move only together with iOS (scripts/build-tailscalekit.sh):
# android/core/go.mod pins tailscale.com, and this the toolchain.
GO_TOOLCHAIN=go1.27.2
# -trimpath keeps the build machine's file paths out of the library.
GO_FLAGS=-trimpath
# As iOS: no debug LocalAPI handlers, and no saved netmap, so a removed guest isn't
# hidden by an old one.
BUILD_TAGS=ts_omit_debug,ts_omit_cachenetmap
RELEASE_TARGETS=android/arm64,android/arm,android/amd64
DEV_TARGETS=android/arm64
# gomobile links with the NDK's clang. Ask for 16 KB pages whatever its default.
LDFLAGS="-s -w -extldflags=-Wl,-z,max-page-size=16384"
JAVA_PACKAGE=com.snowyghost.ovenlight

root="$(cd "$(dirname "$0")/.." && pwd)"
core="$root/android/core"
libs="$root/android/app/libs"
out="$libs/ovenlight-core.aar"
notices="$libs/THIRD_PARTY_NOTICES.txt"
stamp="$libs/.ovenlight-core-inputs"
work="$root/build/android-core"

targets=$RELEASE_TARGETS
force=
for arg in "$@"; do
  case $arg in
  --dev) targets=$DEV_TARGETS ;;
  --force) force=1 ;;
  *) echo "usage: build-android-core.sh [--dev] [--force]" >&2; exit 2 ;;
  esac
done

prop() { sed -n "s/^$1=//p" "$root/android/gradle.properties"; }
ndk_version="$(prop ovenlight.ndkVersion)"
min_sdk="$(prop ovenlight.minSdk)"
[ -n "$ndk_version" ] && [ -n "$min_sdk" ] || { echo "no NDK or minSdk in android/gradle.properties" >&2; exit 1; }
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
: "${JAVA_HOME:?set JAVA_HOME to JDK 21}"
ndk="$ANDROID_HOME/ndk/$ndk_version"
[ -d "$ndk" ] || { echo "no NDK $ndk_version in $ANDROID_HOME/ndk" >&2; exit 1; }

# Everything that goes into the library: the core's files, the settings above, the NDK
# and the notices script.
inputs() {
  echo "$GO_TOOLCHAIN $GO_FLAGS $BUILD_TAGS $LDFLAGS $JAVA_PACKAGE $ndk_version $min_sdk $1"
  (cd "$core" && find . -type f \( -name '*.go' -o -name go.mod -o -name go.sum \) | LC_ALL=C sort | xargs shasum -a 256)
  shasum -a 256 < "$root/scripts/third-party-notices.sh"
}
want="$(inputs "$targets" | shasum -a 256 | cut -d' ' -f1)"
release="$(inputs "$RELEASE_TARGETS" | shasum -a 256 | cut -d' ' -f1)"
if [ -z "$force" ] && [ -f "$out" ] && [ -f "$notices" ] && [ -f "$stamp" ]; then
  have="$(cat "$stamp")"
  # A release build serves a dev build too.
  if [ "$have" = "$want" ] || [ "$have" = "$release" ]; then
    echo "ovenlight-core.aar is current"
    exit 0
  fi
fi

export GOTOOLCHAIN="$GO_TOOLCHAIN" GOFLAGS="$GO_FLAGS" ANDROID_NDK_HOME="$ndk"
mkdir -p "$work/bin" "$libs"
# gomobile and gobind at the versions core/go.mod's tool lines pin. gomobile finds gobind
# on PATH.
(cd "$core" && go build -o "$work/bin/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)
export PATH="$work/bin:$PATH"

echo "building ovenlight-core.aar for $targets with $GO_TOOLCHAIN and NDK $ndk_version"
rm -f "$work/ovenlight-core.aar" "$work/ovenlight-core-sources.jar"
(cd "$core" && gomobile bind -target "$targets" -androidapi "$min_sdk" -javapkg "$JAVA_PACKAGE" \
  -tags "$BUILD_TAGS" -trimpath -ldflags "$LDFLAGS" -o "$work/ovenlight-core.aar" .)

# Every LOAD segment of every library aligned to at least 16 KB, as Play requires.
readelf="$(find "$ndk/toolchains/llvm/prebuilt" -name llvm-readelf -type f -o -name llvm-readelf -type l | head -1)"
rm -rf "$work/jni" && mkdir -p "$work/jni"
unzip -q -o "$work/ovenlight-core.aar" 'jni/*' -d "$work"
for so in "$work"/jni/*/libgojni.so; do
  abi="$(basename "$(dirname "$so")")"
  aligns="$("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }')"
  [ -n "$aligns" ] || { echo "no LOAD segments in $abi's libgojni.so" >&2; exit 1; }
  for a in $aligns; do
    [ $((a)) -ge 16384 ] || { echo "$abi's libgojni.so has a LOAD segment aligned to $a, under 16 KB" >&2; exit 1; }
  done
  echo "$abi: $(du -h "$so" | cut -f1), LOAD aligned to $(echo "$aligns" | sort -u | tr '\n' ' ')"
done

# The licenses of what each ABI links, for the app's Acknowledgements. The ABIs link the
# same modules, or the build stops.
first=
for t in ${targets//,/ }; do
  arch="${t#android/}"
  GOOS=android GOARCH="$arch" CGO_ENABLED=1 "$root/scripts/third-party-notices.sh" "$core" -tags "$BUILD_TAGS" > "$work/notices-$arch.txt"
  if [ -z "$first" ]; then
    first="$arch"
  else
    cmp -s "$work/notices-$first.txt" "$work/notices-$arch.txt" ||
      { echo "$first and $arch link different modules: merge their notices" >&2; exit 1; }
  fi
done

mv "$work/ovenlight-core.aar" "$out"
cp "$work/notices-$first.txt" "$notices"
echo "$want" > "$stamp"
echo "ovenlight-core.aar -> $out"
