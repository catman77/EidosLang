# R20.2 Android history-vault secret storage

`AndroidHistoryVaultSecretStore` stores a local convenience copy of the 256-bit vault recovery
secret under a dedicated AndroidKeyStore AES-256-GCM wrapping key.

Alias:

```text
eidolang.history-vault-secret-wrap.v1
```

The vault ID is AES-GCM AAD, so swapping encrypted secret blobs between two vault IDs fails
authentication.

This key domain is deliberately different from R20.1:

```text
eidolang.forward-session-state-wrap.v1
```

Therefore compromise/substitution of one local encrypted state class is not silently accepted
as another.

The Android store does not replace an external recovery backup. If AndroidKeyStore and the
device are lost together, only an independently preserved recovery secret can unlock a
destructive-recovery archive.

`RecoverySecretExportV1` is a primitive test/admin representation, not the final consumer
backup UX. R21 must add protected passphrase/offline/QR handling rather than asking users to
manage raw secret JSON casually.
