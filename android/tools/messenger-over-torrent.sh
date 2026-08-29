#!/usr/bin/env bash
# Deliver a message between two devices with the payload travelling over BitTorrent.
#
# Alice (device A) authors an eidogram, archives it into an R16 conversation segment and seeds it.
# Bob (device B) fetches the segment over a real BitTorrent connection, admits it and reads the
# eidogram back.
#
# The two devices share no DHT, so the small control plane (identity bundles, rosters, conversation
# descriptor, publisher certificate, signed head) is carried between them as files, exactly as
# R22_PRODUCT_WORKFLOW.md allows. The message payload is not: it crosses the wire.
#
# Data path: B is an emulator and reaches the host at 10.0.2.2, while `adb forward` publishes A's
# listening port on the host. B therefore dials 10.0.2.2:<forwarded port> and lands on A.
#
# Usage:
#   ALICE=<serial> BOB=<emulator-serial> tools/messenger-over-torrent.sh [--install]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PKG=org.eidolang.app
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS=org.eidolang.app.MessengerOverTorrentTest
APK=app/build/outputs/apk/debug/app-debug.apk
TAPK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

ALICE_PORT=6881          # in-device listen port on Alice
HOST_PORT=16881          # host port that forwards to it
BOB_DIALS=10.0.2.2       # how an emulator reaches the host

: "${ALICE:?set ALICE=<adb serial>}"
: "${BOB:?set BOB=<adb serial>}"
[[ "$ALICE" != "$BOB" ]] || { echo "ALICE and BOB must differ" >&2; exit 2; }

WORK="$(mktemp -d)"
SEEDER_PID=""
cleanup() {
  [[ -n "$SEEDER_PID" ]] && kill "$SEEDER_PID" 2>/dev/null || true
  adb -s "$ALICE" forward --remove "tcp:$HOST_PORT" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

banner() { printf '\n== %s ==\n' "$*"; }
wait_device() { timeout 120 adb -s "$1" wait-for-device || { echo "device $1 did not come back" >&2; exit 1; }; }

retry() { local n=$1; shift; local i; for ((i=1;i<=n;i++)); do "$@" && return 0; sleep 3; done; return 1; }

fresh_install() {
  local d=$1
  banner "installing on $d"
  wait_device "$d"
  adb -s "$d" uninstall "$PKG" >/dev/null 2>&1 || true
  adb -s "$d" uninstall "$PKG.test" >/dev/null 2>&1 || true
  retry 3 adb -s "$d" install -r -t "$APK" >/dev/null
  retry 3 adb -s "$d" install -r -t "$TAPK" >/dev/null
}

pull_artifact() {                       # <device> <remote name> [local name]
  local local_name="${3:-$2}"
  wait_device "$1"
  retry 3 sh -c "adb -s '$1' exec-out run-as '$PKG' cat 'files/exchange/$2' > '$WORK/$local_name'"
  [[ -s "$WORK/$local_name" ]] || { echo "empty artifact $2 from $1" >&2; exit 1; }
  echo "   pulled $local_name ($(wc -c < "$WORK/$local_name") bytes)"
}

push_artifact() {                       # <device> <name>
  wait_device "$1"
  adb -s "$1" shell run-as "$PKG" mkdir -p files/exchange
  base64 -w0 "$WORK/$2" | adb -s "$1" shell "run-as $PKG sh -c 'base64 -d > files/exchange/$2'"
  echo "   pushed $2"
}

run_step() {                            # <device> <method>
  local d=$1 m=$2 out attempt
  banner "$m on $d"
  for attempt in 1 2 3; do
    wait_device "$d"
    adb -s "$d" logcat -c >/dev/null 2>&1 || true
    out="$(adb -s "$d" shell am instrument -w -e class "$CLASS#$m" "$RUNNER" 2>&1 | tr -d '\r' || true)"
    if echo "$out" | grep -qE '^OK \(1 test\)$'; then
      adb -s "$d" logcat -d 2>/dev/null | grep -a 'MSG ' | sed 's/.*System.out: /   /' || true
      echo "   PASS $m"
      return 0
    fi
    echo "$out" | grep -qE 'FAILURES!!!|^Error|Exception' && break
    echo "   transport glitch, retry $attempt/3" >&2
    sleep 5
  done
  echo "$out" | tail -30 >&2
  echo "   FAIL $m" >&2
  exit 1
}

if [[ "${1:-}" == "--install" ]]; then
  [[ -f "$APK" && -f "$TAPK" ]] || { echo "build first" >&2; exit 2; }
  fresh_install "$ALICE"
  fresh_install "$BOB"
fi

# 1. both sides mint an identity and export it
run_step "$ALICE" step1IdentityAndExport
pull_artifact "$ALICE" identity-self.json identity-alice.json
ALICE_USER=$(adb -s "$ALICE" exec-out run-as "$PKG" ls -1 files/exchange | tr -d '\r' | grep '^roster-' | head -1)
pull_artifact "$ALICE" "$ALICE_USER" roster-alice.json

run_step "$BOB" step1IdentityAndExport
pull_artifact "$BOB" identity-self.json identity-bob.json
BOB_USER=$(adb -s "$BOB" exec-out run-as "$PKG" ls -1 files/exchange | tr -d '\r' | grep '^roster-' | head -1)
pull_artifact "$BOB" "$BOB_USER" roster-bob.json

banner "exchanging public identities"
push_artifact "$ALICE" identity-bob.json
push_artifact "$ALICE" roster-bob.json
push_artifact "$BOB" identity-alice.json
push_artifact "$BOB" roster-alice.json

# 2. Alice sends and keeps seeding; the step blocks, so run it in the background
banner "step2AliceSendsAndSeeds on $ALICE (background seeder)"
adb -s "$ALICE" logcat -c >/dev/null 2>&1 || true
adb -s "$ALICE" shell am instrument -w -e class "$CLASS#step2AliceSendsAndSeeds" "$RUNNER" \
  > "$WORK/seeder.log" 2>&1 &
SEEDER_PID=$!

echo -n "   waiting for the swarm to come up"
for _ in $(seq 1 60); do
  if adb -s "$ALICE" logcat -d 2>/dev/null | grep -aq "MSG step2 SEEDING"; then echo " ok"; break; fi
  echo -n "."; sleep 2
done
adb -s "$ALICE" logcat -d 2>/dev/null | grep -a 'MSG step2' | sed 's/.*System.out: /   /' || true
adb -s "$ALICE" logcat -d 2>/dev/null | grep -aq "MSG step2 SEEDING" || {
  echo "seeder never reported SEEDING" >&2; tail -30 "$WORK/seeder.log" >&2; exit 1; }

for f in conversation.json publisher-cert.json head.bencode.b64 head-target.txt \
         infohash.txt expected-message-id.txt expected-document.sha256; do
  pull_artifact "$ALICE" "$f"
  push_artifact "$BOB" "$f"
done

# 3. bridge the data path and tell Bob where to dial
banner "bridging $ALICE:$ALICE_PORT -> host:$HOST_PORT -> $BOB via $BOB_DIALS"
adb -s "$ALICE" forward --remove "tcp:$HOST_PORT" 2>/dev/null || true
adb -s "$ALICE" forward "tcp:$HOST_PORT" "tcp:$ALICE_PORT"
printf '%s:%s' "$BOB_DIALS" "$HOST_PORT" > "$WORK/alice-endpoint.txt"
push_artifact "$BOB" alice-endpoint.txt

# 4. Bob pulls the segment over BitTorrent and reads the eidogram
run_step "$BOB" step3BobFetchesAndReads

banner "message delivered over BitTorrent"
echo "alice=$ALICE bob=$BOB"
