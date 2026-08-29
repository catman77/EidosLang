# R16 BEP44 mutable conversation head

R16 uses BEP44 as a small mutable pointer, not as message storage.

## Publisher identity

A device creates an Ed25519 archive-publisher key pair. The raw 32-byte public key is bound
to the existing R15 device by `ArchivePublisherCertificateV1`, signed with the device's R15
P-256 signing key.

## Salt and target

`salt = raw32(ConversationId)`.

`target = SHA1(rawEd25519PublicKey || salt)`.

This lets one archive publisher key expose independent mutable heads for many conversations
without sharing a private key between conversation participants.

## Value

The compact bencoded value contains:

- `c`: conversation ID (32 raw bytes);
- `d`: publisher device ID;
- `h`: current message head IDs (raw 32-byte hashes);
- `i`: torrent-v2 infohash (32 raw bytes);
- `k`: application archive publisher key ID;
- `s`: segment ID (32 raw bytes);
- `v`: format integer (`1`).

R16 limits the head list to eight message IDs and requires the complete encoded `v` to fit
within 1000 bytes.

The fixed golden value is 310 bytes.
