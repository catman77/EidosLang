package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.repository.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.vault.*
import java.security.KeyPair

fun main(){
    val alice=IdentityParser.parsePublicBundleCanonical(GoldenR20_2.ALICE_BUNDLE)
    val alice2Bundle=IdentityParser.parsePublicBundleCanonical(GoldenR20_2.ALICE2_BUNDLE)
    val bob=IdentityParser.parsePublicBundleCanonical(GoldenR20_2.BOB_BUNDLE)
    check(IdentityVerifier.verifyBundle(alice) && IdentityVerifier.verifyBundle(alice2Bundle) && IdentityVerifier.verifyBundle(bob))
    println("PASS fixed R20.2 public identity bundles")

    val (secretExport,secret)=RecoverySecretExportCodec.parseCanonical(GoldenR20_2.RECOVERY_SECRET_EXPORT)
    check(secretExport.vaultId==GoldenR20_2.VAULT_ID)
    val pkg=HistoryVaultArchivePackageCodec.parseCanonical(GoldenR20_2.VAULT_PACKAGE)
    check(pkg.packageId==GoldenR20_2.PACKAGE_ID)
    println("PASS fixed R20.2 recovery-secret export and package signature")

    val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
    val epoch=HistoryVaultEpochCodec.parseCanonical(pkg.epochCanonicalJson.single())
    val entry=HistoryVaultEntryCodec.parseCanonical(pkg.entryCanonicalJson.single())
    check(epoch.epochId==GoldenR20_2.EPOCH_ID && entry.entryId==GoldenR20_2.ENTRY_ID)
    val epochKey=HistoryVaultCrypto.openEpoch(descriptor,secret,epoch)
    val payload=HistoryVaultCrypto.openEntry(descriptor,epochKey,entry)
    check(entry.body.messageId==GoldenR20_2.MESSAGE_ID)
    check(payload.documentContentHash==GoldenR20_2.DOCUMENT_HASH)
    println("PASS fixed R20.2 recovery unwrap and history-entry decryption")

    val alice2=JvmSecondaryDevice(
        KeyPair(
            PublicKeyCodec.ec(alice2Bundle.device.body.signingPublicKeyB64),
            PublicKeyCodec.privateEc(GoldenR20_2.ALICE2_SIGN_PRIVATE),
        ),
        KeyPair(
            PublicKeyCodec.rsa(alice2Bundle.device.body.encryptionPublicKeyB64),
            PublicKeyCodec.privateRsa(GoldenR20_2.ALICE2_ENC_PRIVATE),
        ),
        alice2Bundle.user,
        alice2Bundle.device,
    )
    val grant=HistoryVaultDeviceGrantParser.parseCanonical(GoldenR20_2.DEVICE_GRANT)
    check(HistoryVaultDeviceGrantCrypto.verify(grant,descriptor,epoch,alice,alice2Bundle))
    val grantKey=HistoryVaultDeviceGrantCrypto.open(grant,descriptor,epoch,alice,alice2)
    check(grantKey.contentEquals(epochKey))
    grantKey.fill(0); epochKey.fill(0)
    println("PASS fixed R20.2 active-device epoch-key grant")

    val repo=InMemoryMessengerRepository()
    val restored=HistoryVaultRecoveryCoordinator(repo,alice2).restore(GoldenR20_2.VAULT_PACKAGE,secret,9_000)
    check(repo.keyGrants().isEmpty())
    val service=LocalMessengerService(repo,alice2,historicalDocumentProvider=restored.documentProvider)
    val conversation=repo.conversations().single()
    val timeline=service.timeline(conversation.conversationId)
    check(timeline.single().messageId==GoldenR20_2.MESSAGE_ID)
    check(EidogramCanonical.contentHash(timeline.single().document)==GoldenR20_2.DOCUMENT_HASH)
    println("PASS fixed R20.2 cross-device destructive recovery without legacy RSA/key-grant access")

    val wrong=HistoryVaultRecoverySecret.generate()
    val empty=InMemoryMessengerRepository()
    check(runCatching{HistoryVaultRecoveryCoordinator(empty,alice2).restore(GoldenR20_2.VAULT_PACKAGE,wrong,9_100)}.isFailure)
    check(empty.conversations().isEmpty())
    println("PASS fixed R20.2 wrong recovery secret zero-mutation rejection")

    println("ALL R20.2 FIXED GOLDEN CHECKS PASS")
}
