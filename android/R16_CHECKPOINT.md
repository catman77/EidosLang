# R16 checkpoint — Torrent Archive Model

Status: PROTOCOL IMPLEMENTED / FIXED GOLDEN + INDEPENDENT CROSS-CHECK PASS

Added module:
- `core-archive`

Implemented:
- canonical bencode encoder + strict canonical decoder;
- frozen 16KiB BitTorrent-v2 profile;
- SHA-256 v2 Merkle roots;
- BEP52 file tree and piece layers;
- v2 infohash and `btmh` magnet generation;
- immutable `ConversationSegmentV1`;
- strict segment manifest parser;
- downloaded-file segment reconstruction/verification;
- message DAG head derivation;
- archive path traversal rejection;
- R15-device-signed Ed25519 archive publisher certificate;
- compact `ConversationHeadValueV1`;
- BEP44 mutable-item target/signature/value construction and verification;
- fixed on-disk R16 golden archive;
- independent Python BEP52 byte-for-byte cross-check;
- independent Python Ed25519/BEP44 cross-check.

Fixed golden IDs:
- conversation: `47309d0261530f511489bcf5108a3a0f1bdc59fa88015aa7c84ece864d3959e3`
- segment: `78aa678b7004e0acb800bcc0732d39c41028db776da51cbc0018e22e8af0d25b`
- v2 infohash: `d53c6a8c2d521374d8cf38d025b5dfa273c59b3df75d44f26d7a495340d45129`
- BEP44 target: `06ed00b9aa03a6f26a30069a823c115d1f1792ca`
- BEP44 value size: 310 bytes

Regression:
- R15 fixed crypto golden: PASS
- R14.1 editor/canonical core: PASS

Not executed:
- actual DHT publication;
- actual swarm download/seeding;
- Android/NDK/JNI build.

Next stage:
R17 — Android P2P/Torrent Engine.
