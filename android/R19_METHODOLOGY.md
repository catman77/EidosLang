# R19 — Archive / Recovery methodology

## Goal

R19 integrates the R18 local messenger repository with the already frozen R16 immutable
archive format. The acceptance criterion is not merely exportability. The criterion is
reconstruction:

\[
\boxed{
Delete(LocalMessageState)
\;\land\;
Keep(DeviceKeys)
\;\land\;
Verified(R16Segments)
\Rightarrow
Recover(MessageDAG,Eidograms)
}
\]

A full R19 package additionally restores local presentation metadata that R16 intentionally
does not carry, such as contact aliases, conversation titles and empty conversations.

## 1. Incremental segment construction

For a conversation let:

- \(M_L\) be locally committed messages;
- \(M_A\) be message IDs already covered by verified local archive segments.

The next segment contains exactly:

\[
M_{new}=M_L\setminus M_A.
\]

If `M_new` is empty, no new segment is emitted.

`previous_segment_ids` are the current heads of the local segment DAG. Message parents that
are not in `M_new` are declared as external parents, preserving partial-DAG semantics.

Every segment includes the public identity bundles currently known for all conversation
participants. The local private keys are never put into a segment.

## 2. Archive identity layers

R19 preserves the separation:

\[
MessageId \neq SegmentId \neq TorrentInfoHashV2 \neq PackageId.
\]

- `MessageId` identifies the encrypted R15 message body.
- `SegmentId` identifies the logical immutable R16 segment manifest.
- `TorrentInfoHashV2` identifies its deterministic BitTorrent-v2 representation.
- `PackageId` identifies the local recovery package containing segment blobs plus local
  presentation metadata.

Pin state is not part of `SegmentId` or `TorrentInfoHashV2`.

## 3. Complete local recovery package

`EidoArchivePackageV1` contains:

- owner user ID;
- required recovery device ID;
- required recovery encryption-key ID;
- public contact identity bundles + local aliases;
- canonical conversation descriptors + local titles/creation times;
- canonical R16 segment blobs;
- pin state.

It contains no private keys.

The package is canonical JSON and has:

\[
PackageId=SHA256(CanonicalJSON(PackageBody)).
\]

Export is fail-closed if the local repository contains committed messages that are not yet
covered by archive segments. The Android Archive screen archives pending messages before
export.

## 4. Segment blob verification

A stored segment blob contains the exact files needed to reconstruct the R16 artifact and the
exact `.torrent` metainfo bytes. Import performs:

1. base64 decode;
2. per-file length and SHA-256 checks;
3. R16 canonical manifest parsing;
4. conversation descriptor parsing;
5. public identity verification;
6. R15 message signature verification;
7. R16 message-DAG/head checks;
8. deterministic BEP52 rebuild;
9. exact torrent-v2 infohash check;
10. exact `.torrent` byte equality.

Therefore:

\[
ArchiveFileReadable\not\Rightarrow ArchiveAccepted.
\]

## 5. Transactional preflight

Package import verifies every segment before mutating either the archive store or messenger
repository. Repository recovery is first replayed into an in-memory mirror containing the
current local state. Only if the mirror accepts the whole recovery set is the actual repository
mutated.

This makes malformed/colliding packages fail before partial recovery writes.

## 6. Deterministic overlap handling

Multiple valid segments may overlap in message coverage. R19 merges by `MessageId`.

For repeated copies of one message:

- all copies must parse and verify;
- their canonical encrypted bodies must be identical;
- distinct valid ECDSA proof bytes are allowed;
- one canonical proof copy is selected deterministically.

Different message IDs using the same `(conversation_id, sender_device_id, sender_seq)` remain
an equivocation error.

## 7. Recovery ordering

All unique archived messages are ordered by their parent graph. Topological ordering uses the
same deterministic R18 tie-break among independent nodes:

1. `created_at_ms`;
2. `sender_user_id`;
3. `sender_seq`;
4. `message_id`.

The archive cannot introduce a cycle.

## 8. Two recovery levels

### Segment-only recovery

Verified R16 segments alone recover:

- public sender identities present in the archive;
- canonical conversation descriptors carried by segments;
- encrypted message envelopes;
- message DAG and heads;
- exact decrypted eidograms, if the current device still owns the required decryption key.

Aliases, titles of conversations with no segments, and unused contacts are not R16 data and
therefore cannot be inferred from segments alone.

### Full-package recovery

`EidoArchivePackageV1` additionally restores those local presentation objects, including
contacts and conversations that have no messages yet.

## 9. Key-loss boundary

R19 does **not** back up private keys. A package is explicitly bound to:

- `owner_user_id`;
- `recovery_device_id`;
- `recovery_encryption_key_id`.

Thus R19 covers deletion/corruption of local application data while Android Keystore material
survives. Restoring historical ciphertext on another device is intentionally rejected and is a
multi-device/key-migration problem for R20.
