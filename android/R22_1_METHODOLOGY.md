# R22.1 — Android device admission and real transport

## Scope

R22 declared `SOURCE PRODUCT INTEGRATION COMPLETE + ANDROID RUNTIME ADMISSION PENDING` and listed
its own claim boundary honestly: no Gradle build, no instrumentation, no KeyStore runtime probe, no
SQLite v3 runtime migration, no real network. R22.1 executes that boundary.

It is an execution stage, but it did not stay one. Building the tree for the first time and running
it on hardware produced counterexamples, and `R22_METHODOLOGY.md` permits exactly that:

\[
NewWireEntity = 0
\]

unless an Android/runtime counterexample proves one necessary. No new wire entity was needed. Every
change below is a build fix, a defect fix, or the transport backend the stack was always missing.

## 1. The tree did not compile

Five blockers, in dependency order.

1. `android.builtInKotlin=false` opts out of AGP's built-in Kotlin to pin the Compose compiler.
   AGP 9.3.0 also defaults `android.newDsl=true`, and the standalone `org.jetbrains.kotlin.android`
   plugin refuses to apply beside it. `android.newDsl=false` only moves the failure: the legacy
   `android { }` / `kotlinOptions { }` DSL is deprecated and Kotlin DSL script compilation treats
   that as an error. The pin combination has no working configuration. R22.1 migrates to AGP
   built-in Kotlin: the flag is dropped, `alias(libs.plugins.kotlin.android)` is removed from all
   23 module scripts, and every `kotlinOptions { jvmTarget = "17" }` block is deleted
   (`compileOptions` alone suffices).
2. `native-torrent` declared namespace `org.eidolang.native.torrent`; `native` is a Java keyword.
   Only the AGP namespace changes, to `org.eidolang.nativetorrent`. The Kotlin package is legal and
   is untouched.
3. `core-vault` used `org.eidolang.core.archive.*` without declaring `:core-archive`. It reached it
   transitively through `core-recovery`, which uses `implementation`, so it was not exposed.
4. `feature-archive` resolved `UserDeviceRosterV1` out of a `core-vault` return type without
   declaring `:core-multidevice`.
5. `ArchiveScreen.kt` bound the secure-restore button as `onClick={performSecureRestore}` — a bare
   reference where a call is required. This is the primary action of the R22 secure recovery path.

Blockers 3 and 4 are structurally invisible to the pure-JVM harness, which compiles every `core-*`
source in one `kotlinc` invocation and therefore never exercises module boundaries. Blocker 5
passed the R22 Android source audit, which is substring matching over source text.

## 2. SQLite v3 could never store a row

`AndroidLocalSecretBox.validate` restricts `logicalId` to `[A-Za-z0-9._-]{1,160}` because `|` is
its AAD delimiter. `AndroidSqliteMessengerRepository` passed `"$userId|$deviceId"` for contact
aliases and a raw `conversationId` for titles, and identities are formatted `dev:<hex>`. Both the
pipe and the colon violate the contract, so every `putContact` and `putConversation` threw.

The R22 admission probe did not catch this because `repository.sensitive_text` audits *stored* rows
and passes over an empty table. The check was green while the feature was inoperable.

R22.1 derives a delimiter-safe id by hashing — `HexSha256.of("$userId|$deviceId")` — which keeps the
ciphertext bound to its logical row. All six protect/unprotect call sites, including the v2→v3
migration path, are updated. `R22_SQLITE_V3.md` is corrected to match.

## 3. Transport: the product had none, and no message could be delivered

`ReferenceTransportNetwork` was the only implementation of `TorrentTransport`, and its own comment
says it is NOT BitTorrent. No application code referenced `TorrentTransport` or
`NetworkAdmissionGate`. `JcaArchivePublisher` was referenced only from `ConversationHead.kt`, took
`JvmPrivateIdentity` so it could not be constructed on Android at all, and minted a fresh Ed25519
pair on every call with no persistence — so `target = SHA1(pk || salt)` changed every run and a
mutable head could never be updated.

R22.1 adds `transport-libtorrent4j`, and with it the messenger actually delivers:

- `Libtorrent4jTransport` implements `TorrentTransport` over the prebuilt
  `org.libtorrent4j:2.1.0-35` Android binding of libtorrent 2.x;
- `AndroidArchivePublisherStore` persists the publisher seed sealed with the R21
  `AndroidLocalSecretBox`, the same local domain that already protects the history-vault recovery
  secret;
- `JcaArchivePublisher` is generalised to `DevicePrivateCrypto` and gains `restore(seed)`;
- `MessengerOverTorrentTest` + `tools/messenger-over-torrent.sh` carry an eidogram from one device
  to another: the sender archives it into an R16 segment, seeds it and signs a head; the receiver
  fetches the segment over a real BitTorrent connection, admits it and reads back the identical
  MessageId and document hash. The small control plane moves as files because the two devices share
  no DHT; the payload does not.

### Two deviations from the R17 design

**Puts are signed by libtorrent, not pre-signed.** The binding exposes only
`dht_put_item(pk, sk, entry, salt)`; the signing-callback form that `LibtorrentNative.dhtPutPreSigned`
was written for is not available. The transport is therefore given the publisher's Ed25519 seed.
The result is wire-identical — libtorrent signs the standard BEP44 signable buffer with the same
key — and the retrieved head passes `ConversationHeadCodec.verify` unchanged, which the device test
asserts. The key was never hardware-backed: it is a JCA key that R22 already exposed as a public
field.

**`getHead` needs the key, not the target.** `target = SHA1(pk || salt)` is not invertible and BEP44
gets are addressed by `(pk, salt)`. Callers register pairs with `knowHead`, normally straight from a
publisher certificate.

## 4. Dependency pin

`R17_DEPENDENCY_PIN.md` pins libtorrent-rasterbar **2.1.1** and `ENABLE_NATIVE_LIBTORRENT.md`
describes vendoring it and cross-compiling the project's own JNI with the NDK. R22.1 ships the
prebuilt **2.1.0** binding instead. This was a deliberate decision: it is the same 2.x line, it
carries BEP52 v2 support, and it removed a multi-hour cross-compilation from the critical path.

The consequence is stated plainly: `native-torrent`, `LibtorrentNative` and
`eidolang_libtorrent_jni.cpp` remain unbuilt, and `ENABLE_NATIVE_LIBTORRENT.md` is NOT discharged.

## 5. Ed25519 is not available on Android below API 35

Delivering a message to a second device failed at `NetworkAdmissionGate` with `invalid BEP44 head`.
The same head verified on the host JVM, so the artifacts were sound. `ConversationHeadCodec.verify`
wraps everything in `runCatching`, which hid the cause:

```
Ed25519 KeyFactory not available
```

Enumerating providers on an API 34 image shows exactly one Ed25519 service —
`AndroidKeyStoreBCWorkaround Signature.Ed25519` — and it rejects any key that is not
keystore-backed, so even a hand-built X.509 `PublicKey` fails with `No installed provider supports
this key`. There is no general-purpose Ed25519 `KeyFactory` or `KeyPairGenerator`.

The project declares `minSdk = 26`, so the entire BEP44 head layer — publishing and verification —
was inoperable on every Android below 15. It worked on the API 35 phone, which is why nothing
before this caught it.

R22.1 moves Ed25519 off JCA onto Bouncy Castle's low-level signer, which needs no provider
registration and works from minSdk 26. `Ed25519Raw` now offers `publicFromSeed`, `sign` and
`verify`; `JcaArchivePublisher` holds a 32-byte seed instead of a JCA `KeyPair`. The golden R16
head still verifies, and the R17 reference and R22 product journey both still pass, as they must:
the wire format did not change.

This also removed the awkward part of the publisher store — JCA cannot derive an Ed25519 public
key from a seed, so the store had been persisting `seed || public`. Bouncy Castle derives it, so
only the seed is kept.

## 6. Test isolation

The instrumentation suite is split. Classes that start libtorrent sessions carry
`@NativeSessionHeavy` and run as a second invocation. In one process they sort before the Compose
UI tests and the process dies partway with no assertion message, while every class passes alone.
The cause is native thread release after `SessionManager.stop()`, not a product defect, but it is
recorded here because a suite that dies at 6 of 13 is easy to misread as one.

## Claim boundary

R22.1 does not claim:

- the project's own JNI backend, or any build of pinned libtorrent 2.1.1;
- R17.1 items 8 and 9 — session/DHT state save-restore, process restart, WorkManager;
- any transport UI: no screen publishes or fetches, the transport is reachable only from code;
- operation over the public internet — every swarm and DHT run in this stage is loopback with
  public bootstrap disabled;
- StrongBox: nothing requests `setIsStrongBoxBacked`, so keys sit in the TEE.
