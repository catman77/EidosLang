# R19 validation

Executed on the final R19 source tree:

- R19 dynamic archive/recovery: **12 PASS**
- R19 fixed package: **3 PASS**
- R18 messenger/repository regression: **14 PASS**
- R17 reference transport admission: **5 PASS**
- R16 fixed archive golden: **5 PASS**
- independent Python BEP52: **2 PASS**
- independent Python BEP44: **3 PASS**
- R15 fixed cryptographic golden: **7 PASS**
- R14.1 canonical/editor core: **13 PASS**

Total: **64 PASS**.

R19 dynamic controls cover:

1. first incremental archive segment;
2. refusal to export an incomplete archive;
3. second incremental segment and segment lineage;
4. pin-state independence;
5. canonical package round-trip and absence of private keys;
6. destructive local-state recovery including empty conversations/unused contacts;
7. exact package replay idempotence;
8. segment-only message-DAG/eidogram recovery;
9. deterministic overlapping-segment deduplication;
10. refreshed valid ECDSA message proof idempotence;
11. corrupted-package transactional rejection;
12. rejection on a device without the bound historical decryption key.

Not executed in this environment:
- Android Gradle/Compose build;
- AndroidFileArchiveStore instrumentation;
- Android SQLite/Keystore runtime;
- real BitTorrent/DHT networking.
