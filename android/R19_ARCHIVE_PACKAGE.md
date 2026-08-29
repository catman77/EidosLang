# EidoArchivePackageV1

`EidoArchivePackageV1` is a **local recovery container**, not a replacement for R16 torrent
segments.

## Canonical object

```text
EidoArchivePackageV1
├── owner_user_id
├── recovery_device_id
├── recovery_encryption_key_id
├── contacts[]
│   ├── user_id
│   ├── alias
│   └── device_bundle_json[]
├── conversations[]
│   ├── conversation_id
│   ├── title
│   ├── created_at_ms
│   └── descriptor_json
├── segments[]
│   ├── segment_id
│   ├── conversation_id
│   ├── torrent_name
│   ├── piece_length
│   ├── torrent_infohash_v2
│   ├── torrent_metainfo_b64
│   └── files[]
│       ├── path
│       ├── byte_length
│       ├── sha256
│       └── bytes_b64
├── pinned_segment_ids[]
└── package_id
```

`package_id` is not stored in its own hash input:

\[
PackageId=SHA256(CanonicalJSON(Package\setminus\{package\_id\})).
\]

## Security boundary

The package contains public identities and encrypted messages, but **no private signing or
decryption key material**.

The package's device/key binding is an admission requirement, not a secret:

\[
CurrentDeviceId=RecoveryDeviceId
\]

and

\[
CurrentEncryptionKeyId=RecoveryEncryptionKeyId.
\]

## Fixed golden

`golden/r19/archive-package.json` wraps the frozen R16 segment and is used to check canonical
package parsing, embedded R16 verification, and exact torrent metainfo reconstruction.
