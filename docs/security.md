# Security

What Ovenlight protects, how, and where its limits are.

## Threat model

Ovenlight is built so that:

- **A guest gets one app, not your computer.** A guest's phone joins your tailnet, but
  can reach only the nodes of the apps it was invited to, on port 443, and sees none of
  your devices unless one of your own rules lets a device reach guest devices.
- **Nobody else in your tailnet gets in.** The connector admits the owner and guests who
  claimed an invite to that app, and answers 403 to everyone else, including people you
  share a node with through Tailscale.
- **Websites can't act through an admitted device.** Identity comes from the device, not
  a cookie, so any page open on an admitted device could otherwise act as that device.
  The connector refuses such requests (see [The proxy](#the-proxy)), except top-level
  link navigations, which is why an app's GETs must
  [change nothing](app-contract.md#make-get-change-nothing).
- **The app can trust who is calling**, as long as it listens only on `127.0.0.1`, reads
  each request body in full, and guards requests that reach it straight: it checks their
  `Host`, sends no CORS headers, and gives a request without identity headers the owner's
  rights only from its own pages
  ([app-contract.md](app-contract.md#guard-requests-that-come-straight-to-the-port)).

Out of scope: anything running as you on the computer, a coding agent with a shell included,
can do what you can. A tailnet admin can change the policy, and an app that listens on a
LAN address can be reached around the connector. The computer's other accounts can
reach an app on `127.0.0.1` directly too, so they must belong to people you trust.

## Two locks

Every guest request passes two independent checks:

1. **The tailnet policy.** Each shareable app has its own pair of tags. A guest device
   carries `tag:ovenlight-guest-<slug>` for each app it holds, and the policy lets that
   tag reach only `tag:ovenlight-app-<slug>` on port 443. So a guest's phone reaches only
   the apps it was invited to, whatever the connector does.
2. **The connector's WhoIs check.** For every request, the connector asks the tailnet who
   the calling device is. The owner is an untagged device of the owner's login: the one
   recorded by `publish --shareable`, or else the user who owns the app's node. A guest
   is a device tagged as a guest of this app, with an active claim on it. Anything else,
   unknown included, fails closed.

The admin API on port 8443 is owner only by the same WhoIs check, and the policy closes
it to guests as well.

## The policy change

`ovenlight publish --shareable` edits your tailnet policy file. It first names the local
port guests will reach. While no owner is recorded, it then asks one line:

```
Owner: you@example.com, guests see you as "You". Type yes to record:
```

The owner is the login behind your app nodes, taken from the running connector;
`--owner` and `--owner-label` set it instead, and `--owner` is needed when the connector
isn't running. The owner is recorded only once the policy change is applied.

It then reads your policy file and devices, prints the exact diff and a safety report,
and changes nothing until you type `yes`. It asks in a terminal only; there is no
`--yes`. The safety report names every rule that lets one of your devices reach guest
devices. After your `yes` it reads the device list again, and changes nothing if the
plan would now be different.

Only one connector per tailnet can share apps. Each keeps its own guest records, while
tags and a guest's phone reach across the tailnet, so two would mix up each other's
guests. `publish --shareable` refuses while a device in the tailnet carries the app tag
of an app this connector doesn't publish. The refusal names the device and says what to
do: delete it in the admin console (Machines) if it's left from an app you unpublished,
or make apps shareable on the connector it belongs to. The list of unclaimed phones
shows only guest devices of this connector's apps.

### What it adds

- `tagOwners` for `tag:ovenlight-app-<slug>` (the app's node) and
  `tag:ovenlight-guest-<slug>` (guests invited to it), owned by `autogroup:admin`.
- One rule letting `tag:ovenlight-guest-<slug>` reach only `tag:ovenlight-app-<slug>` on
  443.
- The app tag in your own rule letting you reach the app nodes on 443 and 8443 (a tagged
  node no longer counts as your own device). The first shareable app adds that rule.

Ovenlight writes new rules only to `grants`, next to your `acls` if your file uses those
(Tailscale applies both). It never deletes a guest rule you added a key to (such as
`srcPosture`).

Tag names are compared without regard to case, as Tailscale reads them in lowercase, so
a tag spelled in other case counts as the same tag.

### What it narrows

On a tailnet that still allows everything (`"*"` to `"*"`), it narrows that rule to
`autogroup:member` reaching `autogroup:member`, so guests neither inherit it nor see your
devices (a device you can reach shows up as a peer to it). It narrows no other rule on
its own.

### What it refuses

It stops, and prints the edit to make by hand, when:

- narrowing the allow-all rule would cut off something you have: tagged devices, rules
  with tag sources, subnet routes or exit nodes;
- any other rule has a `"*"` source;
- a rule would let guest devices reach more than their apps;
- a source can't be classified (IP sets, unknown autogroups);
- `nodeAttrs` could give guest devices more than client-side settings (NextDNS, App
  Connector domains and the like);
- `autoApprovers` could approve a guest device, or App Connectors could run on one;
- a guest device could take on other tags;
- anyone but admins may apply an Ovenlight tag;
- a key in the file isn't plain ASCII, since a JSON reader may take it for another key.

A destination it can't classify counts as reaching guests in the report.

### How it writes

It edits the file in place, so comments and formatting stay, and running it again
changes nothing. It validates the new policy with Tailscale, writes with `If-Match` (a
concurrent edit makes it stop), keeps the old policy as a backup in the state directory
(`policy-backups/`), reads the policy back,
checks that your app nodes still see your devices, and rolls back to the backup if any of
that fails. Without the connector running, only the read-back is checked. A write whose
answer is lost counts as applied only if the policy reads back as written. The rollback
is conditional on its own write, so if someone edited the policy in between, it stops and
leaves their edit alone.

### Later changes

- `setup-sharing --rollback` restores the last backup, unless the backup would let guest
  devices reach more than their apps while a guest device or an open invite exists.
- `setup-sharing --owner <login>` changes the recorded owner. The new owner gets a rule
  of their own letting them reach the app nodes, and the earlier owner's rule stays as
  it is, with a note in the plan; remove it in the admin console when they no longer
  need it. The connector's identity check turns away anyone but the recorded owner and
  guests, whatever such a rule allows.
- `setup-sharing --remove <slug>` takes an unshared app's tags and rule out of the policy.
  Unpublishing a shareable app offers (after `yes`) to do the same, unless the connector
  didn't confirm the unpublish in time; it then says to run `--remove` later. That policy
  change is refused while a rule Ovenlight doesn't manage, such as an earlier owner's or a
  guest rule you added a key to, still uses the app's tags.
- Whenever the connector loads its config or syncs, it removes the guests of any app the
  config no longer lists (giving "the app was unpublished" as the reason) and cancels its
  open invites. An empty config removes them all, while a missing `config.json` serves no
  apps and keeps every guest until a config exists. So an app unpublished while the
  connector was down loses its guests when the connector next starts. `unpublish` says
  whether its guests are gone already or go later.
- Until the connector has removed an unpublished app's guests and open invites,
  publishing the same slug again is refused, from the terminal and the MCP server alike,
  and `publish --shareable` refuses before it asks anything: start the connector (or wait
  for it) and run the command again.
- Unpublishing a shareable app also deletes its node from the tailnet with the stored
  API credential, and its node state on the computer, even when the connector didn't confirm
  the unpublish. Without a working credential the node stays, offline, and `unpublish`
  says to delete it in the admin console (Machines): until then, no other app can be made
  shareable.

### Tagging the app's node

After the policy change, `publish --shareable` turns the app's node into a
`tag:ovenlight-app-<slug>` node: it mints a short-lived tagged key, deletes the old node
from the tailnet (freeing its name) and logs a new one in under the same name. A new app
published with `--shareable` starts tagged. There is no command to undo it.

Until the node carries the tag, `share` refuses the app; if tagging failed, run
`publish --slug <slug> --shareable` again. The same goes for a shareable app whose node
loses its login: `status`, `doctor` and the log say to run it, rather than showing a login
link, which would sign the node in untagged.

## The API credential

`ovenlight auth set` reads the secret from standard input, never the command line. It
takes a Tailscale API access token, or an OAuth client (`--oauth-client-id <id>`, then
paste its secret) with the scopes `policy_file`, `devices:core` and `auth_keys`. A
client that also has `feature_settings:read`, which Ovenlight no longer uses, works the
same. An OAuth client can only apply the tags it was given, and every shareable app adds
two, so a token is simpler.

The credential is checked against the API, then stored in `credentials.json` beside the
app list, readable only by you ([On the computer](#on-the-computer)); the connector
refuses to use one anyone else can read. `auth status` shows its type (with the
client ID for an OAuth client), a fingerprint, the tailnet and the API URL, never the
secret; `auth status --check` also tries it against the API. `auth remove` deletes it.

With an API access token whose user is the owner (not an OAuth client), a new app that
isn't shareable logs in as you with a single-use, 10-minute key instead of the browser
(the first app on a fresh install still signs in through the browser). Its key expiry is
then turned off; a failed attempt is logged and tried again every hour until it works, so
a new credential takes effect without a restart. A node of yours that signed in through
the browser gets the same once it runs, while a credential is stored. `doctor` warns when
a node's key expires within 30 days.

## Invites

- `share` mints a single-use key tagged `tag:ovenlight-guest-<slug>` that expires in 24
  hours, or 7 days for an App Review invite (`share --review`, terminal only;
  [development.md](development.md#app-review-invites)). The key is preauthorized, whatever
  the tailnet's device approval setting, so a guest's phone never waits for you.
- The connector records the invite with the key's SHA-256, never the key.
- The link carries the key in its fragment, so it never reaches the website that hosts
  the join page ([protocol.md](protocol.md#invite-links)).
- A device belongs to one person. A device already admitted to any of your apps can only
  claim that person's invites; any other invite stays unused.
- `share` runs only in a terminal. The MCP server can't share at all.
- `share --cancel <id>` withdraws an unused invite and deletes its key. If the delete
  fails (an expired API token, say), the connector refuses the invite at once, retries
  the delete on each sync, and `--cancel` says the key still works until then.
- When the person already has a phone in your tailnet for another of your apps
  (`--existing` or `--person`, or chosen in Ovenlight), Ovenlight reuses it rather than
  joining again: `share` adds the new app's guest tag to that device, as does a claim
  from a phone that joins after the invite was made. Canceling the invite, or letting it
  expire, takes the tag back.

Ovenlight never asks you to approve a guest's phone. Guests join under random device
names, so you couldn't tell whose phone was waiting, and approving it would be a rubber
stamp. What protects you is the invite: it works once, for 24 hours, and only for one app,
since the policy lets its guest tag reach that app alone. You can remove anyone at any
time. If a stranger uses a forwarded link first, the invite doesn't work for the guest you
invited, and your guest list shows who joined with it, so you can remove them.

## Revocation

`ovenlight revoke` stops serving the guest at once, open requests and streams included.
It then takes this app's tag off the device if it still holds another of your apps, and
otherwise deletes it from the tailnet, even when an open invite to that person for
another app lists it. That invite still works: the phone joins again to claim it.

Every 3 minutes, and on `guests --sync`, the connector compares its guests with the
tailnet's devices:

- a device deleted in the admin console removes that guest;
- the guests of an app no longer published are removed, and its open invites canceled;
- unused invites expire;
- a guest device whose tags don't match the apps it holds is put right.

A guest device that never claimed an invite is listed as unclaimed (`unclaimed` in
`GET /v1/guests`, and in `guests --json`, which prints the same view). The connector
refuses it; remove it in the admin console if you don't expect it. Keys of used,
canceled and expired invites are deleted, retried until the tailnet confirms it. When a
sync fails, `status` and `guests` say why, and suggest `ovenlight auth set` when the API
turned the credential down (401). `status --json` has the reason as `sharing.syncError`,
and `sharing.stateError` when the guest records can't be read.

A guest who leaves in Ovenlight stays in your guest list until you remove them
([development.md](development.md#guests)).

## The proxy

- HTTPS on port 443 with the node's real `ts.net` certificate, to `127.0.0.1:<port>`
  only.
- **Host.** The request must carry the node's own MagicDNS name; any other `Host` gets
  421. This blocks DNS rebinding.
- **Cross-site requests** get 403, except top-level link navigations
  ([app-contract.md](app-contract.md#cross-site-requests) has the exact rule).
- **Headers.** Every incoming `Tailscale-*`, `Ovenlight-*`, `X-Ovenlight-*`,
  `X-Forwarded-*`, `X-Real-IP` and `Forwarded` header is dropped, underscore spellings
  too, because some frameworks map both to the same variable. The connector then sets
  identity and forwarding headers itself.
- **Upgrades.** Only WebSocket upgrades pass; any other gets 400, since a protocol such as
  h2c would carry requests past these checks.
- `/__ovenlight/` never reaches the app.
- **Admin API.** Owner only, and requests that change something need
  `X-Ovenlight-Request: 1` ([protocol.md](protocol.md#port-8443-the-admin-api)).

## Feedback

Owner and guests can post feedback to `/__ovenlight/feedback`:

- a note of up to 4000 characters, with control characters other than newlines and
  tabs stripped;
- a whole PNG screenshot up to 5 MB, at most 8192 pixels a side and as many pixels as
  4096 by 4096;
- 30 an hour and one at a time per person. An upload that fails or is rejected still
  counts toward the 30; one refused because the person's last upload is still running
  doesn't.

When the inbox is full (1000 items or 500 MB), a new item replaces its sender's oldest,
or the oldest overall if the sender has none there. `ovenlight feedback` lists it with
the screenshot paths, and `status` shows the count and the latest notes.

## On the computer

The connector keeps everything as you, in the places the README's
[table](../README.md#install-the-connector) lists for each system: the app list with the
API credential beside it, and the state directory, with each node's keys and
certificates, the guest and invite records, the feedback inbox, the policy backups and
the control socket. Only you can read them:

- **macOS and Linux:** the directories are mode 700 and the files 600, and the connector
  runs with umask 077.
- **Windows:** the state folder is in `%LOCALAPPDATA%`, which only you, SYSTEM and the
  Administrators group can open, and what the connector writes there inherits that. A
  state folder the connector makes itself grants only you, and each file it saves
  atomically (the app list, the credential, the guest records) gets its own. Windows
  ignores Unix modes, so the check before using the credential reads its access list
  instead, and refuses one that grants reading to anyone but you, SYSTEM or the
  Administrators group, the counterparts of root.
- **The control socket**, `ovenlight.sock` in the state directory, takes the commands
  `publish`, `share`, `revoke` and the rest. It is a Unix socket on every system,
  Windows included, and only you can use it. On macOS and Linux its mode is 600. Windows
  can't be relied on to check a socket file's access list when a client connects (in
  testing it didn't), so there the connector asks Windows which process is calling, and
  refuses any that doesn't run as you.

On Windows the connector runs as a scheduled task, as you, with logon type S4U: it runs
with nobody signed in, Windows stores no password for it, and it gets no network
credentials of yours (it needs none). Registering such a task takes an administrator,
so `install.ps1` must run elevated; the task's run level is Limited, so the connector
itself runs without administrator rights.

## Logging

- The connector's log is readable only by you. At 20 MB it moves to `ovenlight.log.1`,
  replacing the one before, so there are two files at most. A crash lands in
  `ovenlight.stderr.log` beside it on macOS and Windows, and in the user journal on
  Linux. The README's [table](../README.md#install-the-connector) has where the log is.
- It keeps each request's path, cut at 200 bytes, and never its query, which can carry
  OAuth codes and tokens.
- Each node's backend log is `nodes/<slug>/backend.log` in the state directory. At 10 MB
  it moves to `backend.log.1`, replacing the one before.
- An app the connector runs (`publish --run`) writes its output to `<slug>.log`, readable
  only by you, in `~/Library/Logs/ovenlight/` on macOS and in the state directory's
  `logs/` elsewhere. It moves to `<slug>.log.1` at 5 MB. What it holds is up to the app,
  and often includes what requests sent it, such as their paths. The app runs in your
  login shell's environment, so what your shell profile exports, API keys included,
  reaches it, and lands in this log if the app prints it. To read that environment the
  connector runs your login shell, and so your profile, each time it starts an app's
  command. When a command restarts on its own, it runs it only while no read has worked,
  at most once every five minutes. Each read ends whatever the profile left running in
  the shell's process group once it is done, and a profile that takes over 10 seconds is
  ended then. `publish` runs it too, to check a new command's programs are found. Beside
  the log, `<slug>.pid` names the app's running process group, so a connector restarted
  after a crash can end what the last one left running.
- The apps' commands are kept in `commands.json` beside the config, readable only by you.
  They run as the connector's children. On macOS they have whatever access to a folder
  macOS protects (Desktop, Documents, iCloud Drive) you allowed the connector, wherever
  they run.
- The logs name guests, devices and your login. `uninstall.sh --purge` (or
  `uninstall.ps1 --purge`) deletes them,
  with node state, the config, the stored API credential, guest records, the feedback
  inbox and the policy backups.

## What goes to Tailscale

- **The connector** keeps tsnet's defaults. Its nodes send backend logs to Tailscale's log
  service, as the Tailscale app does. tsnet may open a NAT-PMP or UPnP port mapping on
  your router for direct connections, and its peer and UDP listeners bind to all
  interfaces.
- **The iPhone app** doesn't upload logs: its embedded Tailscale library is built with
  log upload turned off, for the owner's node and every guest's
  ([development.md](development.md#tailscalekit)).
- `ovenlight doctor`, in a release build (not a pre-release), reads
  `https://downloads.ovenlight.app/connector/latest.json` to say when a newer connector is
  out and to fail when this one has a security problem a newer one fixes. It is a plain
  GET with no cookies, query or identifiers; nothing else in the connector contacts
  ovenlight.app or its subdomains.
- `ovenlight run` clears the environment variables that would set tsnet's login, control
  server or log target, unless `OVENLIGHT_DEV=1`. The apps it runs never get them from
  your login shell.

## The MCP server

`ovenlight mcp` serves `guide`, `status`, `doctor`, `check_app`, `publish`, `unpublish`,
`feedback_list`, `logs` and `restart_app` on stdio for coding agents. It can't run
commands, share an app or grant anyone access. A command runs as you, with your login
shell's environment, so one set through
MCP would hand a client with no shell of its own, such as a desktop chat app, everything
you can do, sharing included. So `publish` keeps the command the connector runs for an
app and can't set one: you set it in a terminal,
`ovenlight publish --slug <slug> --run '<command>'`, or an agent does through its shell,
where you approve the command. Every tool refuses an argument it doesn't take, such as a
`run` or `command` passed to `publish`, rather than ignoring it. Invites are made in a
terminal or in Ovenlight.
Unpublishing a shareable app, moving it to another port, or changing its command or folder needs a
terminal too, since what listens on its port is what guests reach. These terminal-only
steps are what keep the MCP server from sharing. An agent with a shell can do what you
can, as the [threat model](#threat-model) says. `restart_app` works for a shareable app
too, since it restarts the command you set and changes neither it nor the port. When the
connector doesn't confirm an `unpublish`, the tool still succeeds, since the app is out
of the config, and adds a `note` that the connector stops serving it once it confirms,
or when it next starts. `check_app` (like `check`, `doctor` and `status`) asks the app
with its tailnet name as `Host` and your identity headers, as the connector does for
you, or with none while your login isn't known. `doctor` and `status` ask only `GET /`.
`restart` and `restart_app`, once the app answers, also send the files and CORS requests
below, to `127.0.0.1` without identity headers.
`check_app` and `check` ask only `GET` and `OPTIONS`, to `/`, the pages, stylesheets and
icon the app itself names, a few fixed paths (`/api/ovenlight-check`,
`/ovenlight-check-missing`, `/console`), one random path under `/ovenlight-check-`, which
tells a route that answers every path from a served file, and the app's own files, at
most 40 requests (and one more each for the files and the debug pages when the app
refuses its tailnet name, which they then ask without):
the files at the top of the folder the connector runs the app in and the database files
(such as `*.db` and `*.sqlite`) a folder down, by their paths there under `/`, under the
folders of the assets the home page links, and under a few common prefixes such as
`/static/`. To tell a file served from a page, they read each file's first 16 bytes, and
count a shorter file only when the answer is all of it; a file of blank space is left
out. They
read nothing in a folder that holds more than an app's, such as the home folder, or one
macOS protects, and for an app without a folder they ask only `/.env` and
`/package.json`. The CORS and `/console` requests carry no identity headers, going to
`127.0.0.1` as a page in this Mac's browser would. The agent chooses none of them. What guests wrote
in feedback, and the output of the apps the connector runs, reach the agent fenced as
untrusted data: that output holds what requests sent the app, which a guest, or any
website open in this Mac's browser, can choose.

## On the iPhone

- Face ID (or the passcode) locks the whole app once it keeps anything: an app, an
  invite or a node. It locks again after a set time in the background (a minute by
  default), and is asked again before creating an invite and removing a guest. On an iPhone with no passcode there is nothing to ask for: Ovenlight
  stays open, says so in Settings, and those confirmations pass.
- Add App takes only an address on the owner's own tailnet, and none before the owner
  has signed in, so a new app comes from the owner's own computers or from an invite.
  An app of your own goes through the owner's node only when its host is on the owner's
  own tailnet (its MagicDNS suffix, remembered from the node's status); any other
  address, which only an app saved before that rule can have, loads directly, outside
  the tailnet. The owner's node only ever carries names
  on that known tailnet. Until the tailnet is known, a `*.ts.net` app is routed to the
  owner's node and waits behind Sign In, loading nothing, rather than looking the name up
  in public DNS; after sign-in, a name outside the owner's tailnet loads directly. The
  route is checked before every probe and load, and a view whose route changed is made
  again through the right node. The remembered tailnet survives Sign Out and is forgotten
  by Stop Using My Own Computers. A change of it closes every view whose route it
  changed, and Sign Out and Stop Using My Own Computers close every view that went
  through the owner's node, kept in the background or not. A closed view's data store is
  set to a proxy that refuses every connection, and the app loads again through its
  route, at once if it's on screen. A shared app always goes through its owner's guest
  node.
- While Ovenlight is locked, no page gets the microphone or camera, and an app that an
  `ovenlight://open` link asks for waits for the unlock. Locking also stops any
  microphone or camera stream already running in an open app, on screen or kept in the
  background.
- An app's downloads come only from its own host (or a blob or data URL its pages made),
  none while Ovenlight is locked, and leave Ovenlight only through the share sheet. The
  files wait in Ovenlight's temporary folder until the next launch, shared or cancelled,
  since AirDrop may still be sending.
- The owner's admin calls go through the owner's own node to each connector's port 8443.
  The phone holds no API credentials. It asks only the owner's own machines: peers of
  the owner's own user, or tagged `tag:ovenlight-app-*`, on the owner's own tailnet (not
  machines shared in), and trusts a machine's `/v1/apps` answer only for hosts that are
  themselves such peers.
- Each network the phone joins gets its own node, state and random device name
  (`ovenlight-` and six random characters), so two owners can't tell they have the same
  phone as a guest.
- Node state and the membership list are excluded from backups, so node keys never come
  back on another phone.

## If something goes wrong

- **An invite link leaked.** If it's still open, `ovenlight share --cancel <id>`
  withdraws it (`ovenlight guests` lists open invites). If someone else used it,
  `ovenlight guests` lists the phone that joined: remove it with
  `ovenlight revoke <device ID>` and send a new invite.
- **An app's login link leaked.** Whoever opens a pending login link first and signs in
  gets that app's node, in their own tailnet. If it's still pending, sign in with it
  yourself at once. If someone beat you to it, `ovenlight status` shows the app at an
  address in another tailnet, or with another owner: run `ovenlight unpublish <slug>`,
  delete `nodes/<slug>` in the [state directory](../README.md#install-the-connector),
  delete the app's old node in the admin console (Machines) if it's still there, and
  publish the app again, which starts a new node. The node they got never comes back
  online, since its keys are gone.
- **An API token or OAuth client secret leaked.** Revoke it in the Tailscale admin
  console (Settings, Keys; for an OAuth client, Trust credentials) and store a new one
  with `ovenlight auth set`. Then check your tailnet policy, devices and keys for changes
  you didn't make.
- **A lost or stolen iPhone.** Your own: delete its node, an untagged `ovenlight-` device
  of yours, in the admin console (Machines), then look in `ovenlight guests` for invites
  and guests you didn't make. A guest's: `ovenlight revoke <device ID>`, then invite them
  again on their new phone.
- **A stolen computer.** Revoke the API credential and delete the computer's app nodes in
  the admin console (Machines), then set up again on another computer, as for a move
  [without a backup](../README.md#moving-to-a-new-mac). FileVault keeps the node keys and
  the credential unreadable on a Mac that's off, but the automatic login the README
  suggests for a Mac without a display needs FileVault off.
- **An app that may be compromised.** `ovenlight unpublish <slug>`, which also stops the
  command the connector runs for it. It ran as you, with your login shell's environment
  (or your terminal's, if you started it): rotate the secrets there, and the API
  credential, which it could read too.

## Reporting a vulnerability

See [SECURITY.md](../SECURITY.md).
