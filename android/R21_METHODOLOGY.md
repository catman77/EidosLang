# R21 — Security / Fuzzing / Local Hardening

## Scope

R21 does not add a new messaging primitive. It closes concrete obligations left by R20.1/R20.2.

The necessity chain is:

1. R20.2 recovery requires an independent high-value recovery secret.
2. Raw-secret JSON export therefore creates an unnecessary single-file compromise path.
3. Signed archive authenticity does not imply freshness on a fresh offline device.
4. A locally continuous device can prevent rollback by anchoring previously accepted immutable facts.
5. The legacy-cutover security claim is meaningful only if migration equivalence is verified and the legacy artifact is thereafter retired.
6. R20.1 already bounded skipped-message keys; the outstanding one-time-prekey pool also requires a finite resource bound.
7. Draft plaintext remained on disk despite message/archive cryptographic hardening.

R21 introduces only entities forced by those obligations.

## 1. Two-channel recovery backup

`EidoHistoryVaultRecoveryBackupV1` contains an AES-256-GCM ciphertext of the R20.2 recovery secret.

The AES key is a separately generated random 256-bit `RecoveryBackupCode`.

Therefore:

\[
BackupFile \not\Rightarrow RecoverySecret
\]

and:

\[
RecoveryCode \not\Rightarrow RecoverySecret
\]

without the matching backup file.

The code is explicitly a secret second channel, suitable for later QR/offline presentation.

No password-derived KDF is frozen in R21. Choosing PBKDF/Argon2 parameters without a measured product policy would introduce an arbitrary security parameter. Password UX/KDF policy remains a product-security decision for the final Android admission stage.

## 2. Archive freshness no-go

A valid signed old package and a valid signed latest package are both cryptographically valid.

On a fresh offline device with no trusted monotonic state and no external freshness oracle:

\[
Signed(P_{old}) \land Signed(P_{latest})
\]

does not provide a predicate that identifies which one is latest.

R21 therefore does not pretend otherwise.

Initial offline recovery is labelled:

`BOOTSTRAP_FRESHNESS_UNPROVEN`.

## 3. Local rollback anchor

After a package has been accepted on a continuously trusted installation, R21 stores a rollback anchor containing:

- accepted vault epoch ID prefix;
- immutable `MessageId -> EntryId` bindings;
- known own/contact device IDs;
- latest known roster epoch/ID per user;
- last accepted package ID.

A candidate must be a monotonic extension.

The guard rejects:

- removed history;
- changed entry binding under the same VaultId;
- vault epoch fork;
- old epoch prefix rollback;
- disappearing known identities;
- roster epoch rollback;
- roster fork at an already anchored epoch.

This solves local continuity rollback, not fresh-device global freshness.

## 4. Legacy cutover evidence

`EidoLegacyArchiveCutoverV1` is issued only after source and recovered histories have the same deterministic digest over:

\[
(ConversationId, MessageId, Parents, DocumentContentHash).
\]

The signed receipt binds:

- VaultId;
- vault PackageId;
- history digest;
- SHA-256 identifiers of legacy artifacts declared retired.

The local policy rejects exact committed legacy artifacts after cutover.

The receipt proves that the application verified equivalence before cutover. It cannot prove that an arbitrary external copy was physically deleted.

## 5. Resource hardening

R20.1 already limits skipped ratchet keys.

R21 adds the missing finite bound to outstanding one-time prekeys:

`maxPendingOneTimePreKeys`.

The default value is an operational implementation policy, not a wire-format constant. The security requirement is finiteness and fail-closed admission at the configured bound.

## 6. Local draft protection

Pre-R21 `DraftStore` wrote canonical `EidogramDocumentV1` plaintext to app-private storage.

R21 changes it to `AndroidLocalSecretBox`:

- AES-256-GCM;
- AndroidKeyStore key;
- namespace + logical object ID as AAD;
- atomic encrypted write.

A pre-R21 plaintext draft is parsed first, then encrypted and the plaintext file must be deleted before migration is considered successful.

## 7. Adversarial validation

R21 includes direct controls for:

- wrong recovery code;
- backup ciphertext tamper with recomputed object ID;
- fresh-device stale-valid archive counterexample;
- local rollback;
- vault epoch fork;
- cross-vault entry substitution;
- entry-header substitution;
- signed truncated archive with missing DAG parent;
- skipped-key DoS;
- one-time-prekey pool DoS;
- legacy cutover equivalence;
- deterministic mutation corpus.

The mutation corpus changes authenticated canonical archive/backup bytes and requires rejection.

## Claim boundary

R21 does not claim:

- global freshness on a fresh offline device;
- proof that external legacy copies were deleted;
- physical Java-heap zeroization;
- encrypted full SQLite page-level metadata;
- post-quantum cryptography;
- Android runtime validation in the current artifact environment;
- real network execution.
