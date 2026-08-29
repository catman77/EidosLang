package org.eidolang.tools

import org.eidolang.core.hardening.*
import org.eidolang.core.vault.*
import java.util.Base64

private fun b64(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

fun main(){
    val (_,secret)=RecoverySecretExportCodec.parseCanonical(GoldenR20_2.RECOVERY_SECRET_EXPORT)
    val pkg=HistoryVaultArchivePackageCodec.parseCanonical(GoldenR20_2.VAULT_PACKAGE)
    val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
    val pair=RecoveryBackupCrypto.create(descriptor.vaultId,secret)
    val backupRaw=RecoveryBackupCodec.json(pair.backup)
    val codeRaw=RecoveryBackupCodeCodec.encode(descriptor.vaultId,pair.recoveryCode)
    val anchor=VaultRollbackGuard.inspect(GoldenR20_2.VAULT_PACKAGE,null).nextAnchor
    val anchorRaw=VaultRollbackAnchorCodec.json(anchor)

    println("BACKUP="+b64(backupRaw))
    println("CODE="+b64(codeRaw))
    println("ANCHOR="+b64(anchorRaw))
    println("BACKUP_ID=${pair.backup.backupId}")
    println("ANCHOR_ID=${anchor.anchorId}")
    println("VAULT_ID=${descriptor.vaultId}")
    println("RECOVERY_KEY_ID=${secret.recoveryKeyId}")
}
