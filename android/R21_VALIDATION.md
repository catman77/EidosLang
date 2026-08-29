# R21 validation

## New dynamic hardening — 11 PASS

- encrypted recovery backup contains no raw recovery secret and opens with separate code;
- wrong recovery code and authenticated ciphertext tamper fail;
- fresh offline restore reports freshness as unprovable;
- local rollback anchor rejects older valid signed package;
- locally anchored vault epoch fork rejected;
- cross-vault and entry-header substitution rejected;
- signed truncated history with missing parent gives zero mutation;
- skipped-message DoS bound preserves exact ratchet state;
- outstanding one-time-prekey pool is bounded;
- migrate→verify cutover receipt requires exact history equivalence and blocks retired artifact;
- deterministic mutation corpus rejects 128 vault-package + 96 recovery-backup mutations.

## Fixed golden — 6 PASS

- canonical encrypted backup identity;
- separate code opens exact recovery-secret identity;
- backup-derived secret opens fixed R20.2 vault;
- deterministic rollback anchor;
- wrong-code and recomputed-ID ciphertext tamper rejection;
- rollback-anchor identity tamper rejection.

## Independent Python — 3 PASS

- backup canonical SHA-256 identity;
- independent AES-GCM recovery backup decryption and recovery-key identity;
- rollback-anchor SHA-256 plus AEAD mutation rejection.

## Full prior regression — 131 PASS

- R20.2 dynamic: 12
- R20.2 fixed: 6
- Python R20.2: 3
- R20.1 dynamic: 11
- R20.1 fixed: 6
- Python R20.1: 3
- R20 multi-device: 11
- R20 cross-device recovery: 9
- R20 fixed: 6
- R19 dynamic: 12
- R19 fixed: 3
- R18: 14
- R17: 5
- R16 fixed: 5
- Python BEP52: 2
- Python BEP44: 3
- R15 fixed: 7
- R14.1: 13

Total:

\[
131 + 11 + 6 + 3 = \boxed{151\ PASS}.
\]
