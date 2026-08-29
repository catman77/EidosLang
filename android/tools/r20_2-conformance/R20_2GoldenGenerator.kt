package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*
import java.security.SecureRandom
import java.util.Base64

private fun b64(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
private fun bundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun raw(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(bundle(d))
private fun doc()=EidogramDocumentV1(listOf(
    EidogramAction.Add(0,"g000001","circle.cyan.m",FixedTransform(430_000,500_000),0),
    EidogramAction.Add(1,"g000002","outline.diamond",FixedTransform(610_000,520_000),1),
))

fun main(){
    val rnd=SecureRandom()
    val alice=JvmPrivateIdentity.generate(rnd)
    val bob=JvmPrivateIdentity.generate(rnd)
    val root=JvmRootAuthority.fromPrimary(alice,rnd)

    val pending=PendingJvmDevice.generate(rnd)
    val req=pending.enrollmentRequest(alice.user)
    val a2Cert=DeviceEnrollmentAuthority.authorize(root,req)
    val alice2=pending.install(alice.user,a2Cert)

    val repo=InMemoryMessengerRepository()
    val bRepo=InMemoryMessengerRepository()
    val a=LocalMessengerService(repo,alice,rnd)
    val b=LocalMessengerService(bRepo,bob,rnd)
    a.importOwnDevice(raw(alice2),100)
    a.importContact(raw(bob),"Bob",100)
    b.importContact(raw(alice),"Alice",100)
    b.importContact(raw(alice2),"Alice",100)

    val r0=DeviceRosterAuthority.issue(
        root,null,listOf(alice.certificate.deviceId,alice2.certificate.deviceId),emptyList()
    )
    a.importDeviceRoster(DeviceRosterCanonical.json(r0),110)
    b.importDeviceRoster(DeviceRosterCanonical.json(r0),110)

    val conv=a.createConversation(
        listOf(bob.user.userId),ByteArray(16){(0x31+it).toByte()},"Golden vault",1_000
    )
    b.importConversation(a.conversationDescriptorCanonical(conv.conversationId),"Golden vault",1_001)
    val message=a.send(conv.conversationId,doc(),2_000)
    b.admitIncoming(MessageCanonical.envelopeJson(message),2_010)

    val secret=HistoryVaultRecoverySecret.fromBytes(ByteArray(32){(it+1).toByte()})
    val controller=HistoryVaultController.create(
        alice,secret,ByteArray(16){(0x71+it).toByte()},rnd
    )
    val epoch=controller.createEpochFromRoster(DeviceRosterCanonical.json(r0))
    check(controller.archiveAll(repo,a)==1)
    val pkg=controller.exportPackage(repo)
    val grant=HistoryVaultDeviceGrantCrypto.issue(
        controller.descriptor,controller.openEpochForDeviceGrant(epoch.epochId),
        alice,bundle(alice2),rnd
    )

    val parsed=HistoryVaultArchivePackageCodec.parseCanonical(pkg)
    val entry=parsed.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical).single()

    println("ALICE_BUNDLE="+b64(raw(alice)))
    println("ALICE2_BUNDLE="+b64(raw(alice2)))
    println("BOB_BUNDLE="+b64(raw(bob)))
    println("RECOVERY_SECRET_EXPORT="+b64(controller.recoverySecretExportCanonical()))
    println("VAULT_PACKAGE="+b64(pkg))
    println("DEVICE_GRANT="+b64(HistoryVaultDeviceGrantCodec.json(grant)))
    println("ALICE2_ENC_PRIVATE="+B64Url.encode(alice2.deviceEncryption.private.encoded))
    println("ALICE2_SIGN_PRIVATE="+B64Url.encode(alice2.deviceSigning.private.encoded))
    println("MESSAGE_ID="+message.messageId)
    println("DOCUMENT_HASH="+EidogramCanonical.contentHash(doc()))
    println("VAULT_ID="+controller.descriptor.vaultId)
    println("EPOCH_ID="+epoch.epochId)
    println("ENTRY_ID="+entry.entryId)
    println("PACKAGE_ID="+parsed.packageId)
}
