# R20 — Multi-device identity, revocation and DAG convergence

## Goal

R20 removes the implicit one-user/one-device assumption without changing
`EidogramDocumentV1` or the R15 immutable message identity.

The acceptance criterion is:

\[
\boxed{
SameUser(Device_1,Device_2)
\land Authorized(Device_1,Device_2)
\Rightarrow
DecryptSameHistory \land ConvergeSameDAG
}
\]

while revocation is prospective:

\[
AcceptedBeforeRevocation(M) \Rightarrow M\ remains\ valid,
\]

but

\[
NewMessage(RevokedDevice) \Rightarrow Reject.
\]

## 1. Additional-device enrollment

A secondary device creates its own private keys locally:

- P-256 signing key;
- RSA-2048 OAEP decryption key.

It exports only public keys in `EidoDeviceEnrollmentRequestV1` and signs the request with the
new signing key. This proves possession of the requested signing private key.

The user's existing root authority verifies the request and issues the same R15
`DeviceCertificateV1` used by the primary device.

Therefore no user-root private key needs to be copied to the secondary device.

## 2. Root-signed device roster

`EidoUserDeviceRosterV1` is a root-signed chain:

\[
R_n=(UserId,n,R_{n-1},Active_n,Revoked_n).
\]

Its body hash is `RosterId`.

Admission rules:

1. exact root signature;
2. epoch increases by exactly one when prior local state exists;
3. `previous_roster_id` equals the currently accepted roster;
4. every referenced device has a valid root-signed device certificate;
5. active and revoked sets are disjoint;
6. revocation set is monotone;
7. a revoked device cannot silently return to active state.

A fresh secondary device may bootstrap from a later root-signed roster snapshot. Once local
roster state exists, exact chaining is mandatory.

## 3. Multi-device recipients

For a new message the recipient set is the union of all currently active certified devices of
every conversation participant, including the sender's sibling devices.

The current sender is still added by `MessageCrypto` itself.

Hence a new message created after device enrollment can be decrypted independently on every
active device without changing its `MessageId`.

## 4. Per-device sender sequence

`sender_seq` remains scoped to:

\[
(conversationId, senderDeviceId).
\]

Two devices of the same user can therefore legitimately produce concurrent nodes with
different device IDs and the same local sequence number.

## 5. DAG convergence

If two devices independently observe head \(H\) and produce:

\[
H\to A,\qquad H\to B,
\]

then after exchanging both messages each repository computes:

\[
Heads=\{A,B\}.
\]

The next local send uses all current heads as parents:

\[
A\to C\leftarrow B.
\]

Thus convergence is defined by immutable message-set union plus deterministic DAG-head
recomputation, not by wall-clock ordering.

## 6. Revocation

Revocation affects admission and future encryption recipients.

- current revoked device cannot call normal `send`;
- a newly received message from a currently revoked sender device is rejected;
- revoked devices are removed from new message key boxes;
- an exact replay of an already accepted `MessageId` remains idempotent;
- previously accepted history remains renderable and archive-valid.

This intentionally distinguishes historical proof validity from current authorization.

## 7. Historical key grants

R15 messages created before a secondary device existed cannot contain that device's recipient
key box without changing the encrypted body and therefore `MessageId`.

R20 introduces a supplemental immutable object:

\[
\boxed{EidoMessageKeyGrantV1}
\]

An already authorized device that can decrypt message \(M\):

1. unwraps \(K_M\);
2. wraps \(K_M\) for the target secondary device;
3. binds the wrapped key to `MessageId`, owner user, grantor device and target device;
4. signs the grant using the grantor device signing key.

The original `EidogramMessageV1` is not modified:

\[
MessageId_{before}=MessageId_{after}.
\]

These grants solve historical device migration. They do not provide forward secrecy.

## 8. R19 cross-device migration

R19 packages were deliberately bound to one historical decryption key.

R20 adds `EidoMultiDeviceRecoveryCapsuleV1`, which is separate from the immutable R19 package.
It binds:

- source `PackageId`;
- owner user;
- target active device;
- all owner's public device bundles;
- latest root-signed participant rosters;
- historical message-key grants required by the target.

Recovery first reconstructs historical messages before installing current revocation policy,
so current revocation never retroactively deletes accepted archive history.

## 9. Non-goals

R20 does not claim:

- forward secrecy;
- automatic device-to-device network transport;
- root-key social recovery;
- anonymous device metadata;
- Android first-launch onboarding UX completion.

Those remain later stages. Android Keystore source adapters for primary authorization and
secondary local-key onboarding are included.
