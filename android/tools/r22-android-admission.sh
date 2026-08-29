#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "== EidoLang R22 Android admission =="

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "ERROR: ANDROID_HOME/ANDROID_SDK_ROOT is not configured." >&2
  exit 20
fi

if [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "ERROR: neither gradle-wrapper.jar nor system gradle is available." >&2
  echo "Generate the pinned wrapper with: gradle wrapper --gradle-version 9.5.0" >&2
  exit 21
fi

echo "-- compile/installable APK boundary"
"${GRADLE[@]}" --no-daemon :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
sha256sum "$APK"

if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  echo "-- connected Android instrumentation"
  "${GRADLE[@]}" --no-daemon :app:connectedDebugAndroidTest
  echo "-- install debug APK"
  adb install -r "$APK"
  echo
  echo "Open EidoLang → Диагностика → Запустить admission probe."
  echo "Required result: ADMISSION PASS for identity.bundle, identity.sign,"
  echo "identity.rsa_oaep, local.secret_box, vault.secret_store and repository.sensitive_text."
else
  echo "No adb device/emulator detected; build admission completed, runtime admission pending."
fi
