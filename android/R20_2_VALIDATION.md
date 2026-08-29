# R20.2 validation

## Dynamic — 12 PASS

- legacy messages predate secondary-device authorization;
- control confirms the legacy R19 representation exposes recoverable R15 envelope bytes;
- migration removes raw R15 envelope/key-box material from the new archive surface;
- a current R20.1 ratchet root cannot substitute for the independent recovery secret;
- destructive recovery on a new authorized device requires neither old RSA private key nor R20
  historical key grants;
- recovered device continues the same immutable MessageId DAG;
- package identity is stable across randomized exporter ECDSA proofs;
- incremental vault archive emits only fresh history;
- new vault epoch excludes a revoked device and remains accessible to an active device;
- post-revocation history is sealed under the fresh epoch;
- independent recovery secret reconstructs multi-epoch history;
- wrong secret gives zero repository mutation;
- package metadata/body tampering is rejected.

The dynamic harness prints 12 PASS lines because the package-identity/incremental condition is
one combined control.

## Fixed golden — 6 PASS

- fixed public identities;
- fixed recovery-secret export and package signature;
- fixed recovery-wrap + history-entry decryption;
- fixed active-device epoch-key grant;
- fixed cross-device recovery without legacy RSA/key grants;
- fixed wrong-secret zero-mutation rejection.

## Independent Python — 3 PASS

Python `cryptography` independently reproduces:
- package SHA-256 identity and exporter ECDSA signature;
- HKDF recovery wrap, epoch AES-GCM unwrap and history-entry AES-GCM decryption;
- ECDSA-authenticated RSA-OAEP active-device epoch grant and exact epoch-key recovery.

## Regression

The final R20.2 core was rebuilt and the complete prior pure suite rerun:

- R20.1 dynamic: 11 PASS
- R20.1 fixed: 6 PASS
- independent Python R20.1: 3 PASS
- R20 multi-device: 11 PASS
- R20 cross-device recovery: 9 PASS
- R20 fixed: 6 PASS
- R19 dynamic: 12 PASS
- R19 fixed: 3 PASS
- R18: 14 PASS
- R17 reference admission: 5 PASS
- R16 fixed: 5 PASS
- independent Python BEP52: 2 PASS
- independent Python BEP44: 3 PASS
- R15 fixed: 7 PASS
- R14.1: 13 PASS

Prior subtotal: 110 PASS.
R20.2 additions: 21 PASS.
Total: **131 PASS**.
