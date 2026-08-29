package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import org.eidolang.core.session.*
import org.eidolang.core.vault.*
import java.security.SecureRandom

private fun doc(glyph:String,x:Int=500_000)=EidogramDocumentV1(
    listOf(EidogramAction.Add(0,"g000001",glyph,FixedTransform(x,500_000),0))
)
private fun bundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun rawBundle(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(bundle(d))

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
    a.importContact(rawBundle(bob),"Bob",100)
    b.importContact(rawBundle(alice1),"Alice",100)

    val aRoster0=DeviceRosterAuthority.issue(aliceRoot,null,listOf(alice1.certificate.deviceId),emptyList())
    val bRoster0=DeviceRosterAuthority.issue(bobRoot,null,listOf(bob.certificate.deviceId),emptyList())
    a.importDeviceRoster(DeviceRosterCanonical.json(aRoster0),110)
    a.importDeviceRoster(DeviceRosterCanonical.json(bRoster0),111)
    b.importDeviceRoster(DeviceRosterCanonical.json(aRoster0),110)
    b.importDeviceRoster(DeviceRosterCanonical.json(bRoster0),111)

    val conversation=a.createConversation(
        listOf(bob.user.userId),ByteArray(16){(0x21+it).toByte()},"Vault migration",1_000
    )
    b.importConversation(a.conversationDescriptorCanonical(conversation.conversationId),"Vault migration",1_001)

    val d1=doc("circle.red.m",390_000)
    val m1=a.send(conversation.conversationId,d1,2_000)
    b.admitIncoming(MessageCanonical.envelopeJson(m1),2_010)

    val d2=doc("outline.triangle",610_000)
    val m2=b.send(conversation.conversationId,d2,2_100)
    a.admitIncoming(MessageCanonical.envelopeJson(m2),2_110)

    val pending=PendingJvmDevice.generate(random)
    val request=pending.enrollmentRequest(alice1.user)
    val alice2Cert=DeviceEnrollmentAuthority.authorize(aliceRoot,request)
    val alice2=pending.install(alice1.user,alice2Cert)
    a.importOwnDevice(rawBundle(alice2),2_500)
    b.importContact(rawBundle(alice2),"Alice",2_500)
    val aRoster1=DeviceRosterAuthority.issue(
        aliceRoot,aRoster0,
        listOf(alice1.certificate.deviceId,alice2.certificate.deviceId),emptyList()
    )
    a.importDeviceRoster(DeviceRosterCanonical.json(aRoster1),2_510)
    b.importDeviceRoster(DeviceRosterCanonical.json(aRoster1),2_510)
    println("PASS legacy messages exist before secondary-device authorization")

    val legacyStore=InMemoryArchiveStore()
    val legacyArchiver=ArchiveCoordinator(aRepo,legacyStore,alice1,random)
    legacyArchiver.archiveAll(3_000)
    val r19=legacyArchiver.exportPackageCanonical()
    val parsedR19=ArchivePackageCodec.parseCanonical(r19)
    val exposedR15=parsedR19.segments
        .map(ArchiveSegmentCodec::toArtifact)
        .flatMap{it.files}
        .first{it.path=="messages/${m1.messageId}.json"}
        .bytes.toString(Charsets.UTF_8)
    check(exposedR15==MessageCanonical.envelopeJson(m1))
    println("PASS control: legacy R19 package exposes recoverable canonical R15 envelope bytes")

    val recoverySecret=HistoryVaultRecoverySecret.generate(random)
    val migrated=LegacyArchiveVaultMigrator.migrateR19SameDevice(
        r19,alice1,recoverySecret,ByteArray(16){(0x61+it).toByte()},3_100,
        a.ownDeviceBundlesCanonical(),a.latestDeviceRostersCanonical(),random
    )
    val vaultRaw=migrated.packageCanonicalJson
    val vault=HistoryVaultArchivePackageCodec.parseCanonical(vaultRaw)
    check(migrated.migratedMessageCount==2)
    check(vault.entryCanonicalJson.size==2)
    check(!vaultRaw.contains(MessageCanonical.envelopeJson(m1)))
    val oldWrapped=m1.body.recipientBoxes.first().wrappedKeyB64
    check(!vaultRaw.contains(oldWrapped))
    println("PASS R19 migration removes raw R15 envelope/key-box material from the new archive surface")

    val bobPre=JvmPreKeyStore(bob,random)
    val pre=bobPre.createBundle()
    val session=SessionBootstrap.initiate(
        conversation,alice1,bundle(bob),pre,"ratchet-state-probe".toByteArray(),random
    ).session
    val ratchetRoot=HistoryVaultRecoverySecret.fromBytes(
        B64Url.decode(session.exportSecretSnapshot().rootKeyB64)
    )
    val wrongRepo=InMemoryMessengerRepository()
    check(runCatching{
        HistoryVaultRecoveryCoordinator(wrongRepo,alice2,random)
            .restore(vaultRaw,ratchetRoot,3_200)
    }.isFailure)
    check(wrongRepo.conversations().isEmpty())
    println("PASS compromise of current R20.1 ratchet state cannot open the independent history vault")

    val recoveredRepo=InMemoryMessengerRepository()
    val recoveredState=HistoryVaultRecoveryCoordinator(recoveredRepo,alice2,random)
        .restore(vaultRaw,recoverySecret,3_300)
    check(recoveredRepo.keyGrants().isEmpty())
    val recovered=LocalMessengerService(
        recoveredRepo,alice2,random,recoveredState.documentProvider
    )
    val recoveredTimeline=recovered.timeline(conversation.conversationId)
    check(recoveredTimeline.map{it.messageId}==listOf(m1.messageId,m2.messageId))
    check(recoveredTimeline.map{EidogramCanonical.contentHash(it.document)}==
        listOf(EidogramCanonical.contentHash(d1),EidogramCanonical.contentHash(d2)))
    println("PASS destructive recovery on a new authorized device needs neither old RSA private key nor R20 message-key grants")

    val d3=doc("circle.violet.s",500_000)
    val m3=recovered.send(conversation.conversationId,d3,4_000)
    check(m3.body.aad.parentMessageIds==listOf(m2.messageId))
    println("PASS recovered secondary device continues the same MessageId DAG")

    val controller=HistoryVaultController.fromPackage(vaultRaw,recoverySecret,alice2,random)
    check(controller.archiveAll(recoveredRepo,recovered)==1)
    val vaultWithM3=controller.exportPackage(recoveredRepo)
    val pA=HistoryVaultArchivePackageCodec.parseCanonical(vaultWithM3)
    val pB=HistoryVaultArchivePackageCodec.parseCanonical(controller.exportPackage(recoveredRepo))
    check(pA.packageId==pB.packageId)
    check(pA.entryCanonicalJson.size==3)
    println("PASS package identity excludes randomized exporter proof and incremental archive adds only fresh history")

    val aRoster2=DeviceRosterAuthority.issue(
        aliceRoot,aRoster1,
        listOf(alice2.certificate.deviceId),listOf(alice1.certificate.deviceId)
    )
    recovered.importDeviceRoster(DeviceRosterCanonical.json(aRoster2),4_100)
    val epoch1=controller.createEpochFromRoster(DeviceRosterCanonical.json(aRoster2))
    check(epoch1.body.epoch==1)
    check(epoch1.body.activeDeviceIds==listOf(alice2.certificate.deviceId))

    val opened1=controller.openEpochForDeviceGrant(epoch1.epochId)
    check(runCatching{
        HistoryVaultDeviceGrantCrypto.issue(
            controller.descriptor,opened1,alice2,bundle(alice1),random
        )
    }.isFailure)

    val selfGrant=HistoryVaultDeviceGrantCrypto.issue(
        controller.descriptor,opened1,alice2,bundle(alice2),random
    )
    val selfKey=HistoryVaultDeviceGrantCrypto.open(
        selfGrant,controller.descriptor,opened1.epoch,bundle(alice2),alice2
    )
    check(selfKey.contentEquals(opened1.epochKey))
    selfKey.fill(0)
    println("PASS vault epoch rotation excludes revoked devices while preserving active-device access")

    val d4=doc("outline.square",520_000)
    val m4=recovered.send(conversation.conversationId,d4,4_200)
    check(controller.archiveAll(recoveredRepo,recovered)==1)
    val finalVault=controller.exportPackage(recoveredRepo)
    val finalPkg=HistoryVaultArchivePackageCodec.parseCanonical(finalVault)
    val finalEntry=finalPkg.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical)
        .single{it.body.messageId==m4.messageId}
    check(finalEntry.body.epoch==1 && finalEntry.body.epochId==epoch1.epochId)
    println("PASS post-revocation history is sealed only under the fresh vault epoch")

    val finalRepo=InMemoryMessengerRepository()
    val finalState=HistoryVaultRecoveryCoordinator(finalRepo,alice2,random)
        .restore(finalVault,recoverySecret,5_000)
    val finalMessenger=LocalMessengerService(finalRepo,alice2,random,finalState.documentProvider)
    check(finalMessenger.timeline(conversation.conversationId).map{it.messageId}==
        listOf(m1.messageId,m2.messageId,m3.messageId,m4.messageId))
    println("PASS independent recovery secret reconstructs a multi-epoch history after old session keys are erased")

    val randomWrong=HistoryVaultRecoverySecret.generate(random)
    val empty=InMemoryMessengerRepository()
    check(runCatching{
        HistoryVaultRecoveryCoordinator(empty,alice2,random).restore(finalVault,randomWrong,5_100)
    }.isFailure)
    check(empty.conversations().isEmpty() && empty.contactDevices().isEmpty())
    println("PASS wrong recovery secret causes zero repository mutation")

    val tampered=finalVault.replaceFirst("Vault migration","Vault tampered")
    check(runCatching{HistoryVaultArchivePackageCodec.parseCanonical(tampered)}.isFailure)
    println("PASS archive metadata/body tamper rejected by package identity/signature")

    println("VAULT_ID=${controller.descriptor.vaultId}")
    println("FINAL_VAULT_PACKAGE_ID=${finalPkg.packageId}")
    println("FINAL_HEAD=${finalMessenger.currentHeads(conversation.conversationId).single()}")
    println("ALL R20.2 HISTORY-VAULT CHECKS PASS")
}
