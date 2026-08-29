# R18 checkpoint — Messenger UX + Local Repository

Status: SOURCE COMPLETE + PURE-KOTLIN MESSENGER/REPOSITORY PASS

Added modules:
- `core-repository`
- `feature-viewer`
- `feature-messenger`

Application root:
- changed from standalone editor to `EidoMessengerApp`.

Implemented:
- public contact import/export;
- contact device persistence;
- canonical conversation creation/import/export;
- SQLite conversation/message repository;
- encrypted-envelope-only message persistence;
- exact replay idempotence;
- sender-sequence equivocation rejection;
- out-of-order sequence admission;
- deterministic message-DAG topological timeline;
- all-head merge for new local message;
- local-device decryption gate for incoming messages;
- eidogram timeline viewer;
- conversation-scoped eidogram composer drafts;
- manual message envelope import/export;
- Android instrumentation smoke-test source updated for messenger home/contacts.

R15 correction included:
- stable cached Android root signature for unchanged device certificate body;
- verified re-issued ECDSA proof can refresh the same contact `(user_id, device_id)`.

Executed pure checks on final R18 tree:
- R18 messenger/repository: 14 PASS
- R17 reference admission regression: 5 PASS
- R16 fixed golden: 5 PASS
- R16 independent Python BEP52: 2 PASS
- R16 independent Python BEP44: 3 PASS
- R15 fixed crypto: 7 PASS
- R14.1 canonical/editor core: 13 PASS
- total executed PASS lines: 49

Not executed:
- Android Gradle build;
- Android SQLite runtime instrumentation;
- Android Keystore runtime test;
- Compose instrumentation;
- real BitTorrent/DHT networking.

Per project direction, real network execution is not a blocker for subsequent product stages.

Next:
R19 — Archive / Recovery and local R16 segment integration.
