# R20 historical message-key grants

## Problem

Adding a new recipient box to an existing R15 message changes the encrypted body:

\[
Body' \neq Body
\Rightarrow
MessageId' \neq MessageId.
\]

Therefore rewriting old envelopes is forbidden.

## Supplemental key grant

`EidoMessageKeyGrantV1` contains:

- `message_id`;
- owner user ID;
- grantor device/signing-key ID;
- target device/encryption-key ID;
- RSA-OAEP wrapped historical AES message key;
- `grant_id`;
- grantor device signature.

The grant is a local decryption supplement, not part of the message DAG.

## Security boundary

A grant never permits modification of the message. The original message signature and
`MessageId` are still verified independently before the granted AES key is used.

A device can grant only keys for messages it can itself decrypt.

## Forward secrecy

Static historical-key grants make R20 migration possible but do not solve forward secrecy.
The later ratchet stage must replace long-lived historical access semantics.
