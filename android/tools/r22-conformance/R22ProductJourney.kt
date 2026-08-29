package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import org.eidolang.core.vault.*
import java.security.SecureRandom

private fun doc(glyph:String,x:Int=500_000)=EidogramDocumentV1(
    listOf(EidogramAction.Add(0,"g000001",glyph,FixedTransform(x,500_000),0))
)
private fun bundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun raw(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(bundle(d))

fun main(){
    val random=SecureRandom()

    val alice1=JvmPrivateIdentity.generate(random)
    val bob=JvmPrivateIdentity.generate(random)
    val aliceRoot=JvmRootAuthority.fromPrimary(alice1,random)
    val bobRoot=JvmRootAuthority.fromPrimary(bob,random)

    val aRepo=InMemoryMessengerRepository()
    val bRepo=InMemoryMessengerRepository()
    val a=LocalMessengerService(aRepo,alice1,random)
    val b=LocalMessengerService(bRepo,bob,random)
    a.importContact(raw(bob),"Bob",100)
    b.importContact(raw(alice1),"Alice",100)

    val aRoster0=DeviceRosterAuthority.issue(
        aliceRoot,null,listOf(alice1.certificate.deviceId),emptyList()
    )
    val bRoster0=DeviceRosterAuthority.issue(
        bobRoot,null,listOf(bob.certificate.deviceId),emptyList()
    )
    a.importDeviceRoster(DeviceRosterCanonical.json(aRoster0),110)
    a.importDeviceRoster(DeviceRosterCanonical.json(bRoster0),111)
    b.importDeviceRoster(DeviceRosterCanonical.json(aRoster0),110)
    b.importDeviceRoster(DeviceRosterCanonical.json(bRoster0),111)
    println("PASS explicit epoch-0 device rosters replace implicit first-run active state")

    val conv=a.createConversation(
        listOf(bob.user.userId),ByteArray(16){(0x51+it).toByte()},"R22 journey",1_000
    )
    b.importConversation(a.conversationDescriptorCanonical(conv.conversationId),"R22 journey",1_001)
    val d1=doc("circle.red.m",390_000)
    val m1=a.send(conv.conversationId,d1,2_000)
    b.admitIncoming(MessageCanonical.envelopeJson(m1),2_010)
    println("PASS primary installation creates an ordinary signed/encrypted message before secondary enrollment")

    // Product secondary-device onboarding: locally generated keys -> possession proof -> root authorization -> roster.
    val pending=PendingJvmDevice.generate(random)
    val req=pending.enrollmentRequest(alice1.user)
    check(DeviceEnrollmentAuthority.verifyRequest(req,alice1.user))
    val cert2=DeviceEnrollmentAuthority.authorize(aliceRoot,req)
    val alice2=pending.install(alice1.user,cert2)
    a.importOwnDevice(raw(alice2),2_100)
    b.importContact(raw(alice2),"Alice",2_100)
    val aRoster1=DeviceRosterAuthority.issue(
        aliceRoot,aRoster0,
        listOf(alice1.certificate.deviceId,alice2.certificate.deviceId),emptyList()
    )
    a.importDeviceRoster(DeviceRosterCanonical.json(aRoster1),2_110)
    b.importDeviceRoster(DeviceRosterCanonical.json(aRoster1),2_110)
    check(a.isDeviceActive(alice1.user.userId,alice2.certificate.deviceId))
    println("PASS secondary onboarding possession proof + root certificate + roster activation")

    // R22 secure-vault export and two-channel recovery backup.
    val secret=HistoryVaultRecoverySecret.generate(random)
    val vault=HistoryVaultController.create(
        alice1,secret,ByteArray(16){(0x71+it).toByte()},random
    )
    vault.createEpoch(aRoster1.body.activeDeviceIds)
    check(vault.archiveAll(aRepo,a)==1)
    val vaultRaw=vault.exportPackage(aRepo)
    val vaultPkg=HistoryVaultArchivePackageCodec.parseCanonical(vaultRaw)
    val backupPair=RecoveryBackupCrypto.create(vault.descriptor.vaultId,secret,random)
    val backupRaw=RecoveryBackupCodec.json(backupPair.backup)
    val recoveryCode=RecoveryBackupCodeCodec.encode(vault.descriptor.vaultId,backupPair.recoveryCode)
    check(!backupRaw.contains(B64Url.encode(secret.copyBytes())))
    println("PASS product secure export separates encrypted vault package, encrypted recovery backup and recovery code")

    // Fresh secondary device reconstructs history from the product recovery pair without old RSA or key grants.
    val (_,decodedCode)=RecoveryBackupCodeCodec.decode(recoveryCode)
    val recoveredSecret=RecoveryBackupCrypto.open(
        RecoveryBackupCodec.parseCanonical(backupRaw),decodedCode
    )
    val a2Repo=InMemoryMessengerRepository()
    val recoveredState=HistoryVaultRecoveryCoordinator(a2Repo,alice2,random)
        .restore(vaultRaw,recoveredSecret,3_000)
    check(a2Repo.keyGrants().isEmpty())
    val a2=LocalMessengerService(
        a2Repo,alice2,random,historicalDocumentProvider=recoveredState.documentProvider
    )
    val t=a2.timeline(conv.conversationId)
    check(t.single().messageId==m1.messageId)
    check(EidogramCanonical.contentHash(t.single().document)==EidogramCanonical.contentHash(d1))
    println("PASS secondary destructive restore works with vault backup/code and no historical RSA/key-grant dependency")

    // Fresh restore honestly exposes freshness as unproven; anchoring then rejects rollback.
    val bootstrap=VaultRollbackGuard.inspect(vaultRaw,null)
    check(bootstrap.status==VaultFreshnessStatus.BOOTSTRAP_FRESHNESS_UNPROVEN)
    val d2=doc("outline.diamond",610_000)
    val m2=a2.send(conv.conversationId,d2,3_100)
    val continued=HistoryVaultController.fromPackage(vaultRaw,recoveredSecret,alice2,random)
    check(continued.archiveAll(a2Repo,a2)==1)
    val newerRaw=continued.exportPackage(a2Repo)
    val extension=VaultRollbackGuard.inspect(newerRaw,bootstrap.nextAnchor)
    check(extension.status==VaultFreshnessStatus.MONOTONIC_EXTENSION)
    check(runCatching{VaultRollbackGuard.inspect(vaultRaw,extension.nextAnchor)}.isFailure)
    println("PASS fresh restore labels freshness honestly and local checkpoint rejects later rollback")

    // Secondary continues the exact immutable DAG after restore.
    check(m2.body.aad.parentMessageIds==listOf(m1.messageId))
    println("PASS restored secondary continues the same immutable MessageId DAG")

    // Revoke primary, rotate vault epoch, and ensure only the secondary remains current.
    val aRoster2=DeviceRosterAuthority.issue(
        aliceRoot,aRoster1,
        listOf(alice2.certificate.deviceId),listOf(alice1.certificate.deviceId)
    )
    a2.importDeviceRoster(DeviceRosterCanonical.json(aRoster2),3_200)
    val epoch1=continued.createEpoch(aRoster2.body.activeDeviceIds)
    check(epoch1.body.activeDeviceIds==listOf(alice2.certificate.deviceId))
    println("PASS device revocation forces fresh vault epoch with only currently active secondary")

    val d3=doc("circle.cyan.s",520_000)
    val m3=a2.send(conv.conversationId,d3,3_300)
    check(continued.archiveAll(a2Repo,a2)==1)
    val finalVaultRaw=continued.exportPackage(a2Repo)
    val finalVault=HistoryVaultArchivePackageCodec.parseCanonical(finalVaultRaw)
    check(finalVault.entryCanonicalJson.size==3)
    val lastEntry=finalVault.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical)
        .single{it.body.messageId==m3.messageId}
    check(lastEntry.body.epoch==1)
    println("PASS post-revocation history is archived only under the fresh active-device epoch")

    // Product cutover proof from an actual legacy R19 representation of the same source history.
    val legacyStore=InMemoryArchiveStore()
    val legacy=ArchiveCoordinator(a2Repo,legacyStore,alice2,random)
    legacy.archiveAll(3_400)
    val legacyRaw=legacy.exportPackageCanonical()
    val sourceDigest=HistoryEquivalence.compute(a2Repo,a2)

    val verifyRepo=InMemoryMessengerRepository()
    val verifyState=HistoryVaultRecoveryCoordinator(verifyRepo,alice2,random)
        .restore(finalVaultRaw,recoveredSecret,3_500)
    val verifyService=LocalMessengerService(
        verifyRepo,alice2,random,historicalDocumentProvider=verifyState.documentProvider
    )
    val verifyDigest=HistoryEquivalence.compute(verifyRepo,verifyService)
    val receipt=LegacyCutover.issue(
        listOf("R19_PACKAGE" to legacyRaw.toByteArray(Charsets.UTF_8)),
        finalVaultRaw,sourceDigest,verifyDigest,alice2
    )
    check(LegacyCutover.verify(receipt,bundle(alice2)))
    check(runCatching{
        LegacyCutover.rejectCommittedLegacy(legacyRaw.toByteArray(Charsets.UTF_8),receipt)
    }.isFailure)
    println("PASS verified migrate→recover→compare cutover blocks exact retired legacy artifact")

    // Recovery backup mismatch remains fail-closed in the final product path.
    val otherCode=RecoveryBackupCode.generate(random)
    check(runCatching{
        RecoveryBackupCrypto.open(backupPair.backup,otherCode)
    }.isFailure)
    println("PASS product recovery path rejects mismatched recovery code")

    println("FINAL_PACKAGE_ID=${finalVault.packageId}")
    println("FINAL_HEAD=${a2.currentHeads(conv.conversationId).single()}")
    println("CUTOVER_ID=${receipt.cutoverId}")
    println("ALL R22 PRODUCT-JOURNEY CHECKS PASS")
}
