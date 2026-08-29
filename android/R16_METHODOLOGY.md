# R16 — Torrent Archive Model

## Goal

R16 turns authenticated encrypted `EidogramMessageV1` DAG nodes into a deterministic,
immutable BitTorrent-v2 archive object and defines a compact mutable DHT pointer to the newest
published archive state.

R16 deliberately does **not** implement network I/O. The output is a protocol object that a
BitTorrent/DHT engine can publish and retrieve in R17.

## 1. Immutable archive boundary

A `ConversationSegmentV1` contains:

- canonical `conversation.json`;
- canonical public identity bundles required to authenticate the archived senders;
- canonical encrypted `messages/<message_id>.json` files;
- canonical `segment-manifest.json`.

The archive contains ciphertext message envelopes, not plaintext eidograms.

## 2. Segment identity

The manifest body is canonical JSON and records:

- `conversation_id`;
- sorted `previous_segment_ids`;
- sorted external parent message IDs;
- current message-DAG head IDs;
- identity-file hashes/lengths/paths;
- message-file hashes/lengths/paths and signed message metadata.

The segment identifier is independent of BitTorrent metadata:

\[
SegmentId = SHA256(CanonicalJSON(SegmentManifestBody)).
\]

This creates a stable application-level archive identity before transport.

## 3. Verification on recovery

A downloaded segment is not trusted because BitTorrent delivered it. R16 replays a full
verification chain:

1. strict canonical segment-manifest parse;
2. segment-ID recomputation;
3. strict conversation descriptor parse;
4. per-file SHA-256 and byte-length checks;
5. public identity bundle verification;
6. `EidogramMessageV1` canonical parse;
7. sender device certificate + message signature verification;
8. parent closure check against local or explicitly external parents;
9. message-DAG head recomputation;
10. BitTorrent-v2 metainfo/infohash recomputation.

## 4. Frozen R16 BitTorrent-v2 profile

R16 intentionally freezes a single minimal torrent profile:

- `meta version = 2`;
- piece length = exactly 16 KiB;
- SHA-256 Merkle roots over 16 KiB blocks;
- v2 file tree;
- `piece layers` only for files larger than 16 KiB;
- no tracker requirement in the archive model;
- v2 infohash = SHA-256 of the bencoded `info` dictionary;
- magnet exact topic = `urn:btmh:1220<64-hex-infohash>`.

No adaptive piece-size heuristic is introduced in R16. If a later measurement forces a
change, that is a separate protocol version.

## 5. Mutable conversation head

The mutable DHT item does **not** contain archive data. It contains only a compact pointer:

- raw 32-byte conversation ID;
- publisher device ID and archive publisher key ID;
- latest segment ID;
- latest v2 torrent infohash;
- current message-DAG head IDs;
- format version.

The value is bencoded and constrained to <=1000 bytes.

## 6. BEP44 publisher key binding

BEP44 mutable values use Ed25519. R15 device certificates do not contain an Ed25519 DHT key,
and R16 does not mutate R15 identity semantics retroactively.

Instead R16 introduces:

`ArchivePublisherCertificateV1`

which binds a raw Ed25519 DHT public key to an already-authorized R15 user/device identity.
The binding is signed by the R15 P-256 device signing key.

Therefore verification is two-level:

\[
RootIdentity \to DeviceCertificate \to ArchivePublisherCertificate \to BEP44Head.
\]

Each device publishes its own mutable head. No shared conversation private key is required.
Future multi-device/concurrent-head merge remains an application DAG operation.

## 7. BEP44 target and signed bytes

The R16 salt is the raw 32-byte `ConversationId`.

For public key `k` and salt `s`:

\[
DHTTarget = SHA1(k || s).
\]

The Ed25519 signature is over the exact BEP44 signable byte sequence containing `salt`,
`seq`, and `v`. Sequence numbers are non-negative and monotonically advanced by the publisher.

## 8. Non-goals

R16 does not yet implement:

- DHT get/put packets;
- swarm peer discovery;
- torrent download/seeding;
- BEP44 republishing schedule;
- persistent replay/sequence repository;
- concurrent publisher-head merge UI;
- Android background networking.

Those are R17/R18 responsibilities.
