#!/usr/bin/env bash
# R20/R22 secondary-device enrollment across two independent Android installs.
#
# The protocol is file-based by design ("all flows above are executable through local files",
# R22_PRODUCT_WORKFLOW.md), so this driver alternates devices and carries each exported artifact
# between them. Artifacts live in app-private storage and move via `run-as`, because
# /sdcard/Android/data/<pkg> is not reliably reachable over adb on Android 11+.
#
# Usage:
#   PRIMARY=<serial> SECONDARY=<serial> tools/two-device-enrollment.sh [--install]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PKG=org.eidolang.app
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS=org.eidolang.app.TwoDeviceEnrollmentTest
APK=app/build/outputs/apk/debug/app-debug.apk
TAPK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

: "${PRIMARY:?set PRIMARY=<adb serial>}"
: "${SECONDARY:?set SECONDARY=<adb serial>}"
[[ "$PRIMARY" != "$SECONDARY" ]] || { echo "PRIMARY and SECONDARY must differ" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

banner() { printf '\n== %s ==\n' "$*"; }

# USB re-enumeration mid-run is common with physical devices; wait rather than abort.
wait_device() { timeout 120 adb -s "$1" wait-for-device || { echo "device $1 did not come back" >&2; exit 1; }; }

retry() {                               # retry <attempts> <cmd...>
  local n=$1; shift
  local i
  for ((i = 1; i <= n; i++)); do
    if "$@"; then return 0; fi
    echo "   retry $i/$n: $*" >&2
    sleep 3
  done
  return 1
}

fresh_install() {
  local d=$1
  banner "installing on $d"
  wait_device "$d"
  # Uninstall first: a secondary install must not inherit AndroidKeyStore aliases or files.
  adb -s "$d" uninstall "$PKG" >/dev/null 2>&1 || true
  adb -s "$d" uninstall "$PKG.test" >/dev/null 2>&1 || true
  # Streamed install occasionally fails on a USB blip right after uninstall; retry rather than abort.
  retry 3 adb -s "$d" install -r -t "$APK" >/dev/null
  retry 3 adb -s "$d" install -r -t "$TAPK" >/dev/null
}

_pull_once() { adb -s "$1" exec-out run-as "$PKG" cat "files/exchange/$2" > "$WORK/$2"; }

pull_artifact() {                       # pull_artifact <device> <name>
  wait_device "$1"
  retry 3 _pull_once "$1" "$2"
  [[ -s "$WORK/$2" ]] || { echo "empty artifact $2 from $1" >&2; exit 1; }
  echo "   pulled $2 ($(wc -c < "$WORK/$2") bytes) from $1"
}

push_artifact() {                       # push_artifact <device> <name>
  wait_device "$1"
  adb -s "$1" shell run-as "$PKG" mkdir -p files/exchange
  base64 -w0 "$WORK/$2" | adb -s "$1" shell "run-as $PKG sh -c 'base64 -d > files/exchange/$2'"
  echo "   pushed $2 to $1"
}

run_step() {                            # run_step <device> <method>
  local d=$1 m=$2 out
  banner "$m on $d"
  wait_device "$d"
  local attempt
  for attempt in 1 2 3; do
    wait_device "$d"
    adb -s "$d" logcat -c >/dev/null 2>&1 || true
    # `|| true`: a USB drop makes adb exit non-zero, and under `set -e` a bare command
    # substitution would abort the whole run with no diagnostics at all.
    out="$(adb -s "$d" shell am instrument -w -e class "$CLASS#$m" "$RUNNER" 2>&1 | tr -d '\r' || true)"
    # Must be exactly one executed test. A misspelled method yields "Time: 0" + "OK (0 tests)",
    # which any looser match reports as a pass.
    if echo "$out" | grep -qE '^OK \(1 test\)$'; then
      adb -s "$d" logcat -d 2>/dev/null | grep -a 'ENROLL ' | sed 's/.*System.out: /   /' || true
      echo "   PASS $m"
      return 0
    fi
    if echo "$out" | grep -qE 'FAILURES!!!|^Error|Exception'; then
      break                                   # a real test failure: do not retry
    fi
    echo "   transport glitch on $d, retry $attempt/3 of $m" >&2
    sleep 5
  done
  echo "$out" | tail -30 >&2
  echo "   FAIL $m" >&2
  exit 1
}

if [[ "${1:-}" == "--install" ]]; then
  [[ -f "$APK" && -f "$TAPK" ]] || { echo "build first: gradle :app:assembleDebug :app:assembleDebugAndroidTest" >&2; exit 2; }
  fresh_install "$PRIMARY"
  fresh_install "$SECONDARY"
fi

# 1. primary creates its user root and epoch-0 roster, exports the owner public identity
run_step "$PRIMARY" step1PrimaryCreatesRootAndExportsOwnerIdentity
pull_artifact "$PRIMARY" owner-identity.json
push_artifact "$SECONDARY" owner-identity.json

# 2. secondary generates local device keys and a possession-signed enrollment request
run_step "$SECONDARY" step2SecondaryCreatesEnrollmentRequest
pull_artifact "$SECONDARY" enrollment-request.json
push_artifact "$PRIMARY" enrollment-request.json

# 3. primary root-authorizes the device and issues the successor roster
run_step "$PRIMARY" step3PrimaryAuthorizesSecondaryAndIssuesRoster
for f in authorized-bundle.json roster.json wrapped-key.b64 expected-secret.sha256; do
  pull_artifact "$PRIMARY" "$f"
  push_artifact "$SECONDARY" "$f"
done

# 4. secondary installs the authorization and proves possession of the wrapped key
run_step "$SECONDARY" step4SecondaryInstallsAuthorizationAndProvesPossession

# 5. primary authors an eidogram and exports the secure history vault + two-channel recovery
run_step "$PRIMARY" step5PrimaryAuthorsEidogramAndExportsSecureVault
for f in vault-package.json recovery-backup.json recovery-code.txt \
         conversation-id.txt expected-message-id.txt expected-document.sha256; do
  pull_artifact "$PRIMARY" "$f"
  push_artifact "$SECONDARY" "$f"
done

# 6. secondary restores the vault and must read back the identical MessageId and document
run_step "$SECONDARY" step6SecondaryRestoresVaultAndReadsTheSameEidogram

banner "two-device enrollment complete"
echo "primary=$PRIMARY secondary=$SECONDARY"
