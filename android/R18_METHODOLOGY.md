# R18 — Messenger UX + Local Repository

## Goal

R18 turns the R14–R17 protocol stack into a usable local messenger without making real
network execution a prerequisite.

The product boundary is now:

\[
Editor \rightarrow EidogramDocumentV1 \rightarrow EidogramMessageV1
\rightarrow LocalRepository \rightarrow Timeline.
\]

`TorrentTransport` remains a later synchronization adapter. The local messenger is required
to preserve the same cryptographic and DAG invariants even when messages arrive through
manual file import, a reference transport, or a future native BitTorrent backend.

## 1. Repository is not a second protocol

The SQLite database stores canonical protocol objects rather than inventing a new wire
representation.

Stored contact material:
- public `EidoPublicIdentityBundleV1`;
- local alias and import timestamp.

Stored conversation material:
- canonical `EidoConversationV1` descriptor;
- local title and creation timestamp.

Stored message material:
- canonical encrypted/signed `EidogramMessageV1` envelope;
- direction and local archive state;
- derived index fields for query/replay enforcement.

The decrypted `EidogramDocumentV1` is **not** stored in the messages table. Timeline rendering
decrypts the canonical envelope on demand through the local device key.

## 2. Replay and sequence invariant

For one conversation and sender device:

\[
(conversation\_id, sender\_device\_id, sender\_seq)
\]

is unique.

An exact repeated `message_id` with identical canonical bytes is idempotent and returns
`Duplicate`.

A different message attempting to reuse the same sender sequence is rejected as equivocation.

The repository intentionally does **not** require received sequence numbers to be greater than
the current maximum. P2P delivery can be out of order. Therefore messages `seq=1` then `seq=0`
are both admissible if they are otherwise valid and unique.

## 3. DAG ordering

Every outgoing message uses all current known DAG heads as parents:

\[
Parents(new)=Heads(local\ conversation\ DAG).
\]

If two independent branches are known, the next local message merges them into one new head.

Timeline order is a deterministic topological sort. Parent edges dominate wall-clock time.
Among simultaneously ready nodes, the tie-break is:

1. signed `created_at_ms`;
2. sender user ID;
3. sender sequence;
4. message ID.

Unknown parents do not prevent local storage. If a missing parent arrives later and would make
the known graph cyclic, insertion is rejected.

## 4. Incoming admission

Manual import and future network receipt use the same path:

\[
CanonicalParse
\rightarrow ConversationBinding
\rightarrow SenderIdentity
\rightarrow SignatureVerification
\rightarrow LocalKeyBox
\rightarrow AESGCMDecrypt
\rightarrow EidogramCanonicalParse
\rightarrow DAG/SequenceAdmission
\rightarrow RepositoryInsert.
\]

Transport success or file existence is never sufficient.

## 5. Contact certificate proof stability

R15 device identity is the root-certified **device certificate body**. ECDSA certificate
signatures are proof material and need not be byte-identical if re-issued for the same body.

R18 corrects two consequences:

- contact storage is keyed by `(user_id, device_id)` and may refresh a newly verified proof;
- the Android Keystore adapter caches a valid root signature for the unchanged device body so
  exported public identity bundle bytes remain stable across normal process restarts.

Cached proof bytes are verified against the root public key before reuse.

## 6. Android persistence

`AndroidSqliteMessengerRepository` uses platform SQLite and enforces:

- primary key on `message_id`;
- unique `(conversation_id, sender_device_id, sender_seq)`;
- canonical identity/conversation/message storage.

No external database dependency is required for R18.

## 7. UX

The application root is now the messenger rather than the standalone editor.

Implemented source flows:

- conversation list;
- contact list;
- export local public identity;
- import contact identity;
- create one-to-one conversation from a contact;
- export/import conversation descriptor;
- conversation timeline of rendered eidograms;
- open editor as conversation composer;
- send commits encrypted signed message locally;
- export latest outgoing envelope;
- import incoming envelope;
- conversation-scoped editor drafts.

The underlying service already supports more than two participant user IDs, although R18 UI
exposes the simple one-contact creation flow first.

## 8. No-network two-device workflow

R18 can be exercised without a torrent engine:

1. A exports its public identity; B imports it.
2. B exports its public identity; A imports it.
3. A creates a conversation and exports `conversation.json`; B imports it.
4. A composes an eidogram, sends locally, and exports the latest outgoing message envelope.
5. B imports that envelope; signature/decryption/DAG admission runs and the eidogram appears.
6. B replies and exports the reply; A imports it.

This is not the final transport, but it tests the actual end-to-end message semantics rather
than a UI mock.

## 9. Security boundary

R18 does not change R15 cryptography. Consequently the R15 no-forward-secrecy limitation
remains.

Additional local-storage facts:

- message plaintext is not stored in SQLite;
- message metadata and encrypted envelopes are stored in SQLite;
- editor draft files contain plaintext canonical eidograms in app-internal storage;
- database-at-rest hardening and draft encryption remain R21 security work.
