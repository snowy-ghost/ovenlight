#!/bin/bash
# Runs bundletool at the version AGP pins (gradle/verification-metadata.xml), fetched once
# into build/tools/ and checked against the release's digest.
#
#   android/scripts/bundletool.sh <bundletool arguments>
set -euo pipefail

BUNDLETOOL_VERSION=1.18.3
BUNDLETOOL_SHA256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
jar="$(cd "$(dirname "$0")/../.." && pwd)/build/tools/bundletool-all-$BUNDLETOOL_VERSION.jar"
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl -fsSL -o "$jar.part" "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar"
  echo "$BUNDLETOOL_SHA256  $jar.part" | shasum -a 256 -c --quiet
  mv "$jar.part" "$jar"
fi
exec java -jar "$jar" "$@"
