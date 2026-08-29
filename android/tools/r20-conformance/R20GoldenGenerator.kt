package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import java.security.SecureRandom
import java.util.Base64

private fun b64(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
private fun doc()=EidogramDocumentV1(listOf(
    EidogramAction.Add(0,"g000001","circle.cyan.m",FixedTransform(470_000,510_000),0),
    EidogramAction.Add(1,"g000002","outline.diamond",FixedTransform(610_000,500_000),1),
))
private fun bundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun raw(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(bundle(d))

fun main(){
    val rnd=SecureRandom()
    val alice1=JvmPrivateIdentity.generate(rnd)
    val bob=JvmPrivateIdentity.generate(rnd)
    val root=JvmRootAuthority.fromPrimary(alice1,rnd)
    val bobRoot=JvmRootAuthority.fromPrimary(bob,rnd)

    val aRepo=InMemoryMessengerRepository()
    val bRepo=InMemoryMessengerRepository()
    val a=LocalMessengerService(aRepo,alice1,rnd)
    val b=LocalMessengerService(bRepo,bob,rnd)
    a.importContact(raw(bob),"Bob",100)
    b.importContact(raw(alice1),"Alice",100)

    val r0=DeviceRosterAuthority.issue(root,null,listOf(alice1.certificate.deviceId),emptyList())
    val br0=DeviceRosterAuthority.issue(bobRoot,null,listOf(bob.certificate.deviceId),emptyList())
    a.importDeviceRoster(DeviceRosterCanonical.json(r0),110)
    a.importDeviceRoster(DeviceRosterCanonical.json(br0),111)
    b.importDeviceRoster(DeviceRosterCanonical.json(r0),110)
    b.importDeviceRoster(DeviceRosterCanonical.json(br0),111)

    val conv=a.createConversation(listOf(bob.user.userId),ByteArray(16){(0x33+it).toByte()},"Golden",1_000)
    b.importConversation(a.conversationDescriptorCanonical(conv.conversationId),"Golden",1_001)

    val legacy=a.send(conv.conversationId,doc(),2_000)
    b.admitIncoming(MessageCanonical.envelopeJson(legacy),2_010)

    val store=InMemoryArchiveStore()
    val archiver=ArchiveCoordinator(aRepo,store,alice1,rnd)
    archiver.archiveAll(2_100)

    val pending=PendingJvmDevice.generate(rnd)
    val req=pending.enrollmentRequest(alice1.user)
    val cert2=DeviceEnrollmentAuthority.authorize(root,req)
    val alice2=pending.install(alice1.user,cert2)
    a.importOwnDevice(raw(alice2),2_200)
    val r1=DeviceRosterAuthority.issue(
        root,r0,listOf(alice1.certificate.deviceId,alice2.certificate.deviceId),emptyList()
    )
    a.importDeviceRoster(DeviceRosterCanonical.json(r1),2_210)

    val packageRaw=archiver.exportPackageCanonical()
    val grants=a.createHistoricalKeyGrants(raw(alice2),2_220)
    val capsuleRaw=MultiDeviceRecoveryCapsuleFactory.create(
        packageRaw,
        listOf(raw(alice1),raw(alice2)),
        listOf(DeviceRosterCanonical.json(r1),DeviceRosterCanonical.json(br0)),
        bundle(alice2),
        grants
    )

    println("ALICE1_BUNDLE="+b64(raw(alice1)))
    println("ALICE2_BUNDLE="+b64(raw(alice2)))
    println("BOB_BUNDLE="+b64(raw(bob)))
    println("ENROLLMENT_REQUEST="+b64(EnrollmentCanonical.json(req)))
    println("ROSTER0="+b64(DeviceRosterCanonical.json(r0)))
    println("ROSTER1="+b64(DeviceRosterCanonical.json(r1)))
    println("BOB_ROSTER0="+b64(DeviceRosterCanonical.json(br0)))
    println("CONVERSATION="+b64(ConversationCanonical.descriptorJson(conv)))
    println("LEGACY_MESSAGE="+b64(MessageCanonical.envelopeJson(legacy)))
    println("R19_PACKAGE="+b64(packageRaw))
    println("R20_CAPSULE="+b64(capsuleRaw))
    println("GRANTS="+b64(grants.joinToString("\n")))
    println("ALICE1_ROOT_PRIVATE="+B64Url.encode(alice1.rootSigning.private.encoded))
    println("ALICE1_SIGN_PRIVATE="+B64Url.encode(alice1.deviceSigning.private.encoded))
    println("ALICE1_ENC_PRIVATE="+B64Url.encode(alice1.deviceEncryption.private.encoded))
    println("ALICE2_SIGN_PRIVATE="+B64Url.encode(alice2.deviceSigning.private.encoded))
    println("ALICE2_ENC_PRIVATE="+B64Url.encode(alice2.deviceEncryption.private.encoded))
    println("BOB_ROOT_PRIVATE="+B64Url.encode(bob.rootSigning.private.encoded))
    println("BOB_SIGN_PRIVATE="+B64Url.encode(bob.deviceSigning.private.encoded))
    println("BOB_ENC_PRIVATE="+B64Url.encode(bob.deviceEncryption.private.encoded))
    println("LEGACY_ID="+legacy.messageId)
    println("DOCUMENT_HASH="+EidogramCanonical.contentHash(doc()))
    println("PACKAGE_ID="+ArchivePackageCodec.parseCanonical(packageRaw).packageId)
    println("CAPSULE_ID="+MultiDeviceRecoveryCapsuleCodec.parseCanonical(capsuleRaw).capsuleId)
}
