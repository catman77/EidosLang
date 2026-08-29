# R17 checkpoint

Status: SOURCE COMPLETE + REFERENCE TWO-NODE ADMISSION PASS + NATIVE NETWORK UNRESOLVED

Added:
- `core-transport`
- `feature-sync`
- `native-torrent`

Verified in pure Kotlin/JCA:
- node A seed + head publication;
- node B fetch + full R16 admission;
- non-monotonic mutable head rejected;
- corrupted downloaded segment rejected after transport success;
- malformed head rejected.

Native backend:
- libtorrent pin: 2.1.1
- session/torrent/magnet/alert/DHT-GET JNI source present
- BEP44 native PUT intentionally fail-closed pending NDK/device conformance
- Android SDK/NDK unavailable in artifact environment

Next: R17.1 native admission on an Android SDK/NDK environment, then R18 local repository + messenger UX.
