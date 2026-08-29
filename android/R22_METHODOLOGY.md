# R22 — Product Completion / Android Admission Source Integration

## Scope

R22 does not introduce a new wire cryptographic protocol.

R14–R21 already define the document, message, multi-device, forward-session, history-vault and
hardening layers. R22 closes the product integration gaps that prevented those layers from
forming a coherent Android application.

The R22 rule is therefore:

\[
NewWireEntity = 0
\]

unless an Android/runtime counterexample proves one necessary.

## 1. First-run identity boundary

Pre-R22 the application called:

`AndroidKeystoreIdentityStore.ensure()`

immediately at startup.

That silently created a new user root on every fresh installation and made the already-defined
R20 secondary-device enrollment protocol impossible to reach from product UI.

R22 replaces this with an explicit first-run choice:

- create a primary user/root identity;
- join an existing user identity as a secondary device.

`AndroidKeystoreIdentityStore.loadIfPresent()` is non-creating.

A secondary installation:

1. imports an existing owner's public identity bundle;
2. generates its private device keys locally;
3. exports the existing `EidoDeviceEnrollmentRequestV1`;
4. imports a root-authorized device bundle;
5. imports a root-signed current device roster;
6. only then enters the messenger.

No user-root private key is transferred.

## 2. Primary-device device management

The Devices screen now exposes the R20 protocol without adding a new approval format.

A primary device can:

- import an enrollment request;
- verify possession and root-authorize the new device;
- add the device certificate to known own devices;
- issue the next root-signed roster;
- export the authorized device bundle;
- export the roster;
- revoke a non-current device by issuing a fresh successor roster.

Secondary devices can import newer rosters but cannot act as root authority.

## 3. Secure archive product path

The pre-R22 Archive screen exposed primarily the legacy R16/R19 representation.

R22 integrates the already-proved R20.2/R21 path:

- create/update a local `EidoHistoryVaultArchivePackageV1`;
- persist its independent recovery secret in the R20.2 Android vault-secret store;
- create an `EidoHistoryVaultRecoveryBackupV1`;
- expose the recovery code as a separate secret channel;
- restore from `(vault package, encrypted backup, recovery code)`;
- persist rollback anchors;
- require explicit confirmation when freshness is unprovable;
- migrate R19 to the secure vault;
- create and persist a signed cutover receipt;
- reject exact legacy artifacts already committed as retired.

The legacy R19 UI remains only for compatibility/migration.

## 4. Freshness UX

R21 proved:

\[
FreshOfflineDevice + SignedArchive + NoTrustedAnchor + NoOracle
\]

cannot prove that an archive is the latest valid archive.

R22 makes this a user-visible state instead of hiding it:

`FRESHNESS UNPROVEN`.

A fresh restore is not silently promoted to “latest”.

If the user explicitly confirms recovery, the newly accepted package becomes the local rollback
anchor. Later old/forked packages are rejected relative to that checkpoint.

## 5. SQLite v3 sensitive text migration

R21 left contact aliases and conversation titles as plaintext SQLite fields.

R22 introduces `RepositoryTextProtector`.

On Android the implementation is backed by `AndroidLocalSecretBox` and therefore by the
existing AndroidKeyStore AES-GCM local domain.

Database version advances:

\[
v2 \rightarrow v3.
\]

During migration:

- every contact alias is encrypted in place;
- every conversation title is encrypted in place;
- reads refuse rows that remain plaintext after v3 migration;
- aliases are sorted in memory after decryption.

The encrypted fields are bound by AAD to their logical row IDs.

R22 does not claim full page-level SQLite confidentiality. IDs, timestamps, graph metadata and
other indexing data remain visible inside app-private SQLite storage.

## 6. Draft/local state

The R21 encrypted DraftStore remains the product path.

R22 additionally disables Android platform auto-backup for the application, because silent
platform restoration of only part of the explicitly separated KeyStore/recovery domains would
violate the controlled recovery model.

Cleartext network traffic is also disabled in the application manifest.

## 7. Runtime admission probe

R22 adds `core-admission`.

`AndroidAdmissionProbe` is intended to execute on the actual installed Android device and checks:

- root/device certificate chain;
- device ECDSA sign/verify;
- device RSA-OAEP wrap/unwrap;
- AndroidKeyStore local AES-GCM secret box;
- vault-secret local store;
- SQLite v3 alias/title protection.

The result is exposed through the Diagnostics screen.

Pure-Kotlin tests cannot substitute for this runtime admission.

## 8. Product-level conformance

The R22 JVM product journey composes existing protocol layers in one flow:

\[
Primary
\rightarrow Message
\rightarrow SecondaryEnrollment
\rightarrow SecureVault
\rightarrow RecoveryBackup
\rightarrow SecondaryRestore
\rightarrow ContinueDAG
\rightarrow Revocation
\rightarrow NewVaultEpoch
\rightarrow VerifiedCutover.
\]

No network transport is required for this acceptance path.

## Claim boundary

R22 does not claim:

- Android compilation in the current artifact environment;
- actual AndroidKeyStore runtime success before the on-device probe runs;
- global archive freshness on a fresh offline device;
- proof that an external legacy copy was deleted;
- full SQLite page-level encryption;
- real BitTorrent/DHT execution.

Those limitations are explicit, not hidden behind the source-complete status.
