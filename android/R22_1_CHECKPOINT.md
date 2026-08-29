# R22.1 checkpoint — Android device admission and real transport

Status:

`ANDROID BUILD + DEVICE ADMISSION PASS + REAL TRANSPORT THROUGH THE PRODUCT INTERFACE / OWN JNI STILL UNBUILT`

No new wire cryptographic entity was introduced.

## Added

Modules:
- `transport-libtorrent4j` — `Libtorrent4jTransport`, `AndroidArchivePublisherStore`

Build:
- migration to AGP built-in Kotlin across all 23 module scripts
- `native-torrent` namespace corrected off the `native` Java keyword
- missing module dependencies declared: `core-vault → core-archive`,
  `feature-archive → core-multidevice`

Defects fixed:
- `ArchiveScreen` secure-restore button bound a function reference instead of a call
- SQLite v3 sensitive-text `logicalId` violated the secret-box contract, so no contact or
  conversation could ever be stored

Product:
- `JcaArchivePublisher` generalised to `DevicePrivateCrypto`, gains `seed()` and `restore()`
- publisher key persisted sealed, so the BEP44 target is stable across restarts

Tooling:
- `tools/two-device-enrollment.sh`, `tools/r17-real-swarm.py`, `tools/r17-real-dht.py`,
  `tools/r17-real-conformance/R17RealAdmissionMain.kt`

## Executed

- pure host conformance on this tree: 43 PASS
- real BitTorrent/DHT on the host: 16 PASS
- Android instrumentation: 13 tests
- two-device enrollment phone ↔ emulator: 6 steps

## Not executed

See `R22_1_VALIDATION.md`. In short: the R14.1–R21 dynamic regression, the project's own JNI, R17.1
items 8 and 9, any UI-driven transport flow, and anything on the public internet.

## Next

`R22.2 — transport in the product`.

The transport now exists and is proven through `TorrentTransport`, but no screen calls it. The next
stage wires publish/fetch into the archive flow, decides whether `native-torrent` is revived or
retired, and takes R17.1 items 8 and 9.
