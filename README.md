# Ovenlight

Ovenlight is an iPhone launcher for private web apps that run on your own computers. You
reach them over your own Tailscale network, through a Tailscale node built into the
iPhone app, so the phone needs no Tailscale app and no VPN. One Face ID unlock opens all
of them, and each app runs full screen with its own website data.

You can share one app with a friend. They get that app, never the machine it runs on.

Made by Dimitri Cole at Snowy Ghost.

There are two parts:

- **The connector** (`connector/`, the `ovenlight` command) runs on the computer with
  your apps. It publishes each app as its own device in your tailnet, with HTTPS, and
  manages guests.
- **The Ovenlight iPhone app** (`project.yml`, `Ovenlight/`) opens the apps.

## What you need

- A computer that stays on and awake while you use your apps, with the connector
  installed:
  - a Mac with macOS 13 or later, logged in to its desktop; `ovenlight doctor` checks that
    it won't sleep. The connector is a LaunchAgent, which runs only in a logged-in
    session, so a Mac without a display needs automatic login. FileVault turns automatic
    login off, so after a power cut or a macOS update restart the connector waits until
    someone logs in at the Mac;
  - or a Linux computer with systemd, such as Ubuntu. The connector is a systemd user
    service, and with linger on it runs from startup with nobody logged in. `install.sh`
    turns linger on, or prints the `sudo` command for it when it can't;
  - or a Windows 11 PC. The connector is a scheduled task that runs as you from startup,
    with nobody signed in. Installing it needs an administrator PowerShell.
- A Tailscale account, with MagicDNS and HTTPS certificates turned on (admin console,
  DNS page); `ovenlight doctor` checks it. The computer doesn't need the Tailscale app.
- The Ovenlight iPhone app, on iOS 18.1 or later, from the
  [App Store](https://apps.apple.com/app/id6817561066). To build it yourself, see
  [docs/development.md](docs/development.md#the-iphone-app).

## Install the connector

**From a release.** Download the archive for your computer:
[macOS](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-macos.tar.gz)
(its binary is notarized), or Linux
[amd64](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-linux-amd64.tar.gz)
or [arm64](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-linux-arm64.tar.gz).
These links always name the latest release;
`https://downloads.ovenlight.app/connector/<version>/` keeps each one. The Linux arm64
build is untested on a real machine. A Windows release comes later, once its signing is
set up; until then, build it from source (below).

To check a download, fetch
[SHA256SUMS](https://downloads.ovenlight.app/connector/latest/SHA256SUMS) and
[SHA256SUMS.sig](https://downloads.ovenlight.app/connector/latest/SHA256SUMS.sig) from
the same folder, and [docs/allowed_signers](docs/allowed_signers) from this repository,
then:

```sh
ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n file -s SHA256SUMS.sig < SHA256SUMS
shasum -a 256 -c --ignore-missing SHA256SUMS
```

Then:

```sh
tar xzf ovenlight-connector-<system>.tar.gz
cd ovenlight-connector-<version>
./install.sh
```

**From source.** This needs Go (`brew install go`, your package manager's `golang`,
`winget install GoLang.Go`, or [go.dev/dl](https://go.dev/dl/)); the `go` command
fetches the version `connector/go.mod` asks for when yours is older. Run
`connector/install.sh`.

On Windows, sign in as the account that will own the apps, which must be an
administrator (elevating with another account's password installs the connector for that
account), and in an administrator PowerShell (right-click it, Run as administrator) run
`powershell -ExecutionPolicy Bypass -File connector\install.ps1`. A binary you build
isn't code-signed, so Smart App Control, when it's on, may refuse to run it;
`install.ps1` says so when the connector doesn't start.

Running the install script again reinstalls and restarts the connector, keeping your
apps and node state. It installs:

| | macOS | Linux | Windows |
|---|---|---|---|
| Runs as | launchd agent `com.snowyghost.ovenlight.connector` | systemd user service `ovenlight` | scheduled task `ovenlight` |
| Binary | `~/Library/Application Support/ovenlight/bin/ovenlight` | `~/.local/state/ovenlight/bin/ovenlight` | `%LOCALAPPDATA%\ovenlight\bin\ovenlight.exe` |
| App list | `~/.config/ovenlight/config.json` | `~/.config/ovenlight/config.json` | `%LOCALAPPDATA%\ovenlight\config.json` |
| Node state | `~/Library/Application Support/ovenlight/` | `~/.local/state/ovenlight/` | `%LOCALAPPDATA%\ovenlight\` |
| Log | `~/Library/Logs/ovenlight.log` | `~/.local/state/ovenlight/ovenlight.log` | `%LOCALAPPDATA%\ovenlight\ovenlight.log` |

On Linux, `$XDG_CONFIG_HOME` and `$XDG_STATE_HOME` take the place of `~/.config` and
`~/.local/state` when set. At 20 MB the log moves to `ovenlight.log.1` beside it, replacing
the one before. What bypasses the log, such as a crash, goes to
`ovenlight.stderr.log` beside the log on macOS and Windows, and to
`journalctl --user -u ovenlight` on Linux.

On macOS and Linux, when `~/.local/bin` is on your `PATH`, `install.sh` links
`ovenlight` there. It never replaces a file it didn't make. Otherwise it prints the full
path to use and the `export PATH` line to add. On Windows, `install.ps1` adds the bin
folder to your `PATH`, for PowerShell windows opened after it. `ovenlight help` lists
the commands, and most take `-h` for their flags.

`ovenlight status` says whether the connector runs. To stop it: on macOS,
`launchctl bootout gui/$(id -u)/com.snowyghost.ovenlight.connector`; on Linux,
`systemctl --user stop ovenlight`; on Windows, in an administrator PowerShell,
`Disable-ScheduledTask ovenlight; Stop-ScheduledTask ovenlight` (stopping alone lasts a
minute). Running the install script again starts it.

## Update

Download the new release's archive and run its install script, as above; from source,
pull and run it again. `ovenlight doctor` says when a newer release is out.

## Publish an app

Run your app on that computer, serving plain HTTP on `127.0.0.1` and static files from a
public folder only, never the project's folder with its data and code. Give it a port
nothing else uses, and on a Mac not 5000 or 7000, where macOS's AirPlay Receiver listens.
Then:

```sh
ovenlight publish --port 4317 --name "Interview Coach"   # --slug interview-coach to choose the hostname
ovenlight status        # apps, node state, URL, owner, whether the app answers, login link
ovenlight doctor        # checks, each with its fix; exits 1 on any failure
ovenlight check interview-coach   # how it will look and work on the phone, with fixes
ovenlight unpublish interview-coach
ovenlight new "Family List" --dir ~/src   # a starter app that already suits Ovenlight, and how to publish it
ovenlight version
```

Each app becomes its own node, `https://interview-coach.<your-tailnet>.ts.net/`. The
first time, `publish` prints a login link: open it and sign in with your Tailscale
account. With an API access token of yours stored (see below), later apps sign in as you
without the browser. Without a stored API credential, each app's sign-in expires on your
tailnet's key expiry schedule (180 days by default), and `doctor` warns 30 days ahead;
with one, the connector turns key expiry off.

On your iPhone, open Ovenlight, choose Use My Own Computers, then Connect, and sign in
with the same Tailscale account. If your tailnet has device approval on, approve the
iPhone in the admin console. Ovenlight then finds your published apps by itself. Add
App, in the More menu, also takes an app's address, but only one on your own tailnet;
apps on other people's computers come by invite.

`status`, `doctor` and `check` take `--json`. `publish` edits the app list (see the
[table](#install-the-connector)) and reloads the connector.

An app started in a terminal stops with that terminal, and doesn't come back after a
restart. To have the connector run it instead, stop your copy (Ctrl-C in its terminal,
never `pkill` by name) and give `publish` the command that starts it:

```sh
ovenlight publish --port 4317 --name "Interview Coach" --run 'npm start'
ovenlight logs interview-coach -f     # its output; -n <lines> for more of it
ovenlight restart interview-coach     # stop the command and start it again
```

The connector runs the command in the app's folder, with `PORT` set to the app's port
and `HOST` to `127.0.0.1`. That folder is `--dir`, or else the current directory whenever
the app gets a command it didn't have (a new app, or one after `--run ""`), and stays put
after that until `--dir` changes it. Keep projects
out of `/tmp`, which a restart empties, and out of Desktop, Documents, Downloads, iCloud
Drive, cloud storage folders and external drives: macOS asks on the Mac's screen before
the connector may use those, and the app waits until you click Allow.

The command runs with `/bin/sh` in the environment of your login shell, which reads
`~/.zprofile` but not `~/.zshrc`, so a tool set up only in `~/.zshrc` (nvm, pyenv) isn't
found: for a simple command `publish` warns about it, and for any command
`ovenlight logs <slug>` says command not found. Put the tool's directory first on `PATH`
in the command: `--run 'PATH=/path/to/node/bin:$PATH npm start'`, with the directory from
`command -v node`. A project's own tools aren't on `PATH` either: run them through `npx`
(`npx vite`) or an npm script, and a Python virtual environment's by path
(`.venv/bin/uvicorn`) or through `uv run`. Single quotes keep `$PATH` and `$PORT` for the
connector's shell to expand.

A variable exported only in `~/.zshrc` is missing too, and nothing warns about it: keep
settings the app needs in a file in its folder, outside the public one.

The connector starts the command again whenever it exits, waiting longer after each exit,
up to a minute, unless the app served on its port for a minute first. Its output goes to `~/Library/Logs/ovenlight/<slug>.log`, and
`status` shows whether it runs. Publishing the app again keeps the command and folder;
`--run ""` stops running it, and so does `unpublish`.

`unpublish` stops serving an app. Its node stays in your tailnet, offline, so publishing
it again needs no new login; a shareable app's node is deleted instead (see
[below](#share-it-with-a-friend)). On your iPhone, an unpublished app's tile stays until
you remove it (long-press, Remove App): discovery adds apps but never removes one. Once
you remove an app, discovery doesn't add it back, even when you publish it again; Add
App, in the More menu, adds it by its address.

## Share it with a friend

Your friend needs only Ovenlight. Their phone joins your tailnet as a guest device that
can reach only the apps you invited them to, and none of your other devices.

First store a Tailscale API credential, once, then make each app shareable:

```sh
ovenlight auth set                                     # paste an API access token (input hidden)
ovenlight publish --slug interview-coach --shareable   # confirm the owner, then the policy change
```

Create the token in the Tailscale admin console under Settings, Keys. Tokens expire
after at most 90 days; run `auth set` again with a new one. An OAuth client works too
(`auth set --oauth-client-id <id>`); [docs/security.md](docs/security.md#the-api-credential)
lists the scopes it needs.

`publish --shareable` shows the exact change it will make to your tailnet policy and
changes nothing until you type `yes`. Only one connector per tailnet can share apps, so it
refuses while a node in the tailnet is tagged for an app this connector doesn't publish,
and says what to do. [docs/security.md](docs/security.md#the-policy-change) explains
what it changes and what it refuses to touch.

Then, for each person:

```sh
ovenlight share interview-coach --to "Sam"      # a link, a QR code and a message to send
ovenlight share notes --to "Sam" --existing     # the same Sam, for another app or another device
ovenlight guests                                # guests and open invites
ovenlight revoke Sam                            # add --app <slug> to remove just one app
```

An invite link works once and expires after 24 hours. Your friend's iPhone joins without
waiting for your approval, even if your tailnet has device approval on
([docs/security.md](docs/security.md#invites) says why). `share --cancel <id>` withdraws
one that hasn't been used. You can also share and remove guests from Ovenlight on your
iPhone: long-press one of your apps, or open People & Sharing in the More menu.

Revoking takes effect at once on the connector. The guest's phone removes the app and
its data as soon as the phone notices its device was deleted, or about 3 minutes after
it loses one app while keeping another.

Unpublishing a shareable app removes its guests, cancels its open invites, deletes its
node from the tailnet (so publishing it again logs in a new one) and offers to take its
tags and guest rule out of your policy. When any of that fails or has to wait,
`unpublish` says what's left and what to run, and
[docs/security.md](docs/security.md#later-changes) has the details.

## Moving to a new Mac

The connector keeps everything in `~/.config/ovenlight` (the app list, the commands it
runs for apps, and the API credential) and `~/Library/Application Support/ovenlight`
(node state, guests and invites). Back up both. To move, stop the old Mac's connector if
it still runs (`uninstall.sh` keeps both folders), copy them to the new Mac and run
`install.sh` there: every app and guest carries over. An app the connector runs needs
its project folder at the same path on the new Mac, and the tools its command uses;
otherwise publish it again from its new folder with `--dir`. After restoring an older backup, run
`ovenlight guests` and revoke anyone who shouldn't be there: a guest removed after the
backup can get that app back, and anyone who joined after it needs a new invite.

Without a backup, first delete the old Mac's app nodes in the admin console (Machines).
Otherwise the new nodes come up named `<slug>-1`, and every saved tile and invite points
at the old name. Then publish each app again and send new invites; existing guests lose
access.

On a new or restored iPhone, your own apps come back once you Connect again. Apps shared
with you don't move: ask for a new invite.

## Uninstall

Uninstalling changes nothing in your tailnet. So first, with the connector still running,
run `ovenlight unpublish <slug>` for each shareable app: it removes the app's guests,
deletes its node and offers to take its tags and rule out of your policy. Other app nodes
stay in your tailnet, offline, until you remove them in the admin console, and the API
token works until you revoke it there.

```sh
connector/uninstall.sh            # or ./uninstall.sh in the release folder
connector/uninstall.sh --purge    # also deletes the connector's data
```

On Windows, in an administrator PowerShell, `powershell -ExecutionPolicy Bypass -File
.\uninstall.ps1`, with `--purge` to delete the data too. It also takes the bin folder out
of your `PATH`. On Linux, linger stays on, since other services of yours may need it:
`loginctl disable-linger` turns it off.

`--purge` deletes node state, the config, the stored API credential, guest records, the
feedback inbox, the policy backups and the logs. Without it, all of that stays, so a reinstall needs no new
logins.

## Building apps for Ovenlight

Any web app that serves plain HTTP on `127.0.0.1` works. The connector tells it who is calling
with headers such as `Ovenlight-User-Id` and `Ovenlight-Role`. A web manifest gives it
an icon and a color in Ovenlight. [docs/app-contract.md](docs/app-contract.md) has the
details, including what an app must do to stay safe. Apps get no service workers or
offline copies, so an app loads only while its computer can be reached
([details](docs/app-contract.md#in-the-iphone-app)).

[docs/building-apps.md](docs/building-apps.md) is the guide to building an app that feels
at home in Ovenlight, written for coding agents first; `ovenlight guide` prints it. You
can also let a coding agent publish apps and read feedback for you:

```sh
claude mcp add ovenlight -- ~/Library/Application\ Support/ovenlight/bin/ovenlight mcp
```

On Linux, use the binary's path from the [table](#install-the-connector). On Windows:
`claude mcp add ovenlight -- "$env:LOCALAPPDATA\ovenlight\bin\ovenlight.exe" mcp`.

Or, in Claude Code, install the plugin, which adds the same MCP server and a skill that
reads the guide when you ask for an app on your phone (use one or the other). It installs
from this GitHub repository, so while the repository is private, only people with access
to it can install it:

```
/plugin marketplace add snowy-ghost/ovenlight
/plugin install ovenlight@snowy-ghost
```

## Documentation

- [docs/building-apps.md](docs/building-apps.md): building an app that works and feels
  native in Ovenlight, for coding agents and people.
- [docs/app-contract.md](docs/app-contract.md): what an app behind Ovenlight must do
  and may rely on.
- [docs/protocol.md](docs/protocol.md): how the iPhone app and the connector talk.
- [docs/security.md](docs/security.md): the threat model, the tailnet policy change,
  logging and privacy.
- [docs/development.md](docs/development.md): building the iPhone app, tests, local
  development, shipping and forking.

## Security

To report a vulnerability, see [docs/security.md](docs/security.md#reporting-a-vulnerability).

## License

The Functional Source License, Version 1.1, ALv2 Future License (FSL-1.1-ALv2); see
[LICENSE](LICENSE) and [NOTICE](NOTICE). You may use, change and share Ovenlight for any
purpose except offering a commercial product or service that competes with it, and each
version becomes available under the Apache License 2.0 two years after its release. The
iPhone app and the connector include third-party code under their own licenses, listed in
each release's `THIRD_PARTY_NOTICES.txt` and in the app under Settings, Acknowledgements.

Issues and bug reports are welcome. Code contributions need a contributor license
agreement first, so open an issue before a pull request.
