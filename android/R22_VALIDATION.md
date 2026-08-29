# R22 validation

## R22 product journey — 11 PASS

The JVM product journey verifies:

1. explicit epoch-0 rosters;
2. ordinary pre-secondary message;
3. secondary possession-proof enrollment + root authorization + roster activation;
4. secure vault + encrypted backup + separate code;
5. secondary destructive restore without historical RSA/key grant;
6. explicit fresh-restore freshness classification + rollback protection;
7. restored secondary continues the exact immutable MessageId DAG;
8. primary revocation produces a fresh secondary-only vault epoch;
9. post-revocation history lands only in the fresh epoch;
10. migrate→recover→compare cutover blocks the retired legacy artifact;
11. mismatched recovery code fails.

## Fixed R22 product composition — 5 PASS

The fixed test composes the exact existing R20.2 and R21 vectors:

- same VaultId;
- R21 backup/code opens exact R20.2 recovery-key identity;
- recovered fixed history decrypts to the exact MessageId/document hash;
- rollback anchor binds exact package/message/entry IDs;
- exact current package is idempotently accepted against its checkpoint.

No new crypto primitive required a new independent implementation.

## Android source audit — 13 PASS

Static source boundary checks verify:

- explicit first-run root/secondary routing;
- complete secondary onboarding source;
- non-creating identity lookup and duplicate Android source declaration repair;
- primary authorization/revocation source;
- secure-vault/recovery UI;
- cutover creation;
- cutover enforcement on legacy import;
- SQLite v3 protected alias/title fields;
- encrypted DraftStore;
- runtime admission probe;
- Android backup disabled / cleartext disabled;
- R22 modules/version wiring.

This is a source audit, not an Android compile/runtime claim.

## Prior regression — 151 PASS

R14.1–R21 was rerun on the final R22 pure-core tree:

- R14.1: 13
- R15 fixed: 7
- R16 fixed: 5
- independent Python BEP52: 2
- independent Python BEP44: 3
- R17: 5
- R18: 14
- R19: 12
- R19 fixed: 3
- R20: 11
- R20 recovery: 9
- R20 fixed: 6
- R20.1: 11
- R20.1 fixed: 6
- independent Python R20.1: 3
- R20.2: 12
- R20.2 fixed: 6
- independent Python R20.2: 3
- R21: 11
- R21 fixed: 6
- independent Python R21: 3

Subtotal: 151 PASS.

## Total

\[
151 + 11 + 5 + 13 = \boxed{180\ PASS}.
\]

## Runtime boundary

Android SDK/Gradle/adb are absent from the current artifact environment.

Therefore:

- Android build: NOT RUN
- Android instrumentation: NOT RUN
- AndroidKeyStore admission: NOT RUN
- SQLite v3 Android migration: NOT RUN
- real network: NOT RUN

`tools/r22-android-admission.sh` is included for the next execution environment.
