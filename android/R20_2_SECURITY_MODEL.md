# R20.2 security model

## Separate compromise domains

R20.2 distinguishes:

1. active R20.1 session state;
2. R15/R20 device private keys;
3. history-vault recovery secret;
4. individual vault-epoch keys.

These are not aliases for one another.

### Session-state compromise

After a verified R20.2 cutover and deletion of legacy R16/R19 copies:

\[
Compromise(CurrentRatchetState)
\nRightarrow
Decrypt(HistoryVaultArchive).
\]

A dynamic control explicitly attempts to use the current R20.1 ratchet root as the history
recovery secret and fails before repository mutation.

### Historical R15 RSA compromise

The R20.2 package does not expose raw historical R15 envelope/key-box material. Therefore an old
R15 RSA private key alone is not sufficient to decrypt the new vault archive.

This statement is false if an undeleted legacy R16/R19 copy remains.

### Recovery-secret compromise

\[
Compromise(HistoryVaultRecoverySecret)
\Rightarrow
Decrypt(AllVaultEpochsWrappedByThatSecret).
\]

This is intentional. Destructive recovery requires an independent recovery capability; the
no-go result means that capability cannot simultaneously be absent and usable.

## Epoch revocation

Every new epoch uses a fresh random epoch key.

A revoked device excluded from epoch \(e+1\) cannot derive \(VEK_{e+1}\) merely from an old
\(VEK_e\).

However no protocol can revoke a key that a device has already learned. Revocation is
prospective.

## History-entry authenticity

The recovered historical envelope is authenticated by its original sender signature.

The recovered document is authenticated by:

- its canonical document hash inside the encrypted entry payload;
- AES-GCM authentication under the vault epoch;
- the exporter device signature over the package body.

The document cannot necessarily be re-derived from the old R15 envelope on a new device whose
historical RSA key access was intentionally removed. Therefore R20.2 explicitly treats the
vault record as the authenticated recovery copy of the previously verified document.

## Metadata

R20.2 encrypts message envelopes and document contents inside vault entries.

The following archive metadata remains visible but package-authenticated:

- owner user ID;
- contact public identities and aliases;
- conversation titles/descriptors;
- vault/epoch IDs;
- active device IDs per vault epoch;
- message IDs and conversation IDs on encrypted entry headers;
- sizes/counts/timing inferable from the archive representation.

R20.2 is not a metadata-anonymity protocol.

## Recovery-secret handling

`RecoverySecretExportV1` is included only as a low-level test/admin representation.

A raw 256-bit secret JSON file is not a consumer-safe backup design. Passphrase wrapping,
offline/QR backup policy, confirmation UX and compromise recovery belong to R21 hardening.

## Android at-rest boundary

The local convenience copy of the recovery secret is wrapped by a distinct AndroidKeyStore
AES-GCM alias:

```text
eidolang.history-vault-secret-wrap.v1
```

It is separate from the R20.1 session-state wrapping alias. Loss of AndroidKeyStore still
requires an external recovery-secret copy for destructive recovery.
