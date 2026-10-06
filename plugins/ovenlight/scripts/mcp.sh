#!/bin/sh
# Starts the Ovenlight connector's MCP server from where install.sh puts the connector.
case "$(uname -s)" in
Darwin) bin="$HOME/Library/Application Support/ovenlight/bin/ovenlight" ;;
Linux) bin="${XDG_STATE_HOME:-$HOME/.local/state}/ovenlight/bin/ovenlight" ;;
*)
  echo "Ovenlight's Claude Code plugin runs on macOS and Linux for now. Add the connector with claude mcp add, as https://github.com/snowy-ghost/ovenlight#with-a-coding-agent shows." >&2
  exit 1
  ;;
esac
if [ ! -x "$bin" ]; then
  echo "The Ovenlight connector isn't installed: there's no $bin. Install it with the steps at https://ovenlight.app/support#own-computer, then reconnect the ovenlight server in /mcp." >&2
  exit 1
fi
exec "$bin" mcp "$@"
