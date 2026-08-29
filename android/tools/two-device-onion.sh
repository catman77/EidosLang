#!/usr/bin/env bash
# Delivery of an eidogram from one device to another over Tor onion, end to end.
#
#   PRIMARY=<serial> SECONDARY=<serial> tools/two-device-onion.sh [--install]
#
# The only things that cross between the devices are the two contact cards. The recipient is told no
# head target, no infohash and no torrent name — it derives all of them, which is the difference
# between proving delivery and proving that a file can be copied.
#
# The sender's step blocks while it serves, because an onion service lives only as long as its
# process. So it runs in the background and the recipient runs underneath it.
set -uo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=org.eidolang.app
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CLASS=org.eidolang.app.TwoDeviceOnionDeliveryTest
APK_DIR="$(dirname "$0")/../app/build/outputs/apk"

: "${PRIMARY:?PRIMARY=<serial> required}"
: "${SECONDARY:?SECONDARY=<serial> required}"

# Physical USB re-enumerates mid-run: the serial survives, the transport id does not. Every device
# operation waits for the transport rather than assuming it is there.
dev() { "$ADB" -s "$1" wait-for-device; }

# Installs over the top; it does NOT uninstall first. An uninstall takes the device's identity,
# contacts, messages and saved eidograms with it — and takes the onion service key too, which is the
# one thing this whole line of work exists to keep. Both devices here are ordinary primaries, so
# there is no stale secondary state to clear. If a device ever does need clearing, do it deliberately
# and by hand, not as a side effect of running a test.
install_on() {
  dev "$1"
  "$ADB" -s "$1" install -r -t "$APK_DIR/debug/app-debug.apk" >/dev/null
  "$ADB" -s "$1" install -r -t "$APK_DIR/androidTest/debug/app-debug-androidTest.apk" >/dev/null
}

# `am instrument` reports "OK (0 tests)" for a misspelled method, so the pass check demands exactly
# one test. A typo must fail, not silently succeed.
run_step() {
  local serial=$1 method=$2 out
  dev "$serial"
  out="$("$ADB" -s "$serial" shell am instrument -w -r \
        -e class "$CLASS#$method" \
        -e annotation org.eidolang.app.TwoDeviceOnly "$RUNNER" 2>&1)" || true
  if grep -qE '^OK \(1 test\)$' <<<"$out"; then return 0; fi
  echo "--- $method on $serial FAILED ---"
  grep -E 'stack=|Error in|Assertion' <<<"$out" | head -5
  return 1
}

# /sdcard is not reliably reachable over adb on Android 11+, Samsung especially. run-as + base64 is.
pull_file() { dev "$1"; "$ADB" -s "$1" shell run-as $PKG base64 "files/exchange/$2" | tr -d '\r\n' | base64 -d; }
push_file() {
  dev "$1"
  base64 -w0 "$3" | "$ADB" -s "$1" shell "run-as $PKG sh -c 'mkdir -p files/exchange && base64 -d > files/exchange/$2'"
}

if [ "${1:-}" = "--install" ]; then
  echo "== installing"; install_on "$PRIMARY"; install_on "$SECONDARY"
fi

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "== step 1: both devices publish their own card (Tor bootstrap, may take minutes)"
run_step "$PRIMARY"   step1PublishOwnCard || exit 1
run_step "$SECONDARY" step1PublishOwnCard || exit 1

pull_file "$PRIMARY"   card-self.json > "$TMP/card-a.json" || exit 1
pull_file "$SECONDARY" card-self.json > "$TMP/card-b.json" || exit 1
grep -oE '[a-z2-7]{56}\.onion' "$TMP/card-a.json" | head -1 | sed 's/^/   sender  /'
grep -oE '[a-z2-7]{56}\.onion' "$TMP/card-b.json" | head -1 | sed 's/^/   recipient /'

push_file "$PRIMARY"   card-peer.json "$TMP/card-b.json"
push_file "$SECONDARY" card-peer.json "$TMP/card-a.json"

echo "== step 2: sender publishes and serves (background)"
( run_step "$PRIMARY" step2SendAndServe; echo $? > "$TMP/sender.rc" ) &
SENDER=$!
sleep 30   # let Tor come up and the service descriptor upload before the recipient asks

echo "== step 3: recipient fetches over the sender's onion"
run_step "$SECONDARY" step3ReceiveOverOnion; RECV=$?

wait $SENDER
SEND=$(cat "$TMP/sender.rc" 2>/dev/null || echo 1)
[ "$RECV" -eq 0 ] || { echo "FAIL: recipient step failed"; exit 1; }
[ "$SEND" -eq 0 ] || { echo "FAIL: sender step failed (nobody reached it?)"; exit 1; }

pull_file "$PRIMARY"   expected.txt > "$TMP/expected.txt" || exit 1
pull_file "$SECONDARY" received.txt > "$TMP/received.txt" || exit 1

if diff -q "$TMP/expected.txt" "$TMP/received.txt" >/dev/null; then
  echo "PASS: the eidogram arrived over onion unchanged"
  sed 's/^/   /' "$TMP/received.txt"
else
  echo "FAIL: what arrived is not what was sent"
  diff "$TMP/expected.txt" "$TMP/received.txt"
  exit 1
fi
