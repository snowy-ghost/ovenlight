#!/bin/sh
# Installs the Ovenlight connector on a Mac or Linux computer. In a terminal:
#
#   curl -fsSL https://ovenlight.app/install.sh | sh
#
# It takes the verified steps in docs/connector.md
# (https://ovenlight.app/docs/connector#check-a-download-first) for you. It fetches the
# index the connector updates from, latest.json, and checks its signature against the
# release keys below when this computer's ssh-keygen can. It downloads this computer's
# archive of the release the index names, checks it against the index, unpacks it into
# ~/ovenlight-connector and runs its install.sh. Then, if ~/.local/bin isn't on your PATH,
# it adds the line for it to your shell's profile.
#
# Everything happens in main, called on the last line, so a download cut short runs nothing.
set -eu

base=https://downloads.ovenlight.app/connector
dir="$HOME/ovenlight-connector"
# Written to a profile as it stands, so $HOME and $PATH stay literal.
# shellcheck disable=SC2016
path_line='export PATH="$HOME/.local/bin:$PATH"'

# The keys that sign releases, as docs/allowed_signers lists them (a test checks).
signers='team@snowyghost.com namespaces="file,ovenlight-latest" sk-ecdsa-sha2-nistp256@openssh.com AAAAInNrLWVjZHNhLXNoYTItbmlzdHAyNTZAb3BlbnNzaC5jb20AAAAIbmlzdHAyNTYAAABBBNGElEfuj/FR/VGf3W0dW/8AZBqOHB+dY1qIa91ydBA/yMgdbWvBZ6GE1FTXrbGRzyp9AQygHn2lkSDzQbfAleEAAAAEc3NoOg==
team@snowyghost.com namespaces="file,ovenlight-latest" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEDOy2WEnSb8Zrd39QCghqqIg37IYk9ayCjxB15ukqD4'

fail() {
  echo "install.sh: $*" >&2
  exit 1
}

main() {
  # The connector runs as you, so sudo would install it for root.
  if [ "$(id -u)" = 0 ] && [ -n "${SUDO_USER:-}" ]; then
    fail "run it without sudo: the connector runs as your own user"
  fi
  case "$(uname -s)" in
  Darwin) system=macos ;; # one archive for Apple silicon and Intel
  Linux)
    case "$(uname -m)" in
    x86_64 | amd64) system=linux-amd64 ;;
    aarch64 | arm64) system=linux-arm64 ;;
    *) fail "there's no release for $(uname -m): build from source, https://ovenlight.app/docs/connector#from-source" ;;
    esac
    ;;
  *) fail "this is for macOS and Linux. On Windows: https://ovenlight.app/docs/connector#install" ;;
  esac
  archive="ovenlight-connector-$system.tar.gz"
  command -v curl >/dev/null || fail "it needs curl"
  if command -v sha256sum >/dev/null; then
    sha256=sha256sum
  elif command -v shasum >/dev/null; then
    sha256='shasum -a 256'
  else
    fail "it needs sha256sum or shasum to check the download"
  fi

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsS --proto =https -o "$tmp/latest.json" "$base/latest.json" \
    -o "$tmp/latest.json.sig" "$base/latest.json.sig" || fail "couldn't download the release index"

  printf '%s\n' "$signers" >"$tmp/allowed_signers"
  cut -d ' ' -f 3- "$tmp/allowed_signers" >"$tmp/keys.pub"
  # An ssh-keygen without security keys, from before OpenSSH 8.2 (RHEL 8's) or Apple's on
  # macOS 13, can't read the key that signs with Touch ID, and leaves it out here.
  if command -v ssh-keygen >/dev/null &&
    [ "$(ssh-keygen -l -f "$tmp/keys.pub" 2>/dev/null | wc -l)" -eq "$(wc -l <"$tmp/keys.pub")" ]; then
    ssh-keygen -Y verify -f "$tmp/allowed_signers" -I team@snowyghost.com -n ovenlight-latest \
      -s "$tmp/latest.json.sig" <"$tmp/latest.json" ||
      fail "the release index's signature doesn't check out, so nothing was installed"
  else
    echo "The release index's signature couldn't be checked: no ssh-keygen here can read the release key that signs with Touch ID (that takes OpenSSH 8.2 or later, and macOS 14 or later on a Mac). Only that the download matches the index is checked."
  fi

  version="$(sed -n 's/^{"latest": "\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)".*/\1/p' "$tmp/latest.json")"
  [ -n "$version" ] || fail "the release index names no release"
  echo "downloading the Ovenlight connector $version for $system"
  curl -fL --proto =https --progress-bar -o "$tmp/$archive" "$base/$version/$archive" ||
    fail "couldn't download $archive"
  sum="$($sha256 "$tmp/$archive" | cut -d ' ' -f 1)"
  grep -qF "\"$archive\": \"$sum\"" "$tmp/latest.json" ||
    fail "$archive doesn't match the release index, so nothing was installed"
  echo "checked $archive against the release index"

  mkdir -p "$dir"
  tar xzf "$tmp/$archive" -C "$dir" --strip-components 1
  echo "unpacked into $dir"
  case ":$PATH:" in
  *":$HOME/.local/bin:"*) "$dir/install.sh" ;;
  *)
    # With ~/.local/bin on its PATH, install.sh leaves the profile line to this script.
    PATH="$HOME/.local/bin:$PATH" "$dir/install.sh"
    add_path
    ;;
  esac
}

# Adds the PATH line to your login shell's profile, once. Printed for the reader, so ~
# stays literal.
# shellcheck disable=SC2088
add_path() {
  shell="${SHELL:-}"
  case "${shell##*/}" in
  zsh) profile="${ZDOTDIR:-$HOME}/.zshrc" ;;
  bash) if [ "$system" = macos ]; then profile="$HOME/.bash_profile"; else profile="$HOME/.bashrc"; fi ;;
  *) profile= ;;
  esac
  if [ -n "$profile" ] && grep -qxF "$path_line" "$profile" 2>/dev/null; then
    echo "$profile puts ~/.local/bin on your PATH: open a new terminal window to run ovenlight"
  elif [ -n "$profile" ] && printf '\n%s\n' "$path_line" 2>/dev/null >>"$profile"; then
    echo "added ~/.local/bin to your PATH in $profile: open a new terminal window to run ovenlight"
  else
    echo "~/.local/bin isn't on your PATH. Add this line to your shell's profile, then open a new terminal window to run ovenlight:"
    echo "  $path_line"
  fi
}

main
