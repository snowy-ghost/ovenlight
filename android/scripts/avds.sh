#!/bin/sh
# The fixed set of Android emulators, on the mini only. Sessions boot and stop these only
# through this script, and never create, delete or wipe one by hand.
#
#   avds.sh list                 the set, and which of it exists
#   avds.sh create               creates whatever of the set is missing, never deletes
#   avds.sh boot <avd> <port>    boots headless from "ready" on that console port, waits for
#                                it, unlocks it, and prints its serial and pid
#   avds.sh stop <port>          stops the emulator this script started on that port
#   avds.sh webview <port>       prints the running emulator's WebView version
#   avds.sh snapshot <avd>       boots with fresh data, brings WebView to M144 or later, sets
#                                the PIN 1111, enrolls fingerprint 1, turns animations off and
#                                saves the snapshot "ready"; OVENLIGHT_KEEP_DATA=1 keeps the
#                                data, after a step by hand in a window
#   avds.sh window <avd> <port>  cold boots with a window, keeping the data, for a step by
#                                hand over Screen Sharing
#
# Ports are even, 5554 to 5582, in the range adb looks in; snapshot and window use 5584. Set OVENLIGHT_AVD_FLAGS=-read-only
# to boot an AVD another session already has open.
#
# Ovenlight needs WebView M144 or later, and the API 36 images ship M133. snapshot installs
# Google's own WebView from webview/ beside the SDK (pulled from the API 37 image, which
# Android accepts only with Google's signature). Its build needs API 32 or later, so the
# API 29 AVD uses the Play image instead, updated once through the Play Store after a
# Google sign-in in a window, and then Play is turned off so it can't swap WebView mid-run.
# To fetch the APK, boot the API 37 AVD and pull the file that
# `adb shell pm path com.google.android.webview` names into webview/.

set -eu
# An interrupted run still reaches the EXIT trap that stops the emulator it started.
trap 'exit 1' INT TERM HUP

: "${ANDROID_HOME:?set ANDROID_HOME (the mini's zshenv does)}"
: "${ANDROID_AVD_HOME:?set ANDROID_AVD_HOME (the mini's zshenv does)}"
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
run="$ANDROID_AVD_HOME/../run"
webviews="$ANDROID_HOME/../webview"
snapshot_port=5584

# name|system image|device profile|RAM in MB
set_list() {
	cat <<'EOF'
Ovenlight_API36|system-images;android-36;google_apis;arm64-v8a|pixel_9|4096
Ovenlight_API37|system-images;android-37.0;google_apis;arm64-v8a|pixel_9|4096
Ovenlight_API29|system-images;android-29;google_apis_playstore;arm64-v8a|pixel_4a|2048
Ovenlight_Tablet_API36|system-images;android-36;google_apis;arm64-v8a|pixel_tablet|4096
Ovenlight_16K_API36|system-images;android-36;google_apis_ps16k;arm64-v8a|pixel_9|4096
EOF
}

die() { echo "avds.sh: $*" >&2; exit 1; }

known() { set_list | cut -d'|' -f1 | grep -qx "$1" || die "$1 isn't in the fixed set"; }

# Sets key=value in an AVD's config.ini.
config() {
	ini="$ANDROID_AVD_HOME/$1.avd/config.ini"
	grep -v "^$2[ ]*=" "$ini" > "$ini.new" || true
	echo "$2=$3" >> "$ini.new"
	mv "$ini.new" "$ini"
}

sh_() { "$adb" -s "$serial" shell "$@"; }

webview_major() {
	v=$(sh_ dumpsys webviewupdate | sed -n 's/.*Current WebView package (name, version): ([^,]*, \([0-9]*\)\..*/\1/p' | head -1)
	echo "${v:-0}"
}

# A pid file outlives its emulator after a crash or a reboot, and the pid may since
# belong to something else, so only the emulator on that port is ever signaled.
is_emulator() { # pid port
	case $(ps -p "$1" -o args= 2> /dev/null) in
	*qemu-system*" -port $2 "* | *qemu-system*" -port $2") return 0 ;;
	esac
	return 1
}

check_port() {
	case $1 in *[!0-9]* | '') die "port must be a number" ;; esac
	[ $(($1 % 2)) -eq 0 ] && [ "$1" -ge 5554 ] && [ "$1" -le 5584 ] || die "port must be even, 5554 to 5584"
}

start() { # avd port extra-flags...
	avd=$1 port=$2
	shift 2
	check_port "$port"
	serial=emulator-$port
	pidfile="$run/$serial.pid"
	mkdir -p "$run"
	! "$adb" devices | grep -q "^$serial[[:space:]]" || die "$serial is already running"
	if [ -f "$pidfile" ]; then
		! is_emulator "$(cat "$pidfile")" "$port" || die "$serial's emulator is still running; avds.sh stop $port"
		rm "$pidfile"
	fi
	# shellcheck disable=SC2086
	"$emulator" -avd "$avd" -port "$port" -no-audio -no-boot-anim -no-metrics \
		${OVENLIGHT_WINDOW:--no-window -gpu swiftshader_indirect} "$@" ${OVENLIGHT_AVD_FLAGS:-} \
		> "$run/$serial.log" 2>&1 &
	pid=$!
	echo "$pid" > "$pidfile"
	i=0
	until [ "$(sh_ getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]; do
		if ! kill -0 "$pid" 2> /dev/null; then
			rm "$pidfile"
			die "the emulator exited; see $run/$serial.log"
		fi
		i=$((i + 1))
		[ "$i" -lt 300 ] || { stop "$port"; die "$serial didn't finish booting"; }
		sleep 1
	done
}

stop() { # port
	serial=emulator-$1
	pidfile="$run/$serial.pid"
	[ -f "$pidfile" ] || die "no recorded pid for $serial"
	pid=$(cat "$pidfile")
	if is_emulator "$pid" "$1"; then
		# emu kill doesn't wait for the disk, so flush it first.
		"$adb" -s "$serial" shell sync > /dev/null 2>&1 || true
		"$adb" -s "$serial" emu kill > /dev/null 2>&1 || true
		i=0
		while is_emulator "$pid" "$1" && [ "$i" -lt 30 ]; do sleep 1; i=$((i + 1)); done
		! is_emulator "$pid" "$1" || kill "$pid"
	fi
	rm "$pidfile"
	i=0
	while "$adb" devices | grep -q "^$serial[[:space:]]" && [ "$i" -lt 15 ]; do sleep 1; i=$((i + 1)); done
}

# Taps the first node in the screen's UI dump whose text or description matches.
tap_text() {
	n=0
	while [ "$n" -lt 4 ]; do
		b=$("$adb" -s "$serial" exec-out uiautomator dump /dev/tty 2> /dev/null |
			tr '>' '\n' | grep -i -E "(text|content-desc)=\"($1)\"" | head -1 |
			sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
		if [ -n "$b" ]; then
			set -- $b
			sh_ input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
			return 0
		fi
		n=$((n + 1))
		sleep 1
	done
	return 1
}

keyguard_showing() { sh_ dumpsys window policy | grep -A1 KeyguardStateMonitor | grep -q 'mIsShowing=true'; }

# A phone left on its lock screen keeps every app in the background, where Android cuts
# their network within seconds, so a booted emulator is unlocked with its PIN.
unlock() {
	sh_ input keyevent KEYCODE_WAKEUP
	i=0
	while keyguard_showing && [ "$i" -lt 5 ]; do
		sh_ wm dismiss-keyguard > /dev/null 2>&1 || true
		sleep 1
		sh_ input text 1111
		sh_ input keyevent 66
		sleep 2
		i=$((i + 1))
	done
	! keyguard_showing || die "couldn't unlock $serial"
}

enrolled() { sh_ dumpsys fingerprint | grep -q '"count":[1-9]'; }

by_hand() {
	die "$1. Run avds.sh window $avd $snapshot_port, $2 over Screen Sharing, avds.sh stop $snapshot_port, then OVENLIGHT_KEEP_DATA=1 avds.sh snapshot $avd"
}

snapshot() { # avd
	avd=$1
	wipe=-wipe-data
	[ -z "${OVENLIGHT_KEEP_DATA:-}" ] || wipe=
	start "$avd" "$snapshot_port" -no-snapshot-load -no-snapshot-save $wipe
	trap 'stop "$snapshot_port"' EXIT
	unlock
	sdk=$(sh_ getprop ro.build.version.sdk | tr -d '\r')

	if [ "$(webview_major)" -lt 144 ] && [ "$sdk" -ge 32 ]; then
		# The package manager may still be settling just after boot, so try a few times.
		for apk in "$webviews"/*.apk; do
			[ -f "$apk" ] || continue
			i=0
			until "$adb" -s "$serial" install -r "$apk" > /dev/null || [ "$i" -ge 5 ]; do
				sleep 3
				i=$((i + 1))
			done
		done
		i=0
		while [ "$(webview_major)" -lt 144 ] && [ "$i" -lt 20 ]; do sleep 1; i=$((i + 1)); done
	fi
	major=$(webview_major)
	if [ "$major" -lt 144 ]; then
		[ "$sdk" -lt 32 ] || die "$avd has WebView M$major and no APK in $webviews installed; pull one from the API 37 AVD (see the top of this script)"
		by_hand "$avd has WebView M$major" "sign in to the Play Store and update Android System WebView"
	fi
	echo "WebView M$major"
	if sh_ pm path com.android.vending > /dev/null 2>&1; then
		sh_ pm disable-user --user 0 com.android.vending > /dev/null
	fi

	for k in window_animation_scale transition_animation_scale animator_duration_scale; do
		sh_ settings put global "$k" 0
	done
	sh_ settings put global sysui_demo_allowed 1
	sh_ settings put system screen_off_timeout 2147483647
	sh_ svc power stayon true
	sh_ locksettings set-pin 1111 > /dev/null 2>&1 ||
		sh_ locksettings verify --old 1111 > /dev/null ||
		die "$avd has a screen lock other than the PIN 1111"

	if ! enrolled; then
		# Settings asks for the PIN, then the introduction scrolls with More until
		# I Agree shows (API 29 has one Next instead), then the sensor wants a dozen or so
		# touches.
		sh_ am start -a android.settings.FINGERPRINT_ENROLL > /dev/null 2>&1 ||
			sh_ am start -n com.android.settings/.biometrics.fingerprint.FingerprintEnrollIntroduction > /dev/null
		sleep 3
		sh_ input text 1111
		sh_ input keyevent 66
		sleep 3
		i=0
		until tap_text 'I agree|Next' || [ "$i" -ge 5 ]; do tap_text 'More' || true; i=$((i + 1)); done
		sleep 3
		i=0
		while [ "$i" -lt 15 ]; do
			"$adb" -s "$serial" emu finger touch 1 > /dev/null
			sleep 0.7
			"$adb" -s "$serial" emu finger remove 1 > /dev/null 2>&1 || true
			sleep 0.5
			i=$((i + 1))
		done
		sleep 2
		tap_text 'Done' || true
		enrolled || by_hand "couldn't enroll fingerprint 1" "enroll it in Settings (PIN 1111; touch with adb -s emulator-$snapshot_port emu finger touch 1)"
	fi
	echo "fingerprint 1 enrolled"
	sh_ input keyevent 3

	out=$("$adb" -s "$serial" emu avd snapshot save ready 2>&1 || true)
	case $out in *OK*) ;; *) die "saving the snapshot failed: $out" ;; esac
	echo "saved ready for $avd"
}

case ${1:-} in
list)
	set_list | while IFS='|' read -r name image device ram; do
		if [ -d "$ANDROID_AVD_HOME/$name.avd" ]; then state=exists; else state=missing; fi
		echo "$name  $image  $device  ${ram} MB  $state"
	done
	;;
create)
	set_list | while IFS='|' read -r name image device ram; do
		[ -d "$ANDROID_AVD_HOME/$name.avd" ] && continue
		# Images without a devices.xml make avdmanager complain, harmlessly.
		echo no | "$avdmanager" create avd -n "$name" -k "$image" -d "$device" 2>&1 |
			grep -v 'devices\.xml' >&2 || true
		[ -f "$ANDROID_AVD_HOME/$name.avd/config.ini" ] || die "avdmanager didn't create $name"
		config "$name" hw.ramSize "$ram"
		config "$name" disk.dataPartition.size 8G
		config "$name" hw.keyboard yes
		config "$name" fastboot.forceColdBoot no
		case $image in *playstore*) config "$name" PlayStore.enabled yes ;; esac
		echo "created $name"
	done
	;;
boot)
	[ $# -eq 3 ] || die "usage: avds.sh boot <avd> <port>"
	known "$2"
	[ "$3" != "$snapshot_port" ] || die "port $snapshot_port is for snapshot and window; boot on 5554 to 5582"
	start "$2" "$3" -snapshot ready -no-snapshot-save
	# Anything that fails from here stops the emulator it started. The port is expanded now:
	# inside die, bash's sh would read $3 as die's own.
	trap "stop $3" EXIT
	grep -q "Successfully loaded snapshot 'ready'" "$run/$serial.log" ||
		die "$2 has no usable ready snapshot (see $run/$serial.log); run avds.sh snapshot $2"
	unlock
	trap - EXIT
	echo "$serial $pid"
	;;
window)
	[ $# -eq 3 ] || die "usage: avds.sh window <avd> <port>"
	known "$2"
	# Software rendering: the API 29 image crashes its system process under -gpu host.
	OVENLIGHT_WINDOW="-gpu swiftshader_indirect" start "$2" "$3" -no-snapshot-load -no-snapshot-save
	trap "stop $3" EXIT
	unlock
	trap - EXIT
	echo "$serial $pid"
	;;
stop)
	[ $# -eq 2 ] || die "usage: avds.sh stop <port>"
	check_port "$2"
	stop "$2"
	;;
snapshot)
	[ $# -eq 2 ] || die "usage: avds.sh snapshot <avd>"
	known "$2"
	snapshot "$2"
	;;
webview)
	[ $# -eq 2 ] || die "usage: avds.sh webview <port>"
	check_port "$2"
	serial=emulator-$2
	sh_ dumpsys webviewupdate | grep -E 'Current WebView package' | head -1
	;;
*)
	awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
	exit 2
	;;
esac
