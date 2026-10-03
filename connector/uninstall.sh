#!/bin/bash
# Stops and removes the ovenlight launchd agent (macOS) or systemd user service (Linux),
# its binary and its ~/.local/bin link.
# Node state, the app list and the logs stay, so a reinstall needs no new logins; --purge
# deletes them too, with the stored API credential, the guest records, the feedback
# inbox and the policy backups (the nodes then stay in your tailnet, offline, until you remove them in the admin
# console).
set -euo pipefail

case "$(uname -s)" in
Darwin)
  label=com.snowyghost.ovenlight.connector
  support="$HOME/Library/Application Support/ovenlight"
  data=("$support" "$HOME/.config/ovenlight" "$HOME/Library/Logs/ovenlight.log" "$HOME/Library/Logs/ovenlight.log.1"
    "$HOME/Library/Logs/ovenlight.stderr.log" "$HOME/Library/Logs/ovenlight")
  launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
  rm -f "$HOME/Library/LaunchAgents/$label.plist"
  ;;
Linux)
  label=ovenlight
  support="${XDG_STATE_HOME:-$HOME/.local/state}/ovenlight"
  data=("$support" "${XDG_CONFIG_HOME:-$HOME/.config}/ovenlight") # the logs are in $support
  if ! manager_env="$(systemctl --user show-environment 2>/dev/null)"; then
    echo "uninstall.sh can't reach your user's systemd to stop the connector: run it in a login of your own (a desktop or SSH login, not sudo or su)" >&2
    exit 1
  fi
  systemctl --user disable --now --quiet "$label" 2>/dev/null || true
  # Where install.sh put the unit: where your user's systemd looks, by its own environment.
  unit_config="$(sed -n 's/^XDG_CONFIG_HOME=//p' <<<"$manager_env")"
  rm -f "${unit_config:-$HOME/.config}/systemd/user/$label.service"
  systemctl --user daemon-reload
  ;;
*)
  echo "uninstall.sh supports macOS and Linux; on Windows, use uninstall.ps1" >&2
  exit 1
  ;;
esac

# The link install.sh made, only if it still points at our binary.
link="$HOME/.local/bin/ovenlight"
[ "$(readlink "$link" 2>/dev/null)" = "$support/bin/ovenlight" ] && rm "$link"
rm -f "$support/bin/ovenlight" "$support/ovenlight.sock" "$support/ovenlight.lock"
echo "removed $label"

if [ "${1:-}" = "--purge" ]; then
  # The logs name guests, devices and your login.
  rm -rf "${data[@]}"
  echo "deleted node state, config, the stored API credential, guest records, the feedback inbox, policy backups and logs"
fi
