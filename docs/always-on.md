# A computer that stays on

Ovenlight opens your apps only while the computer that runs them is on and awake. A laptop
works while it's open, plugged in and awake, and its apps go offline when the lid closes.
For apps that open any time, this page sets up a computer that stays on: a Raspberry Pi or
a spare computer at home, or a server you rent in the cloud. The connector runs on it as
[connector.md](connector.md) describes, over your own tailnet.

## Which to choose

There are two ways to work with it:

- **Build on your laptop, and send apps to it.** Keep building where you do now. Your
  coding agent copies each app to the computer that stays on and publishes it there, which
  then only serves your apps. Most people want this.
- **Build on it.** Your coding agent runs on the computer that stays on, and you work there
  over SSH from your laptop. It needs more memory, since Claude Code asks for 4 GB.

And two places for it:

- **At home:** a Raspberry Pi, or a spare computer you own. A Raspberry Pi 5 with 4 GB
  to send apps to it, or 8 GB to build on it, bought once. Your data stays in your house,
  and it stays up while your power and internet do.
- **In the cloud:** a server you rent from AWS Lightsail, $12 a month (2 GB) to send apps
  to it, or $24 a month (4 GB) to build on it. Your data sits on the provider's disk, in
  its data center, and it stays up while you keep paying.

A rented server is the provider's computer. It runs the hardware and holds the disk, so
your apps and their data sit with it, under its terms. Ovenlight still reaches it over your
tailnet, and nothing passes through our servers.

Either way, your laptop needs [Tailscale](https://tailscale.com/download), signed in with
the account you use for Ovenlight, with MagicDNS and HTTPS certificates on (the README's
[first step](../README.md#1-turn-on-https-certificates)).

## At home

### A Raspberry Pi

No spare computer? A Raspberry Pi costs once, draws a few watts and keeps your data at
home. Get:

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

1. Choose your Pi, then Raspberry Pi OS Lite (64-bit), then the card.
2. Under Customisation, set the hostname to `oven` and the username to `pi`, with a
   password of your own. Enter your Wi-Fi, unless the Pi will use a network cable. Under
   Remote Access, turn on Enable SSH with password authentication.
3. Choose Write.

Put the card in the Pi, plug it in and give it a minute to start. Then, on your laptop, in
Terminal (PowerShell on Windows), connect over your home network with the password you
chose, answering `yes` when ssh asks whether to trust the Pi:

```sh
ssh pi@oven.local
```

Then [set up the server](#set-up-the-server).

### A spare computer

**A mini PC or an old laptop on Ubuntu or Debian** works like the Pi, and you can set it
up at its own keyboard or over SSH. On Debian, if you set a root password at install, your
user can't use `sudo` yet: [add yourself to the sudo group](https://wiki.debian.org/sudo)
first. On a laptop, leave it plugged in. On any spare computer, run these, which install
curl for the steps that follow and keep the computer awake, even with a laptop's lid
closed:

```sh
sudo apt-get update
sudo apt-get install -y curl
sudo mkdir -p /etc/systemd/sleep.conf.d
printf '[Sleep]\nAllowSuspend=no\nAllowHibernation=no\n' | sudo tee /etc/systemd/sleep.conf.d/stay-awake.conf
```

Then [set it up as a server](#set-up-the-server).

**A Mac or a Windows PC** you use every day can run your apps too: build on it, and install
the connector as the [README](../README.md#get-started) shows. Then keep it awake:

- **On a Mac:** in System Settings, under Energy, turn on Prevent automatic sleeping when
  the display is off (under Battery, Options on a laptop, which must also stay open). The
  connector runs only while you're logged in to the Mac's desktop, so a Mac without a
  display needs your account chosen under Users & Groups, Automatically log in as.
  FileVault turns automatic login off: with FileVault on, the connector waits for someone
  to log in after each restart.
- **On Windows 11:** in Settings, under System > Power (Power & battery on a laptop, which
  must also stay open), set sleep to Never.

`ovenlight doctor` then confirms that the computer won't sleep on its own.

## In the cloud

### Create the server on AWS Lightsail

Lightsail is AWS's fixed-price server. In the
[Lightsail console](https://lightsail.aws.amazon.com/):

1. Choose Create instance.
2. Choose a location near you with Change AWS Region and Availability Zone. The price is
   the same in each, though a few regions include half the transfer.
3. Choose Linux/Unix, then OS Only, then Ubuntu 24.04 LTS.
4. Under the plan, choose Dual-stack, then a plan. As
   [Lightsail's pricing page](https://aws.amazon.com/lightsail/pricing/) lists them on
   October 24, 2026:
   - **To send apps to it:** the $12 USD plan, with 2 GB memory, 2 vCPUs, 60 GB SSD and
     3 TB transfer.
   - **To build on it:** the $24 USD plan, with 4 GB memory, 2 vCPUs, 80 GB SSD and 4 TB
     transfer.
5. Name the instance `oven` and choose Create instance.
6. Once it shows Running, open it, choose the Connect tab, then Connect using SSH. A
   terminal opens in your browser, logged in as `ubuntu`.

Lightsail charges by the hour up to the plan's monthly price, and a stopped instance still
costs: delete it to stop paying. On EC2, a
[t4g.small](https://aws.amazon.com/ec2/instance-types/t4/) (2 GB) is free for up to 750
hours a month until December 31, 2026, but the offer covers only the instance: EC2 bills
its disk and public IPv4 address apart, so it costs more than Lightsail's 2 GB plan, with
more to set up.

To keep a copy of everything on it, turn on Automatic snapshots in the instance's
Snapshots tab: Lightsail keeps the last seven daily copies of the whole server, at $0.05
per GB a month, and deletes them with the instance.

Then, in the browser terminal, [set up the server](#set-up-the-server).

## Set up the server

These steps are the same on a Raspberry Pi, a spare computer and a cloud server, and you
run them yourself, since `sudo` may ask for your password. A cloud server's user is
`ubuntu`, and a spare computer's is your own: wherever a command below says `pi@oven`, use
that user in place of `pi`.

### Tailscale

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

Now switch to your laptop for good. Type `exit` (on a cloud server, close the browser
terminal), then in Terminal on your laptop (PowerShell on Windows), connect over Tailscale,
from anywhere, with no password or SSH keys. If ssh asks whether to trust the computer,
answer `yes`:

```sh
ssh pi@oven
```

The rest of the setup happens in this session. Tailscale may print a link to confirm it's
you: open it, and the connection carries on. It asks again after 12 hours. When your
coding agent's ssh command hangs, or prints that Tailscale SSH requires an additional
check, open the link it printed (or run `ssh pi@oven true` yourself and open its link),
then have the agent try again. Your tailnet policy's SSH rule can stretch the 12 hours up
to a week with `checkPeriod`. This works with Tailscale's default policy. If you've edited
your tailnet policy, it needs a rule allowing SSH to your own devices.

**On a Raspberry Pi or a spare computer, turn off its own SSH now.** Tailscale SSH doesn't
use it, and it would otherwise keep taking passwords from anyone on your home network:

```sh
sudo systemctl disable --now ssh.service ssh.socket
```

If it says a unit doesn't exist, SSH was never on and there's nothing to turn off.

If Tailscale ever stops working, plug a keyboard and screen into the computer, log in and
run `sudo systemctl enable --now ssh` to reach it over your home network again.

**On a cloud server, close the firewall now.** Tailscale and the connector only dial out,
so nothing needs to reach the server from the internet. In the Lightsail console, open the
instance's Networking tab and delete every rule under IPv4 Firewall and IPv6 Firewall (SSH
and HTTP). The browser terminal stops working, but `ssh ubuntu@oven` keeps working. Add
the SSH rule back if you ever need the browser terminal again.

**On a cloud server's 2 GB plan, add a swap file now**, which gives a busy moment room:

```sh
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### Updates, Node and the connector

Let the server install security updates by itself and restart at 04:00 when one needs it,
such as a new kernel. The same command installs rsync, which sending apps needs. A
Raspberry Pi keeps your time zone. A cloud server's clock is UTC, so put an hour that's
night for you in UTC in place of `04:00`, such as `10:00` in the Americas. A Raspberry
Pi's kernel and firmware come from Raspberry Pi's own archive, which these updates leave
out, so on a Pi, run `sudo apt update` and then `sudo apt full-upgrade` now and then, as
[Raspberry Pi's docs](https://www.raspberrypi.com/documentation/computers/os.html#update-software)
advise.

```sh
sudo apt-get update
sudo apt-get install -y unattended-upgrades rsync
printf 'Unattended-Upgrade::Automatic-Reboot "true";\nUnattended-Upgrade::Automatic-Reboot-Time "04:00";\n' | sudo tee /etc/apt/apt.conf.d/52automatic-reboot
```

Node, for the starter app and most apps an agent builds, from
[NodeSource](https://github.com/nodesource/distributions), since the system's own may be
too old:

```sh
curl -fsSL https://deb.nodesource.com/setup_lts.x -o nodesource_setup.sh
sudo -E bash nodesource_setup.sh
sudo apt-get install -y nodejs
```

`sudo apt upgrade` keeps it up to date. Then the connector, set to keep running with
nobody logged in:

```sh
mkdir -p ~/ovenlight-connector && cd ~/ovenlight-connector
curl -fsSL "https://downloads.ovenlight.app/connector/latest/ovenlight-connector-linux-$(dpkg --print-architecture).tar.gz" |
  tar xz --strip-components 1
sudo loginctl enable-linger "$USER"
./install.sh
```

If install.sh says `~/.local/bin` isn't on your PATH yet, skip the profile line it
suggests: Ubuntu, Debian and Raspberry Pi OS add it at your next login.

The connector, your apps and Tailscale start again by themselves after a restart.
`~/.local/bin/ovenlight doctor` checks the setup.

## Build on your laptop

Your laptop keeps the code. The server runs a copy of each app, and keeps its data:
whatever people add to an app lives on the server, and sending a new version never
replaces it.

### Let your agent send your apps

Paste this into Claude Code, or another coding agent, on your laptop, with your server's
user in place of `pi`:

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
computer. If an ssh command hangs, or prints that Tailscale SSH requires
an additional check, give me the link it printed (or ask me to
run `ssh pi@oven true` in my own terminal and open its link), then
try again.
```

With Ovenlight's Claude Code plugin, the agent follows that note too.

### Send an app

Unpublish an app that's also published on your laptop before you publish it on the
server: run `ovenlight unpublish family-list` on your laptop and delete its node on the
[Machines](https://console.tailscale.com/admin/machines) page. Otherwise the server's copy
comes up as `family-list-1`. People you shared it with need new invites.

To send one yourself, start with an app. On Windows, run this whole section in Git Bash,
which Claude Code also uses there, since Windows PowerShell 5.1 mangles the inner quotes.
Git Bash has no rsync, so use `tar` over `ssh` in place of each `rsync` line below. To
copy an app back from the server:
`ssh pi@oven 'tar czf - -C apps family-list' | tar xzf - -C ~/src`. To send it:
`tar czf - --exclude ./data --exclude ./node_modules --exclude ./.git -C ~/src/family-list . | ssh pi@oven 'mkdir -p apps/family-list && tar xzf - -C apps/family-list'`.

No app yet? Make the starter on the server, which has the connector, and copy it to your
laptop to work on:

```sh
ssh pi@oven '~/.local/bin/ovenlight new "Family List" --dir ~/apps'
mkdir -p ~/src
rsync -a pi@oven:apps/family-list ~/src/
```

Then send it, and publish it there:

```sh
rsync -a --exclude /data --exclude /node_modules --exclude /.git ~/src/family-list/ pi@oven:apps/family-list/
ssh pi@oven '~/.local/bin/ovenlight publish --port 27748 --name "Family List" --run "npm start" --dir ~/apps/family-list'
```

Any app takes the same steps:

1. Copy the project folder to `~/apps` on the server, leaving out its top-level
   `node_modules`, `.git` and data (a data folder, or a database file for an app that
   keeps one), so the server's data stays.
2. If the app has dependencies, install them on the server, in its folder (`npm ci` for a
   Node app with a `package-lock.json`). The server's system differs from your laptop's,
   so it builds its own.
3. Publish it on the server with its port, name, start command and folder. `publish`
   prints a login link: open it on your laptop and sign in with your Tailscale account.
   Then [open the app on your phone](../README.md#4-open-it-on-your-phone).

To send a new version, copy the folder again the same way, install dependencies again if
they changed, then run `ssh pi@oven '~/.local/bin/ovenlight restart family-list'`. The
connector's other commands work over `ssh pi@oven` too, such as
`ssh pi@oven '~/.local/bin/ovenlight logs family-list'`.

To share an app, connect with `ssh pi@oven` and follow
[Share an app](connector.md#share-an-app) there, once for each app. Its commands ask
questions in a terminal, so they don't work as `ssh pi@oven '…'`. After that, share from
your phone: touch and hold the app in Ovenlight and choose Share.

## Build on the server

To work on the server itself, install `tmux`, so your agent keeps working when your laptop
closes, and [Claude Code](https://code.claude.com/docs/en/setup), or another coding agent:

```sh
sudo apt-get install -y tmux
curl -fsSL https://claude.ai/install.sh | bash
```

As with the connector, skip the `PATH` line the installer suggests: log out with `exit`
and run `ssh pi@oven` again. Then run `tmux`, and `claude` in it. Open the link it prints
to sign in, and [connect it](../README.md#with-a-coding-agent) to Ovenlight. When you come back,
`ssh pi@oven` and `tmux attach` pick up where you left off.
