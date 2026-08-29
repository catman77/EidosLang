# R20.1 — Forward-Secrecy Session Layer

## Goal

R20.1 adds a pairwise asynchronous forward-session layer without changing
`EidogramDocumentV1`, `ConversationId`, or the existing immutable R15/R20 message DAG.

The transport transformation is:

\[
EidogramMessageV1
\rightarrow
EidoForwardSessionV1(packet)
\]

and the recipient performs:

\[
SessionDecrypt(packet)
\rightarrow
Canonical(EidogramMessageV1)
\rightarrow
R18/R20\ repository\ admission.
\]

Thus the ratchet is an outer delivery/session layer. It does not silently redefine historical
message IDs.

## 1. Cryptographic suite

R20.1 freezes the initial compatibility profile:

- DH: NIST P-256 / `ECDH`;
- KDF: HKDF-SHA-256;
- symmetric chain: HMAC-SHA-256;
- packet AEAD: AES-256-GCM;
- prekey/initial authentication: existing R20 P-256 ECDSA device key.

This is an EidoLang-specific protocol. It is not claimed to be X3DH or the Signal Double
Ratchet wire format.

## 2. Asynchronous prekey bootstrap

A recipient device publishes a signed `EidoDevicePreKeyBundleV1` containing:

- one signed P-256 ECDH prekey;
- one P-256 one-time prekey;
- user/device/signing-key identity binding;
- device ECDSA signature over the canonical bundle body.

The initiator creates a fresh P-256 ephemeral key \(E_A\) and derives:

\[
IKM =
ECDH(E_A,SPK_B)
\Vert
ECDH(E_A,OPK_B).
\]

HKDF expands this into:

- root key;
- initiator→recipient chain key;
- recipient→initiator chain key.

The initial ciphertext and all public session parameters are signed by the initiator's already
certified R20 device signing key.

The recipient consumes the one-time prekey exactly once. Replay/reuse after consumption fails.

## 3. Symmetric ratchet

For each message on one sending chain:

\[
MK_n=HMAC(CK_n,0x01),
\]

\[
CK_{n+1}=HMAC(CK_n,0x02).
\]

`MK_n` is used once for AES-256-GCM and then erased from protocol state.

Hence later chain state does not directly contain earlier message keys.

## 4. DH/root ratchet

A fresh remote DH public key triggers:

\[
(RK',CK_r)=KDF_{RK}(RK,ECDH(DH_s,DH_r')),
\]

then a fresh local DH key is generated and:

\[
(RK'',CK_s)=KDF_{RK}(RK',ECDH(DH_s',DH_r')).
\]

`KDF_RK` is HKDF-SHA-256 with the old root key as HKDF salt and fresh ECDH output as input
keying material.

The responder performs the first fresh-DH step on its first post-bootstrap send.

## 5. Out-of-order receive

The implementation keeps a bounded local skipped-message-key map keyed by:

\[
(DHPublic,n).
\]

The current default bound is 256 keys. This is an implementation DoS/resource policy, not a
wire-format field or protocol identity constant.

Once a skipped key is consumed, it is removed.

## 6. Transactional decryption

Ratchet receive state must not advance on authentication failure.

R20.1 therefore checkpoints secret state before a mutating receive. If packet hash, DH
transition, skip admission, or AES-GCM authentication fails, the prior state is restored.

This prevents an unauthenticated ciphertext from desynchronizing a valid session.

## 7. Secret persistence

Two secret state classes now exist:

- `EidoForwardSessionSecretStateV1`;
- `EidoPreKeySecretStateV1`.

They are canonical only to permit deterministic encrypted persistence. They are never public
protocol artifacts.

On Android, `AndroidSecretBlobStore` encrypts these opaque bytes under an AES-256-GCM key
owned by AndroidKeyStore.

The logical blob name is included as GCM AAD, preventing substitution of one valid encrypted
session/prekey blob for another.

## 8. Compatibility boundary

Android platform `ECDH` is available well below project `minSdk 26`, but AndroidKeyStore
`PURPOSE_AGREE_KEY` is a later API. Therefore the compatibility implementation generates
ratchet/prekey P-256 keys with ordinary JCA and stores their private state only inside the
KeyStore-encrypted local secret store.

This storage backend can later be replaced by hardware-backed ECDH where available without
changing the R20.1 public wire objects.

## 9. Security claim boundary

R20.1 supports the forward-secrecy mechanism:

\[
Compromise(CurrentRatchetState)
\not\Rightarrow
Recover(ErasedHistoricalMessageKey)
\]

provided the old key is not retained elsewhere.

It does not claim:

- formal equivalence to Signal's Double Ratchet;
- perfect Java-heap zeroization;
- post-quantum security;
- archive-level forward secrecy;
- protection after compromise of locally stored plaintext/history-vault material.

The archive limitation is a proved architectural obligation, documented separately.
