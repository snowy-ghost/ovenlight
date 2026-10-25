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

| | At home | In the cloud |
|---|---|---|
| What it is | A Raspberry Pi, or a spare computer you own | A server you rent from AWS Lightsail |
| To send apps to it | A Raspberry Pi 5 with 4 GB, bought once | $12 a month (2 GB) |
| To build on it | A Raspberry Pi 5 with 8 GB, bought once | $24 a month (4 GB) |
| Your data | Stays in your house | On the provider's disk, in its data center |
| Stays up while | Your power and internet do | You keep paying |

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

**A mini PC or an old laptop on Ubuntu**, or another Linux with systemd, works like the Pi:
[set it up as a server](#set-up-the-server), at its own keyboard or over SSH. On a laptop,
leave it plugged in. So it never sleeps, even with the lid closed, also run:

```sh
sudo mkdir -p /etc/systemd/sleep.conf.d
printf '[Sleep]\nAllowSuspend=no\nAllowHibernation=no\n' | sudo tee /etc/systemd/sleep.conf.d/stay-awake.conf
```

**A Mac or a Windows PC** you use every day can run your apps too: build on it, and install
the connector as the [README](../README.md#get-started) shows. Then keep it awake:

- **On a Mac:** in System Settings, under Energy, turn on Prevent automatic sleeping when
  the display is off (under Battery, Options on a laptop, which must also stay open). The
  connector runs only while you're logged in to the Mac's desktop, so a Mac without a
  display needs your account chosen under Users & Groups, Automatically log in as.
  FileVault turns automatic login off: with FileVault on, the connector waits for someone
  to log in after each restart.
- **On Windows 11:** in Settings, under System > Power (Power & battery on a laptop), set
  sleep to Never.

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

In the browser terminal, [set up the server](#set-up-the-server), starting with Tailscale.
On the 2 GB plan, add a swap file too, which gives a busy moment room:

```sh
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

To keep a copy of everything on it, turn on Automatic snapshots in the instance's
Snapshots tab: Lightsail keeps the last seven daily copies of the whole server, at $0.05
per GB a month, and deletes them with the instance.

## Set up the server

These steps are the same on a Raspberry Pi, a spare Linux computer and a cloud server. A
cloud server's user is `ubuntu`: wherever a command below says `pi@oven`, use
`ubuntu@oven` in its place.

### Tailscale

Install Tailscale and sign the server in, with
[Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) on:

```sh
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --ssh --hostname=oven
```

Open the link it prints and sign in with your Tailscale account. Then, in the admin
console's [Machines](https://console.tailscale.com/admin/machines) page, open the menu
beside `oven` and choose Disable Key Expiry. Otherwise the server signs out after 180
days, and you can't reach it until you sign it in again.

From now on, your laptop reaches the server from anywhere, with no password or SSH keys:

```sh
ssh pi@oven
```

Tailscale may print a link to confirm it's you: open it. It asks again after 12 hours.
This works with Tailscale's default policy. If you've edited your tailnet policy, it needs
a rule [allowing SSH](https://tailscale.com/kb/1193/tailscale-ssh) to your own devices.

**On a cloud server, close the firewall now.** Tailscale and the connector only dial out,
so nothing needs to reach the server from the internet. In the Lightsail console, open the
instance's Networking tab and delete every rule under IPv4 Firewall and IPv6 Firewall (SSH
and HTTP). The browser terminal stops working, but `ssh ubuntu@oven` keeps working. Add
the SSH rule back if you ever need the browser terminal again.

From here, your coding agent can do the rest: see [Let your agent do it](#let-your-agent-do-it).

### Updates, Node and the connector

In an `ssh pi@oven` session, let the server install security updates by itself, and
restart when one needs it, such as a new kernel. Its clock may be UTC, where 10:00 is the
small hours in the Americas: put a night hour where you are in its place.

```sh
sudo apt-get install -y unattended-upgrades
printf 'Unattended-Upgrade::Automatic-Reboot "true";\nUnattended-Upgrade::Automatic-Reboot-Time "10:00";\n' | sudo tee /etc/apt/apt.conf.d/52automatic-reboot
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

The connector, your apps and Tailscale start again by themselves after a restart.
`~/.local/bin/ovenlight doctor` checks the setup.

## Build on your laptop

Your laptop keeps the code. The server runs a copy of each app, and keeps its data:
whatever people add to an app lives on the server, and sending a new version never
replaces it.

### Send an app

Ask your coding agent to put the app on your phone through `oven`, and point it to this
section. Or do it yourself. For the starter app in `~/src/family-list`, on a Mac or Linux
laptop:

```sh
rsync -a --exclude data --exclude node_modules --exclude .git ~/src/family-list pi@oven:apps/
ssh pi@oven '~/.local/bin/ovenlight publish --port 27748 --name "Family List" --run "npm start" --dir ~/apps/family-list'
```

This is what any app takes:

1. Copy the project folder to `~/apps` on the server, leaving out its data folder,
   `node_modules` and `.git`. On Windows, where rsync is missing, your agent can copy it
   with `scp`.
2. If the app has dependencies, install them on the server, in its folder (`npm ci` for a
   Node app with a `package-lock.json`). The server's system differs from your laptop's,
   so it builds its own.
3. Publish it on the server with its port, name, start command and folder. `publish`
   prints a login link: open it on your laptop and sign in with your Tailscale account.
   Then [open the app on your phone](../README.md#4-open-it-on-your-phone).

To send a new version, copy the folder again the same way, then run
`ssh pi@oven '~/.local/bin/ovenlight restart family-list'`. The connector's other commands
work the same way from your laptop, such as `ovenlight logs family-list` and
`ovenlight check family-list` after `ssh pi@oven`. To share an app, touch and hold it in
Ovenlight on your phone and choose Share.

Leave the app unpublished on your laptop: two connectors publishing one app give it two
nodes, and the second comes up as `family-list-1`.

## Build on the server

To work on the server itself, install [Claude Code](https://code.claude.com/docs/en/setup),
or another coding agent, there:

```sh
curl -fsSL https://claude.ai/install.sh | bash
```

It asks you to add `~/.local/bin` to your `PATH`. The system adds it at login, so instead
log out with `exit` and run `ssh pi@oven` again. Then start a `tmux` session, so the agent
keeps working when your laptop closes, and run `claude` in it. Open the link it prints to
sign in, and [connect it](../README.md#with-a-coding-agent) to Ovenlight. When you come
back, `ssh pi@oven` and `tmux attach` pick up where you left off.

## Let your agent do it

Once `ssh pi@oven` works from your laptop, and a cloud server's firewall is closed, your
coding agent can finish the setup. Paste this into Claude Code, or another agent, on your
laptop, with `ubuntu@oven` in place of `pi@oven` for a cloud server:

```text
Set up my always-on computer for Ovenlight. I reach it with `ssh pi@oven`. Follow https://ovenlight.app/docs/always-on.md under "Updates, Node and the connector", running each code block on the server as one SSH command, and show me what each prints. Don't change anything in my Tailscale or cloud accounts. If ssh or sudo asks me for anything, tell me what to run myself. Finish by running `~/.local/bin/ovenlight doctor` on the server and telling me what's left for me to do. From then on, when I ask you to put an app on my phone, send it to this computer as that page's "Send an app" section describes.
```
