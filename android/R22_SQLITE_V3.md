# R22 SQLite v3 sensitive-text migration

## Previous state

R18/R20 SQLite tables intentionally stored cryptographic message envelopes, but two
human-readable presentation fields remained plaintext:

- `contacts.alias`;
- `conversations.title`.

## New abstraction

`RepositoryTextProtector`:

```text
protect(namespace, logicalId, plaintext)
unprotect(namespace, logicalId, stored)
isProtected(stored)
```

The Android implementation is:

`AndroidRepositoryTextProtector`.

It uses the existing R21 `AndroidLocalSecretBox`.

Stored representation begins with:

```text
enc1:
```

followed by the authenticated local secret-box encoding.

## AAD domains

Contact alias:

```text
namespace = contact-alias
logicalId  = SHA256(userId + "|" + deviceId)   hex
```

Conversation title:

```text
namespace = conversation-title
logicalId  = SHA256(conversationId)            hex
```

The logical id is hashed because `AndroidLocalSecretBox` restricts it to `[A-Za-z0-9._-]` --- `|`
is its AAD delimiter --- while identities are formatted `dev:<hex>`. R22 passed the raw
`userId|deviceId` pair and a raw `conversationId`, so every insert threw and the feature could not
store a single row (corrected in R22.1). Hashing keeps the ciphertext bound to its logical row.

Cross-row ciphertext substitution therefore fails authentication.

## Migration

`DB_VERSION = 3`.

For every v2 row:

1. read plaintext alias/title;
2. encrypt it with row-specific AAD;
3. overwrite the same field;
4. continue only after all rows are migrated.

R22 reads v3 rows fail closed if a protected field unexpectedly remains plaintext.

Contact sorting is moved from SQL alias ordering to in-memory ordering after decryption.

## Remaining metadata

R22 does not claim full database encryption.

The following remain indexed/plain in SQLite by design:

- user IDs;
- device IDs;
- conversation IDs;
- message IDs;
- sender IDs;
- sender sequences;
- timestamps;
- parent DAG IDs;
- local state flags.

Message document content is still inside encrypted message envelopes/history-vault records.

A future full-page encryption layer would require a separately admitted Android database
dependency/runtime, not an untested source-only substitution.
