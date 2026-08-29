# EidoLang Android R18

Android eidogram messenger source tree.

## Frozen eidographic content
- `EidoGlyphCatalogV1`: 113 glyphs
- glyph catalog hash: `da2d3b72ff8d41abb8c293ce1deca44e99cb1d1a696e9834997395c6f6218005`
- production grammar hash: `d5afb09e6ecdcff8a4b2c9758af83f215059d1b5bc4ad32da3fb8dd191b69b19`

## Current stack
- R14/R14.1: canonical eidogram editor
- R15: root/device identities and encrypted signed message envelopes
- R16: immutable torrent archive format and mutable heads
- R17: transport abstraction/admission and native torrent scaffold
- **R18: local messenger repository and UX**

## R18 modules
- `core-repository` — contacts, conversations, encrypted message DAG, SQLite adapter
- `feature-viewer` — read-only eidogram renderer
- `feature-messenger` — contacts/conversations/timeline/composer flows

`app` now starts `EidoMessengerApp`, not the standalone editor.

## Manual no-network interoperability
R18 can be exercised on two devices by exporting/importing the exact public identity, conversation descriptor and encrypted message envelope files. See `R18_MANUAL_EXCHANGE.md`.

## Validation
Pure Kotlin/JCA/archive regressions on the final R18 tree total 49 PASS lines. Android SDK/emulator execution is not claimed. See `R18_CHECKPOINT.md` and `R18_VALIDATION.md`.

## Build pins inherited from R14
- compileSdk / targetSdk: 37
- AGP: 9.3.0
- Gradle target: 9.5.0
- Kotlin / Compose compiler plugin: 2.3.21
- Compose BOM: 2026.06.00
- minSdk: 26
- Java target: 17

## R19 — Archive / Recovery

R19 adds incremental R16 segment creation from the local messenger repository, persistent
archive indexing/pinning, a complete canonical recovery package, an archive browser, and
same-device destructive recovery. Private keys are not exported. See `R19_METHODOLOGY.md` and
`R19_RECOVERY_PROTOCOL.md`.


## R20 — Multi-device

R20 adds root-authorized secondary devices, signed monotone device rosters, prospective
revocation, active-device recipient fan-out, deterministic cross-device DAG convergence,
historical message-key grants, and migration of an R19 archive onto a second authorized
device without changing existing MessageIds.

See `R20_METHODOLOGY.md`, `R20_DEVICE_AUTHORIZATION.md`,
`R20_RECOVERY_MIGRATION.md`, and `R20_ANDROID_ONBOARDING.md`.


## R20.1 — Forward-Secrecy Session Layer

R20.1 adds a signed asynchronous prekey bootstrap, P-256/HKDF/AES-GCM DH ratchet, bounded
out-of-order handling, transactional receive rollback and AndroidKeyStore-encrypted session/
prekey state.

The existing R16/R19 archive is intentionally not claimed forward-secret. The resulting
archive/recovery impossibility is documented in `R20_1_ARCHIVE_NO_GO.md` and forces R20.2.

## R20.2 — Forward-Secure History Vault

R20.2 resolves the R20.1 archive/recovery no-go by introducing an independent 256-bit recovery
secret and independently random vault-epoch keys.

Historical R15 envelopes and canonical documents are encrypted together inside history-vault
entries. A second authorized device can reconstruct the same immutable message DAG without the
old R15 RSA private key or R20 historical message-key grants.

The confidentiality cutover is complete only after verified migration and deletion of all
legacy R16/R19 archive copies.



## R21 — Security / Fuzzing / Local Hardening

R21 adds a two-channel encrypted recovery backup, local archive rollback anchors, a formal
fresh-offline freshness no-go, verified legacy cutover receipts, bounded outstanding prekeys,
deterministic mutation tests and AndroidKeyStore-encrypted draft persistence.

The next stage is R22 Android/product admission. Real torrent networking remains isolated and
non-blocking until the application stack passes device/runtime checks.


## R22 — Product Completion / Android Admission

R22 integrates the existing R14–R21 protocol stack into explicit Android product flows:
primary/secondary onboarding, device authorization/revocation, secure history-vault
export/recovery, recovery backup/code handling, freshness warnings, legacy cutover and Android
runtime diagnostics.

SQLite schema v3 encrypts contact aliases and conversation titles with the local AndroidKeyStore
secret-box domain. Android platform auto-backup and cleartext traffic are disabled.

The source/product layer is complete under pure validation. Actual Android compilation and
KeyStore/device admission remain R22.1 because the current artifact environment contains no
Android SDK/Gradle runtime.


## R22.1 — Android device admission and real transport

R22.1 executes the boundary R22 declared. The tree is built for the first time (five blockers had
to be cleared), the app runs on a physical Samsung SM-G996N under Android 15, the runtime admission
probe reports `ADMISSION PASS` for all six checks, and every private key alias reports
`TRUSTED_ENVIRONMENT`.

Two defects were found only by executing: the secure-restore button bound a function reference
instead of a call, and SQLite v3 sensitive-text protection could never store a row because the
logical id violated the secret-box contract.

The messenger also gains the transport it never had. `transport-libtorrent4j` implements
`TorrentTransport` over the prebuilt `org.libtorrent4j` binding of libtorrent 2.x, and the product
interface is proven on device: `seed`/`fetch` feed `NetworkAdmissionGate` the exact R16
`segment_id`, and `putHead`/`getHead` round-trip a conversation head that verifies under the
project's own signature rules.

The project's own `native-torrent` JNI and the pinned libtorrent 2.1.1 build remain unbuilt, and no
screen calls the transport yet. See `R22_1_METHODOLOGY.md` and `R22_1_VALIDATION.md`.
