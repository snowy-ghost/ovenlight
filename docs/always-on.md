# Your own server

Ovenlight opens your apps only while the computer that runs them is on and awake. A laptop
works while it's open, plugged in and awake, and its apps go offline when the lid closes.
For apps that open any time, run them on your own server: a computer you own that stays on
at home, such as a Mac mini, a spare PC or a Raspberry Pi. The connector runs on it as
[connector.md](connector.md) describes, over your own tailnet. If you can't keep a computer
at home, a server you rent in the cloud works too.

## Choose your setup

Pick where your server lives, then how you'll work with it. Not sure? Use a spare PC on
Ubuntu or Debian, or a Raspberry Pi 5 with 4 GB, and send apps to it from your laptop. A
Mac or Windows PC you already have works too, if you build on it.

| Where it lives | Send apps from your laptop | Build on it |
|---|---|---|
| [A spare PC or old laptop](#a-computer-you-have) on Ubuntu or Debian | Yes | Yes |
| [A Raspberry Pi](#a-raspberry-pi) | Pi 5 or Pi 4 with 4 GB | Pi 5 with 8 GB |
| [A Mac or Windows PC](#a-computer-you-have) | No | Yes, at the computer itself |
| [Rented on AWS Lightsail](#rented-in-the-cloud) | $12 a month (2 GB) | $24 a month (4 GB) |

- **[Send apps from your laptop](#send-apps-from-your-laptop)** (most people): keep
  building where you do now. Your coding agent copies each app to the server and publishes
  it there, and the server only serves your apps.
- **[Build on it](#build-on-the-server):** your coding agent runs on the server, and you work there over SSH from
  your laptop. It needs more memory, since Claude Code and Codex each ask for 4 GB.

At home, your data stays in your house, and the server stays up while your power and
internet do. A rented server is the provider's computer: your apps and their data sit on
its disk, in its data center, under its terms, and it stays up while you keep paying.
Ovenlight still reaches it over your tailnet, and nothing passes through our servers.

Either way, your laptop needs [Tailscale](https://tailscale.com/download), signed in with
the account you use for Ovenlight, with MagicDNS and HTTPS certificates on (the README's
[first step](../README.md#1-turn-on-https-certificates)).

## At home

### A computer you have

**A mini PC or an old laptop on Ubuntu or Debian** makes a good server, and you can set it
up at its own keyboard or over SSH. On Debian, if you set a root password at install, sudo
isn't installed and your user can't use it yet:
[install sudo and add yourself to the sudo group](https://wiki.debian.org/sudo) first. On
a laptop, leave it plugged in. On a desktop or mini PC, turn on Restore on AC Power Loss
(or a setting like it) in its BIOS, which a key such as F2 or Delete opens at startup, so
it starts again by itself after a power cut. Then run these, which install curl for the
steps that follow and keep the computer awake, even with a laptop's lid closed:

```sh
sudo apt-get update
sudo apt-get install -y curl
sudo mkdir -p /etc/systemd/sleep.conf.d
printf '[Sleep]\nAllowSuspend=no\nAllowHibernation=no\n' | sudo tee /etc/systemd/sleep.conf.d/stay-awake.conf
```

Then [set it up as a server](#set-up-the-server).

**A Mac, such as a Mac mini, or a Windows PC**, even one you use every day, can run your
apps too: build on it, and install the connector as the [README](../README.md#get-started)
shows. Then keep it awake:

- **On a Mac:** in System Settings, under Energy, turn on Prevent automatic sleeping when
  the display is off, and Start up automatically after a power failure. On a laptop, find
  the first under Battery, Options instead, and keep the lid open. The connector runs only while
  you're logged in to the Mac's desktop, so a Mac without a display needs your account
  chosen under Users & Groups, Automatically log in as. FileVault turns automatic login
  off: with FileVault on, the connector waits for someone to log in after each restart.
- **On Windows 11:** in Settings, under System > Power (Power & battery on a laptop, which
  must also stay open), set sleep to Never. On a desktop, turn on Restore on AC Power Loss
  in its BIOS, as above.

`ovenlight doctor` then confirms that the computer won't sleep on its own. That's all the
setup it needs: publish apps on it as the README's
[Publish an app](../README.md#3-publish-an-app) shows, or
[with your coding agent](../README.md#with-a-coding-agent). The rest of this page is for
Linux servers.

### A Raspberry Pi

No spare computer? A Raspberry Pi costs once, draws a few watts, starts again by itself
after a power cut and keeps your data at home. Get:

- A [Raspberry Pi 5](https://www.raspberrypi.com/products/raspberry-pi-5/) with 4 GB or
  more, or 8 GB or more to build on it too. A Raspberry Pi 4 with 4 GB or more works for
  sending apps to.
- Its official power supply (the 27W USB-C Power Supply for a Pi 5) and, for a Pi 5, the
  Active Cooler.
- A microSD card of 32 GB or more, rated A2, from a known brand, such as Raspberry Pi's
  [own](https://www.raspberrypi.com/products/sd-cards/). For more speed, a Pi 5 can boot
  from an NVMe SSD on the [M.2 HAT+](https://www.raspberrypi.com/products/m2-hat-plus/)
  instead.

On your laptop, write the card with [Raspberry Pi Imager](https://www.raspberrypi.com/software/):

1. Choose your Pi, then Raspberry Pi OS (other), then Raspberry Pi OS Lite (64-bit), then
   the card.
2. Under Customisation, set the hostname to `oven` and the username to `pi`, with a
   password of your own. Enter your Wi-Fi, unless the Pi will use a network cable. Under
   Remote access, turn on Enable SSH, with Use password authentication.
3. Choose Write, then I understand, erase and write.

Put the card in the Pi, plug it in and give it a minute to start. Then, on your laptop, in
Terminal (PowerShell on Windows), connect over your home network with the password you
chose, answering `yes` when ssh asks whether to trust the Pi:

```sh
ssh pi@oven.local
```

Then [set up the server](#set-up-the-server).

## Rented in the cloud

### Create the server on AWS Lightsail

Lightsail is AWS's fixed-price server. In the
[Lightsail console](https://lightsail.aws.amazon.com/):

1. Choose Create instance.
2. Choose a location near you with Change AWS Region and Availability Zone. The price is
   the same in each, though a few regions include half the transfer.
3. Choose Linux/Unix, then OS Only, then Ubuntu 24.04 LTS.
4. Choose Dual-stack, then a plan. As
   [Lightsail's pricing page](https://aws.amazon.com/lightsail/pricing/) lists them on
   October 25, 2026:
   - **To send apps to it:** the $12 USD plan, with 2 GB memory, 2 vCPUs, 60 GB SSD and
     3 TB transfer.
   - **To build on it:** the $24 USD plan, with 4 GB memory, 2 vCPUs, 80 GB SSD and 4 TB
     transfer.
5. Name the instance `oven` and choose Create instance.
6. Once it shows Running, open it, choose the Connect tab, then Connect using SSH. A
   terminal opens in your browser, logged in as `ubuntu`.

Lightsail charges by the hour up to the plan's monthly price, and a stopped instance still
costs: delete it to stop paying. To keep a copy of everything on it, turn on Automatic
snapshots in the instance's Snapshots tab: Lightsail keeps the last seven daily copies of
the whole server, at $0.05 per GB a month, and deletes them with the instance.

In the browser terminal, set the server's clock to your time zone, choosing your region
and city from the menus, so its restarts for updates come at night for you:

```sh
sudo dpkg-reconfigure tzdata
```

On the 2 GB plan, also add a swap file, which gives a busy moment room:

```sh
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Then, in the browser terminal, [set up the server](#set-up-the-server).

## Set up the server

These steps are the same on a Raspberry Pi, a spare computer and a cloud server, and you
run them yourself, since `sudo` may ask for your password. A Pi's user is `pi`, a cloud
server's is `ubuntu` and a spare computer's is your own: wherever a command says
`pi@oven`, use yours in place of `pi`.

### 1. Install Tailscale

Install Tailscale on the server:

```sh
curl -fsSL https://tailscale.com/install.sh | sh
```

Then sign it in, with [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) on:

```sh
sudo tailscale up --ssh --hostname=oven
```

Open the link it prints and sign in with your Tailscale account. Then, in the admin
console's [Machines](https://console.tailscale.com/admin/machines) page, approve `oven` if
your tailnet requires device approval, then open the menu beside `oven` and choose Disable
Key Expiry. Otherwise the server signs out after 180 days, and you can't reach it until
you sign it in again.

### 2. Connect from your laptop

Now switch to your laptop for good. Type `exit` (on a cloud server, close the browser
terminal), then in Terminal on your laptop (PowerShell on Windows), connect over Tailscale,
from anywhere, with no password or SSH keys. If ssh asks whether to trust the computer,
answer `yes`:

```sh
ssh pi@oven
```

The rest of the setup happens in this session. If Tailscale prints a link to confirm it's
you, open it, and the connection carries on. It asks again after 12 hours
([Troubleshooting](#troubleshooting) has more).

### 3. Close the other ways in

**On a Raspberry Pi or a spare computer, turn off its own SSH.** Tailscale SSH doesn't
use it, and it would otherwise keep taking passwords from anyone on your home network:

```sh
sudo systemctl disable --now ssh.service ssh.socket
```

If it says a unit doesn't exist, SSH was never on and there's nothing to turn off.

**On a cloud server, close the firewall.** Tailscale and the connector only dial out, so
nothing needs to reach the server from the internet. In the Lightsail console, open the
instance's Networking tab and delete every rule under IPv4 Firewall and IPv6 Firewall (SSH
and HTTP). The browser terminal stops working, but `ssh ubuntu@oven` keeps working.

### 4. Turn on automatic updates

These let the server install security updates by itself, and restart at 04:00 when one
needs it, such as for a new kernel. They also keep Tailscale up to date, and install
rsync, which sending apps needs:

```sh
sudo apt-get update
sudo apt-get install -y unattended-upgrades rsync
printf 'Unattended-Upgrade::Automatic-Reboot "true";\nUnattended-Upgrade::Automatic-Reboot-Time "04:00";\n' | sudo tee /etc/apt/apt.conf.d/52automatic-reboot
sudo tailscale set --auto-update
```

A Raspberry Pi's kernel and firmware come from Raspberry Pi's own archive, which these
updates leave out, so on a Pi, run `sudo apt update` and then `sudo apt full-upgrade` now
and then, as
[Raspberry Pi's docs](https://www.raspberrypi.com/documentation/computers/os.html#update-software)
advise.

### 5. Install Node and the connector

Node, for the starter app and most apps an agent builds, comes from
[NodeSource](https://github.com/nodesource/distributions/blob/master/DEV_README.md), since
the system's own may be too old. The last line adds Node's updates to the nightly ones,
within its major version:

```sh
curl -fsSL https://deb.nodesource.com/setup_lts.x -o nodesource_setup.sh
sudo -E bash nodesource_setup.sh
sudo apt-get install -y nodejs
echo 'Unattended-Upgrade::Origins-Pattern:: "site=deb.nodesource.com";' | sudo tee /etc/apt/apt.conf.d/52nodesource
```

Then the folder your apps go in, and the connector, set to keep running with nobody
logged in:

```sh
mkdir -p ~/apps
curl -fsSL https://ovenlight.app/install.sh | sh
sudo loginctl enable-linger "$USER"
```

If the installer says the connector runs only while you're logged in, the last line takes
care of that. The installer also adds `~/.local/bin` to your PATH in `~/.bashrc`, for your
next login.

The connector, your apps and Tailscale start again by themselves after a restart.
`~/.local/bin/ovenlight doctor` checks the setup.

## Send apps from your laptop

Your laptop keeps the code. The server runs a copy of each app and keeps what people add
to it.

### Let your agent send your apps

Paste this into your coding agent (Claude Code, Codex and others) on your laptop, with
your server's user in place of `pi`:

```text
I have an always-on computer for Ovenlight, `oven`, which I reach with
`ssh pi@oven`. Save this in your memory or instructions for every
session: when I ask you to put an app on my phone, or to change one I've
put there, build it here on this computer, then send it to `oven` and
publish it there, as https://ovenlight.app/docs/always-on.md under "Send
an app" describes. Read the building guide with
`ssh pi@oven '~/.local/bin/ovenlight guide'`, and start a new app with
`ssh pi@oven '~/.local/bin/ovenlight new "App Name" --dir ~/apps'`, then
copy it here with rsync to work on (on Windows, where Git Bash has no
rsync, use the tar forms that section gives). Don't publish apps on this
computer. Tell me each step `publish` says only I can take, such as
opening its login link or turning off the app's key expiry. If an ssh
command hangs, or prints that Tailscale SSH requires an additional check,
give me the link it printed (or ask me to run `ssh pi@oven true` in my
own terminal and open its link), then try again.
```

Paste it even if Claude Code has Ovenlight's plugin: the plugin's skill takes the same
steps, but only this note tells it where your server is.

### Send an app

Your agent takes these steps once it has the note above. To take them yourself, use
Terminal on your laptop (on Windows, Git Bash, with the [tar forms](#on-windows) below).
The commands use the starter, Family List, in `~/src/family-list`: for your own app, put
its folder, name, port and start command in their place.

1. If the app is published on your laptop now, first
   [move it off your laptop](#already-published-on-your-laptop). No app yet? Make the
   starter on the server, which has the connector, and copy it to your laptop to work on:

   ```sh
   ssh pi@oven '~/.local/bin/ovenlight new "Family List" --dir ~/apps'
   mkdir -p ~/src
   rsync -a pi@oven:apps/family-list ~/src/
   ```

2. Copy the app's folder to `~/apps` on the server. The copy leaves out its top-level
   `data`, `node_modules` and `.git`, so the server's data stays. An app keeps everything
   people add in `data` ([the app contract](app-contract.md#keep-what-people-add-in-the-data-folder)),
   so move a database or uploads kept anywhere else into it first (on the server too, for
   an app already there). For a Node app with dependencies but no `package-lock.json`,
   run `npm install` in its folder on your laptop first, so the copy carries the lockfile
   (the starter has no dependencies and needs nothing).

   ```sh
   rsync -a --exclude /data --exclude /node_modules --exclude /.git ~/src/family-list/ pi@oven:apps/family-list/
   ```

3. Install the app's dependencies on the server, so any native ones are built for its
   system. For a Node app, this runs `npm ci`:

   ```sh
   ssh pi@oven 'cd ~/apps/family-list && if [ -f package-lock.json ]; then npm ci; fi'
   ```

4. Publish it on the server with its port, name, start command and folder:

   ```sh
   ssh pi@oven '~/.local/bin/ovenlight publish --port 27748 --name "Family List" --run "npm start" --dir ~/apps/family-list'
   ```

   `publish` prints a login link: open it on your laptop and sign in with your Tailscale
   account. Then, on the [Machines](https://console.tailscale.com/admin/machines) page,
   choose Disable Key Expiry for `family-list` too, as `publish` reminds you, or the app
   goes offline after 180 days. (Once you store an API access token, as
   [Share an app](connector.md#share-an-app) shows, the connector does this for each app.)
   Then [open the app on your phone](../README.md#4-open-it-on-your-phone).

To send a new version, copy it, install its dependencies and restart it in one go:

```sh
rsync -a --exclude /data --exclude /node_modules --exclude /.git ~/src/family-list/ pi@oven:apps/family-list/
ssh pi@oven 'cd ~/apps/family-list && if [ -f package-lock.json ]; then npm ci; fi && ~/.local/bin/ovenlight restart family-list'
```

The connector's other commands work over `ssh` the same way, such as
`ssh pi@oven '~/.local/bin/ovenlight logs family-list'`.

#### Already published on your laptop

Unpublish the app on your laptop before you publish it on the server, or the server's copy
comes up as `family-list-1`: run `ovenlight unpublish family-list` on your laptop, and
delete its node on the [Machines](https://console.tailscale.com/admin/machines) page.
People you shared it with need new invites. Then, with everything people add moved into
`data` as step 2 describes, send it once, before step 2:

```sh
rsync -a ~/src/family-list/data pi@oven:apps/family-list/
```

#### On Windows

Use Git Bash, since Windows PowerShell 5.1 mangles the inner quotes. Git Bash has no
rsync, so use `tar` over `ssh` in place of each `rsync` line. To copy an app from the
server:

```sh
ssh pi@oven 'tar czf - -C apps family-list' | tar xzf - -C ~/src
```

To send it:

```sh
tar czf - --exclude ./data --exclude ./node_modules --exclude ./.git -C ~/src/family-list . | ssh pi@oven 'mkdir -p apps/family-list && tar xzf - -C apps/family-list'
```

To send its data once:

```sh
tar czf - -C ~/src/family-list data | ssh pi@oven 'mkdir -p apps/family-list && tar xzf - -C apps/family-list'
```

### Share an app

Connect with `ssh pi@oven` and follow [Share an app](connector.md#share-an-app) there,
once for each app. Its commands ask questions in a terminal, so they don't work as
`ssh pi@oven '…'`. After that, share from your phone: touch and hold the app in Ovenlight
and choose Share.

## Build on the server

To work on a Linux server itself, over SSH from your laptop:

1. Install `tmux`, so your agent keeps working when your laptop closes:

   ```sh
   sudo apt-get install -y tmux
   ```

2. Install your coding agent. For [Claude Code](https://code.claude.com/docs/en/setup):

   ```sh
   curl -fsSL https://claude.ai/install.sh | bash
   ```

   For [Codex](https://learn.chatgpt.com/docs/codex/cli):

   ```sh
   curl -fsSL https://chatgpt.com/codex/install.sh | sh
   ```

3. Skip any `PATH` line the installer suggests: log out with `exit` and run `ssh pi@oven`
   again, which adds it. Then run `tmux`.
4. In tmux, sign your agent in and connect it to Ovenlight:
   - **Claude Code:** run `claude`, open the link it prints to sign in, and install
     Ovenlight's plugin as the [README](../README.md#with-a-coding-agent) shows.
   - **Codex:** turn on device code login in ChatGPT's security settings (in a workspace,
     its admin allows it). Then run these. The first prints a link and a code to sign in
     with, and the last starts Codex:

     ```sh
     codex login --device-auth
     codex mcp add ovenlight -- ~/.local/state/ovenlight/bin/ovenlight mcp
     codex
     ```

5. Ask your agent to put an app on your phone.

When you come back, `ssh pi@oven` and `tmux attach` pick up where you left off. A restart
for an update ends tmux and your agent with it, so check on work left running overnight.

## Back it up

On a cloud server, Lightsail's automatic snapshots keep a copy once you turn them on, as
[Create the server](#create-the-server-on-aws-lightsail) shows. At home, what people add to your
apps lives only on the server, so copy it to your laptop now and then: your apps, and the
connector's own folders, which hold your apps' node keys and your guests. Keep the copy as
private as the server. Run this on your laptop (in Git Bash on Windows) at a quiet moment,
since a database written to mid-copy may not restore:

```sh
ssh pi@oven 'tar czf - apps .config/ovenlight .local/state/ovenlight' > oven-backup.tar.gz
```

To restore it to a new server:

1. Turn the old server off if it still runs, and delete only `oven` on the
   [Machines](https://console.tailscale.com/admin/machines) page, so the new one takes its
   name. Leave the apps' nodes: the copy brings them back as they were.
2. On your laptop, forget the old server's keys, or ssh refuses the new one:

   ```sh
   ssh-keygen -R oven
   ssh-keygen -R oven.local
   ```

3. Set up the new server through [step 4](#4-turn-on-automatic-updates), with the same
   username as the old one, since each app runs from its folder's full path.
4. Type `exit`. Then, on your laptop, in the folder that holds `oven-backup.tar.gz`,
   unpack the copy on the new server:

   ```sh
   ssh pi@oven 'tar xzf -' < oven-backup.tar.gz
   ```

5. Connect with `ssh pi@oven` again and carry on with
   [step 5](#5-install-node-and-the-connector). On a different kind of server, such as a
   spare PC in place of a Pi, then install each app's dependencies again, as step 3 of
   [Send an app](#send-an-app) shows.

[Move to a new computer](connector.md#move-to-a-new-computer) has more on what carries
over.

## Troubleshooting

- **`ssh pi@oven` hangs, or says Tailscale SSH requires an additional check.** Tailscale
  confirms it's you every 12 hours. Open the link it printed. When your coding agent's
  command hangs, run `ssh pi@oven true` yourself, open its link, then have the agent try
  again. On Tailscale's Premium and Enterprise plans, `checkPeriod` in your tailnet
  policy's SSH rule stretches the 12 hours up to a week.
- **ssh is refused.** If you've edited your tailnet policy, it needs a rule allowing SSH
  to your own devices, as Tailscale's default policy has.
- **You can't reach the server over Tailscale.** At a Pi or spare computer, plug in a
  keyboard and screen, log in and run `sudo systemctl enable --now ssh` to reach it over
  your home network. On Lightsail, add the SSH rule back in the Networking tab and use the
  browser terminal.
- **An app comes up as `family-list-1`.** Its old node is still in your tailnet: see
  [Already published on your laptop](#already-published-on-your-laptop).
- **An app doesn't appear on your phone.** Run `ssh pi@oven '~/.local/bin/ovenlight doctor'`
  and follow the fix it gives for each problem. `ovenlight check family-list` and
  `ovenlight logs family-list`, over `ssh` the same way, show how the app answers and what
  it printed. Your phone must be signed in to the same Tailscale account, under Use my own
  computers.
