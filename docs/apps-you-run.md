# Apps you already run

Any web app that serves plain HTTP on `127.0.0.1` can sit behind Ovenlight, including the
ones you already self-host. Publish its port, and it opens on your phone full screen, from
its own icon, off the public internet. This page has recipes for five apps, each tested
with the connector's own proxy code and the app in Docker Engine on Linux.

| App | You sign in | Guests sign in | Tested version |
|---|---|---|---|
| [Home Assistant](#home-assistant) | with its own login | with accounts of their own | 2026.10.4 |
| [Grafana](#grafana) | with its own login | with accounts of their own | 13.2.4 |
| [Miniflux](#miniflux) | with its own login | with accounts of their own | 2.3.4 |
| [Open WebUI](#open-webui) | with its own login | with accounts of their own | 0.12.4 |
| [Paperless-ngx](#paperless-ngx) | by header | by header | 3.3.4 |

## Any app

- **Use Docker Engine 28 or later.** In earlier releases, other hosts on your local
  network could reach ports published to `127.0.0.1`
  ([Docker's docs](https://docs.docker.com/engine/network/port-publishing/)).
- **Publish the port on `127.0.0.1` only.** In Docker that's `-p 127.0.0.1:3000:3000`.
  A bare `-p 3000:3000` listens on every address, so anyone on your network could reach
  the app without Ovenlight.
- **Start the app, then publish it.** `ovenlight publish --port 3000 --name "Grafana"`
  gives it its own address, `https://grafana.<your-tailnet>.ts.net`. Docker keeps the
  container running, so there's no `--run`.
- **Give the app its address.** The connector passes the app's tailnet name as `Host`
  and sends `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`. An app that
  builds links or checks its host name needs `https://<slug>.<your-tailnet>.ts.net` as its
  base URL. WebSockets pass through.
- **Check the app's name first.** Before you run a recipe, look for a device with the
  app's name on the Tailscale admin console's
  [Machines](https://console.tailscale.com/admin/machines) page, and rename or remove it.
  Otherwise Tailscale adds a suffix, such as `paperless-1` (`ovenlight status` shows it).
  An app set to the plain name then builds wrong links, and Paperless-ngx turns away every
  request.
- **Sign in as you do now.** You open the app in Ovenlight and use its own login. Guests
  need accounts in the app too, unless it can sign them in by header (below).
- **Share it like any app.** Make it shareable with `ovenlight publish --slug <slug>
  --shareable`, then invite people with `ovenlight share` or from your phone
  ([Share an app](connector.md#share-an-app)).
- **Set the app up before you share it.** On a fresh install, whoever gets in first
  becomes its admin: Open WebUI makes its first account the admin, Grafana starts with
  `admin` as both login and password, and Home Assistant's first visitor to onboarding
  creates the owner.
  Create your own admin account, or change Grafana's default password, before you make
  the app shareable.

## Home Assistant

These steps are for Home Assistant's container install. Home Assistant answers `400` to
every request through Ovenlight until it trusts the connector as a proxy, since the
connector always sends `X-Forwarded-For`.

- **With host networking** (`docker run --network=host`, as Home Assistant's install
  docs show), it listens on every address unless told otherwise. Set Listen addresses to
  `127.0.0.1`, turn on Trust X-Forwarded-For and add `127.0.0.1` to Trusted proxies.
- **Bridged, with `-p 127.0.0.1:8123:8123` only**, turn on Trust X-Forwarded-For and add
  the Docker network's gateway to Trusted proxies. `docker network inspect` shows it.

**Listening on `127.0.0.1` cuts Home Assistant off from your network.** That includes
the Companion app at home, devices that push to its webhooks, and speakers such as Cast
or Sonos that fetch text-to-speech and media from it. To keep those, use host networking
on Linux and leave Listen addresses empty (`server_host` out). Home Assistant then
answers on your network as it does today, behind its own login, and takes
`X-Forwarded-For` only from `127.0.0.1`. In testing, a forged one from another address
got `400`. `ovenlight doctor` then fails its bind check and advises listening on
`127.0.0.1`. That's expected here, since Home Assistant keeps its own login.

Since 2026.8, these settings are under Settings, System, Network, HTTP server
([Home Assistant's docs](https://www.home-assistant.io/integrations/http/)). Saving
restarts Home Assistant on them as a five-minute trial. Within those five minutes, open
it again and choose Confirm when it asks you to confirm the new HTTP server
configuration, or it goes back to the old settings. With host networking, open it at
`http://127.0.0.1:8123` in a browser on your computer, since it now listens there only.
On a server without a browser, open an SSH tunnel from your laptop first, with
`ssh -L 8123:127.0.0.1:8123` and the server's name, and open that address there.

Then publish it with `ovenlight publish --port 8123 --name "Home Assistant"`. You sign in
as usual, Ovenlight shows its icon, and the WebSocket its pages use works. Home Assistant
has no header sign-in, so a guest needs a Home Assistant account of their own.

With host networking, a bridged container couldn't reach Home Assistant. The bridged setup
was tested on Colima.

- **Don't publish a bridged container on every address.** On Colima, a connection to the
  Mac's network address arrived from the same gateway as one from `127.0.0.1`. With that
  gateway trusted, any device on your network could send its own `X-Forwarded-For` and
  get past Home Assistant's IP bans.
- **Don't combine these settings with the `trusted_networks` login.** With Trust
  X-Forwarded-For on, Home Assistant takes the caller's address from `X-Forwarded-For`
  when the request comes from a trusted proxy, and doesn't check `Host`
  ([forwarded.py](https://github.com/home-assistant/core/blob/2026.10.4/homeassistant/components/http/forwarded.py)).
  `trusted_networks` then trusts that address
  ([trusted_networks.py](https://github.com/home-assistant/core/blob/2026.10.4/homeassistant/auth/providers/trusted_networks.py)).
  So a page on your computer could claim an address on your network and skip the login,
  and a guest, who arrives from a tailnet address in `100.64.0.0/10` or
  `fd7a:115c:a1e0::/48`, gets in wherever a trusted network covers it. This comes from
  Home Assistant's source and wasn't tested.

### On a new install

On a new install with host networking, you can set Listen addresses, Trust X-Forwarded-For
and Trusted proxies in its configuration before its first start instead. Home Assistant
writes its default `configuration.yaml` only when there's none, so write the whole file,
its defaults plus the `http:` block, in the folder you mount at `/config`:

```yaml
default_config:

frontend:
  themes: !include_dir_merge_named themes

automation: !include automations.yaml
script: !include scripts.yaml
scene: !include scenes.yaml

http:
  server_host: 127.0.0.1
  use_x_forwarded_for: true
  trusted_proxies:
    - 127.0.0.1
```

Next to it, create `automations.yaml` containing `[]`, and empty `scripts.yaml` and
`scenes.yaml`. Without them, Home Assistant failed to read the file and started in
recovery mode on every address. With them, it loaded its defaults, including the mobile
app integration the Companion app needs.

Home Assistant imports the block on that first start as the same five-minute trial. So
within five minutes, open `http://127.0.0.1:8123` in a browser on your computer (or
through the SSH tunnel above, on a server without one), create the owner account and
choose Confirm. If you miss the five minutes, Home Assistant goes back to
listening on every address and ignores the block from then on, and its onboarding is
open to your whole network, where whoever gets there first creates the owner. Set the
same values in Settings then, and confirm them. Home Assistant reads an `http:` block only
on a new install's first start, or its first start after upgrading to 2026.8 or later.
Then publish it as above.

## Grafana

Grafana can take its user from a header, but it reads the header before it checks the
address, so a page on your computer could change its accounts. Leave `GF_AUTH_PROXY_ENABLED`
off and sign in with its own login. Set `GF_SERVER_ROOT_URL` to
`https://grafana.<your-tailnet>.ts.net/`, or the links Grafana builds point to
`http://localhost:3000`. Publish the port on `127.0.0.1`, for example
`-p 127.0.0.1:3000:3000`, then run `ovenlight publish --port 3000 --name "Grafana"`.
Signing in, Grafana's icon in Ovenlight and its live updates over a WebSocket all worked
through the connector.

## Miniflux

Miniflux can take its user from a header, but it signed in a forged header under any
`Host`, so leave `AUTH_PROXY_HEADER` unset and sign in with its own login. Set `BASE_URL`
to `https://miniflux.<your-tailnet>.ts.net/`, then publish the port you gave it, for
example `ovenlight publish --port 8080 --name "Miniflux"`. Signing in through the
connector worked as it does in a browser.

## Open WebUI

Open WebUI can sign people in from the header `WEBUI_AUTH_TRUSTED_EMAIL_HEADER` names. The
connector sends your login as `Tailscale-User-Login`, so setting
`WEBUI_AUTH_TRUSTED_EMAIL_HEADER` to `Tailscale-User-Login` would sign you in. Leave the
variable unset: Open WebUI trusts that header under any `Host` and from any address. In
testing, a forged header under a foreign `Host` and one from another container were both
signed in as you.

Set `WEBUI_URL` to `https://open-webui.<your-tailnet>.ts.net` instead, publish the port you
gave it, for example `ovenlight publish --port 8081 --name "Open WebUI"`, and use its own
login. Open WebUI reads `WEBUI_URL` only on its first start, so on an existing install,
change WebUI URL in its admin panel's settings
([Open WebUI's docs](https://docs.openwebui.com/reference/env-configuration/)). Signing in
and its WebSocket both worked through the connector.

## Signing people in by header

The connector tells the app who is calling: `Ovenlight-User-Id` is your Tailscale login,
or `guest:` and a person ID for a guest, and `Ovenlight-User` is a name to show. An app
that can take its user from a header signs everyone in without accounts of their own. Of
these five, Paperless-ngx is the one that can do it safely, and only when all of these
hold:

- **The app refuses a `Host` other than its tailnet name before it reads the header.** A
  web page open on your computer can point a name of its own at `127.0.0.1` and send the
  header itself. The connector always sends the tailnet name. Paperless-ngx accepts only
  the names you give it, plus `localhost`. Grafana reads the header before that check,
  and Miniflux and Open WebUI take it under any name, so their recipes leave header
  sign-in off.
- **Nothing else on your network reaches the app.** On Docker Engine 28 or later on
  Linux, containers on other networks couldn't reach a port published on `127.0.0.1`.
  Docker's routed and nat-unprotected gateway modes and its `allow-direct-routing` option
  let other hosts reach container ports directly, so leave them off. Any container on the
  app's own network reaches it directly, so the app gets a network of its own. That keeps
  other containers off the app's port only. A container on any device signed in to
  Tailscale as you, your computer or another one such as a NAS, can reach the app through
  your tailnet, where the connector sees that device and signs it in as you
  ([Security](security.md#threat-model)). This comes from the connector's source and
  wasn't tested.
- **No one else has an account on your computer, and you run nothing there you don't
  trust.** Anything on your computer that can open `127.0.0.1` and the app's port, such
  as another user, a script or a container with `--network host`, can send the right
  `Host` and header and be signed in as you. In testing, a `--network host` container was.
  The [app contract](app-contract.md#guard-requests-that-come-straight-to-the-port) has
  the same rule.

### On a Mac

Docker on a Mac runs containers in a Linux VM, which can reach your Mac's `127.0.0.1`. In
testing with Colima, a container on another network reached an app signed in by header
through `host.docker.internal`, arrived from the app network's gateway with the `Host` it
chose, and was signed in as you. So with Docker Desktop, Colima or OrbStack, leave header
sign-in off and use each app's own login.

## Paperless-ngx

This recipe is for Docker Engine 28 or later installed on Linux itself (not Docker Desktop
for Linux), with Docker's default network settings. Its header sign-in is safe only there.
Anywhere else, such as [on a Mac](#on-a-mac), leave out the recipe's
`PAPERLESS_ENABLE_HTTP_REMOTE_USER` and `PAPERLESS_HTTP_REMOTE_USER_HEADER_NAME` lines and
use Paperless's own login: set the admin's password with the command in
[The admin password](#the-admin-password), and give each guest an account of their own.
With header sign-in, you're signed in as Paperless's admin. A guest is signed in as a new
user named `guest:` and their person ID, which `ovenlight guests` lists under PERSON. That
user sees nothing until you add it to a group
([Giving guests permissions](#giving-guests-permissions)).

Paperless takes the header from anything that reaches it, so it gets a network shared
only with its Redis (and its database, if it uses one). A container on that network was
signed in as you, and one on another network couldn't reach its port. Neither network
stops a container that comes through your tailnet (the conditions above).

The recipe uses two shell variables. Set `TAILNET` to your tailnet's name,
as the Tailscale admin console's [DNS](https://console.tailscale.com/admin/dns) page shows
it, for example `export TAILNET=tail1234.ts.net`. Set `OWNER_LOGIN` to the login you use
for Tailscale, for example `export OWNER_LOGIN=riley@example.com`. A block run without
them stops with a message instead of setting up the app wrong.

```sh
test -n "${TAILNET:?set TAILNET first}" && test -n "${OWNER_LOGIN:?set OWNER_LOGIN first}" &&
PL_SECRET="$(openssl rand -base64 48)" && PL_ADMIN_PW="$(openssl rand -base64 24)" &&
docker network create paperless &&
docker run -d --name paperless-redis --restart unless-stopped --network paperless redis:7-alpine &&
docker run -d --name paperless --restart unless-stopped --network paperless \
  -p 127.0.0.1:8000:8000 \
  -v paperless-data:/usr/src/paperless/data -v paperless-media:/usr/src/paperless/media \
  -e PAPERLESS_SECRET_KEY="$PL_SECRET" \
  -e PAPERLESS_REDIS=redis://paperless-redis:6379 \
  -e PAPERLESS_URL="https://paperless.$TAILNET" \
  -e PAPERLESS_ALLOWED_HOSTS="paperless.$TAILNET,127.0.0.1" \
  -e PAPERLESS_USE_X_FORWARD_HOST=false \
  -e PAPERLESS_WEBHOOKS_ALLOW_INTERNAL_REQUESTS=false \
  -e PAPERLESS_ENABLE_HTTP_REMOTE_USER=true \
  -e PAPERLESS_HTTP_REMOTE_USER_HEADER_NAME=HTTP_OVENLIGHT_USER_ID \
  -e PAPERLESS_ADMIN_USER="$OWNER_LOGIN" \
  -e PAPERLESS_ADMIN_PASSWORD="$PL_ADMIN_PW" \
  ghcr.io/paperless-ngx/paperless-ngx:3.3.4 &&
ovenlight publish --port 8000 --name "Paperless"; unset PL_SECRET PL_ADMIN_PW
```

- `PAPERLESS_ALLOWED_HOSTS` lists the names Paperless accepts, and Paperless adds
  `localhost` itself. Every other `Host` gets `400`, and a forged header under one created
  no user. Under `localhost` or `127.0.0.1` a forged header was signed in, which only
  something already on your computer can send (the third condition above). `127.0.0.1` is
  in the list so the connector can read the home page for Ovenlight's listing.
- `PAPERLESS_USE_X_FORWARD_HOST=false` keeps that check on `Host`. With it on, Paperless
  takes the name from `X-Forwarded-Host`, which a page can send too: in testing, a forged
  header under a foreign `Host` was then signed in as you. The connector keeps the tailnet
  name in `Host`, so Paperless doesn't need forwarded hosts.
- Copy `OWNER_LOGIN` exactly as Tailscale shows it. With SQLite, as here, or Postgres,
  Paperless matches the user name case for case, so a login typed in another case signs
  you in as a new user with no permissions. The way back is below. (MariaDB's default
  collation ignores case.)
- A guest you let view documents sees every document without an owner, and documents
  from the consume folder have none. Give guests only the permissions in
  [Giving guests permissions](#giving-guests-permissions).
- `PAPERLESS_WEBHOOKS_ALLOW_INTERNAL_REQUESTS=false` stops workflow webhooks from calling
  internal addresses, Paperless's own port among them, which it accepts under
  `localhost`. Paperless allows them by default. It also blocks your own webhooks to
  services at home or on your tailnet, such as Home Assistant or ntfy.
- `PAPERLESS_ADMIN_USER` does nothing once any superuser exists, so on an existing
  Paperless the header would sign you in as a new user with no permissions. Before you
  turn header sign-in on, rename your superuser to your Tailscale login in Settings,
  Users & Groups. Then set it a new password no one else knows, with the command in
  [The admin password](#the-admin-password), since its old one still signs in through
  Paperless's API.
- Then run your existing Paperless with exactly this recipe's settings, keeping your own
  secret key. The only other `PAPERLESS_` settings to keep are its database ones
  (`PAPERLESS_DBHOST` and the rest), its OCR ones (`PAPERLESS_OCR_LANGUAGE` and the rest)
  and `PAPERLESS_TIME_ZONE`. Others can undo the recipe: `PAPERLESS_AUTO_LOGIN_USERNAME`
  signs every request to Paperless's API in as that user, guests' included, and
  `PAPERLESS_EMAIL_HOST` lets guests send email
  ([Giving guests permissions](#giving-guests-permissions)).
- Publish its port on `127.0.0.1` only, and give it a network holding only Paperless, its
  Redis and its database (Postgres or MariaDB, if it uses one). That means giving up
  Gotenberg and Tika, which would share its network, so Paperless no longer reads Office
  documents and email files. Paperless's stock compose files publish the port on every
  address, and the `-tika` ones put Gotenberg and Tika on its network, so as they stand
  they break the conditions above.

To check that Paperless keeps the first condition, send it a made-up ID under a foreign
`Host`, with `X-Forwarded-Host` set to its tailnet name, straight to its port. This prints
`400`, then `False`:

```sh
test -n "${TAILNET:?set TAILNET first}" && curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: evil.example' -H "X-Forwarded-Host: paperless.$TAILNET" -H 'Ovenlight-User-Id: probe-check' http://127.0.0.1:8000/ && docker exec --user paperless paperless python3 manage.py shell -v 0 -c "from django.contrib.auth.models import User; print(User.objects.filter(username='probe-check').exists())"
```

The `400` shows Paperless kept your data from a foreign page. The `False` shows it made no
user named `probe-check`, so it didn't act on the header before answering.

[Paperless-ngx's configuration docs](https://docs.paperless-ngx.com/configuration/)
cover the rest.

### Giving guests permissions

Paperless's user editor refuses guest names, since they contain a colon: saving one
answers "Enter a valid username". So give guests their permissions through a group. In
Settings, Users & Groups, create a group named `Guests`. Tick only the View box, on each
of these rows and no others: Document, UISettings, Tag, Correspondent, DocumentType,
StoragePath, CustomField and SavedView. With those, a guest's Paperless loaded its
dashboard, document list and document pages with no errors. With View on Document alone,
it stopped at "Error loading settings". Other permissions do more than show documents:
those on users, groups and application configuration let a guest raise their own access
or reach admin settings, and those on workflows and mail accounts make Paperless send
requests of its own.

From Paperless's source at v3.3.4, View on Document carries four things further:

- A guest can email the documents they see to any address, with their own subject and
  message, through the mail server Paperless sends from. Leave `PAPERLESS_EMAIL_HOST`
  unset while guests sign in by header. The recipe sets none.
- A guest can read your notes on a document they see through Paperless's API, whatever
  the Note permission.
- Custom fields have no owner, so guests see every field's name and options, though only
  the values on documents they can see.
- With AI turned on in Paperless's settings, a guest can chat about the documents they
  see through your language model, at your cost.

Tags, correspondents, document types and storage paths you create belong to you, so
guests don't see them unless each one's permissions say so. In testing, a guest saw a
document but not its tag.

Once a guest has opened Paperless, add them to the group from the shell. Set
`GUEST_PERSON` to their person ID, from the PERSON column of `ovenlight guests`, for
example `export GUEST_PERSON=3f9a1c07e2`. This prints `added`:

```sh
test -n "${GUEST_PERSON:?set GUEST_PERSON first}" && docker exec --user paperless -e GUEST_PERSON="$GUEST_PERSON" paperless python3 manage.py shell -v 0 -c "import os; from django.contrib.auth.models import Group, User; User.objects.get(username='guest:' + os.environ['GUEST_PERSON']).groups.add(Group.objects.get(name='Guests')); print('added')"
```

Change the group's permissions in Settings later, and every guest in it follows.

### The admin password

The admin password is random because you never type it: Ovenlight signs you in. If you
ever need it, set a new one. This asks for it twice:

```sh
test -n "${OWNER_LOGIN:?set OWNER_LOGIN first}" && docker exec -it --user paperless paperless python3 manage.py changepassword "$OWNER_LOGIN"
```

If you set `OWNER_LOGIN` in a different case from Tailscale's, run that command with the
value you used, which is the admin's name. Then open `http://localhost:8000` in a browser
on your computer, where no header is sent (on a server without one, open an SSH tunnel
from your laptop with `ssh -L 8000:127.0.0.1:8000` and the server's name, and open it
there), and sign in as the admin with the new
password. In Settings, Users & Groups, delete the empty user named with your exact
login, then rename the admin to it.
