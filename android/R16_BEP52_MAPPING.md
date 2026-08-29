# R16 BitTorrent-v2 mapping

## Frozen profile

`piece length = 16384` bytes.

Every archive file becomes one leaf in the v2 `file tree` path hierarchy. Non-empty files
receive a SHA-256 Merkle `pieces root`. For files larger than one piece, the top-level
`piece layers` dictionary maps that pieces root to the concatenated 16KiB leaf hashes.

The `info` dictionary contains:

- `file tree`;
- `meta version = 2`;
- `name`;
- `piece length = 16384`.

The archive's v2 infohash is:

`SHA256(Bencode(info))`.

The R16 magnet is:

`magnet:?xt=urn:btmh:1220<sha256-infohash>&dn=<name>`.

The golden segment deliberately contains one encrypted message file larger than 16KiB so
that `piece layers` are exercised rather than only the single-piece special case.
