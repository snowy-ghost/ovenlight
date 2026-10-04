# Development

## Layout

- `connector/`: the `ovenlight` Go program. `internal/policy` edits tailnet policy files,
  `internal/tsapi` talks to the Tailscale API, and `e2e/` holds the end-to-end sharing
  test.
- `project.yml`, `Ovenlight/`, `OvenlightTests/`: the iPhone app.
- `scripts/build-tailscalekit.sh`: builds the embedded Tailscale library.
- `scripts/release-connector.sh`: builds a connector release, and
  `scripts/publish-connector.sh` publishes it.
- `design/icon/generate.py`: writes the app icon, and the site's `icon.svg` and
  `icon-180.png`.
- `.claude-plugin/marketplace.json`, `plugins/ovenlight/`: the Claude Code plugin (the MCP
  server and a skill that points to `ovenlight guide`). Check it with
  `claude plugin validate .`.
- `design/og/generate.py`: writes the link preview image, `site/og-invite.jpg`.

## The connector

Build and install it from a checkout with `connector/install.sh`, or
`connector\install.ps1` on Windows (see
[connector.md](connector.md#from-source)). The minimum Go version is the `go` line
in `connector/go.mod`.

- **One node per app.** Each published app gets its own tsnet node named after its slug,
  with state in `nodes/<slug>/` in the state directory (connector.md's
  [table](connector.md#what-it-installs) has it for each system). The commands the
  connector runs for apps are in `commands.json` beside the app list, where a connector
  from before `--run`, which saves the config without them, can't drop them. Loading the
  config drops the command of an app it no longer lists, as such a connector's
  `unpublish` leaves it. `publish` edits both and reloads the running connector over its
  control socket, as does `kill -HUP` on macOS and Linux.
- **Per-system code** sits in files named for it: `lock_*.go` (flock, or LockFileEx on
  Windows), `listen_*.go` (what listens on an app's port, for `doctor`),
  `internal/jsonfile/private_*.go` (files only you can read: mode 0600, or on Windows an
  access list that grants only you; there, saving, reading and removing also wait a moment
  while another process has the file open), `peer_*.go` (on Windows, refuses control
  connections from another user) and `supervise_*.go` (the process groups of the apps
  the connector runs).
- **Global flags.** Commands take `--config <file>` and `--state <dir>`.
- **The building guide.** `ovenlight guide` and the MCP `guide` tool serve
  `connector/building-apps.md`, a copy of [building-apps.md](building-apps.md) that Go
  can embed. After editing the doc, run `go generate` in `connector/`; a test fails
  while the two differ.
- `ovenlight version` prints the build: a release's version, else the module version,
  else `devel-<commit>`, with `-modified` when the tree had changes.

### Tests

```sh
cd connector && go test ./...
```

The unit tests include the Tailscale API client and `setup-sharing` against an in-memory
fake of the API. `TestWireFiles` compares the connector's answers with the files in
`testdata/wire/`, which Ovenlight's tests decode too
([protocol.md](protocol.md#compatibility)); `go test -run TestWireFiles -update`
rewrites them, for a change Ovenlight still reads. It refuses to drop a key a file
already has unless `-allow-drop` is added too, which is only for a key no shipped build
of Ovenlight needs.

The end-to-end sharing test runs against a throwaway local Headscale, on macOS or Linux
(CI runs it on Linux). It passes against Headscale v0.29.4 (`go install github.com/juanfont/headscale/cmd/headscale@v0.29.4`):

```sh
cd connector && OVENLIGHT_E2E_HEADSCALE=/path/to/headscale go test -tags e2e ./e2e -v -timeout 15m
```

CI (`.github/workflows/ci.yml`) runs on every push and pull request, and weekly, on Linux
and Windows: `go vet` (with and without the `e2e` tag), the tests (with `-race` on Linux)
and `govulncheck`, then the install scripts as a user runs them: install, a killed
connector restarting, reinstall and `uninstall --purge`. On Linux it also runs `gofmt`,
`go vet` and `govulncheck` for darwin/arm64 with cgo off, and the end-to-end test against
Headscale. The iPhone app's side of the wire files runs only in Xcode, locally and before
shipping, never in CI, so a change to `testdata/wire/` must also pass the
[iPhone app's tests](#tests-1).

### Releases

```sh
scripts/release-connector.sh 1.0.0       # or --unsigned 1.0.0 to skip all signing
scripts/release-connector.sh --no-windows 1.0.0   # without the Windows archives, only until the first Windows release
scripts/publish-connector.sh 1.0.0 1.0.0 # the version, then the oldest secure one
git tag -s connector-v1.0.0 <commit>     # then tag the commit it was built from
git push origin connector-v1.0.0
```

`release-connector.sh` writes the release to `build/release/`, replacing the one before:

- `ovenlight-connector-macos.tar.gz`: a universal (arm64 and x86_64) binary for macOS 13
  or later, signed with the Developer ID and notarized, with `install.sh`, `uninstall.sh`
  and the launchd plist;
- `ovenlight-connector-linux-amd64.tar.gz` and `-linux-arm64.tar.gz`: static binaries,
  with `install.sh`, `uninstall.sh` and the systemd unit;
- `ovenlight-connector-windows-amd64.zip` and `-windows-arm64.zip`: with `install.ps1`
  and `uninstall.ps1`, the binary and both scripts signed with Azure Artifact Signing;
- `SHA256SUMS`, and `SHA256SUMS.sig`, its signature by git's SSH signing key
  (`user.signingkey`), which must be one of the keys in
  [allowed_signers](allowed_signers).

Each archive holds an `ovenlight-connector-<version>` folder, with `LICENSE`, `NOTICE` and
`THIRD_PARTY_NOTICES.txt`, the licenses of what that system's build links. The archive
names carry no version, so the latest release keeps the same URLs. It builds with the Go
version on `connector/go.mod`'s `go` line (through `GOTOOLCHAIN`), as CI does. Unless
`--unsigned`, it builds only a clean `HEAD` that is on `origin/master`, and checks with
`go version -m` that each binary carries that commit. Run it in a clone: go1.26 stamps no
commit in a git worktree.

Notarization uses an App Store Connect API key, named by the environment variables
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_PATH`. Over SSH, unlock the login keychain
first. Windows signing needs `jsign` (`brew install jsign`) and the Azure CLI
(`brew install azure-cli`), signed in with `az login` as someone with the Artifact Signing
Certificate Profile Signer role on the certificate profile that `WIN_PROFILE` in the
script names.

Try the Windows build on a PC with Smart App Control on before publishing.
`publish-connector.sh` uploads the release to the R2 bucket behind
`https://downloads.ovenlight.app`, through `wrangler` (signed in with
`npx wrangler@4.147.0 login`, or `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`):

- `connector/<version>/`: the release's files, never replaced;
- `connector/latest/`: the same files, replaced by each release, which the install commands
  and download links in the docs point to;
- `connector/latest.json`: `{"latest": "<version>", "secure": "<secure>"}`. `ovenlight doctor`
  reads it to tell people about updates, and fails while they run a version older than
  `secure`, so raise `secure` with any release that fixes a security problem.

If a release goes wrong, roll forward with a fixed one, raising `secure` if it fixes a
security problem. `publish-connector.sh` won't move `latest` back, so restoring an older
release is done by hand: upload `connector/<good version>/`'s files to `connector/latest/`
with `latest/`'s `Cache-Control: public, max-age=300`, as `publish-connector.sh` puts them
(a copy would keep the version's year-long cache), point `latest.json` at that version,
then wait out the caches.

## The iPhone app

The Xcode project is generated. Edit `project.yml`, then run `xcodegen generate`; the
`.xcodeproj` is committed. Minimum iOS is 18.1, TailscaleKit's minimum.

### TailscaleKit

`scripts/build-tailscalekit.sh` builds `Frameworks/TailscaleKit.xcframework` from
`tailscale/libtailscale` at a pinned commit. Run it once before building in Xcode. The
output is gitignored and cached; `--force` rebuilds. It needs Go, and fetches the
`go1.26.8` toolchain itself. On top of upstream it:

- builds with the `ts_omit_debug` tag, which drops the node's debug LocalAPI, pprof and
  debug HTTP handlers;
- turns off log upload to `log.tailscale.com` from a Go `init`, for every node the app
  runs, guests' included;
- raises a few `golang.org/x` modules for security fixes;
- adds a privacy manifest to the framework, which Apple requires of a framework that
  calls a required-reason API.

### Tests

```sh
scripts/build-tailscalekit.sh && xcodebuild test -scheme Ovenlight -destination "platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5"
```

### Debug launch arguments

Debug builds take these as launch arguments:

- `-openApp "Interview Coach"` opens an app directly. `ovenlight://open?app=<name>` does
  the same on a device, in any build.
- `-controlURL <url> -authKey <key>` joins a local Headscale instead of Tailscale.
- `-joinInvite "<link>"` opens an invite, so one simulator can be an owner and, for
  another local owner, a guest.
- `-sharingFixture YES` fills in sample sharing data for screenshots.

Debug builds also accept an `http` control server on loopback in invites; release builds
only `https`.

## Local Headscale

`ovenlight run` takes `--control-url`, `--auth-key`, `--dev-tls-cert` and `--dev-tls-key`
(Headscale can't issue `ts.net` certificates) only with `OVENLIGHT_DEV=1`. Use a separate
`--config` and `--state` so your real nodes are untouched. Trust the test CA in the
simulator only for the length of the test:

```sh
xcrun simctl keychain <udid> add-root-cert ca.pem
```

For sharing, add `--dev-headscale <bin> --dev-headscale-config <file>`: the connector
then drives Headscale's CLI instead of the Tailscale API for keys, devices and tags.
Headscale has no policy API, so `setup-sharing` works only against Tailscale;
`publish --shareable` still records the owner, but you write the policy it would have
written into Headscale's policy file by hand.

To try guest mode in one simulator, run two Headscales with different `base_domain`s
(two owners), one dev connector each, and a test certificate covering both wildcards.
Sign Ovenlight in to the first with `-authKey`, and open the second owner's invite with
`-joinInvite`.

## How the iPhone app works

- **First launch** asks nothing of a guest: no Face ID until the iPhone keeps something
  (an app, an invite or a node). The empty launcher, "Welcome to Ovenlight", offers Join
  with Invite and Use My Own Computers. Only the second (also in Settings) starts the
  owner's node and shows "Connect Your Computers"; Connect opens Tailscale's sign-in page
  in Ovenlight and closes it once connected. With device approval on, an "Approve This
  iPhone" card then names this iPhone as the tailnet lists it and opens Tailscale's
  Machines page; an app opened meanwhile shows the same card. Settings shows the account
  and has Sign Out, which logs the node out and deletes its state; your apps stay, and
  ask you to sign in again. While no account is signed in, before the first sign-in or
  after Sign Out, Settings has Stop Using My Own Computers, which undoes the choice: the
  node logs out, its state and the remembered tailnet are forgotten, and the launcher
  goes back to its welcome when nothing else is saved. Both close every app view that
  went through the owner's node, kept in the background included
  (`WebViewPool.discard(attachedTo:)`).
- **Memberships.** One embedded node per network: the owner's own tailnet (state in
  `Application Support/Ovenlight/tailscale/owner`) and one guest node per owner whose
  invite this iPhone accepted (`tailscale/<membership ID>`, listed in
  `memberships.json`). Each has its own loopback proxy, state, reachability and random
  device name, and each shared app's data store goes through its membership's node.
  Which node carries an app of your own is in [security.md](security.md#on-the-iphone):
  `Routing.isOnOwnersTailnet` decides, `Routing.mayLoad` checks the route before every
  probe and load, and `WebViewPool.discardStaleRoutes` closes the views whose route
  changed.
- **Lifecycle.** Nodes stay up in the background. Every return to the foreground checks
  each node's loopback listener (iOS can reclaim it while Ovenlight is suspended) and
  starts a new node only for a dead one; that membership's data stores get the new SOCKS5
  proxy in place.
- **Opening an app** shows its icon and "Connecting…" at once, but the page loads only
  after the node is ready and the app's host answers a probe through the current proxy
  (`AppLoadGate`). After about 10 seconds the app shows "Can't Reach <App>" with Try
  Again. Launcher tiles dim with a badge while their machine isn't answering.
- **The open app** has one piece of native chrome: a small capsule at the top with the
  way home and the app's menu, which tucks up under the status bar. Tapping its handle,
  scrolling up or pulling down at the top of the page brings it back. Tapping the status
  bar scrolls the page to the top, as iOS does, and leaves the chrome alone.
- **Lock.** What locking holds back is in [security.md](security.md#on-the-iphone);
  `WebViewPool.stopCapture` ends the microphone and camera streams already running.
- **Permissions.** Besides the camera (scanning invite codes, and pages when allowed) and
  the microphone (pages), Ovenlight asks to add to the photo library, for Save to Photos
  on an image in a page.
- **Launcher.** It works like the Home Screen: one scrolling grid of icons, 4 columns (3
  at large text sizes). Long-press an icon for its menu, or empty space (or More, Edit
  Apps) to edit: icons jiggle, show a remove badge and drag to a new place among
  those on screen. VoiceOver gets Move Before and Move After actions on each icon. An app
  that arrived on its own (shared with this iPhone, or discovered on your machines) has a
  blue dot before its name until it's first opened (`WebApp.isNew`, saved in
  `apps.json`). Lock and More (Add App, Edit Apps, Join with Invite, People & Sharing
  for an owner, Settings) sit in the top corners.
- **Apps** come from discovery: the owner's online machines serving `ovenlight.json`
  show up in the launcher, and Ovenlight looks again each time it comes to the front.
  Add App also takes an address, but only one on the owner's own tailnet (`AppAddress`,
  with `Routing.isOnOwnersTailnet`). Before the owner has signed in it says to sign in
  first, and any other address gets a refusal that points to publishing the app or asking
  its owner for an invite. An app saved by another address before this rule keeps its
  tile and loads directly. One tile per host either way, and a removed app stays
  removed. Discovery only adds: an app unpublished on its computer keeps its tile until
  the owner removes it.
- **Copy.** Everything a guest can see (connection states, joining, errors,
  "No Longer Shared with You", feedback) is in `ConnectionCopy` and never says Tailscale
  or tailnet; a unit test checks. Only the owner's sign-in card, status pill and Settings
  do.
- **Brand.** An ember accent (`AccentColor`) and one oven light (the `LampLight` view): a
  cone of warm light from behind the Dynamic Island, or from its side in landscape, that
  lights the launcher, the lock screen and the privacy cover; the colors are asset
  catalog color sets. Grouped lists and forms (Settings, People & Sharing, the join
  sheet) sit on `GroupedBackground`, a warm paper in place of the system's cool grey. New
  York is the typeface for the wordmark, titles and monograms. The icon is an Icon
  Composer document, `Ovenlight/Resources/AppIcon.icon`, written by
  `design/icon/generate.py`; Xcode renders every appearance and older iOS from it.
- **Presentation** is `Router`, in `OvenlightApp.swift`.

### Guests

- **Joining.** An invite tapped in Messages or scanned with the Camera opens Ovenlight
  directly: Associated Domains lists `applinks:ovenlight.app`, and the site's
  `apple-app-site-association`, at
  `https://ovenlight.app/.well-known/apple-app-site-association`, lists this app for
  `/join*` only. Without Ovenlight, the link opens the join page, `ovenlight.app/join`,
  served from this repo's `site/` directory, which reads the fragment in the browser,
  sends nothing, and offers Open in Ovenlight and a way to get the app. Ovenlight also takes invite links pasted or typed under More, Join with
  Invite, or scanned there as a QR code (VisionKit, on devices with a camera). The join
  flow itself is in [protocol.md](protocol.md#joining-on-the-phone). The app then appears
  on the launcher labeled "from <owner>". An invite for an app already here offers Join
  Again rather than claiming on its own, and a membership's saved names change only when
  a claim succeeds.
- **Failures** say what happened without naming the network: key already used, no longer
  valid, invite canceled, used on another iPhone, or the owner's computer not answering
  (the invite is kept for Try Again).
- **Report a Problem** (every app's menu, and a guest's long-press menu) opens a mail to
  team@snowyghost.com with the app, its address, who shared it, its connection state and
  the Ovenlight and iOS versions. Settings, Support has Contact Us, the privacy policy
  (`AppLinks` in `Support.swift`) and Acknowledgements, the licenses
  `scripts/build-tailscalekit.sh` puts in the framework.
- **Leaving.** Settings, Shared with You, Leave (or Leave in a shared app's menu) removes
  that owner's apps and data and logs the node out. An open app closes without loading
  again. The owner's connector still lists the guest until the owner removes them.

### Owners

- **Per app.** Long-press one of your apps for Share… (disabled, with a note, until
  `publish --shareable` on its computer) and People (guests, open invites, that app's
  feedback). A guest's long-press menu has Report a Problem and Leave instead.
- **People & Sharing** (More menu) talks to each connector's admin API on port 8443
  through the owner's own node, and asks the owner's online machines at once (after the
  first answer, one app node per machine). It lists each app with whether it's online
  and shareable, its guests (one row per person with a count of their devices; swipe to
  add a device or remove them), open invites (swipe to cancel) and the feedback inbox
  with screenshots.
- **Share** asks for someone new (a name, only a label) or someone already shared with,
  who then keeps one identity across apps and devices.
- **Invites** show a QR code for in person, Send Invite (the share sheet, with the
  connector's `message`, the one `ovenlight share` prints, or just the link when there is
  none) and Copy Link, all with the universal link. Share in an open app's menu does the
  same for that app; it shows only for the owner's shareable apps.
- **Send Feedback** is in every app from a connector, owner or guest ("Send Feedback to
  <owner>" in a shared app, so it isn't mistaken for Report a Problem). It takes a snapshot
  of the page (`WKWebView.takeSnapshot`, shrunk under the connector's 5 MB), adds an
  optional note, and posts both to `/__ovenlight/feedback` through the app's own node.

## App Review invites

App Review, for TestFlight and for the App Store, needs a way in. Make it an invite:

```sh
ovenlight share <slug> --to "App Review" --review
```

It is the same single-use `tag:ovenlight-guest-<slug>` key and claim step as any invite,
but valid for 7 days, since the reviewer may test days later. It runs only in a terminal,
is marked `review` in `guests`, `status` and the admin API's `invites`, and prints the
link and notes to paste into App Store Connect (App Review Information, Notes). The app's
computer has to stay on and the connector running until the review is done. While a
reviewer is still a guest or has an open invite, another review invite needs
`--existing`.

Revoke it after the review: `ovenlight revoke "App Review"`, or `share --cancel <id>` if
it was never used.

## Shipping

Build TailscaleKit first (`scripts/build-tailscalekit.sh`), then archive and upload with
Xcode or your own tooling. Before uploading, check the archived app: TailscaleKit is
embedded with its license notices (`THIRD_PARTY_NOTICES.txt`) and privacy manifest.
Export compliance is answered in App Store Connect, since the app ships WireGuard
encryption.

## Forking

To build and sign your own copy, change to your own:

- in `project.yml`, `DEVELOPMENT_TEAM`, `bundleIdPrefix`, the bundle identifiers and the
  associated domains (see [Invite links](#invite-links)), then run `xcodegen generate`;
- in `Ovenlight/Sources/Support.swift`, `AppLinks.supportEmail` and
  `AppLinks.privacyPolicy`;
- to release the connector, the Developer ID identity (`IDENTITY`) and the Artifact
  Signing profile (`WIN_ENDPOINT`, `WIN_PROFILE`) in `scripts/release-connector.sh`, the
  keys in `docs/allowed_signers`, the bucket and site in `scripts/publish-connector.sh`,
  and `latestURL` in `connector/update.go`.

### Invite links

Invite links point at `https://ovenlight.app/join`, set in
`connector/invite.go` and `Ovenlight/Sources/InviteLink.swift`, and the app claims that
domain with Associated Domains in `project.yml`. iOS opens a universal link only in the
app the site lists, so a fork's build takes invites in the `ovenlight://join?...` form
instead: pasted under More, Join with Invite, or from the join page's Open in Ovenlight.
