# Ovenlight plugin

Your web apps run on your own computer, and Ovenlight opens them on your phone, iPhone or
Android. You can share one with a friend without sharing the computer it runs on. This
plugin lets Claude Code build apps that feel at home in Ovenlight, publish them from your
computer, check how they'll work on a phone, and read the notes and screenshots people send
from their phones.

## What you need

- The Ovenlight connector, on the computer where you run Claude Code: a Mac with macOS 13
  or later, a Linux computer with systemd, or a Windows 11 PC. That computer stays on and
  awake while anyone uses your apps, and a Mac stays logged in.
  [ovenlight.app/support](https://ovenlight.app/support#own-computer) has every step of
  the setup. The connector can instead go on an always-on Linux computer of yours that
  Claude Code reaches over `ssh`, as
  [always-on.md](https://github.com/snowy-ghost/ovenlight/blob/master/docs/always-on.md)
  describes.
- A Tailscale account with MagicDNS and HTTPS certificates turned on. Its free Personal
  plan is for non-commercial use.
- Ovenlight from the [App Store](https://apps.apple.com/app/id6817561066) or
  [Google Play](https://play.google.com/store/apps/details?id=com.snowyghost.ovenlight), on
  an iPhone with iOS 18.1 or later or a phone with Android 10 or later.

The plugin works in Claude Code. In claude.ai chat, the skill loads but the MCP server
doesn't. Another agent that supports local MCP servers can use the connector's MCP server
directly, as the [README](https://github.com/snowy-ghost/ovenlight#with-a-coding-agent)
shows.

## Install

If Claude Code on your laptop will send your apps to an always-on Linux computer over
`ssh`, set that computer up as
[always-on.md](https://github.com/snowy-ghost/ovenlight/blob/master/docs/always-on.md#choose-your-setup)
shows, connector included, and skip to the plugin below.

Otherwise, in the Tailscale admin console, under
[DNS](https://console.tailscale.com/admin/dns), turn on MagicDNS if it's off, then turn on
HTTPS certificates. Then install the connector on the computer where you run Claude Code.
On a Mac or Linux, run this in a terminal:

```sh
curl -fsSL https://ovenlight.app/install.sh | sh
```

On Windows, sign in as the account that will own the apps, which must be an administrator.
Open PowerShell with Run as administrator, then run:

```powershell
irm https://ovenlight.app/install.ps1 | iex
```

On Linux, if it prints a `sudo loginctl enable-linger` command, run that too, so the
connector keeps serving with nobody logged in and after a restart.

For the plugin, start Claude Code in a new terminal window. If you found the plugin in
Claude Code's plugin directory, install it from there. Otherwise, run:

```
/plugin marketplace add snowy-ghost/ovenlight
```

then:

```
/plugin install ovenlight@snowy-ghost
```

If you install the connector after the plugin, reconnect the `ovenlight` server in `/mcp`.

When you send apps to an always-on computer, finish by pasting the note under
[Let your agent send your apps](https://github.com/snowy-ghost/ovenlight/blob/master/docs/always-on.md#let-your-agent-send-your-apps)
into Claude Code on your laptop. Only that note tells the skill where your server is and
how to send your apps there. The plugin's `ovenlight` MCP server won't connect on your laptop,
since the connector isn't there. Leave it: the note has Claude Code run the connector on
your server over `ssh`.

## Try it

- "Make a recipe box for my family and put it on my phone."
- "Check whether Recipe Box is ready for my phone, and fix what isn't."
- "Read the feedback on Recipe Box and fix what it reports."
- "Recipe Box won't open on my phone. Find out why."

New apps start from Ovenlight's starter app, which needs Node 22.13 or later. When Claude
Code publishes a new app, open the login link it gives you and sign in with your Tailscale
account. Once you've stored a Tailscale API access token for sharing, every app after the
first signs in without a login link. The first time, also open Ovenlight on your phone,
choose Use My Own Computers, tap Connect and sign in with the same account. Your apps
appear on their own, and if one doesn't, `ovenlight doctor` says why.

## What it adds

- A skill that has Claude Code read Ovenlight's building guide before it builds or changes
  an app, then publish, check and fix the app with the tools below. It leaves signing in
  and sharing to you.
- The connector's MCP server, with these tools:
  - `guide`: the guide to building an app that works and feels at home in Ovenlight.
  - `status`: your published apps, their addresses, whether each one answers, counts of
    your guests, invites and feedback, and the connector's updates.
  - `doctor`: checks the whole setup and gives a fix for anything wrong.
  - `check_app`: how one app will look and work on the phone, with fixes.
  - `publish`: makes an app its own device in your tailnet, with an HTTPS address, or
    updates one. Only you can open it until you share it.
  - `unpublish`: stops serving an app.
  - `logs` and `restart_app`: the output of an app the connector runs, and a restart
    after a change.
  - `feedback_list`: the notes and screenshots people send with Send Feedback.

The MCP server can't set the command an app runs, share an app or let anyone in.

## What it runs and connects to

- The plugin's MCP server entry runs `scripts/mcp`, or `scripts/mcp.cmd` on Windows. It
  starts `ovenlight mcp` from where the connector's install script put it, or stops with a
  message saying how to install the connector. The plugin downloads nothing.
- The skill has Claude Code run the connector's own commands in its shell, where you
  approve each one: `ovenlight new` to start an app, and `ovenlight publish` with `--run`
  to set the command that keeps an app running.
- When you send apps to an always-on computer of yours, the skill has Claude Code reach it
  over `ssh`, run the connector's commands there and copy each app to it, as
  [always-on.md](https://github.com/snowy-ghost/ovenlight/blob/master/docs/always-on.md)
  describes.
- `publish` has the connector bring the app's node into your tailnet through Tailscale's
  coordination server. With a stored Tailscale API credential, the connector also signs
  the node in and turns off its key expiry through Tailscale's API. The node sends
  Tailscale's usual diagnostic logs to Tailscale, as Tailscale's own app does, and may
  ask your router for a port mapping (NAT-PMP or UPnP) for direct connections.
- Your phone reaches each app's node over an encrypted connection: directly when
  possible, otherwise through Tailscale's relays, which pass the traffic along but can't
  read it.
- Each app's node gets its HTTPS certificate from Let's Encrypt, which lists the app's
  address in public Certificate Transparency logs. The address holds the app's name and
  your tailnet's name, so pick app names you don't mind people seeing.
- `doctor` checks each node's certificate, and a node without one gets it then. Once
  you've set up sharing, it also checks your stored Tailscale API credential with
  Tailscale's API.
- Checks of your apps go to them at `127.0.0.1`. Feedback, logs and the app list come from
  files on your computer.
- The connector, which you install yourself, checks
  `https://downloads.ovenlight.app/connector/latest.json` once a day and installs a signed
  update on its own, unless you run `ovenlight update off`. The plugin adds nothing to that.

No server of ours carries your apps or their data. The
[privacy policy](https://ovenlight.app/privacy) has the details.

## Help

See [ovenlight.app/support](https://ovenlight.app/support), ask in
[Discussions](https://github.com/snowy-ghost/ovenlight/discussions), email
team@snowyghost.com, or open an [issue](https://github.com/snowy-ghost/ovenlight/issues).
The plugin is Apache-2.0, from Snowy Ghost. Ovenlight works with Tailscale and isn't
affiliated with Tailscale Inc.
