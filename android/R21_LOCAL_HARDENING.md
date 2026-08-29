# R21 local hardening

## Encrypted drafts

`DraftStore` no longer writes a canonical eidogram plaintext JSON file.

It now uses `AndroidLocalSecretBox`:

```text
AES-256-GCM
AndroidKeyStore alias:
eidolang.local-sensitive-wrap.v1
```

AAD:

```text
EIDOLANG-R21-LOCAL | namespace | logical-id
```

This prevents a valid ciphertext for one local object from being substituted as another.

The old `.json` draft is migrated only after canonical parsing; migration then writes the encrypted replacement and requires plaintext-file deletion.

## Rollback anchors and cutover receipts

The same local secret-box primitive protects:

- rollback-anchor files;
- legacy-cutover receipt files.

They are not confidentiality-critical in the same way as drafts, but authenticated local storage prevents accidental/cross-file substitution.

## Remaining SQLite boundary

The existing SQLite repository already stores message payloads as encrypted R15 envelopes, but contact aliases, conversation titles and some traffic metadata remain plaintext SQLite fields.

R21 does not claim page-level database encryption.

Closing that boundary cleanly requires either:

- a vetted encrypted SQLite backend, or
- a deliberate field-encryption schema/migration with corresponding search/sort semantics.

No dependency is added without an Android build/runtime validation path.
