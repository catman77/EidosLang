# R15 security model

## Protected in R15

### Authenticity
A message is accepted only when:
1. the sender device certificate verifies under the sender root identity;
2. `MessageId` equals SHA-256 of the canonical encrypted body;
3. the device ECDSA signature verifies over exactly that body;
4. protocol/conversation lineage matches.

### Confidentiality and integrity
The eidogram canonical bytes are encrypted using AES-256-GCM with per-message random key and
nonce. GCM AAD authenticates immutable message metadata.

The AES key is never put in the envelope directly. A separate RSA-OAEP key box is emitted for
each authorized device, including the sending device.

### Key storage on Android
Production source uses `AndroidKeyStore` aliases for root signing, device signing, and device
decryption private keys.

## Explicit limitations

### No forward secrecy
R15 uses a long-lived device RSA decryption key. Compromise of that private key can expose
historical messages for which the attacker possesses the corresponding key boxes.

R15 therefore provides encrypted authenticated messaging, but it is not a Signal-style
forward-secret ratchet.

### Metadata remains visible
The following are public/signed:
- conversation ID;
- sender user/device IDs;
- recipient encryption-key IDs;
- parent message IDs;
- sender sequence;
- creation timestamp;
- ciphertext length.

### Replay persistence is deferred
The immutable envelope contains enough information to identify replay, but R15 itself has no
persistent conversation-state database. Replay rejection across process restarts belongs to
the message repository / conversation state layer.

### Root-key recovery is not yet defined
Loss of the Android root signing key currently means loss of the ability to authorize new
devices for that identity. Multi-device onboarding/recovery is a later protocol stage.
