# Android eidogram messenger roadmap after R22

## R14–R21 — protocol/core complete

Completed source/core layers:
- deterministic eidogram documents/editor;
- user/device identity;
- encrypted/signed immutable message DAG;
- archive/torrent object format;
- local messenger/repository;
- multi-device authorization/revocation;
- forward-session ratchet;
- independent history-vault recovery;
- legacy migration/cutover;
- recovery backup and rollback hardening.

## R22 — product source integration complete

Added:
- first-launch primary/secondary onboarding;
- device enrollment/authorization/revocation UI;
- secure-vault/recovery UI;
- explicit fresh-restore freshness warning;
- encrypted recovery backup/code UX;
- verified legacy cutover workflow;
- SQLite v3 alias/title field protection;
- Android runtime diagnostics;
- Android platform-backup/cleartext hardening.

Pure/product validation: 180 executed PASS lines including the complete prior regression.

## R22.1 — Android device admission — NEXT

No new protocol design should occur unless an actual Android counterexample forces it.

Required:

1. provide Android SDK + pinned Gradle wrapper/runtime;
2. `:app:assembleDebug`;
3. `:app:connectedDebugAndroidTest`;
4. install on a clean primary device/emulator;
5. run Diagnostics → `ADMISSION PASS`;
6. exercise first-run secondary enrollment between two Android installs;
7. exercise SQLite v2→v3 migration on a populated legacy database;
8. export/restore secure vault with recovery backup/code;
9. verify fresh restore displays `FRESHNESS UNPROVEN`;
10. verify device revocation and fresh vault epoch;
11. verify legacy cutover rejects the exact retired R19 artifact.

## Final network admission

Real R17 BitTorrent/DHT execution remains intentionally last and separate.

After local two-device Android admission succeeds, connect the transport adapter and require:

\[
TwoAndroidDevices
\land SameMessageDAG
\land ForwardSession
\land RecoverableHistoryVault
\land VerifiedCutover
\land NoApplicationServer.
\]


## R22.1 — Android device admission — DONE

1. Android SDK + Gradle 9.5.0 supplied; `:app:assembleDebug` succeeds after five build fixes.
2. `:app:connectedDebugAndroidTest` runs on a physical device: 13 tests.
3. Diagnostics admission probe: `ADMISSION PASS`, six checks, over a seeded repository.
4. First-run secondary enrollment exercised between a phone and an emulator, ending in a
   cross-device key unwrap and a history-vault restore with an identical MessageId.
5. SQLite v2→v3 migration exercised on a hand-built populated legacy database.
6. Secure vault export/restore with recovery backup and code.
7. Fresh restore displays `FRESHNESS UNPROVEN`; device revocation forces a fresh vault epoch;
   legacy cutover rejects the exact retired R19 artifact.

## R22.2 — transport in the product — NEXT

A message now travels between two devices over BitTorrent end to end
(`tools/messenger-over-torrent.sh`), but it is driven from tests. Nothing in the UI calls the
transport, and head discovery still moves as a file because the two devices share no DHT.

Required:

1. wire publish/fetch into the archive flow;
2. decide whether `native-torrent` is revived against pinned libtorrent 2.1.1 or retired in favour
   of the prebuilt binding;
3. R17.1 item 8 — save and restore session/DHT state, repeat lookup;
4. R17.1 item 9 — process restart and WorkManager scheduling;
5. first run over the public network, between two devices that are not on loopback.

## Final network admission

Unchanged. After the product drives the transport itself, require:

\[
TwoAndroidDevices
\land SameMessageDAG
\land ForwardSession
\land RecoverableHistoryVault
\land VerifiedCutover
\land NoApplicationServer.
\]
