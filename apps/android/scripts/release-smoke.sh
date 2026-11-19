#!/usr/bin/env bash
# Installs a release APK on the emulator adb is connected to and takes it
# through the paths that load code by reflection, which only the minified
# build can break: opening the pairing code scanner, pairing with a local
# server, taking a photo that reaches the server, and opening the document
# scanner. Fails if the app crashes or the photo doesn't arrive.
#
# Usage: release-smoke.sh <apk>, from anywhere in the repository after
# `bun install`. Needs an emulator image that allows `adb root` (google_apis),
# because the release build trusts only system certificates, so the script
# adds its own for the local server. Writes the UI dumps and the log to
# $SMOKE_OUT (default: a temporary directory) for a failed run.
set -euo pipefail

apk=$(realpath "$1")
package=io.github.davidvornholt.glimpse
root=$(git rev-parse --show-toplevel)
here="$root/apps/android/scripts"
out=${SMOKE_OUT:-$(mktemp -d)}
mkdir -p "$out"
work=$(mktemp -d)
screen="$work/ui.xml"
server_port=8797
proxy_port=8443
base="http://127.0.0.1:$server_port"
owner=smoke-owner-token
pids=()

# Keeps the log, the last screen and the last UI dump of any failed run, also
# one that a failing command or a timeout ended.
cleanup() {
  local status=$?
  if [ "$status" != 0 ]; then
    # The UI dump is already on disk. adb can hang, so it goes after the dump
    # and gets 10 s a read, within the 30 s the CI timeout leaves after its kill.
    cp "$screen" "$out/ui.xml" 2>/dev/null || true
    timeout 10 adb logcat -d >"$out/logcat.txt" 2>/dev/null || true
    timeout 10 adb exec-out screencap -p >"$out/screen.png" 2>/dev/null || true
    echo "release smoke test failed; logs and the last screen are in $out." >&2
  fi
  for pid in "${pids[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  rm -rf "$work"
}
trap cleanup EXIT
# Without these, bash runs the exit trap with the last command's status.
trap 'exit 143' TERM
trap 'exit 130' INT

fail() {
  echo "release smoke test: $*" >&2
  exit 1
}

# adb forwards the phone's own port to this machine, so the server is at
# 127.0.0.1 for both, and the phone needs no local network access.
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=Glimpse smoke CA' \
  -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign \
  -keyout "$work/ca.key" -out "$work/ca.pem" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj '/CN=127.0.0.1' \
  -keyout "$work/server.key" -out "$work/server.csr" 2>/dev/null
printf 'subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' >"$work/server.ext"
openssl x509 -req -in "$work/server.csr" -CA "$work/ca.pem" -CAkey "$work/ca.key" \
  -CAcreateserial -days 2 -extfile "$work/server.ext" -out "$work/server.pem" 2>/dev/null

mkdir -p "$work/data"
GLIMPSE_DATA_DIR="$work/data" \
  GLIMPSE_PUBLIC_URL="https://127.0.0.1:$proxy_port" \
  GLIMPSE_OWNER_TOKEN_SHA256=$(printf %s "$owner" | sha256sum | cut -d' ' -f1) \
  GLIMPSE_HOST=127.0.0.1 PORT=$server_port \
  bun "$root/apps/server/src/app/main.ts" >"$out/server.log" 2>&1 &
pids+=($!)
PROXY_PORT=$proxy_port PROXY_TARGET=$base PROXY_CERT="$work/server.pem" PROXY_KEY="$work/server.key" \
  bun "$here/https-proxy.ts" >"$out/proxy.log" 2>&1 &
pids+=($!)
for _ in $(seq 30); do
  curl -fsS -o /dev/null "$base/healthz" 2>/dev/null &&
    curl -fsS -o /dev/null --cacert "$work/ca.pem" "https://127.0.0.1:$proxy_port/healthz" 2>/dev/null && break
  sleep 1
done
curl -fsS -o /dev/null --cacert "$work/ca.pem" "https://127.0.0.1:$proxy_port/healthz" ||
  fail "the local server didn't start"

owner_get() {
  curl -fsS -H "Authorization: Bearer $owner" "$base$1"
}

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 1; done
# A Glimpse paired before reconnects to its old server instead of pairing with
# this one.
installed=$(adb shell pm list packages "$package" | tr -d '\r') || fail "couldn't list the installed apps"
if grep -qx "package:$package" <<<"$installed"; then
  fail "$package is already installed; run the test on a fresh emulator"
fi

# Adds the certificate to the system store of apps started from now on. Since
# Android 14 that store comes from the Conscrypt APEX, so a copy of the store
# with the certificate is mounted over both places, in this shell's mount
# namespace and in those of the zygotes that start apps.
# adb root restarts adbd and can lose its own reply when that is quick, so
# what counts is whether the shell then runs as root.
for _ in $(seq 10); do
  adb root >/dev/null 2>&1 || true
  adb wait-for-device
  [ "$(adb shell id -u | tr -d '\r')" = 0 ] && break
  sleep 2
done
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || fail "couldn't get root on the emulator"
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 1; done
adb reverse "tcp:$proxy_port" "tcp:$proxy_port" >/dev/null
hash=$(openssl x509 -subject_hash_old -noout -in "$work/ca.pem")
adb push "$work/ca.pem" "/data/local/tmp/$hash.0" >/dev/null
cat >"$work/mount-store.sh" <<'EOF'
set -e
store=/system/etc/security/cacerts
apex=/apex/com.android.conscrypt/cacerts
mount -t tmpfs tmpfs $store
cp /data/local/tmp/smoke-cacerts/* $store/
chown root:root $store/*
chmod 644 $store/*
chcon u:object_r:system_file:s0 $store/*
if [ -d $apex ]; then mount --bind $store $apex; fi
EOF
adb push "$work/mount-store.sh" /data/local/tmp/smoke-mount-store.sh >/dev/null
adb shell sh -s <<EOF || fail "couldn't add the certificate"
set -e
rm -rf /data/local/tmp/smoke-cacerts
mkdir -m 755 /data/local/tmp/smoke-cacerts
if [ -d /apex/com.android.conscrypt/cacerts ]; then source=/apex/com.android.conscrypt/cacerts; else source=/system/etc/security/cacerts; fi
cp \$source/* /data/local/tmp/smoke-cacerts/
cp /data/local/tmp/$hash.0 /data/local/tmp/smoke-cacerts/
sh /data/local/tmp/smoke-mount-store.sh
for zygote in \$(pidof zygote zygote64); do
  nsenter --mount=/proc/\$zygote/ns/mnt -- sh /data/local/tmp/smoke-mount-store.sh
done
EOF

adb install -r "$apk" >/dev/null || fail "couldn't install $apk"
for permission in CAMERA POST_NOTIFICATIONS ACCESS_LOCAL_NETWORK; do
  adb shell pm grant "$package" "android.permission.$permission" || fail "couldn't grant $permission"
done
adb logcat -c
adb logcat -b crash -c

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb exec-out cat /sdcard/ui.xml >"$screen"
}

# The centre of the first element whose text or description is $1, and that
# is enabled if $2 is "enabled".
centre() {
  grep -o "<node [^>]*\(text\|content-desc\)=\"$1\"[^>]*>" "$screen" |
    if [ "${2:-}" = enabled ]; then grep -F ' enabled="true"'; else cat; fi | head -1 |
    sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

alive() {
  # Read into a file first: grep -q stops reading early, and pipefail would
  # then count logcat's broken pipe as no crash.
  adb logcat -b crash -d >"$out/crash.txt" || fail "couldn't read the crash log $1"
  if grep -Fq "$package" "$out/crash.txt"; then
    fail "the app crashed $1: $(grep -m1 -E 'Exception|Error' "$out/crash.txt")"
  fi
  [ -n "$(adb shell pidof "$package" | tr -d '\r')" ] || fail "the app isn't running $1"
}

# Waits up to $2 seconds (default 30) for an element labelled $1 to show, and
# to be enabled if $3 is "enabled".
wait_for() {
  local seconds=${2:-30}
  for _ in $(seq "$seconds"); do
    if dump && [ -n "$(centre "$1" "${3:-}")" ]; then return 0; fi
    sleep 1
  done
  alive "while waiting for \"$1\""
  fail "\"$1\" didn't show${3:+ $3} within $seconds s"
}

# The shutter shows while the camera starts, but ignores taps until it's ready.
tap() {
  wait_for "$1" "${2:-30}" enabled
  # shellcheck disable=SC2046 # The two coordinates are separate arguments.
  adb shell input tap $(centre "$1" enabled)
}

adb shell am start -W -n "$package/.MainActivity" >/dev/null
tap 'Scan code' 60
# The barcode scanner starts with the camera preview behind this hint.
wait_for 'Point the camera at the code glimpse pair shows'
sleep 5
alive 'after opening the pairing code scanner'
adb shell input keyevent KEYCODE_BACK

link=$(curl -fsS -X POST -H "Authorization: Bearer $owner" "$base/v1/pairings" | jq -er .link)
adb shell am start -W -a android.intent.action.VIEW -d "\"$link\"" "$package" >/dev/null
tap 'Pair'
for _ in $(seq 30); do
  [ "$(owner_get /v1/devices | jq length)" = 1 ] && break
  sleep 1
done
[ "$(owner_get /v1/devices | jq length)" = 1 ] || fail "the phone didn't pair"
alive 'after pairing'

tap 'Take photo'
for _ in $(seq 90); do
  owner_get /v1/photos | jq -e '[.photos[] | select(.hasOriginal)] | length == 1' >/dev/null && break
  sleep 1
done
owner_get /v1/photos | jq -e '[.photos[] | select(.hasOriginal)] | length == 1' >/dev/null ||
  fail "the photo didn't reach the server within 90 s"
alive 'after taking a photo'

tap 'Scan'
tap 'Scan document'
# The scanner is Google Play services' own screen, or the app says it isn't
# ready yet; either shows that the code starting it ran. The message goes
# after 4 s, so the loop doesn't sleep between looks.
scanner=
tapped=$SECONDS
deadline=$((SECONDS + 30))
while [ -z "$scanner" ] && [ "$SECONDS" -lt "$deadline" ]; do
  resumed=$(adb shell dumpsys activity activities | grep ResumedActivity || true)
  case $resumed in
    *' com.google.android.gms/'*) scanner=open ;;
    *) if dump && grep -Fq 'text="The document scanner isn' "$screen"; then scanner='not ready'; fi ;;
  esac
done
if [ -z "$scanner" ]; then
  alive 'while waiting for the document scanner'
  fail "neither the document scanner nor the message that it isn't ready showed within 30 s"
fi
echo "document scanner: $scanner"
# A crash in the code that runs once the scanner is up takes a few seconds.
sleep $((tapped + 10 > SECONDS ? tapped + 10 - SECONDS : 0))
alive 'after opening the document scanner'

echo 'release smoke test passed'
