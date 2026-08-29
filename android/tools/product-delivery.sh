#!/usr/bin/env bash
# Deliver a message between two devices over the open internet, through the product code path.
#
# The driver carries only the contact card, which people exchange out of band by design. Head
# discovery goes through the public DHT and the payload through a real swarm: no adb bridge, no
# explicit peer, no loopback.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
PKG=org.eidolang.app
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS=org.eidolang.app.ProductDeliveryTest
: "${SENDER:?set SENDER=<serial>}"; : "${RECEIVER:?set RECEIVER=<serial>}"
WORK="$(mktemp -d)"; SEEDER=""
cleanup() { [[ -n "$SEEDER" ]] && kill "$SEEDER" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT
banner() { printf '\n== %s ==\n' "$*"; }
wait_device() { timeout 120 adb -s "$1" wait-for-device; }
pull() { wait_device "$1"; adb -s "$1" exec-out run-as "$PKG" cat "files/exchange/$2" > "$WORK/$3"; [[ -s "$WORK/$3" ]]; }
push() { wait_device "$1"; adb -s "$1" shell run-as "$PKG" mkdir -p files/exchange
         base64 -w0 "$WORK/$2" | adb -s "$1" shell "run-as $PKG sh -c 'base64 -d > files/exchange/$3'"; }
step() { local d=$1 m=$2 out
  banner "$m on $d"; wait_device "$d"; adb -s "$d" logcat -c >/dev/null 2>&1 || true
  out="$(adb -s "$d" shell am instrument -w -e class "$CLASS#$m" "$RUNNER" 2>&1 | tr -d '\r' || true)"
  if echo "$out" | grep -qE '^OK \(1 test\)$'; then
    adb -s "$d" logcat -d 2>/dev/null | grep -a 'PROD ' | sed 's/.*System.out: /   /' || true
    echo "   PASS $m"
  else echo "$out" | tail -25 >&2; echo "   FAIL $m" >&2; exit 1; fi; }

step "$SENDER" step1ExportCard;   pull "$SENDER" card-self.json card-sender.json;   pull "$SENDER" roster-self.json roster-sender.json
step "$RECEIVER" step1ExportCard; pull "$RECEIVER" card-self.json card-receiver.json; pull "$RECEIVER" roster-self.json roster-receiver.json
banner "exchanging contact cards"
push "$SENDER" card-receiver.json card-peer.json;   push "$SENDER" roster-receiver.json roster-peer.json
push "$RECEIVER" card-sender.json card-peer.json;   push "$RECEIVER" roster-sender.json roster-peer.json

banner "step2SendToPeer on $SENDER (keeps seeding)"
adb -s "$SENDER" logcat -c >/dev/null 2>&1 || true
adb -s "$SENDER" shell am instrument -w -e class "$CLASS#step2SendToPeer" "$RUNNER" > "$WORK/seed.log" 2>&1 &
SEEDER=$!
echo -n "   waiting for publish"
for _ in $(seq 1 90); do
  adb -s "$SENDER" logcat -d 2>/dev/null | grep -aq "PROD step2 outcome" && { echo " ok"; break; }
  echo -n "."; sleep 2
done
adb -s "$SENDER" logcat -d 2>/dev/null | grep -a 'PROD step2' | sed 's/.*System.out: /   /' || true
adb -s "$SENDER" logcat -d 2>/dev/null | grep -aq "PROD step2 outcome=отправлено" || {
  echo "sender never published" >&2; tail -20 "$WORK/seed.log" >&2; exit 1; }
for f in expected-message-id.txt expected-document.sha256; do pull "$SENDER" "$f" "$f"; push "$RECEIVER" "$f" "$f"; done

step "$RECEIVER" step3ReceiveFromPeer
banner "delivered over the open internet"
