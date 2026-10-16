#!/bin/bash
# Fails when an iPhone test has no Android twin of the same name, outside the list below.
# The Swift tests are the spec, so each is ported under its own name (testReadsTheConnectorsEscapes
# in Kotlin as in Swift), and a test added on one side shows up here.
#
#   android/scripts/test-parity.sh
#
# The tests are read from the iPhone release the port follows, not this branch's own copy:
# OVENLIGHT_SWIFT_SPEC names it, origin/app-1.0.2 until 1.0.2 is on master.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
spec="${OVENLIGHT_SWIFT_SPEC:-origin/app-1.0.2}"

# Swift tests with no Kotlin twin, each with its reason. Pending ones land with their feature,
# and none is pending after Phase 6.
excluded() {
  sed 's/#.*//' <<'EOF' | awk 'NF { print $1 }'
testTheShareSheetClosesOnce              # iOS presentation only: Android has its own Save, Share and Open sheet
testOnlyAShownSheetTakesItsFiles         # iOS presentation only, as above
testScreenshotsAreDecodedSmall           # pending, Phase 6: Android's Bitmap, with the feedback inbox
testAppReplacedByAppStaysOnScreen        # SwiftUI only: a replaced cover's onDismiss
testSheetsReplaceEachOther               # SwiftUI only: a replaced sheet's onDismiss
testOnlyTheAppsOwnPagesPopUpAPost        # Android opens no popups: setSupportMultipleWindows(false)
EOF
}

swift="$(git -C "$root" grep -h -o -E 'func test[A-Za-z0-9_]+' "$spec" -- 'OvenlightTests/*.swift' | sed 's/^func //' | sort -u)"
[ -n "$swift" ] || { echo "no iPhone tests at $spec" >&2; exit 1; }
kotlin="$(find "$root/android/app/src" \( -path '*/src/test/*' -o -path '*/src/androidTest/*' \) -name '*.kt' -print0 |
  xargs -0 grep -h -o -E 'fun test[A-Za-z0-9_]+' 2>/dev/null | sed 's/^fun //' | sort -u || true)"

missing="$(comm -23 <(printf '%s\n' "$swift") <(printf '%s\n' "$kotlin") | comm -23 - <(excluded | sort -u))"
stale="$(comm -12 <(excluded | sort -u) <(printf '%s\n' "$kotlin"))"

status=0
if [ -n "$missing" ]; then
  echo "iPhone tests with no Android twin:"
  printf '  %s\n' $missing
  status=1
fi
if [ -n "$stale" ]; then
  echo "excluded, but ported now (take them off the list):"
  printf '  %s\n' $stale
  status=1
fi
[ "$status" = 0 ] && echo "every iPhone test at $spec has an Android twin ($(printf '%s\n' "$swift" | wc -l | tr -d ' ') tests, $(excluded | wc -l | tr -d ' ') excluded)"
exit "$status"
