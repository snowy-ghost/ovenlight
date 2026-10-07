# The connector

The connector, the `ovenlight` command, runs on the computer with your apps. It publishes
each app as its own node (a device in your tailnet) with HTTPS, and manages guests. This
page covers it in full: installing and updating it, publishing and sharing apps, moving to
a new computer and uninstalling. The [README](../README.md#get-started) has the short
version.

## What the computer needs

The computer must stay on and awake while anyone uses your apps, and be one of these:

- **A Mac with macOS 13 or later**, logged in to its desktop; `ovenlight doctor` checks
  that it won't sleep. The connector is a launchd agent, which runs only in a logged-in
  session, so a Mac without a display needs automatic login. FileVault turns automatic
  login off, so after a power cut, or a restart for a macOS update, the connector waits
  until someone logs in at the Mac.
- **A Linux computer with systemd**, such as Ubuntu. The connector is a systemd user
  service, and with linger on, it runs from startup with nobody logged in. `install.sh`
  turns linger on, or prints the `sudo` command for it when it can't.
- **A Windows 11 PC.** The connector is a scheduled task that runs as you from startup,
  with nobody signed in. Installing it needs an administrator PowerShell, signed in as
  the account that will own the apps: elevating with another account's password installs
  the connector for that account. Windows puts an idle computer to sleep by default: in
  Settings, under System > Power (Power & battery on a laptop), set sleep to Never.

You also need a Tailscale account with MagicDNS and HTTPS certificates turned on in the
admin console, under [DNS](https://console.tailscale.com/admin/dns); `ovenlight doctor`
checks both. The computer doesn't need the Tailscale app. Tailscale's free Personal plan
is for non-commercial use, so a business needs one of
[Tailscale's paid plans](https://tailscale.com/pricing).

## Install

### From a release

On a Mac, in Terminal:

```sh
mkdir -p ~/ovenlight-connector && cd ~/ovenlight-connector
curl -fsSL https://downloads.ovenlight.app/connector/latest/ovenlight-connector-macos.tar.gz |
  tar xz --strip-components 1
./install.sh
```

On Linux, use `linux-amd64` or `linux-arm64` in place of `macos`.

On Windows, signed in as the account that will own the apps, open PowerShell with Run as
administrator and run:

```powershell
mkdir $HOME\ovenlight-connector -Force | Out-Null; cd $HOME\ovenlight-connector
curl.exe -fsSL https://downloads.ovenlight.app/connector/latest/ovenlight-connector-windows-amd64.zip -o connector.zip
tar -xf connector.zip --strip-components 1
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

On an Arm PC, use `windows-arm64` in place of `windows-amd64`. The Windows arm64 build is
untested on real hardware. `ovenlight.exe`, `install.ps1` and `uninstall.ps1` are
signed by Snowy Ghost LLC. To check one, open its Properties and look under Digital
Signatures.

To check a download on a Mac or Linux before installing it, fetch the archive with its
checksums ([SHA256SUMS](https://downloads.ovenlight.app/connector/latest/SHA256SUMS)),
their signature
([SHA256SUMS.sig](https://downloads.ovenlight.app/connector/latest/SHA256SUMS.sig)) and
the signing key from this repository ([allowed_signers](allowed_signers)), then verify
them:

```sh
mkdir -p ~/ovenlight-connector && cd ~/ovenlight-connector
base=https://downloads.ovenlight.app/connector/latest
curl -fsSL --remote-name-all "$base/ovenlight-connector-macos.tar.gz" \
  "$base/SHA256SUMS" "$base/SHA256SUMS.sig"
curl -fsSLO https://raw.githubusercontent.com/snowy-ghost/ovenlight/master/docs/allowed_signers
ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n file \
  -s SHA256SUMS.sig < SHA256SUMS
shasum -a 256 -c --ignore-missing SHA256SUMS
```

On Linux, put `linux-amd64` or `linux-arm64` in place of `macos` here too; if `shasum` is
missing, run `sha256sum -c --ignore-missing SHA256SUMS` instead. If both checks pass,
unpack the archive and run its install script:

```sh
tar xzf ovenlight-connector-macos.tar.gz --strip-components 1
./install.sh
```

To download in a browser instead, use these links:
[macOS](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-macos.tar.gz)
(its binary is notarized), Linux
[amd64](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-linux-amd64.tar.gz)
and
[arm64](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-linux-arm64.tar.gz),
and Windows
[x64](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-windows-amd64.zip)
and
[Arm](https://downloads.ovenlight.app/connector/latest/ovenlight-connector-windows-arm64.zip).
These links always point to the latest release. Each release from 1.0.1 on also stays at
`https://downloads.ovenlight.app/connector/<version>/`. Unpack the archive if your browser
hasn't, then run `./install.sh` in its `ovenlight-connector-<version>` folder. On Windows,
right-click that folder in File Explorer and choose Copy as path. Then, in PowerShell
opened with Run as administrator, type `cd` and a space, paste the path, press Enter, and
run `powershell -ExecutionPolicy Bypass -File .\install.ps1`.

### From source

This needs Git and Go 1.21 or later (`brew install go`, your package manager's `golang`,
or [go.dev/dl](https://go.dev/dl/)); the `go` command fetches the version
`connector/go.mod` asks for when yours is older. On macOS and Linux, clone this repository
and run its install script:

```sh
git clone https://github.com/snowy-ghost/ovenlight
cd ovenlight
connector/install.sh
```

If `go env GOTOOLCHAIN` says `local`, as on Fedora and Red Hat Enterprise Linux, `go`
won't fetch a newer one: run `GOTOOLCHAIN=auto connector/install.sh` instead.

On Windows, signed in as the account that will own the apps, install Git and Go with
`winget install Git.Git` and `winget install GoLang.Go`, open a new PowerShell window so
it finds them, and run `git clone https://github.com/snowy-ghost/ovenlight $HOME\ovenlight`.
Then open an administrator PowerShell (right-click it and choose Run as administrator)
and run:

```powershell
cd $HOME\ovenlight
powershell -ExecutionPolicy Bypass -File connector\install.ps1
```

A binary you build isn't code-signed, so Smart App Control, when it's on, may refuse to
run it; `install.ps1` says so when the connector doesn't start.

### What it installs

Running the install script again reinstalls and restarts the connector, keeping your apps
and node state. It installs:

| | macOS | Linux | Windows |
|---|---|---|---|
| Runs as | launchd agent `com.snowyghost.ovenlight.connector` | systemd user service `ovenlight` | scheduled task `ovenlight` |
| Binary | `~/Library/Application Support/ovenlight/bin/ovenlight` | `~/.local/state/ovenlight/bin/ovenlight` | `%LOCALAPPDATA%\ovenlight\bin\ovenlight.exe` |
| App list | `~/.config/ovenlight/config.json` | `~/.config/ovenlight/config.json` | `%LOCALAPPDATA%\ovenlight\config.json` |
| State directory | `~/Library/Application Support/ovenlight/` | `~/.local/state/ovenlight/` | `%LOCALAPPDATA%\ovenlight\` |
| Log | `~/Library/Logs/ovenlight.log` | `~/.local/state/ovenlight/ovenlight.log` | `%LOCALAPPDATA%\ovenlight\ovenlight.log` |

On Linux, `$XDG_CONFIG_HOME` and `$XDG_STATE_HOME` take the place of `~/.config` and
`~/.local/state` when set. At 20 MB the log moves to `ovenlight.log.1` beside it,
replacing the one before. What bypasses the log, such as a crash, goes to
`ovenlight.stderr.log` beside the log on macOS and Windows, and to the user journal on
Linux (`journalctl --user -u ovenlight`).

On macOS and Linux, when `~/.local/bin` is on your `PATH`, `install.sh` links `ovenlight`
there; otherwise it prints the full path to use and the `export PATH` line to add. It
never replaces a file it didn't make. On Windows, `install.ps1` adds the bin folder to
your `PATH`, for PowerShell windows opened after it.

### Start and stop

`ovenlight status` says whether the connector runs. To stop it on macOS until you next log
in:

```sh
launchctl bootout gui/$(id -u)/com.snowyghost.ovenlight.connector
```

On Linux:

```sh
systemctl --user disable --now ovenlight
```

On Windows, in an administrator PowerShell, disable the task as well as stopping it, since
a task that's only stopped starts again within a minute:

```powershell
Disable-ScheduledTask ovenlight; Stop-ScheduledTask ovenlight
```

Running the install script again starts the connector.

## Update

Run the [release commands](#from-a-release) again: they replace the files in
`~/ovenlight-connector`, and the install script reinstalls the connector. From source,
pull and run the install script again. A release build's `ovenlight doctor` says when a
newer release is out, and fails when one fixes a security problem in yours; a build from
source doesn't check.

## Publish an app

Run your app on the computer with the connector. It must serve plain HTTP on `127.0.0.1`,
and serve static files only from a public folder, never from the project's folder with its
data and code. Give it a port nothing else uses; on a Mac, avoid 5000 and 7000, where
AirPlay Receiver listens. Then:

```sh
ovenlight publish --port 4317 --name "Recipe Box"
```

The hostname comes from the name; `--slug` chooses another. These commands help along the
way:

- `ovenlight status`: apps, node state, URL, owner, whether each app answers, and any
  login link.
- `ovenlight doctor`: checks, each with its fix; it exits 1 on any failure.
- `ovenlight check recipe-box`: how the app will look and work on the phone, with fixes.
- `ovenlight unpublish recipe-box`: stops serving it (see [Unpublish](#unpublish)).
- `ovenlight new "Family List" --dir ~/src`: a starter app that suits Ovenlight, and how
  to publish it.
- `ovenlight version`: the connector's version.

Recipe Box becomes its own node, at `https://recipe-box.<your-tailnet>.ts.net/`. When a
node gets its HTTPS certificate, its full address, including your tailnet's name, is
listed in public Certificate Transparency logs, so choose names and slugs you don't mind
people seeing. For each new app, `publish` prints a login link: open it and sign in with
your Tailscale account. Once you store an API access token of your own (see
[Share an app](#share-an-app)), later apps sign in as you without the browser; the first
app on a fresh install still needs its link. Without a stored API credential, each app's
sign-in expires on your tailnet's key expiry schedule (180 days by default), and `doctor`
warns 30 days ahead; with one, the connector turns key expiry off.

On your iPhone, open Ovenlight, choose Use My Own Computers, tap Connect, and sign in with
the same Tailscale account. If you've already joined someone else's app, Use My Own
Computers is in Settings, under the ••• button. If your tailnet has device approval on,
approve the iPhone in the admin console. An app you signed in with a login link waits
there for approval too, listed by its slug. Ovenlight then finds your published apps by
itself. Add App, under the ••• button, also takes an app's address, but only one on your
own tailnet; apps on other people's computers come by invite.

`ovenlight help` lists the commands, and most take `-h` for their flags. `status`,
`doctor` and `check` also take `--json`. `publish` edits the app list (see the
[table](#what-it-installs)) and reloads the connector.

### Keep an app running

An app started in a terminal stops with that terminal, and doesn't come back after a
restart. To have the connector run it instead, stop your copy (Ctrl-C in its terminal,
never `pkill` by name) and, in the app's folder, give `publish` the command that starts
it:

```sh
ovenlight publish --port 4317 --name "Recipe Box" --run 'npm start'
```

`ovenlight logs recipe-box -f` follows its output (`-n <lines>` shows more of it), and
`ovenlight restart recipe-box` stops the command and starts it again.

The connector runs the command in the app's folder, with `PORT` set to the app's port and
`HOST` to `127.0.0.1`. That folder is the one `--dir` names. Without `--dir`, an app given
a command when it has none (a new app, or one after `--run=`) takes the current
directory, and keeps it until `--dir` changes it. Keep projects out of `/tmp`, which a
restart empties, and out of Desktop, Documents, Downloads, iCloud Drive, cloud storage
folders and external drives: macOS asks on the Mac's screen before the connector may use
those, and the app waits until you click Allow.

On macOS and Linux, the command runs with `/bin/sh` in the environment of your login
shell, which leaves out what only a terminal's shell loads: zsh, the macOS default, reads
`~/.zprofile` but not `~/.zshrc`, and bash on Ubuntu and Debian skips what `~/.bashrc`
sets up. So a tool set up only in `~/.zshrc` or `~/.bashrc` (nvm, pyenv) isn't found: for
a simple command, `publish` warns about it, and for any command, `ovenlight logs <slug>`
says command not found. Put the tool's directory first on `PATH` in the command:
`--run 'PATH=/path/to/node/bin:$PATH npm start'`, with the directory from
`command -v node`. Single quotes keep `$PATH` and `$PORT` for the connector's shell to
expand. A project's own tools aren't on `PATH` either: run them through `npx` (`npx vite`)
or an npm script, and a Python virtual environment's tools by path (`.venv/bin/uvicorn`)
or through `uv run`.

A variable exported only in `~/.zshrc` or `~/.bashrc` is missing too, and nothing warns
about it: keep settings the app needs in a file in its folder, outside the public one.

On Windows, the command runs with `cmd`, in the environment Windows keeps for your
account, which the connector reads again when you publish or restart the app: a tool
installed since is found after `ovenlight restart <slug>`, and anything only a PowerShell
profile sets is missing. `$PORT` and `$HOST` still work, since the connector passes them
on as `%PORT%` and `%HOST%`. A Python virtual environment's tools are in `.venv\Scripts`,
written with backslashes, in place of `.venv/bin`.

The connector starts the command again whenever it exits, waiting longer after each exit,
up to a minute. Once a run has lasted a minute and served on the app's port, the wait
starts over. The command's output goes to `<slug>.log` in `~/Library/Logs/ovenlight` on
macOS, and in the `logs` folder of the state directory on Linux and Windows; `status`
shows whether it runs. Publishing the app again keeps the command and folder; `--run=`
stops running it, and so does `unpublish`.

### Unpublish

`unpublish` stops serving an app. Its node stays in your tailnet, offline, so publishing
it again needs no new login; a shareable app's node is deleted instead (see
[Share an app](#share-an-app)). On your iPhone, an unpublished app's tile stays until you
remove it (touch and hold it, then choose Remove App): discovery adds apps but never
removes one. Once you remove an app, discovery doesn't add it back, even when you publish
it again; Add App, under the ••• button, adds it by its address.

## Share an app

Your friend needs only Ovenlight. Their phone joins your tailnet as a guest device that
can reach only the apps you invited them to, and none of your other devices.

Sharing uses tagged devices, which Tailscale's plans count: each shareable app is one, and
so is each guest's phone, however many of your apps it holds. Three shared apps and ten
guests use 13, for example. The free Personal plan includes a set number and charges for
more ([pricing](https://tailscale.com/pricing)). Removing a guest from all your apps
deletes their device, so it stops counting.

First create an API access token in the Tailscale admin console, under
[Settings, Keys](https://console.tailscale.com/admin/settings/keys), and store it once;
`auth set` asks for it, with input hidden:

```sh
ovenlight auth set
```

Tokens expire after at most 90 days; run `auth set` again with a new one. An OAuth client
works too (`auth set --oauth-client-id <id>`);
[security.md](security.md#the-api-credential) lists the scopes it needs.

Then make each app shareable:

```sh
ovenlight publish --slug recipe-box --shareable
```

It asks you to confirm the owner, then shows the exact change it will make to your tailnet
policy, and changes nothing until you type `yes`. Only one connector per tailnet can share
apps, so it refuses while a node in the tailnet is tagged for an app this connector
doesn't publish, and says what to do. [security.md](security.md#the-policy-change)
explains what it changes and what it refuses to touch.

Then, for each person:

```sh
ovenlight share recipe-box --to "Sam"
```

It prints a link, a QR code and a message to send. The other sharing commands:

- `ovenlight share garden-log --to "Sam" --existing`: the same Sam, for another app or
  another device.
- `ovenlight guests`: guests and open invites.
- `ovenlight revoke Sam`: removes Sam from every app; add `--app <slug>` to remove just
  one.
- `ovenlight share --cancel <id>`: withdraws an invite that hasn't been used.

An invite link works once and expires after 24 hours. Your friend's iPhone joins without
waiting for your approval, even if your tailnet has device approval on
([security.md](security.md#invites) says why). You can also share apps and remove guests
in Ovenlight on your iPhone: touch and hold one of your apps, or open People & Sharing
under the ••• button.

Revoking takes effect at once on the connector. As soon as the guest's phone notices it's
no longer in your tailnet, it removes the app and its data. If the guest still has another
of your apps, the phone stays in your tailnet and removes the revoked one about 3 minutes
later.

With Send Feedback, in an app's menu, guests can send you a note and, unless they turn it
off, a screenshot of the page. `ovenlight feedback` lists what arrives, and so does People
& Sharing in Ovenlight; [security.md](security.md#feedback) has the limits.

Unpublishing a shareable app removes its guests, cancels its open invites, deletes its
node from the tailnet (so publishing it again logs in a new one) and offers to take its
tags and guest rule out of your policy. When any of that fails or has to wait, `unpublish`
says what's left and what to run, and [security.md](security.md#later-changes) has the
details.

## Move to a new computer

The connector keeps everything in two folders: its config (the app list, the commands it
runs for apps, and the API credential) and its state (node state, guests and invites). On
a Mac they are `~/.config/ovenlight` and `~/Library/Application Support/ovenlight`; on
Linux, `~/.config/ovenlight` and `~/.local/state/ovenlight`, or under `$XDG_CONFIG_HOME`
and `$XDG_STATE_HOME` when those are set; on Windows, one folder holds both:
`%LOCALAPPDATA%\ovenlight`. Back them up. To move, stop the old computer's connector if it
still runs (the uninstall script stops it and keeps its data), copy the folders to the new
computer and install the connector there: every app and guest carries over. An app the
connector runs needs its project folder at the same path on the new computer, and the
tools its command uses; otherwise publish it again from its new folder with `--dir`. After
restoring an older backup, run `ovenlight guests` and revoke anyone who shouldn't be
there: a guest removed after that backup can regain access, and anyone who joined after it
needs a new invite.

Without a backup, first delete the old computer's app nodes in the admin console
(Machines). Otherwise the new nodes come up named `<slug>-1`, and every saved tile and
invite points at the old name. Then publish each app again and send new invites; existing
guests lose access.

On a new or restored iPhone, your own apps come back once you tap Connect again. Apps
shared with you don't move: ask for a new invite.

## Uninstall

Uninstalling changes nothing in your tailnet. So first, with the connector still running,
run `ovenlight unpublish <slug>` for each shareable app: it removes the app's guests,
deletes its node and offers to take its tags and rule out of your policy. Other app nodes
stay in your tailnet, offline, until you remove them in the admin console, and the API
credential works until you revoke it there.

```sh
~/ovenlight-connector/uninstall.sh
```

If you installed from another folder, run the `uninstall.sh` beside the `install.sh` you
ran (`connector/uninstall.sh` in a clone). Add `--purge` to delete the connector's data
too.

On Windows, run this in an administrator PowerShell, adding `--purge` to the second line
to delete the data too:

```powershell
cd $HOME\ovenlight-connector
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
```

If you installed from another folder, run the `uninstall.ps1` beside the `install.ps1` you
ran (`connector\uninstall.ps1` in a clone).

`uninstall.ps1` also takes the bin folder out of your `PATH`. On Linux, linger stays on,
since other services of yours may need it: `loginctl disable-linger` turns it off.

`--purge` deletes node state, the config, the stored API credential, guest records, the
feedback inbox, the policy backups and the logs. Without it, all of that stays, so a
reinstall needs no new logins.
