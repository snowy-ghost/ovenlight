# The app contract

What a web app behind Ovenlight must do, and what it may rely on. The connector sits in
front of the app as an HTTPS reverse proxy on the app's own tailnet node.

## Must

### Serve plain HTTP on 127.0.0.1 only

The connector proxies to `http://127.0.0.1:<port>` and nowhere else, so the app speaks
plain HTTP (the connector does TLS) and binds to `127.0.0.1` itself. `localhost` can
resolve to `::1`, which the connector doesn't reach. Never bind to `0.0.0.0` or a LAN
address: anyone who can reach the port directly can send any identity headers they like.
That includes the computer's other accounts even on `127.0.0.1`
([security.md](security.md#threat-model)). `check` fails when the app listens on anything
other than loopback.

### Guard requests that come straight to the port

Loopback still lets in what runs on the computer, a web page in its browser included,
and those requests don't pass the connector. Trust identity headers only when `Host` is
the app's `ts.net` name (which the connector always sends) or `127.0.0.1`/`localhost`,
since a website that points its own name at `127.0.0.1` sends its own `Host`. Send no
CORS headers: they would let another website attach identity headers of its own. Treat a
request without identity headers as the owner, if at all, only with a `127.0.0.1` or
`localhost` `Host` and from the app's own pages or from no page at all
(`Sec-Fetch-Site` of `same-origin` or `none`, or, without it, no `Origin` or the app's
own).
[building-apps.md](building-apps.md#who-is-calling) has an example.

### Answer GET / with a page or a redirect

Ovenlight opens the app at `/`. `doctor` fails the app unless a plain `GET /` answers
with a 2xx or 3xx status, and `status` then shows it as not answering. Once the app's
node has a name, both send it as `Host`, as the phone's requests do, so a dev server
that refuses unknown hosts fails here too: allow `.ts.net` (in Vite,
`server.allowedHosts`). The connector also reads `/` and the web manifest without any
identity headers (see [Discovery](#discovery-name-icon-and-color)), so they must not
require a signed-in user. `ovenlight check <slug>` goes further: redirects, the
viewport, the icon and the color.

### Make GET change nothing

The connector refuses most requests another website makes on a visitor's behalf (see
[Cross-site requests](#cross-site-requests)), but a plain link from another site still
reaches the app as a top-level GET or HEAD navigation. So a GET or HEAD must never
change state: no "delete on GET", no one-click actions behind a link.

### Key user data by Ovenlight-User-Id

Use `Ovenlight-User-Id` as the key for anything that belongs to a person. It is stable
for the owner and for every device of one guest. The other headers are labels.

## May rely on

### Identity headers

Every incoming `Tailscale-*`, `Ovenlight-*` and `X-Ovenlight-*` header is dropped,
underscore spellings included, and then set by the connector from Tailscale's WhoIs for
the calling device. Only the owner and invited guests reach the app; everyone else gets
403 from the connector.

| Header | Owner | Guest |
| --- | --- | --- |
| `Ovenlight-Role` | `owner` | `guest` |
| `Ovenlight-User-Id` | the owner's Tailscale login | `guest:<person ID>`, the same on every device of theirs |
| `Ovenlight-User` | the owner's display name, or login | the name the owner gave them when inviting (a label) |
| `Tailscale-User-Login` | the owner's login | not sent |
| `Tailscale-User-Name` | the owner's display name | not sent |

Values that aren't plain ASCII are RFC 2047 encoded, as `tailscale serve` does.

### Forwarding headers

Incoming `X-Forwarded-*`, `X-Real-IP` and `Forwarded` are dropped (underscore spellings
too), and the connector sets `X-Forwarded-For`, `X-Forwarded-Host` and
`X-Forwarded-Proto`. The request keeps its tailnet `Host`
(`<slug>.<tailnet>.ts.net`).

### HTTPS, streaming and WebSockets

The node serves HTTPS on port 443 with its real `ts.net` certificate. Responses stream,
and WebSocket upgrades are proxied. Any other protocol upgrade gets 400.

### Cross-site requests

From a browser, only these reach the app: requests from the app's own pages, URLs typed
into the address bar, and top-level navigations from another site (a link, GET or HEAD).
Anything else another site sends, reads, frames, WebSocket upgrades and preflights
included, gets 403. The connector decides by `Sec-Fetch-Site`, or by `Origin` when a
browser sends no fetch metadata.

### Paths the app never sees

The connector answers these itself:

- anything under `/__ovenlight/` (claims, feedback, whoami; see
  [protocol.md](protocol.md));
- `/.well-known/ovenlight.json`.

## Discovery: name, icon and color

Ovenlight finds the owner's published apps by fetching `/.well-known/ovenlight.json`
from each of the owner's online machines. The connector builds that answer from the
app's home page (`/`) and its web manifest:

- **Name and slug** come from `ovenlight publish`.
- **Icon**: the raster icon in the manifest's `icons` closest to 512 px (of two equally
  close, the larger), skipping SVG, which iOS can't use. Without one, the page's
  `<link rel="apple-touch-icon">`.
- **Theme color**: the manifest's `theme_color`, else the page's first
  `<meta name="theme-color">`.

The manifest is found through `<link rel="manifest">` on the home page. The connector
fetches from `http://127.0.0.1:<port>`, so the manifest and icon URLs must be relative
(`/icon-512.png`, `icons/app.png`); an absolute URL, even one naming the app's `ts.net`
host, is ignored.

The connector reads all this when the app's node starts and when its config reloads,
and again only after a failed read. After changing the icon or color, run
`ovenlight publish` again (on macOS and Linux, `kill -HUP` on the connector works too).

None of this is required: an app without it is still listed, by name.

## In the iPhone app

- Each app runs full screen in its own WKWebView with its own website data store.
- **User agent**: WebKit's own, ending in `Ovenlight/<version>` (for example
  `... Mobile/15E148 Ovenlight/1.0`), so a page can tell it runs in Ovenlight and still
  looks like mobile Safari. Only the page's own requests carry it, not Ovenlight's
  reachability checks and icon fetches.
- **Downloads**: an `<a download>` link, a blob or data URL download, and a page load
  answered with `Content-Disposition: attachment` or a type WebKit can't show all open the
  share sheet, where the person can save the file to Files or send it on. Only the page
  itself downloads: a frame loads such a response as WebKit would, never raising the share
  sheet. Downloads come only from the app's own host, and are refused while Ovenlight is
  locked.
- **Service workers and offline copies** work only on the tailnets listed in the iPhone
  app's `WKAppBoundDomains`, and the App Store build lists one tailnet. Apps on any other
  tailnet get neither, and Open Offline Copy doesn't appear for them (see
  [development.md](development.md#app-bound-domain)).
- **Microphone**: the owner's own apps (discovered, or on one of the owner's machines),
  on their own origin, get it without a second prompt (iOS still asks once). The
  camera, shared apps and any other app get WebKit's prompt. While
  Ovenlight is locked, no page gets either, and locking stops any stream already
  running.
- **Other websites**: a main-frame navigation to any other host opens in a Safari sheet,
  not in the app's view. `window.open` and `target=_blank` to the app's own host load in
  the same view; no second window opens. So sign-in flows that redirect through another
  site, or rely on a popup and `window.opener`, don't return to the app.
- **Other apps**: a page can't open `ovenlight://` links directly. Other apps open only
  from a link activated in the page's main frame, and Ovenlight first asks "Open in
  Another App?" naming the link, except for `tel:`, `facetime:` and `facetime-audio:`,
  where iOS asks.

## Feedback

The app needs to do nothing. Send Feedback in Ovenlight takes a snapshot of the page,
adds an optional note, and posts both to the connector, which keeps them in the owner's
inbox. The owner reads it with `ovenlight feedback`, in `ovenlight status`, in
Ovenlight's People & Sharing, or through the MCP server. Limits are in
[security.md](security.md#feedback).
