package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.repository.*
import java.security.SecureRandom

private fun doc(glyph: String, x: Int = 500_000): EidogramDocumentV1 =
    EidogramDocumentV1(
        listOf(
            EidogramAction.Add(
                0,
                "g000001",
                glyph,
                FixedTransform(x, 500_000),
                0,
            )
        )
    )

fun main() {
    val random = SecureRandom()
    val aliceIdentity = JvmPrivateIdentity.generate(random)
    val bobIdentity = JvmPrivateIdentity.generate(random)

    val aliceRepo = InMemoryMessengerRepository()
    val bobRepo = InMemoryMessengerRepository()
    val alice = LocalMessengerService(aliceRepo, aliceIdentity, random)
    val bob = LocalMessengerService(bobRepo, bobIdentity, random)

    alice.importContact(bob.exportLocalIdentityCanonical(), "Bob", 100)
    bob.importContact(alice.exportLocalIdentityCanonical(), "Alice", 100)
    check(alice.contactSummaries().single().alias == "Bob")
    check(bob.contactSummaries().single().alias == "Alice")
    println("PASS contact identity import and local alias")

    val resignedBobCert = IdentityFactory.certificate(
        bobIdentity.certificate.body,
        bobIdentity.rootSigning.private,
        random,
    )
    val resignedBobBundle = PublicIdentityBundleV1(bobIdentity.user, resignedBobCert)
    check(IdentityVerifier.verifyBundle(resignedBobBundle))
    alice.importContact(IdentityCanonical.publicBundleJson(resignedBobBundle), "Bob", 150)
    check(alice.contactSummaries().single().deviceCount == 1)
    println("PASS refreshed ECDSA certificate proof does not fork contact identity")

    val conversationA = alice.createConversation(
        remoteUserIds = listOf(bobIdentity.user.userId),
        seed = ByteArray(16) { (it + 1).toByte() },
        title = "Alice ↔ Bob",
        createdAtMs = 1_000,
    )
    val conversationB = bob.importConversation(
        ConversationCanonical.descriptorJson(conversationA),
        title = "Alice ↔ Bob",
        createdAtMs = 1_001,
    )
    check(conversationA == conversationB)
    println("PASS shared canonical conversation descriptor")

    val firstDoc = doc("circle.red.m", 400_000)
    val first = alice.send(conversationA.conversationId, firstDoc, 2_000)
    check(first.body.aad.senderSeq == 0)
    check(first.body.aad.parentMessageIds.isEmpty())
    check(alice.currentHeads(conversationA.conversationId) == listOf(first.messageId))
    println("PASS first outgoing message committed as DAG head")

    val firstRaw = MessageCanonical.envelopeJson(first)
    check(bob.admitIncoming(firstRaw, 2_100) == InsertMessageResult.Inserted)
    val bobTimeline1 = bob.timeline(conversationB.conversationId)
    check(bobTimeline1.size == 1)
    check(!bobTimeline1.single().outgoing)
    check(EidogramCanonical.contentHash(bobTimeline1.single().document) == EidogramCanonical.contentHash(firstDoc))
    println("PASS manual incoming admission decrypts exact eidogram")

    check(bob.admitIncoming(firstRaw, 2_200) == InsertMessageResult.Duplicate)
    check(bob.timeline(conversationB.conversationId).size == 1)
    println("PASS exact message replay is idempotent")

    val replyDoc = doc("stick.black.pose045.m.normal", 600_000)
    val reply = bob.send(conversationB.conversationId, replyDoc, 3_000)
    check(reply.body.aad.senderSeq == 0)
    check(reply.body.aad.parentMessageIds == listOf(first.messageId))
    check(alice.admitIncoming(MessageCanonical.envelopeJson(reply), 3_100) == InsertMessageResult.Inserted)
    val aliceTimeline2 = alice.timeline(conversationA.conversationId)
    check(aliceTimeline2.map { it.messageId } == listOf(first.messageId, reply.messageId))
    println("PASS reply parent edge produces deterministic causal timeline")

    val thirdDoc = doc("outline.triangle")
    val third = alice.send(conversationA.conversationId, thirdDoc, 4_000)
    check(third.body.aad.senderSeq == 1)
    check(third.body.aad.parentMessageIds == listOf(reply.messageId))
    check(alice.currentHeads(conversationA.conversationId) == listOf(third.messageId))
    println("PASS sender sequence increments and current head advances")

    val equivocation = MessageCrypto.seal(
        document = doc("circle.blue.l"),
        conversation = conversationA,
        sender = bobIdentity,
        recipients = listOf(MessageCrypto.recipient(aliceIdentity.certificate)),
        senderSeq = 0, // Bob already used seq=0 for reply
        createdAtMs = 4_500,
        random = random,
    )
    check(runCatching {
        alice.admitIncoming(MessageCanonical.envelopeJson(equivocation), 4_600)
    }.isFailure)
    println("PASS same sender/device sequence with different message is rejected")

    val noAliceRecipient = MessageCrypto.seal(
        document = doc("dot.black.m"),
        conversation = conversationA,
        sender = bobIdentity,
        recipients = emptyList(), // MessageCrypto adds Bob itself, not Alice.
        senderSeq = 1,
        createdAtMs = 4_700,
        random = random,
    )
    check(runCatching {
        alice.admitIncoming(MessageCanonical.envelopeJson(noAliceRecipient), 4_800)
    }.isFailure)
    println("PASS incoming message without local recipient key box is rejected")

    val charlie = JvmPrivateIdentity.generate(random)
    val unknownParticipantConversation = ConversationFactory.fromSeed(
        ByteArray(16) { (0x50 + it).toByte() },
        listOf(aliceIdentity.user.userId, charlie.user.userId),
    )
    check(runCatching {
        alice.importConversation(
            ConversationCanonical.descriptorJson(unknownParticipantConversation),
            createdAtMs = 5_000,
        )
    }.isFailure)
    println("PASS conversation import requires known participant identities")

    // Out-of-order arrival is allowed: uniqueness, not max-sequence, is the replay invariant.
    val branchA = alice.createConversation(
        remoteUserIds = listOf(bobIdentity.user.userId),
        seed = ByteArray(16) { (0x70 + it).toByte() },
        title = "Branch test",
        createdAtMs = 6_000,
    )
    bob.importConversation(ConversationCanonical.descriptorJson(branchA), "Branch test", 6_001)

    val bobSeq1 = MessageCrypto.seal(
        document = doc("outline.circle", 650_000),
        conversation = branchA,
        sender = bobIdentity,
        recipients = listOf(MessageCrypto.recipient(aliceIdentity.certificate)),
        senderSeq = 1,
        createdAtMs = 7_100,
        parentMessageIds = emptyList(),
        random = random,
    )
    val bobSeq0 = MessageCrypto.seal(
        document = doc("outline.square", 350_000),
        conversation = branchA,
        sender = bobIdentity,
        recipients = listOf(MessageCrypto.recipient(aliceIdentity.certificate)),
        senderSeq = 0,
        createdAtMs = 7_000,
        parentMessageIds = emptyList(),
        random = random,
    )
    alice.admitIncoming(MessageCanonical.envelopeJson(bobSeq1), 7_200)
    alice.admitIncoming(MessageCanonical.envelopeJson(bobSeq0), 7_300)
    val branchTimeline = alice.timeline(branchA.conversationId)
    check(branchTimeline.map { it.senderSeq } == listOf(0, 1))
    check(alice.currentHeads(branchA.conversationId).toSet() == setOf(bobSeq0.messageId, bobSeq1.messageId))
    println("PASS out-of-order arrival retained and deterministically reordered")

    val merge = alice.send(branchA.conversationId, doc("circle.violet.s"), 8_000)
    check(merge.body.aad.parentMessageIds.toSet() == setOf(bobSeq0.messageId, bobSeq1.messageId))
    check(alice.currentHeads(branchA.conversationId) == listOf(merge.messageId))
    println("PASS new local message merges all current DAG branches")

    val summary = alice.conversationSummaries().first { it.conversationId == conversationA.conversationId }
    check(summary.messageCount == 3)
    check(summary.headMessageIds == listOf(third.messageId))
    println("PASS conversation summary derives message count and heads")

    println("ALICE_USER_ID=${aliceIdentity.user.userId}")
    println("BOB_USER_ID=${bobIdentity.user.userId}")
    println("PRIMARY_CONVERSATION_ID=${conversationA.conversationId}")
    println("PRIMARY_HEAD=${third.messageId}")
    println("BRANCH_MERGE_HEAD=${merge.messageId}")
    println("ALL R18 PURE-KOTLIN MESSENGER/REPOSITORY CHECKS PASS")
}
