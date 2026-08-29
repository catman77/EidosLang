# R22 product workflow

## Ordinary primary-user path

1. First run: choose primary profile.
2. App creates AndroidKeyStore root/device keys.
3. App creates explicit root-signed epoch-0 device roster.
4. Export public contact identity.
5. Import contacts.
6. Create/import conversation descriptors.
7. Author/send eidograms.
8. Use secure vault export for durable recovery.
9. Create encrypted recovery backup and preserve its separate recovery code offline.

## Adding another device

Primary:

1. receive `EidoDeviceEnrollmentRequestV1`;
2. open Devices;
3. authorize request;
4. export device bundle;
5. export roster.

Secondary:

1. choose secondary on first launch;
2. import owner public identity;
3. export request;
4. import authorized bundle;
5. import roster;
6. enter same user identity.

## Secure recovery

Inputs:

```text
HistoryVaultArchivePackage
RecoveryBackup
RecoveryCode
CurrentAuthorizedDeviceIdentity
```

Fresh-device result:

- package authenticity is checked;
- backup/code must match the same VaultId;
- history reconstructs through the independent recovery domain;
- if there is no trusted rollback anchor, UI states `FRESHNESS UNPROVEN`;
- explicit confirmation is required before such a restore is accepted;
- accepted package becomes the local checkpoint.

## Legacy transition

R22 UI supports same-device R19 migration:

```text
R19
→ verify old recovery
→ construct R20.2 vault
→ restore new vault in isolation
→ compare history digests
→ sign cutover receipt
→ store rollback anchor
→ create recovery backup
→ user deletes external R19 copy
```

After a cutover receipt is stored, exact retired R19 bytes are rejected by the legacy import UI.

## Network

All flows above are executable through local files.

The real R17 BitTorrent/DHT transport remains a replaceable transport adapter and is not a
blocker for R22 product/state validation.
