package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import java.security.SecureRandom

private fun sampleDocument() = EidogramDocumentV1(listOf(
    EidogramAction.Add(
        0,
        "g000001",
        "circle.red.m",
        FixedTransform(420_000, 500_000),
        0,
    ),
    EidogramAction.Add(
        1,
        "g000002",
        "stick.black.pose045.m.normal",
        FixedTransform(580_000, 500_000),
        1,
    ),
))

fun main() {
    val random = SecureRandom()
    val alice = JvmPrivateIdentity.generate(random)
    val bob = JvmPrivateIdentity.generate(random)

    check(IdentityVerifier.verifyDevice(alice.user, alice.certificate))
    check(IdentityVerifier.verifyDevice(bob.user, bob.certificate))
    println("PASS identity root/device certificate verification")

    val aliceBundle = PublicIdentityBundleV1(alice.user, alice.certificate)
    val bundleJson = IdentityCanonical.publicBundleJson(aliceBundle)
    check(IdentityParser.parsePublicBundleCanonical(bundleJson) == aliceBundle)
    println("PASS canonical public identity bundle round-trip")

    val conversation = ConversationFactory.fromSeed(
        ByteArray(16) { (it + 1).toByte() },
        listOf(alice.user.userId, bob.user.userId),
    )
    val conversationJson = ConversationCanonical.descriptorJson(conversation)
    check(ConversationParser.parseCanonical(conversationJson) == conversation)
    println("PASS canonical conversation descriptor round-trip")

    val doc = sampleDocument()
    val docHash = EidogramCanonical.contentHash(doc)

    val message = MessageCrypto.seal(
        document = doc,
        conversation = conversation,
        sender = alice,
        recipients = listOf(
            MessageCrypto.recipient(bob.certificate),
        ),
        senderSeq = 0,
        createdAtMs = 1_786_400_000_000L,
        random = random,
    )
    check(message.body.recipientBoxes.size == 2) // Alice + Bob
    check(message.body.recipientBoxes.map { it.encryptionKeyId } == message.body.aad.recipientEncryptionKeyIds)
    check(MessageCrypto.verify(message, alice.user, alice.certificate, conversation))
    println("PASS signed encrypted message authentication")

    val messageJson = MessageCanonical.envelopeJson(message)
    val parsed = MessageParser.parseCanonical(messageJson)
    check(MessageCanonical.envelopeJson(parsed) == messageJson)
    check(parsed.messageId == message.messageId)
    println("PASS canonical message envelope round-trip")

    val openedByBob = MessageCrypto.open(parsed, alice.user, alice.certificate, bob, conversation)
    val openedByAlice = MessageCrypto.open(parsed, alice.user, alice.certificate, alice, conversation)
    check(EidogramCanonical.contentHash(openedByBob) == docHash)
    check(EidogramCanonical.contentHash(openedByAlice) == docHash)
    println("PASS recipient and sender decrypt exact canonical eidogram")

    val tamperedCiphertext = parsed.copy(
        body = parsed.body.copy(
            ciphertextB64 = parsed.body.ciphertextB64.dropLast(1) +
                if (parsed.body.ciphertextB64.last() == 'A') "B" else "A"
        )
    )
    check(!MessageCrypto.verify(tamperedCiphertext, alice.user, alice.certificate, conversation))
    println("PASS ciphertext tamper rejected by signed body hash/signature")

    val tamperedSeq = parsed.copy(body = parsed.body.copy(aad = parsed.body.aad.copy(senderSeq = 1)))
    check(!MessageCrypto.verify(tamperedSeq, alice.user, alice.certificate, conversation))
    println("PASS metadata tamper rejected")

    val wrongConversation = ConversationFactory.fromSeed(
        ByteArray(16) { (it + 2).toByte() },
        listOf(alice.user.userId, bob.user.userId),
    )
    check(!MessageCrypto.verify(parsed, alice.user, alice.certificate, wrongConversation))
    println("PASS cross-conversation replay binding")

    val nonCanonical = messageJson.replaceFirst("{", "{ ")
    check(runCatching { MessageParser.parseCanonical(nonCanonical) }.isFailure)
    println("PASS non-canonical message rejected")

    val bobKey = bob.certificate.body.encryptionKeyId
    val bobBox = parsed.body.recipientBoxes.single { it.encryptionKeyId == bobKey }
    val wrongBob = JvmPrivateIdentity.generate(random)
    check(runCatching {
        val key = wrongBob.unwrapMessageKey(B64Url.decode(bobBox.wrappedKeyB64))
        key.fill(0)
    }.isFailure)
    println("PASS wrong private key cannot unwrap recipient key")

    println("MESSAGE_ID=${message.messageId}")
    println("CONVERSATION_ID=${conversation.conversationId}")
    println("ALICE_USER_ID=${alice.user.userId}")
    println("ALICE_DEVICE_ID=${alice.certificate.deviceId}")
    println("BOB_USER_ID=${bob.user.userId}")
    println("BOB_DEVICE_ID=${bob.certificate.deviceId}")
    println("PLAINTEXT_CONTENT_HASH=$docHash")
    println("ALL R15 PURE-KOTLIN/JCA CONFORMANCE CHECKS PASS")
}
