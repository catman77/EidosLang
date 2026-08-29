# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository shape — read this first

This is **not a working source checkout**. It is not a git repository. The entire repo is
`docs/`, containing:

- **5 Markdown design/theory documents** (Russian) — the normative specification.
- **~40 `.zip` release artifacts** — each zip is a complete, immutable snapshot of one numbered
  release (`R1` … `R22`). Source code exists only inside these zips.

**This changed on 2026-08-29: the Android line is now ordinary tracked source in `android/`**, and
the repository is a git checkout pushed to `github.com/catman77/EidosLang`. Edit it in place and
commit; the patch file that used to carry uncommitted work is superseded by history. The zips in
`docs/` remain the frozen record of each release and the source for the Python line:

```bash
mkdir -p work && unzip -q docs/EidoLang-Mobile_v0.1-R12.zip -d work   # → work/eidolang_mobile_v0_1_r12/
```

**Never put anything under `/tmp`** — not the extracted tree, not build outputs, not jars, not
scratch scripts. The owner has forbidden it outright, and the reason is not tidiness: `/tmp` is
cleared on reboot, and a clear-out has already destroyed a working tree, its APKs and the Gradle
distribution mid-task, costing a full re-extract, re-patch and a 140 MB re-download. Everything
durable lives in this repository:

| What | Where |
|---|---|
| Android source (tracked) | `android/` |
| Gradle 9.5.0 distribution | `toolchain/gradle-9.5.0/bin/gradle` |
| conformance jars, scratch scripts | `work/out/` |
| build's own temp files | `work/.tmp` — export `TMPDIR` and `-Djava.io.tmpdir` at it |

Uncommitted work in progress is kept as a patch against the pristine zip
(`r22-to-r22.1-build-admission.patch`, applied with `patch -p2` from inside the extracted tree).
Regenerate it after every change — it is the only copy of anything not yet cut into a release zip.

Never assume a zip's content from its name — list it (`unzip -l`) before deciding which one holds
what you need. Deliverables are produced as a **new** `R{N+1}` zip; prior releases are never
edited in place.

## Two release lines

| Line | Zips | Language | Purpose |
|---|---|---|---|
| **EidoLang-Mobile v0.1-R1 … R13.1** | `EidoLang-Mobile_v0.1-R*.zip` | Python (`src/eidolang/`, pytest) | Research core: does an eidographic gesture *cause* a state change, or merely correlate? |
| **EidoLang-Android R14 … R22** | `EidoLang-Android-R*.zip` | Kotlin / Gradle / Jetpack Compose | Serverless P2P eidogram messenger built on a frozen glyph catalog. |

`R13`/`R13.1` is the pivot: it freezes `EidoGlyphCatalogV1` (113 glyphs) and hands off from the
statistical research pipeline to the Android product. **R22 is current**; next stage is
`R22.1 — Android device admission` (an *execution* stage, explicitly not a new protocol-design
stage).

## Commands

### Android line (R14–R22) — pure-JVM conformance is the real test gate

Every `core-*` module is written so that only the `Android*.kt` files touch the Android SDK
(`AndroidKeystoreIdentityStore`, `AndroidSqliteMessengerRepository`, `AndroidLocalSecretBox`,
`AndroidAdmissionProbe`, `GlyphRenderer`, …). **Everything else is pure Kotlin + JCA.** That is
deliberate: it lets the whole protocol stack be compiled with a standalone `kotlinc` and executed
on the JVM without an Android SDK.

The harnesses live in `tools/r{N}-conformance/`, each with a `main()` that prints `PASS <check>`
lines and a final `ALL … CHECKS PASS`. Compile the pure core sources plus one harness and run it:

```bash
# from inside android/
export JAVA_HOME=/opt/android-studio/jbr
KOTLINC=/opt/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc
SRC=$(grep -rL "^import android" core-*/src/main --include=*.kt | sort)   # 52 pure files at R22

$KOTLINC $SRC tools/r22-conformance/R22ProductJourney.kt -include-runtime -d work/out/r22.jar
java -jar work/out/r22.jar    # → the 11 PASS lines in tools/r22-conformance/RUN_LOG_R22.txt
```

Select the pure sources with `grep -rL "^import android"`, **not** by filename. Most
Android-coupled files are named `Android*.kt`, but `core-render/GlyphRenderer.kt` is not — a
name-based filter pulls it in and the compile fails on missing Compose classes.

Harnesses that verify golden vectors need their `Golden*.kt` data files passed explicitly:

```bash
$KOTLINC $SRC tools/r20_2-conformance/GoldenR20_2.kt tools/r21-conformance/GoldenR21.kt \
         tools/r22-conformance/R22FixedVerify.kt -include-runtime -d work/out/r22fixed.jar
java -jar work/out/r22fixed.jar  # → 5 PASS, reproducing the exact stored golden hashes
```

Two harness flavours, and the difference matters when reading output:

- **Dynamic** (`R22ProductJourney.kt`, `R21Main.kt`, `R20_2Main.kt`, …) generate fresh keys from
  `SecureRandom`, so trailing `FINAL_PACKAGE_ID` / `FINAL_HEAD` / `CUTOVER_ID` values differ on
  every run and will *not* match the recorded `RUN_LOG_*.txt`. Only the `PASS` lines are the
  contract.
- **Fixed** (`*FixedVerify.kt`, `*GoldenVerify.kt`) replay frozen vectors and must reproduce the
  stored hashes byte-for-byte. A mismatch here is a real regression.

Substitute `tools/r21-conformance/R21Main.kt`, `R20_2Main.kt`, etc. to run a single stage. The
exact source list used for a past run is in files like `tools/r20_2-conformance/core_sources.txt`
(paths there are the original `/mnt/data/...` build container); expected output is in the
adjacent `RUN_LOG_*.txt`.

Independent cross-implementation verifiers (Python, deliberately written against the golden JSON
rather than the Kotlin code, so a shared bug cannot pass both):

```bash
python3 tools/python/verify_r16_bep52.py golden/r16   # BEP52 piece tree — takes the golden dir as argv[1]
python3 tools/python/verify_r16_bep44.py golden/r16   # BEP44 DHT mutable head signing
python3 tools/python/verify_r20_1_forward_session.py   # these three locate golden/ themselves
python3 tools/python/verify_r20_2_history_vault.py
python3 tools/python/verify_r21_hardening.py
python3 tools/r22-conformance/android_source_audit.py  # static audit of the Android UI/source layer
```

The two `verify_r16_*` scripts require the golden directory as an argument; the rest resolve it
from `__file__`. All of them need `cryptography` installed.

Android build + on-device admission — the R22.1 gate:

```bash
export ANDROID_HOME=~/Android/Sdk JAVA_HOME=/opt/android-studio/jbr
tools/r22-android-admission.sh    # :app:assembleDebug, :app:connectedDebugAndroidTest, adb install
```

`gradle-wrapper.jar` is intentionally not vendored, so `./gradlew` exits 2 with instructions.
Bootstrapping it is itself blocked: `gradle wrapper` under the locally cached Gradle 9.1.0 fails
with `NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding`, because evaluating
the build already requires AGP 9.3.0, which requires Gradle 9.5.0. Download the 9.5.0 distribution
directly and drive the build with its `bin/gradle`, or open the project in Android Studio.

### The R22 tree does not compile as shipped — five blockers

`:app:assembleDebug` **has now been run** and produced `app-debug.apk` (12.7 MB,
`org.eidolang.app` 0.1-R22, minSdk 26 / targetSdk 37) — but only after five fixes. R22's own
`R22_VALIDATION.md` reports 180 PASS while listing `Android build: NOT RUN`; that gap is exactly
where these hid. In dependency order:

1. **`gradle.properties: android.builtInKotlin=false`** — the tree deliberately opts out of AGP's
   built-in Kotlin to pin Compose to Kotlin 2.3.21. AGP 9.3.0 also defaults `android.newDsl=true`,
   and the standalone `org.jetbrains.kotlin.android` plugin refuses to apply alongside it. Setting
   `android.newDsl=false` only moves the failure: the old `android { }` / `kotlinOptions { }` DSL
   is deprecated, and Kotlin DSL script compilation treats that as an error. The pin combination
   has no working configuration — you must migrate to built-in Kotlin: drop
   `android.builtInKotlin=false`, remove `alias(libs.plugins.kotlin.android)` from all 23 module
   build files, and delete every `kotlinOptions { jvmTarget = "17" }` block (`compileOptions`
   alone is sufficient). `libs.plugins.compose.compiler` stays.
2. **`native-torrent` namespace** — `org.eidolang.native.torrent` is rejected because `native` is
   a Java keyword. The Kotlin source package is legal, so only the AGP `namespace` needs changing
   (e.g. `org.eidolang.nativetorrent`); it does not have to match the source package.
3. **`core-vault` is missing `implementation(project(":core-archive"))`** — `LegacyMigration.kt`
   does `import org.eidolang.core.archive.*`. It reaches `core-archive` only transitively through
   `core-recovery`, which uses `implementation`, so it is not exposed.
4. **`feature-archive` is missing `implementation(project(":core-multidevice"))`** —
   `ArchiveScreen.kt` never imports the package, but resolves `UserDeviceRosterV1` out of a
   `core-vault` return type, so the class must still be on its compile classpath.
5. **A real product bug**: `feature-archive/…/ArchiveScreen.kt:394` had
   `onClick={performSecureRestore}` — a bare function reference where a call is required. This is
   the `Проверить и восстановить` button, i.e. the primary secure-restore action of the R22
   product path. Line 401 calls the same function correctly. Fix: `onClick={performSecureRestore()}`.

**Why the existing suites all missed these.** The pure-JVM harness passes every `core-*` source to
one `kotlinc` invocation, so the classpath is flat and module boundaries are never exercised —
#3 and #4 are structurally invisible to it. And `android_source_audit.py` is substring matching
(`"HistoryVaultRecoveryCoordinator" in archive`), which passes happily on code that does not
compile — it reported 13 PASS over the broken button in #5. Treat "180 PASS" as a statement about
the protocol core, never about the Android product layer.

### Runtime admission has now been observed — with two more fixes

Booting an AVD from the `android-34` image closes the R22.1 gate. `/dev/kvm` is world-writable
here, so acceleration works without group membership:

```bash
avdmanager create avd -n eidolang_r22_api34 -k "system-images;android-34;google_apis;x86_64" -d pixel_6
emulator -avd eidolang_r22_api34 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device && adb shell getprop sys.boot_completed
```

`:app:connectedDebugAndroidTest` then runs. Two further defects surfaced, both invisible to every
pre-existing suite:

6. **`MessengerSmokeTest.secureArchiveRecoveryControlsAreVisible` asserted a UI that does not
   exist.** `onNodeWithText("Recovery backup")` demands exactly one match, but `ArchiveScreen.kt`
   has two buttons with that exact label — one creating a backup (line ~351, Secure export) and
   one importing it (line ~378, Secure restore). The test was written against an imagined screen
   and never executed. Use `onAllNodesWithText(...).onFirst()`, or add `testTag`s to disambiguate.
7. **SQLite v3 sensitive-text protection could never store a single row.**
   `AndroidLocalSecretBox.validate` restricts `logicalId` to `[A-Za-z0-9._-]{1,160}` because `|`
   is its AAD delimiter — but `AndroidSqliteMessengerRepository` passed `"$userId|$deviceId"` for
   contact aliases and a raw `conversationId` for titles, and identities are formatted `dev:<hex>`.
   Both the pipe and the colon violate the contract, so *every* `putContact`/`putConversation`
   threw `IllegalArgumentException`. Fix: derive a delimiter-safe id by hashing
   (`HexSha256.of("$userId|$deviceId")`), which keeps the ciphertext bound to the logical row.
   All six protect/unprotect call sites, including the v2→v3 migration path, must agree.

**Why #7 hid so well, and the lesson for writing new checks.** `repository.sensitive_text` audits
*stored* rows, so on an empty database it passes over zero rows and proves nothing. The probe was
therefore green while the feature was totally broken. Any admission run must seed a contact and a
conversation first and assert `audit.contactRows > 0 && audit.conversationRows > 0` before
trusting that check.

With #1–#7 applied, on emulator API 34 / x86_64: `:app:assembleDebug` succeeds,
`:app:connectedDebugAndroidTest` reports **3/3 passing**, and the probe reports
`AndroidAdmissionReport.passed = true` over `contacts=1 conversations=1` — all six of
`identity.bundle`, `identity.sign`, `identity.rsa_oaep`, `local.secret_box`, `vault.secret_store`,
`repository.sensitive_text`. That is the condition `R22_ANDROID_ADMISSION.md` defines as the gate.
None of the fixes touch a pure `core-*` source, so the 180-PASS protocol regression is unaffected
(re-verified after the change).

### v2→v3 migration coverage, and mutation-testing your own tests

`SqliteV3MigrationTest` (added, `app/src/androidTest/`) builds a **real legacy database by hand** —
the pre-R22 DDL, plaintext aliases/titles, `user_version` set to 1 or 2 — then opens
`AndroidSqliteMessengerRepository` to trigger `onUpgrade`. It covers the four claims in
`R22_SQLITE_V3.md` that nothing else reaches: every row is encrypted in place, `v1` additionally
gains the R20 tables, reads fail closed on a surviving plaintext field, and cross-row ciphertext
substitution fails authentication. Round-tripping asserts the exact original UTF-8 plaintext
(Cyrillic included) comes back out. All 7 instrumentation tests pass.

Two things worth copying from how it was validated:

- **Mutation-test the test.** Everything passed on the first run, which given this codebase is a
  warning sign, not a result. Reverting the #7 fix killed all four migration tests plus admission;
  stubbing `migrateSensitiveTextToV3` to a no-op killed the two that assert migration happened.
  That is what makes the green run mean something.
- **The second mutation exposed a flaw in the new test itself.** With migration stubbed out,
  `migratedRowsRejectCrossRowCiphertextSubstitution` still passed — it caught `Exception` broadly,
  so a fail-closed *plaintext* error looked like the AAD authentication failure it claims to test.
  It now asserts the row is actually `enc1:`-encrypted before substituting. Any test whose
  assertion is "some exception was thrown" deserves this suspicion.

**Documentation drift to resolve.** `R22_SQLITE_V3.md` still specifies the AAD domains as
`logicalId = userId|deviceId` and `logicalId = conversationId`. That is precisely the contract
violation in #7, so the doc now describes something that cannot work; it must be updated to the
hashed form alongside any R22.1 release.

### Physical-device admission, and a feature flag that lies

Run on a real phone (Samsung SM-G996N, Android 15 / SDK 35, arm64-v8a, verified boot `green`):
**8/8 instrumentation tests pass and the probe reports `ADMISSION PASS`** for all six checks.
`connectedDebugAndroidTest` uninstalls both APKs afterwards, so it leaves no trace on the device.
If `adb devices` is empty with the phone plugged in, `adb kill-server && adb start-server` is
usually enough — `lsusb` showing `Samsung … (MTP mode)` confirms the cable and phone are fine.

`KeystoreSecurityLevelTest` (added) checks the only property a physical device establishes that an
emulator cannot: that private key material is really in secure hardware. It inspects
`KeyInfo.getSecurityLevel()` for all five aliases — `eidolang.root-signing.v1`,
`eidolang.device-signing.v1`, `eidolang.device-decrypt.v1`, `eidolang.local-sensitive-wrap.v1`,
`eidolang.history-vault-secret-wrap.v1`. On the phone every one is `TRUSTED_ENVIRONMENT`; on the
`android-34` emulator every one is `SOFTWARE`.

**Do not gate hardware assertions on `PackageManager.FEATURE_HARDWARE_KEYSTORE`.** The emulator
advertises it (`=300`) and still produces `SECURITY_LEVEL_SOFTWARE` for every key — the flag was
the test's original guard, and it made the test fail on the emulator for a reason that had nothing
to do with the code under test. Only `KeyInfo.getSecurityLevel()` is trustworthy; the guard is now
emulator detection via `Build.HARDWARE in {ranchu, goldfish}`.

Note that nothing in the codebase requests `setIsStrongBoxBacked(true)` or user authentication,
even though this device exposes `android.hardware.strongbox_keystore`. Root/device keys therefore
sit in the TEE, not the secure element — a deliberate-looking gap worth confirming with the author
before any R22.1 write-up claims StrongBox.

### Two-device secondary enrollment (phone ↔ emulator)

`tools/two-device-enrollment.sh` (added) drives `TwoDeviceEnrollmentTest` across two genuinely
independent installs — the protocol is file-based, so the driver alternates devices and carries
each artifact between them:

```bash
PRIMARY=<phone-serial> SECONDARY=emulator-5554 tools/two-device-enrollment.sh --install
```

All six steps pass. Enrollment (1–4): the primary creates its root and epoch-0 roster and exports
the owner identity; the secondary generates local device keys and a possession-signed enrollment
request; the primary root-authorizes it and issues the epoch-1 roster listing both devices; the
secondary installs the authorization, imports the roster, and **opens a secret the primary wrapped
to its freshly authorized RSA key** — only that device's non-exportable AndroidKeyStore private
key can do it.

History transfer (5–6): the primary authors an eidogram, archives it into an R20.2 history vault
and exports the vault package plus a two-channel recovery backup and code. The secondary restores
from that pair alone and reads back **the identical MessageId and document content hash, with zero
message-key grants** — R22 product-journey steps 4–7, executed across two independent installs
instead of inside one JVM. Both devices belong to the same user and `createConversation` demands a
remote participant, so the conversation partner is generated inside step 5; the partner is
scaffolding, the vault round-trip is the claim. Negative control: corrupting one character of the
recovery code makes step 6 fail.

Practicalities that cost time here:

- **`run-as <pkg> sh -c '...'` silently reports the wrong thing.** `adb shell run-as org.eidolang.app
  sh -c 'ls -la files/contact-publishers'` printed the *data-directory root* instead of that path,
  with no error, and reading it as "the directory does not exist" produced a confident and wrong
  diagnosis of a delivery bug. The direct form — `run-as <pkg> ls -la files/<path>` — is accurate.
  Same lesson as `uiautomator dump` against Compose: verify a claim with an instrument that is
  actually measuring it.
- Artifacts move via `run-as` + base64, not `adb push/pull` — `/sdcard/Android/data/<pkg>` is not
  reliably reachable over adb on Android 11+, Samsung especially.
- Physical USB re-enumerates mid-run (the serial stays, `transport_id` changes). The driver wraps
  every device operation in `wait-for-device` and retries installs, pulls and the instrumentation
  call itself; without that, runs fail randomly at ~1 in 3. Note the interaction with `set -e`:
  `out="$(adb shell am instrument ...)"` aborts the whole script *silently* when adb exits
  non-zero, so that substitution needs `|| true` plus an explicit check, or a transport glitch
  looks like the run simply stopping after a banner.
- `LocalMessengerService.importDeviceRoster` walks `DeviceRosterVerifier.advance`, which requires
  the repository's roster chain to start at epoch 0 (`previous == null` ⇒ `epoch == 0` and
  `previousRosterId == null`) and requires every referenced device to already be a known bundle.
  So each stage must import its roster as the product does — epoch 0 at first run, then
  `importOwnDevice` for the authorized certificate before importing epoch 1. Handing a fresh
  repository the epoch-1 roster fails, which is what `importDeviceRosterSnapshot` exists for on
  the secondary.
- `TwoDeviceEnrollmentTest` is marked `@TwoDeviceOnly` and excluded from
  `connectedDebugAndroidTest` via `testInstrumentationRunnerArguments["notAnnotation"]`, because
  its steps are meaningless (and failing) on a single device.

**`SqliteV3MigrationTest` builds its legacy database at the production path**, so its
`deleteDatabase` calls destroy a real installation's contacts, conversations and messages. It wiped
a user's tablet during a routine regression run, and the missing rows then presented as a delivery
bug — the sender published, the recipient had no contact left to poll, and nothing said a database
had been deleted. It now parks the device's database in `cacheDir` and restores it in `@After`;
`MigrationTestIsNonDestructiveTest` asserts that guard and dies without it. Treat any test that
touches `eidolang-messenger-r18.db` on a real device as destructive until proven otherwise, and
remember that `AndroidAdmissionTest` additionally *seeds* a contact and a conversation that then
appear in the owner's list as a phantom.

**Tests that seed contacts must remove them.** `RelayLockerTest` imported a stand-in peer named
`Локер` on every run and left it there; seven of them accumulated in a real address book, alongside
`Пир` from the two-device suite and the synthetic contact `AndroidAdmissionTest` seeds so the
admission probe is not auditing an empty table. They are unreadable names that can never deliver —
`receiveFrom` refuses a contact with no publisher certificate — and each one cost a directory
round trip on every poll. `PhantomContactCleanupTest` removes exactly those (no publisher
certificate *and* no `#` handle, so nothing introduced through the product can match) and
`MessengerService.deleteContact` now exists for it; contacts used to be append-only at every level,
with no way to remove one from the UI either.

**Do not run the single-device suite on a device left in secondary state.** Gradle installs over
the existing app without clearing data, and `AndroidAdmissionTest` calls
`AndroidKeystoreIdentityStore.ensure()`, which adds a primary root next to the secondary bundle.
`EidoRootApp` then correctly renders `Конфликт локальной identity` and returns, so every UI test
fails with no obvious cause. That screen is intended R22 behaviour, not a bug. Re-install with
`--install` (which uninstalls first) before switching a device between roles.

Two measurement lessons from this run:

- **`uiautomator dump` is the wrong instrument for Compose.** It never showed the `Эйдограммы`
  TopAppBar title in *any* state because the title's semantics are merged, which sent me chasing a
  non-existent UI regression. Compose's own `onNodeWithText` is the only reliable probe; verify a
  UI claim with the same instrument that made it.
- **`am instrument` reports `OK (0 tests)` for a misspelled method**, and the driver's original
  pass check matched on `Time:`, so a typo would have been reported as a passing step. It now
  requires `^OK \(1 test\)$`; a deliberately bogus method name is the negative control.

### Real BitTorrent and DHT (R17.1, host-side)

`R17Main` proves admission over `ReferenceTransportNetwork`, which its own comment calls "NOT
BitTorrent". Two host scripts replace that double with a real implementation
(`libtorrent-rasterbar` **2.1.0** from the distro; note `R17_DEPENDENCY_PIN.md` pins **2.1.1**).
Both are strictly local — DHT/LSD/UPnP/trackers off for the swarm, empty `dht_bootstrap_nodes` for
the DHT — so nothing is published to the public network.

```bash
/usr/bin/python3 tools/r17-real-swarm.py        # A seeds, B fetches by magnet only
kotlinc <pure core> tools/r17-real-conformance/R17RealAdmissionMain.kt ...   # admission over the
java -jar ... golden/r16 work/out/r17-swarm/b/<name>                            # downloaded bytes
/usr/bin/python3 tools/r17-real-dht.py          # BEP44 put/get on a private 8-node DHT
```

Covered of the nine `R17_NATIVE_ADMISSION.md` items: **2–5 and 7**. Two independent sessions; the
golden segment seeded from A; B fetches by v2 magnet with no shared files, pulling metadata over
BEP9 and data over BEP52; the bytes libtorrent wrote to disk re-derive the exact R16 `segment_id`
and v2 infohash under `NetworkAdmissionGate`; a post-completion single-bit flip is rejected, and
restoring that byte restores admission. Item **6 partially**: the golden head value survives a real
BEP44 put/get between different nodes byte-identically and `SHA1(k||salt)` matches the golden
target, but the golden artifacts carry the publisher's Ed25519 *public* key only, so the project's
own signed item cannot be republished — the round trip uses a fresh key.

### On-device BitTorrent without building anything — use libtorrent4j

`ENABLE_NATIVE_LIBTORRENT.md` describes vendoring upstream libtorrent 2.1.1 and cross-compiling it
with the NDK. **Do not start there.** A prebuilt Android binding of libtorrent 2.x already exists
on Maven Central and covers every ABI:

```
org.libtorrent4j:libtorrent4j:2.1.0-35
org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35     # + -android-x86_64, -arm, -x86
```

`LibtorrentOnDeviceTest` wires it as an **androidTest-only** dependency — the shipped app gains no
new runtime dependency — and runs two libtorrent sessions inside the app process on the phone: A
seeds the golden R16 segment, B starts from the v2 magnet alone, pulls metadata over BEP9 and data
over BEP52, and the bytes B wrote to device storage go through the production
`NetworkAdmissionGate`. On the Galaxy S21+ all eight checks pass, including
`transferred bytes reproduce the exact R16 segment_id` and post-completion corruption rejection.
Assert `TorrentStatus.totalPayloadDownload() > 0` (it reports 147456) — without it, "B is seeding"
would also be true if the files had somehow been local, and `numPeers()` already reads 0 by the
time the transfer finishes.

API notes that cost time: the `InfoHash` wrapper exposes `hasV2()`/`getV1()` but **no `getV2()`**,
so the v2 infohash needs `infoHashes().swig().v2.to_hex()`; and `TorrentHandle` has no
`connectPeer`, so use `handle.swig().connect_peer(TcpEndpoint(host, port).swig())`.

This does **not** discharge `ENABLE_NATIVE_LIBTORRENT.md`: it is a different binding, not the
project's own `native-torrent`/`eidolang_torrent` JNI, and it is libtorrent 2.1.0 rather than the
pinned 2.1.1. What it establishes is that the R16 wire format survives a real BitTorrent v2 stack
on real hardware.

`DhtOnDeviceTest` closes item **6** the same way: eight libtorrent sessions form a private DHT on
the phone (public bootstrap empty, anti-Sybil settings lifted), and `ConversationHeadCodec`'s own
canonical encoding of the golden head is published as a real BEP44 mutable item and retrieved from
a *different* node byte-identical (310 bytes, published to 7 nodes). The Ed25519 key must be the
clamped SHA-512 expansion of the seed, not `seed || public` — the same orlp/ed25519 trap as on the
host. Android's `KeyPairGenerator.getInstance("Ed25519")` (API 33+) gives a consistent pair; the
raw seed and public key are the trailing 32 bytes of the fixed-size DER encodings. Assert
`DhtPutAlert.swig().num_success > 0`: the Java wrapper does not expose it, and a put that reached
nobody otherwise looks like a success.

Still open: the project's own JNI backend built against pinned 2.1.1 (`ENABLE_NATIVE_LIBTORRENT.md`
steps 1–4), and R17.1 items **8 and 9** — session/DHT state save-restore and process-restart /
WorkManager behaviour.

**UI instrumentation needs the device awake and unlocked.** With the phone dozing
(`dumpsys power | grep mWakefulness` reads `Dozing`, `mDreamingLockscreen=true`) the two
`MessengerSmokeTest` cases fail with `No compose hierarchies found in the app` because the Activity
never reaches the foreground. Everything that does not touch the UI — admission, keystore level,
SQLite migration, swarm and DHT — passes regardless. Check wakefulness before reading a UI failure
as a code defect.

Four traps, all of which produce a *passing-looking* run if assertions are loose:

- **libtorrent's 64-byte secret key is not `seed || public`.** It bundles orlp/ed25519, whose
  secret key is the clamped SHA-512 expansion of the seed. The NaCl layout passes the length check
  and is then rejected by every storing node with `(206) invalid signature`, visible only in
  `dht_log_alert`.
- **`dht_mutable_item_alert.item` is already the raw bencoded value.** Bencoding it again wraps it
  in a length prefix, so a byte comparison fails for a reason unrelated to the DHT.
- **A DHT miss also raises `dht_mutable_item_alert`** — with `authoritative=True` and an empty
  item. And `dht_put_alert.num_success == 0` means the item reached nobody. Assert on both.
- **A loopback DHT will not form.** libtorrent's anti-Sybil defaults (`dht_restrict_routing_ips`,
  `dht_enforce_node_id`, `dht_ignore_dark_internet`) must be off, and a two-node DHT never
  finishes bootstrapping — use ~8 nodes and wait for a non-empty routing table.

Build pins (`gradle/libs.versions.toml`, frozen since R14): AGP 9.3.0, Kotlin/Compose-compiler
2.3.21, Compose BOM 2026.06.00, Gradle 9.5.0, compileSdk/targetSdk 37, minSdk 26, Java 17.

### Mobile line (R1–R13) — Python

```bash
pip install -e .          # setuptools, src/ layout, requires numpy
pytest                    # full suite
pytest tests/test_r12.py  # one stage
eidolang analyze session.json | discover *.json | r3-synthetic | topology | multilayer
```

### Toolchain on this machine

Nothing Android-related is on `PATH`, but everything is installed — use the absolute paths:

| Tool | Path / version |
|---|---|
| Android SDK | `~/Android/Sdk` (`ANDROID_HOME` is **unset**; export it yourself) |
| platforms | `android-34`, `android-36`, `android-36.1`, `android-37.0` |
| build-tools | `35.0.0`, `36.0.0`, `36.1.0`, `37.0.0` |
| NDK / cmake | `28.2.13676358` / present (for `native-torrent`) |
| system images | `android-27`, `android-34` (emulator present) |
| Android Studio | `/opt/android-studio` (build `AI-261.23567.138.2611.15646644`) |
| bundled JDK | `/opt/android-studio/jbr` — JBR 21.0.10 |
| bundled kotlinc | `/opt/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc` — **2.3.10** |
| Gradle dists | `~/.gradle/wrapper/dists/` — `9.1.0-all`, `8.14.3-bin` |
| system JDK | `java 17` on `PATH`; `adb` 1.0.41, `sqlite3`, `python3.13` + `cryptography` |

Two consequences:

1. **The pure-JVM conformance suite is fully runnable here** with the bundled `kotlinc` 2.3.10 —
   close enough to the pinned 2.3.21 to compile the `core-*` sources. This is the primary gate,
   so verify changes with it rather than assuming.
2. **The Gradle Android build now works, but needs a Gradle 9.5.0 you must supply.** `android-37.0`
   is installed (`compileSdk = 37` maps to it — the repo has no bare `platforms;android-37`, only
   `37.0`/`37.1`/`37.2-beta*`); AGP 9.3.0 and Kotlin 2.3.21 download fine. Only the Gradle
   distribution is missing locally — fetch
   `https://services.gradle.org/distributions/gradle-9.5.0-bin.zip` (140 MB) and run its
   `bin/gradle` directly. See the five source fixes below; without them the build fails.

Any claim requiring a tool you did not actually run must be reported as `NOT RUN`, never silently
skipped — see the release discipline below.

## Release discipline (the most important convention here)

Each release `R{N}` closes exactly one question and ships a fixed document set at the tree root:

- `R{N}_METHODOLOGY.md` — what the stage does and *why the previous stage forced it*.
- `R{N}_CHECKPOINT.md` — status line, what was added, PASS totals, explicit "Not executed", "Next".
- `R{N}_VALIDATION.md` — every executed check, with counts.
- `TEST_LOG_R{N}_SUMMARY.txt` — machine-ish tally of every suite and its PASS count.
- `EXAMPLE_R{N}_VALIDATION_REPORT.json` — same facts as structured JSON, including a
  `claim_boundary` object of explicit `false` flags and an `environment` object.
- Topic docs as needed (`R15_SECURITY_MODEL.md`, `R18_DATABASE_SCHEMA.md`, `R22_SQLITE_V3.md`, …).
- `golden/r{N}/` — frozen golden vectors, hashed; `schemas/*.schema.json` for each wire type.

Rules that hold across every release and must be preserved:

1. **Full regression, every time.** `R{N}` re-runs *all* prior conformance harnesses on the final
   `R{N}` tree and reports the subtotal (R22: 151 prior + 29 new = 180 PASS). A new release may
   not invalidate an earlier release's canonical bytes or golden hashes.
2. **Claim boundaries are stated, never hidden.** Docs enumerate what was *not* executed and what
   is *not* claimed ("Android compilation", "real BitTorrent/DHT execution", "full SQLite
   page-level encryption", "proof that an external legacy copy was deleted"). A source audit is
   labelled a source audit, not a runtime claim. Follow this tone exactly — do not upgrade a
   static check into a runtime guarantee.
3. **No-go documents are first-class.** When a stage proves something *impossible*, it ships that
   proof and it forces the next stage (`R20_1_ARCHIVE_NO_GO.md` → R20.2;
   `R21_FRESHNESS_NO_GO.md` → the user-visible `FRESHNESS UNPROVEN` state in R22).
4. **No new wire entity without a counterexample.** R22 states the rule as `NewWireEntity = 0`
   unless a runtime counterexample proves one necessary. Prefer composing existing layers.
5. **Exact tests or `UNRESOLVED`.** The Python line refuses approximate p-values: if a sample
   exceeds the exact-enumeration budget the verdict is `UNRESOLVED`, never a pseudo-p-value.

## Android architecture (R22)

Gradle modules in `settings.gradle.kts`, layered strictly bottom-up:

```
core-canonical   strict/canonical JSON, SHA-256   — Parse(bytes)=doc ⟺ Serialize(doc)=bytes
core-model       EidogramDocumentV1, editor reducer, hit-testing, frozen glyph catalog asset
core-render      Compose glyph drawing
core-crypto      user root + device identities (ECDSA P-256, RSA-2048-OAEP), AndroidKeyStore
core-message     EidogramMessageV1 — AES-256-GCM payload, per-recipient key wrap, signed AAD
core-session     R20.1 forward secrecy: prekey bootstrap, DH ratchet, out-of-order handling
core-multidevice enrollment, root-signed monotone device rosters, revocation, key grants
core-vault       R20.2 history vault: independent recovery secret, per-epoch keys
core-recovery    archive coordinator, multi-device recovery, R19 archive package
core-archive     BitTorrent-v2 segments (BEP52), bencode, mutable DHT heads (BEP44)
core-transport   transport abstraction + admission gate;  native-torrent = libtorrent JNI scaffold
core-repository  contacts/conversations/encrypted message DAG; SQLite v3 + in-memory adapters
core-hardening   rollback anchors, recovery backup, legacy cutover, local secret box
core-admission   on-device runtime probe surfaced in the Diagnostics screen
feature-*        onboarding, messenger, editor, viewer, library, archive, sync  (Compose UI)
app              MainActivity → EidoRootApp (first-run primary/secondary routing)
```

Invariants enforced by the conformance suites — violating any of these breaks golden hashes:

- **Canonical round-trip is byte-exact.** No float exponents, no duplicate keys, no extra fields,
  fixed field ordering. Non-canonical input is `INVALID`, never a warning.
- **Frozen ids, growing catalogue.** `EidoGlyphCatalogV1` now holds 791 glyphs — sticks gained the
  full palette (10 colours x 8 poses x 3 lengths x 3 thicknesses = 720), and every id it ever
  shipped is still present and unchanged. `catalog_hash` is therefore
  `f5fef90fd3dc9fd58227b30e5b1d66e0004d385e42f0026a452ee2857f18d10d`, was
  `da2d3b72ff8d41abb8c293ce1deca44e99cb1d1a696e9834997395c6f6218005`, and is now **derived** from
  the catalogue's contents by `EidoGlyphCatalogV1.contentHash()` rather than maintained by hand —
  `CatalogTest` fails if the two drift.

  Two things made that survivable, and both are load-bearing. `catalog_id` stays
  `EidoGlyphCatalogV1`: the parser checks it strictly, so moving it would reject every document ever
  written. And the hash is now *carried* by each message (`MessageAadV1.catalogHash`) instead of
  being read from the constant when the AAD is built — the AAD is covered by the GCM tag and the
  sender's signature, so a constant meant every message signed before the change would be
  re-authenticated against a hash it was never signed with. With it as a field, R15/R16/R20.2/R21/R22
  golden vectors still verify byte-for-byte.

  When a golden harness *constructs* documents rather than parsing them (R14), pin the catalogue
  hash of the revision those bytes were recorded against — see `r14Catalog` in
  `tools/kotlin-conformance/Main.kt` — or the goldens silently change meaning whenever the catalogue
  grows; production grammar hash
  `d5afb09e6ecdcff8a4b2c9758af83f215059d1b5bc4ad32da3fb8dd191b69b19`. Every export carries
  `(catalog_hash, document_format_version, binding_version)`; a lineage mismatch is a rejection.
- **A document is its action history**, not a bitmap: `ADD/REMOVE/GROUP/UNGROUP/TRANSFORM` with
  deterministic IDs (`g000012`, `grp000005`). Rendering is irreversible.
- **Plaintext eidograms are never stored in the DB.** Decryption happens on read via the device key.
- **The message DAG has no total order.** Parents declare causality; a new local message parents
  all current heads. Duplicate `message_id` = NOOP; the same `(deviceId, senderSeq)` under a
  different `message_id` = equivocation = REJECT. MessageIds are immutable across recovery,
  migration and device changes — several tests exist solely to prove this.
- **No user-root private key ever leaves the primary device.** Secondary enrollment is
  request → root authorization → device bundle → root-signed roster, all via exported files.
- **Networking is never an admission dependency.** Every product flow is exercisable through local
  files; real BitTorrent/DHT is a replaceable adapter, intentionally validated last.

## Theory documents in `docs/` (Russian)

- `EidoLang_v1.md` — what the language *is*: an eidogram is a trajectory `Γ = (E_0 → … → E_n)` in
  a reflexive loop `m_t → e_t → E_{t+1} → m_{t+1}`, not a picture. **Semantics are discovered, never
  assigned** — writing "red = force, triangle = action" anywhere collapses eidography into
  pictography and is the project's central prohibition. Likewise a recommender may suggest the next
  *move class* but must never produce a finished eidogram.
- `MVP_v1.md` — the Python line's gate sequence: R1 recording → R2 induction → R3 causalization
  (`do(w) → Δm`?) → R4 causal-equivalence cliques. Operators are evidence-carrying: they are stored
  with their counterexamples and are not promoted on a good average alone.
- `Android_MVP_v1.md` — the normative spec for R14–R22: glyph catalog, document/message/segment
  wire formats, BEP52/BEP44 mapping, repository schema.
- `RRK_v1.md` + `Kabbalah.md` — the formal invariant kernel (K0–K5: distinction is primitive, the
  observer is embedded, things are relational profiles, the observed world is a quotient not a copy)
  and its historical source audit. Background for *why* the gates exist; not implementation input.

Docs, commit-style prose, and Android UI strings are Russian; code identifiers are English. Match
the surrounding language when editing.
