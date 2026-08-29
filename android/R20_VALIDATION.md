# R20 validation

## New R20 dynamic controls

### Multi-device
11 PASS:
- initial root-signed rosters;
- legacy pre-secondary message;
- possession-proved root authorization;
- two-device activation for both users;
- historical key grant;
- all-active-device recipient boxes;
- concurrent branch convergence;
- deterministic merge;
- prospective revocation;
- revoked-recipient removal;
- roster rollback/re-activation rejection.

### Cross-device recovery
9 PASS:
- R16 source archive before secondary enrollment;
- minimal historical grant generation;
- canonical package/capsule binding;
- R19 package restored on second authorized device;
- key-grant persistence;
- continued DAG from recovered device;
- migrated-device backup preserves historical grants through a companion capsule;
- incomplete capsule zero-mutation failure;
- revoked recovery target rejection.

### Fixed R20 golden
6 PASS:
- public identities;
- enrollment possession proof;
- root-signed roster chain;
- historical grant decryption;
- fixed cross-device recovery capsule;
- key-grant target tamper rejection.

## Regression

- R19 dynamic: 12 PASS
- R19 fixed package: 3 PASS
- R18: 14 PASS
- R17 reference admission: 5 PASS
- R16 fixed: 5 PASS
- independent Python BEP52: 2 PASS
- independent Python BEP44: 3 PASS
- R15 fixed: 7 PASS
- R14.1: 13 PASS

Total executed PASS lines: **90**.

Android runtime/build and real network execution remain intentionally outside this artifact
environment.
