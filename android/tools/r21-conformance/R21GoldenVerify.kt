package org.eidolang.tools

import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*
import java.security.KeyPair

fun main(){
    val backup=RecoveryBackupCodec.parseCanonical(GoldenR21.RECOVERY_BACKUP)
    check(backup.backupId==GoldenR21.BACKUP_ID)
    check(backup.body.vaultId==GoldenR21.VAULT_ID)
    println("PASS fixed R21 canonical encrypted recovery backup identity")

    val (vaultId,code)=RecoveryBackupCodeCodec.decode(GoldenR21.RECOVERY_CODE)
    check(vaultId==GoldenR21.VAULT_ID)
    val secret=RecoveryBackupCrypto.open(backup,code)
    check(secret.recoveryKeyId==GoldenR21.RECOVERY_KEY_ID)
    println("PASS fixed R21 separate recovery code opens exact recovery-secret identity")

    val alice2Bundle=IdentityParser.parsePublicBundleCanonical(GoldenR20_2.ALICE2_BUNDLE)
    val alice2=JvmSecondaryDevice(
        KeyPair(
            PublicKeyCodec.ec(alice2Bundle.device.body.signingPublicKeyB64),
            PublicKeyCodec.privateEc(GoldenR20_2.ALICE2_SIGN_PRIVATE),
        ),
        KeyPair(
            PublicKeyCodec.rsa(alice2Bundle.device.body.encryptionPublicKeyB64),
            PublicKeyCodec.privateRsa(GoldenR20_2.ALICE2_ENC_PRIVATE),
        ),
        alice2Bundle.user,alice2Bundle.device,
    )
    val repo=InMemoryMessengerRepository()
    val state=HistoryVaultRecoveryCoordinator(repo,alice2).restore(GoldenR20_2.VAULT_PACKAGE,secret,12_000)
    check(state.report.messageCount==1)
    println("PASS fixed R21 backup-derived recovery secret opens the fixed R20.2 history vault")

    val anchor=VaultRollbackAnchorCodec.parseCanonical(GoldenR21.ROLLBACK_ANCHOR)
    check(anchor.anchorId==GoldenR21.ANCHOR_ID)
    val recomputed=VaultRollbackGuard.inspect(GoldenR20_2.VAULT_PACKAGE,null)
    check(recomputed.status==VaultFreshnessStatus.BOOTSTRAP_FRESHNESS_UNPROVEN)
    check(VaultRollbackAnchorCodec.json(recomputed.nextAnchor)==GoldenR21.ROLLBACK_ANCHOR)
    println("PASS fixed R21 rollback anchor is deterministic over the fixed vault package")

    val wrong=RecoveryBackupCode.generate()
    check(runCatching{RecoveryBackupCrypto.open(backup,wrong)}.isFailure)
    val body=backup.body.copy(ciphertextB64=backup.body.ciphertextB64.reversed())
    val tampered=RecoveryBackupV1(body,HexSha256.ofUtf8(RecoveryBackupCodec.bodyJson(body)))
    check(runCatching{RecoveryBackupCrypto.open(tampered,code)}.isFailure)
    println("PASS fixed R21 wrong-code and recomputed-id ciphertext tamper rejection")

    val brokenAnchor=GoldenR21.ROLLBACK_ANCHOR.replaceFirst(GoldenR21.ANCHOR_ID,"0".repeat(64))
    check(runCatching{VaultRollbackAnchorCodec.parseCanonical(brokenAnchor)}.isFailure)
    println("PASS fixed R21 rollback-anchor identity tamper rejection")

    println("ALL R21 FIXED GOLDEN CHECKS PASS")
}
