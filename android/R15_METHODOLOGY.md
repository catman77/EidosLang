# R15 — Identity + Signed/Encrypted Message Envelope

## Goal

Turn a canonical `EidogramDocumentV1` into an authenticated, confidential message object
without introducing torrent/archive semantics yet.

The protocol separates four identities:

\[
UserIdentity \neq DeviceIdentity \neq ConversationId \neq MessageId.
\]

## 1. User root identity

A user's root identity is a NIST P-256 public signing key.

\[
UserId =
SHA256(CanonicalJSON(UserIdentityBody)).
\]

The root private key is used only to certify device public keys.

## 2. Device identity

Every device has:

- P-256 ECDSA signing key;
- RSA-2048 OAEP decryption key.

The device certificate body binds both public keys to `UserId` and is signed by the root
identity key.

\[
DeviceId =
SHA256(CanonicalJSON(DeviceCertificateBody)).
\]

Private keys in the Android implementation are generated under stable `AndroidKeyStore`
aliases and are not exported by application code.

## 3. Conversation identity

A conversation descriptor contains:

- 128-bit random conversation seed;
- sorted distinct participant user IDs.

\[
ConversationId =
SHA256(CanonicalJSON(ConversationBody)).
\]

The random seed means the same participant set can create multiple unlinkable conversation
IDs.

## 4. Per-message hybrid encryption

For every message:

1. generate a fresh 256-bit AES key \(K_m\);
2. generate a fresh 96-bit GCM nonce \(N_m\);
3. construct canonical AAD containing:
   - protocol lineage;
   - conversation ID;
   - sender user/device/signing-key IDs;
   - sender-local sequence;
   - creation timestamp;
   - parent message IDs;
   - sorted recipient encryption-key IDs;
4. encrypt the canonical eidogram bytes with AES-256-GCM;
5. wrap \(K_m\) separately for every recipient device and the sender device using
   RSA-OAEP SHA-256 / MGF1-SHA-1;
6. build canonical encrypted body;
7. define:

\[
MessageId=SHA256(CanonicalJSON(EncryptedBody));
\]

8. sign the exact encrypted body with the sender device's ECDSA P-256 key.

The plaintext eidogram hash is not exposed in the public message envelope. Re-encrypting the
same eidogram produces a different public `MessageId`.

## 5. Message DAG

The signed AAD contains sorted `parent_message_ids` and a sender-local sequence number.

This is enough to establish the immutable message DAG later used by the torrent conversation
head. Wall-clock `created_at_ms` is signed but is presentation metadata, not the canonical
causal ordering primitive.

## 6. Canonical import

Public identity bundles, conversation descriptors, and message envelopes all have strict
canonical parsers. A valid-but-noncanonical JSON encoding is rejected.

## 7. R15 non-goals

R15 does not provide:

- forward secrecy / double ratchet;
- anonymous metadata;
- contact discovery;
- multi-device key onboarding;
- revocation;
- torrent transport;
- replay-state database.

Those require stateful protocols beyond a single immutable message envelope.
