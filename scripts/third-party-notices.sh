#!/bin/bash
# Prints the licenses of everything a Go build links: the Go standard library, then each
# module that provides a linked package, with its version and license text. A license
# file below a module's root, in a linked package's folder or one above it, follows the
# root's under its path in the module.
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
# The LICENSE, COPYING or NOTICE files in a folder, in any case and extension, but not
# source files such as tailscale.com's license_test.go.
licenses() {
  find "$1" -maxdepth 1 -type f \( -iname 'LICEN[CS]E*' -o -iname 'COPYING*' -o -iname 'NOTICE*' \) \
    ! -iname '*.go' ! -iname '*.[chs]' | sort
}
# section <title> <root> <file>...: a file below the root is printed under its path from it.
section() {
  printf '%s\n' "$1" "$(printf '%*s' "${#1}" '' | tr ' ' '=')"
  local root="$2"
  shift 2
  for f in "$@"; do
    printf '\n'
    [ "${f%/*}" = "$root" ] || printf '%s\n\n' "${f#"$root"/}"
    cat "$f"
  done
  printf '\n\n'
}

# Each linked package's module, a tab, and the package's folder. A replaced module is
# listed as its replacement, which is the code that gets linked.
pkgs="$(go list -deps "$@" -f '{{if not .Standard}}{{with .Module}}{{if not .Main}}{{with .Replace}}{{.Path}} {{or .Version "local"}} {{.Dir}}{{else}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{"\t"}}{{$.Dir}}{{end}}{{end}}{{end}}' .)"
mods="$(cut -f1 <<< "$pkgs" | sort -u)"

section "Go standard library $(go env GOVERSION)" "${golicense%/*}" "$golicense"
missing=""
while read -r path version moddir; do
  [ -n "$path" ] || continue
  # The license files at the module's root, which it must have, then those in each folder
  # from a linked package's up to the root.
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(licenses "$moddir")
  if [ "${#files[@]}" = 0 ]; then
    missing+=" $path@$version"
    continue
  fi
  while IFS= read -r f; do files+=("$f"); done < <(
    while IFS=$'\t' read -r mod d; do
      [ "$mod" = "$path $version $moddir" ] || continue
      while [[ "$d" == "$moddir"/* ]]; do echo "$d"; d="${d%/*}"; done
    done <<< "$pkgs" | sort -u | while IFS= read -r d; do licenses "$d"; done
  )
  section "$path $version" "$moddir" "${files[@]}"
done <<< "$mods"

if [ -n "$missing" ]; then
  echo "no license file in:$missing" >&2
  exit 1
fi
