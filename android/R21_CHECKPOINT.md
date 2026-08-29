# R21 checkpoint — Security / Fuzzing / Local Hardening

Status: PURE CORE HARDENING COMPLETE; ANDROID SOURCE HARDENING ADDED; NETWORK STILL NON-BLOCKING

Added module:
- `core-hardening`

Added:
- `EidoHistoryVaultRecoveryBackupV1`;
- separate random 256-bit recovery backup code;
- `EidoVaultRollbackAnchorV1`;
- fresh-device freshness no-go classification;
- `EidoLegacyArchiveCutoverV1`;
- deterministic history-equivalence digest;
- local exact legacy-artifact block after committed cutover;
- bounded outstanding one-time prekey pool;
- AndroidKeyStore local secret box;
- encrypted DraftStore with plaintext migration;
- encrypted local rollback-anchor store;
- encrypted local cutover-receipt store;
- deterministic adversarial mutation corpus;
- fixed R21 golden;
- independent Python backup/anchor verification.

New R21 checks:
- dynamic: 11 PASS
- fixed golden: 6 PASS
- independent Python: 3 PASS

Prior R14.1–R20.2 regression rerun: 131 PASS.

Total executed PASS lines: 151.

Not executed:
- Android Gradle build/runtime;
- AndroidKeyStore draft/anchor runtime;
- full SQLite at-rest encryption;
- real BitTorrent/DHT networking.

Next:
R22 — Product completion / Android admission, with the fresh-device freshness UX and remaining SQLite metadata boundary explicitly visible rather than hidden.
