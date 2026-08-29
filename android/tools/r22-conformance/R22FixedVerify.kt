package org.eidolang.tools

import org.eidolang.core.hardening.*
import org.eidolang.core.vault.*

fun main(){
    val pkg=HistoryVaultArchivePackageCodec.parseCanonical(GoldenR20_2.VAULT_PACKAGE)
    check(pkg.packageId==GoldenR20_2.PACKAGE_ID)
    check(GoldenR20_2.VAULT_ID==GoldenR21.VAULT_ID)
    println("PASS fixed R22 composes the exact R20.2 vault identity with the R21 backup domain")

    val backup=RecoveryBackupCodec.parseCanonical(GoldenR21.RECOVERY_BACKUP)
    check(backup.backupId==GoldenR21.BACKUP_ID)
    val (codeVault,code)=RecoveryBackupCodeCodec.decode(GoldenR21.RECOVERY_CODE)
    check(codeVault==GoldenR20_2.VAULT_ID)
    val secret=RecoveryBackupCrypto.open(backup,code)
    check(secret.recoveryKeyId==GoldenR21.RECOVERY_KEY_ID)
    println("PASS fixed R22 recovery backup/code opens the exact R20.2 recovery-key identity")

    val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
    val epoch=HistoryVaultEpochCodec.parseCanonical(pkg.epochCanonicalJson.single())
    val epochKey=HistoryVaultCrypto.openEpoch(descriptor,secret,epoch)
    val entry=HistoryVaultEntryCodec.parseCanonical(pkg.entryCanonicalJson.single())
    val payload=HistoryVaultCrypto.openEntry(descriptor,epochKey,entry)
    epochKey.fill(0)
    check(entry.body.messageId==GoldenR20_2.MESSAGE_ID)
    check(payload.documentContentHash==GoldenR20_2.DOCUMENT_HASH)
    println("PASS fixed R22 product recovery pair decrypts the canonical archived eidogram history")

    val anchor=VaultRollbackAnchorCodec.parseCanonical(GoldenR21.ROLLBACK_ANCHOR)
    check(anchor.anchorId==GoldenR21.ANCHOR_ID)
    check(anchor.vaultId==GoldenR20_2.VAULT_ID)
    check(anchor.lastPackageId==GoldenR20_2.PACKAGE_ID)
    check(anchor.messageEntryIds[GoldenR20_2.MESSAGE_ID]==GoldenR20_2.ENTRY_ID)
    println("PASS fixed R22 rollback anchor binds the exact package/message/entry checkpoint")

    val admission=VaultRollbackGuard.inspect(GoldenR20_2.VAULT_PACKAGE,anchor)
    check(admission.status==VaultFreshnessStatus.MONOTONIC_EXTENSION)
    check(admission.nextAnchor.anchorId==anchor.anchorId)
    println("PASS fixed R22 exact current package is idempotently admitted against its stored checkpoint")

    println("ALL R22 FIXED PRODUCT CHECKS PASS")
}
