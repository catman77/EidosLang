# R22.1 validation

Every count below was executed during this stage. Nothing is carried over from R22's own report.

## Pure host conformance on the final R22.1 tree — 43 PASS

Compiled with the Android Studio bundled `kotlinc` 2.3.10 (JBR 21) over the 52 `core-*` sources
that carry no `android` import.

- R22 product journey: 11
- R22 fixed cross-stage composition: 5
- R22 Android source audit (`android_source_audit.py`): 13
- independent Python BEP52: 2
- independent Python BEP44: 3
- independent Python R20.1 forward session: 3
- independent Python R20.2 history vault: 3
- independent Python R21 hardening: 3

The R14.1–R21 dynamic regression was NOT re-executed in this stage. R22 reports it as 151 PASS;
that number is inherited, not reproduced here, and is excluded from the total above.

## Real BitTorrent on the host — 16 PASS

`libtorrent-rasterbar 2.1.0` (distro build), strictly loopback, public bootstrap disabled.

- `tools/r17-real-swarm.py`: 6 — v2 infohash agreement, seed, magnet-only metadata (BEP9),
  transfer (BEP52), byte-identical tree, received infohash
- `tools/r17-real-conformance/R17RealAdmissionMain.kt`: 4 — exact `segment_id` from transferred
  bytes, re-derived v2 infohash, post-completion single-bit corruption rejected, restoring the byte
  restores admission
- `tools/r17-real-dht.py`: 6 — target formula, private 8-node routing table, put to >0 nodes,
  retrieval by a different node, byte-identical value, BEP44 size limit

## Android instrumentation — 13 tests

Samsung SM-G996N, Android 15 / SDK 35, arm64-v8a, verified boot `green`.

| test | what it establishes |
|---|---|
| `AndroidAdmissionTest` | `AndroidAdmissionReport.passed = true`, all six checks, over a seeded repository (contacts=1, conversations=1) so `repository.sensitive_text` is not vacuous |
| `KeystoreSecurityLevelTest` | all five key aliases report `TRUSTED_ENVIRONMENT` |
| `SqliteV3MigrationTest` × 4 | v2→v3 and v1→v3 migration on a hand-built legacy database, fail-closed on plaintext, cross-row ciphertext substitution rejected |
| `MessengerSmokeTest` × 2 | first-run primary flow, home navigation, secure archive controls |
| `LibtorrentOnDeviceTest` | two libtorrent sessions on device: seed, magnet-only fetch, 147456 payload bytes, exact `segment_id`, corruption rejected |
| `DhtOnDeviceTest` | private DHT on device, BEP44 put/get of the golden head value, byte-identical |
| `TransportInterfaceOnDeviceTest` × 3 | `seed`/`fetch` through `TorrentTransport` → admission yields the exact `segment_id`; `putHead`/`getHead` through `TorrentTransport` → head verifies under `ConversationHeadCodec.verify`; publisher key survives restart with a stable `publisher_key_id` |

**Message delivery between two devices** ran separately via `tools/messenger-over-torrent.sh`:
Alice on the phone authored an eidogram, archived it into an R16 segment and seeded it; Bob on an
`android-34` emulator fetched the segment over BitTorrent through an `adb forward` bridge, passed
`NetworkAdmissionGate`, admitted the envelope and read back the identical MessageId
(`dc00e6ed…`) and document hash. Bob's storage was wiped by the preceding fresh install, so the
payload can only have arrived over the wire.

The native-heavy suite now also passes on the API 34 emulator (`OK (5 tests)`), which it could not
before the Ed25519 change: `DhtOnDeviceTest` used `KeyPairGenerator("Ed25519")`, unavailable there.

Two-device secondary enrollment ran separately across the phone and an `android-34` emulator via
`tools/two-device-enrollment.sh` — six steps, ending in a cross-device RSA key unwrap and a
history-vault restore that reproduces the identical MessageId and document hash.

## Execution note — two instrumentation invocations

The 13 tests do not share one process. Classes that start libtorrent sessions are marked
`@NativeSessionHeavy` and excluded from the default run via `notAnnotation`:

```
gradle :app:connectedDebugAndroidTest                     -> OK (8 tests)
am instrument -e annotation org.eidolang.app.NativeSessionHeavy -> OK (5 tests)
```

Run as a single suite they sort before `MessengerSmokeTest`, and the instrumentation process dies
partway through with no assertion message — the run reports `Finished 6 tests` out of 13. Every
class passes in isolation, so this is resource release, not a defect: `SessionManager.stop()` does
not drop native threads promptly enough for a Compose UI test to follow in the same process. The
two-invocation arrangement is the fix, mirroring how `@TwoDeviceOnly` is already handled.

`MessengerSmokeTest` additionally needs the device awake and unlocked. With the phone dozing both
cases fail with `No compose hierarchies found in the app`. Confirm
`dumpsys power | grep mWakefulness` reads `Awake` and the keyguard is dismissed before reading a UI
failure as a code defect.

## Not executed

- R14.1–R21 dynamic regression on this tree
- the project's own `eidolang_torrent` JNI, and any build of pinned libtorrent 2.1.1
- R17.1 items 8 and 9: session/DHT state save-restore, process restart, WorkManager
- any transport flow driven from the UI
- any traffic on the public internet
