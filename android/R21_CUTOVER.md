# R21 verified legacy cutover

R20.2 confidentiality is conditional on retiring old R16/R19 copies.

R21 turns the local part of that operation into a checkable transaction.

## Equivalence digest

Before cutover, source and recovered repositories are reduced to sorted rows:

```text
conversation_id
message_id
sorted parent_message_ids
document_content_hash
```

The canonical row array is SHA-256 hashed.

Cutover is refused unless source and recovered rows and hashes are exactly equal.

## Receipt

`EidoLegacyArchiveCutoverV1` binds:

- owner user;
- signer device/signing key;
- VaultId;
- R20.2 PackageId;
- history digest;
- sorted `(kind, SHA-256)` legacy-artifact references.

The receipt is signed by the current device signing key.

## Local enforcement

After a receipt is committed, the application can reject exact legacy artifact hashes from being re-imported through the old recovery path.

## Limitation

A software receipt cannot demonstrate deletion of an unknown external copy.

Therefore the strongest supported claim remains:

\[
LocalCutoverVerified
\]

not:

\[
AllCopiesEverywhereDeleted.
\]
