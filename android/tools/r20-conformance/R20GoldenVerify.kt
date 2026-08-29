package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import java.security.KeyPair

fun main(){
    val a1Bundle=IdentityParser.parsePublicBundleCanonical(GoldenR20.ALICE1_BUNDLE)
    val a2Bundle=IdentityParser.parsePublicBundleCanonical(GoldenR20.ALICE2_BUNDLE)
    val bobBundle=IdentityParser.parsePublicBundleCanonical(GoldenR20.BOB_BUNDLE)

    check(IdentityVerifier.verifyBundle(a1Bundle))
    check(IdentityVerifier.verifyBundle(a2Bundle))
    check(IdentityVerifier.verifyBundle(bobBundle))
    println("PASS fixed R20 identity bundles")

    val req=EnrollmentCanonical.parseCanonical(GoldenR20.ENROLLMENT_REQUEST)
    check(DeviceEnrollmentAuthority.verifyRequest(req,a1Bundle.user))
    check(req.body.signingKeyId==a2Bundle.device.body.signingKeyId)
    check(req.body.encryptionKeyId==a2Bundle.device.body.encryptionKeyId)
    println("PASS fixed R20 enrollment possession proof")

    val r0=DeviceRosterCanonical.parseCanonical(GoldenR20.ROSTER0)
    val r1=DeviceRosterCanonical.parseCanonical(GoldenR20.ROSTER1)
    check(DeviceRosterVerifier.verifySignature(r0,a1Bundle.user))
    check(DeviceRosterVerifier.verifySignature(r1,a1Bundle.user))
    DeviceRosterVerifier.advance(
        r0,r1,a1Bundle.user,setOf(a1Bundle.device.deviceId,a2Bundle.device.deviceId)
    )
    println("PASS fixed R20 root-signed roster chain")

    val a1=JvmPrivateIdentity.fromEncoded(
        rootPrivateB64=GoldenR20.A1_ROOT_PRIVATE,
        rootPublicB64=a1Bundle.user.rootSigningPublicKeyB64,
        deviceSigningPrivateB64=GoldenR20.A1_SIGN_PRIVATE,
        deviceSigningPublicB64=a1Bundle.device.body.signingPublicKeyB64,
        deviceEncryptionPrivateB64=GoldenR20.A1_ENC_PRIVATE,
        deviceEncryptionPublicB64=a1Bundle.device.body.encryptionPublicKeyB64,
        certificate=a1Bundle.device,
    )
    val a2=JvmSecondaryDevice(
        KeyPair(
            PublicKeyCodec.ec(a2Bundle.device.body.signingPublicKeyB64),
            PublicKeyCodec.privateEc(GoldenR20.A2_SIGN_PRIVATE),
        ),
        KeyPair(
            PublicKeyCodec.rsa(a2Bundle.device.body.encryptionPublicKeyB64),
            PublicKeyCodec.privateRsa(GoldenR20.A2_ENC_PRIVATE),
        ),
        a2Bundle.user,
        a2Bundle.device,
    )

    val legacy=MessageParser.parseCanonical(GoldenR20.LEGACY_MESSAGE)
    check(legacy.messageId==GoldenR20.LEGACY_ID)
    val conv=ConversationParser.parseCanonical(GoldenR20.CONVERSATION)
    check(MessageCrypto.verify(legacy,a1Bundle.user,a1Bundle.device,conv))

    val grant=GoldenR20.GRANTS.map(MessageKeyGrantCanonical::parseCanonical)
        .single{it.body.messageId==legacy.messageId}
    check(MessageKeyGrantCrypto.verify(grant,legacy,a1Bundle,a2Bundle))
    val opened=MessageKeyGrantCrypto.open(legacy,a1Bundle,conv,grant,a1Bundle,a2)
    check(EidogramCanonical.contentHash(opened)==GoldenR20.DOCUMENT_HASH)
    println("PASS fixed R20 historical grant decrypts legacy message")

    val pack=ArchivePackageCodec.parseCanonical(GoldenR20.R19_PACKAGE)
    val capsule=MultiDeviceRecoveryCapsuleCodec.parseCanonical(GoldenR20.R20_CAPSULE)
    check(pack.packageId==GoldenR20.PACKAGE_ID)
    check(capsule.capsuleId==GoldenR20.CAPSULE_ID)

    val repo=InMemoryMessengerRepository()
    val store=InMemoryArchiveStore()
    val report=MultiDeviceRecoveryCoordinator(repo,store,a2)
        .restore(GoldenR20.R19_PACKAGE,GoldenR20.R20_CAPSULE,9_000)
    check(report.uniqueMessageCount==1)
    val svc=LocalMessengerService(repo,a2)
    val timeline=svc.timeline(conv.conversationId)
    check(timeline.size==1 && timeline.single().messageId==legacy.messageId)
    check(EidogramCanonical.contentHash(timeline.single().document)==GoldenR20.DOCUMENT_HASH)
    println("PASS fixed R20 cross-device recovery capsule")

    val tampered=grant.copy(body=grant.body.copy(targetDeviceId=a1Bundle.device.deviceId))
    check(!MessageKeyGrantCrypto.verify(tampered,legacy,a1Bundle,a2Bundle))
    println("PASS fixed R20 key-grant target tamper rejection")

    println("ALL R20 FIXED GOLDEN CHECKS PASS")
}
