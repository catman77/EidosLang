package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*

private fun restore(
    bundle: PublicIdentityBundleV1,
    rootPrivate: String,
    signPrivate: String,
    encPrivate: String,
): JvmPrivateIdentity =
    JvmPrivateIdentity.fromEncoded(
        rootPrivateB64 = rootPrivate,
        rootPublicB64 = bundle.user.rootSigningPublicKeyB64,
        deviceSigningPrivateB64 = signPrivate,
        deviceSigningPublicB64 = bundle.device.body.signingPublicKeyB64,
        deviceEncryptionPrivateB64 = encPrivate,
        deviceEncryptionPublicB64 = bundle.device.body.encryptionPublicKeyB64,
        certificate = bundle.device,
    )

fun main() {
    val aliceBundle = IdentityParser.parsePublicBundleCanonical(GoldenR15.ALICE_BUNDLE_JSON)
    val bobBundle = IdentityParser.parsePublicBundleCanonical(GoldenR15.BOB_BUNDLE_JSON)
    val conversation = ConversationParser.parseCanonical(GoldenR15.CONVERSATION_JSON)
    val message = MessageParser.parseCanonical(GoldenR15.MESSAGE_JSON)
    check(message.messageId == GoldenR15.MESSAGE_ID)

    val alice = restore(
        aliceBundle,
        GoldenR15.ALICE_ROOT_PRIVATE_B64,
        GoldenR15.ALICE_SIGN_PRIVATE_B64,
        GoldenR15.ALICE_ENC_PRIVATE_B64,
    )
    val bob = restore(
        bobBundle,
        GoldenR15.BOB_ROOT_PRIVATE_B64,
        GoldenR15.BOB_SIGN_PRIVATE_B64,
        GoldenR15.BOB_ENC_PRIVATE_B64,
    )

    check(MessageCrypto.verify(message, aliceBundle.user, aliceBundle.device, conversation))
    val docBob = MessageCrypto.open(message, aliceBundle.user, aliceBundle.device, bob, conversation)
    val docAlice = MessageCrypto.open(message, aliceBundle.user, aliceBundle.device, alice, conversation)
    check(EidogramCanonical.contentHash(docBob) == GoldenR15.DOCUMENT_CONTENT_HASH)
    check(EidogramCanonical.snapshotHash(docBob) == GoldenR15.DOCUMENT_SNAPSHOT_HASH)
    check(EidogramCanonical.contentHash(docAlice) == GoldenR15.DOCUMENT_CONTENT_HASH)

    val brokenSignature = message.copy(
        signature = message.signature.copy(
            valueB64 = message.signature.valueB64.dropLast(1) +
                if (message.signature.valueB64.last() == 'A') "B" else "A"
        )
    )
    check(!MessageCrypto.verify(brokenSignature, aliceBundle.user, aliceBundle.device, conversation))

    val wrongConversation = ConversationFactory.fromSeed(
        ByteArray(16) { (0xB0 + it).toByte() },
        conversation.participantUserIds,
    )
    check(!MessageCrypto.verify(message, aliceBundle.user, aliceBundle.device, wrongConversation))

    println("PASS R15 fixed golden public identity validation")
    println("PASS R15 fixed golden message signature verification")
    println("PASS R15 fixed golden RSA-OAEP/AES-GCM decryption for Bob")
    println("PASS R15 fixed golden sender self-decryption")
    println("PASS R15 fixed golden document hash/snapshot recovery")
    println("PASS R15 fixed golden signature tamper rejection")
    println("PASS R15 fixed golden conversation-binding rejection")
    println("ALL R15 FIXED GOLDEN CHECKS PASS")
}
