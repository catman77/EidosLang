# R20 checkpoint — Multi-device

Status: PROTOCOL/CORE COMPLETE + PURE-KOTLIN MULTI-DEVICE/RECOVERY PASS

Added:
- `core-multidevice`;
- root-authorized enrollment request with signing-key possession proof;
- secondary `JvmSecondaryDevice`;
- Android primary root-authority adapter;
- Android secondary-device KeyStore adapter;
- root-signed monotone device roster;
- fresh-device roster snapshot bootstrap;
- prospective revocation enforcement;
- active-device filtering for message recipients;
- sibling local devices included in recipient boxes;
- sibling devices accepted as same-user message senders;
- deterministic concurrent-head convergence;
- historical `EidoMessageKeyGrantV1`;
- repository persistence for own devices, rosters and key grants;
- SQLite v1 -> v2 migration;
- R20 cross-device recovery capsule v1.1;
- R19 package restoration onto a second authorized device;
- participant roster preservation in recovery capsule;
- R16 archive segments include known sibling local device certificates.

Core invariant:
`MessageId` remains unchanged during historical device migration.

Executed final pure checks:
- R20 multi-device: 11 PASS
- R20 cross-device recovery: 9 PASS
- R20 fixed golden: 6 PASS
- R19 dynamic regression: 12 PASS
- R19 fixed package: 3 PASS
- R18 regression: 14 PASS
- R17 reference admission: 5 PASS
- R16 fixed golden: 5 PASS
- independent Python BEP52: 2 PASS
- independent Python BEP44: 3 PASS
- R15 fixed crypto: 7 PASS
- R14.1 canonical/editor core: 13 PASS

Total executed PASS lines: 90.

Not executed:
- Android Gradle/Compose build;
- AndroidKeyStore secondary-device enrollment on emulator/device;
- SQLite migration instrumentation;
- real BitTorrent/DHT networking.

Next:
R20.1 — forward-secrecy session layer.
