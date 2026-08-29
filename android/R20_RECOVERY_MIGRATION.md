# R20 cross-device recovery migration

R19 deliberately required:

\[
RecoveryDeviceId_{package}=CurrentDeviceId.
\]

R20 keeps R19 packages unchanged and adds a separate migration capsule.

## Capsule contents

`EidoMultiDeviceRecoveryCapsuleV1` v1.1 contains:

- source `PackageId`;
- owner user ID;
- target device/encryption-key ID;
- all public owner-device bundles needed by recovery;
- latest root-signed rosters for the owner and archived contacts;
- historical message-key grants;
- `CapsuleId`.

It contains no private keys.

## Recovery ordering

1. canonical-parse R19 package and R20 capsule;
2. require matching `PackageId`;
3. require target certificate belongs to package owner;
4. verify owner roster and target is currently active;
5. verify every R16 segment;
6. replay the whole operation into in-memory repository/archive mirrors;
7. import public identities and conversation descriptors;
8. restore historical messages:
   - direct R15 recipient box when available;
   - otherwise a verified R20 key grant;
9. only after historical reconstruction install current roster/revocation state;
10. persist immutable R16 segment blobs and pin state;
11. apply the same validated operation to the real repository.

Hence an incomplete migration capsule fails before mutation.

## Result

The second authorized device reconstructs the same:

- message IDs;
- message DAG;
- current heads;
- decrypted `EidogramDocumentV1` content hashes;
- R16 archive segment identities.

It can then continue the same DAG as an independent sender.


## Subsequent backups from a migrated device

A migrated device may possess old message keys only through R20 grants, not through recipient
boxes embedded in the immutable R15 envelopes. Therefore future backups from that device must
export the R20 companion capsule together with the unchanged R19 package.

The capsule carries the persisted grants, own-device bundles and current participant rosters.
A bare R19 package is intentionally insufficient for those legacy messages; the R20 pair is
the complete multi-device recovery object.
