# R15 checkpoint — Identity + Message Envelope

Status: PROTOCOL IMPLEMENTED / PURE-KOTLIN JCA + FIXED GOLDEN PASS

Added modules:
- `core-crypto`
- `core-message`

Implemented:
- P-256 root user identity;
- P-256 device signing identity;
- RSA-2048 OAEP device decryption identity;
- root-signed device certificates;
- canonical public identity bundle;
- canonical conversation descriptor with random 128-bit seed;
- AES-256-GCM encrypted canonical eidogram payload;
- per-recipient RSA-OAEP wrapped content key;
- sender self-recipient key box;
- signed AAD with lineage/conversation/parents/sequence/time;
- deterministic message ID from canonical encrypted body;
- strict canonical message parser;
- sender verification and recipient decryption;
- AndroidKeyStore production adapter;
- fixed test-only cryptographic golden vector.

Regression:
- all R14.1 fixed eidogram golden hashes remain unchanged under R15.

Not executed:
- Android Gradle build / emulator;
- AndroidKeyStore runtime test on a physical/emulated Android device.

Security boundary:
- authenticated encryption: YES
- static-key forward secrecy: NO
- anonymous metadata: NO

Next stage:
R16 — Torrent Archive Model.
