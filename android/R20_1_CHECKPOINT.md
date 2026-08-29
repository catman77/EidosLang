# R20.1 checkpoint — Forward-Secrecy Session Layer

Status: CORE SESSION PROTOCOL COMPLETE + PURE-KOTLIN + FIXED GOLDEN + INDEPENDENT PYTHON PASS

Added module:
- `core-session`

Public/session objects:
- `EidoDevicePreKeyBundleV1`
- `EidoInitialSessionPacketV1`
- `EidoRatchetPacketV1`

Secret local objects:
- `EidoPreKeySecretStateV1`
- `EidoForwardSessionSecretStateV1`

Implemented:
- P-256 ECDH;
- RFC5869 HKDF-SHA-256;
- HMAC-SHA-256 symmetric chains;
- AES-256-GCM packet encryption;
- signed recipient prekey bundles;
- one-time prekey consumption;
- signed asynchronous initial packet;
- fresh-DH/root ratchet;
- bounded out-of-order skipped keys;
- used/skipped message-key erasure;
- transactional receive rollback after authentication failure;
- canonical encrypted session-state persistence;
- canonical encrypted prekey-state persistence;
- AndroidKeyStore AES-GCM secret blob adapter with AAD name binding;
- `ForwardSecureMessengerBridge` around canonical R15/R20 messages;
- fixed R20.1 golden vector;
- independent Python ECDH/HKDF/AES-GCM cross-check.

Executed:
- R20.1 dynamic: 11 PASS
- R20.1 fixed golden: 6 PASS
- independent Python R20.1: 3 PASS
- full R14.1–R20 regression: 90 PASS

Total executed PASS lines: 110.

Not executed:
- Android Gradle build;
- AndroidKeyStore secret-store runtime;
- Compose/UI session onboarding;
- real network transport.

Critical result:
R20.1 does NOT make current R16/R19 archives forward-secret. `R20_1_ARCHIVE_NO_GO.md` proves
that ciphertext-only destructive recovery and erased historical session keys require a
separate recovery/history-vault secret domain.

Next:
R20.2 — Forward-Secure History Vault / Archive Migration.
