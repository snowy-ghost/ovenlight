# A computer that stays on

Ovenlight opens your apps only while the computer that runs them is on and awake. A laptop
works while it's open, plugged in and awake (on a Mac, with the sleep setting below), and
its apps go offline when the lid closes. For apps that open any time, this page sets up a
computer that stays on: one you own, at home, or a server you rent in the cloud. Either way, the connector works as
[connector.md](connector.md) describes, over your own tailnet.

## Which to choose

| | At home | In the cloud |
|---|---|---|
| What it is | A computer you own: a Raspberry Pi, a Mac, a mini PC or an old laptop | A server you rent from AWS Lightsail |
| Cost | Free on one you have, or a Raspberry Pi bought once, plus power | $24 a month for the plan below |
| Your data | Stays on your computer, in your house | On the provider's disk, in its data center |
| Stays up while | Your power and internet do | You keep paying |

A rented server is the provider's computer. It runs the hardware and holds the disk, so
your apps and their data sit with it, under its terms. Ovenlight still reaches the server
over your tailnet, and nothing passes through our servers.

## At home

Any computer the connector supports works, as long as it doesn't sleep. Install the
connector as the [README](../README.md#get-started) shows, then change the settings below
for your computer.

### A Mac

- **Sleep:** in System Settings, under Energy, turn on Prevent automatic sleeping when the
  display is off. On a laptop, it's under Battery, Options: Prevent automatic sleeping on
  power adapter when the display is off. A laptop must also stay open and plugged in.
- **Login:** the connector runs only while you're logged in to the Mac's desktop. For a Mac
  without a display, choose your account under Users & Groups, Automatically log in as.
  FileVault turns automatic login off, so with FileVault on, the connector waits for
  someone to log in after each restart, such as for a macOS update.
- **Power cuts:** a Mac mini, Mac Studio or iMac can start by itself when the power comes
  back. Look for the setting under Energy.

### A Raspberry Pi

No spare computer? A Raspberry Pi costs once, draws a few watts and keeps your data at
home. Get:

- A [Raspberry Pi 5](https://www.raspberrypi.com/products/raspberry-pi-5/) with 8 GB or
  more, which leaves room to run your coding agent on it too. A Raspberry Pi 4 with 4 GB or
  more works.
- Its official power supply (the 27W USB-C Power Supply for a Pi 5) and, for a Pi 5, the
  Active Cooler.
- A microSD card of 32 GB or more, rated A2, from a known brand, such as Raspberry Pi's
  [own](https://www.raspberrypi.com/products/sd-cards/). For more speed, a Pi 5 can boot
  from an NVMe SSD on the [M.2 HAT+](https://www.raspberrypi.com/products/m2-hat-plus/)
  instead.

Install a 64-bit system, Raspberry Pi OS Lite (64-bit) or Ubuntu Server, with
[Raspberry Pi Imager](https://www.raspberrypi.com/software/) on another computer. Then
install the connector for `linux-arm64` and set it up as below.

### A mini PC or an old laptop

On Ubuntu, or another Linux with systemd, install the connector as the README shows,
including the `sudo loginctl enable-linger` command if it prints one. Then stop the
computer from sleeping, both when idle and when a laptop's lid closes:

```sh
sudo mkdir -p /etc/systemd/sleep.conf.d
printf '[Sleep]\nAllowSuspend=no\nAllowHibernation=no\n' | sudo tee /etc/systemd/sleep.conf.d/stay-awake.conf
```

### A Windows 11 PC

In Settings, under System > Power (Power & battery on a laptop), set sleep to Never.
[connector.md](connector.md#what-the-computer-needs) covers the accounts the connector runs
under.

### Check it

Run `ovenlight doctor`. It checks the connector and your apps, and on a Mac or Windows,
that the computer won't sleep on its own.

## In the cloud

This sets up one Ubuntu server as the place you build: your coding agent runs there, over
SSH from your laptop, and publishes your apps there. Claude Code asks for 4 GB of memory,
so the plan below has 4 GB.

You need your laptop with Tailscale on it, and a Tailscale account with MagicDNS and HTTPS
certificates on (the README's [first step](../README.md#1-turn-on-https-certificates)).

### Create the server on AWS Lightsail

Lightsail is AWS's fixed-price server. In the
[Lightsail console](https://lightsail.aws.amazon.com/):

1. Choose Create instance.
2. Choose a location near you with Change AWS Region and Availability Zone. The price is
   the same in each, though a few regions include half the transfer.
3. Choose Linux/Unix, then OS Only, then Ubuntu 24.04 LTS.
4. Under the plan, choose Dual-stack, then the $24 USD plan: 4 GB memory, 2 vCPUs, 80 GB
   SSD and 4 TB transfer, as
   [Lightsail's pricing page](https://aws.amazon.com/lightsail/pricing/) lists it on
   October 24, 2026.
5. Name the instance `cloud` and choose Create instance.
6. Once it shows Running, open it, choose the Connect tab, then Connect using SSH. A
   terminal opens in your browser, logged in as `ubuntu`.

Lightsail charges by the hour up to the plan's monthly price, and a stopped instance still
costs: delete it to stop paying. On EC2, a
[t4g.small](https://aws.amazon.com/ec2/instance-types/t4/) (2 GB) is free for up to 750
hours a month until December 31, 2026, but the offer covers only the instance: EC2 bills
its disk and public IPv4 address apart, so it costs more than Lightsail's 2 GB plan, with
more to set up.

### Connect it to your tailnet

In the browser terminal, install Tailscale and sign the server in, with
[Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) on:

```sh
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --ssh --hostname=cloud
```

Open the link it prints and sign in with your Tailscale account. Then, in the admin
console's [Machines](https://console.tailscale.com/admin/machines) page, open the menu
beside `cloud` and choose Disable Key Expiry. Otherwise the server signs out after 180
days, and you can't reach it until you sign it in again.

On your laptop, in Terminal (PowerShell on Windows):

```sh
ssh ubuntu@cloud
```

Answer `yes` when ssh asks whether to trust the server. Tailscale may print a link to
confirm it's you: open it. No SSH keys are involved, and Tailscale asks again after 12
hours. This works with Tailscale's default policy. If you've edited your tailnet policy,
it needs a rule [allowing SSH](https://tailscale.com/kb/1193/tailscale-ssh) to your own
devices.

### Close the firewall

Tailscale and the connector only dial out, so nothing needs to reach the server from the
internet. In the Lightsail console, open the instance's Networking tab and delete every
rule under IPv4 Firewall and IPv6 Firewall (SSH and HTTP). The browser terminal stops
working, since it uses the SSH rule. `ssh ubuntu@cloud` keeps working. Add the SSH rule
back if you ever need the browser terminal again.

From here, your coding agent can take over: see [Let your agent do it](#let-your-agent-do-it).

### Keep it patched

The rest happens in your `ssh ubuntu@cloud` session. Ubuntu installs security updates by
itself each day. Some, such as a new kernel, take effect only after a restart, so let it
restart when one needs it. The server's clock is UTC, and 10:00 UTC is the small hours in
the Americas: put a night hour where you are in its place.

```sh
printf 'Unattended-Upgrade::Automatic-Reboot "true";\nUnattended-Upgrade::Automatic-Reboot-Time "10:00";\n' | sudo tee /etc/apt/apt.conf.d/52automatic-reboot
```

The connector and your apps start again by themselves after a restart. Builds use a lot of
memory for a short time, so give them room with a swap file:

```sh
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### Install Node, the connector and your agent

Node, for the starter app and most apps an agent builds, from
[NodeSource](https://github.com/nodesource/distributions), since Ubuntu's own is too old:

```sh
curl -fsSL https://deb.nodesource.com/setup_lts.x -o nodesource_setup.sh
sudo -E bash nodesource_setup.sh
sudo apt-get install -y nodejs
```

`sudo apt upgrade` keeps it up to date. Then the connector, set to keep running with
nobody logged in:

```sh
mkdir -p ~/ovenlight-connector && cd ~/ovenlight-connector
curl -fsSL https://downloads.ovenlight.app/connector/latest/ovenlight-connector-linux-amd64.tar.gz |
  tar xz --strip-components 1
sudo loginctl enable-linger "$USER"
./install.sh
```

Then [Claude Code](https://code.claude.com/docs/en/setup), or another coding agent:

```sh
curl -fsSL https://claude.ai/install.sh | bash
```

Both scripts ask you to add `~/.local/bin` to your `PATH`. Ubuntu adds it at login, so
instead log out with `exit` and run `ssh ubuntu@cloud` again. Then run `claude` and open
the link it prints to sign in, and [connect it](../README.md#with-a-coding-agent) to
Ovenlight.

### Publish a first app

Ask your agent to put an app on your phone. Or try the starter app yourself:

```sh
ovenlight new "Family List" --dir ~/src
```

Run the two commands it prints under Put it on your phone. `publish` prints a login link:
open it on your laptop and sign in with your Tailscale account. Then
[open the app on your phone](../README.md#4-open-it-on-your-phone).

Your apps' data now lives only on the server. To keep a copy, turn on Automatic snapshots
in the instance's Snapshots tab: Lightsail keeps the last seven daily copies of the whole
server, at $0.05 per GB a month, and deletes them with the instance.
[Move to a new computer](connector.md#move-to-a-new-computer) says which folders hold the
connector's own data.

## Let your agent do it

Once `ssh ubuntu@cloud` works from your laptop and the firewall is closed, your coding agent
can do the rest. Paste this into Claude Code, or another agent, on your laptop:

```text
Set up the Ubuntu server I reach with `ssh ubuntu@cloud` as an always-on computer for Ovenlight. Follow https://ovenlight.app/docs/always-on.md from "Keep it patched" through "Install Node, the connector and your agent", running each code block on the server as one SSH command, and show me what each prints. Don't change anything in my Tailscale or cloud accounts. If ssh prints a link for me to confirm, give it to me and wait. Leave signing in to Claude Code to me. Finish by running `~/.local/bin/ovenlight doctor` on the server and telling me what's left for me to do.
```

Then sign in to Claude Code on the server, as above, and publish your first app.
