package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import java.security.SecureRandom

private fun rdoc(glyph:String,x:Int=500_000)=EidogramDocumentV1(
    listOf(EidogramAction.Add(0,"g000001",glyph,FixedTransform(x,500_000),0))
)
private fun rbundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun rraw(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(rbundle(d))

fun main(){
    val random=SecureRandom()
    val alice1=JvmPrivateIdentity.generate(random)
    val bob1=JvmPrivateIdentity.generate(random)
    val root=JvmRootAuthority.fromPrimary(alice1,random)
    val bobRoot=JvmRootAuthority.fromPrimary(bob1,random)

    val sourceRepo=InMemoryMessengerRepository()
    val sourceArchive=InMemoryArchiveStore()
    val alice=LocalMessengerService(sourceRepo,alice1,random)
    val bobRepo=InMemoryMessengerRepository()
    val bob=LocalMessengerService(bobRepo,bob1,random)

    alice.importContact(rraw(bob1),"Bob",100)
    bob.importContact(rraw(alice1),"Alice",100)

    val roster0=DeviceRosterAuthority.issue(root,null,listOf(alice1.certificate.deviceId),emptyList())
    val bobRoster0=DeviceRosterAuthority.issue(bobRoot,null,listOf(bob1.certificate.deviceId),emptyList())
    alice.importDeviceRoster(DeviceRosterCanonical.json(roster0),110)
    alice.importDeviceRoster(DeviceRosterCanonical.json(bobRoster0),111)
    bob.importDeviceRoster(DeviceRosterCanonical.json(roster0),110)
    bob.importDeviceRoster(DeviceRosterCanonical.json(bobRoster0),111)

    val conv=alice.createConversation(
        listOf(bob1.user.userId), ByteArray(16){(0x20+it).toByte()},"Recovery",1_000
    )
    bob.importConversation(alice.conversationDescriptorCanonical(conv.conversationId),"Recovery",1_001)

    val legacyA=alice.send(conv.conversationId,rdoc("circle.red.l",400_000),2_000)
    bob.admitIncoming(MessageCanonical.envelopeJson(legacyA),2_010)
    val legacyB=bob.send(conv.conversationId,rdoc("outline.triangle",600_000),2_100)
    alice.admitIncoming(MessageCanonical.envelopeJson(legacyB),2_110)

    val archiver=ArchiveCoordinator(sourceRepo,sourceArchive,alice1,random)
    check(archiver.archiveConversation(conv.conversationId,2_500) is ArchiveConversationResult.Created)
    println("PASS source archive captures pre-secondary-device history")

    val pending=PendingJvmDevice.generate(random)
    val req=pending.enrollmentRequest(alice1.user)
    val cert2=DeviceEnrollmentAuthority.authorize(root,req)
    val alice2=pending.install(alice1.user,cert2)
    alice.importOwnDevice(rraw(alice2),3_000)
    bob.importContact(rraw(alice2),"Alice",3_000)

    val roster1=DeviceRosterAuthority.issue(
        root,roster0,listOf(alice1.certificate.deviceId,alice2.certificate.deviceId),emptyList()
    )
    alice.importDeviceRoster(DeviceRosterCanonical.json(roster1),3_010)
    bob.importDeviceRoster(DeviceRosterCanonical.json(roster1),3_010)

    val modern=alice.send(conv.conversationId,rdoc("stick.black.pose045.m.normal"),3_500)
    bob.admitIncoming(MessageCanonical.envelopeJson(modern),3_510)
    check(modern.body.recipientBoxes.any{it.encryptionKeyId==alice2.certificate.body.encryptionKeyId})
    check(archiver.archiveConversation(conv.conversationId,3_600) is ArchiveConversationResult.Created)

    val packageRaw=archiver.exportPackageCanonical()
    val packageId=ArchivePackageCodec.parseCanonical(packageRaw).packageId
    val grants=alice.createHistoricalKeyGrants(rraw(alice2),3_700)
    val grantIds=grants.map{MessageKeyGrantCanonical.parseCanonical(it).body.messageId}.toSet()
    check(legacyA.messageId in grantIds && legacyB.messageId in grantIds)
    check(modern.messageId !in grantIds)
    println("PASS source creates grants only for ciphertext lacking the new device key box")

    val capsuleRaw=MultiDeviceRecoveryCapsuleFactory.create(
        sourcePackageCanonical = packageRaw,
        ownDeviceBundleCanonicalJson = listOf(rraw(alice1), rraw(alice2)),
        deviceRosterCanonicalJson = listOf(DeviceRosterCanonical.json(roster1), DeviceRosterCanonical.json(bobRoster0)),
        target = rbundle(alice2),
        grantCanonicalJson = grants,
    )
    val capsule=MultiDeviceRecoveryCapsuleCodec.parseCanonical(capsuleRaw)
    check(capsule.sourcePackageId==packageId)
    check(capsule.targetDeviceId==alice2.certificate.deviceId)
    println("PASS canonical recovery capsule binds source package, active target and historical grants")

    val targetRepo=InMemoryMessengerRepository()
    val targetArchive=InMemoryArchiveStore()
    val recovery=MultiDeviceRecoveryCoordinator(targetRepo,targetArchive,alice2)
    val report=recovery.restore(packageRaw,capsuleRaw,4_000)
    check(report.uniqueMessageCount==3)
    check(targetArchive.list().size==2)
    check(targetRepo.deviceRosters().map { it.userId }.toSet() == setOf(alice1.user.userId,bob1.user.userId))

    val target=LocalMessengerService(targetRepo,alice2,random)
    val sourceTimeline=alice.timeline(conv.conversationId)
    val targetTimeline=target.timeline(conv.conversationId)
    check(sourceTimeline.map{it.messageId}==targetTimeline.map{it.messageId})
    check(sourceTimeline.map{EidogramCanonical.contentHash(it.document)}==
        targetTimeline.map{EidogramCanonical.contentHash(it.document)})
    check(sourceTimeline.last().messageId==modern.messageId)
    println("PASS R19 package restores on second authorized device with identical DAG and eidograms")

    val grantsStored=targetRepo.keyGrants().map{it.messageId}.toSet()
    check(grantsStored.containsAll(setOf(legacyA.messageId,legacyB.messageId)))
    println("PASS historical key grants persist as local decryption supplements")

    val continuation=target.send(conv.conversationId,rdoc("circle.violet.m"),4_500)
    check(continuation.body.aad.parentMessageIds==listOf(modern.messageId))
    check(continuation.body.recipientBoxes.any{
        it.encryptionKeyId==alice1.certificate.body.encryptionKeyId
    })
    println("PASS recovered secondary device continues the same DAG and encrypts back to sibling device")

    // A migrated device must preserve its historical grants in future backups. The unchanged
    // R19 package alone still cannot decrypt those legacy messages on a clean repository; the
    // R20 companion capsule makes the new backup self-consistent.
    val targetArchiver=ArchiveCoordinator(targetRepo,targetArchive,alice2,random)
    check(targetArchiver.archiveConversation(conv.conversationId,4_600) is ArchiveConversationResult.Created)
    val package2=targetArchiver.exportPackageCanonical()
    val capsule2=MultiDeviceRecoveryCapsuleFactory.create(
        package2,
        target.ownDeviceBundlesCanonical(),
        target.latestDeviceRostersCanonical(),
        rbundle(alice2),
        target.allKeyGrantsCanonical(),
    )
    val directRepo=InMemoryMessengerRepository()
    val directStore=InMemoryArchiveStore()
    check(runCatching{
        ArchiveCoordinator(directRepo,directStore,alice2,random)
            .importAndRestorePackage(package2,4_700)
    }.isFailure)
    val migratedRepo=InMemoryMessengerRepository()
    val migratedStore=InMemoryArchiveStore()
    MultiDeviceRecoveryCoordinator(migratedRepo,migratedStore,alice2)
        .restore(package2,capsule2,4_700)
    val migrated=LocalMessengerService(migratedRepo,alice2,random)
    check(migrated.timeline(conv.conversationId).map{it.messageId}==
        target.timeline(conv.conversationId).map{it.messageId})
    println("PASS migrated-device backups preserve historical grants through the R20 companion capsule")

    // Missing grant must fail in mirror preflight and leave actual repository untouched.
    val incompleteCapsule=MultiDeviceRecoveryCapsuleCodec.json(
        MultiDeviceRecoveryCapsuleCodec.withId(
            packageId,alice1.user.userId,alice2.certificate.deviceId,
            alice2.certificate.body.encryptionKeyId,
            listOf(rraw(alice1),rraw(alice2)),
            listOf(DeviceRosterCanonical.json(roster1),DeviceRosterCanonical.json(bobRoster0)),
            grants.filterNot{MessageKeyGrantCanonical.parseCanonical(it).body.messageId==legacyA.messageId}
        )
    )
    val emptyRepo=InMemoryMessengerRepository()
    val emptyArchive=InMemoryArchiveStore()
    check(runCatching{
        MultiDeviceRecoveryCoordinator(emptyRepo,emptyArchive,alice2)
            .restore(packageRaw,incompleteCapsule,5_000)
    }.isFailure)
    check(emptyRepo.conversations().isEmpty() && emptyArchive.list().isEmpty())
    println("PASS incomplete migration capsule fails before repository/archive mutation")

    val roster2=DeviceRosterAuthority.issue(
        root,roster1,listOf(alice1.certificate.deviceId),listOf(alice2.certificate.deviceId)
    )
    check(runCatching{
        MultiDeviceRecoveryCapsuleFactory.create(
            packageRaw,
            listOf(rraw(alice1),rraw(alice2)),
            listOf(DeviceRosterCanonical.json(roster2),DeviceRosterCanonical.json(bobRoster0)),
            rbundle(alice2),
            grants
        )
    }.isFailure)
    println("PASS revoked target device cannot receive a new recovery capsule")

    println("SOURCE_PACKAGE_ID=$packageId")
    println("RECOVERY_CAPSULE_ID=${capsule.capsuleId}")
    println("RECOVERED_HEAD=${target.currentHeads(conv.conversationId).single()}")
    println("ALL R20 CROSS-DEVICE RECOVERY CHECKS PASS")
}
