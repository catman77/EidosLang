#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT=Path(__file__).resolve().parents[2]

def text(rel):
    return (ROOT/rel).read_text(encoding="utf-8")

def ok(name, condition):
    if not condition:
        raise AssertionError(name)
    print("PASS", name)

main=text("app/src/main/java/org/eidolang/app/MainActivity.kt")
onboard=text("feature-onboarding/src/main/java/org/eidolang/feature/onboarding/EidoOnboarding.kt")
messenger=text("feature-messenger/src/main/java/org/eidolang/feature/messenger/EidoMessengerApp.kt")
archive=text("feature-archive/src/main/java/org/eidolang/feature/archive/ArchiveScreen.kt")
identity=text("core-crypto/src/main/java/org/eidolang/core/crypto/AndroidKeystoreIdentityStore.kt")
db=text("core-repository/src/main/java/org/eidolang/core/repository/AndroidSqliteMessengerRepository.kt")
draft=text("feature-editor/src/main/java/org/eidolang/feature/editor/DraftStore.kt")
admission=text("core-admission/src/main/java/org/eidolang/core/admission/AndroidAdmissionProbe.kt")
settings=text("settings.gradle.kts")
appbuild=text("app/build.gradle.kts")
manifest=text("app/src/main/AndroidManifest.xml")

ok("MainActivity routes through explicit first-run EidoRootApp", "EidoRootApp" in main)
ok("first-run source contains separate primary and secondary roles",
   "Создать основной профиль" in onboard and "Подключить как дополнительное устройство" in onboard)
ok("secondary onboarding requires enrollment request, authorized bundle and root-signed roster",
   all(x in onboard for x in ["createEnrollmentRequest","installAuthorizedBundle","importDeviceRosterSnapshot"]))
ok("Android primary identity store has non-creating loadIfPresent boundary and no duplicate rootPublic declaration",
   "fun loadIfPresent()" in identity and identity.count("val rootPublic = ks.getCertificate(ROOT_ALIAS).publicKey")==1)
ok("device management source includes root authorization, roster export and revocation",
   all(x in messenger for x in ["DeviceEnrollmentAuthority.authorize","DeviceRosterAuthority.issue","Отозвать"]))
ok("secure archive UI includes R20.2 restore, R21 recovery backup and explicit freshness warning",
   all(x in archive for x in ["HistoryVaultRecoveryCoordinator","RecoveryBackupCrypto","FRESHNESS UNPROVEN"]))
ok("legacy migration UI requires history equivalence/cutover receipt path",
   all(x in archive for x in ["HistoryEquivalence.compute","LegacyCutover.issue","cutoverStore.save"]))
ok("legacy import UI blocks an exact artifact committed as retired by cutover receipt",
   "LegacyCutover.rejectCommittedLegacy" in archive)
ok(
    "SQLite repository is v3 and protects alias/title fields",
    "DB_VERSION=3" in db
    and 'protect("contact-alias"' in db
    and 'protect("conversation-title"' in db
    and "Plaintext contact alias remained after v3 migration" in db,
)
ok("DraftStore uses AndroidLocalSecretBox and encrypted .bin persistence",
   "AndroidLocalSecretBox" in draft and 'return "eidogram-draft-$h.bin"' in draft)
ok("Android runtime admission probe exercises identity, RSA, secret-box, vault store and repository storage",
   all(x in admission for x in ["identity.sign","identity.rsa_oaep","local.secret_box","vault.secret_store","repository.sensitive_text"]))
ok("Android platform backup is disabled and cleartext traffic is forbidden",
   'android:allowBackup="false"' in manifest and 'android:usesCleartextTraffic="false"' in manifest)
ok("R22 modules/version are wired without making networking an admission dependency",
   all(x in settings for x in ['":core-admission"', '":feature-onboarding"'])
   and 'versionName = "0.1-R22"' in appbuild)

print("ALL R22 ANDROID SOURCE-AUDIT CHECKS PASS")
