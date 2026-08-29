# R19 checkpoint — Archive / Recovery

Status: SOURCE COMPLETE + PURE-KOTLIN DESTRUCTIVE-RECOVERY PASS

Added modules:
- `core-recovery`
- `feature-archive`

Implemented:
- incremental R18 -> R16 segment creation;
- segment-DAG lineage;
- persistent Android file archive store;
- archive segment index metadata;
- pin/unpin state;
- canonical `EidoArchivePackageV1`;
- complete package metadata for contacts and empty conversations;
- fail-closed export when committed messages are not archived;
- deterministic overlapping-segment deduplication;
- full package import/recovery;
- segment-only cryptographic recovery;
- preflight before archive/repository mutation;
- same-device/key recovery binding;
- archive browser UI;
- package import/export through Android document provider;
- source instrumentation navigation check.

R19 correction:
- `MessageId` is identity of the canonical encrypted body;
- ECDSA signature bytes are proof material;
- a valid re-signature of the same body no longer forks a repository message.

Executed on final pure source boundary:
- R19 dynamic archive/recovery: 12 PASS
- R19 fixed recovery package: 3 PASS
- R18 messenger/repository regression: 14 PASS
- R17 reference transport admission: 5 PASS
- R16 fixed archive golden: 5 PASS
- R16 independent Python BEP52: 2 PASS
- R16 independent Python BEP44: 3 PASS
- R15 fixed crypto golden: 7 PASS
- R14.1 canonical/editor core: 13 PASS

Total executed PASS lines: 64.

Not executed in this environment:
- Android Gradle build;
- Android file-store instrumentation;
- Android SQLite/Keystore runtime;
- Compose instrumentation;
- real BitTorrent/DHT execution.

Per project direction, real network execution remains deferred and is not a blocker.

Next:
R20 — Multi-device identity, authorization, revocation and deterministic cross-device merge.
