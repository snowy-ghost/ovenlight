#!/bin/bash
# Checks a release bundle before it goes to Play (play.md §2 and §4): the SDK level, the
# Go core's three ABIs aligned to 16 KB pages, the license notices, no advertising ID or
# media permissions, the camera and microphone not required, and WebView metrics off.
# ship-play runs it as VERIFY_CMD, against the exact file it then uploads.
#
#   android/scripts/verify-bundle.sh <app-release.aab>
set -euo pipefail

aab="${1:?usage: verify-bundle.sh <app-release.aab>}"
[ -f "$aab" ] || { echo "no bundle at $aab"; exit 1; }
android="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:?set ANDROID_HOME}"
prop() { sed -n "s/^$1=//p" "$android/gradle.properties"; }

# bundletool at the version AGP pins (gradle/verification-metadata.xml), checked against
# the release's digest.
BUNDLETOOL_VERSION=1.18.3
BUNDLETOOL_SHA256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
jar="$android/../build/tools/bundletool-all-$BUNDLETOOL_VERSION.jar"
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl -fsSL -o "$jar.part" "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar"
  echo "$BUNDLETOOL_SHA256  $jar.part" | shasum -a 256 -c --quiet
  mv "$jar.part" "$jar"
fi
bundletool() { java -jar "$jar" "$@"; }

work="$(mktemp -d)"
trap 'rm -r "$work"' EXIT
fail() { echo "FAILED: $*"; exit 1; }

manifest="$work/manifest.xml"
bundletool dump manifest --bundle="$aab" > "$manifest"
attr() { bundletool dump manifest --bundle="$aab" --xpath "$1"; }

target="$(attr /manifest/uses-sdk/@android:targetSdkVersion)"
[ "$target" = "$(prop ovenlight.targetSdk)" ] || fail "targetSdk is $target, not $(prop ovenlight.targetSdk)"
echo "targetSdk $target, versionCode $(attr /manifest/@android:versionCode), versionName $(attr /manifest/@android:versionName)"

abis="$(unzip -Z1 "$aab" 'base/lib/*' | cut -d/ -f3 | sort -u | tr '\n' ' ')"
[ "$abis" = "arm64-v8a armeabi-v7a x86_64 " ] || fail "the native libraries are for: $abis"

bundletool dump config --bundle="$aab" | grep -q PAGE_ALIGNMENT_16K || fail "the bundle doesn't ask Play for 16 KB alignment"
readelf="$(ls "$sdk/ndk/$(prop ovenlight.ndkVersion)"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)"
unzip -q "$aab" 'base/lib/*' -d "$work/libs"
for so in "$work"/libs/base/lib/*/*.so; do
  aligns="$("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }')"
  [ -n "$aligns" ] || fail "no LOAD segments in ${so#"$work"/libs/}"
  for align in $aligns; do
    [ $((align)) -ge 16384 ] || fail "${so#"$work"/libs/} has a LOAD segment aligned to $align"
  done
done
# The APKs Play makes from the bundle, as bundletool makes them (signed with the debug key).
bundletool build-apks --bundle="$aab" --output="$work/app.apks" > /dev/null
unzip -q "$work/app.apks" -d "$work/apks"
for apk in "$work"/apks/*/*.apk; do
  "$sdk/build-tools/$(prop ovenlight.buildTools)/zipalign" -c -P 16 4 "$apk" || fail "${apk#"$work"/apks/} isn't aligned for 16 KB pages"
done

for notice in android-libraries.txt go-core.txt; do
  [ -n "$(unzip -p "$aab" "base/assets/licenses/$notice")" ] || fail "no $notice in the bundle's notices"
done

if grep -E 'android:name="(com\.google\.android\.gms\.permission\.AD_ID|android\.permission\.READ_MEDIA_[A-Z_]*)"' "$manifest"; then
  fail "the manifest asks for the permissions above"
fi
for feature in android.hardware.camera android.hardware.camera.autofocus android.hardware.microphone; do
  [ "$(attr "/manifest/uses-feature[@android:name='$feature']/@android:required")" = false ] || fail "$feature isn't declared optional"
done
[ "$(attr "/manifest/application/meta-data[@android:name='android.webkit.WebView.MetricsOptOut']/@android:value")" = true ] ||
  fail "WebView metrics aren't opted out"

echo "the bundle passes"
