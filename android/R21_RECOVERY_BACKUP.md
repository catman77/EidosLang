# R21 recovery-secret backup

R20.2 requires a 256-bit `HistoryVaultRecoverySecret`.

R21 stops treating the raw secret JSON as the normal backup representation.

The new pair is:

```text
EidoHistoryVaultRecoveryBackupV1   # encrypted file
RecoveryBackupCode                 # separate 256-bit secret channel
```

The backup key is random and independent of:

- R20.1 session state;
- R15/R20 device keys;
- vault recovery secret;
- vault epoch keys.

AES-GCM AAD binds:

- VaultId;
- RecoveryKeyId;
- BackupKeyId;
- backup protocol type/version.

The encrypted backup file contains no raw recovery-secret bytes.

A wrong backup code fails before a recovery secret object is returned.

The current recovery-code textual representation is a low-level QR/offline transport encoding, not a recommendation to store the code in the same directory as the encrypted backup file.
