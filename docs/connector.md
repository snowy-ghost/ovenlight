# The connector

The connector, the `ovenlight` command, runs on the computer with your apps. It publishes
each app as its own node (a device in your tailnet) with HTTPS, and manages guests. This
page covers it in full: installing and updating it, publishing apps, connecting your
coding agent (Claude Code, Codex and others), sharing apps, moving to a new computer and
uninstalling. It ends with [Troubleshooting](#troubleshooting) and a list of
[every command](#commands). The [README](../README.md#get-started) has the short version.

## What the computer needs

The computer must stay on and awake while anyone uses your apps. A laptop can keep
serving with its lid closed once you change one setting
([how](#a-laptop-with-its-lid-closed)). It must be one of these:

- **A Mac with macOS 13 or later**, logged in to its desktop; `ovenlight doctor` checks
  that it won't sleep. The connector is a launchd agent, which runs only in a logged-in
  session, so a Mac without a display needs automatic login. FileVault turns automatic
  login off, so after a power cut, or a restart for a macOS update, the connector waits
  until someone logs in at the Mac.
- **A Linux computer with systemd**, such as Ubuntu. The connector is a systemd user
  service, and with linger on, it runs from startup with nobody logged in. `install.sh`
  turns linger on, or prints the `sudo` command for it when it can't.
- **A Windows 11 PC.** The connector is a scheduled task that runs as you from startup,
  with nobody signed in. On a work or school account (Microsoft Entra ID) or a domain
  account, Windows runs it only while you're signed in. Installing it needs an
  administrator PowerShell, signed in as the account that will own the apps: elevating
  with another account's password installs the connector for that account. Windows puts
  an idle computer to sleep by default: in Settings, under System > Power (Power &
  battery on a laptop), set sleep to Never. `ovenlight doctor` checks that too.

### A laptop with its lid closed

Closing a laptop's lid puts it to sleep, and your apps go offline, until you change one
setting. After that it stays fully awake even with the lid closed, so keep it plugged in,
and don't put it in a bag while it's on: it can overheat there.

- **On a Mac,** turn off sleep in Terminal:

  ```sh
  sudo pmset -a disablesleep 1
  ```

  The Mac then never sleeps, with the lid open or closed and on battery too. To turn sleep
  back on, run the same command with `0` in place of `1`. `ovenlight doctor` says so when
  closing the lid would still put the Mac to sleep. Amphetamine, a Mac app, can do this
  for you with its Power Protect add-on, available on Apple Silicon laptops. It turns the
  setting on for a session and off again when the session ends.
- **On Windows 11,** set what closing the lid does to Do nothing when plugged in. It's in
  Control Panel, under Power Options > Choose what closing the lid does, and on Windows 11
  24H2 or later also in Settings, under System > Power & battery. `ovenlight doctor`
  doesn't check this one.
- **On Linux,** the commands under
  [A computer you have](always-on.md#a-computer-you-have) keep a laptop awake with its
  lid closed.

You also need a Tailscale account with MagicDNS and HTTPS certificates turned on in the
admin console, under [DNS](https://console.tailscale.com/admin/dns); `ovenlight doctor`
checks both. The computer doesn't need the Tailscale app. Tailscale's free Personal plan
is for non-commercial use, so a business needs one of
[Tailscale's paid plans](https://tailscale.com/pricing).

## Install

On a Mac or Linux, in a terminal:

```sh
curl -fsSL https://ovenlight.app/install.sh | sh
```

On Windows, sign in as the account that will own the apps, which must be an
administrator. Open PowerShell with Run as administrator and run:

```powershell
irm https://ovenlight.app/install.ps1 | iex
```

Never used a terminal? [Here's how to open one](#open-a-terminal).

The script takes the [verified steps](#check-a-download-first) for you, all but comparing
the index's serial with the one your connector holds. It fetches the index the connector
updates from and checks the index's signature with the release keys. It downloads this
computer's archive of the release the index names and checks it against the index. Then
it unpacks the archive into `~/ovenlight-connector` (`$HOME\ovenlight-connector` on
Windows) and runs its install script. If a check fails, it stops with nothing installed.
On a Mac or Linux, when `~/.local/bin` isn't on your `PATH`, it adds
`export PATH="$HOME/.local/bin:$PATH"` to your shell's profile: `~/.zshrc` for zsh, and for
bash `~/.bashrc` on Linux and `~/.bash_profile` on a Mac (or the `~/.bash_login` or
`~/.profile` bash reads in its place). For another shell, it prints the line to add.
Running it again installs the release the index names over yours, keeping your apps, node
state and update settings.

Checking the signature needs an `ssh-keygen` that can read the release key: OpenSSH 8.2
or later on Linux, 8.9 or later on Windows, and macOS 14 or later on a Mac. Without one,
the script says so and checks the download against the index alone, so it trusts the
HTTPS download, as the steps by hand do when you skip the check. On Windows it then also
checks that Snowy Ghost LLC signed `ovenlight.exe`, `install.ps1` and `uninstall.ps1`.

### Open a terminal

The command goes in a terminal, a window where you type commands:

- **Mac:** press Command-Space, type `Terminal` and press Return.
- **Linux:** on Ubuntu, press Ctrl-Alt-T. Elsewhere, open Terminal from your apps. If the
  command then says `curl` isn't found, install it with `sudo apt install curl` (or your
  system's package manager) and run the command again.
- **Windows:** open Start, type `PowerShell`, choose Run as administrator, and choose Yes
  when Windows asks.

Copy the command, paste it into the terminal (Command-V on a Mac, Ctrl-Shift-V on Linux,
Ctrl-V on Windows) and press Return (Enter on a PC). The script says what it does as it
goes. When it's done, open a new terminal window for the next steps it lists.

### Read the script first

To read the script before it runs, save a copy, read it, and run that copy. On a Mac or
Linux, `curl -fsSLO https://ovenlight.app/install.sh` saves it and `sh install.sh` runs
it. On Windows, in an administrator PowerShell,
`irm https://ovenlight.app/install.ps1 -OutFile $HOME\install.ps1` saves it and
`powershell -ExecutionPolicy Bypass -File $HOME\install.ps1` runs it. Reading it at its
address in a browser isn't the same: the site could send your terminal another one.

### Or install by hand

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

On an Arm PC, use `windows-arm64` in place of `windows-amd64`. `ovenlight.exe`,
`install.ps1` and `uninstall.ps1` are signed by Snowy Ghost LLC. To check one, open its
Properties and look under Digital Signatures.

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
These links always point to the latest release. Unpack the archive if your browser
hasn't, then run `./install.sh` in its `ovenlight-connector-<version>` folder. On Windows,
right-click that folder in File Explorer and choose Copy as path. Then, in PowerShell
opened with Run as administrator, type `cd` and a space, paste the path, press Enter, and
run `powershell -ExecutionPolicy Bypass -File .\install.ps1`.

#### Check a download first

To check a download before installing it, fetch it with the index the connector updates
from (`latest.json`), the index's signature (`latest.json.sig`) and the release keys from
this repository ([allowed_signers](allowed_signers)). Then check that a release key signed
the index, and that the index names the archive's SHA-256. On a Mac or Linux:

```sh
mkdir -p ~/ovenlight-connector && cd ~/ovenlight-connector
archive=ovenlight-connector-macos.tar.gz
base=https://downloads.ovenlight.app/connector
curl -fsSL --remote-name-all "$base/latest.json" "$base/latest.json.sig" "$base/latest/$archive"
curl -fsSLO https://raw.githubusercontent.com/snowy-ghost/ovenlight/master/docs/allowed_signers
ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n ovenlight-latest \
  -s latest.json.sig < latest.json
grep -F "\"$archive\": \"$(shasum -a 256 "$archive" | cut -d ' ' -f 1)\"" latest.json
```

On Linux, put `linux-amd64` or `linux-arm64` in place of `macos`, and if `shasum` is
missing, `sha256sum` in place of `shasum -a 256`. The first check prints
`Good "ovenlight-latest" signature`. On macOS 13, or Linux with OpenSSH before 8.2, it
says `unknown or unsupported key type` instead: check the download on a Mac with macOS 14
or later, or Linux with OpenSSH 8.2 or later. The second check prints the index, which
names the release after `"latest"` and its serial after `"serial"`. If both checks pass,
and the serial is no lower than the one `ovenlight status` shows for the index your
connector holds, unpack the archive and run its install script, in the same window:

```sh
tar xzf "$archive" --strip-components 1
./install.sh
```

On Windows, in PowerShell opened with Run as administrator:

```powershell
mkdir $HOME\ovenlight-connector -Force | Out-Null; cd $HOME\ovenlight-connector
$archive = 'ovenlight-connector-windows-amd64.zip'
$base = 'https://downloads.ovenlight.app/connector'
curl.exe -fsSL --remote-name-all "$base/latest.json" "$base/latest.json.sig" "$base/latest/$archive"
curl.exe -fsSLO https://raw.githubusercontent.com/snowy-ghost/ovenlight/master/docs/allowed_signers
cmd /c 'ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n ovenlight-latest -s latest.json.sig < latest.json'
(Get-Content latest.json -Raw | ConvertFrom-Json).archives.$archive -eq (Get-FileHash $archive).Hash
(Get-Content latest.json -Raw | ConvertFrom-Json).serial
```

On an Arm PC, use `windows-arm64` in place of `windows-amd64`. The first check needs
OpenSSH 8.9 or later (`ssh -V` shows yours): an older one can't read the release key that
signs with Touch ID, and says `unknown or unsupported key type`. If yours is older, check
the download on a Mac with macOS 14 or later, or Linux with OpenSSH 8.2 or later. If the
first check prints `Good "ovenlight-latest" signature`, the second `True`, and the last
the index's serial, no lower than the one `ovenlight status` shows, unpack the archive and
run its install script, in the same window:

```powershell
tar -xf $archive --strip-components 1
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

If a check fails, don't install the download. For five minutes after a release comes out,
the server may still hand out some of the last release's files, so a check that fails
then is worth trying again. A serial that stays lower than the one `ovenlight status`
shows means the server is sending an older index than your connector holds, which the
other checks pass too: report it to team@snowyghost.com.

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

Running the install script again installs the connector it came with, even an older one,
and restarts it, keeping your apps, node state and update settings. It installs:

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
Linux (`journalctl --user -eu ovenlight`). The updater keeps a few files of its own in the
state directory and the bin folder ([security.md](security.md#the-files-it-keeps)).

On macOS and Linux, `install.sh` links `ovenlight` in `~/.local/bin`, but never replaces a
file it didn't make. Run by hand, when `~/.local/bin` isn't on your `PATH`, it prints the
`export PATH` line to add to your shell profile (the one-line installer adds it for you),
and `ovenlight` works in terminal windows opened after that. On Windows, `install.ps1`
adds the bin folder to your `PATH`, for PowerShell windows opened after it.

### Start and stop

`ovenlight status` says whether the connector runs. On macOS:

- Start: `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.snowyghost.ovenlight.connector.plist || launchctl kickstart gui/$(id -u)/com.snowyghost.ovenlight.connector`
  (when the connector is loaded already, `bootstrap` says it failed, and `kickstart`
  starts it)
- Stop, until you next log in: `launchctl bootout gui/$(id -u)/com.snowyghost.ovenlight.connector`
- Restart: `launchctl kickstart -k gui/$(id -u)/com.snowyghost.ovenlight.connector`

On Linux:

- Start: `systemctl --user enable --now ovenlight`
- Stop: `systemctl --user disable --now ovenlight`
- Restart: `systemctl --user restart ovenlight`

On Windows, in an administrator PowerShell. Stopping disables the task too, since a task
that's only stopped starts again within a minute.

- Start: `Enable-ScheduledTask ovenlight; Start-ScheduledTask ovenlight`
- Stop: `Disable-ScheduledTask ovenlight; Stop-ScheduledTask ovenlight`
- Restart: `Stop-ScheduledTask ovenlight; Start-ScheduledTask ovenlight`

## Update

The connector updates itself. Releases up to 1.0.9 don't: run the
[install command](#install) once, and from then on it does. It checks once a day,
and installs a new release two days after it first sees it, at a quiet moment in the
night: once nothing has used your apps or the connector for 10 minutes. Each computer's
night starts at a time of its own between 02:00 and 04:00, and ends at 05:00, its own
time. A release that fixes a security problem in yours goes in at the first quiet 10
minutes, day or night. On a computer that's never quiet, or asleep every night, it goes in
anyway, busy or not, from the start of the next night, and any other update from the start
of the first night a day after it was due. Either waits until the connector has been
running and awake for 10 minutes. Installing restarts the connector, so the apps it runs
stop and open connections drop for a few seconds (about 10 on Linux, up to a minute on
Windows). If the new release doesn't serve your apps again within 10 minutes, the
connector goes back to the one before. Unpublishing an app before the new release serves
it again has the same effect. A restart of your computer in those 10 minutes starts them
again. On Windows, the Stop or Restart command above in that time makes it go back too, as
does shutting down or signing out of a work, school or domain account. An update that
fails, whether it went back or its download broke off, is tried again at the start of the
next night, busy or not.

`ovenlight status` shows the last check, a release waiting its turn and when it installs,
the last update, one that failed, and whether yours has a known security problem.
[security.md](security.md#updates) has what the connector checks before it installs
anything. Only a release updates itself: from source, pull and run the install script
again.

- `ovenlight update off` stops installing updates. The connector still checks daily.
  `ovenlight doctor` warns while updates are off, says so when no check has worked for
  seven days, and still fails when your release has a known security problem. Turn updates
  back on at least once every four weeks, and leave them on until `ovenlight status` shows
  no release waiting: a connector that misses a change of release keys can't verify updates
  after that.
- `ovenlight update on` turns them back on. A release already overdue then installs within
  the hour, busy or not.

### Install a release by hand

A release that changes what the install script sets up can't come as an update: `status`
and `doctor` say when yours needs its install script. To install it, or the latest
release at any time, run the [install command](#install) again. To also check that the
index is no older than the one your connector holds, take the
[verified steps](#check-a-download-first) yourself instead. Either replaces the files in
`~/ovenlight-connector`, and the install script reinstalls the connector.

Each release from 1.0.1 on also stays in its own folder, such as
`https://downloads.ovenlight.app/connector/1.0.9/`, with its checksums signed by the keys
in `allowed_signers` as it was at that release's tag. The release you updated from is in
`ovenlight status`'s Last update line. To install an older release and stay on it, first
run `ovenlight update off`, or the connector may move to the newest again. Check it on a
Mac or Linux with its version in place of `1.0.9`, and the Linux changes above:

```sh
mkdir -p ~/ovenlight-connector && cd ~/ovenlight-connector
release=1.0.9
archive=ovenlight-connector-macos.tar.gz
base="https://downloads.ovenlight.app/connector/$release"
curl -fsSL --remote-name-all "$base/$archive" "$base/SHA256SUMS" "$base/SHA256SUMS.sig"
curl -fsSL -o allowed_signers "https://raw.githubusercontent.com/snowy-ghost/ovenlight/connector-v$release/docs/allowed_signers"
ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n file \
  -s SHA256SUMS.sig < SHA256SUMS
shasum -a 256 -c --ignore-missing SHA256SUMS
tar tzf "$archive" | cut -d / -f 1 | sort -u
```

On Windows, check it in an administrator PowerShell, with its version in place of `1.0.9`
(a release after 1.0.9 needs OpenSSH 8.9 or later, as [above](#check-a-download-first)):

```powershell
mkdir $HOME\ovenlight-connector -Force | Out-Null; cd $HOME\ovenlight-connector
$release = '1.0.9'
$archive = 'ovenlight-connector-windows-amd64.zip'
$base = "https://downloads.ovenlight.app/connector/$release"
curl.exe -fsSL --remote-name-all "$base/$archive" "$base/SHA256SUMS" "$base/SHA256SUMS.sig"
curl.exe -fsSL -o allowed_signers "https://raw.githubusercontent.com/snowy-ghost/ovenlight/connector-v$release/docs/allowed_signers"
cmd /c 'ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n file -s SHA256SUMS.sig < SHA256SUMS'
[bool](Select-String -SimpleMatch "$((Get-FileHash $archive).Hash.ToLower())  $archive" SHA256SUMS)
tar -tf $archive | ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique
```

If the first check prints `Good "file" signature`, the second the archive's name with `OK`
(`True` on Windows), and the third only the release's own folder, such as
`ovenlight-connector-1.0.9` (an archive's name carries no version), unpack and install it
as the [verified steps](#check-a-download-first) do, in the same window.

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

Recipe Box becomes its own node, at `https://recipe-box.<your-tailnet>.ts.net/`.
Once the node is signed in, it gets its HTTPS certificate, which can take up to a minute
the first time. `publish` waits for it when the node is already signed in, and the
connector renews it before it runs out. Once a node has its certificate, its full
address, including your tailnet's name, is listed in public Certificate Transparency
logs, so choose names and slugs you don't mind people seeing. For each new app,
`publish` prints a login link: open it and sign in with your Tailscale account. Once you
store an API access token of your own (see [Share an app](#share-an-app)), later apps
sign in as you without the browser; the first app on a fresh install still needs its
link. Without a stored API credential, each app's sign-in expires on your tailnet's key
expiry schedule (180 days by default), and `doctor` warns 30 days ahead; with one, the
connector turns key expiry off.

On your phone, open Ovenlight, choose Use My Own Computers, tap Connect, and sign in with
the same Tailscale account. If you've already joined someone else's app, Use My Own
Computers is in Settings, under the More button. If your tailnet has device approval on,
approve the phone in the admin console. An app you signed in with a login link waits
there for approval too, listed by its slug. Ovenlight then finds your published apps by
itself. Add App, under the More button, also takes an app's address, but only one on your
own tailnet; apps on other people's computers come by invite.

[Commands](#commands) lists every command. `publish` edits the app list (see the
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

On Windows, the command runs with `cmd`, without the administrator rights the connector
has, as in a PowerShell window not run as administrator. It gets the environment Windows
keeps for your account, which the connector reads again when you publish or restart the
app: a tool installed since is found after `ovenlight restart <slug>`, and anything only
a PowerShell profile sets is missing. `$PORT` and `$HOST` still work, since the connector
passes them on as `%PORT%` and `%HOST%`. A Python virtual environment's tools are in
`.venv\Scripts`, written with backslashes, in place of `.venv/bin`.

The connector starts the command again whenever it exits, waiting longer after each exit,
up to a minute. Once a run has lasted a minute and served on the app's port, the wait
starts over. The command's output goes to `<slug>.log` in `~/Library/Logs/ovenlight` on
macOS, and in the `logs` folder of the state directory on Linux and Windows; `status`
shows whether it runs. Publishing the app again keeps the command and folder; `--run=`
stops running it, and so does `unpublish`.

### Unpublish

`unpublish` stops serving an app. Its node stays in your tailnet, offline, so publishing
it again needs no new login; a shareable app's node is deleted instead (see
[Share an app](#share-an-app)). On your phone, an unpublished app's tile stays until you
remove it (touch and hold it, then choose Remove App): discovery adds apps but never
removes one. Once you remove an app, discovery doesn't add it back, even when you publish
it again; Add App, under the More button, adds it by its address.

## Connect a coding agent

The connector is also an MCP server, `ovenlight mcp`, which speaks over stdio. Through it,
your coding agent (Claude Code, Codex and others that support local MCP servers) reads the
building guide, publishes your app, checks it, restarts it, and reads its logs and the
feedback people send. [Install the connector](#install) first, then connect the agent on
the same computer. If you build on your laptop and run your apps on a Linux server of
your own, [set up the server](always-on.md#choose-your-setup) and give your agent the note
in [Let your agent send your apps](always-on.md#let-your-agent-send-your-apps) instead.

In Claude Code, install Ovenlight's plugin. It adds the MCP server and a skill that reads
the guide whenever you ask to put an app on your phone:

```
/plugin marketplace add snowy-ghost/ovenlight
```

then:

```
/plugin install ovenlight@snowy-ghost
```

If you install the connector after the plugin, reconnect the `ovenlight` server in `/mcp`.
To add only the MCP server to Claude Code instead, on a Mac:

```sh
claude mcp add --scope user ovenlight -- \
  ~/Library/Application\ Support/ovenlight/bin/ovenlight mcp
```

On Linux, the path is `~/.local/state/ovenlight/bin/ovenlight` (under `$XDG_STATE_HOME`
when that's set). On Windows, in PowerShell:

```powershell
claude mcp add --scope user ovenlight -- "$env:LOCALAPPDATA\ovenlight\bin\ovenlight.exe" mcp
```

In Codex, on a Mac (on Linux, with the path above):

```sh
codex mcp add ovenlight -- \
  ~/Library/Application\ Support/ovenlight/bin/ovenlight mcp
```

On Windows, in PowerShell:

```powershell
codex mcp add ovenlight -- "$env:LOCALAPPDATA\ovenlight\bin\ovenlight.exe" mcp
```

For another agent, add a local MCP server whose command is the connector's binary and
whose one argument is `mcp`. Write the binary's path out in full, with no `~` or
`%LOCALAPPDATA%`, such as `/Users/you/Library/Application Support/ovenlight/bin/ovenlight`
on a Mac ([What it installs](#what-it-installs) has the path on each system).

### The MCP tools

| Tool | Arguments | What it does |
|---|---|---|
| `guide` | none | The building guide, as `ovenlight guide` prints it |
| `status` | none | What `ovenlight status --json` shows: your apps, their addresses, whether each answers, sharing and updates |
| `doctor` | none | The checks `ovenlight doctor` runs, each problem with its fix |
| `check_app` | `slug` | The checks `ovenlight check` runs: how one app will look and work on the phone |
| `publish` | `name`, with `port` for a new app, and an existing app's `slug` to update it or change its name | Publishes an app or updates one, keeping the command the connector runs for it |
| `unpublish` | `slug` | Stops serving an app that isn't shareable, and stops its command |
| `restart_app` | `slug` | Restarts an app the connector runs, then lists the checks that fail or warn once it's back |
| `logs` | `slug`, and `lines` (100 unless you say, up to 1000) | The last lines of an app's output, for an app the connector runs |
| `feedback_list` | `app` (a slug) and `limit` (20 unless you say, up to 200), both optional | The notes and screenshots people sent from Ovenlight, newest first, each screenshot as the path of a PNG on the computer |

The MCP server has no tool to share an app or invite anyone, or to set the command that
runs an app. An agent with a shell sets an app's command with
`ovenlight publish --slug <slug> --dir '<folder>' --run '<command>'`, which you approve.
Once the app is shareable, you change its command yourself, in a terminal.
[The MCP server](security.md#the-mcp-server) lists what else takes a terminal, and why.

An agent with a shell can run the commands too. `ovenlight doctor --json` and
`ovenlight check <slug> --json` print an object with `ok`, false when a check fails, and a
list of `checks`. Each check has an `id`, the `app` it's about when there is one, a
`status` of `ok`, `warn` or `fail`, and a `message`. Most problems also have a `fix` and an
`actor`, which says who applies it: `agent` or `person`. A fix the person makes on a web
page, such as the Tailscale admin console, comes with its `url`. Both commands exit 1 when
a check fails.

## Share an app

Your friend needs only Ovenlight. Their phone joins your tailnet as a guest device that
can reach only the apps you invited them to, and none of your other devices.

Sharing uses tagged devices, which Tailscale's plans count: each shareable app is one, and
so is each guest's phone, however many of your apps it holds. Three shared apps and ten
guests use 13, for example. The free Personal plan includes a set number and charges for
more ([pricing](https://tailscale.com/pricing)). Removing a guest from all your apps
deletes their device, so it stops counting. `ovenlight guests` says how many tagged
devices your tailnet has and how many are Ovenlight's, and when each guest last opened
each app, so you can tell who no longer uses them.

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
apps, so it refuses while a node in the tailnet is tagged for an app and isn't one of
this connector's own, and says what to do. [security.md](security.md#the-policy-change)
explains what it changes and what it refuses to touch.

Then, for each person:

```sh
ovenlight share recipe-box --to "Sam"
```

It prints a link, a QR code and a message to send. The other sharing commands:

- `ovenlight share garden-log --to "Sam" --existing`: the same Sam, for another app or
  another device.
- `ovenlight guests`: guests, the day each last opened each app, and open invites.
- `ovenlight revoke Sam`: removes Sam from every app; add `--app <slug>` to remove just
  one.
- `ovenlight share --cancel <id>`: withdraws an invite that hasn't been used, or a review
  invite with places left (devices that joined with it keep the app).
- `ovenlight share recipe-box --to "App Review" --review`: an invite for an app store's
  review, which lasts 90 days and works on up to 10 devices
  ([development.md](development.md#app-review-invites)).

An invite link works once and expires after 7 days (a review invite's works on up to 10
devices for 90 days). Your friend's phone joins without waiting for your approval, even
if your tailnet has device approval on ([security.md](security.md#invites) says why). You
can also share apps and remove guests in Ovenlight on your phone: touch and hold one of
your apps, or open People & Sharing under the More button.

Revoking takes effect at once on the connector. As soon as the guest's phone notices it's
no longer in your tailnet, it removes the app and its data. If the guest still has another
of your apps, the phone stays in your tailnet and removes the revoked one about 3 minutes
later.

With Send Feedback, in an app's menu, guests can send you a note and, unless they turn it
off, a screenshot of the page. `ovenlight feedback` lists what arrives, and so does People
& Sharing in Ovenlight; [security.md](security.md#feedback) has the limits.

Unpublishing a shareable app removes its guests, cancels its open invites, deletes its
node from the tailnet (so publishing it again logs in a new node, which serves the app's
existing HTTPS certificate) and offers to take its tags and guest rule out of your
policy. When any of that fails or has to wait, `unpublish` says what's left and what to
run, and [security.md](security.md#later-changes) has the details.

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

On a new or restored phone, your own apps come back once you tap Connect again. Apps
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
feedback inbox, the policy backups, the updater's record and settings, and the logs.
Without it, all of that stays, so a reinstall needs no new logins and keeps updates off if
you turned them off. Neither deletes `~/ovenlight-connector`, the unpacked release the
uninstall script runs from. Afterwards, `rm -r ~/ovenlight-connector` deletes it, or on
Windows, `cd ~` and then `Remove-Item -Recurse -Force $HOME\ovenlight-connector`. The
`PATH` line the one-line installer added stays in the profile it named, since other tools
may use `~/.local/bin` too.

## Troubleshooting

Start with `ovenlight doctor`. It checks the computer's side and gives a fix for each
problem it finds, such as an app that isn't listening or listens on the wrong address,
another program on an app's port, a node waiting for its login, a missing HTTPS
certificate, a Mac or Windows PC that sleeps, or sharing that isn't fully set up.
`ovenlight check <slug>` looks at one app the way your phone will. When `ovenlight` isn't
found, or neither finds the problem, look here.

- **Your terminal says command not found for `ovenlight`** (on Windows, that it isn't
  recognized). Open a new terminal window, since the install puts it on your `PATH` only
  for windows opened after it. If it's still not found on a Mac or Linux, add
  `export PATH="$HOME/.local/bin:$PATH"` to your shell's profile ([Install](#install)
  names the file for zsh and bash) and open a new window. Until then, run
  `~/.local/bin/ovenlight`. On Windows, run it by its full path:
  `& "$env:LOCALAPPDATA\ovenlight\bin\ovenlight.exe"`.
- **The connector isn't running.** `ovenlight status` gives the command that starts it,
  the same as in [Start and stop](#start-and-stop). If it starts and then stops again,
  the end of its log says why, or after a crash the stderr log beside it (the user
  journal on Linux). [What it installs](#what-it-installs) says where each is.
- **Your coding agent doesn't have Ovenlight's tools.** On a laptop that sends your apps to
  a Linux server of your own, that's expected: the agent runs the connector over `ssh`, as
  [Let your agent send your apps](always-on.md#let-your-agent-send-your-apps) sets up, so
  don't install the connector on the laptop. Otherwise, install it and
  [connect the agent](#connect-a-coding-agent) if you haven't, then start a new session
  (in Claude Code, reconnecting `ovenlight` in `/mcp` also works). A setting you added by
  hand has to name the binary at its installed path
  ([What it installs](#what-it-installs)). `claude mcp list` checks each server Claude
  Code has. In Codex, `/mcp` lists the tools it has.
- **Your app doesn't appear on your phone.** The phone must be signed in to the same
  Tailscale account as the computer. To check, tap the More button in Ovenlight, then
  Settings, then Advanced, which shows the phone's account and tailnet. If it's another
  account, choose Sign Out in Settings and connect again with the right one. With device
  approval on in your tailnet, the phone and any app that signed in with a login link
  wait for you to approve them ([Publish an app](#publish-an-app)). An app you removed
  from the phone stays removed until you add it back ([Unpublish](#unpublish)).
- **Ovenlight says it can't reach one of your apps.** The computer has to be on, awake
  and online ([What the computer needs](#what-the-computer-needs) covers a Mac's login and
  a laptop's lid). Once it is, `ovenlight status` says whether the connector is running.
- **The phone shows an error page from the app, such as 400, 403 or Vite's "Blocked
  request", though it works at `127.0.0.1`.** Usually the app turns away its tailnet name,
  which the phone sends as `Host` with every request and as `Origin` with a form. Servers
  that check hosts need to allow `.ts.net`, as
  [the building guide](building-apps.md#serve-plain-http-on-127001) shows for Vite's dev
  server and Django. `ovenlight check <slug>` tests `Host` but sends no form. An app you
  already self-host may need more, which `check` can miss: Home Assistant answers 400
  until it trusts the connector as a proxy. Follow the app's recipe in
  [Apps you already run](apps-you-run.md), or [Any app](apps-you-run.md#any-app) for one
  without a recipe.
- **An app's output in `ovenlight logs <slug>` says command not found.** On a Mac or
  Linux, the app's command doesn't get tools set up only in `~/.zshrc` or `~/.bashrc`,
  such as nvm and pyenv. [Keep an app running](#keep-an-app-running) has the fix, and
  what differs on Windows.
- **A change doesn't show on the phone.** Choose Reload in the app's menu on the phone,
  after `ovenlight restart <slug>` if the app's command doesn't reload when files change.
  For a new icon or color, run `ovenlight publish --slug <slug>` again, then touch and hold
  the app in Ovenlight and choose Refresh Icon
  ([the building guide](building-apps.md#make-it-feel-native) has the details).
- **Sharing fails with 401 Unauthorized, or `doctor` says the API credential doesn't
  work.** Access tokens expire. `ovenlight auth status --check` tests the one you stored,
  and `ovenlight auth set` replaces it ([Share an app](#share-an-app)).
- **A friend can't open an app you shared.** See
  [Support](https://ovenlight.app/support#friend-cant-open).

For apps on your own server, [Your own server](always-on.md#troubleshooting) has more, for
`ssh` and reaching the server.

## Commands

`ovenlight help` lists these. To see a command's flags, add `-h` after it, as in
`ovenlight share -h`. For `auth` and `update`, add it after both words:
`ovenlight auth set -h`. Every command also takes `--config <file>` and `--state <dir>`,
to use an app list and state directory other than the installed connector's
([What it installs](#what-it-installs)).

### Apps

- `ovenlight new "<App Name>" [--dir <parent>] [--port <n>]`: writes a starter app in a
  new folder under `<parent>`, on a free port from 20000 to 29999 unless `--port` picks
  one, and prints how to run and publish it.
- `ovenlight publish --port <n> --name "<App>" [--slug <slug>]`: publishes an app, or
  updates the one with that slug ([Publish an app](#publish-an-app)).
- `ovenlight publish ... --run '<command>' [--dir <path>]`: has the connector run the app
  and keep it running, and `--run=` stops that
  ([Keep an app running](#keep-an-app-running)).
- `ovenlight logs <slug> [-n <lines>] [-f]`: the last 100 lines of output of an app the
  connector runs, or the last `-n`. With `-f`, it keeps printing new lines until Ctrl-C.
- `ovenlight restart <slug>`: stops an app the connector runs and starts it again.
- `ovenlight unpublish <slug>`: stops serving an app ([Unpublish](#unpublish)).
- `ovenlight status [--json]`: whether the connector runs, each app's address and
  whether it answers, any login link, sharing and updates.
- `ovenlight doctor [--json]`: checks the whole setup, with a fix for each problem.
- `ovenlight check <slug> [--json]`: checks how one app will look and work on the phone,
  with a fix for each problem.
- `ovenlight guide`: prints [the building guide](building-apps.md) for the installed
  release.
- `ovenlight mcp`: the MCP server for coding agents
  ([Connect a coding agent](#connect-a-coding-agent)).

### Sharing

- `ovenlight auth set [--oauth-client-id <id>]`: stores a Tailscale API access token, or
  an OAuth client's secret, asking for it with input hidden
  ([Share an app](#share-an-app)).
- `ovenlight auth status [--check]`: which credential is stored, and with `--check`,
  whether Tailscale's API still accepts it.
- `ovenlight auth remove`: deletes the stored credential. Revoke it in the admin console
  too.
- `ovenlight publish --slug <slug> --shareable [--owner <login>] [--owner-label <name>]`:
  makes an app shareable once you've confirmed the owner and typed `yes` to the policy
  change it shows ([Share an app](#share-an-app)).
- `ovenlight share <slug> --to "<Name>"`: an invite link, a QR code and a message to
  send. For someone you already share with, add `--existing`, or give `--person <ID>` in
  place of `--to`. `--review` makes one for an app store's review.
- `ovenlight share --cancel <id>`: withdraws an open invite.
- `ovenlight guests [--all] [--sync] [--json]`: your guests, when each last opened each
  app, and open invites. `--all` adds removed guests, and `--sync` checks the tailnet's
  device list first.
- `ovenlight revoke <name | device ID | invite ID> [--app <slug>]`: removes a guest from
  every app, or from one with `--app`
  ([If something goes wrong](security.md#if-something-goes-wrong) says when to use a
  device or invite ID). `--person <ID>`, from `ovenlight guests`, in place of a name
  removes every device and open invite of that person.
- `ovenlight feedback [--app <slug>] [-n <count>] [--json]`: the last 20 notes and
  screenshots people sent, or the last `-n` (0 for all).
- `ovenlight setup-sharing --owner <login> [--owner-label <name>]`: changes the recorded
  owner and how guests see you. `--rollback` in their place restores the policy saved
  before the last change, and `--remove <slug>` takes an app you no longer share out of
  the policy ([Later changes](security.md#later-changes)).

### The connector itself

- `ovenlight version`: the connector's version.
- `ovenlight update off` and `ovenlight update on`: stop installing updates, and start
  again ([Update](#update)).
- `ovenlight update now --index <file>`: installs the release a canary index names right
  away. It's for testing a release ([development.md](development.md#releases)).
- `ovenlight run`: runs the connector. Its background service does this, so use the
  commands in [Start and stop](#start-and-stop) instead.
