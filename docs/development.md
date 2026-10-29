# Development

## Layout

- `connector/`: the `ovenlight` Go program. `internal/policy` edits tailnet policy files,
  `internal/tsapi` talks to the Tailscale API, and `e2e/` holds the end-to-end sharing
  test.
- `project.yml`, `Ovenlight/`, `OvenlightTests/`: the iPhone app.
- `android/`: the Android app. `scripts/build-android-core.sh` builds its Go core,
  `android/core/`, into the library the app links.
- `scripts/build-tailscalekit.sh`: builds the iPhone app's embedded Tailscale library.
- `scripts/release-connector.sh`: builds a connector release, and
  `scripts/publish-connector.sh` publishes it.
- `design/icon/generate.py`: writes the iPhone and Android app icons, the Google Play
  icon, and the site's `icon.svg` and `icon-180.png`.
- `.claude-plugin/marketplace.json`, `plugins/ovenlight/`: the Claude Code plugin (the MCP
  server and a skill that points to `ovenlight guide`). Check it with
  `claude plugin validate .`. Raise `version` in its `plugin.json` with every change to
  the plugin: Claude Code keeps people on their cached copy until it changes.
- `design/og/generate.py`: writes the link preview images, `site/og.jpg` for the site and
  `site/og-invite.jpg` for invite links.

## The connector

Build and install it from a checkout with `connector/install.sh`, or
`connector\install.ps1` on Windows (see
[connector.md](connector.md#from-source)). The minimum Go version is the `go` line
in `connector/go.mod`.

- **One node per app.** Each published app gets its own tsnet node named after its slug,
  with state in `nodes/<slug>/` in the state directory (connector.md's
  [table](connector.md#what-it-installs) has it for each system). The commands the
  connector runs for apps are in `commands.json` beside the app list. `publish` edits both
  and reloads the running connector over its control socket, as does `kill -HUP` on macOS
  and Linux.
- **Per-system code** sits in files named for it: `lock_*.go` (flock, or LockFileEx on
  Windows), `listen_*.go` (what listens on an app's port, for `doctor`),
  `internal/jsonfile/private_*.go` (files only you can read: mode 0600, or on Windows an
  access list that grants only you; there, saving, reading and removing also wait a moment
  while another process has the file open), `peer_*.go` (on Windows, refuses control
  connections from another user) and `supervise_*.go` (the process groups of the apps
  the connector runs).
- **Global flags.** Commands take `--config <file>` and `--state <dir>`.
- **The service.** The launchd agent, systemd unit and scheduled task start
  `ovenlight run --service`, the only connector that updates itself, and only in a
  release build (`selfUpdating` in `connector/updater.go`,
  [security.md](security.md#updates)).
- **The building guide.** `ovenlight guide` and the MCP `guide` tool serve
  `connector/building-apps.md`, a copy of [building-apps.md](building-apps.md) that Go
  can embed. After editing the doc, run `go generate` in `connector/`; a test fails
  while the two differ.
- `ovenlight version` prints the build: a release's version, else the module version,
  else `devel-<commit>`, with `-modified` when the tree had changes.

### Tests

```sh
go -C connector test ./...
```

The unit tests include the Tailscale API client and `setup-sharing` against an in-memory
fake of the API. `TestWireFiles` compares the connector's answers with the files in
`testdata/wire/`, which Ovenlight's tests decode too
([protocol.md](protocol.md#compatibility));
`go -C connector test -run TestWireFiles -update` rewrites them, for a change Ovenlight
still reads. It refuses to drop a key a file already has unless `-allow-drop` is added
too, which is only for a key no shipped build of Ovenlight needs.

The end-to-end sharing test runs against a throwaway local Headscale, on macOS or Linux
(CI runs it on Linux). It passes against Headscale v0.29.4 (`go install github.com/juanfont/headscale/cmd/headscale@v0.29.4`):

```sh
OVENLIGHT_E2E_HEADSCALE="$(go env GOPATH)/bin/headscale" go -C connector test -tags e2e ./e2e -v -timeout 15m
```

CI (`.github/workflows/ci.yml`) runs on every push and pull request, and weekly, on Linux
and Windows: `go vet` (with and without the `e2e` tag), the tests (with `-race` on Linux)
and `govulncheck`, then the install scripts as a user runs them: install, a killed
connector restarting, reinstall and `uninstall --purge`. The install jobs then run the
updater end to end, on macOS too, where the job also dry-runs the release scripts with
`scripts/test-connector-release.sh`. On Linux it also runs `gofmt`, `go vet` and
`govulncheck` for darwin/arm64 with cgo off, a check that `scripts/third-party-notices.sh`
still finds a license kept below a module's root, and the end-to-end test against
Headscale. It also renders the docs with `scripts/sitedocs`, which fails on a link or
`#anchor` that goes nowhere. Its `android` job checks the Android app, its side of the wire
files included ([Android tests](#android-tests)). The iPhone app's side runs only in Xcode,
locally and before shipping, never in CI, so a change to `testdata/wire/` must also pass
the [iPhone app's tests](#tests-1).

### Releases

On the mini, in `~/src/ovenlight`:

```sh
scripts/release-connector.sh 1.2.0 1.0.6
```

It takes the version, the oldest secure one, and optionally `reinstallBelow`, the oldest
version that may update to it by binary alone (by default the highest `reinstallBelow` of
the releases below it, so it carries forward). `--unsigned`, before the version, skips
all signing, to test the rest.

`release-connector.sh` writes the release to `build/release/`, replacing the one before:

- `ovenlight-connector-macos.tar.gz`: a universal (arm64 and x86_64) binary for macOS 13
  or later, signed with the Developer ID and notarized, with `install.sh`, `uninstall.sh`
  and the launchd plist;
- `ovenlight-connector-linux-amd64.tar.gz` and `-linux-arm64.tar.gz`: static binaries,
  with `install.sh`, `uninstall.sh` and the systemd unit;
- `ovenlight-connector-windows-amd64.zip` and `-windows-arm64.zip`: with `install.ps1`
  and `uninstall.ps1`, the binary and both scripts signed with Azure Artifact Signing;
- `SHA256SUMS` and the release's index `index.json`, both unsigned, and `commit`.

Each archive holds an `ovenlight-connector-<version>` folder, with `LICENSE`, `NOTICE` and
`THIRD_PARTY_NOTICES.txt`, the licenses of what that system's build links. The archive
names carry no version, so the latest release keeps the same URLs. It builds with the Go
version on `connector/go.mod`'s `go` line (through `GOTOOLCHAIN`), as CI does. Unless
`--unsigned`, it builds only a clean `HEAD` (no changes or untracked files) that is on
`origin/master`, and checks with `go version -m` that each binary carries that commit. Run
it in a clone: go1.26 stamps no commit in a git worktree.

Notarization uses an App Store Connect API key, named by the environment variables
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_PATH`. Over SSH, unlock the login keychain
first. Windows signing needs `jsign` (`brew install jsign`) and the Azure CLI
(`brew install azure-cli`), signed in with `az login` as someone with the Artifact Signing
Certificate Profile Signer role on the certificate profile that `WIN_PROFILE` in the
script names.

Try the Windows build on a PC with Smart App Control on before publishing. Then sign it on
the Air, with the release key behind Touch ID:

```sh
scripts/sign-connector.sh 1.2.0
```

It copies the build's `SHA256SUMS` and index from `~/src/ovenlight/build/release` on the
mini and prints what it signs: the index's fields, the commit, the fingerprints of its
`release_keys`, the connector changes since the last promoted release and `SHA256SUMS`. It
refuses a commit whose `connector/release_keys` isn't the two keys in
`~/.ssh/ovenlight-release-keys/` plus the key signing it, when that folder doesn't hold
it. It signs `SHA256SUMS`, checks that signature with `docs/allowed_signers` at that
commit, as owners will, and signs a canary index, `canary.json`, which only
`ovenlight update now --index` takes. It keeps both in its history,
`~/.ssh/ovenlight-release-keys/history/`, the only record it reads, and sends a copy with
`rsync` to the release's folder in the mini's `~/Sync/ovenlight/releases/`. Nothing on the
Air writes to its own `~/Sync`, where a link an agent syncs would write into the Air's
files. `--key <file>` signs with another key, such as the paper backup.

Back on the mini, tag the release on the commit it was built from, which
`build/release/commit` names. Tag only once it's signed: a pushed `connector-v*` tag can't
move, and a refusal at signing may need a new build from a new commit.

```sh
git tag -s -m "Connector 1.2.0" connector-v1.2.0 "$(cat build/release/commit)"
git push origin connector-v1.2.0
```

Then upload the version's folder, and try the release on the mini's own connector, the
canary. The mini's connector must run the live release first: after a pull, a dropped
promote, or a canary that failed once its trial passed, it may still run a release that
isn't live, so install the live one by hand from its folder. The live index must also be
signed with a key the new release lists, so after a key change, `--resign` it first.

```sh
scripts/publish-connector.sh 1.2.0
ovenlight update now --index ~/Sync/ovenlight/releases/1.2.0/canary.json
```

The canary passes once `ovenlight status` shows `Last update: <live> to 1.2.0`, written
only when 1.2.0's 10-minute trial passes, and `last checked` no earlier than that, with no
failed check. A release that raises `reinstallBelow` passes once `update now` says it
needs its install script, the mini installs it by hand from `connector/1.2.0/`
([connector.md](connector.md#install-a-release-by-hand)), and status shows version 1.2.0
running, with a check made since. A canary that rolls back says why in the mini's
`~/Library/Logs/ovenlight.log`, on its `updates: rolling back from 1.2.0` line. A release
whose canary fails is never promoted: fix it under a new version, since a published folder
never changes.

Then promote it. Sign the promote on the Air:

```sh
scripts/sign-connector.sh --promote 1.2.0
```

then publish it on the mini:

```sh
scripts/publish-connector.sh --promote 1.2.0
```

That puts its files in `connector/latest/` and its index in `latest.json`, and every
owner's two-day wait starts. The mini publishes only the newest index signed, so a publish
that fails partway is finished by running the same command again (a folder's before the
next build replaces `build/release`). If the publish names another index as the newest, a
`.base` it can't follow or a file it lacks, the mini lacks part of what the Air signed:
`scripts/sign-connector.sh --check` on the Air sends every index published and the newest
signed again, with their releases' signed files, as every run there does. To drop a
promote, pull or resign cut short instead, run `scripts/sign-connector.sh --check` on the
Air (after a lost Air, `--import`). If it says `latest.json` is that index, it went out:
pull it if it's a promote. Otherwise sign the next index and publish that: a `--resign`,
which puts the live release back in `connector/latest/`, or a promote of the next release
when nothing is promoted before it, or a fresh pull for a pull.

`--pull`, signed and published like a promote, takes the live release back out by
publishing the index of the release before it, signed again. Computers that already took
the pulled release keep it, since the connector never installs an older one, so the fix is
the next release. `--resign` publishes the live index signed again, to supersede a signed
promote that mustn't ship. Before signing anything the Air fetches `latest.json`, which
must be the last index it saw go up, so a signature is built only on what you were last
shown. `--check` also takes the newest index it signed, and marks it seen. After signing
an index for `latest.json` the Air waits until `latest.json` is that index, which Ctrl-C
leaves to `--check`. Its refusals say what to do, as when a pull signed and not yet
published blocks a promote or resign. Each script's header says what it checks, and
`scripts/test-connector-release.sh` dry-runs them all. Each release restarts every owner's
connector, and one promoted within two weeks of the last restarts the wait of every
computer that hasn't taken the last yet (a computer off for a long weekend, or two, can
take thirteen days), so batch what isn't urgent. A `--resign` restarts the wait too, for a
computer whose daily check fetches it before its moment to swap.

Uploads go to the R2 bucket behind `https://downloads.ovenlight.app`, through `wrangler`
(signed in with `npx wrangler@4.147.0 login`, or `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID`):

- `connector/<version>/`: the release's files, never replaced;
- `connector/latest/`: the same files of the latest release, which the install commands
  and download links in the docs point to;
- `connector/latest.json` and its signature `latest.json.sig`: the signed index the
  updater checks daily ([security.md](security.md#updates)). A connector older than its
  `secure` updates without the two-day wait, and `doctor` fails on it, so raise `secure`
  with any release that fixes a security problem.

Once a release is promoted, publish its GitHub Release, which is what people watching the
repository's releases hear about:

```sh
gh release create connector-v1.2.0 --verify-tag --title "Connector 1.2.0" --notes-file notes.md
```

The notes say what changed for owners since the last Release, a security fix first, and
point a connector that doesn't update itself to the [install command](connector.md#install).
The archives stay on downloads.ovenlight.app alone, so the Release attaches none. A phone
app version gets a Release once it's live in its store: tag the commit its build came from
with `git tag -s`, as `ios-v1.2.0` or `android-v1.2.0`, and push the tag. Give its What's
New as the notes, and add `--latest=false`, so the connector's stays the latest.

#### The first updater release

The first release that updates itself, 1.1.0 here, goes out once, with 1.1.1 after it:

1. On the Air, make the Touch ID key with the two commands under [Changing a release
   key](#changing-a-release-key), if it isn't made yet. Then run key setup,
   `scripts/release-key-setup.sh`, in Terminal there, not over ssh: it makes and prints the
   paper key, and files both public keys and a test signature by each in
   `~/.ssh/ovenlight-release-keys/`.
2. Before the updater reaches master, swap the mock release keys for the real ones, in
   one commit:
   - `connector/release_keys` and `docs/allowed_signers` list the two real public keys,
     the latter in namespaces `file,ovenlight-latest`. The mock lines, the keys that
     signed releases up to 1.0.9 and their comments leave `allowed_signers`: a release up
     to 1.0.9 is checked with the file at its own tag.
   - `index.json.release-1.sig` and `index.json.release-2.sig`, which key setup signed
     with the two real keys, are copied in the Air's clone from
     `~/.ssh/ovenlight-release-keys/` into `connector/testdata/update/`.
   - The comments in `connector/keys.go` and `connector/sshsig_test.go` lose what they say
     about the mock keys.
   - `connector/testdata/update/sign.sh` loses the lines that delete
     `index.json.release-*.sig` and sign them with the mock keys, and their comment.
   - The Air's `~/.ssh/ovenlight-release-keys/` holds the two real public keys.

   `connector/testdata/releasekey/mock-*.pub` stay: `release-connector.sh` refuses any key
   there in `release_keys` or `allowed_signers`.
3. Merge the updater to master. Check that `~/Sync/ovenlight/releases/` on the mini and
   `~/.ssh/ovenlight-release-keys/history/` on the Air hold no records yet, so 1.1.0
   names its own `reinstallBelow`:
   `scripts/release-connector.sh 1.1.0 1.0.6 1.1.0`. Sign, tag and publish its folder,
   then build, sign, tag and publish 1.1.1 from the same commit.
4. Nothing before 1.1.0 updates itself, so it has no canary. The mini installs it by hand
   from `connector/1.1.0/` ([connector.md](connector.md#install-a-release-by-hand)), then
   runs 1.1.1's canary. The trial passes on the canary index, but no daily check verifies
   while `latest.json` is 1.0.9's, which is unsigned. The mini's first verified check after
   1.1.0's promote completes the canary. The mini's Windows on Arm VM installs 1.1.0 by
   hand too, with no canary, and stays on it, to take 1.1.1 the way owners will.
5. Deploy the site, so its privacy policy covers the update check, then promote 1.1.0.
   From the merge until that promote, both sets of install steps fail: the new ones on
   1.0.9's unsigned `latest.json`, and 1.0.x's, still on the site, on `latest/`'s
   `SHA256SUMS`, since master's `allowed_signers` no longer lists 1.0.9's key. So promote
   soon after the merge. Promote 1.1.1 a day later, the owners'
   first automatic update.

#### Changing a release key

A change of release keys takes two releases. R brings in the new keys, signed with an old
one, and R+1 drops that old key. R's `connector/release_keys` is the two keys that sign
from then on plus the old key that signs R, as `sign-connector.sh` requires.
`docs/allowed_signers` adds the new keys. A key that leaves `release_keys` stays in
`allowed_signers` until the release after R+1 is promoted, since a pull back to a release
it signed puts that release's files in `latest/` again, and 1.0.x's docs check those with
master's `allowed_signers`. `publish-connector.sh --pull` says when one has to come back.
The one-line installers, `site/install.sh` and `site/install.ps1`, check `latest.json` with
the keys they list, which a test keeps equal to `allowed_signers`: deploy the site from R's
commit before an index signed with a new key goes up, and again whenever a key leaves
`allowed_signers`.

`TestReleaseKeySignatures` wants one test signature in `connector/testdata/update/` for
each key in `release_keys`. R adds one for each new key, as the next free
`index.json.release-<n>.sig`, and the commit that drops a key deletes its signature.

For a planned move to a new Air, the old Air signs R:

1. On the new Air, make its Touch ID key:

   ```sh
   sc_auth create-ctk-identity -l ovenlight-release -k p-256-ne -t bio
   cd ~/.ssh && ssh-keygen -w /usr/lib/ssh-keychain.dylib -K -N ''
   ```

   Carry its public half, `~/.ssh/id_ecdsa_sk_rk_ovenlight-release.pub`, to the old Air by
   AirDrop or a USB stick, never through `~/Sync`, and check that `ssh-keygen -lf` shows
   the same fingerprint for the copy as for the original on the new Air. Write that
   fingerprint on both paper copies. In the old Air's `~/.ssh/ovenlight-release-keys/`,
   the copy takes the place of `touchid.pub`. The new Air's folder gets that folder's
   `touchid.pub` and `backup.pub` the same way.
2. In the new Air's clone, sign the new key's test signature (number 3 at the first
   change):

   ```sh
   SSH_SK_PROVIDER=/usr/lib/ssh-keychain.dylib ssh-keygen -Y sign -q -f ~/.ssh/id_ecdsa_sk_rk_ovenlight-release -n ovenlight-test < connector/testdata/update/index.json > connector/testdata/update/index.json.release-3.sig
   ```

3. Commit R's keys and signature, then release R as usual on the old Air. A release needed
   before R+1 can go out is signed there too and lists the same three keys.
4. R+1 drops the old key and is signed on the new Air. Its canary needs the live index
   signed with the new key (`--resign`). That and R+1's promote are refused until six
   weeks after R's promote, and a pull back below R starts the wait again at the next
   promote. The `--resign` also waits until two weeks after `latest.json` last changed,
   since each change starts the wait of computers still below R again. Keep the
   old Air until R+1 is promoted, since it signs any pull before then. Before the new Air
   first signs, and each time the signing Air switches, carry the other Air's
   `~/.ssh/ovenlight-release-keys/history` the same way and merge it in with
   `cp -Rn <copy>/ ~/.ssh/ovenlight-release-keys/history`. Without it, the Air
   refuses to sign once `latest.json` is an index it lacks.

After a lost Air, the paper key signs R. If the lost Air turns up, delete its
`~/.ssh/ovenlight-release-keys/history` before running a release script there, since each
run sends the newest index in it to the mini.

1. On the replacement Air, make its Touch ID key with the two commands above. In
   `~/.ssh/ovenlight-release-keys/`, if the Air has one, delete what a backup may have
   brought back
   (`rm -f touchid.pub backup.pub index.json.release-1.sig index.json.release-2.sig`).
   Keep `history` only if it came from a disk only the lost Air used and
   `scripts/sign-connector.sh --check` finds `latest.json` in it. Otherwise
   `rm -r history`, then write into a new file each key the `release_keys` in
   `~/Sync/ovenlight/releases/` list whose fingerprint is on the current paper key's page,
   printed or written on, except a paper key that was lost.
   `cat ~/Sync/ovenlight/releases/*/release_keys | sort -u` lists those keys, and
   `ssh-keygen -lf -` on that list prints their fingerprints in the same order. Then
   `scripts/sign-connector.sh --import <file>` copies what those keys signed into the
   history, with the index each was built on, checks `latest.json` against it and prints
   it: check the list against the releases you published. If it's wrong, find out who
   changed the records before you delete `history` and import again. Then run key setup
   (`scripts/release-key-setup.sh`). It makes a new paper key, files both new public keys
   and a test signature by each in `~/.ssh/ovenlight-release-keys/`. Its page also lists
   the fingerprints of the keys earlier releases list.
2. R lists the two new keys and the old paper key, and drops the lost key and its
   signature. Copy key setup's `index.json.release-1.sig` and `index.json.release-2.sig`
   in under the next two free numbers.
3. The old paper key signs what R needs: its folder, the `--resign` before its canary and
   its promote. The old paper key also signs any pull before R+1 is promoted. A release
   needed before R+1 can go out is signed with it too and lists the same three keys. For
   each, make a folder with `mktemp -d`, type the key from paper into a file there and
   `chmod 600` it. Write its public half beside it with
   `ssh-keygen -y -f <file> > <file>.pub`, which also checks the passphrase. Sign with
   `--key <file>`, then delete the folder.
4. R+1 drops the old paper key and is signed with the new Touch ID key. Its `--resign`
   and promote wait as a planned move's do.

After a lost paper key, the Air signs R, which lists its own key and a new paper key and
takes a version above every release signed so far, since those list the lost key and none
of them is promoted any more. Note the lost key's fingerprint
(`ssh-keygen -lf ~/.ssh/ovenlight-release-keys/backup.pub`), then delete `backup.pub`
(the lost key may be in other hands, so the Air trusts nothing it signs) and run key
setup. Its page lists the lost key among the keys earlier releases list, so cross it out
on both copies. Copy only its `index.json.release-2.sig` in under the next free number.
During a planned move, delete `backup.pub` on both Airs, merge the old Air's history into
the new Air's as step 4 does, and run key setup on the new Air, then carry the new
`backup.pub` to the old Air as step 1 carries the new key: the old Air signs R, and the
move's R+1 still follows. Drop the lost key's signature and its line in
`allowed_signers`, since it signed no release. Outside a planned move, if the Air's key is
still inside its wait, the old paper key, which every release so far lists, signs this R
instead, and R+1 drops that key. Otherwise the Air's key signs this R, which waits for
nothing, and no other R+1 follows. If the Air is lost too, the new paper key's `--resign`
and promote wait as a planned move's do.

A lost Air inside a new Touch ID key's wait is answered by an R signed with the paper key
every release so far lists, which waits for nothing. After a planned move whose old Air is
lost, the new Air keeps its key and history and deletes `backup.pub`. If
`sign-connector.sh --check` then says `latest.json` isn't in the history, the old Air
signed past the last merge: delete `history` and import it, as after a lost Air. Run key
setup and copy only its `index.json.release-2.sig` in, as after a lost paper key. R lists
the new Air's key, the new paper key and the old paper key that signs it.

After losing both keys, no computer can verify a release signed with new ones, so every
owner installs the next release by hand once. On the mini, move
`~/Sync/ovenlight/releases/` aside. On the Air, move `~/.ssh/ovenlight-release-keys/`
aside, make the Touch ID key with the two commands above and run key setup. On the mini,
write an unsigned index naming the live release and its `secure` to `build/latest.json` in
the repo, as 1.0.9's was (for example `{"latest": "1.2.0", "secure": "1.0.6"}`), and upload
it:

```sh
npx wrangler@4.147.0 r2 object put ovenlight-downloads/connector/latest.json --file build/latest.json --content-type application/json --cache-control "public, max-age=300" --remote
```

Then release as in the first updater release: a version above every release so far,
naming its own `reinstallBelow`, with it and `secure` no lower than the live index's
(every installed connector keeps that index, and refuses one that lowers either), whose
`release_keys` and `allowed_signers` list only the new keys.

## The iPhone app

Building the app needs Xcode 26 or later, for the Icon Composer icon and the iOS 26 glass
effects. Minimum iOS is 18.1, TailscaleKit's minimum. The Xcode project is generated from
`project.yml`, and the `.xcodeproj` is committed, so XcodeGen (`brew install xcodegen`) is
needed only after editing `project.yml`: then run `xcodegen generate`.

### TailscaleKit

`scripts/build-tailscalekit.sh` builds `Frameworks/TailscaleKit.xcframework` from
`tailscale/libtailscale` at a pinned commit. Run it once before building in Xcode. The
output is gitignored and cached; `--force` rebuilds. It needs Go, and fetches the
`go1.27.3` toolchain itself. On top of upstream it:

- builds with the `ts_omit_debug` tag, which drops the node's debug LocalAPI, pprof and
  debug HTTP handlers, with `ts_omit_peerapiserver` and `ts_omit_cachenetmap` (the script
  says why), and with `ts_omit_webclient`, which leaves out Tailscale's web client and the
  font and JavaScript bundle it embeds;
- turns off log upload to `log.tailscale.com`, and the log files tsnet would keep in each
  node's folder, from a Go `init`, for every node the app runs, guests' included;
- serves each node's loopback SOCKS5 port with a proxy of its own from
  `scripts/libtailscale`, which dials only the node's peers and the internet by the
  Android core's rules ([security.md](security.md#on-the-phone)), and runs its tests
  before each build;
- raises `golang.org/x` modules past libtailscale's own when they have security fixes;
- adds a privacy manifest to the framework, which Apple requires of a framework that
  calls a required-reason API.

### Tests

Any iPhone simulator with iOS 18.1 or later works as the destination.
`xcrun simctl list devices available` lists them.

```sh
scripts/build-tailscalekit.sh && xcodebuild test -scheme Ovenlight -destination "platform=iOS Simulator,name=iPhone 17,OS=latest"
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
only `https`. Only debug builds load an app at `localhost` directly, so a seeded launcher
can open a local demo app for screenshots (loopback IP literals stay blocked).

## Local Headscale

`ovenlight run` takes `--control-url`, `--auth-key`, `--dev-tls-cert` and `--dev-tls-key`
(Headscale can't issue `ts.net` certificates) only with `OVENLIGHT_DEV=1`. Use a separate
`--config` and `--state` so your real nodes are untouched. Trust the test CA in the
simulator only for the length of the test:

```sh
xcrun simctl keychain booted add-root-cert ca.pem
```

For sharing, add `--dev-headscale <bin> --dev-headscale-config <file>`: the connector
then drives Headscale's CLI instead of the Tailscale API for keys, devices and tags.
Ovenlight leaves a development server's policy alone, so `setup-sharing` works only
against Tailscale. `publish --shareable` still records the owner, but you write the policy
it would have written into Headscale's policy file by hand.

To try guest mode in one simulator, run two Headscales with different `base_domain`s
(two owners), one dev connector each, and a test certificate covering both wildcards.
Sign Ovenlight in to the first with `-authKey`, and open the second owner's invite with
`-joinInvite`.

## How the iPhone app works

- **First launch** asks nothing of a guest: no Face ID until the iPhone keeps something
  (an app, an invite or a node). The empty launcher, "Welcome to Ovenlight", offers Join
  with Invite and Use My Own Computers. Only the second (also in Settings) starts the
  owner's node and shows "Connect Your Computers"; Connect opens Tailscale's sign-in page
  in Ovenlight and closes it once connected. With device approval on, the sign-in page stays
  open until the iPhone is approved. Closed before that, it gives way to Tailscale's
  Machines page, in Ovenlight's browser rather than Safari, which stays until Done so an
  app waiting there can be approved too. An "Approve This iPhone" card names this iPhone as
  the tailnet lists it and opens that page again, and an app opened meanwhile shows the same
  card. Settings shows the account
  and has Sign Out; while no account is signed in, it has Stop Using My Own Computers in
  its place. Both undo the choice (`NodeManager.disableOwner`): the node logs out, its
  state and the remembered tailnet are forgotten, the apps found on the owner's computers
  go with their data (`AppRegistry.removeDiscovered`), and the launcher goes back to its
  welcome when nothing else is saved. Apps added by hand and shared ones stay, and
  signing in again finds the owner's apps. Sign Out asks first, and so does Stop Using My
  Own Computers when it has apps to remove. A Sign Out cut short by a quit finishes at the
  next launch. Both close every app view that went through the owner's node, kept in the
  background included (`WebViewPool.discard(attachedTo:)`).
- **Memberships.** One embedded node per network: the owner's own tailnet (state in
  `Application Support/Ovenlight/tailscale/owner`) and one guest node per owner whose
  invite this iPhone accepted (`tailscale/<membership ID>`, listed in
  `memberships.json`). Each has its own loopback proxy, state, reachability and random
  device name, and each shared app's data store goes through its membership's node.
  Which node carries an app of your own is in [security.md](security.md#on-the-phone):
  `Routing.isOnOwnersTailnet` decides, `Routing.mayLoad` checks the route before every
  probe and load, and `WebViewPool.discardStaleRoutes` closes the views whose route
  changed.
- **Lifecycle.** Nodes stay up in the background. Every return to the foreground checks
  each node's loopback listener (iOS can reclaim it while Ovenlight is suspended) and
  starts a new node only for a dead one; that membership's data stores get the new SOCKS5
  proxy in place.
- **Opening an app** shows its icon and "Connecting…" at once, but the page loads only
  after the node is ready and the app's host answers a probe through the current proxy
  (`AppLoadGate`). After about 10 seconds the app shows "Can't Reach \<App>" with Try
  Again. When the network lists the app's computer as offline (believed by a guest's node
  only once its network has listed a peer online), it shows that at once, keeps trying
  quietly while it's on screen, and opens as soon as the computer answers. Launcher tiles
  dim with a badge while their computer isn't answering, or is listed offline, which the
  launcher doesn't probe. When the connector answers but the app itself isn't running (a
  502 with the code `unavailable`), the first page is tried a few more times, then
  Ovenlight says the app isn't responding rather than showing the connector's text.
- **The open app** starts below a band at the top, the status bar's height plus the
  handle's 20 points (`AppChrome.handleHeight`), painted in the page's header color: its
  `theme-color`, else its background, and before the first load the one remembered from
  the last (`StatusBarTint`). The status bar's text takes the tone that reads on what is
  under it: iOS 26 and later choose it, and before that `StatusBarTone` sets it from the
  band. A page with `viewport-fit=cover` runs to the bottom and side edges. Any other
  stays inside the side safe areas. The one piece of native chrome is a small capsule at
  the top with the way home and the app's menu. It starts tucked up under the status bar,
  leaving its handle in the band (the first time, it shows with a one-time tip about the
  handle until the tip is closed). Before the app has loaded, its sign-in, approval and
  error screens each have Back to Apps. Tapping the band or pulling down at the top of the
  page brings it back. With no status bar above the band (a phone on its side, the iPhone
  Duo's cover), the handle's tap reaches 44 points down at its middle. Tapping the status
  bar scrolls the page to the top, as iOS does, and leaves the chrome alone. Ovenlight
  removes WebRTC from every frame of every page (`WebViewPool.noWebRTC`).
- **Lock.** What locking holds back is in [security.md](security.md#on-the-phone).
  `WebViewPool.stopCapture` ends the microphone and camera streams already running.
- **Permissions.** Besides the camera (scanning invite codes, and pages when allowed) and
  the microphone (pages), Ovenlight asks to add to the photo library, for Save to Photos
  on an image in a page.
- **Launcher.** It works like the Home Screen: one scrolling grid of icons, 4 columns (3
  at large text sizes). Long-press an icon for its menu, or empty space (or More, Edit
  Apps) to edit: icons jiggle, show a remove badge and drag to a new place among
  those on screen. VoiceOver gets Move Before and Move After actions on each icon. An app
  that arrived on its own (shared with this iPhone, or discovered on your computers) has a
  blue dot before its name until it's first opened (`WebApp.isNew`, saved in
  `apps.json`). Lock and More (Add App, Edit Apps, Join with Invite, People & Sharing
  for an owner, Settings) sit in the top corners.
- **Apps** come from discovery: the owner's online devices serving `ovenlight.json`
  show up in the launcher, and Ovenlight looks again each time it comes to the front.
  Add App also takes an address, but only one on the owner's own tailnet (`AppAddress`,
  with `Routing.isOnOwnersTailnet`). Before the owner has signed in it says to sign in
  first, and any other address gets a refusal that points to publishing the app or asking
  its owner for an invite. Once the owner's tailnet is known, an app of your own off it,
  such as one from a tailnet the owner was signed in to before, keeps its tile but is
  refused (`Routing.route`): it shows Can't Reach and nothing goes out. Until then a
  `*.ts.net` app waits behind Sign In, and any other address is refused. One tile per
  host either way, and a removed app stays removed until Sign Out. Discovery only adds:
  an app unpublished on its computer keeps its tile until the owner removes it.
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
  sends nothing, and offers a way to get the app, Open in Ovenlight on Android and Copy Invite on iPhone. Ovenlight also takes invite links pasted or typed under More, Join with
  Invite, or scanned there as a QR code (VisionKit, on devices with a camera). The join
  flow itself is in [protocol.md](protocol.md#joining-on-the-phone). The app then appears
  on the launcher labeled "from \<owner>". An invite for an app already here offers Join
  Again rather than claiming on its own, and a membership's saved names change only when
  a claim succeeds.
- **Failures** say what happened without naming the network: key already used, no longer
  valid, invite canceled, used on another phone, or the owner's computer not answering
  (the invite is kept for Try Again).
- **Report a Problem** (every app's menu, and a guest's long-press menu) opens a mail to
  team@snowyghost.com with the app, its address, who shared it, its connection state (in
  detail, without the sign-in link), how far opening it got, and the Ovenlight and iOS
  versions. Settings, Support has Contact Us, the privacy policy
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
  through the owner's own node, and asks the owner's online devices at once (after the
  first answer, one app node per computer). It lists each app with whether it's online
  and shareable, its guests (one row per person with a count of their devices and when
  they last opened it; swipe to add a device or remove them), open invites (swipe to
  cancel) and the feedback inbox with screenshots. People lists everyone across apps, those
  who opened one most recently first, with Select to remove several at once. A person's
  page shows each of their apps, and each device when they have more than one, with when
  it was last opened, to remove them from one, or remove an old device. The footer counts
  the tagged devices the apps and guests use (`taggedDevices`).
- **Share** asks for someone new (a name, only a label) or someone already shared with,
  who then keeps one identity across apps and devices.
- **Invites** show a QR code for in person, Send Invite (the share sheet, with the
  connector's `message`, the one `ovenlight share` prints, or just the link when there is
  none) and Copy Link, all with the universal link. Share in an open app's menu does the
  same for that app; it shows only for the owner's shareable apps.
- **Send Feedback** is in every app from a connector, owner or guest ("Send Feedback to
  \<owner>" in a shared app, so it isn't mistaken for Report a Problem). It takes a snapshot
  of the page (`WKWebView.takeSnapshot`, shrunk under the connector's 5 MB), adds an
  optional note, and posts both to `/__ovenlight/feedback` through the app's own node.

## App Review invites

App Review, for TestFlight and for the App Store, needs a way in. Make it an invite,
using the app's slug: `ovenlight share <slug> --to "App Review" --review`.

It uses the same `tag:ovenlight-guest-<slug>` tag and claim step as any invite. Its key
is reusable, so up to 10 of the reviewer's devices can claim it, and lasts 90 days,
Tailscale's longest, since a review can take weeks. It runs only in a terminal, is
marked `review` in `guests`, `status` and the admin API's `invites`, and
prints the link and notes to paste into App Store Connect (App Review Information, Notes). The app's
computer has to stay on and the connector running until the review is done. While a
reviewer is still a guest or has an open invite, another review invite needs
`--existing`.

Revoke it after the review: `ovenlight revoke "App Review"`. `share --cancel <id>` only
stops more devices from claiming it: those that have keep the app until revoked.

Google Play's review needs a review invite of its own, for another person such as
`--to "Play Review"`, so revoking one store's reviewers leaves the other's. Paste its link
and notes into Play Console, under App content, Sign-in details. Play expects them to keep
working and reviews any change to them, so make the next one with `--review --existing`
well before the invite expires or its 10 places are used up (a device that leaves or is
removed keeps its place). Once the new details are live and no review still uses the old
link, run `ovenlight revoke` with the old invite's ID (`share` printed it, and
`ovenlight guests --json` shows it as `inviteId` on each device that claimed it). That
removes the devices that claimed it and cancels it if it's still open. Revoking by the
person's name would end the new invite too.

## Shipping

Build TailscaleKit first (`scripts/build-tailscalekit.sh`), then archive and upload with
Xcode or your own tooling. Before uploading, check the archived app: TailscaleKit is
embedded with its license notices (`THIRD_PARTY_NOTICES.txt`) and privacy manifest.
Export compliance is answered in App Store Connect, since the app ships WireGuard
encryption.

## Forking

To build and sign your own copy, change to your own:

- in `project.yml`, `DEVELOPMENT_TEAM`, `bundleIdPrefix`, the bundle identifiers and the
  associated domains (see [Invite links](#invite-links)), then run `xcodegen generate`. A
  free Apple ID's personal team can't sign Associated Domains: to build for a device with
  one, remove the `com.apple.developer.associated-domains` entitlement before generating;
- in `Ovenlight/Sources/Support.swift`, `AppLinks.supportEmail` and
  `AppLinks.privacyPolicy`;
- to release the connector, the Developer ID identity (`IDENTITY`) and the Artifact
  Signing profile (`WIN_ENDPOINT`, `WIN_PROFILE`) in `scripts/release-connector.sh`, the
  bucket and site in `scripts/publish-connector.sh`, the download host, doc links and
  contact address in the site's `install.sh` and `install.ps1` (and the signing profile's
  EKU `install.ps1` checks), and `releaseBase`, `installDocs` and
  `olderDocs` in `connector/update.go`. A fork signs with release keys of its own: list
  them in `connector/release_keys`, which every build trusts, in `docs/allowed_signers`
  (a test and the signing script check it lists each one), and in the site's `install.sh`
  and `install.ps1` (a test checks they list the same keys), put their public halves in
  `~/.ssh/ovenlight-release-keys/` on the computer that signs, and change `HANDLE`, the
  `mini` host and the site `fetchlive` reads in `scripts/sign-connector.sh`, `minirelease`
  and `minisync` in `scripts/connector-release-lib.sh`, and the `team@snowyghost.com`
  principal, which `sign-connector.sh`, `publish-connector.sh`, connector.md and the
  site's installers verify `SHA256SUMS.sig` and `latest.json.sig` with, and
  `connector/keys_test.go` wants in `allowed_signers` and the installers.

### Invite links

Invite links point at `https://ovenlight.app/join`, set in
`connector/invite.go` and `Ovenlight/Sources/InviteLink.swift`, and the app claims that
domain with Associated Domains in `project.yml`. iOS opens a universal link only in the
app the site lists, so in a fork's build, or one without the entitlement, an invite tapped
in Messages or scanned with the Camera opens the join page, whose Copy Invite copies the
link, which Join with Invite takes pasted. Pasted or scanned there, an invite opens in
either form.

## The Android app

The Android app is native Kotlin and Jetpack Compose, in `android/`. It follows the iPhone
app's structure: most Swift files have a Kotlin twin of the same name (`NodeManager.kt`
for `NodeManager.swift`), and the iPhone tests are ported under their own names. It runs
on Android 10 (API 29) or later and opens apps in Android System WebView 144 or later, the
first that gives every web view its safe areas. On an older WebView it asks for an update
and opens no app.

Building it needs JDK 21 (`JAVA_HOME`), Go, and the Android SDK (`ANDROID_HOME`) with the
platform, build tools and NDK that `android/gradle.properties` names. From the
repository's root:

```sh
android/gradlew -p android :app:assembleDebug
```

### The Android Tailscale library

`android/core` is a Go module over Tailscale's tsnet, with Tailscale pinned in its
`go.mod` and Go in `scripts/build-android-core.sh`. It runs one node per network, as the
iPhone app does, and the local proxy that every page and every native request goes through.
`scripts/build-android-core.sh` binds it with gomobile into
`android/app/libs/ovenlight-core.aar`, gitignored and cached (`--force` rebuilds), with
the licenses of what it links beside it for Acknowledgements. Gradle runs the script
before every build: for arm64-v8a only in a debug build, and for arm64-v8a, armeabi-v7a
and x86_64 when a release or bundle task is asked for, so a debug build runs only on an
arm64 phone or emulator (such as one on an Apple silicon computer). It builds with the
`ts_omit_debug`, `ts_omit_peerapiserver` and `ts_omit_cachenetmap` tags, links for 16 KB
pages, and turns off log upload, and the log files tsnet would keep for it, for every
node.

### Android tests

From the repository's root:

```sh
android/gradlew -p android :app:lintDebug :app:testDebugUnitTest
go -C android/core test ./...
android/scripts/test-parity.sh
```

The JVM tests run the screens under Robolectric, and
`testDecodesEveryGoldenFileFromTheConnector` decodes every file in
`connector/testdata/wire/`: a new file fails it until it has a case there.
`test-parity.sh` fails when an iPhone test has no Android twin of the same name, outside
a short list of exclusions, each with its reason. It reads the iPhone tests from
`origin/app-1.0.2`, or the ref that `OVENLIGHT_SWIFT_SPEC` names. CI runs all three, with
`go vet` and `govulncheck` for the core, a release bundle and `verify-bundle.sh` (see
[Shipping on Google Play](#shipping-on-google-play)).

The device tests need an emulator or phone with WebView 144 or later:
`android/gradlew -p android :app:connectedDebugAndroidTest`. `android/e2e/e2e.sh` runs
whole scenarios against a local Headscale and a dev connector, on the emulators that
`android/scripts/avds.sh` keeps. Its header lists them.

### Debug launch extras

Debug builds take extras on `adb shell am start -n com.snowyghost.ovenlight/.MainActivity`,
as the iPhone app takes launch arguments:

- `--es controlURL <url> --es authKey <key>` signs the owner's node in to a local
  Headscale with a key.
- `--es joinInvite <link>` opens an invite.
- `--es openApp <name>` opens an app. `ovenlight://open?app=<name>` does the same in any
  build, and so does `ovenlight://open?id=<app ID>`, which home screen shortcuts use.
  Both wait for the unlock.
- `--ez lockNow true` locks Ovenlight.
- `--ez sharingFixture true` fills in sample sharing data for screenshots.
- `--ez fakeOldWebView true` shows the screen that asks for a WebView update.

`DebugLaunch.kt` lists the rest, including the lab that first checked the proxy and
profiles. As on iPhone, debug builds also accept an `http` control server on loopback in
invites. To reach a Headscale on your own computer from an emulator, forward its port
with `adb reverse`. Debug builds trust one more certificate authority, for the
certificates `ovenlight run --dev-tls-cert` serves: `test-ca/ca.pem` beside the SDK
folder, which `e2e.sh` makes, or else a throwaway one.

### How the Android app differs

- **One proxy.** Every page and every request of Ovenlight's own (probes, claims, the
  admin API, feedback, downloads, through OkHttp) goes through one password-protected
  proxy in the Go core (`android/core/proxy.go`, `TailnetCore.kt`), and each app has its
  own WebView profile. [security.md](security.md#on-the-phone) says what it refuses, and
  [On Android](security.md#on-android) how it's guarded.
- **The lock** is Android's biometric prompt: a fingerprint or face, or the screen lock.
  Android 10's prompt can't fall back to the screen lock, so there a button opens the
  screen lock's own screen, at once when no fingerprint or face is enrolled
  (`UnlockPrompt.kt`). The lock screen is a window of its own (`LockWindow` in
  `MainActivity.kt`).
- **Coming and going.** A trip out that hands something back (the sign-in tab, a picker,
  the lock's prompt) keeps nodes and pages running for five minutes. Any other departure,
  or a trip out past five minutes, pauses polling and pages' timers, and after a minute
  closes every node, whose state stays on disk. Coming back starts them again
  (`AppLifecycle.kt`).
- **Tailscale's sign-in and Machines page** open in a Custom Tab, in the phone's browser,
  since Google refuses sign-ins inside web views. A Custom Tabs session keeps Ovenlight
  running behind the tab in the browser, so it comes back by itself once the phone is
  approved.
- **Invites** open Ovenlight as App Links (the site's `assetlinks.json`), as
  `ovenlight://join` links with the fields in the query or the fragment, pasted, or
  scanned in Ovenlight with CameraX and ZXing.
- **Pages.** Back goes back in the page, then to the launcher. The page script is
  `assets/page.js`. [app-contract.md](app-contract.md#in-the-android-app) says what it and
  downloads do.

### Shipping on Google Play

`android/gradlew -p android --no-configuration-cache :app:bundleRelease` builds the
release bundle, for all three ABIs, signed with the upload key (PKCS12, alias `upload`)
when `PLAY_UPLOAD_KEYSTORE` names its file and `PLAY_UPLOAD_PASSWORD` holds its password.
`android/scripts/verify-bundle.sh` checks the bundle: the target SDK, every native library
aligned for 16 KB pages, the license notices, no advertising ID or media permissions, the
camera and microphone not required, WebView's metrics and Safe Browsing off, and nothing
backed up or moved to a new phone. Play signs what reaches phones with Play App Signing,
and gives every upload its `versionCode`: the Gradle Play Publisher plugin, on only when
`PLAY_CREDENTIALS` names a service account's key, asks Play for one past the highest on
any track.

### Forking the Android app

To build and sign your own copy, change `applicationId` in `android/app/build.gradle.kts`,
`AppLinks` in `Support.kt`, the App Link host in `AndroidManifest.xml` and
`InviteLink.kt`, the invite host in `connector/invite.go`, and the package in
`site/join.js`'s `intent:` link, and serve your own `assetlinks.json` with your signing
key. On Android 12 and later, an App Link opens only an app whose key the site lists, so
in a fork's build an invite opens the join page, whose Open in Ovenlight opens the app
that `site/join.js` names.
