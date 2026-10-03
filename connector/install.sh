#!/bin/bash
# Installs the Ovenlight connector and runs it in the background: a launchd agent
# (com.snowyghost.ovenlight.connector) on macOS, a systemd user service (ovenlight) on
# Linux. From a release, it installs the `ovenlight` binary next to this script; in the
# source tree, it builds one.
# Safe to run again: it rebuilds, reinstalls and restarts. Node state and the app list
# are kept.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
link="$HOME/.local/bin/ovenlight"
os="$(uname -s)"
# Printed for the reader, so ~ stays literal.
# shellcheck disable=SC2088
case "$os" in
Darwin)
  label=com.snowyghost.ovenlight.connector
  support="$HOME/Library/Application Support/ovenlight"
  plist="$HOME/Library/LaunchAgents/$label.plist"
  log="$HOME/Library/Logs/ovenlight.log"
  # launchd's standard output and error: what bypasses the log, such as a panic.
  stderr_log="$HOME/Library/Logs/ovenlight.stderr.log"
  domain="gui/$(id -u)"
  profile="~/.zshrc"
  ;;
Linux)
  label=ovenlight
  # The connector's own defaults. The service is given them explicitly, so it and your
  # shell agree even when the service manager's environment differs from your shell's.
  support="${XDG_STATE_HOME:-$HOME/.local/state}/ovenlight"
  config="${XDG_CONFIG_HOME:-$HOME/.config}/ovenlight/config.json"
  log="$support/ovenlight.log"
  profile="~/.profile or ~/.bashrc"
  if ! manager_env="$(systemctl --user show-environment 2>/dev/null)"; then
    cat >&2 <<'EOF'
install.sh runs the connector as a systemd user service, and can't reach your user's
systemd. Run it in a login of your own (a desktop or SSH login, not sudo or su), on a
Linux with systemd. On WSL, turn systemd on first: add [boot] systemd=true to
/etc/wsl.conf, then restart the distribution (wsl --terminate <name>).
EOF
    exit 1
  fi
  # Where your user's systemd looks for units, by its own environment, not this shell's.
  unit_config="$(sed -n 's/^XDG_CONFIG_HOME=//p' <<<"$manager_env")"
  unit="${unit_config:-$HOME/.config}/systemd/user/$label.service"
  ;;
*)
  echo "install.sh supports macOS and Linux; on Windows, use install.ps1" >&2
  exit 1
  ;;
esac
bin="$support/bin/ovenlight"

# A release ships the binary next to this script; the source tree has go.mod instead.
prebuilt="$here/ovenlight"
# go itself enforces go.mod's version, fetching a newer toolchain when it needs one.
if [ ! -f "$prebuilt" ] && ! command -v go >/dev/null; then
  echo "install.sh needs Go to build the connector: brew install go, your package manager's golang, or https://go.dev/dl/" >&2
  exit 1
fi

mkdir -p "$support/bin" "$(dirname "$log")"
chmod 700 "$support"
# The logs name guests and devices; only you read them.
(umask 077 && touch "$log")
chmod 600 "$log"

if [ -f "$prebuilt" ]; then
  echo "installing $bin"
  cp "$prebuilt" "$bin.new"
  chmod 755 "$bin.new"
else
  echo "building $bin"
  (cd "$here" && go build -trimpath -o "$bin.new" .)
fi
mv "$bin.new" "$bin"

tmp="$(mktemp)"
if [ "$os" = Linux ]; then
  mkdir -p "$(dirname "$unit")"
  sed -e "s|__BIN__|$bin|" -e "s|__LOG__|$log|" -e "s|__CONFIG__|$config|" -e "s|__STATE__|$support|" "$here/systemd/$label.service" > "$tmp"
  mv "$tmp" "$unit"
  systemctl --user daemon-reload
  systemctl --user enable --quiet "$label"
  # Starts a stopped service, and puts a running one on the new binary.
  systemctl --user restart "$label"
  echo "installed the $label user service (log: $log; what bypasses it, such as a panic: journalctl --user -u $label)"
  # Linger starts your user's services at boot and keeps them running with nobody logged in.
  user="$(id -un)"
  if [ "$(loginctl show-user "$user" -p Linger --value 2>/dev/null)" != yes ] && ! loginctl enable-linger "$user" 2>/dev/null; then
    echo "It runs only while you're logged in. To keep it serving with nobody logged in, and after a restart, run: sudo loginctl enable-linger $user"
  fi
  if [ -n "${WSL_DISTRO_NAME:-}" ]; then
    echo "This is WSL, which Windows starts and stops, so the connector serves only while $WSL_DISTRO_NAME runs. To serve from startup with nobody signed in, install the Windows connector (install.ps1) instead."
  fi
else
  (umask 077 && touch "$stderr_log")
  chmod 600 "$stderr_log"
  mkdir -p "$(dirname "$plist")"
  sed -e "s|__BIN__|$bin|" -e "s|__LOG__|$log|" -e "s|__STDERR_LOG__|$stderr_log|" "$here/launchd/$label.plist" > "$tmp"
  plutil -lint "$tmp" >/dev/null

  if launchctl print "$domain/$label" >/dev/null 2>&1 && cmp -s "$tmp" "$plist"; then
    # Same agent definition: restart it on the new binary.
    rm "$tmp"
    launchctl kickstart -k "$domain/$label"
  else
    mv "$tmp" "$plist"
    launchctl bootout "$domain/$label" 2>/dev/null || true
    # bootstrap can fail for a moment right after bootout while launchd tears down.
    for attempt in 1 2 3 4 5; do
      launchctl bootstrap "$domain" "$plist" && break
      if [ "$attempt" = 5 ]; then
        cat >&2 <<'EOF'
launchctl bootstrap failed. The connector is a launchd agent of your login session, so it
runs only while you're logged in to this Mac's desktop: log in there (not only over SSH)
and run install.sh again. A Mac without a display needs automatic login (System Settings >
Users & Groups).
EOF
        exit 1
      fi
      sleep 1
    done
  fi
  echo "installed $label (log: $log)"
fi

# Put `ovenlight` on PATH with a link in ~/.local/bin, never over a file this didn't make.
cmd="$(printf %q "$bin")"
case ":$PATH:" in
*":$HOME/.local/bin:"*)
  if { [ ! -e "$link" ] && [ ! -L "$link" ]; } || [ "$(readlink "$link")" = "$bin" ]; then
    mkdir -p "$(dirname "$link")"
    ln -sfn "$bin" "$link"
    cmd=ovenlight
    echo "linked $link"
  else
    echo "left $link alone (it isn't this install's link); run this one as $cmd"
  fi
  ;;
*)
  # Printed for the reader to copy, so ~ and $HOME stay literal.
  # shellcheck disable=SC2088
  echo "~/.local/bin isn't on your PATH, so neither is ovenlight: run it as $cmd, or add this line to your shell profile ($profile) and run install.sh again:"
  # shellcheck disable=SC2016
  echo '  export PATH="$HOME/.local/bin:$PATH"'
  ;;
esac
echo "next:"
echo "  $cmd publish --port <n> --name \"<App Name>\""
echo "  $cmd status"
