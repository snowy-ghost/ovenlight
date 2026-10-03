# Protocol

How the Ovenlight iPhone app and the connector talk. Everything runs over the tailnet,
through the phone's embedded node, to an app's node:

- **Port 443**: the app, plus a few paths the connector answers itself.
- **Port 8443**: the owner's admin API.

Both ports check that `Host` is the node's own MagicDNS name and answer 421 otherwise
(on 8443, only after the owner check).

## Compatibility

Phones and connectors update on their own schedules. A new Ovenlight meets connectors
that are already out there, and a guest's phone may be older than the owner's
connector. Each side has to keep working with the other's older builds, in both
directions. So:

- Add fields; never rename or remove them. Readers ignore fields they don't know. The
  phone decodes each list one record at a time and requires only a few fields of each:
  those that identify the record, plus an invite's `state`, `created` and `expires`, a
  guest's `claimedAt` and a feedback item's `at`. A record it can't read costs only that
  record.
- Every answer is a view type, never a stored record (most views are in
  `connector/wire.go`), so key IDs, key hashes and other internal fields never go on the
  wire.
- The files in `connector/testdata/wire/` are the contract: the connector's tests compare
  its answers with them, and Ovenlight's tests decode every one. They cover `apps`,
  `guests`, `invite-created`, `invite-canceled`, `guest-removed`,
  `guest-removed-with-errors`, `feedback`, `feedback-sent`, `claim`,
  `whoami-owner`, `whoami-guest`, `site-manifest` (`/.well-known/ovenlight.json`) and an
  `error-<code>` file for each code the phone reads. `go test -run TestWireFiles -update`
  rewrites them, but refuses to drop a key a file already has unless `-allow-drop` is
  added, which is only for a key no shipped build of Ovenlight needs.
- The phone never acts on an error's text, only on its status and
  [error code](#errors). An answer without a code it knows is an unknown failure, which
  never ends access or deletes anything.
- Put new owner-facing behavior behind the connector's version
  (`Ovenlight-Connector-Version`), so the phone can tell whether a connector has it.
  Guests never get that header, so a guest-facing change needs another signal, such as
  a new field.

## Errors

Every error the connector makes carries a code in the `Ovenlight-Error` header. JSON
errors also carry it in the body:

```json
{"error": "not invited", "code": "not_invited"}
```

The refusals a browser may show as a page ([Other refusals](#other-refusals)) are plain
text with the header only.

| Code | Meaning |
| --- | --- |
| `not_invited` | a guest device with no claim on this app, or a claim with an unknown, expired, canceled or removed invite |
| `used_elsewhere` | the invite was already used by another device |
| `other_person` | the device belongs to another guest |
| `owner_only` | the admin API refused someone other than the owner; on port 443, a caller who is neither the owner nor a guest of this app |
| `unknown_caller` | the node doesn't know the calling device yet; worth another try |
| `not_found` | no such route, invite, guest or screenshot, or a guest name that matches several people |
| `bad_request` | a malformed body, the wrong method, the wrong `Host`, a missing `X-Ovenlight-Request`, a cross-site request, an upgrade other than WebSocket, or a refused invite, a failed Tailscale API call included |
| `rate_limited` | feedback over its rate limit |
| `too_large` | a feedback note or upload over its size limit |
| `unavailable` | the app isn't answering on its computer (a 502), or the connector can't serve the request now |
| `internal` | the connector failed |

A newer connector may send a code the phone doesn't know; the phone then reads the
answer as it would without one.

## Port 443: connector paths

Anything under `/__ovenlight/` is the connector's and never reaches the app; an unknown
path there is 404 `not_found`, in plain text.

### `GET /.well-known/ovenlight.json`

Anyone the connector admits (the owner, a guest who claimed an invite) may read it. It
describes the app for the launcher:

```json
{"name": "Interview Coach", "slug": "interview-coach", "icon": "/icon-512.png", "themeColor": "#1b1b1f", "version": 1}
```

`icon` (a path on the app's own origin) and `themeColor` may be `null`. The phone
ignores an answer with `version` below 1 or an empty `name`. How the connector fills it
in: [app-contract.md](app-contract.md#discovery-name-icon-and-color).

### `POST /__ovenlight/claim`

A guest device binds itself to an invite. Body: `{"key": "<the invite's auth key>"}`.
The connector matches the key's SHA-256 against its open invites, and the calling device
must already carry the app's guest tag: either it joined with that key, or the owner
added the tag to a device the person already has. Claiming again from the same device is
harmless.

| Status | Code | Error text | Meaning |
| --- | --- | --- | --- |
| 200 | | | claimed: `{"name", "userId", "app", "appName", "owner"}` |
| 400 | `bad_request` | | no key in the body |
| 403 | `not_invited` | not invited | unknown, expired, canceled or removed |
| 403 | `used_elsewhere` | this invite was already used by another device | |
| 403 | `other_person` | not invited: this device already belongs to another guest | a device belongs to one person, so it may claim only that person's invites |
| 405 | `bad_request` | POST only | not a POST |
| 500 | `internal` | the connector couldn't record the claim; try again in a moment | always this text, whatever failed |

The owner can't claim (404). A claim is accepted up to an hour after the invite's key
expires, for a device that joined just before.

### `GET /__ovenlight/whoami`

```json
{"role": "owner", "user": "Riley", "userId": "riley@example.com", "app": "interview-coach", "appName": "Interview Coach", "owner": "Riley"}
```

`role` is `owner` or `guest`; `owner` is the label the owner chose. A device that still
carries the app's guest tag but has no claim on it (it never claimed, or the owner
removed it) gets 403 `not_invited`, "You're not invited to this app." The phone probes
this path to learn that a share has ended. Once the device loses the tag, the policy
stops it reaching the node at all. A tagged device without this app's guest tag that
does reach it gets 403 `owner_only`, "This app is private to its owner."

### `POST /__ovenlight/feedback`

Owner and guests. Body:

```json
{"note": "The timer skips a second", "pageUrl": "https://interview-coach.example.ts.net/q/3", "screenshotPngBase64": "..."}
```

A note, a screenshot or both. Answers `{"id"}` on success, 400 (`bad_request`, or
`too_large` over a size limit) for a bad body, 405 `bad_request` for a method other
than POST, 429 (`rate_limited`) over the rate limit or while the same person's previous
upload is still running, and 500 `internal` when the screenshot can't be stored. There
is no 507: a full inbox makes room instead. Limits: [security.md](security.md#feedback).

### Other refusals

Before any of the above, the connector answers with plain text and the code in
`Ovenlight-Error`:

| Status | Code | Text | When |
| --- | --- | --- | --- |
| 421 | `bad_request` | Use this app's tailnet name. | `Host` isn't the node's name |
| 403 | `bad_request` | Cross-site requests are refused. | see [app-contract.md](app-contract.md#cross-site-requests) |
| 403 | `unknown_caller` | Unknown caller. | WhoIs doesn't know the device yet |
| 403 | `not_invited` | You're not invited to this app. | a device with the app's guest tag and no claim |
| 403 | `owner_only` | This app is private to its owner. | anyone else |

Past them, an upgrade other than WebSocket gets 400 `bad_request`, also in plain text.

## Port 8443: the admin API

JSON, for the owner's Ovenlight only: an untagged device of the owner's login (the
recorded one, or else the user who owns the app's node), by WhoIs. Anyone else gets 403
`owner_only`, "the admin API is for the owner only", and the tailnet policy closes the
port to guests as well. Requests that change something need the header
`X-Ovenlight-Request: 1`, which a web page can't add to a cross-site request without a
CORS preflight the API never grants.

| Route | Does |
| --- | --- |
| `GET /v1/apps` | this connector's apps: `slug`, `name`, `url`, `state`, `online`, `shareable`, `guests` |
| `POST /v1/invites` | `{"to": "Sam", "app": "<slug>"}` for someone new; for someone already shared with, `{"person": "<ID>", "app": "<slug>"}`, or `{"to": "Sam", "existing": true, "app": "<slug>"}` by name |
| `DELETE /v1/invites/{id}` | cancel an unused invite |
| `GET /v1/guests` | `guests` (removed ones too, with `removedAt`), `invites`, `unclaimed`, `lastSync`, `syncError`; `ovenlight guests --json` prints the same |
| `DELETE /v1/guests/{ref}` | a device ID or invite ID removes that one device; a person ID or name removes every device and open invite of that person. `?app=<slug>` limits it to one app; `?by=person` reads `ref` only as a person ID |
| `GET /v1/feedback` | the inbox, newest first; `?app=<slug>`, `?limit=<n>`. `ovenlight feedback --json`, the MCP server's `feedback_list` and `status --json` (`sharing.latestFeedback`) use the same view |
| `GET /v1/feedback/{id}/screenshot` | the PNG |

Open invites are the `invites` of `GET /v1/guests`. Any other route or method gets a
JSON 404 `not_found`.

`POST /v1/invites` answers `{"invite": {...}, "link", "appLink", "appName", "owner",
"devices", "tagErrors", "message"}`: `link` is the universal link and `appLink`
the same invite as `ovenlight://join?...`. `message` is the text to send, with the link
in it, as `ovenlight share` prints it. All three carry the key and are shown once.
`message` is the only invite wording the phone sends: it composes none of its own, and
without `message` it sends just the link.
`devices` names the person's devices that can now reach the app, and `tagErrors` those
that couldn't be tagged yet. Invites for App Review can only be made in a terminal.

## The version header

The connector sends its build in `Ovenlight-Connector-Version` on admin API answers and
`whoami` answers to the owner. Guests never see it. `ovenlight status --json` reports it
as `daemonVersion` (its `version` is the command's own binary). The value is a release's
version (such as `1.0.0`), else the module version, else `devel-<commit>`, with
`-modified` when the tree had changes. The phone doesn't read it yet.

## Invite links

`ovenlight share` and `POST /v1/invites` produce the same invite in two forms:

```
https://ovenlight.app/join#v=1&control=&key=&owner=&app=&invite=&host=&name=&to=
ovenlight://join?v=1&control=&key=&owner=&app=&invite=&host=&name=&to=
```

The first is the one to send. Its fields ride in the fragment, which browsers never send
to a server, so the key never reaches the website. It opens Ovenlight when it's
installed; otherwise the page offers Open in Ovenlight (the second form) and a way to
get the app.
The fields are form-encoded (`+` is a space).

| Field | Required | Meaning |
| --- | --- | --- |
| `v` | yes | `1` |
| `control` | yes | the owner's control server; must be `https` (debug builds also take `http` on loopback) |
| `key` | yes | a single-use auth key tagged `tag:ovenlight-guest-<slug>`; ASCII, no whitespace, up to 256 characters |
| `app` | yes | the app's slug; letters, digits, `-` and `_`, up to 64 characters |
| `invite` | yes | the invite's ID on the connector; letters, digits and `-`, up to 64 characters |
| `host` | yes | the app node's MagicDNS name, a plain DNS name |
| `owner` | no | how the owner calls themself |
| `name` | no | the app's name |
| `to` | no | the name the owner gave the person invited |

The phone trims labels (`owner`, `name`, `to`), drops control characters, and keeps at
most 64 characters.

The key is a credential until it is used. It expires after 24 hours (7 days for an App
Review invite).

## Joining, on the phone

1. Ovenlight checks the link (version 1, an https control server, every required field,
   a plain DNS host) and asks "Join Riley's Echo Board?", warning when the control
   server isn't Tailscale's.
2. It starts a guest node with the key and waits for it to run. The key is
   preauthorized, so the node never waits for the owner.
3. It checks that the node is in the network the invite names (its MagicDNS suffix
   matches the part of `host` after the first label). A node this join registered
   elsewhere logs out; one that joined earlier just doesn't claim.
4. It posts the key to `https://<host>/__ovenlight/claim` through that node. An answer
   with the code `used_elsewhere`, `other_person`, `not_invited`, `bad_request` or
   `not_found` is final. Anything else, `unknown_caller` while the connector doesn't
   know the new device yet and any answer without a known code among them, is retried
   for up to 25 seconds; the invite is then kept for Try Again.

A second invite from the same owner (same control server and network) reuses the node
and only claims. Accepted invites are saved until claimed, so a relaunch finishes an
interrupted join.

## When sharing ends, on the phone

Ovenlight takes a shared app away when:

- the guest node loses its login (the owner deleted the device);
- `whoami` answers 403 `not_invited` (checked when the app opens, every minute while
  connected, and on a 403 page). Any other answer from `whoami`, even an error page that
  says "not invited" without the code, counts as reaching the app;
- the app's node has been missing from the guest node's peers for about 3 minutes while
  others are listed. When the owner stops sharing one app with a guest who keeps
  another, the device loses that app's tag and can no longer reach the node to hear
  `not_invited`.

Taking an app away closes it without loading it again, wipes its data store, logs the
node out and deletes its state once no app of that owner is left, and tells the guest
"No Longer Shared with You" once.
