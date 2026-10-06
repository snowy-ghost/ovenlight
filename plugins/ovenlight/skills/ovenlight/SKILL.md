---
name: ovenlight
description: Put a web app on someone's iPhone with Ovenlight, from their own computer, and share it with family or friends. Use when the person wants an app they made (or are about to make) on their phone, wants to share an app with their family, or is building or changing an app published with Ovenlight (the ovenlight connector, an app at a ts.net address, Ovenlight-User-Id headers, feedback from the Ovenlight iPhone app).
---

# Ovenlight

Ovenlight opens web apps that run on the person's computer, full screen on their iPhone.
The `ovenlight` connector on that computer publishes each app and tells it who is calling.

1. **Read the guide before building or changing anything.** Call the `guide` tool of the
   `ovenlight` MCP server, or run `ovenlight guide`. It matches the installed connector.
   Follow it over anything here.
2. **If the connector isn't installed** (the MCP tools don't answer, and there's no
   `~/Library/Application Support/ovenlight/bin/ovenlight` on a Mac or
   `~/.local/state/ovenlight/bin/ovenlight` on Linux, under `$XDG_STATE_HOME` in place of
   `~/.local/state` when that's set), tell the person what Ovenlight needs, in plain
   words: a Mac or Linux computer that stays on, a Tailscale account, the Ovenlight
   iPhone app, and the connector, a free download.
   https://ovenlight.app/support#own-computer has the steps. Then wait for them; don't
   look for workarounds. If it is installed but not running, the person
   starts it with the command the guide gives; don't run `ovenlight run` yourself.
3. **Use the tools for the loop**: publish, restart the app after changing the server,
   check it, read its logs, and read the feedback the person sends from their phone. The
   command that keeps the app running is set only in a terminal: with a shell, publish
   with `ovenlight publish --port <n> --name "<Name>" --run '<command>' --dir <folder>`
   (for an app already published, `ovenlight publish --slug <slug> --run '<command>'`),
   where the person approves it, after stopping any copy you started by its process ID
   (never `pkill` by name); without one, publish with the `publish` tool and ask the
   person to stop their own copy and run the `--slug` command from the app's folder. A
   new app starts from `ovenlight new "<App Name>" --dir ~/src`, also in a shell. If you
   can't edit files (only the MCP tools work), tell the person to build the app with a
   coding agent that can, Claude Code for example, and publish it from there.
4. **You can't share an app.** When the person wants to share, tell them the steps from
   the guide and let them do it.
