#!/bin/bash
# Checks a release bundle against what Play and the plan require: the SDK level, the
# Go core's three ABIs aligned to 16 KB pages, the license notices, no advertising ID or
# media permissions, the camera and microphone not required, WebView metrics and Safe Browsing
# off, and nothing backed up or transferred.
# ship-play runs it as VERIFY_CMD, against the exact file it then uploads.
#
#   android/scripts/verify-bundle.sh <app-release.aab>
set -euo pipefail

aab="${1:?usage: verify-bundle.sh <app-release.aab>}"
[ -f "$aab" ] || { echo "no bundle at $aab"; exit 1; }
android="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:?set ANDROID_HOME}"
prop() { sed -n "s/^$1=//p" "$android/gradle.properties"; }

bundletool() { "$android/scripts/bundletool.sh" "$@"; }

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
[ "$(attr "/manifest/application/meta-data[@android:name='android.webkit.WebView.EnableSafeBrowsing']/@android:value")" = false ] ||
  fail "Safe Browsing isn't off"
[ "$(attr /manifest/application/@android:allowBackup)" = false ] && [ -n "$(attr /manifest/application/@android:dataExtractionRules)" ] ||
  fail "backups or device transfer could carry Ovenlight's data"

echo "the bundle passes"
