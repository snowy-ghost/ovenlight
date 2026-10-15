#!/bin/sh
# The fixed set of Android emulators, on the mini only. Sessions reuse these by name and
# never create, delete or wipe one by hand.
#
#   avds.sh list                 the set, and which of it exists
#   avds.sh create               creates whatever of the set is missing, never deletes
#   avds.sh snapshot <avd>       boots with fresh data, brings WebView to M140 or later, sets
#                                the PIN 1111, enrolls fingerprint 1, turns animations off and
#                                saves the snapshot "ready"
#   avds.sh boot <avd> <port>    boots headless from "ready" on that even console port,
#                                waits for it, and prints its serial and pid
#   avds.sh window <avd> <port>  cold boots with a window, keeping its data, for a step by hand
#                                over Screen Sharing (the API 29 AVD's Play Store sign-in)
#   avds.sh stop <port>          stops the emulator on that port, by the pid boot recorded
#   avds.sh webview <port>       prints the running emulator's WebView version
#
# Pass -read-only through OVENLIGHT_AVD_FLAGS when another session has that AVD open.
#
# Ovenlight needs WebView M140 or later, and the API 36 images ship M133. snapshot installs
# Google's own WebView from webview/ beside the SDK (pulled from the API 37 image, which
# Android accepts only with Google's signature). Its build needs API 32 or later, so the
# API 29 AVD uses the Play image instead, updated once through the Play Store after a
# Google sign-in over Screen Sharing, before its snapshot. To fetch the APK, boot the API 37
# AVD and pull the file that `adb shell pm path com.google.android.webview` names into webview/.
set -eu

: "${ANDROID_HOME:?set ANDROID_HOME (the mini's zshenv does)}"
: "${ANDROID_AVD_HOME:?set ANDROID_AVD_HOME (the mini's zshenv does)}"
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
run="$ANDROID_AVD_HOME/../run"
webviews="$ANDROID_HOME/../webview"

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

wait_boot() {
	"$adb" -s "$serial" wait-for-device
	i=0
	until [ "$(sh_ getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
		i=$((i + 1))
		[ "$i" -lt 300 ] || die "$serial didn't finish booting"
		sleep 1
	done
}

# Taps the first node in the screen's UI dump whose text or description matches.
tap_text() {
	n=0
	while [ "$n" -lt 4 ]; do
		b=$("$adb" -s "$serial" exec-out uiautomator dump /dev/tty 2>/dev/null |
			tr '>' '\n' | grep -i -E "(text|content-desc)=\"($1)\"" | head -1 |
			sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
		if [ -n "$b" ]; then
			set -- $b
			sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
			return 0
		fi
		n=$((n + 1))
		sleep 1
	done
	return 1
}

start() { # avd port extra-flags...
	avd=$1 port=$2
	shift 2
	case $port in *[!0-9]*|'') die "port must be a number";; esac
	[ $((port % 2)) -eq 0 ] && [ "$port" -ge 5554 ] && [ "$port" -le 5682 ] || die "port must be even, 5554 to 5682"
	serial=emulator-$port
	mkdir -p "$run"
	! "$adb" devices | grep -q "^$serial" || die "$serial is already running"
	# shellcheck disable=SC2086
	"$emulator" -avd "$avd" -port "$port" -no-audio -no-boot-anim \
		${OVENLIGHT_WINDOW:--no-window -gpu swiftshader_indirect} "$@" ${OVENLIGHT_AVD_FLAGS:-} \
		> "$run/$serial.log" 2>&1 &
	echo $! > "$run/$serial.pid"
	wait_boot
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
		config "$name" hw.ramSize "$ram"
		config "$name" disk.dataPartition.size 8G
		config "$name" hw.keyboard yes
		config "$name" fastboot.forceColdBoot no
		case $image in *playstore*) config "$name" PlayStore.enabled yes;; esac
		echo "created $name"
	done
	;;
snapshot)
	[ $# -eq 2 ] || die "usage: avds.sh snapshot <avd>"
	known "$2"
	port=5680
	wipe=-wipe-data
	[ -z "${OVENLIGHT_KEEP_DATA:-}" ] || wipe=
	start "$2" "$port" -no-snapshot-load -no-snapshot-save $wipe
	webview_major=$(webview_major)
	if [ "$webview_major" -lt 140 ]; then
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
		while [ "$(webview_major)" -lt 140 ] && [ "$i" -lt 20 ]; do sleep 1; i=$((i + 1)); done
		webview_major=$(webview_major)
	fi
	if [ "$webview_major" -lt 140 ]; then
		echo "avds.sh: $2 has WebView M$webview_major. Update it through the Play Store over Screen Sharing, then run snapshot again with OVENLIGHT_KEEP_DATA=1" >&2
		"$0" stop "$port"
		exit 1
	fi
	echo "WebView M$webview_major"
	for k in window_animation_scale transition_animation_scale animator_duration_scale; do
		sh_ settings put global "$k" 0
	done
	sh_ settings put global sysui_demo_allowed 1
	sh_ settings put system screen_off_timeout 2147483647
	sh_ svc power stayon true
	sh_ locksettings set-pin 1111 > /dev/null
	# Enroll fingerprint 1 through Settings, confirming the PIN first.
	sh_ am start -a android.settings.FINGERPRINT_ENROLL > /dev/null 2>&1 ||
		sh_ am start -n com.android.settings/.biometrics.fingerprint.FingerprintEnrollIntroduction > /dev/null
	sleep 3
	sh_ input text 1111
	sh_ input keyevent 66
	sleep 3
	# The introduction scrolls with More until I Agree shows.
	i=0
	until tap_text 'I agree' || [ "$i" -ge 5 ]; do tap_text 'More' || true; i=$((i + 1)); done
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
	tap_text 'Done|DONE' || true
	sh_ input keyevent 3
	if sh_ dumpsys fingerprint | grep -q '"count":1'; then
		echo "fingerprint 1 enrolled"
	else
		echo "avds.sh: couldn't confirm the fingerprint; enroll it over Screen Sharing, then save with: adb -s $serial emu avd snapshot save ready" >&2
	fi
	"$adb" -s "$serial" emu avd snapshot save ready > /dev/null
	echo "saved ready for $2"
	"$0" stop "$port"
	;;
boot)
	[ $# -eq 3 ] || die "usage: avds.sh boot <avd> <port>"
	known "$2"
	start "$2" "$3" -snapshot ready -no-snapshot-save
	echo "$serial $(cat "$run/$serial.pid")"
	;;
window)
	[ $# -eq 3 ] || die "usage: avds.sh window <avd> <port>"
	known "$2"
	OVENLIGHT_WINDOW="-gpu host" start "$2" "$3" -no-snapshot-load -no-snapshot-save
	echo "$serial $(cat "$run/$serial.pid")"
	;;
stop)
	[ $# -eq 2 ] || die "usage: avds.sh stop <port>"
	serial=emulator-$2
	pidfile="$run/$serial.pid"
	[ -f "$pidfile" ] || die "no recorded pid for $serial"
	pid=$(cat "$pidfile")
	"$adb" -s "$serial" emu kill > /dev/null 2>&1 || true
	i=0
	while kill -0 "$pid" 2> /dev/null && [ "$i" -lt 30 ]; do sleep 1; i=$((i + 1)); done
	kill "$pid" 2> /dev/null || true
	rm "$pidfile"
	i=0
	while "$adb" devices | grep -q "^$serial" && [ "$i" -lt 15 ]; do sleep 1; i=$((i + 1)); done
	;;
webview)
	[ $# -eq 2 ] || die "usage: avds.sh webview <port>"
	serial=emulator-$2
	sh_ dumpsys webviewupdate | grep -E 'Current WebView package|versionName' | head -3
	;;
*)
	sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
	exit 2
	;;
esac
