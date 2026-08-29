# R20.2 checkpoint — Forward-Secure History Vault / Archive Migration

Status: CORE/PROTOCOL COMPLETE + DYNAMIC + FIXED GOLDEN + INDEPENDENT PYTHON PASS

Added module:
- `core-vault`

Added objects:
- `EidoHistoryVaultV1`
- `EidoHistoryVaultEpochV1`
- `EidoHistoryVaultEntryV1`
- `EidoHistoryVaultDeviceGrantV1`
- `EidoHistoryVaultArchivePackageV1`
- low-level `EidoHistoryVaultRecoverySecretV1` export representation

Implemented:
- independent 256-bit recovery-secret domain;
- independent random 256-bit vault epoch keys;
- HKDF-SHA-256 recovery wrapping domain;
- AES-256-GCM epoch-key recovery wraps;
- per-message HKDF-derived entry keys;
- encrypted historical R15 envelope + canonical document backup;
- package SHA-256 identity with exporter ECDSA proof outside identity;
- active-device epoch-key grants;
- prospective revocation through fresh random epochs;
- mirror/preflight zero-mutation recovery;
- cross-device destructive recovery without historical R15 RSA access;
- cross-device destructive recovery without R20 historical MessageKeyGrants;
- `HistoricalDocumentProvider` integration into R18/R20 timeline;
- R19 same-device migration;
- R19+R20-capsule migration;
- AndroidKeyStore-separated recovery-secret storage;
- fixed R20.2 golden vector;
- independent Python package/ECDH-independent vault crypto/RSA-grant cross-check.

Critical cutover condition:
R20.2 archive confidentiality applies only after legacy R16/R19 copies are deleted.

Executed final pure checks:
- R20.2 dynamic: 12 PASS
- R20.2 fixed golden: 6 PASS
- independent Python R20.2: 3 PASS
- R14.1–R20.1 regression: 110 PASS

Total executed PASS lines: 131.

Not executed:
- Android Gradle build;
- AndroidKeyStore history-vault runtime;
- consumer recovery-secret backup UX;
- real BitTorrent/DHT networking.

Next:
R21 — Security / Fuzzing / Local Hardening.
