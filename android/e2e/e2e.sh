#!/bin/bash
# Ovenlight for Android, end to end on the mini's emulator: a local Headscale, a dev
# connector serving two test apps, and the debug build on Ovenlight_API36, which reaches
# both through adb reverse. Nothing reaches the real tailnet or the live connector: the
# connector runs from this run's folder with its own HOME, config and state, logs in only to
# the local Headscale, and stops by its recorded pid.
#
#   android/e2e/e2e.sh [gates | owner | signin [seconds] | screens | app | chrome | downloads | upload | return] [avd]
#
# gates runs Phase 1's checks G2 to G5 through the debug lab. owner signs the owner's node in
# with a preauth key and waits for the launcher to show both test apps. signin goes through
# the launcher's Connect card and the sign-in page in Chrome's Custom Tab, approves the phone
# after [seconds] (default 90: Android freezes an app about a minute after it goes out of
# sight), and checks that Ovenlight comes back over the tab by itself, connected. screens
# signs in as owner does, takes the launcher, Settings and lock screenshots in light and
# dark, and checks the lock: at Immediately, Home and back through the icon or Recents asks
# for it, and a canceled prompt doesn't come back by itself. app opens Recipes from the
# launcher and waits for its page.
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
# Each writes what it saw to
# android/e2e/out/: gates.txt, logs and screenshots.
#
# Needs Headscale v0.29.4 (docs/development.md#local-headscale) and the fixed AVD set
# (android/scripts/avds.sh). The test CA lives in test-ca/ beside the SDK, made on the first
# run, and the debug build trusts it, so this builds the app itself.
# bash, not zsh: zsh skips its EXIT trap when err_exit fires inside a function, which would
# leave Headscale, the connector and the emulator running after a failure.
set -Eeuo pipefail

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
[[ $SCENARIO =~ ^(gates|owner|signin|screens|app|chrome|downloads|upload|return)$ ]] || fail "unknown scenario $SCENARIO"

say "the test CA in $CA, made once, and a *.$BASE certificate for this run"
if [[ ! -f $CA/ca.pem ]]; then
  mkdir -p $CA && chmod 700 $CA
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj "/CN=Ovenlight e2e test CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign" -keyout $CA/ca.key -out $CA/ca.pem 2>/dev/null
fi
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=*.$BASE" -keyout $D/leaf.key -out $D/leaf.csr 2>/dev/null
printf 'subjectAltName=DNS:*.%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature\nbasicConstraints=critical,CA:FALSE\n' $BASE > $D/leaf.ext
openssl x509 -req -in $D/leaf.csr -CA $CA/ca.pem -CAkey $CA/ca.key -CAcreateserial -CAserial $D/ca.srl -days 2 -extfile $D/leaf.ext -out $D/leaf.pem 2>/dev/null

say "the debug build, which trusts that CA"
(cd $ROOT/android && ./gradlew -q :app:assembleDebug) > $D/gradle.log 2>&1 || fail "the build failed: $(tail -20 $D/gradle.log)"
APK=$ROOT/android/app/build/outputs/apk/debug/app-debug.apk

say "headscale on 127.0.0.1, base domain $BASE"
HSPORT=$(port) METRICS=$(port) GRPC=$(port)
CTRL=http://127.0.0.1:$HSPORT
printf '%s\n' '{"acls": [{"action": "accept", "src": ["owner@"], "dst": ["owner@:*"]}]}' > $D/policy.hujson
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

say "the connector, built from the tree, with its own HOME, config and state"
export GOCACHE="$(go env GOCACHE)" GOMODCACHE="$(go env GOMODCACHE)"
go -C $ROOT/connector build -o $D/ovenlight .
mkdir -p $D/home
c() { env HOME=$D/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight "$@"; }
for app in recipes:Recipes pantry:Pantry; do
  name=${app#*:} app=${app%%:*}
  c publish --config $D/config.json --state $D/st --port $(port) --name "$name" --slug $app \
    --run "python3 $ROOT/android/e2e/app.py" --dir "$D/$app" >> $D/publish.log 2>&1 || fail "publish $app: $(cat $D/publish.log)"
done
( exec env HOME=$D/home OVENLIGHT_DEV=1 TS_NO_LOGS_NO_SUPPORT=1 $D/ovenlight run --config $D/config.json --state $D/st \
    --control-url $CTRL --auth-key $KEY --dev-tls-cert $D/leaf.pem --dev-tls-key $D/leaf.key >> $D/connector.log 2>&1 ) &
connector_pid=$!
until_ok 120 sh -c "env HOME=$D/home $D/ovenlight status --config $D/config.json --state $D/st --json | grep -c '\"state\": \"serving\"' | grep -qx 2" ||
  fail "the connector didn't serve both apps"

say "$AVD, booted from ready with a network capture, and the app"
for p in $(seq 5554 2 5582); do
  if ! adb devices | grep -q "^emulator-${p}[[:space:]]"; then emulator_port=$p; break; fi
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
locked() { screen_text | grep -qxE 'Unlock Ovenlight|Ovenlight is locked'; }
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
  for i in 1 2 3 4; do screen_text | grep -qxF "Unlock Ovenlight" && break; sleep 1; done
  screen_text | grep -qxF "Unlock Ovenlight" || { tap_text Unlock || true; sleep 2; }
  for i in 1 2 3; do
    a emu finger touch 1 >/dev/null
    sleep 2
    locked || return 0
  done
  fail "Ovenlight didn't unlock: $(screen_text | tr '\n' '|')"
}

# The activity in front, as the activity manager names it.
front() { a shell dumpsys activity activities | LC_ALL=C grep -m1 -E 'topResumedActivity|mResumedActivity' || true; }
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
  # Loaded, the pill tucks itself away 2.5 s later, leaving its handle.
  await_text 10 "Show app menu" || { shot chrome-failed; fail "the pill didn't tuck away: $(screen_text | tr '\n' '|')"; }
  ! screen_text | grep -qxF "Recipes options" || fail "the pill still shows beside its handle"
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
  for i in $(seq 1 10); do screen_text | grep -qxF "Feedback sent" || break; sleep 1; done
  ! screen_text | grep -qxF "Feedback sent" || fail "Send feedback didn't close after sending"
  await_text 5 "Recipe Box" || fail "the page isn't back after Send feedback: $(screen_text | tr '\n' '|')"
  note "Send feedback showed Feedback sent and closed by itself"
  inbox=$(env HOME=$D/home $D/ovenlight feedback --config $D/config.json --state $D/st --app recipes --json)
  printf '%s\n' "$inbox" > $OUT/chrome-feedback.json
  size=$(a shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r')
  # The note, the page and a PNG as wide as the screen. Its column at 60 percent across runs
  # through the pill and the handle, right of the page's heading: from the top to the pill's
  # bottom it must be all the page's own white.
  python3 - "$size" "$OUT/chrome-feedback.json" "${pill[3]}" <<'PY' || fail "the connector's feedback isn't as sent: $inbox"
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
  note "the connector has the note, the page and a $size PNG with no pill or handle in it"
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
  if screen_text | grep -qxF Open; then note "the blob's sheet offers Open, for plain text"; fi
  got=$(downloaded note.txt)
  [[ $got == "blob from the page" ]] || fail "the blob download's bytes: $got"
  note "the blob arrived, though the page revoked its URL right after the click"
  # Share and Open run in tasks of their own, so Recents never shows the file under Ovenlight's card.
  top_task() { a shell dumpsys activity activities | grep -m1 topResumedActivity | grep -o ' t[0-9]*}'; }
  own=$(top_task)
  tap_text Share; sleep 3
  [[ $(top_task) != "$own" ]] || fail "the Sharesheet runs in Ovenlight's task"
  note "the Sharesheet runs in a task of its own"
  a shell input keyevent KEYCODE_BACK
  await_text 10 "Download blob" || fail "not back on the page after the Sharesheet: $(screen_text | tr '\n' '|')"
  tap_text "Download blob"
  await_text 30 "note.txt" Save Share || { shot downloads-failed; fail "no sheet for the blob again: $(screen_text | tr '\n' '|')"; }
  if screen_text | grep -qxF Open; then
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
  for i in $(seq 1 15); do screen_text | grep -qE '^Camera (live|error)' && break; sleep 1; done
  if screen_text | grep -qxF "Camera live"; then
    note "the page has the camera after Ovenlight's prompt"
    (( $(camera_clients) > 0 )) || fail "the camera service lists no client while the page captures, so it can't show one closing: $(a shell dumpsys media.camera | grep -A5 'Active Camera Clients')"
    lock_now
    sleep 3
    (( $(camera_clients) == 0 )) || fail "the camera is still open under the lock: $(a shell dumpsys media.camera | grep -A3 'Active Camera Clients')"
    ! a logcat -d -s Ovenlight | grep -q 'still capturing after the stop' || fail "the page script didn't stop the camera, so the view went"
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
  tap_text More && sleep 1 && tap_text Settings
  await_text 10 "Require screen lock" || fail "no Settings: $(screen_text | tr '\n' '|')"
  sleep 1; shot settings-dark
  night no; shot settings-light
  a shell input keyevent KEYCODE_BACK; sleep 2
  # Landscape, then the largest font, each on the launcher and Settings.
  a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 3
  shot launcher-landscape
  tap_text More && sleep 1 && tap_text Settings && sleep 2 && shot settings-landscape
  a shell input keyevent KEYCODE_BACK; sleep 2
  a shell settings put system user_rotation 0; sleep 3
  a shell settings put system font_scale 2.0; sleep 3
  shot launcher-largest-font
  tap_text More && sleep 1 && tap_text Settings && sleep 2 && shot settings-largest-font
  a shell input keyevent KEYCODE_BACK; sleep 2
  a shell settings put system font_scale 1.0; sleep 3
  await_text 10 Recipes || fail "the launcher didn't come back after the font change"
  tap_text More && sleep 1 && tap_text Settings
  await_text 10 "Require screen lock" || fail "no Settings after the font change"
  note "screenshots of the launcher and Settings in light, dark, landscape and at the largest font"

  say "lock: Immediately, from Settings"
  tap_text "Require screen lock" && sleep 1 && tap_text Immediately
  await_text 10 Immediately || fail "the delay didn't change"
  a shell input keyevent KEYCODE_BACK; sleep 2
  tap_text "Lock Ovenlight"; sleep 2
  KEEP_LOCK=1 await_text 10 "Ovenlight is locked" || fail "Lock Ovenlight didn't lock"
  shot lock-light; night yes; shot lock-dark; night no
  note "Lock Ovenlight locks; screenshots of the lock screen in light and dark"
  unlock_now
  await_text 20 Recipes || fail "didn't unlock"

  say "lock: Home, then back through the icon"
  a shell input keyevent KEYCODE_HOME; sleep 3
  a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
  locked || fail "back through the icon without the lock: $(screen_text | tr '\n' '|')"
  note "back through the icon: locked"
  # The prompt opened by itself; cancel it, and it must stay closed.
  a shell input keyevent KEYCODE_BACK; sleep 6
  ! screen_text | grep -qxF 'Unlock Ovenlight' || fail "the canceled prompt came back by itself"
  KEEP_LOCK=1 await_text 5 "Ovenlight is locked" || fail "the lock screen went away: $(screen_text | tr '\n' '|')"
  note "a canceled prompt stays closed, and the lock screen stays"
  unlock_now
  await_text 20 Recipes || fail "didn't unlock"

  say "lock: Home, then back through Recents"
  a shell input keyevent KEYCODE_HOME; sleep 3
  a shell input keyevent KEYCODE_APP_SWITCH; sleep 3
  size=$(a shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r'); w=${size%x*}; h=${size#*x}
  a shell input tap $((w / 2)) $((h / 2)); sleep 3
  locked || fail "back through Recents without the lock: $(screen_text | tr '\n' '|')"
  note "back through Recents: locked"
  say "PASSED"
  exit 0
fi

if [[ $SCENARIO == owner ]]; then
  say "owner: the owner's node signs in with a preauth key"
  owner_launcher
  shot owner-launcher
  note "the launcher shows Recipes and Pantry"
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
  $HS -c $D/headscale.yaml nodes register --user owner --key "$reg" >/dev/null || fail "headscale couldn't register the phone"
  # Ovenlight comes back over the tab by itself once the node connects, as on iOS.
  for i in $(seq 1 30); do [[ $(front) == *$PKG* ]] && break; sleep 1; done
  [[ $(front) == *$PKG* ]] || { shot signin-failed; fail "still behind the tab 30 s after the approval: $(front)"; }
  await_text 40 Recipes Pantry || { shot signin-failed; fail "back over the tab, but not connected: $(screen_text | tr '\n' '|')"; }
  note "Ovenlight came back over the tab by itself, connected"
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
  keys=$({ grep '^proxy: ' $D/core-$profile.log || true; } | cut -d' ' -f2 | sort -u | tr '\n' ' ')
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
! corelog "$opened" | grep '^proxy: ' | grep -q -v ': 502 no route$' || fail "with no route, a CONNECT got something other than a refusal"
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

insets() { # name
  lab "page --es labProfile I --es labBase https://lab.$BASE/"
  await 30 '^page I finished' >/dev/null || fail "the lab page didn't load"
  sleep 2
  lab "insets"
  line=$(await 10 '^insets') || fail "no insets"
  note "$1: ${line#insets I: }"
  shot g5-$1
}
nav() { a shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.$1 >/dev/null; sleep 3; }
rotate() { a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation $1; sleep 3; }

say "G5: env(safe-area-inset-*) beside Android's insets"
nav gestural; rotate 0; insets portrait-gestures
rotate 1; insets landscape-gestures
nav threebutton; rotate 0; insets portrait-three-button
rotate 1; insets landscape-three-button
nav gestural; rotate 0

# Each case in its own profile, with what the proxy answered beside WebView's error.
error_case() { # profile what url [extras]
  local profile=$1 what=$2 url=$3 extras=${4:-} line opened
  lab "open --es labProfile $profile --es labId lab $extras --es labUrl $url"
  opened=$since
  line=$(await 40 "^error $profile main frame" || echo "no error")
  # The proxy's own answer can come after WebView gives up.
  local proxy=
  for i in $(seq 1 12); do
    proxy=$(corelog "$opened" | grep "^proxy: app-$profile " | sed 's/^proxy: [^ ]* //' | head -1 || true)
    [[ -n $proxy ]] && break
    sleep 1
  done
  note "$what: ${line#error $profile main frame }; the proxy: ${proxy:-nothing}"
}
say "G5: the WebView error codes Phase 4 maps"
error_case E1 "refused CONNECT (an unknown tailnet name)" https://nope.$BASE/
lab "start --es labId ghost --es labHostname ghost --es controlURL $CTRL --es authKey $KEY"
await 60 '^start ghost' >/dev/null; lab "waitup --es labId ghost"; await 70 '^waitup ghost' >/dev/null || fail "the ghost node never ran"
lab "close --es labId ghost"; await 20 '^close ghost' >/dev/null
sleep 3
error_case E2 "a peer that doesn't answer" https://ghost.$BASE/
lab "close --es labId lab"; await 20 '^close lab' >/dev/null
error_case E3 "a CONNECT held past Chromium's 10 second tunnel timeout" https://recipes.$BASE/ "--el labHold 15000"
error_case E4 "a CONNECT held for a node that never comes" https://recipes.$BASE/ "--el labHold 8000"
lab "closedPort"; await 10 '^proxy override' >/dev/null
error_case E5 "a closed port" https://recipes.$BASE/
lab "proxy"; await 10 '^proxy override' >/dev/null

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
lab "insets"; note "$(await 10 '^insets' || echo none)"
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
