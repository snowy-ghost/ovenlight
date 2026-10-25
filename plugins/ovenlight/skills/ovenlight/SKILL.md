---
name: ovenlight
description: Put a web app on someone's phone with Ovenlight, from their own computer, and share it with family or friends. Use when the person wants an app they made (or are about to make) on their phone, wants to share an app with their family, or is building or changing an app published with Ovenlight (the ovenlight connector, an app at a ts.net address, Ovenlight-User-Id headers, feedback from the Ovenlight app).
---

# Ovenlight

Ovenlight opens web apps that run on the person's computer, full screen on their phone.
The `ovenlight` connector on that computer publishes each app and tells it who is calling.

1. **Read the guide before building or changing anything.** Call the `guide` tool of the
   `ovenlight` MCP server, or run `ovenlight guide`. It matches the installed connector.
   Follow it over anything here.
2. **If the person sends apps to an always-on computer** (their notes or instructions name
   one, such as `oven`, reached with `ssh`), build on this computer and publish there, not
   here: read the guide with `ssh <user>@oven '~/.local/bin/ovenlight guide'` (quoted, so
   `~` is the server's), start a new app there with `ovenlight new "<App Name>" --dir ~/apps` and copy it here, and
   send each app as https://ovenlight.app/docs/always-on.md under "Send an app" describes,
   never copying over the server's data. Run the connector's commands there over `ssh`. If
   an ssh command waits with no output, Tailscale wants the person to confirm: ask them to
   run `ssh <user>@oven true` in their own terminal, then try again.
3. **If the connector isn't installed** (the MCP tools don't answer, and there's no
   `~/Library/Application Support/ovenlight/bin/ovenlight` on a Mac,
   `%LOCALAPPDATA%\ovenlight\bin\ovenlight.exe` on Windows, or
   `~/.local/state/ovenlight/bin/ovenlight` on Linux, with `$XDG_STATE_HOME` in place of
   `~/.local/state` when that's set), tell the person what Ovenlight needs, in plain
   words: a computer that stays on and awake (a laptop with its lid open), a Tailscale
   account, the Ovenlight app on their phone, and the connector, a free download.
   https://ovenlight.app/support#own-computer has the steps. Then wait for them; don't
   look for workarounds. If it is installed but not running, the person starts it with
   the command `ovenlight status` gives. Don't run `ovenlight run` yourself.
4. **Use the tools for the loop**: publish, restart the app after changing the server,
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
5. **You can't share an app.** When the person wants to share, tell them the steps from
   the guide and let them do it.
6. **The connector updates itself.** When `status` shows a failed update, tell the
   person what failed, in plain words. With updates on, it tries again the next night, and
   a release waiting its turn needs nothing from either of you. When `doctor`'s `update`
   check gives a fix, pass it on. For a release to install by hand, offer the verified
   steps, never a bare download: those at
   https://github.com/snowy-ghost/ovenlight/blob/master/docs/connector.md#check-a-download-first
   for the latest, including one an older connector's `doctor` names with a download
   link, and those at
   https://github.com/snowy-ghost/ovenlight/blob/master/docs/connector.md#install-a-release-by-hand
   for an older one. With the steps for the latest, pass on the serial `status` shows
   (`update.serial`): the index they fetch must have one at least as high.
