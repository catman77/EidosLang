# R20.2 archive migration and cutover

## Why R16/R19 cannot simply be retained as the new archive

R16/R19 contains recoverable R15 message envelopes. Those envelopes contain content-key boxes
for long-lived R15 RSA device keys.

Therefore retaining those packages preserves:

\[
LegacyArchive + HistoricalRSAKey \Rightarrow HistoricalPlaintext.
\]

Wrapping a second format around the same undeleted legacy object does not remove this path.

## Migration procedure

1. Verify/restore the legacy R19 package using the historical recovery mechanism.
2. Reconstruct the authenticated local message DAG and documents.
3. Create an independent history-vault recovery secret.
4. Create vault epoch 0 from the current active owner-device roster when available.
5. Seal each historical `(MessageEnvelope, Document)` pair into an encrypted vault entry.
6. Build and verify `EidoHistoryVaultArchivePackageV1`.
7. Perform destructive recovery from the new package into an isolated repository.
8. Compare recovered message IDs, DAG heads and document content hashes against the source.
9. Only after all checks pass, delete all old R16/R19 archive copies and any exported R20
   migration capsules/message-key-grant backup material no longer required by policy.

## Migration helpers

`LegacyArchiveVaultMigrator.migrateR19SameDevice(...)`

restores a same-device R19 package and emits:

- R20.2 vault package;
- low-level recovery-secret export;
- VaultId;
- migrated message count.

`LegacyArchiveVaultMigrator.migrateR19WithR20Capsule(...)`

first uses the R20 cross-device recovery path, then seals the resulting verified history into
R20.2.

Optional current own-device bundles and roster snapshots may be injected during migration so
authorization state introduced after an older R19 snapshot is not lost.

## Cutover invariant

The security transition is:

\[
LegacyPresent \rightarrow MigrationVerified \rightarrow LegacyDeleted.
\]

Only the final state is the R20.2 archive-security state.

The software can verify migration equivalence but cannot prove that an arbitrary external copy
of an old archive was deleted. Operational deletion therefore remains a user/storage-system
obligation.
