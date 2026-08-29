# R20.1 security model

## Protected

### Packet confidentiality/integrity
Every session packet uses a one-use ratchet message key and AES-256-GCM.

### Asynchronous bootstrap authentication
The recipient prekey bundle is signed by the existing R20 device-signing key.
The initial session ciphertext and public bootstrap parameters are signed by the initiator's
certified device-signing key.

### Historical ratchet-key erasure
After a chain step, the previous chain key is replaced. Used message keys and consumed skipped
keys are removed from session state.

### One-time prekey consumption
A successful/authorized initial bootstrap consumes the selected one-time prekey. It cannot be
used by a second session initialization.

### Local-state substitution
Encrypted Android secret blobs authenticate their logical storage name through GCM AAD.

## Explicit limitations

### Java/JCA erasure is logical, not a hardware proof
Byte-array chain/message material is overwritten where directly controlled, but `PrivateKey`
objects and managed-runtime copies cannot be proven physically erased from all Java heap/VM
locations.

### P-256, not X25519
The initial compatibility profile uses P-256 ECDH because it works with the existing R20 P-256
identity ecosystem and the project's `minSdk 26` JCA boundary.

### Not X3DH
The bootstrap combines a signed prekey and one-time prekey with an initiator ephemeral key,
then authenticates the resulting initial packet using the existing device signature. It does
not reproduce X3DH's identity-DH construction and must not be labelled X3DH.

### Not a formal Double Ratchet implementation claim
R20.1 uses a symmetric ratchet plus recurring fresh DH/root-key mixing and skipped-message keys.
Its equations and state transitions are independently tested, but this project has not yet
undergone formal protocol verification or third-party cryptographic audit.

### Archive remains the decisive gap
The old R16/R19 archive stores R15 envelopes rather than forward-session packets/history-vault
objects. Therefore archive compromise combined with later static R15 RSA-key compromise still
exposes those legacy archived messages.

R20.1 gives the session transport a forward-secret key schedule; it does not magically change
the already-defined archive threat model.
