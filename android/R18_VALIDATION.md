# R18 validation

Executed on final source tree:

- R18 messenger/repository: 14 PASS
- R17 reference transport admission regression: 5 PASS
- R16 fixed archive golden: 5 PASS
- independent Python BEP52: 2 PASS
- independent Python BEP44: 3 PASS
- R15 fixed cryptographic golden: 7 PASS
- R14.1 canonical/editor-core regression: 13 PASS

Total: **49 PASS**.

R18 tests cover contact import, refreshed certificate proof, shared conversation descriptor, local send, incoming decrypt, exact replay idempotence, reply DAG ordering, sender sequence advancement, sequence equivocation rejection, missing recipient-key-box rejection, unknown participant rejection, out-of-order arrival, branch merge, and conversation summary derivation.

Not executed in this environment: Android Gradle build, SQLite instrumentation, AndroidKeyStore runtime test, Compose instrumentation, real network execution.
