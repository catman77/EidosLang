# R20.1 validation

## New dynamic controls — 11 PASS

- RFC5869 HKDF-SHA256 vector A.1;
- signed P-256 prekey bundle authentication;
- persistent prekey secret-state round-trip;
- one-time prekey exact single consumption;
- responder fresh-DH/root ratchet;
- out-of-order skipped-message-key recovery;
- current-state compromise cannot recover consumed historical message key;
- canonical session-secret-state round-trip;
- restored session continues live ratchet;
- failed AEAD receive rolls state back transactionally;
- initial ciphertext tamper fails sender signature.

## Fixed golden — 6 PASS

- fixed identity bundles;
- fixed prekey bundle;
- initial packet signature/session binding;
- fixed P-256 DH ratchet response;
- fixed out-of-order skipped-key consumption/erasure;
- fixed canonical secret-state vector.

## Independent Python — 3 PASS

Python `cryptography` independently reproduced:
- P-256 ECDH + HKDF root step + AES-GCM response decryption;
- out-of-order chain-key derivation for packet order 2,0,1;
- RFC5869 Appendix A.1 HKDF vector.

## Regression

Final pure-core tree was recompiled and prior suites rerun:

- R20 multi-device: 11 PASS
- R20 cross-device recovery: 9 PASS
- R20 fixed golden: 6 PASS
- R19 dynamic: 12 PASS
- R19 fixed package: 3 PASS
- R18: 14 PASS
- R17 reference admission: 5 PASS
- R16 fixed: 5 PASS
- independent Python BEP52: 2 PASS
- independent Python BEP44: 3 PASS
- R15 fixed crypto: 7 PASS
- R14.1 canonical/editor: 13 PASS

Prior regression subtotal: 90 PASS.
R20.1 additions: 20 PASS.
Total executed PASS lines: **110**.
