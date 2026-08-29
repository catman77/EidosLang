# R20.2 — Forward-Secure History Vault / Archive Migration

## Goal

R20.1 proved that three requirements cannot be simultaneously satisfied by one secret domain:

\[
ForwardSecrecy \land CiphertextOnlyArchive \land DestructiveRecovery.
\]

R20.2 introduces the necessary independent recovery domain instead of weakening the
forward-session ratchet.

The separation is:

\[
ActiveSessionState \perp HistoryVaultRecoverySecret.
\]

The archive must remain recoverable from the vault recovery domain while compromise of current
R20.1 ratchet state does not reveal historical vault plaintext.

## 1. Vault identity

`EidoHistoryVaultV1` is identified by:

\[
VaultId = SHA256(CanonicalJSON(ownerUserId, randomSeed)).
\]

The 128-bit seed prevents two vaults for the same user from sharing an identity accidentally.

## 2. Recovery secret

Each vault has a random 256-bit `HistoryVaultRecoverySecret`.

It is not a message key, device key, ratchet key, or user root key.

Its identifier is:

\[
RecoveryKeyId = \text{"hvr:"} \Vert SHA256(RecoverySecret).
\]

The archive stores only this identifier, never the recovery secret.

## 3. Independent random vault epochs

Every vault epoch gets a fresh random 256-bit epoch key:

\[
VEK_e \leftarrow_R \{0,1\}^{256}.
\]

Epoch keys are not derived from previous epoch keys. Therefore revocation can be prospective:
a revoked device that does not receive the new random epoch key cannot derive it from an old
epoch key.

The epoch chain contains:

- vault ID;
- integer epoch;
- previous epoch ID;
- sorted active device IDs;
- recovery key ID;
- recovery-wrap nonce/ciphertext.

The recovery wrapping key is derived using HKDF-SHA-256 from the independent recovery secret,
then AES-256-GCM wraps `VEK_e`.

## 4. History entries

A history-vault entry is bound to exactly one immutable R15/R20 message ID and conversation.

Its encrypted payload contains:

- the canonical historical `EidogramMessageV1`;
- the canonical recovered `EidogramDocumentV1`;
- the document content hash.

This entire payload is encrypted. Consequently the new archive surface does not publish the
old R15 recipient key boxes or message envelope bytes.

Each entry has an independently derived AES key:

\[
K_{entry} =
HKDF(VEK_e,\ salt=MessageId,\ info=VaultId\Vert EpochId).
\]

AES-GCM AAD binds:

\[
(VaultId,Epoch,EpochId,MessageId,ConversationId).
\]

## 5. Why both envelope and document are backed up

After destructive recovery on a new device, the new device may intentionally possess neither:

- the historical sender/recipient R15 RSA content-key access;
- an R20 historical message-key grant.

The immutable old envelope is still required to reconstruct and verify:

- MessageId;
- sender signature;
- sender sequence;
- parent DAG edges;
- conversation binding.

The document is backed up under the independent vault domain so that history remains readable
without reintroducing the old RSA recovery dependency.

The document↔envelope pair is authenticated at archive creation by the already verified local
timeline and then protected by vault AEAD plus the exporter device signature. The old R15
envelope signature is verified independently during restore.

## 6. Package identity and proof stability

`EidoHistoryVaultArchivePackageV1` contains public presentation/identity metadata, vault epochs
and encrypted history entries.

\[
PackageId = SHA256(CanonicalJSON(PackageBody)).
\]

The exporter ECDSA signature is outside `PackageId`.

Therefore randomized ECDSA proof bytes may change without changing the logical package
identity, following the same proof/identity separation established earlier for messages and
device certificates.

## 7. Recovery

Recovery performs all parsing, package verification, epoch unwrapping, entry decryption,
message-signature verification, document hash validation and DAG validation in an isolated
in-memory repository first.

Only if the complete mirror succeeds are mutations applied to the actual repository.

Thus:

\[
InvalidVaultPackage \Rightarrow ZeroRepositoryMutation.
\]

A new authorized device can reconstruct the same message DAG with:

\[
VaultPackage + RecoverySecret + CurrentOwnerIdentity
\]

without the old device's RSA private key and without R20 historical message-key grants.

## 8. Device access and revocation

The recovery secret is the ultimate recovery domain.

For normal active-device access to a current vault epoch, `EidoHistoryVaultDeviceGrantV1`
wraps the random epoch key to an active same-user device's R20 RSA public key and authenticates
the grant with an active device signing key.

A device must belong to the epoch's active device set.

Revocation creates a new epoch with a fresh random key and excludes revoked devices. This is
prospective revocation; knowledge already obtained for an old epoch cannot be revoked.

## 9. Legacy migration

Migration from R19 is:

\[
R19Package
\rightarrow VerifiedLegacyRestore
\rightarrow VerifiedTimeline
\rightarrow HistoryVaultEntries.
\]

R20 second-device migration can analogously use the existing R20 recovery capsule first.

Migration is not complete until the old archive representation has been deleted:

\[
ForwardSecureCutover =
VerifiedMigration \land Delete(LegacyR16/R19Copies).
\]

Keeping a legacy copy retains the legacy RSA compromise path by definition.

## 10. Non-goals / claim boundary

R20.2 does not claim:

- that compromise of the recovery secret leaves history confidential;
- retroactive confidentiality for undeleted legacy archive copies;
- physical zeroization guarantees inside the Java VM;
- post-quantum security;
- consumer-ready recovery-secret backup UX;
- Android runtime validation in this artifact environment;
- real BitTorrent execution.

The recovery secret is deliberately a separate high-value secret. Compromising it compromises
the vault archive by design.
