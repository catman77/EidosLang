#!/usr/bin/env bash
# Pin the certificate of the published R22.1 APK. A runner's debug key must never replace it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${1:?Usage: verify-apk-signature.sh <apk>}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
APKSIGNER="${APKSIGNER:-${SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_HOME}/build-tools/37.0.0/apksigner}"
EXPECTED="$(tr -d '[:space:]' < "$ROOT/signing-certificate.sha256")"
REPORT="$("$APKSIGNER" verify --verbose --print-certs "$1")"
ACTUAL="$(printf '%s\n' "$REPORT" | sed -n 's/^.*Signer #[0-9]* certificate SHA-256 digest: //p; s/^V[0-9]* Signer: certificate SHA-256 digest: //p')"
if [[ "$ACTUAL" != "$EXPECTED" ]]; then
    echo "APK signing certificate differs from the published app; refusing to publish." >&2
    echo "Expected: $EXPECTED" >&2
    echo "Actual: $ACTUAL" >&2
    exit 1
fi
printf 'APK signature verified: %s\n' "$ACTUAL"
