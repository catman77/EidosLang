# R20.2 independent recovery domain

## Recovery hierarchy

The vault deliberately does not derive its recovery secret from:

- user root signing key;
- device RSA private key;
- R20.1 ratchet root;
- current chain key;
- message key.

Instead:

\[
RecoverySecret \xrightarrow{HKDF} RecoveryWrapKey
\]

and every epoch has independently random:

\[
VEK_e.
\]

Then:

\[
RecoveryWrapKey \xrightarrow{AES-GCM} VEK_e
\]

and:

\[
VEK_e \xrightarrow{HKDF(MessageId,EpochId)} EntryKey.
\]

This direction is one-way from recovery authority to archived history. Active session state
contains no derivation path back to the recovery secret.

## Device grants are not the recovery root

An active-device epoch grant wraps one particular `VEK_e` to a particular active R20 device.

It does not reveal the recovery secret.

Consequently:

- a device may have ordinary access to epoch \(e\) without gaining universal vault recovery
  authority;
- a recovery-secret holder can reconstruct all epochs;
- revocation can exclude a device from newly random epochs.

## Recovery-secret rotation

The current v1 protocol freezes one recovery-key identifier across one vault descriptor.
Changing the recovery root cleanly should be treated as a new vault/re-encryption operation,
not as an in-place mutation hidden under the same identity.

Automated recovery-secret rotation is therefore intentionally deferred rather than specified
without a complete migration/revocation proof.
