# Enable native libtorrent backend

R17 pins **libtorrent 2.1.1**. The source is intentionally not vendored into this archive.

1. Obtain the upstream `v2.1.1` source into `native-torrent/third_party/libtorrent`.
2. Build with Android NDK/CMake via `externalNativeBuild`; use the NDK toolchain supplied by AGP.
3. Keep WebTorrent disabled (`webtorrent=OFF`); EidoLang needs BEP5/BEP44/BEP52, not WebRTC/WebTorrent.
4. Run the R17 Android-NDK admission suite before enabling the backend in release builds.

The C++ source currently covers session creation, torrent-file/magnet add, alert polling and mutable DHT GET. Mutable DHT PUT and session-state persistence deliberately fail closed until NDK conformance is executed.
