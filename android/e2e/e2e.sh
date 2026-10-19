#!/bin/bash
# Ovenlight for Android, end to end on the mini's emulator: a local Headscale, a dev
# connector serving two test apps, and the debug build on Ovenlight_API36, which reaches
# both through adb reverse. Nothing reaches the real tailnet or the live connector: the
# connector runs from this run's folder with its own HOME, config and state, logs in only to
# the local Headscale, and stops by its recorded pid.
#
#   android/e2e/e2e.sh [gates | signin [seconds] | screens | app | chrome | downloads | upload | return | guests | sharing | shortcuts | conditions | perf] [avd]
#
# gates runs Phase 1's checks G2 to G5 through the debug lab. signin goes through the
# launcher's Connect card and the sign-in page in Chrome's Custom Tab, approves the phone
# after [seconds] (default 90: Android freezes an app about a minute after it goes out of
# sight), checking first that Chrome keeps Ovenlight running behind the tab, and checks that
# Ovenlight comes back over the tab by itself, connected, and lets Chrome go. screens signs
# the owner's node in with a preauth key, takes the launcher, Settings and lock screenshots in
# light and dark, and checks the lock: at Immediately, Home and back through the icon or Recents asks
# for it, and a canceled prompt doesn't come back by itself. app opens Recipes from the
# launcher and waits for its page, then stops Pantry on the connector and checks that opening it
# says it isn't running, never showing the connector's 502 text as the page, and that Try again
# opens it once it runs again.
# chrome opens Recipes, closes the pill's one-time tip, brings the pill back with its handle,
# opens the app menu and writes feedback, locks and unlocks with the note still there, sends
# it, and checks that the connector got it, screenshot and all, with no pill or handle in the
# screenshot.
# downloads opens a Recipes page that downloads a cookie-gated file over http and a blob, and
# checks each arrives in Ovenlight's sheet with the bytes the page meant, that Share (and Open,
# where a viewer exists) leave Ovenlight's task, and that Save writes each under its own name;
# then, if the emulator's camera answers, that locking stops the page's camera without
# discarding the page.
# upload pushes a file into Download and a PNG into Pictures, picks each through the real
# pickers (documents for a plain input, the photo picker from Ovenlight's sheet for an image
# input), and checks the page got its name and size and the app's server its bytes; then that
# canceling either picker answers the page, so the next tap opens one again.
# return checks that a page keeps running through a Custom Tab it opened: the same page, its
# first WebSocket still open and answering, and its timers never held up for more than
# Chromium's once a second out of sight. Then a POST to Pantry, which the connector refuses
# from another site, goes back and opens Pantry outside.
# guests has the connector's owner, Owen, share Recipes with a phone that has no account. It
# joins twice, each through the claim: by an App Link and by a link pasted under Join with
# invite, and the invite scanner opens with the camera. An invite arriving from the join
# page while Recipes is open closes it and shows the join screen. A revoke on the connector takes
# the app away and says so once, and Leave removes it and logs the phone out, after which the
# same link asks to join again. No invite's key reaches the system log.
# sharing makes Recipes shareable and shares it from the phone: the tile menu's Share, the
# invite composer with the screen lock asked for again, Send invite and Copy link, then People
# from the menu, where the owner cancels the invite and removes a guest who joined from a
# phone of their own (guest.go), checked on the connector. On Android 10 it confirms an invite
# on the screen lock's own screen, and leaves that screen for Home. A second owner on a second
# Headscale invites the phone, which joins from the link.
# shortcuts pins Recipes to the home screen from its menu through the launcher's own dialog,
# and with the lock at Immediately opens it from there through the lock, warm and after Android
# ends Ovenlight's process. A rename reaches the shortcut, Remove app disables it so a tap opens
# nothing, and a link to the removed app's ID opens no app.
# conditions keeps Recipes open while the phone changes under it: rotation, dark mode, the
# largest font and three-button navigation leave the same page working; with Wi-Fi off and
# back on it still reaches Recipes; Pantry opened in airplane mode says it's offline at once and
# loads once the network is back; after more than a minute at Home the nodes have closed and
# the page works again once back; and after Android ends the process in the background, Recents
# brings Ovenlight back unlocked within the lock's time, with no app open, and Recipes opens again.
# perf records cold start over ten launches, memory once four apps have opened (the pool keeps
# two pages live on a phone of 4 GB or less, four above), that opening, closing and rotating
# leaves one activity and no more web views than the pool keeps, and, at Home, that
# Ovenlight holds no wake lock, with its alarms, jobs, freezer state and CPU time over two
# minutes. On a debuggable build: ORG_GRADLE_PROJECT_minify=true comes nearer a release.
# Each writes what it saw to
# android/e2e/out/: gates.txt, logs and screenshots.
#
# OVENLIGHT_E2E_PORTS limits the emulator ports tried (5554 to 5582 by default), for a run
# beside another session's emulators. OVENLIGHT_E2E_CONDITION runs a scenario in dark mode
# (dark), at the largest font (font), with three-button navigation (threebutton) or in
# landscape (landscape). ORG_GRADLE_PROJECT_minify=true runs it on the debug build with R8 on,
# as a release build is shrunk (-Pminify).
# Needs Headscale v0.29.4 (docs/development.md#local-headscale) and the fixed AVD set
# (android/scripts/avds.sh). The test CA lives in test-ca/ beside the SDK, made on the first
# run, and the debug build trusts it, so this builds the app itself.
# bash, not zsh: zsh skips its EXIT trap when err_exit fires inside a function, which would
# leave Headscale, the connector and the emulator running after a failure.
set -Eeuo pipefail
# A grep fed by a pipe reads all its input (>/dev/null, never -q): one that stops at its first
# match can leave the writer a closed pipe, which pipefail counts as a failure and ! as a pass.
# A shell that didn't read the profile may lack adb, and the free port check would then count
# every port as free.
PATH=${ANDROID_HOME:?set ANDROID_HOME}/platform-tools:$PATH
command -v adb > /dev/null || { echo "no adb in $ANDROID_HOME/platform-tools" >&2; exit 1; }

SCENARIO=${1:-gates}
SIGNIN_WAIT=90
[[ $SCENARIO == signin && ${2:-} =~ ^[0-9]+$ ]] && { SIGNIN_WAIT=$2; shift; }
AVD_ARG=${2:-}
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT=$HERE/out
HS=$(go env GOPATH)/bin/headscale
AVD=${AVD_ARG:-Ovenlight_API36}
PKG=com.snowyghost.ovenlight
BASE=e2e.ts.net
CA=${ANDROID_HOME:?set ANDROID_HOME}/../test-ca
D=$(mktemp -d "${TMPDIR:-/tmp}/ovl-e2e.XXXX")
mkdir -p $OUT
rm -f "$OUT"/*.png "$OUT"/*.log "$OUT/gates.txt"
pids=
connector_pid=
emulator_port=
chrome_flags=

say() { printf '== %s\n' "$*"; printf '%s\n' "$*" >> "$OUT/gates.txt"; }
note() { printf '   %s\n' "$*"; printf '   %s\n' "$*" >> "$OUT/gates.txt"; }
port() { python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
S=
a() { adb -s "$S" "$@"; }
shot() { [[ -n $emulator_port ]] && a exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
fail() { printf 'FAILED: %s\n' "$*"; printf 'FAILED: %s\n' "$*" >> "$OUT/gates.txt"; shot failed; exit 1; }

stop_connector() {
  [[ -n $connector_pid ]] || return 0
  kill $connector_pid 2>/dev/null || true
  for i in $(seq 1 20); do kill -0 $connector_pid 2>/dev/null || break; sleep 1; done
  ! kill -0 $connector_pid 2>/dev/null || kill -9 $connector_pid 2>/dev/null || true
  wait $connector_pid 2>/dev/null || true
  connector_pid=
}

cleanup() {
  stop_connector
  for p in $pids; do kill "$p" 2>/dev/null || true; wait "$p" 2>/dev/null || true; done
  cp $D/*.log $OUT/ 2>/dev/null || true
  if [[ -n $emulator_port ]]; then
    a logcat -d > $OUT/logcat.log 2>/dev/null || true
    # Chrome without the flags chrome_without_first_run gave it, so the AVD is as it was.
    if [[ -n ${chrome_flags:-} ]]; then
      a shell rm -f /data/local/tmp/chrome-command-line >/dev/null 2>&1 || true
      a shell am clear-debug-app >/dev/null 2>&1 || true
    fi
    a uninstall $PKG >/dev/null 2>&1 || true
    a reverse --remove-all >/dev/null 2>&1 || true
    $ROOT/android/scripts/avds.sh stop $emulator_port || true
  fi
  rm -r "${D:?}"
}
trap cleanup EXIT
# A command that fails outside fail() still leaves a FAILED line saying where.
# Only the main shell's: a command substitution's own failures are its caller's to judge.
trap '[[ $BASH_SUBSHELL == 0 ]] && printf "FAILED: line %s: %s\n" "${BASH_LINENO[0]:-$LINENO}" "$BASH_COMMAND" | tee -a "$OUT/gates.txt" >&2' ERR

until_ok() {
  local seconds=$1; shift
  for i in $(seq 1 $seconds); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

[[ -x $HS ]] || fail "no headscale at $HS"
[[ $SCENARIO =~ ^(gates|signin|screens|app|chrome|downloads|upload|return|guests|sharing|shortcuts|conditions|perf)$ ]] || fail "unknown scenario $SCENARIO"
[[ -z ${OVENLIGHT_E2E_CONDITION:-} || $OVENLIGHT_E2E_CONDITION =~ ^(dark|font|threebutton|landscape)$ ]] || fail "unknown condition $OVENLIGHT_E2E_CONDITION"

say "the test CA in $CA, made once, and a *.$BASE certificate for this run"
if [[ ! -f $CA/ca.pem ]]; then
  mkdir -p $CA && chmod 700 $CA
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj "/CN=Ovenlight e2e test CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign" -keyout $CA/ca.key -out $CA/ca.pem 2>/dev/null
fi
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=*.$BASE" -keyout $D/leaf.key -out $D/leaf.csr 2>/dev/null
# The second owner's network, in sharing, is riley.$BASE.
printf 'subjectAltName=DNS:*.%s,DNS:*.riley.%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature\nbasicConstraints=critical,CA:FALSE\n' $BASE $BASE > $D/leaf.ext
openssl x509 -req -in $D/leaf.csr -CA $CA/ca.pem -CAkey $CA/ca.key -CAcreateserial -CAserial $D/ca.srl -days 2 -extfile $D/leaf.ext -out $D/leaf.pem 2>/dev/null

say "the debug build, which trusts that CA"
(cd $ROOT/android && ./gradlew -q :app:assembleDebug) > $D/gradle.log 2>&1 || fail "the build failed: $(tail -20 $D/gradle.log)"
APK=$ROOT/android/app/build/outputs/apk/debug/app-debug.apk

say "headscale on 127.0.0.1, base domain $BASE"
HSPORT=$(port) METRICS=$(port) GRPC=$(port)
CTRL=http://127.0.0.1:$HSPORT
# What setup-sharing and publish --shareable write for app <slug>, with Headscale's user syntax
# for the owner $1: their own devices, the app's node, and its guests to its port 443 alone.
# Recipes' tags go unused until the guests or sharing scenario makes it shareable.
policy() {
  local owner=$1 slug=$2
  printf '{"tagOwners": {"tag:ovenlight-app-%s": ["%s"], "tag:ovenlight-guest-%s": ["%s"]},\n' $slug $owner $slug $owner
  printf ' "acls": [{"action": "accept", "src": ["%s"], "dst": ["%s:*", "tag:ovenlight-app-%s:*"]},\n' $owner $owner $slug
  printf '          {"action": "accept", "src": ["tag:ovenlight-guest-%s"], "dst": ["tag:ovenlight-app-%s:443"]}]}\n' $slug $slug
}
policy owner@ recipes > $D/policy.hujson
cat > $D/headscale.yaml <<EOF
server_url: $CTRL
listen_addr: 127.0.0.1:$HSPORT
metrics_listen_addr: 127.0.0.1:$METRICS
grpc_listen_addr: 127.0.0.1:$GRPC
grpc_allow_insecure: false
noise:
  private_key_path: $D/noise_private.key
prefixes:
  v4: 100.64.0.0/10
  v6: fd7a:115c:a1e0::/48
  allocation: sequential
derp:
  server:
    enabled: false
  urls:
    - https://controlplane.tailscale.com/derpmap/default
  paths: []
  auto_update_enabled: false
  update_frequency: 24h
disable_check_updates: true
node:
  expiry: 0
database:
  type: sqlite
  sqlite:
    path: $D/db.sqlite
    write_ahead_log: true
log:
  level: info
  format: text
policy:
  mode: file
  path: $D/policy.hujson
dns:
  magic_dns: true
  base_domain: $BASE
  override_local_dns: false
  nameservers:
    global: []
    split: {}
  search_domains: []
  extra_records: []
unix_socket: $D/hs.sock
unix_socket_permission: "0770"
logtail:
  enabled: false
taildrop:
  enabled: false
EOF
$HS -c $D/headscale.yaml serve > $D/headscale.log 2>&1 &
pids="$pids $!"
until_ok 30 $HS -c $D/headscale.yaml users list || fail "headscale didn't start"
$HS -c $D/headscale.yaml users create owner >/dev/null
OWNER_ID=$($HS -c $D/headscale.yaml users list -o json | python3 -c 'import json, sys; print(json.load(sys.stdin)[0]["id"])')
KEY=$($HS -c $D/headscale.yaml preauthkeys create -u $OWNER_ID --reusable -e 2h -o json | python3 -c 'import json, sys; print(json.load(sys.stdin)["key"])')

say "two apps: Recipe Box (a WebSocket, a fetch of the proxy's own port, and subresources from Pantry and the internet) and Pantry"
mkdir -p $D/recipes $D/pantry
cat > $D/recipes/index.html <<'EOF'
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>loading</title><script>var out = {};</script><link rel="stylesheet" href="https://pantry.e2e.ts.net/style.css" onload="out.css = 'ok'; show()" onerror="out.css = 'error'; show()"></head>
<body><h1>Recipe Box</h1><img src="https://pantry.e2e.ts.net/dot.svg" width="20" onload="out.img = 'ok'; show()" onerror="out.img = 'error'; show()"><p id="out"></p>
<script>
function show() { if (!document.getElementById("out")) return; document.title = JSON.stringify(out); document.getElementById("out").textContent = document.title; }
const ws = new WebSocket("wss://recipes.e2e.ts.net/ws");
ws.onopen = () => ws.send("ping");
ws.onmessage = e => { out.ws = e.data; show(); ws.close(); };
ws.onerror = () => { out.ws = "error"; show(); };
fetch("https://ovenlight.app/", { mode: "no-cors" }).then(() => { out.internet = "ok"; show(); }, e => { out.internet = "error"; show(); });
const port = new URLSearchParams(location.hash.slice(1)).get("port");
if (port) fetch("http://127.0.0.1:" + port + "/", { mode: "no-cors" }).then(() => { out.proxy = "sent"; show(); }, e => { out.proxy = "error " + e.name; show(); });
</script></body></html>
EOF
printf '%s\n' 'body { background: #fbf4ea; }' > $D/pantry/style.css
printf '%s\n' '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2 2"><circle cx="1" cy="1" r="1" fill="#c0392b"/></svg>' > $D/pantry/dot.svg
printf '%s\n' '<!doctype html><meta charset="utf-8"><title>Pantry</title><h1>Pantry</h1>' > $D/pantry/index.html
apps="recipes:Recipes pantry:Pantry"
# perf opens four apps.
if [[ $SCENARIO == perf ]]; then
  apps="$apps garden:Garden notes:Notes"
  for app in garden notes; do mkdir -p $D/$app; printf '<!doctype html><meta charset="utf-8"><title>%s</title><h1>%s</h1>\n' $app $app > $D/$app/index.html; done
fi

say "the connector, built from the tree, with its own HOME, config and state"
export GOCACHE="$(go env GOCACHE)" GOMODCACHE="$(go env GOMODCACHE)"
go -C $ROOT/connector build -o $D/ovenlight .
mkdir -p $D/home
c() { env HOME=$D/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight "$@"; }
for app in $apps; do
  name=${app#*:} app=${app%%:*}
  c publish --config $D/config.json --state $D/st --port $(port) --name "$name" --slug $app \
    --run "python3 $ROOT/android/e2e/app.py" --dir "$D/$app" >> $D/publish.log 2>&1 || fail "publish $app: $(cat $D/publish.log)"
done
( exec env HOME=$D/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight run --config $D/config.json --state $D/st \
    --control-url $CTRL --auth-key $KEY --dev-tls-cert $D/leaf.pem --dev-tls-key $D/leaf.key \
    --dev-headscale $HS --dev-headscale-config $D/headscale.yaml >> $D/connector.log 2>&1 ) &
connector_pid=$!
until_ok 120 sh -c "env HOME=$D/home $D/ovenlight status --config $D/config.json --state $D/st --json | grep -c '\"state\": \"serving\"' | grep -qx $(wc -w <<<"$apps" | tr -d ' ')" ||
  fail "the connector didn't serve every app"

say "$AVD, booted from ready with a network capture, and the app"
for p in ${OVENLIGHT_E2E_PORTS:-$(seq 5554 2 5582)}; do
  if ! adb devices | grep "^emulator-${p}[[:space:]]" >/dev/null; then emulator_port=$p; break; fi
done
[[ -n $emulator_port ]] || fail "no free emulator port"
OVENLIGHT_AVD_FLAGS="${OVENLIGHT_AVD_FLAGS:-} -tcpdump $D/emulator.pcap" $ROOT/android/scripts/avds.sh boot $AVD $emulator_port >/dev/null || { emulator_port=; fail "the emulator didn't boot"; }
S=emulator-$emulator_port
[[ -z $(a shell pm path $PKG 2>/dev/null) ]] || fail "Ovenlight is already on $AVD; this run would replace it"
a reverse tcp:$HSPORT tcp:$HSPORT >/dev/null
a install $APK >/dev/null
a shell "am start -W -n $PKG/.MainActivity --ez writeProxySecret true" >/dev/null
until_ok 20 a shell run-as $PKG cat files/proxy.txt || fail "the app didn't write its proxy file"
PROXY_PORT=$(a shell run-as $PKG sed -n 1p files/proxy.txt | tr -d '\r' | sed 's/.*://')

# Runs a lab command and waits for its result line in logcat.
since=
lab() {
  since=$(a shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
  a shell "am start -n $PKG/.MainActivity --es lab $*" >/dev/null 2>&1
}
# Logcat can carry bytes that aren't UTF-8, so grep and sed read it as bytes.
lablog() { a logcat -d -T "${1:-$since}" -s OvenlightLab 2>/dev/null | LC_ALL=C grep 'OvenlightLab:' | LC_ALL=C sed 's/.*OvenlightLab: //' || true; }
corelog() { a logcat -d -T "${1:-$since}" -s OvenlightCore 2>/dev/null | LC_ALL=C grep 'OvenlightCore:' | LC_ALL=C sed 's/.*OvenlightCore: //' || true; }
# Waits up to $1 seconds for a lab line matching $2, and prints it.
await() {
  local seconds=$1 pattern=$2 line
  for i in $(seq 1 $((seconds * 2))); do
    line=$(lablog | grep -E -- "$pattern" | tail -1 || true)
    if [[ -n $line ]]; then printf '%s\n' "$line"; return 0; fi
    sleep 0.5
  done
  return 1
}
# The value of a script in the open view, after $2 seconds for its promises.
evaljs() {
  lab "eval --es labJs '$1'"
  sleep ${2:-1}
  lab "eval --es labJs 'window.labResult || document.title'"
  await 10 '^eval ' | sed -e 's/^eval [^:]*: //' -e 's/\\"/"/g' || true
}

# The screen's text and content descriptions, one per line.
screen_text() {
  # Values with a double quote are quoted with single quotes in the dump.
  a exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import html, re, sys
for m in re.finditer(r"(?<![\w-])(?:text|content-desc)=(?:\"([^\"]*)\"|\x27([^\x27]*)\x27)", sys.stdin.read()):
    value = html.unescape(m.group(1) if m.group(1) is not None else m.group(2))
    if value: print(value)
'
}
# The bounds, "x1 y1 x2 y2", of the first element whose text or description is exactly $2, or
# with class=text whose class is $2 (android.widget.EditText, say), or with match=text whose text
# or description matches the regular expression $2.
bounds() {
  a exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' | python3 -c '
import re, sys
by, want = sys.argv[1], sys.argv[2]
for line in sys.stdin:
    if by == "class":
        hit = "class=\"%s\"" % want in line
    elif by == "match":
        hit = any(re.search(want, v) for v in re.findall(r"(?:text|content-desc)=\"([^\"]*)\"", line))
    else:
        hit = re.search(r"(text|content-desc)=\"%s\"" % re.escape(want), line)
    m = hit and re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", line)
    if m:
        print(*m.groups()); break
' "$1" "$2"
}
# Taps the middle of the first element found by bounds, by text (tap_text), class (tap_class) or pattern (tap_matching).
tap_bounds() {
  local box
  box=($(bounds "$1" "$2"))
  [[ ${#box[@]} == 4 ]] || return 1
  a shell input tap $(((box[0] + box[2]) / 2)) $(((box[1] + box[3]) / 2))
}
tap_text() { tap_bounds text "$1"; }
tap_class() { tap_bounds class "$1"; }
tap_matching() { tap_bounds match "$1"; }
# Touches and holds what's named $1 (a tile's name), as a person does to open its menu.
hold() {
  local box
  # uiautomator's dump now and then comes back empty while the screen animates.
  for i in 1 2 3 4 5; do
    box=($(bounds text "$1"))
    [[ ${#box[@]} == 4 ]] && break
    sleep 1
  done
  [[ ${#box[@]} == 4 ]] || return 1
  a shell input swipe $(((box[0] + box[2]) / 2)) $(((box[1] + box[3]) / 2)) $(((box[0] + box[2]) / 2)) $(((box[1] + box[3]) / 2)) 900
}
# The first app Ovenlight opens shows its pill with a one-time tip, in a popup of its own: closed here.
close_tip() { if await_text ${1:-10} "Got it"; then tap_text "Got it"; sleep 1; fi; }
# Waits up to $1 seconds until every one of the other arguments is on screen.
await_text() {
  local seconds=$1; shift
  for i in $(seq 1 $seconds); do
    local seen=$(screen_text) missing=0
    # Back after the lock's delay: answer its prompt with fingerprint 1.
    if [[ -z ${KEEP_LOCK:-} ]] && grep -qxE 'Unlock Ovenlight|Ovenlight is locked' <<<"$seen"; then a emu finger touch 1 >/dev/null; sleep 1; continue; fi
    for want in "$@"; do grep -qxF -- "$want" <<<"$seen" || missing=1; done
    (( missing == 0 )) && return 0
    sleep 1
  done
  return 1
}

night() { a shell cmd uimode night $1 >/dev/null; sleep 3; }
nav() { a shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.$1 >/dev/null; sleep 3; }
rotate() { a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation $1; sleep 3; }
font() { a shell settings put system font_scale $1; sleep 3; }
locked() { screen_text | grep -xE 'Unlock Ovenlight|Ovenlight is locked' >/dev/null; }
# Locks Ovenlight where it is. The activity pauses and resumes for the intent, so the system's
# unlock prompt ("Unlock Ovenlight") opens over the lock screen at once, as on a return; its
# window is secure (black in a screenshot) and the only one uiautomator reads, so either counts.
lock_now() {
  a shell "am start -n $PKG/.MainActivity --ez lockNow true" >/dev/null
  for i in $(seq 1 10); do locked && return 0; sleep 1; done
  fail "lockNow didn't lock: $(screen_text | tr '\n' '|')"
}
# Answers the prompt with fingerprint 1, opening it first from the lock screen if it was
# closed, and fails if Ovenlight stays locked.
unlock_now() {
  # The prompt may still be on its way over the lock screen.
  local i
  for i in 1 2 3 4; do screen_text | grep -xF "Unlock Ovenlight" >/dev/null && break; sleep 1; done
  screen_text | grep -xF "Unlock Ovenlight" >/dev/null || { tap_text Unlock || true; sleep 2; }
  for i in 1 2 3; do
    a emu finger touch 1 >/dev/null
    sleep 2
    locked || return 0
  done
  fail "Ovenlight didn't unlock: $(screen_text | tr '\n' '|')"
}

# The activity in front, as the activity manager names it.
front() { a shell dumpsys activity activities | LC_ALL=C grep -m1 -E 'topResumedActivity|mResumedActivity' || true; }
# Whether service $1 (a component) has a binding from app $2's process.
bound() { a shell dumpsys activity services "$1" | LC_ALL=C grep -E "ProcessRecord\{[^}]* [0-9]+:$2/" >/dev/null; }
# Waits up to $1 seconds for an activity other than Ovenlight in front, with the address $2 in its intent.
await_outside() {
  for i in $(seq 1 $1); do
    # Read whole: a grep that stops at its first match would break adb's pipe under pipefail.
    if [[ $(front) != *$PKG* && $(a shell dumpsys activity activities) == *"dat=$2"* ]]; then return 0; fi
    sleep 1
  done
  return 1
}
# Backs out of whatever the browser shows (the Custom Tab, or a page of its own) until
# Ovenlight is in front, then waits for the text $1; $2 says what for. A browser that keeps the
# front is closed by bringing Ovenlight back, which clears what sits above it in its task.
back_to() {
  for i in 1 2 3; do
    [[ $(front) == *$PKG* ]] && break
    a shell input keyevent KEYCODE_BACK; sleep 2
  done
  [[ $(front) == *$PKG* ]] || a shell am start -n $PKG/.MainActivity >/dev/null
  await_text 20 "$1" || fail "$2: Ovenlight didn't come back to the page: $(front) $(screen_text | tr '\n' '|')"
}
# Chrome's first run would stay in front of every tab: Chrome reads these flags as the debug
# app, and cleanup takes them away again.
chrome_without_first_run() {
  [[ -n $(a shell pm path com.android.chrome 2>/dev/null) ]] || return 0
  chrome_flags=1
  a shell 'echo "_ --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
  a shell am set-debug-app --persistent com.android.chrome >/dev/null
  a shell am force-stop com.android.chrome
}
# Signs the owner in with the preauth key, and waits for the launcher.
owner_launcher() {
  a shell "am start -W -n $PKG/.MainActivity --es controlURL $CTRL --es authKey $KEY" >/dev/null
  await_text 90 Recipes Pantry || fail "the launcher never showed both apps: $(screen_text | tr '\n' '|')"
}
# More, then Settings, from the launcher.
open_settings() {
  tap_text More || fail "no More: $(screen_text | tr '\n' '|')"
  await_text 5 Settings || fail "no Settings in More: $(screen_text | tr '\n' '|')"
  tap_text Settings
  await_text 10 "Require screen lock" || fail "no Settings: $(screen_text | tr '\n' '|')"
}
# Sets the lock to Immediately in Settings, and goes back to the launcher.
lock_immediately() {
  open_settings
  tap_text "Require screen lock" && sleep 1 && tap_text Immediately
  await_text 10 Immediately || fail "the delay didn't change"
  a shell input keyevent KEYCODE_BACK; sleep 2
}
# Back to Ovenlight from elsewhere through its icon, or through Recents ($1), tapping the card in the middle.
back_through() {
  if [[ $1 == icon ]]; then
    a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  else
    a shell input keyevent KEYCODE_APP_SWITCH; sleep 3
    local size=$(a shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r')
    a shell input tap $((${size%x*} / 2)) $((${size#*x} / 2))
  fi
  sleep 3
}
# Android ends Ovenlight's process at Home, as it does for memory. Not force-stop: the
# launcher hides a stopped app's shortcuts until it runs again.
end_in_background() {
  a shell input keyevent KEYCODE_HOME; sleep 2
  a shell am kill $PKG
  until_ok 10 sh -c "[ -z \"\$(adb -s $S shell pidof $PKG)\" ]" || fail "Ovenlight's process didn't end"
}

# The connector's status, and the user app $1's node belongs to.
status_json() { c status --config $D/config.json --state $D/st --json; }
owner_of() { python3 -c 'import json, sys; print(next(a["nodeUser"] for a in json.load(sys.stdin)["apps"] if a["slug"] == sys.argv[1]))' "$1"; }
# Names the owner in connector config $1: user $2, called $3. It goes in by hand, as the
# connector's own e2e test does: publish --shareable records one only after a person types yes.
set_owner() { python3 -c 'import json, sys; c = json.load(open(sys.argv[1])); c.update(owner=sys.argv[2], ownerLabel=sys.argv[3]); json.dump(c, open(sys.argv[1], "w"))' "$@"; }
# Every app serves, and Recipes as a shareable app node.
serving_shareable() { status_json | python3 -c '
import json, sys
apps = {a["slug"]: a for a in json.load(sys.stdin)["apps"]}
ok = all(a.get("state") == "serving" for a in apps.values()) and "tag:ovenlight-app-recipes" in (apps["recipes"].get("tags") or [])
sys.exit(0 if ok else 1)'; }
# Makes Recipes shareable, its owner called $1.
shareable_recipes() {
  set_owner $D/config.json "$(status_json | owner_of recipes)" "$1"
  c publish --config $D/config.json --state $D/st --slug recipes --shareable >> $D/publish.log 2>&1 || fail "publish --shareable: $(tail -5 $D/publish.log)"
  until_ok 120 serving_shareable || fail "Recipes never served as a shareable app node: $(status_json)"
}
# Shares app $1 with $2 from a terminal, as the connector requires, and prints the invite's
# link. $3 is another connector's folder (Riley's) in place of this run's; the link stays there.
share() {
  local at=${3:-$D}
  script -q /dev/null env HOME=$at/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight share $1 --to "$2" \
    --config $at/config.json --state $at/st < /dev/null > $at/share-$2.txt 2>&1 || fail "sharing $1 with $2: $(tail -5 $at/share-$2.txt)"
  LC_ALL=C grep -o -E 'https://ovenlight\.app/join#[^[:space:][:cntrl:]]+' $at/share-$2.txt | head -1
}

case ${OVENLIGHT_E2E_CONDITION:-} in
dark) say "in dark mode"; night yes ;;
font) say "at the largest font"; font 2.0 ;;
threebutton) say "with three-button navigation"; nav threebutton ;;
landscape) say "in landscape"; rotate 1 ;;
esac

if [[ $SCENARIO == guests ]]; then
  say "sharing: Owen owns the connector, and Recipes runs as a shareable app node"
  shareable_recipes Owen
  # The whole log from here on: the device's ring buffer may drop the lines the key check needs.
  a logcat > $OUT/logcat-guests.log 2>/dev/null &
  pids="$pids $!"
  key_of() { python3 -c 'import sys, urllib.parse; print(urllib.parse.parse_qs(sys.argv[1].split("#", 1)[1])["key"][0])' "$1"; }
  keys=()
  question="Join Owen’s Recipes?"
  # Joins from the question, through the claim, and checks the connector counts the guest.
  join() { # name how
    tap_text Join || fail "$2: no Join button: $(screen_text | tr '\n' '|')"
    local start=$SECONDS
    await_text 120 "Recipes is ready" "Open Recipes" || { shot guests-failed; fail "$2: never joined: $(screen_text | tr '\n' '|')"; }
    note "$2: joined and claimed in $((SECONDS - start)) s"
    c guests --config $D/config.json --state $D/st > $D/guests.txt 2>&1 || true
    grep -q "$1" $D/guests.txt || fail "$2: the connector doesn't list $1: $(cat $D/guests.txt)"
  }

  say "guests: Sam's invite arrives as an App Link"
  link=$(share recipes Sam)
  [[ -n $link ]] || fail "no link for Sam"
  keys+=("$(key_of "$link")")
  a shell "am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -p $PKG -d '$link'" >/dev/null
  await_text 20 "$question" "Not now" "This invite uses 127.0.0.1, a connection server Owen chose. Join only if you know this invite came from Owen." ||
    { shot guests-failed; fail "the App Link didn't ask to join: $(screen_text | tr '\n' '|')"; }
  shot guests-question
  join Sam "by App Link"
  shot guests-joined
  tap_text "Open Recipes"
  close_tip
  await_text 40 "Recipe Box" || { shot guests-failed; fail "Recipes never loaded for the guest: $(screen_text | tr '\n' '|')"; }
  note "Recipes opened for the guest"

  say "guests: the same invite from the join page, while Recipes is open"
  # The join page's link, which Android parses as the browser does: the fields in the fragment.
  a shell "am start -W 'intent://join#${link#*#}#Intent;scheme=ovenlight;package=$PKG;end'" >/dev/null
  # Joined this session, so the screen says so; after a relaunch it would offer Join again too.
  await_text 20 "Recipes is ready" "Open Recipes" || { shot guests-failed; fail "no join screen over the open app: $(screen_text | tr '\n' '|')"; }
  ! screen_text | grep -xF "Recipe Box" >/dev/null || fail "Recipes is still open under the join screen"
  shot guests-again
  note "Recipes closed, and the join screen says it's ready"
  start_line=$(a logcat -d | LC_ALL=C grep -E 'START u0 .*dat=ovenlight:' | tail -1 || true)
  [[ -n $start_line ]] || fail "no activity START line for the invite"
  [[ $start_line != *"${keys[0]}"* && $start_line != *key=* ]] || fail "the START line shows the invite's key"
  note "its START line shows no key: ${start_line#*START u0 }"

  say "guests: Owen revokes Sam with Recipes open"
  tap_text "Open Recipes"
  await_text 20 "Recipe Box" || fail "Recipes didn't open again: $(screen_text | tr '\n' '|')"
  c revoke Sam --config $D/config.json --state $D/st > $D/revoke.txt 2>&1 || fail "revoke: $(cat $D/revoke.txt)"
  start=$SECONDS
  await_text 300 "No longer shared with you" \
    "Owen stopped sharing Recipes with you, so Ovenlight removed it and everything it stored on this phone." ||
    { shot guests-failed; fail "the revoke never took Recipes away: $(screen_text | tr '\n' '|')"; }
  note "Recipes closed and went $((SECONDS - start)) s after the revoke, with the notice"
  shot guests-revoked
  tap_text OK
  await_text 20 "Welcome to Ovenlight" || fail "not back to the welcome: $(screen_text | tr '\n' '|')"
  ! screen_text | grep -xF "No longer shared with you" >/dev/null || fail "the notice showed again"
  [[ -z $(a shell run-as $PKG sh -c "'ls no_backup/tailscale 2>/dev/null'" | tr -d '\r') ]] || fail "the guest node's state is still on the phone"
  note "the notice showed once; the welcome is back, and the guest node's state is gone"

  say "guests: Kim's invite, pasted under Join with invite"
  link=$(share recipes Kim)
  [[ -n $link ]] || fail "no link for Kim"
  keys+=("$(key_of "$link")")
  tap_text "Join with invite"
  await_text 10 "Paste" "Invite link" || fail "no Join with invite: $(screen_text | tr '\n' '|')"
  # The text of the field, and whether it holds the whole link or none of it.
  field_text() {
    a exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import html, re, sys
node = re.search(r"<node[^>]*class=\"android.widget.EditText\"[^>]*>", sys.stdin.read())
text = node and re.search(r"(?<![\w-])text=\"([^\"]*)\"", node.group(0))
print(html.unescape(text.group(1)) if text else "")
'
  }
  typed() { [[ $(field_text) == "$link" ]]; }
  emptied() { [[ $(field_text) != *"${link: -12}"* ]]; }
  type_link() {
    tap_class android.widget.EditText
    # input text drops what comes after its first 150 or so characters, so the link goes in parts.
    for ((i = 0; i < ${#link}; i += 100)); do a shell "input text '${link:i:100}'"; done
    until_ok 15 typed || note "the field didn't show the whole link"
  }
  # Into the clipboard and out of the field, once every part has landed, then back with Paste.
  paste_link() {
    type_link
    a shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
    a shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_C
    a shell input keyevent KEYCODE_DEL
    until_ok 10 emptied || note "the field didn't empty"
    # On a phone on its side the keyboard takes the whole screen; Back puts it away.
    tap_text Paste || { a shell input keyevent KEYCODE_BACK; sleep 1; tap_text Paste; }
  }
  if (( $(a shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )); then
    # Under load a copy can miss: then it goes once more.
    paste_link
    await_text 20 "$question" || { note "the first paste didn't take, so it goes again"; paste_link; }
    how="pasted"
  else
    type_link
    tap_text Continue
    how="typed (no key combinations before Android 13)"
  fi
  await_text 20 "$question" || { shot guests-failed; fail "the $how link didn't ask to join: $(screen_text | tr '\n' '|')"; }
  join Kim "$how"

  say "guests: Leave, from the app's menu"
  tap_text Close
  await_text 10 "Recipes, from Owen" || fail "no Recipes on the launcher: $(screen_text | tr '\n' '|')"
  tile=($(bounds text "Recipes, from Owen"))
  a shell input swipe $(((tile[0] + tile[2]) / 2)) $(((tile[1] + tile[3]) / 2)) $(((tile[0] + tile[2]) / 2)) $(((tile[1] + tile[3]) / 2)) 800
  # Recipes is Owen's only app here, so its tile's Remove app does what Leave would, and Leave isn't there.
  await_text 10 "Remove app" || { shot guests-failed; fail "no Remove app in the tile's menu: $(screen_text | tr '\n' '|')"; }
  ! screen_text | grep -xF "Leave Owen’s apps" >/dev/null || fail "the tile's menu has Leave beside Remove app"
  a shell input keyevent KEYCODE_BACK
  await_text 10 "Recipes, from Owen" || fail "Back didn't close the tile's menu"
  tap_text "Recipes, from Owen"
  close_tip 5
  await_text 40 "Show app menu" || { shot guests-failed; fail "Recipes didn't open with its handle: $(screen_text | tr '\n' '|')"; }
  tap_text "Show app menu"
  await_text 5 "Recipes options" || fail "the handle didn't bring the pill back: $(screen_text | tr '\n' '|')"
  tap_text "Recipes options"
  await_text 10 "Leave Owen’s apps" || { shot guests-failed; fail "no Leave in the app's menu: $(screen_text | tr '\n' '|')"; }
  tap_text "Leave Owen’s apps"
  await_text 10 "Leave Owen’s apps?" || fail "Leave didn't ask first"
  tap_text Leave
  await_text 30 "Welcome to Ovenlight" || { shot guests-failed; fail "Leave didn't remove Recipes: $(screen_text | tr '\n' '|')"; }
  [[ -z $(a shell run-as $PKG sh -c "'ls no_backup/tailscale 2>/dev/null'" | tr -d '\r') ]] || fail "the guest node's state is still on the phone"
  # Headscale marks a node that logged out expired, and it goes offline.
  guest_out() {
    $HS -c $D/headscale.yaml nodes list -o json | python3 -c '
import json, sys, time
nodes = [n for n in json.load(sys.stdin) if "tag:ovenlight-guest-recipes" in n.get("tags", []) + n.get("forced_tags", []) + n.get("valid_tags", [])]
live = [n for n in nodes if n.get("online") or int((n.get("expiry") or {}).get("seconds", 0)) > time.time()]
sys.exit(0 if nodes and not live else 1)'
  }
  until_ok 20 guest_out || fail "Kim's device is still signed in: $($HS -c $D/headscale.yaml nodes list)"
  note "Leave removed Recipes and the guest node's state, and logged the phone out"

  say "guests: Kim's link again, after Leave"
  tap_text "Join with invite"
  await_text 10 "Paste" "Invite link" || fail "no Join with invite: $(screen_text | tr '\n' '|')"
  type_link
  tap_text Continue || { a shell input keyevent KEYCODE_BACK; sleep 1; tap_text Continue; }
  # Leave forgot how the join went, so the screen asks again rather than saying Recipes is ready.
  await_text 20 "$question" Join "Not now" || { shot guests-failed; fail "the link after Leave didn't ask to join: $(screen_text | tr '\n' '|')"; }
  tap_text "Not now"
  await_text 10 "Welcome to Ovenlight" || fail "Not now didn't go back to the welcome: $(screen_text | tr '\n' '|')"
  note "after Leave, the same link asks to join again"

  say "guests: the invite scanner"
  # The emulator's camera can't be pointed at a code once booted from its snapshot (it keeps the
  # snapshot's camera), so this checks the way in and out; InviteScannerTests reads a code.
  tap_text "Join with invite"
  # On a phone on its side it's below the fold, a swipe up.
  if ! await_text 5 "Scan invite code"; then
    w=$(a shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r'); w=${w%x*}
    a shell input swipe $((w / 2)) $((w * 3 / 4)) $((w / 2)) $((w / 4)) 300
  fi
  await_text 10 "Scan invite code" || fail "no Scan invite code: $(screen_text | tr '\n' '|')"
  tap_text "Scan invite code"
  # Android asks for the camera the first time.
  for i in $(seq 1 10); do
    if screen_text | grep -xE 'While using the app|Allow' >/dev/null; then tap_matching '^(While using the app|Allow)$'; break; fi
    sleep 1
  done
  await_text 10 "Point at the invite code" Cancel || { shot guests-failed; fail "the scanner didn't open: $(screen_text | tr '\n' '|')"; }
  scanning() { a shell dumpsys media.camera | tr -d '\r' | sed -n '/Active Camera Clients/,/Allowed user IDs/p' | grep "Client Package Name: $PKG," >/dev/null; }
  until_ok 15 scanning || fail "the scanner doesn't have the camera"
  shot guests-scanner
  tap_text Cancel
  await_text 10 "Scan invite code" || fail "Cancel didn't go back: $(screen_text | tr '\n' '|')"
  until_ok 10 eval '! scanning' || fail "the camera is still open after Cancel"
  note "the camera was asked for once, the scanner had it, and Cancel went back and let it go"

  say "guests: no invite's key in the system log"
  # Not adbd's lines: Android 17's adbd logs each command this script sends it, links and all.
  [[ -s $OUT/logcat-guests.log ]] || fail "no system log was captured"
  for key in "${keys[@]}"; do ! LC_ALL=C grep -v ' adbd *: ' $OUT/logcat-guests.log | LC_ALL=C grep -F -- "$key" >/dev/null || fail "an invite's key is in the system log"; done
  note "none of the ${#keys[@]} keys is in the $(wc -l < $OUT/logcat-guests.log | tr -d ' ') lines logged"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == app ]]; then
  say "app: the owner signs in, then opens Recipes"
  owner_launcher
  tap_text Recipes
  close_tip
  # The page's own heading, read from the web view.
  await_text 40 "Recipe Box" || { shot app-failed; fail "Recipes never loaded: $(screen_text | tr '\n' '|')"; }
  sleep 8; shot app-recipes
  a exec-out uiautomator dump /dev/tty > "$OUT/app-recipes.xml" 2>/dev/null || true
  report=$(screen_text | grep -m1 -F '"ws"' || true)
  note "Recipes opened and its page loaded: $report"
  # Its WebSocket and the internet work; Pantry's files don't, since the connector refuses a
  # request another site's page makes (fromThisSite in connector/proxy.go).
  [[ $report == *'"ws":"ping"'* && $report == *'"internet":"ok"'* ]] || fail "the page's WebSocket or internet fetch failed: $report"
  [[ $report == *'"css":"error"'* && $report == *'"img":"error"'* ]] || fail "another app's files loaded cross-site: $report"
  a shell input keyevent KEYCODE_BACK; sleep 2
  await_text 10 Pantry || fail "Back didn't close the app: $(screen_text | tr '\n' '|')"
  tap_text Recipes
  await_text 10 "Recipe Box" || fail "Recipes didn't reopen from the pool"
  note "Back closed it, and it reopened from the pool"
  # An app that isn't running: after its tries, Ovenlight says so in its own words, never the
  # connector's 502 text as the page, and Try again opens it once it runs again.
  a shell input keyevent KEYCODE_BACK; sleep 2
  c publish --config $D/config.json --state $D/st --slug pantry --run= >> $D/publish.log 2>&1 || fail "stopping Pantry: $(tail -5 $D/publish.log)"
  tap_text Pantry
  await_text 40 "Pantry isn’t running on your computer. Run ovenlight doctor there, then try again." "Try again" ||
    { shot app-down-failed; fail "a stopped app didn't say so: $(screen_text | tr '\n' '|')"; }
  shot app-down
  c publish --config $D/config.json --state $D/st --slug pantry --run "python3 $ROOT/android/e2e/app.py" --dir "$D/pantry" >> $D/publish.log 2>&1 || fail "starting Pantry: $(tail -5 $D/publish.log)"
  # It takes a moment to start: Try again until it opens.
  opened=
  for i in $(seq 1 5); do
    tap_text "Try again" || true
    if await_text 15 Pantry; then opened=1; break; fi
  done
  [[ -n $opened ]] || { shot app-down-retry-failed; fail "Try again didn't open Pantry: $(screen_text | tr '\n' '|')"; }
  note "a stopped Pantry said it isn't running, with Try again, which opened it once it ran again"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == chrome ]]; then
  say "chrome: the owner signs in, opens Recipes, and sends feedback from the app menu"
  owner_launcher
  tap_text Recipes
  # Until its tip is closed, the pill shows with it, even before the page loads.
  await_text 20 "Your apps are up here" "Got it" || { shot chrome-failed; fail "no tip over the pill: $(screen_text | tr '\n' '|')"; }
  shot chrome-tip
  tap_text "Got it"
  await_text 40 "Recipe Box" || { shot chrome-failed; fail "Recipes never loaded: $(screen_text | tr '\n' '|')"; }
  # The tip closed, the pill tucks itself away as the page appears, leaving its handle.
  await_text 10 "Show app menu" || { shot chrome-failed; fail "the pill didn't tuck away: $(screen_text | tr '\n' '|')"; }
  ! screen_text | grep -xF "Recipes options" >/dev/null || fail "the pill still shows beside its handle"
  shot chrome-tucked
  note "the tip showed with the pill; once closed, the pill tucked away to its handle after the load"
  tap_text "Show app menu"
  await_text 5 "All apps" "Recipes options" || fail "the handle didn't bring the pill back: $(screen_text | tr '\n' '|')"
  # Where the pill reaches down to, for the screenshot's check.
  pill=($(bounds text "Recipes options"))
  [[ ${#pill[@]} == 4 ]] || fail "no bounds for the pill"
  tap_text "Recipes options"
  await_text 5 "Reload" "Send feedback" "Report a problem" "Lock Ovenlight" || { shot chrome-failed; fail "the app menu is missing items: $(screen_text | tr '\n' '|')"; }
  shot chrome-menu
  note "the handle brought the pill back, and its menu has Reload, Send feedback, Report a problem and Lock Ovenlight"
  tap_text "Send feedback"
  await_text 10 "Include screenshot" "Screenshot of Recipes" || { shot chrome-failed; fail "Send feedback has no screenshot: $(screen_text | tr '\n' '|')"; }
  tap_class android.widget.EditText || fail "no note field"
  sleep 1
  # input text takes %s for a space.
  a shell input text 'e2e%snote%sfrom%sthe%sapp%smenu'
  sleep 1
  shot chrome-feedback
  # The lock takes the screen apart; the note waits with the app's page and comes back with it.
  lock_now
  unlock_now
  await_text 15 "e2e note from the app menu" "Include screenshot" || { shot chrome-failed; fail "Send feedback didn't come back with its note after the lock: $(screen_text | tr '\n' '|')"; }
  shot chrome-feedback-unlocked
  note "Send feedback came back after the lock, its note as typed"
  tap_text Send || fail "no Send button"
  await_text 30 "Feedback sent" || { shot chrome-failed; fail "the feedback wasn't sent: $(screen_text | tr '\n' '|')"; }
  # It closes by itself 1.4 s later.
  for i in $(seq 1 10); do screen_text | grep -xF "Feedback sent" >/dev/null || break; sleep 1; done
  ! screen_text | grep -xF "Feedback sent" >/dev/null || fail "Send feedback didn't close after sending"
  await_text 5 "Recipe Box" || fail "the page isn't back after Send feedback: $(screen_text | tr '\n' '|')"
  note "Send feedback showed Feedback sent and closed by itself"
  inbox=$(env HOME=$D/home $D/ovenlight feedback --config $D/config.json --state $D/st --app recipes --json)
  printf '%s\n' "$inbox" > $OUT/chrome-feedback.json
  size=$(a shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r')
  # The note, the page and a PNG as wide as the screen. Its column at 60 percent across runs
  # through the pill and the handle, right of the page's heading: from the top to the pill's
  # bottom it must be all the page's own white. In landscape or at the largest font the screen's
  # shape and the heading move, so only the note, the page and the PNG are checked there.
  python3 - "$size" "$OUT/chrome-feedback.json" "${pill[3]}" "${OVENLIGHT_E2E_CONDITION:-}" <<'PY' || fail "the connector's feedback isn't as sent: $inbox"
import json, struct, sys, zlib
width, height = map(int, sys.argv[1].split("x"))
bottom = int(sys.argv[3])
items = json.load(open(sys.argv[2]))
assert len(items) == 1, items
item = items[0]
assert item["note"] == "e2e note from the app menu", item
assert item["pageUrl"].startswith("https://recipes.e2e.ts.net/"), item
data = open(item["screenshotPath"], "rb").read()
assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
if sys.argv[4] in ("landscape", "font"):
    print("feedback: %s, a PNG" % item["note"])
    sys.exit(0)
chunks, i = {}, 8
while i < len(data):
    n, kind = struct.unpack(">I4s", data[i:i + 8])
    chunks.setdefault(kind, []).append(data[i + 8:i + 8 + n])
    i += 12 + n
w, h, depth, color = struct.unpack(">IIBB", chunks[b"IHDR"][0][:10])
assert w == width and h <= height, (w, h, width, height)
assert depth == 8 and color in (2, 6), (depth, color)
bpp = 4 if color == 6 else 3
raw = zlib.decompress(b"".join(chunks[b"IDAT"]))
stride, rows, prev = w * bpp, [], bytearray(w * bpp)
for y in range(h):
    kind, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
    for x in range(stride):
        left = line[x - bpp] if x >= bpp else 0
        up, corner = prev[x], prev[x - bpp] if x >= bpp else 0
        if kind == 1: line[x] = (line[x] + left) & 0xFF
        elif kind == 2: line[x] = (line[x] + up) & 0xFF
        elif kind == 3: line[x] = (line[x] + (left + up) // 2) & 0xFF
        elif kind == 4:
            p = left + up - corner
            pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
            line[x] = (line[x] + (left if pa <= pb and pa <= pc else up if pb <= pc else corner)) & 0xFF
    rows.append(line)
    prev = line
at = int(w * 0.6) * bpp
column = {tuple(rows[y][at:at + 3]) for y in range(min(h, bottom))}
assert column == {(255, 255, 255)}, "the screenshot shows more than the page under the pill: %s" % sorted(column)[:5]
print("feedback: %s, %dx%d PNG, no chrome in it" % (item["note"], w, h))
PY
  if [[ ${OVENLIGHT_E2E_CONDITION:-} =~ ^(landscape|font)$ ]]; then
    note "the connector has the note, the page and a PNG"
  else
    note "the connector has the note, the page and a $size PNG with no pill or handle in it"
  fi
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == downloads ]]; then
  say "downloads: Recipes offers an http file behind a cookie, a blob and the camera"
  # app.py serves the folder as it is at each request, so the page changes here.
  cat > $D/recipes/index.html <<'EOF'
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Files</title>
<style>a, button { display: block; margin: 24px; padding: 16px; font-size: 20px; }</style></head>
<body><h1 id="state">Files</h1>
<a download="Recipes export.csv" href="/private.csv">Download file</a>
<button onclick="blobDownload()">Download blob</button>
<button onclick="startCamera()">Start camera</button>
<script>
const state = text => document.getElementById("state").textContent = text;
fetch("/session").then(r => state(r.ok ? "Signed in" : "No session"), () => state("No session"));
function blobDownload() {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob(["blob from the page\n"], { type: "text/plain" }));
  a.download = "note.txt";
  a.click();
  URL.revokeObjectURL(a.href);
}
let track;
function startCamera() {
  navigator.mediaDevices.getUserMedia({ video: true }).then(stream => {
    track = stream.getVideoTracks()[0];
    setInterval(() => state("Camera " + track.readyState), 500);
  }, e => state("Camera error " + e.name));
}
</script></body></html>
EOF
  owner_launcher
  tap_text Recipes
  close_tip
  # The page's HttpOnly session cookie, which only the profile's cookie jar can carry.
  await_text 40 "Signed in" "Download file" || { shot downloads-failed; fail "the page never signed in: $(screen_text | tr '\n' '|')"; }
  downloaded() { a shell run-as $PKG sh -c "'cat cache/Downloads/*/\"$1\"'" 2>/dev/null | tr -d '\r'; }
  a shell touch /sdcard/Download/e2e-start

  tap_text "Download file"
  await_text 30 "Recipes export.csv" Save Share || { shot downloads-failed; fail "no sheet for the http download: $(screen_text | tr '\n' '|')"; }
  shot downloads-file-sheet
  got=$(downloaded "Recipes export.csv")
  [[ $got == $'name,amount\nflour,500' ]] || fail "the http download's bytes: $got"
  note "the http file arrived under the page's own name, fetched again with the session cookie"
  a shell input keyevent KEYCODE_BACK; sleep 2
  await_text 5 "Download file" || fail "Back didn't just close the sheet: $(screen_text | tr '\n' '|')"

  tap_text "Download blob"
  await_text 30 "note.txt" Save Share || { shot downloads-failed; fail "no sheet for the blob download: $(screen_text | tr '\n' '|')"; }
  shot downloads-blob-sheet
  if screen_text | grep -xF Open >/dev/null; then note "the blob's sheet offers Open, for plain text"; fi
  got=$(downloaded note.txt)
  [[ $got == "blob from the page" ]] || fail "the blob download's bytes: $got"
  note "the blob arrived, though the page revoked its URL right after the click"
  # Share and Open run in tasks of their own, so Recents never shows the file under Ovenlight's card.
  top_task() { a shell dumpsys activity activities | grep -m1 topResumedActivity | grep -o ' t[0-9]*}' || true; }
  own=$(top_task)
  [[ -n $own ]] || fail "no resumed task"
  tap_text Share; sleep 3
  [[ $(top_task) != "$own" ]] || fail "the Sharesheet runs in Ovenlight's task"
  note "the Sharesheet runs in a task of its own"
  a shell input keyevent KEYCODE_BACK
  await_text 10 "Download blob" || fail "not back on the page after the Sharesheet: $(screen_text | tr '\n' '|')"
  tap_text "Download blob"
  await_text 30 "note.txt" Save Share || { shot downloads-failed; fail "no sheet for the blob again: $(screen_text | tr '\n' '|')"; }
  if screen_text | grep -xF Open >/dev/null; then
    tap_text Open; sleep 3
    [[ $(top_task) != "$own" ]] || fail "Open's viewer runs in Ovenlight's task"
    note "Open's viewer runs in a task of its own"
    a shell input keyevent KEYCODE_BACK
    await_text 10 "Download blob" || fail "not back on the page after Open: $(screen_text | tr '\n' '|')"
  else
    a shell input keyevent KEYCODE_BACK; sleep 2
  fi
  # Save writes the file where the documents picker says, under the page's name: a text file
  # that isn't .txt keeps its own extension.
  saved_as() {
    tap_text "$1"
    await_text 30 "$2" Save Share || { shot downloads-failed; fail "no sheet for $2 to save: $(screen_text | tr '\n' '|')"; }
    tap_text Save
    for i in $(seq 1 15); do [[ $(front) == *documentsui* ]] && break; sleep 1; done
    [[ $(front) == *documentsui* ]] || fail "Save didn't open the documents picker: $(front)"
    sleep 2; tap_matching '^(SAVE|Save)$' || { shot downloads-save; fail "no Save in the documents picker: $(screen_text | tr '\n' '|')"; }
    # The picker makes the file empty; Ovenlight writes it once back in front.
    local saved= got=
    for i in $(seq 1 15); do
      saved=$(a shell "find /sdcard/ -path '*/Android' -prune -o -type f -newer /sdcard/Download/e2e-start -print 2>/dev/null" | tr -d '\r' | head -1)
      [[ -n $saved ]] && got=$(a shell cat "'$saved'" | tr -d '\r')
      [[ $got == "$3" ]] && break; sleep 1
    done
    [[ $got == "$3" ]] || { shot downloads-save; fail "Save wrote nothing or other bytes: ${saved:-no file} '$got'"; }
    [[ ${saved##*/} == "$2" ]] || fail "Save named $2 ${saved##*/}"
    a shell rm -f "'$saved'" >/dev/null 2>&1 || true
    await_text 10 "$1" || fail "not back on the page after Save: $(screen_text | tr '\n' '|')"
  }
  saved_as "Download blob" note.txt "blob from the page"
  saved_as "Download file" "Recipes export.csv" $'name,amount\nflour,500'
  note "Save wrote the blob and the CSV where the documents picker said, under their own names"
  a shell rm -f /sdcard/Download/e2e-start >/dev/null 2>&1 || true

  say "capture: the camera stops on lock, and the page stays"
  tap_text "Start camera"
  # Android's own permission first ("While using the app" from API 30, "Allow" beside "Deny"
  # on API 29), then Ovenlight's question.
  for i in $(seq 1 10); do
    seen=$(screen_text)
    if grep -qxF "While using the app" <<<"$seen"; then tap_text "While using the app"; break; fi
    if grep -qxF Deny <<<"$seen"; then tap_text Allow; break; fi
    sleep 1
  done
  await_text 15 "Allow Recipes to use the camera?" || fail "no capture prompt: $(screen_text | tr '\n' '|')"
  shot downloads-capture-prompt
  # Allow takes no tap in its first half second.
  sleep 1; tap_text Allow
  # Ovenlight's camera clients, as the camera service lists them: "(Camera ID: 1, ..., Client
  # Package Name: <package>, ...)" lines between "Active Camera Clients:" and "Allowed user IDs".
  camera_clients() { a shell dumpsys media.camera | tr -d '\r' | sed -n '/Active Camera Clients/,/Allowed user IDs/p' | grep -c "Client Package Name: $PKG," || true; }
  for i in $(seq 1 15); do screen_text | grep -E '^Camera (live|error)' >/dev/null && break; sleep 1; done
  if screen_text | grep -xF "Camera live" >/dev/null; then
    note "the page has the camera after Ovenlight's prompt"
    (( $(camera_clients) > 0 )) || fail "the camera service lists no client while the page captures, so it can't show one closing: $(a shell dumpsys media.camera | grep -A5 'Active Camera Clients')"
    lock_now
    sleep 3
    (( $(camera_clients) == 0 )) || fail "the camera is still open under the lock: $(a shell dumpsys media.camera | grep -A3 'Active Camera Clients')"
    ! a logcat -d -s Ovenlight | grep 'still capturing after the stop' >/dev/null || fail "the page script didn't stop the camera, so the view went"
    unlock_now
    await_text 20 "Camera ended" || fail "after the unlock the page isn't the one that captured: $(screen_text | tr '\n' '|')"
    note "the lock stopped the page's track, the camera closed, and the same page came back"

    say "capture: closing the app to the launcher stops the camera too, and the lock still shows"
    tap_text "Start camera"
    # The lock forgot the Allow, so Ovenlight asks again.
    await_text 15 "Allow Recipes to use the camera?" || fail "no capture prompt after the lock: $(screen_text | tr '\n' '|')"
    sleep 1; tap_text Allow
    await_text 15 "Camera live" || fail "the camera didn't come back: $(screen_text | tr '\n' '|')"
    a shell input keyevent KEYCODE_BACK
    await_text 10 Pantry || fail "Back didn't close the app: $(screen_text | tr '\n' '|')"
    sleep 3
    (( $(camera_clients) == 0 )) || fail "the camera is still open after the app closed: $(a shell dumpsys media.camera | grep -A3 'Active Camera Clients')"
    lock_now
    unlock_now
    await_text 20 Pantry || fail "no launcher after the unlock: $(screen_text | tr '\n' '|')"
    note "closing stopped the camera, and the lock showed and let go over the launcher"
  else
    answer=$(screen_text | grep -m1 '^Camera error' || echo "no answer")
    # Only a phone with no camera to give excuses this; Ovenlight refusing it fails.
    [[ $answer == "Camera error NotFoundError" || $answer == "Camera error NotReadableError" ]] || fail "the page didn't get the camera: $answer"
    note "the emulator's camera didn't answer ($answer); capture on lock not checked"
  fi
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == upload ]]; then
  say "upload: Recipes takes a file through the documents picker and a photo through the photo picker"
  # A file in Download and a photo in Pictures, as a person's own would be.
  printf 'flour,500\nsugar,200\n' > $D/e2e-upload.txt
  python3 -c '
import struct, sys, zlib
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
rows = b"".join(b"\0" + b"\xc0\x39\x2b" * 16 for _ in range(16))
sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))
' > $D/e2e-photo.png
  a push $D/e2e-upload.txt /sdcard/Download/e2e-upload.txt >/dev/null
  a push $D/e2e-photo.png /sdcard/Pictures/e2e-photo.png >/dev/null
  # The pickers list what MediaStore knows, so both files are scanned in.
  a shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
  for f in Download/e2e-upload.txt Pictures/e2e-photo.png; do
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/$f >/dev/null 2>&1 || true
  done
  uploaded_cleanup() { a shell rm -f /sdcard/Download/e2e-upload.txt /sdcard/Pictures/e2e-photo.png >/dev/null 2>&1 || true; }
  cat > $D/recipes/index.html <<'EOF'
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Uploads</title>
<style>button { display: block; margin: 24px; padding: 16px; font-size: 20px; } input { display: none; }</style></head>
<body><h1>Recipe Box uploads</h1><p id="state">Nothing picked</p>
<button onclick="pick('any')">Pick any file</button>
<button onclick="pick('photo')">Pick a photo</button>
<input id="any" type="file"><input id="photo" type="file" accept="image/*">
<script>
const state = text => document.getElementById("state").textContent = text;
// Each pick starts from "waiting", so every answer the page gets shows anew.
function pick(id) { state(id + " waiting"); document.getElementById(id).click(); }
for (const id of ["any", "photo"]) {
  const input = document.getElementById(id);
  input.addEventListener("change", async () => {
    const file = input.files[0];
    if (!file) return state(id + " empty");
    const sent = await fetch("/upload", { method: "POST", headers: { "X-Name": encodeURIComponent(file.name) }, body: file });
    state(id + " " + file.name + " " + file.size + (sent.ok ? " sent" : " refused " + sent.status));
    input.value = "";
  });
  input.addEventListener("cancel", () => state(id + " canceled"));
}
</script></body></html>
EOF
  # The documents picker opens on Recent or on the last folder; Downloads is a step away otherwise.
  pick_document() {
    await_text 10 "$1" && { tap_text "$1"; return 0; }
    tap_matching '^Show roots$' || return 1
    sleep 2; tap_text Downloads || tap_text Download || return 1
    await_text 10 "$1" && tap_text "$1"
  }
  page_state() { screen_text | grep -m1 -E '^(any|photo) ' || true; }
  owner_launcher
  tap_text Recipes
  close_tip
  await_text 40 "Recipe Box uploads" "Pick any file" || { shot upload-failed; fail "the uploads page never loaded: $(screen_text | tr '\n' '|')"; }

  tap_text "Pick any file"
  pick_document e2e-upload.txt || { shot upload-documents; fail "the documents picker never showed the file: $(front) $(screen_text | tr '\n' '|')"; }
  await_text 30 "any e2e-upload.txt 20 sent" || { shot upload-failed; fail "the page didn't send the file: $(page_state)"; }
  cmp -s $D/e2e-upload.txt $D/recipes/uploaded/e2e-upload.txt || fail "the server got other bytes for the file"
  note "the documents picker's file reached the page (name and size) and the server (bytes)"

  tap_text "Pick a photo"
  await_text 10 "Take photo" "Choose photo" || fail "no camera-or-photo sheet for an image input: $(screen_text | tr '\n' '|')"
  tap_text "Choose photo"
  # The photo picker labels each photo "Photo taken on <date>"; its tab reads "Photos".
  for i in $(seq 1 15); do tap_matching '^Photo taken' && break; sleep 1; done
  photo=
  for i in $(seq 1 30); do photo=$(screen_text | grep -m1 -E '^photo .* sent$' || true); [[ -n $photo ]] && break; sleep 1; done
  [[ -n $photo ]] || { shot upload-failed; fail "the page didn't send the photo: $(page_state) $(front)"; }
  size=$(wc -c < $D/e2e-photo.png | tr -d ' ')
  name=$(sed -E 's/^photo (.*) [0-9]+ sent$/\1/' <<<"$photo")
  [[ $photo == *" $size sent" ]] || fail "the photo's size: $photo, pushed $size bytes"
  cmp -s $D/e2e-photo.png "$D/recipes/uploaded/$name" || fail "the server got other bytes for the photo ($name)"
  note "the photo picker's photo reached the page (as $name, $size bytes) and the server"

  say "upload: a canceled picker answers the page, so the next tap opens one again"
  tap_text "Pick any file"
  sleep 3; [[ $(front) != *$PKG* ]] || fail "no documents picker: $(front)"
  a shell input keyevent KEYCODE_BACK
  await_text 15 "any canceled" || fail "canceling the documents picker didn't answer the page: $(page_state)"
  tap_text "Pick any file"
  sleep 3; [[ $(front) != *$PKG* ]] || fail "a second tap opened no documents picker: $(front)"
  a shell input keyevent KEYCODE_BACK
  await_text 15 "any canceled" || fail "the second cancel didn't answer the page"
  tap_text "Pick a photo"
  await_text 10 "Choose photo" || fail "no sheet for the photo input"
  tap_text "Choose photo"
  sleep 3; [[ $(front) != *$PKG* ]] || fail "no photo picker: $(front)"
  a shell input keyevent KEYCODE_BACK
  await_text 15 "photo canceled" || fail "canceling the photo picker didn't answer the page: $(page_state)"
  tap_text "Pick a photo"
  await_text 10 "Take photo" "Choose photo" || fail "a second tap opened no sheet: $(screen_text | tr '\n' '|')"
  a shell input keyevent KEYCODE_BACK
  await_text 15 "photo canceled" || fail "closing the sheet didn't answer the page"
  note "each cancel answered the page, and the next tap opened the picker or sheet again"
  uploaded_cleanup
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == return ]]; then
  say "return: a page on screen keeps running through a Custom Tab it opened, and a POST to Pantry opens outside"
  cat > $D/recipes/index.html <<'EOF'
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Return</title>
<style>a, button, p { display: block; margin: 16px; font-size: 20px; } a, button { padding: 12px; }</style></head>
<body><h1>Recipe Box return</h1>
<p id="load"></p><p id="ws">socket connecting</p><p id="echo">no echo</p><p id="gap">longest pause 0</p>
<a href="https://example.com/">Outside link</a>
<button onclick="ping()">Ping</button><button onclick="longest = 0; show()">Reset pause</button>
<form method="post" action="https://pantry.e2e.ts.net/"><input type="hidden" name="a" value="1"><button>Post to Pantry</button></form>
<script>
const text = (id, value) => document.getElementById(id).textContent = value;
text("load", "load " + String(Math.random()).slice(2, 8));
let opens = 0, echoes = 0, last = Date.now(), longest = 0;
const show = () => text("gap", "longest pause " + Math.round(longest / 1000));
setInterval(() => { const now = Date.now(); longest = Math.max(longest, now - last); last = now; show(); }, 200);
const ws = new WebSocket("wss://recipes.e2e.ts.net/ws");
ws.onopen = () => text("ws", "socket open " + ++opens);
ws.onclose = () => text("ws", "socket closed");
ws.onmessage = () => text("echo", "echo " + ++echoes);
function ping() { ws.send("ping"); }
</script></body></html>
EOF
  load_id() { screen_text | grep -m1 -E '^load [0-9]+$' || true; }
  pause_secs() { screen_text | sed -n -E 's/^longest pause ([0-9]+)$/\1/p' | head -1; }
  # The same page, its socket the first and still open, answering, and its timers not held up.
  same_page() { # what
    await_text 20 "socket open 1" || fail "$1: the socket isn't the page's first, still open: $(screen_text | tr '\n' '|')"
    [[ $(load_id) == "$loaded" ]] || fail "$1: the page reloaded ($(load_id), was $loaded)"
    local echoes_before=$(screen_text | grep -m1 -E '^(no echo|echo [0-9]+)$' || true)
    tap_text Ping
    for i in $(seq 1 10); do [[ $(screen_text | grep -m1 -E '^(no echo|echo [0-9]+)$' || true) != "$echoes_before" ]] && break; sleep 1; done
    [[ $(screen_text | grep -m1 -E '^(no echo|echo [0-9]+)$' || true) != "$echoes_before" ]] || fail "$1: the socket didn't answer a ping"
    local gap=$(pause_secs)
    # Out of sight Chromium runs a page's timers once a second; Ovenlight pausing them would
    # show the whole time away here.
    (( ${gap:-99} <= 3 )) || fail "$1: the page's timers stopped for $gap s"
    note "$1: the same page, its socket open and answering, its timers held up at most $gap s"
  }
  chrome_without_first_run
  owner_launcher
  tap_text Recipes
  close_tip
  await_text 40 "Recipe Box return" "socket open 1" || { shot return-failed; fail "the page never opened its socket: $(screen_text | tr '\n' '|')"; }
  loaded=$(load_id)

  tap_text "Reset pause"
  tap_text "Outside link"
  await_outside 20 "https://example.com/" || fail "the outside link didn't open outside: $(front)"
  sleep 10
  back_to "Recipe Box return" "the page's Custom Tab"
  same_page "after the page's Custom Tab"

  # The connector refuses another site's POST (fromThisSite in connector/proxy.go); the page
  # it answers with commits, and Ovenlight opens it outside and goes back. Last, as going
  # back may load the page again.
  tap_text "Post to Pantry"
  await_outside 20 "https://pantry.e2e.ts.net/" || { shot return-post; fail "the POST to Pantry didn't open outside: $(front)"; }
  note "the POST to Pantry opened Pantry outside: $(front | sed 's/^ *//')"
  back_to "Recipe Box return" "the POST"
  note "and Recipes is back on its own page"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == screens ]]; then
  say "screens: the owner signs in, then the screenshots"
  owner_launcher
  sleep 2; shot launcher-light
  night yes; shot launcher-dark
  open_settings
  sleep 1; shot settings-dark
  night no; shot settings-light
  a shell input keyevent KEYCODE_BACK; sleep 2
  # Landscape, then the largest font, each on the launcher and Settings.
  rotate 1
  shot launcher-landscape
  open_settings; shot settings-landscape
  a shell input keyevent KEYCODE_BACK; sleep 2
  rotate 0
  font 2.0
  shot launcher-largest-font
  open_settings; shot settings-largest-font
  a shell input keyevent KEYCODE_BACK; sleep 2
  font 1.0
  await_text 10 Recipes || fail "the launcher didn't come back after the font change"
  note "screenshots of the launcher and Settings in light, dark, landscape and at the largest font"

  say "lock: Immediately, from Settings"
  lock_immediately
  tap_text "Lock Ovenlight"; sleep 2
  KEEP_LOCK=1 await_text 10 "Ovenlight is locked" || fail "Lock Ovenlight didn't lock"
  shot lock-light; night yes; shot lock-dark; night no
  note "Lock Ovenlight locks; screenshots of the lock screen in light and dark"
  unlock_now
  await_text 20 Recipes || fail "didn't unlock"

  say "lock: Home, then back through the icon"
  a shell input keyevent KEYCODE_HOME; sleep 3
  back_through icon
  locked || fail "back through the icon without the lock: $(screen_text | tr '\n' '|')"
  note "back through the icon: locked"
  # The prompt opened by itself; cancel it, and it must stay closed.
  a shell input keyevent KEYCODE_BACK; sleep 6
  ! screen_text | grep -xF 'Unlock Ovenlight' >/dev/null || fail "the canceled prompt came back by itself"
  KEEP_LOCK=1 await_text 5 "Ovenlight is locked" || fail "the lock screen went away: $(screen_text | tr '\n' '|')"
  note "a canceled prompt stays closed, and the lock screen stays"
  unlock_now
  await_text 20 Recipes || fail "didn't unlock"

  say "lock: Home, then back through Recents"
  a shell input keyevent KEYCODE_HOME; sleep 3
  back_through Recents
  locked || fail "back through Recents without the lock: $(screen_text | tr '\n' '|')"
  note "back through Recents: locked"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == sharing ]]; then
  say "sharing: Recipes made shareable, as the owner's"
  shareable_recipes Dimitri
  # The connector's guests and invites, as the admin API lists them.
  guests() { c guests --config $D/config.json --state $D/st --json; }
  # The system's prompt for the screen lock again, titled $1, answered with fingerprint 1.
  confirm_with_finger() {
    KEEP_LOCK=1 await_text 15 "$1" || fail "no prompt for the screen lock titled \"$1\": $(screen_text | tr '\n' '|')"
    a emu finger touch 1 >/dev/null
  }
  # Opens the composer from Recipes' menu, once the connector has said Recipes is shareable.
  share_recipes() {
    for i in 1 2 3 4 5 6; do
      hold Recipes || fail "no Recipes tile"
      await_text 5 "Share…" && break
      a shell input keyevent KEYCODE_BACK; sleep 3
    done
    tap_text "Share…" || fail "Recipes' menu has no Share: $(screen_text | tr '\n' '|')"
    await_text 10 "Share Recipes" "Their name, like Sam" || fail "no composer: $(screen_text | tr '\n' '|')"
    tap_class android.widget.EditText
    a shell input text "$1"; sleep 1
    tap_text Create || fail "no Create"
  }
  owner_launcher

  say "sharing: Share from Recipes' menu invites Sam, after the screen lock"
  share_recipes Sam
  confirm_with_finger "Share Recipes with Sam"
  await_text 20 "Invite for Sam" "Invite code" "Send invite" "Copy link" || { shot sharing-failed; fail "no invite for Sam: $(screen_text | tr '\n' '|')"; }
  shot sharing-invite
  invite=$(guests | python3 -c 'import json, sys; print(next(i["id"] for i in json.load(sys.stdin)["invites"] if i["to"] == "Sam" and i["state"] == "sent"))') ||
    fail "the connector has no invite for Sam"
  note "Create asked for the screen lock, then showed Sam's invite with its code; the connector has it ($invite)"
  tap_text "Send invite"
  for i in $(seq 1 10); do [[ $(front) == *Chooser* ]] && break; sleep 1; done
  [[ $(front) == *Chooser* ]] || fail "Send invite opened no Sharesheet: $(front)"
  shot sharing-sheet
  a shell input keyevent KEYCODE_BACK; sleep 2
  await_text 10 "Invite for Sam" || fail "back from the Sharesheet, the invite is gone: $(screen_text | tr '\n' '|')"
  tap_text "Copy link"
  await_text 5 Copied || fail "Copy link didn't say Copied"
  # A key in a link is a credential until used: the system log, which adb and bug reports read, never has one.
  log=$(a logcat -d 2>/dev/null) || fail "couldn't read the system log"
  ! LC_ALL=C grep 'ovenlight\.app/join' <<<"$log" >/dev/null || fail "an invite link reached the system log"
  note "Send invite opened the Sharesheet, Back came back to the invite, Copy link says Copied, and no link reached the system log"
  tap_text Close
  await_text 10 Recipes Pantry || fail "Close didn't close the invite"

  say "sharing: Kim joins Recipes from a phone of her own, with an invite from the terminal"
  link=$(share recipes Kim)
  [[ -n $link ]] || fail "share printed no invite link"
  printf '%s\n' "$link" | env TS_NO_LOGS_NO_SUPPORT=1 TS_DISABLE_PORTMAPPER=1 go -C $ROOT/connector run $ROOT/android/e2e/guest.go $CTRL $D/guest $CA/ca.pem > $D/guest.log 2>&1 ||
    fail "Kim's phone didn't claim the invite: $(tail -3 $D/guest.log)"
  note "Kim's phone joined the owner's Headscale and claimed the invite"
  # The menu reuses an answer younger than 15 s, so it may take another look to count Kim.
  for i in 1 2 3 4 5 6; do
    hold Recipes || fail "no Recipes tile"
    await_text 5 "People (1)" && break
    a shell input keyevent KEYCODE_BACK; sleep 4
  done
  tap_text "People (1)" || fail "Recipes' menu doesn't count Kim: $(screen_text | tr '\n' '|')"
  await_text 15 Kim "For Sam" || { shot sharing-failed; fail "Recipes' people show neither Kim nor Sam's invite: $(screen_text | tr '\n' '|')"; }
  shot sharing-people
  note "Recipes' menu says People (1), which shows Kim and Sam's open invite"

  say "sharing: Cancel invite takes Sam's invite back"
  tap_text "For Sam options" && await_text 5 "Cancel invite" && tap_text "Cancel invite" || fail "no Cancel invite in Sam's invite's menu"
  for i in $(seq 1 15); do screen_text | grep -xF "For Sam" >/dev/null || break; sleep 1; done
  ! screen_text | grep -xF "For Sam" >/dev/null || fail "Sam's invite is still listed"
  guests | python3 -c 'import json, sys; sys.exit(0 if all(i["state"] != "sent" for i in json.load(sys.stdin)["invites"] if i["id"] == sys.argv[1]) else 1)' "$invite" ||
    fail "the connector still has Sam's invite open"
  note "Sam's invite left the list, and the connector has it canceled"

  say "sharing: Remove Kim, after the screen lock"
  tap_text "Kim options" && await_text 5 "Remove Kim" && tap_text "Remove Kim" || fail "no Remove Kim in Kim's menu"
  await_text 5 "Remove Kim?" || fail "Remove Kim didn't ask first"
  tap_text Remove
  confirm_with_finger "Remove Kim"
  for i in $(seq 1 20); do screen_text | grep -xF Kim >/dev/null || break; sleep 1; done
  ! screen_text | grep -xF Kim >/dev/null || { shot sharing-failed; fail "Kim is still listed"; }
  shot sharing-removed
  guests | python3 -c 'import json, sys; sys.exit(0 if all(g.get("removedAt") for g in json.load(sys.stdin)["guests"] if g["name"] == "Kim") else 1)' ||
    fail "the connector still has Kim"
  ! $HS -c $D/headscale.yaml nodes list -o json | grep '"ovenlight-guest' >/dev/null || fail "Kim's phone is still in the owner's network"
  note "Kim left the list, the connector removed her, and her phone left the owner's network"
  a shell input keyevent KEYCODE_BACK; sleep 2
  await_text 10 Recipes Pantry || fail "Back didn't close People"

  if [[ $(a shell getprop ro.build.version.sdk | tr -d '\r') == 29 ]]; then
    say "sharing, Android 10: at Immediately, the screen lock's own screen before an invite"
    lock_immediately
    # Use screen lock opens the screen lock's own screen, which stops Ovenlight.
    open_screen_lock() {
      KEEP_LOCK=1 await_text 15 "Use screen lock" || fail "no Use screen lock: $(screen_text | tr '\n' '|')"
      tap_text "Use screen lock"
      for i in $(seq 1 10); do [[ $(front) != *$PKG/.MainActivity* ]] && break; sleep 1; done
      [[ $(front) != *$PKG/.MainActivity* ]] || fail "the screen lock's screen didn't open: $(front)"
      # Android 10's screen offers the fingerprint first, with its own Use PIN.
      KEEP_LOCK=1 await_text 10 "Use PIN" || fail "the screen lock's screen has no Use PIN: $(screen_text | tr '\n' '|')"
      tap_text "Use PIN"
      sleep 2
    }
    share_recipes Ann
    open_screen_lock
    a shell input text 1111; a shell input keyevent KEYCODE_ENTER
    KEEP_LOCK=1 await_text 20 "Invite for Ann" || { shot sharing-failed; fail "the screen lock entered, but no invite for Ann: $(screen_text | tr '\n' '|')"; }
    ! locked || fail "a second prompt after the screen lock's own screen"
    note "the screen lock entered on its own screen made Ann's invite, with no second prompt"
    tap_text Close
    await_text 10 Recipes || fail "Close didn't close the invite"

    for way in icon Recents; do
      share_recipes "Bo $way"
      open_screen_lock
      a shell input keyevent KEYCODE_HOME; sleep 3
      back_through $way
      shot sharing-return-$way
      # Whatever comes back, the screen lock's screen or Ovenlight's lock, nothing opens unlocked.
      if [[ $(front) != *$PKG/.MainActivity* ]]; then
        note "back through the $way: the screen lock's screen, which Back cancels"
        # Back leaves the PIN for the fingerprint, then cancels.
        for i in 1 2 3; do [[ $(front) == *$PKG/.MainActivity* ]] && break; a shell input keyevent KEYCODE_BACK; sleep 3; done
      fi
      locked || { shot sharing-failed; fail "back through the $way from the screen lock's screen without the lock: $(screen_text | tr '\n' '|')"; }
      guests | python3 -c 'import json, sys; sys.exit(0 if not any(i["to"].startswith("Bo") for i in json.load(sys.stdin)["invites"]) else 1)' || fail "an invite for Bo without the screen lock"
      note "Home from the screen lock's screen, back through the $way: Ovenlight is locked, and no invite was made"
      unlock_now
      a shell input keyevent KEYCODE_BACK; sleep 2
      await_text 10 Recipes || fail "the launcher didn't come back: $(screen_text | tr '\n' '|')"
    done
  fi

  say "sharing: a second owner, Riley, on a Headscale of their own, invites this phone"
  B=$D/riley
  mkdir -p $B/home $B/garden
  HSPORT_B=$(port) METRICS_B=$(port) GRPC_B=$(port)
  CTRL_B=http://127.0.0.1:$HSPORT_B
  sed -e "s|$D/|$B/|g" -e "s|127.0.0.1:$HSPORT\$|127.0.0.1:$HSPORT_B|" -e "s|127.0.0.1:$METRICS\$|127.0.0.1:$METRICS_B|" \
    -e "s|127.0.0.1:$GRPC\$|127.0.0.1:$GRPC_B|" -e "s|base_domain: $BASE|base_domain: riley.$BASE|" $D/headscale.yaml > $B/headscale.yaml
  policy riley@ garden > $B/policy.hujson
  $HS -c $B/headscale.yaml serve > $B/headscale.log 2>&1 &
  pids="$pids $!"
  until_ok 30 $HS -c $B/headscale.yaml users list || fail "Riley's headscale didn't start"
  $HS -c $B/headscale.yaml users create riley >/dev/null
  RILEY_ID=$($HS -c $B/headscale.yaml users list -o json | python3 -c 'import json, sys; print(json.load(sys.stdin)[0]["id"])')
  KEY_B=$($HS -c $B/headscale.yaml preauthkeys create -u $RILEY_ID --reusable -e 2h -o json | python3 -c 'import json, sys; print(json.load(sys.stdin)["key"])')
  printf '%s\n' '<!doctype html><meta charset="utf-8"><title>Garden</title><h1>Garden beds</h1>' > $B/garden/index.html
  cr() { env HOME=$B/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight "$@" --config $B/config.json --state $B/st; }
  cr publish --port $(port) --name Garden --slug garden --run "python3 $ROOT/android/e2e/app.py" --dir $B/garden >> $D/publish.log 2>&1 || fail "publish garden: $(tail -5 $D/publish.log)"
  ( exec env HOME=$B/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight run --config $B/config.json --state $B/st --control-url $CTRL_B --auth-key $KEY_B \
      --dev-tls-cert $D/leaf.pem --dev-tls-key $D/leaf.key --dev-headscale $HS --dev-headscale-config $B/headscale.yaml >> $B/connector.log 2>&1 ) &
  pids="$pids $!"
  until_ok 120 sh -c "env HOME=$B/home $D/ovenlight status --config $B/config.json --state $B/st --json | grep -q '\"state\": \"serving\"'" || fail "Riley's connector didn't serve Garden"
  set_owner $B/config.json "$(cr status --json | owner_of garden)" Riley
  cr publish --slug garden --shareable >> $D/publish.log 2>&1 || fail "publish garden --shareable: $(tail -5 $D/publish.log)"
  until_ok 120 sh -c "env HOME=$B/home $D/ovenlight status --config $B/config.json --state $B/st --json | grep -q 'tag:ovenlight-app-garden'" || fail "Garden never served as a shareable app node"
  riley_link=$(share garden Dimitri $B)
  [[ $riley_link == *"control=http%3A%2F%2F127.0.0.1%3A$HSPORT_B"* ]] || fail "Riley's invite doesn't name Riley's Headscale"
  note "Riley's connector serves Garden, shareable, and made an invite for this phone"
  a reverse tcp:$HSPORT_B tcp:$HSPORT_B >/dev/null
  # Quoted whole for the device's shell, which reads # as a comment and & as background.
  a shell "am start -n $PKG/.MainActivity -a android.intent.action.VIEW -d '$riley_link'" >/dev/null
  await_text 20 "Join Riley’s Garden?" || { shot sharing-failed; fail "the invite didn't ask to join: $(screen_text | tr '\n' '|')"; }
  tap_text Join
  await_text 90 "Garden is ready" || { shot sharing-failed; fail "the phone didn't join Riley's Garden: $(screen_text | tr '\n' '|')"; }
  tap_text "Open Garden"
  close_tip
  await_text 40 "Garden beds" || { shot sharing-failed; fail "Garden didn't open: $(screen_text | tr '\n' '|')"; }
  cr guests --json | python3 -c 'import json, sys; sys.exit(0 if any(g["name"] == "Dimitri" and not g.get("removedAt") for g in json.load(sys.stdin)["guests"]) else 1)' ||
    fail "Riley's connector has no guest Dimitri"
  note "the phone joined Riley's Garden from its link, and Riley's connector has it as a guest"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == conditions ]]; then
  say "conditions: Recipes stays the same page through rotation, dark mode, the largest font and three-button navigation"
  cat > $D/recipes/index.html <<'EOF'
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Conditions</title>
<style>button, p { display: block; margin: 16px; font-size: 20px; } button { padding: 12px; }</style></head>
<body><h1>Recipe Box conditions</h1><p id="load"></p><p id="fetch">no fetch</p><button onclick="ask()">Fetch</button>
<script>
document.getElementById("load").textContent = "load " + String(Math.random()).slice(2, 8);
let fetches = 0;
function ask() {
  const n = ++fetches, show = text => document.getElementById("fetch").textContent = "fetch " + n + " " + text;
  show("waiting");
  fetch("/?n=" + n, { cache: "no-store" }).then(r => show(r.ok ? "ok" : "refused"), () => show("failed"));
}
</script></body></html>
EOF
  load_id() { screen_text | grep -m1 -E '^load [0-9]+$' || true; }
  # Taps Fetch until the page's own fetch of Recipes answers, within $2 seconds.
  fetches_within() { # what seconds
    local start=$SECONDS n line
    while (( SECONDS - start < $2 )); do
      n=$(( $(screen_text | sed -n -E 's/^fetch ([0-9]+) .*/\1/p' | head -1) + 1 ))
      tap_text Fetch || fail "$1: no Fetch button: $(screen_text | tr '\n' '|')"
      for i in $(seq 1 15); do
        line=$(screen_text | grep -m1 -E "^fetch $n " || true)
        [[ $line == "fetch $n ok" ]] && { note "$1: the page reached Recipes after $((SECONDS - start)) s"; return 0; }
        [[ -n $line && $line != "fetch $n waiting" ]] && break
        sleep 1
      done
    done
    fail "$1: the page never reached Recipes in $2 s: $(screen_text | tr '\n' '|')"
  }
  same_page() { # what
    [[ $(load_id) == "$loaded" ]] || fail "$1: the page reloaded ($(load_id), was $loaded)"
    fetches_within "$1" 20
  }
  owner_launcher
  tap_text Recipes
  close_tip
  await_text 40 "Recipe Box conditions" Fetch || { shot conditions-failed; fail "the page never loaded: $(screen_text | tr '\n' '|')"; }
  loaded=$(load_id)
  fetches_within "opened" 20
  rotate 1; shot conditions-landscape; same_page "landscape"
  rotate 0; same_page "portrait again"
  night yes; shot conditions-dark; same_page "dark mode"
  night no
  font 2.0; shot conditions-largest-font; same_page "the largest font"
  font 1.0
  nav threebutton; shot conditions-three-button; same_page "three-button navigation"
  tap_text "Show app menu" || fail "no handle with three-button navigation: $(screen_text | tr '\n' '|')"
  await_text 5 "All apps" "Recipes options" || fail "the handle didn't bring the pill back with three-button navigation: $(screen_text | tr '\n' '|')"
  shot conditions-three-button-pill
  a shell input keyevent KEYCODE_BACK; sleep 2
  await_text 10 Pantry || fail "Back didn't close the app with three-button navigation: $(screen_text | tr '\n' '|')"
  tap_text Recipes
  await_text 10 "Recipe Box conditions" || fail "Recipes didn't reopen with three-button navigation"
  same_page "reopened from the pool"
  nav gestural

  say "conditions: Wi-Fi off and back on, with Recipes open"
  a shell svc wifi disable; sleep 3
  fetches_within "Wi-Fi off (cellular)" 60
  a shell svc wifi enable; sleep 3
  fetches_within "Wi-Fi back" 60

  say "conditions: an app opened in airplane mode says so at once, and loads when the network is back"
  printf '%s\n' '<!doctype html><meta charset="utf-8"><title>Pantry</title><h1>Pantry conditions</h1>' > $D/pantry/index.html
  a shell input keyevent KEYCODE_BACK
  await_text 10 Pantry || fail "Back didn't close Recipes: $(screen_text | tr '\n' '|')"
  a shell cmd connectivity airplane-mode enable; sleep 3
  tap_text Pantry
  await_text 3 "You’re offline" || { a shell cmd connectivity airplane-mode disable; fail "no offline message: $(screen_text | tr '\n' '|')"; }
  shot conditions-offline
  a shell cmd connectivity airplane-mode disable
  await_text 60 "Pantry conditions" || fail "Pantry didn't load once the network was back: $(screen_text | tr '\n' '|')"
  note "offline at once, then Pantry loaded with the network back"
  a shell input keyevent KEYCODE_BACK
  await_text 10 Recipes || fail "Back didn't close Pantry"
  tap_text Recipes
  await_text 10 "Recipe Box conditions" || fail "Recipes didn't reopen after Pantry"

  say "conditions: Home for more than a minute, which closes the nodes, then back"
  home_at=$(a shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
  a shell input keyevent KEYCODE_HOME
  sleep 75
  # Were Ovenlight frozen meanwhile, the close comes due and finishes as it returns (AppLifecycle).
  states() { a logcat -d -T "$home_at" -s Ovenlight 2>/dev/null | LC_ALL=C grep -E 'owner: state' | LC_ALL=C sed 's/.*owner: state //' | tr '\n' ',' || true; }
  away_states=$(states)
  note "the owner's node while away: ${away_states:-no change}"
  back_through icon
  await_text 40 "Recipe Box conditions" || { shot conditions-failed; fail "Recipes isn't on screen after the time away: $(screen_text | tr '\n' '|')"; }
  fetches_within "back after 75 s away" 60
  note "the owner's node from Home to back: $(states)"
  [[ $(states) == *"-> idle"*"-> starting"* ]] || fail "the owner's node didn't close and start again: $(states)"
  [[ $(load_id) == "$loaded" ]] || note "the page loaded again ($(load_id), was $loaded)"

  say "conditions: Android ends Ovenlight's process in the background, and Recents brings it back"
  end_in_background
  back_through Recents
  # Back within the lock's minute: the time it went to the background outlives the process.
  ! locked || fail "Recents brought Ovenlight back locked within the lock's time: $(screen_text | tr '\n' '|')"
  await_text 60 Recipes Pantry || { shot conditions-failed; fail "no launcher after the restart: $(screen_text | tr '\n' '|')"; }
  ! screen_text | grep -xF "Recipe Box conditions" >/dev/null || fail "the page came back from the ended process"
  note "back from Recents unlocked, within the lock's time, to the launcher with no app open"
  tap_text Recipes
  await_text 40 "Recipe Box conditions" || { shot conditions-failed; fail "Recipes didn't open after the restart: $(screen_text | tr '\n' '|')"; }
  fetches_within "after the restart" 30
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == perf ]]; then
  say "perf: cold start, ten launches from a stopped process to the lock"
  owner_launcher
  times=()
  for i in $(seq 1 10); do
    times+=($(a shell "am start-activity -W -S -n $PKG/.MainActivity" | tr -d '\r' | sed -n 's/^TotalTime: //p'))
    sleep 3
  done
  sorted=($(printf '%s\n' "${times[@]}" | sort -n))
  note "TotalTime ms: ${times[*]}; median $(( (sorted[4] + sorted[5]) / 2 )), fastest ${sorted[0]}, slowest ${sorted[9]}"

  say "perf: memory once four apps have opened"
  owner_launcher
  open_and_close() { # name
    tap_text $1 || fail "no $1 tile"
    close_tip 5
    await_text 40 "Show app menu" || fail "$1 never loaded: $(screen_text | tr '\n' '|')"
    a shell input keyevent KEYCODE_BACK
    await_text 10 Recipes Notes || fail "Back didn't close $1: $(screen_text | tr '\n' '|')"
  }
  for name in Recipes Pantry Garden Notes; do open_and_close $name; done
  sleep 5
  a shell dumpsys meminfo $PKG > $OUT/perf-meminfo.log
  a shell dumpsys meminfo > $OUT/perf-meminfo-all.log
  note "Ovenlight: $(grep -E 'TOTAL PSS' $OUT/perf-meminfo.log | tr -s ' ')"
  renderers=$(sed -n '/^Total PSS by process/,/^$/p' $OUT/perf-meminfo-all.log | LC_ALL=C grep 'sandboxed_process' | sed 's/(pid.*//' | tr -s ' ' || true)
  note "$(grep -c . <<<"$renderers") WebView renderer processes: $(tr '\n' ';' <<<"$renderers")"
  # After a garbage collection (SIGUSR1 to a debuggable app), so a destroyed view isn't counted.
  objects() {
    a shell run-as $PKG kill -USR1 "$(a shell pidof $PKG | tr -d '\r')"; sleep 3
    a shell dumpsys meminfo $PKG | tr -d '\r' | sed -n -E 's/.*Activities: +([0-9]+).*/\1/p; s/.*WebViews: +([0-9]+).*/\1/p' | tr '\n' ' '
  }
  note "objects (Activities, WebViews): $(objects)"
  rotate 1; rotate 0
  for name in Recipes Pantry Recipes; do open_and_close $name; done
  sleep 5
  counts=($(objects))
  # The pool's own limit (WebViewPool.maxLive): one page on a low-RAM phone, two up to 4 GB, else four.
  kb=$(a shell cat /proc/meminfo | tr -d '\r' | sed -n 's/^MemTotal: *\([0-9]*\) kB/\1/p')
  if [[ $(a shell getprop ro.config.low_ram | tr -d '\r') == true ]]; then live=1; elif (( kb <= 4 * 1024 * 1024 )); then live=2; else live=4; fi
  note "after rotating and opening apps again: Activities ${counts[0]}, WebViews ${counts[1]}, of $live the pool keeps"
  (( counts[0] == 1 && counts[1] <= live )) || fail "an activity or web view outlived its use"

  say "perf: at Home, with the nodes up and after they close"
  uid=$(a shell pm list packages -U $PKG | tr -d '\r' | sed -n 's/.*uid://p')
  pid=$(a shell pidof $PKG | tr -d '\r')
  cpu() { a shell run-as $PKG cat /proc/$pid/stat | awk '{ print $14 + $15 }'; }
  held() { a shell dumpsys power | LC_ALL=C grep -E "_WAKE_LOCK .*uid=$uid" || true; }
  a shell input keyevent KEYCODE_HOME
  sleep 10
  start=$(cpu)
  for at in 10 75 130; do
    (( at == 10 )) || sleep $(( at - last ))
    last=$at
    [[ -z $(held) ]] || fail "$at s at Home, Ovenlight holds a wake lock: $(held)"
    a shell dumpsys activity processes $PKG > $OUT/perf-processes-$at.log
    note "$at s: no wake lock; alarms $(a shell dumpsys alarm | LC_ALL=C grep -c "$PKG" || true), jobs $(a shell dumpsys jobscheduler $PKG | LC_ALL=C grep -c -E '^ *JOB #' || true), $(LC_ALL=C grep -o -i -E 'frozen=[a-z]+|isFrozen=[a-z]+' $OUT/perf-processes-$at.log | head -1 || echo 'no freezer state')"
  done
  note "CPU from 10 s to 130 s at Home: $(( $(cpu) - start )) ticks of 10 ms"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == shortcuts ]]; then
  say "shortcuts: the owner signs in, with the lock at Immediately"
  owner_launcher
  lock_immediately
  # The shortcut's state as Android keeps it: its label, and whether it's pinned or disabled.
  shortcut() { a shell dumpsys shortcut | LC_ALL=C grep -A12 "id=$1" || true; }

  say "shortcuts: Add to Home screen from Recipes' menu, confirmed in the launcher's dialog"
  hold Recipes || fail "no Recipes tile"
  await_text 5 "Add to Home screen" || fail "Recipes' menu has no Add to Home screen: $(screen_text | tr '\n' '|')"
  tap_text "Add to Home screen"
  sleep 3; shot shortcuts-dialog
  note "the launcher asks: $(screen_text | tr '\n' '|')"
  tap_matching '^(Add|ADD|Add automatically|ADD AUTOMATICALLY|Add to home screen)$' || fail "no Add in the launcher's dialog: $(screen_text | tr '\n' '|')"
  sleep 2
  id=$(a shell dumpsys shortcut | LC_ALL=C sed -n "/Package: $PKG /,/Package: /p" | LC_ALL=C grep -o -E 'id=[0-9a-f-]{36}' | head -1 | sed 's/id=//' || true)
  [[ -n $id ]] || fail "no shortcut for Recipes: $(a shell dumpsys shortcut | LC_ALL=C grep -A40 "Package: $PKG" | head -60)"
  note "pinned $id: $(shortcut $id | tr -s ' ' | tr '\n' ' ' | cut -c1-400)"
  # Asked again, Android answers without its dialog, and Ovenlight says it's already there.
  hold Recipes || fail "no Recipes tile"
  await_text 5 "Add to Home screen" || fail "Recipes' menu has no Add to Home screen"
  tap_text "Add to Home screen"
  sleep 2
  [[ $(front) == *$PKG* ]] || fail "asked again, the launcher's dialog came back: $(front)"
  shot shortcuts-again
  note "asked again, the launcher shows no second dialog"

  say "shortcuts: from the home screen, through the lock"
  a shell input keyevent KEYCODE_HOME; sleep 3
  shot shortcuts-home
  tap_text Recipes || fail "no Recipes on the home screen: $(screen_text | tr '\n' '|')"
  sleep 3
  [[ $(front) == *$PKG* ]] || fail "the shortcut didn't open Ovenlight: $(front)"
  locked || fail "the shortcut opened Ovenlight without the lock: $(screen_text | tr '\n' '|')"
  unlock_now
  close_tip
  await_text 40 "Recipe Box" || { shot shortcuts-failed; fail "Recipes didn't open after the unlock: $(screen_text | tr '\n' '|')"; }
  note "the shortcut opened Ovenlight locked, and Recipes after the unlock"

  say "shortcuts: again after Android ends Ovenlight's process in the background, so the shortcut starts it"
  end_in_background
  tap_text Recipes || fail "no Recipes on the home screen: $(screen_text | tr '\n' '|')"
  sleep 3
  locked || fail "the shortcut started Ovenlight without the lock: $(screen_text | tr '\n' '|')"
  unlock_now
  await_text 60 "Recipe Box" || { shot shortcuts-failed; fail "Recipes didn't open after a cold start: $(screen_text | tr '\n' '|')"; }
  note "a cold start through the shortcut is locked, and opens Recipes after the unlock"
  a shell input keyevent KEYCODE_BACK; sleep 2
  await_text 10 Pantry || fail "Back didn't close Recipes: $(screen_text | tr '\n' '|')"

  say "shortcuts: Rename carries over to the shortcut"
  hold Recipes || fail "no Recipes tile"
  await_text 5 Rename || fail "no Rename: $(screen_text | tr '\n' '|')"
  tap_text Rename
  await_text 5 "Rename app" || fail "no Rename app dialog"
  a shell input keyevent KEYCODE_MOVE_END $(printf 'KEYCODE_DEL %.0s' 1 2 3 4 5 6 7 8 9 10)
  a shell input text Cookbook
  tap_text Save
  until_ok 15 sh -c "adb -s $S shell dumpsys shortcut | grep -q 'shortLabel=Cookbook'" || fail "the shortcut kept its name: $(shortcut $id | head -5)"
  note "the shortcut is called Cookbook too"

  say "shortcuts: Remove app disables the shortcut"
  hold Cookbook || fail "no Cookbook tile"
  await_text 5 "Remove app" || fail "no Remove app: $(screen_text | tr '\n' '|')"
  tap_text "Remove app"
  await_text 5 "Remove Cookbook?" || fail "Remove didn't ask first"
  tap_text "Remove app"
  until_ok 15 sh -c "adb -s $S shell dumpsys shortcut | grep -q 'disabledMessage=No longer in Ovenlight'" ||
    fail "the shortcut isn't disabled: $(shortcut $id | head -8)"
  note "disabled: $(shortcut $id | LC_ALL=C grep -o -E 'disabledMessage=[^,]*|disabledReason=\[[^]]*\]' | tr '\n' ' ')"
  a shell input keyevent KEYCODE_HOME; sleep 3
  shot shortcuts-disabled
  tap_text Cookbook || fail "the disabled shortcut left the home screen: $(screen_text | tr '\n' '|')"
  sleep 3
  [[ $(front) != *$PKG* ]] || fail "the disabled shortcut opened Ovenlight"
  note "on the home screen it stays, dimmed, and a tap doesn't open Ovenlight"

  say "shortcuts: a link to an app that isn't there opens nothing else"
  a shell "am start -W -a android.intent.action.VIEW -d 'ovenlight://open?id=$id' -p $PKG" >/dev/null
  sleep 2
  unlock_now
  await_text 20 Pantry "Lock Ovenlight" || fail "no launcher after the link: $(screen_text | tr '\n' '|')"
  sleep 3
  screen_text | grep -xF "Lock Ovenlight" >/dev/null || fail "the link opened an app: $(screen_text | tr '\n' '|')"
  [[ $(front) == *$PKG* && -n $(a shell pidof $PKG) ]] || fail "Ovenlight isn't running after the link"
  note "Ovenlight shows the launcher, with no app open"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == signin ]]; then
  say "signin: Use my own computers, then Connect, then the sign-in page in a Custom Tab"
  chrome_without_first_run
  a shell "am start -W -n $PKG/.MainActivity --es controlURL $CTRL" >/dev/null
  await_text 30 "Use my own computers" || fail "no welcome: $(screen_text | tr '\n' '|')"
  tap_text "Use my own computers"
  await_text 60 Connect || fail "no Connect card: $(screen_text | tr '\n' '|')"
  shot signin-card
  # Ovenlight connects to Chrome once the owner needs a sign-in, and a tab opened before Chrome
  # answers goes without the keep-alive: a cold Chrome, just force-stopped, mustn't race it.
  chrome_tabs=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabsConnectionService
  until_ok 30 bound $chrome_tabs $PKG || fail "Ovenlight never connected to Chrome"
  tap_text Connect
  # Headscale's registration page, in the Custom Tab: its address names the registration.
  reg=
  for i in $(seq 1 30); do
    reg=$(a shell dumpsys activity activities | LC_ALL=C grep -o -E "127\.0\.0\.1:$HSPORT/register/[A-Za-z0-9_-]+" | head -1 | sed 's|.*/register/||' || true)
    [[ -n $reg ]] && break
    sleep 1
  done
  [[ -n $reg ]] || { shot signin-failed; fail "no sign-in page opened"; }
  shot signin-tab
  note "the sign-in page opened in a Custom Tab; approving in $SIGNIN_WAIT s"
  sleep $SIGNIN_WAIT
  # What keeps Ovenlight from being frozen behind the tab, on API 29 too, which has no freezer.
  bound $PKG/.TabKeepAlive com.android.chrome || { shot signin-failed; fail "Chrome isn't keeping Ovenlight running behind the tab"; }
  note "Chrome keeps Ovenlight running behind the tab"
  $HS -c $D/headscale.yaml nodes register --user owner --key "$reg" >/dev/null || fail "headscale couldn't register the phone"
  # Ovenlight comes back over the tab by itself once the node connects, as on iOS.
  for i in $(seq 1 30); do [[ $(front) == *$PKG* ]] && break; sleep 1; done
  [[ $(front) == *$PKG* ]] || { shot signin-failed; fail "still behind the tab 30 s after the approval: $(front)"; }
  await_text 40 Recipes Pantry || { shot signin-failed; fail "back over the tab, but not connected: $(screen_text | tr '\n' '|')"; }
  note "Ovenlight came back over the tab by itself, connected"
  ! bound $chrome_tabs $PKG || fail "still connected to Chrome once the phone is"
  note "and it let Chrome go"
  shot signin-connected
  say "PASSED"
  exit 0
fi

# ---- G2, the node -------------------------------------------------------------------------
say "G2: a node joins the local Headscale with a preauth key"
lab "start --es labId lab --es controlURL $CTRL --es authKey $KEY"
line=$(await 60 '^start lab') || fail "the node didn't start"
note "$line"
[[ $line != *failed* ]] || fail "$line"
lab "waitup --es labId lab"
line=$(await 70 '^waitup lab') || fail "the node never reached Running"
[[ $line == *Running* ]] || fail "$line"
note "$line"
lab "probe --es labId lab --es labUrl https://recipes.$BASE/"
line=$(await 30 '^probe') || fail "no probe answer"
[[ $line == *"HTTP 200"* ]] || fail "the first probe: $line"
note "first probe: $line"

probe_until_ok() {
  local seconds=$1 start=$(date +%s)
  while (( $(date +%s) - start < seconds )); do
    lab "probe --es labId lab --es labUrl https://recipes.$BASE/"
    line=$(await 25 '^probe') || true
    if [[ $line == *"HTTP 200"* ]]; then printf '%s s\n' "$(( $(date +%s) - start ))"; return 0; fi
    sleep 2
  done
  return 1
}

say "G2: Wi-Fi off, then on"
a shell svc wifi disable
sleep 5
took=$(probe_until_ok 90) || fail "no answer over the cellular network with Wi-Fi off"
note "with Wi-Fi off (cellular): answering after $took"
a shell svc wifi enable
sleep 5
took=$(probe_until_ok 90) || fail "no answer after Wi-Fi came back"
note "Wi-Fi back: answering after $took"

say "G2: airplane mode on, then off"
a shell cmd connectivity airplane-mode enable
sleep 8
a shell cmd connectivity airplane-mode disable
took=$(probe_until_ok 120) || fail "no answer after airplane mode"
note "airplane mode off: answering after $took"

say "G2: a closed node restarts from its state"
lab "close --es labId lab"
await 20 '^close lab' >/dev/null || fail "close didn't finish"
lab "restart --es labId lab --es controlURL $CTRL"
line=$(await 90 '^restart lab') || fail "the node didn't restart"
[[ $line == *Running* ]] || fail "$line"
note "$line"
took=$(probe_until_ok 60) || fail "no answer after the restart"
note "first probe after the restart: answering after $took"

say "G2: four nodes at once"
a shell dumpsys meminfo $PKG > $OUT/meminfo-1-node.log
note "one node: $(grep -E 'TOTAL PSS|TOTAL:' $OUT/meminfo-1-node.log | head -1 | tr -s ' ' || true)"
for n in g1 g2 g3; do
  lab "start --es labId $n --es labHostname ovenlight-$n --es controlURL $CTRL --es authKey $KEY"
  await 60 "^start $n" >/dev/null || fail "node $n didn't start"
  lab "waitup --es labId $n"
  line=$(await 70 "^waitup $n") || fail "node $n never ran"
  [[ $line == *Running* ]] || fail "$line"
done
sleep 10
a shell dumpsys meminfo $PKG > $OUT/meminfo-4-nodes.log
note "four nodes: $(grep -E 'TOTAL PSS|TOTAL:' $OUT/meminfo-4-nodes.log | head -1 | tr -s ' ' || true)"
for n in g1 g2 g3; do lab "close --es labId $n"; await 20 "^close $n" >/dev/null || true; done

# ---- G3, the proxy and WebView ------------------------------------------------------------
say "G3: from adb shell, as another app on the phone"
cat > $D/g3shell.sh <<EOF
secret=\$(run-as $PKG sed -n 2p files/proxy.txt)
# The input stays open until the answer comes: a client that half-closes ends the request.
# Longer than the proxy's 8 seconds; the proxy closes every connection but a tunnel.
ask() { { printf "\$1"; sleep 9; } | nc -w 10 127.0.0.1 $PROXY_PORT 2>/dev/null | head -1 | tr -d '\r'; }
challenged() { { printf "\$1"; sleep 9; } | nc -w 10 127.0.0.1 $PROXY_PORT 2>/dev/null | grep -ci '^proxy-authenticate'; }
auth=\$(printf 'node-lab:%s' "\$secret" | base64 | tr -d '\n')
echo "no secret|\$(ask 'CONNECT recipes.$BASE:443 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n')|\$(challenged 'CONNECT recipes.$BASE:443 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n')"
echo "origin form|\$(ask 'GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n')|\$(challenged 'GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n')"
echo "absolute form|\$(ask 'GET http://127.0.0.1:$PROXY_PORT/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n')|\$(challenged 'GET http://127.0.0.1:$PROXY_PORT/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n')"
for t in recipes.$BASE:443 127.0.0.1:$PROXY_PORT '[::1]:443' '[::ffff:127.0.0.1]:$PROXY_PORT' '[64:ff9b::7f00:1]:443' 10.0.2.2:80 10.0.2.3:53 192.168.1.1:443 '[::ffff:192.168.1.1]:443' 100.64.0.250:443 nope.$BASE:443 someone.tail999.ts.net:443; do
  echo "\$t|\$(ask "CONNECT \$t HTTP/1.1\r\nHost: x\r\nConnection: close\r\nProxy-Authorization: Basic \$auth\r\n\r\n")|"
done
EOF
a push $D/g3shell.sh /data/local/tmp/ovl-g3.sh >/dev/null
a shell sh /data/local/tmp/ovl-g3.sh > $D/g3shell.out
a shell rm /data/local/tmp/ovl-g3.sh
while IFS='|' read -r what got chal; do note "$what: $got${chal:+, challenges: $chal}"; done < $D/g3shell.out
grep -q '^no secret|HTTP/1.1 407.*|1$' $D/g3shell.out || fail "a CONNECT without the secret didn't get a 407 challenge"
grep -q '^origin form|HTTP/1.1 400.*|0$' $D/g3shell.out || fail "an origin-form request didn't get a 400 without a challenge"
grep -q '^absolute form|HTTP/1.1 405.*|0$' $D/g3shell.out || fail "an absolute-form request didn't get a 405 without a challenge"
grep -q "^recipes.$BASE:443|HTTP/1.1 200" $D/g3shell.out || fail "the tailnet app wasn't reachable with the secret"
(( $(grep -c '|HTTP/1.1 502' $D/g3shell.out) == 11 )) || fail "a local, LAN or unknown tailnet target wasn't refused"

load_and_check() { # profile url key title-pattern
  local profile=$1 url=$2 key=$3 want=$4 title opened
  lab "open --es labProfile $profile --es labId lab --es labUrl '$url'"
  opened=$since
  await 60 "^page $profile finished" >/dev/null || fail "$url didn't load in profile $profile"
  sleep 6
  lab "eval --es labJs document.title"
  title=$(await 10 '^eval ' | sed -e 's/^eval [^:]*: //' -e 's/\\"/"/g' || true)
  note "profile $profile, $url: $title"
  [[ $title == *$want* ]] || fail "profile $profile's page reported $title"
  corelog "$opened" > $D/core-$profile.log
  lablog "$opened" > $D/lab-$profile.log
  { grep '^proxy: ' $D/core-$profile.log || true; } | cut -d' ' -f2- | sed 's/: open$//' | sort | uniq -c | while read -r l; do note "  $l"; done
  # Tunnels only: a plain request (405 or 400, as Android 17's WebView sends the page's fetch of
  # the proxy's own port) opens none and has no key.
  keys=$({ grep '^proxy: ' $D/core-$profile.log | grep -v -E ': (400|405)$' || true; } | cut -d' ' -f2 | sort -u | tr '\n' ' ')
  [[ $keys == "$key " ]] || fail "profile $profile's tunnels carried keys: $keys"
}

say "G3: profile A answers the 407 with its own key, for every request of its page"
load_and_check A "https://recipes.$BASE/#port=$PROXY_PORT" app-A '"ws":"ping"'
grep -q "^proxy: app-A pantry.$BASE:443: open" $D/core-A.log || fail "Pantry's subresources didn't go through profile A's key"
grep -q "^proxy: app-A ovenlight.app:443: open" $D/core-A.log || fail "the internet fetch didn't go through profile A's key"
# The page's fetch of the proxy's own port: Chromium blocks it before it leaves, and were it
# sent, it would come through the proxy as a plain request (405, no challenge). It must never
# open a tunnel, nor reach the port directly, which would show as an origin-form request:
# that is what a lost removeImplicitRules would look like.
! grep -q "127.0.0.1:$PROXY_PORT: open" $D/core-A.log || fail "the page opened a tunnel to the proxy's own port"
! grep -q '^proxy: GET origin form' $D/core-A.log || fail "the page reached the proxy's port directly, past the proxy"
note "the page's fetch of the proxy's port: $(grep -E "^proxy: GET http://127.0.0.1:$PROXY_PORT/: " $D/core-A.log | head -1 || echo "never reached the proxy")"
{ grep -E '^challenge' $D/lab-A.log || true; } | while read -r l; do note "$l"; done
shot g3-profile-A

say "G3: profile B, with its own key"
load_and_check B "https://pantry.$BASE/" app-B 'Pantry'

# A query no load has used, so the page can't come from profile A's HTTP cache.
say "G3: with profile A's route cleared, nothing loads"
lab "clearRoute --es labProfile A"
await 10 '^clearRoute' >/dev/null
lab "open --es labProfile A --es labRoute none --es labUrl https://recipes.$BASE/?fresh=$RANDOM"
line=$(await 30 '^error A main frame') || fail "a page with no route showed no error"
note "no route: $line"
opened=$since
sleep 3
corelog "$opened" | grep '^proxy: ' | sort -u | while read -r l; do note "  $l"; done
! corelog "$opened" | grep '^proxy: ' | grep -v ': 502 no route$' >/dev/null || fail "with no route, a CONNECT got something other than a refusal"
lab "proxy"
await 10 '^proxy override' >/dev/null

say "G3: with the proxy override on a closed port, nothing loads"
lab "closedPort"
await 10 '^proxy override' >/dev/null || fail "the closed-port override didn't apply"
lab "open --es labProfile C --es labId lab --es labUrl https://recipes.$BASE/"
line=$(await 30 '^error C main frame') || fail "a page with the proxy gone showed no error"
note "closed port: $line"
lab "proxy"
await 10 '^proxy override' >/dev/null

# ---- G4, profiles -------------------------------------------------------------------------
say "G4: storage set in one profile is invisible in another"
lab "page --es labProfile S1 --es labBase https://lab.$BASE/"
await 30 '^page S1 finished' >/dev/null || fail "the lab page didn't load"
res=$(evaljs "labSet(\"S1\")" 2); note "S1 set: $res"
res=$(evaljs 'labGet()' 2); note "S1 reads: $res"
[[ $res == *'"cookie":"S1"'*'"local":"S1"'*'"idb":"S1"'*'"cache":"S1"'* ]] || fail "S1 didn't keep its own storage: $res"
lab "page --es labProfile S2 --es labBase https://lab.$BASE/"
await 30 '^page S2 finished' >/dev/null || fail "the lab page didn't load in S2"
res=$(evaljs 'labGet()' 2); note "S2 reads: $res"
[[ $res == *'"cookie":"","local":"","idb":"","cache":""'* ]] || fail "S2 saw another profile's storage: $res"

say "G4: deleteBrowsingData empties a loaded profile"
lab "wipe --es labProfile S1"
await 20 '^wipe S1' >/dev/null || fail "the wipe didn't finish"
lab "page --es labProfile S1 --es labBase https://lab.$BASE/"
await 30 '^page S1 finished' >/dev/null || fail "the lab page didn't load in S1 again"
res=$(evaljs 'labGet()' 2); note "S1 after the wipe: $res"
[[ $res == *'"cookie":"","local":"","idb":"","cache":""'* ]] || fail "S1 kept storage after deleteBrowsingData: $res"

say "G4: deleteProfile at the next launch"
lab "profiles"; note "$(await 10 '^profiles' || echo none)"
a shell am force-stop $PKG
a shell "am start -W -n $PKG/.MainActivity --ez writeProxySecret true" >/dev/null
unlock_now
lab "profiles"
line=$(await 15 '^profiles') || fail "no profile list after the relaunch"
note "after the relaunch: $line"
[[ $line != *S1* ]] || fail "S1 survived the relaunch"
[[ $line == *S2* ]] || fail "S2 went too"

# ---- G5, the page -------------------------------------------------------------------------
lab "start --es labId lab --es controlURL $CTRL"
await 60 '^start lab' >/dev/null || fail "the node didn't start after the relaunch"
lab "waitup --es labId lab"; await 70 '^waitup lab' >/dev/null || fail "the node didn't run after the relaunch"

# Last, since the keyboard can't be put away without leaving the page.
say "G5: an input at the bottom with the keyboard up"
lab "page --es labProfile I --es labBase https://lab.$BASE/"
await 30 '^page I finished' >/dev/null
sleep 2
spot=$(evaljs 'window.labResult = (() => { const r = document.getElementById("field").getBoundingClientRect(); return Math.round((r.left + r.width / 2) * devicePixelRatio) + " " + Math.round((r.top + r.height / 2) * devicePixelRatio); })()' 1 | tr -d '"')
a shell input tap $spot
sleep 3
res=$(evaljs 'window.labResult = JSON.stringify({focused: document.activeElement.id, inputBottom: Math.round(document.getElementById("field").getBoundingClientRect().bottom), visual: Math.round(visualViewport.height), offset: Math.round(visualViewport.offsetTop), layout: innerHeight})' 1)
note "keyboard up: $res"
shot g5-keyboard
[[ $res == *'"focused":"field"'* ]] || fail "the tap didn't focus the input"
python3 -c 'import json, sys; r = json.loads(sys.argv[1].strip(chr(34)) if sys.argv[1].startswith(chr(34)+"{") else sys.argv[1]); sys.exit(0 if r["visual"] < r["layout"] and r["inputBottom"] <= r["offset"] + r["visual"] else 1)' "$res" ||
  fail "with the keyboard up, the input isn't inside the visual viewport"

say "G3: no DNS lookup of a ts.net name left the emulator"
a logcat -d > $OUT/logcat.log 2>/dev/null || true
a uninstall $PKG >/dev/null
a reverse --remove-all >/dev/null
$ROOT/android/scripts/avds.sh stop $emulator_port
emulator_port=
[[ -s $D/emulator.pcap ]] || fail "the emulator wrote no network capture"
tcpdump -nn -r $D/emulator.pcap -A 'udp port 53 or tcp port 53' > $D/dns.txt 2>/dev/null || fail "tcpdump couldn't read the capture"
lookups=$(grep -c -E ' (A|AAAA)\? ' $D/dns.txt || true)
dns=$(grep -c -i 'ts\.net' $D/dns.txt || true)
note "DNS lookups in the capture: $lookups; naming ts.net: $dns"
(( lookups > 0 )) || fail "the capture holds no DNS lookups at all, so it proves nothing"
(( dns == 0 )) || fail "a ts.net name was looked up in DNS"

say "PASSED"
