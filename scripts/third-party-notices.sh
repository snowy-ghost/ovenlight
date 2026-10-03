#!/bin/bash
# Prints the licenses of everything a Go build links: the Go standard library, then each
# module that provides a linked package, with its version and license text.
#
#   scripts/third-party-notices.sh <module dir> [go list flags, e.g. -tags ios]
#
# Set GOOS, GOARCH, CGO_ENABLED and GOTOOLCHAIN as for the build: they pick the files,
# and so the packages, that get linked. The main module itself is left out. Fails if a
# module has no license file.
set -euo pipefail

dir="${1:?usage: third-party-notices.sh <module dir> [go list flags]}"
shift
cd "$dir"

# Homebrew's Go keeps the LICENSE one level above GOROOT.
goroot="$(go env GOROOT)"
golicense="$goroot/LICENSE"
[ -f "$golicense" ] || golicense="$goroot/../LICENSE"
[ -f "$golicense" ] || { echo "no LICENSE for the Go toolchain at $goroot" >&2; exit 1; }
# A module's LICENSE, COPYING or NOTICE files, at its root, in any case and extension.
licenses() {
  find "$1" -maxdepth 1 -type f \( -iname 'LICEN[CS]E*' -o -iname 'COPYING*' -o -iname 'NOTICE*' \) | sort
}
section() {
  printf '%s\n' "$1" "$(printf '%*s' "${#1}" '' | tr ' ' '=')"
  shift
  for f in "$@"; do
    printf '\n'
    cat "$f"
  done
  printf '\n\n'
}

# A replaced module is listed as its replacement, which is the code that gets linked.
mods="$(go list -deps "$@" -f '{{if not .Standard}}{{with .Module}}{{if not .Main}}{{with .Replace}}{{.Path}} {{or .Version "local"}} {{.Dir}}{{else}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{end}}{{end}}{{end}}' . | sort -u)"

section "Go standard library $(go env GOVERSION)" "$golicense"
missing=""
while read -r path version moddir; do
  [ -n "$path" ] || continue
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(licenses "$moddir")
  if [ "${#files[@]}" = 0 ]; then
    missing+=" $path@$version"
    continue
  fi
  section "$path $version" "${files[@]}"
done <<< "$mods"

if [ -n "$missing" ]; then
  echo "no license file in:$missing" >&2
  exit 1
fi
