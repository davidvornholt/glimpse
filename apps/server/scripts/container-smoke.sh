#!/usr/bin/env bash
# Runs the server image as production does (read-only, no capabilities, an
# arbitrary user, a data directory it can write) and checks that it answers,
# pairs a phone, keeps tokens out of its log, and exits 0 within seconds of a
# stop signal while the phone holds its event stream open. Usage:
# container-smoke.sh [image]; DOCKER=podman runs it with Podman.
set -euo pipefail

docker=${DOCKER:-docker}
image=${1:-glimpse-server:smoke}
name=glimpse-smoke
port=8798
base="http://127.0.0.1:$port"
owner=smoke-owner-token
digest=$(printf %s "$owner" | sha256sum | cut -d' ' -f1)

events=$(mktemp)
events_pid=

stop_events() {
  if [ -n "$events_pid" ]; then
    kill "$events_pid" 2>/dev/null || true
    wait "$events_pid" 2>/dev/null || true
    events_pid=
  fi
}

cleanup() {
  stop_events
  rm -f "$events"
  "$docker" rm --force "$name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$docker" run --detach --name "$name" \
  --read-only \
  --tmpfs=/tmp:rw,noexec,nosuid,nodev,size=16m \
  --tmpfs=/data:rw,noexec,nosuid,nodev,size=64m,mode=1777 \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  --user=2025:2025 \
  --env=GLIMPSE_PUBLIC_URL=https://glimpse.example.com \
  --env="GLIMPSE_OWNER_TOKEN_SHA256=$digest" \
  --publish="127.0.0.1:$port:8787" \
  "$image" >/dev/null

for _ in $(seq 30); do
  if curl -fsS -o /dev/null "$base/healthz" 2>/dev/null; then
    break
  fi
  sleep 1
done
curl -fsS -o /dev/null "$base/healthz"

owner_get() {
  curl -fsS -H "Authorization: Bearer $owner" "$base$1"
}

pairing=$(curl -fsS -X POST -H "Authorization: Bearer $owner" "$base/v1/pairings")
link=$(jq -er .link <<<"$pairing")
case $link in
  'glimpse://pair?server=https%3A%2F%2Fglimpse.example.com&code='*) ;;
  *)
    echo "The pairing link does not point at GLIMPSE_PUBLIC_URL: $link" >&2
    exit 1
    ;;
esac
code=${link##*&code=}

# Claiming writes the paired phone to the data directory.
claim=$(jq -n --arg code "$code" '{code: $code, deviceName: "Smoke"}' |
  curl -fsS -X POST -H 'content-type: application/json' --data @- \
    "$base/v1/pairings/claim")
phone=$(jq -er .token <<<"$claim")
owner_get /v1/devices | jq -e '[.[].name] == ["Smoke"]' >/dev/null
curl -fsS -H "Authorization: Bearer $phone" "$base/v1/me" |
  jq -e '.kind == "device"' >/dev/null

status=$(curl -sS -o /dev/null -w '%{http_code}' \
  -H 'Authorization: Bearer wrong' "$base/v1/photos")
test "$status" = 401

# The phone's event stream stays open until the server ends it, so the stop
# below shows it doesn't hold the server up. Its first event is the count of
# agents waiting.
curl -sSN -H "Authorization: Bearer $phone" "$base/v1/events" \
  >"$events" 2>/dev/null &
events_pid=$!
for _ in $(seq 50); do
  if grep -Fqx 'event: waiting' "$events"; then
    break
  fi
  sleep 0.1
done
if ! grep -Fqx 'event: waiting' "$events"; then
  echo "The event stream sent no first event within 5 s." >&2
  exit 1
fi
# A stream the server has already ended would hold nothing open at the stop.
sleep 1
if ! kill -0 "$events_pid" 2>/dev/null; then
  echo "The event stream ended after its first event instead of staying open." >&2
  exit 1
fi

started=$(date +%s)
"$docker" stop --time 10 "$name" >/dev/null
took=$(($(date +%s) - started))
stop_events
exit_code=$("$docker" inspect --format '{{.State.ExitCode}}' "$name")
if [ "$exit_code" != 0 ] || [ "$took" -gt 6 ]; then
  echo "The server exited $exit_code after $took s instead of 0 within 6 s." >&2
  exit 1
fi

log=$("$docker" logs "$name" 2>&1)
if grep -Fq -e "$owner" -e "$phone" -e "$code" <<<"$log"; then
  echo "The server log holds a token or pairing code." >&2
  exit 1
fi

echo "The image serves Glimpse and stops cleanly."
