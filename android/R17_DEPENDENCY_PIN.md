# R17 dependency pin

Verified on 2026-08-11 against upstream release documentation.

- libtorrent-rasterbar: **2.1.1** (`v2.1.1`, upstream release commit shown as `56ae8ca`).
  - R17 needs ordinary BitTorrent v2 + Mainline DHT + mutable DHT items.
  - WebTorrent is explicitly disabled in the EidoLang native CMake profile.
- AndroidX WorkManager: **2.11.2** stable.
- Android NDK integration: CMake through Android Gradle Plugin `externalNativeBuild` / NDK toolchain when R17.1 is executed.

The archive intentionally does not vendor the libtorrent upstream source tree. This keeps third-party code and licensing separate and prevents an unverified native binary from being mistaken for an admitted R17 backend.
