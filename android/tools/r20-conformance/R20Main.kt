package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import java.security.SecureRandom

private fun doc(glyph: String, x: Int = 500_000) = EidogramDocumentV1(
    listOf(EidogramAction.Add(0, "g000001", glyph, FixedTransform(x, 500_000), 0))
)

private fun bundle(d: DevicePrivateCrypto) =
    PublicIdentityBundleV1(d.user, d.certificate)

private fun rawBundle(d: DevicePrivateCrypto) =
    IdentityCanonical.publicBundleJson(bundle(d))

fun main() {
    val random = SecureRandom()

    val alice1 = JvmPrivateIdentity.generate(random)
    val bob1 = JvmPrivateIdentity.generate(random)
    val aRoot = JvmRootAuthority.fromPrimary(alice1, random)
    val bRoot = JvmRootAuthority.fromPrimary(bob1, random)

    val aRepo1 = InMemoryMessengerRepository()
    val bRepo1 = InMemoryMessengerRepository()
    val a1 = LocalMessengerService(aRepo1, alice1, random)
    val b1 = LocalMessengerService(bRepo1, bob1, random)

    a1.importContact(rawBundle(bob1), "Bob", 100)
    b1.importContact(rawBundle(alice1), "Alice", 100)

    val aRoster0 = DeviceRosterAuthority.issue(
        aRoot, null, listOf(alice1.certificate.deviceId), emptyList()
    )
    val bRoster0 = DeviceRosterAuthority.issue(
        bRoot, null, listOf(bob1.certificate.deviceId), emptyList()
    )
    a1.importDeviceRoster(DeviceRosterCanonical.json(aRoster0), 110)
    a1.importDeviceRoster(DeviceRosterCanonical.json(bRoster0), 111)
    b1.importDeviceRoster(DeviceRosterCanonical.json(aRoster0), 110)
    b1.importDeviceRoster(DeviceRosterCanonical.json(bRoster0), 111)
    println("PASS initial root-signed single-device rosters")

    val conversation = a1.createConversation(
        listOf(bob1.user.userId),
        seed = ByteArray(16) { (it + 1).toByte() },
        title = "Alice/Bob",
        createdAtMs = 1_000,
    )
    b1.importConversation(a1.conversationDescriptorCanonical(conversation.conversationId), "Alice/Bob", 1_001)

    val legacyDoc = doc("circle.red.m", 420_000)
    val legacy = a1.send(conversation.conversationId, legacyDoc, 2_000)
    check(legacy.body.recipientBoxes.size == 2)
    b1.admitIncoming(MessageCanonical.envelopeJson(legacy), 2_010)
    println("PASS legacy message predates secondary devices")

    // Secondary devices generate private keys themselves and prove possession.
    val aPending = PendingJvmDevice.generate(random)
    val aRequest = aPending.enrollmentRequest(alice1.user)
    check(DeviceEnrollmentAuthority.verifyRequest(aRequest, alice1.user))
    val a2Cert = DeviceEnrollmentAuthority.authorize(aRoot, aRequest)
    val alice2 = aPending.install(alice1.user, a2Cert)

    val bPending = PendingJvmDevice.generate(random)
    val bRequest = bPending.enrollmentRequest(bob1.user)
    check(DeviceEnrollmentAuthority.verifyRequest(bRequest, bob1.user))
    val b2Cert = DeviceEnrollmentAuthority.authorize(bRoot, bRequest)
    val bob2 = bPending.install(bob1.user, b2Cert)

    val brokenRequest = aRequest.copy(requestId = "0".repeat(64))
    check(!DeviceEnrollmentAuthority.verifyRequest(brokenRequest, alice1.user))
    println("PASS root-authorized secondary-device enrollment with possession proof")

    // Register sibling public devices and contact devices.
    a1.importOwnDevice(rawBundle(alice2), 3_000)
    a1.importContact(rawBundle(bob2), "Bob", 3_000)
    b1.importOwnDevice(rawBundle(bob2), 3_000)
    b1.importContact(rawBundle(alice2), "Alice", 3_000)

    val aRoster1 = DeviceRosterAuthority.issue(
        aRoot, aRoster0,
        listOf(alice1.certificate.deviceId, alice2.certificate.deviceId),
        emptyList(),
    )
    val bRoster1 = DeviceRosterAuthority.issue(
        bRoot, bRoster0,
        listOf(bob1.certificate.deviceId, bob2.certificate.deviceId),
        emptyList(),
    )
    listOf(a1).forEach {
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster1), 3_010)
        it.importDeviceRoster(DeviceRosterCanonical.json(bRoster1), 3_011)
    }
    listOf(b1).forEach {
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster1), 3_010)
        it.importDeviceRoster(DeviceRosterCanonical.json(bRoster1), 3_011)
    }
    check(a1.contactSummaries().single().deviceCount == 2)
    check(b1.contactSummaries().single().deviceCount == 2)
    println("PASS explicit multi-device roster activates certified sibling/contact devices")

    // Bring the two secondary devices online with their own independent repositories.
    val aRepo2 = InMemoryMessengerRepository()
    val bRepo2 = InMemoryMessengerRepository()
    val a2 = LocalMessengerService(aRepo2, alice2, random)
    val b2 = LocalMessengerService(bRepo2, bob2, random)

    a2.importOwnDevice(rawBundle(alice1), 3_020)
    a2.importContact(rawBundle(bob1), "Bob", 3_020)
    a2.importContact(rawBundle(bob2), "Bob", 3_020)
    b2.importOwnDevice(rawBundle(bob1), 3_020)
    b2.importContact(rawBundle(alice1), "Alice", 3_020)
    b2.importContact(rawBundle(alice2), "Alice", 3_020)

    listOf(a2).forEach {
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster0), 3_021)
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster1), 3_022)
        it.importDeviceRoster(DeviceRosterCanonical.json(bRoster0), 3_023)
        it.importDeviceRoster(DeviceRosterCanonical.json(bRoster1), 3_024)
        it.importConversation(a1.conversationDescriptorCanonical(conversation.conversationId), "Alice/Bob", 3_025)
    }
    listOf(b2).forEach {
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster0), 3_021)
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster1), 3_022)
        it.importDeviceRoster(DeviceRosterCanonical.json(bRoster0), 3_023)
        it.importDeviceRoster(DeviceRosterCanonical.json(bRoster1), 3_024)
        it.importConversation(a1.conversationDescriptorCanonical(conversation.conversationId), "Alice/Bob", 3_025)
    }

    // Historical static-key migration without changing MessageId.
    val aLegacyGrant = a1.createHistoricalKeyGrants(rawBundle(alice2), 3_100)
        .single { MessageKeyGrantCanonical.parseCanonical(it).body.messageId == legacy.messageId }
    val bLegacyGrant = b1.createHistoricalKeyGrants(rawBundle(bob2), 3_100)
        .single { MessageKeyGrantCanonical.parseCanonical(it).body.messageId == legacy.messageId }

    check(a2.admitHistoricalWithGrant(MessageCanonical.envelopeJson(legacy), aLegacyGrant, 3_110) == InsertMessageResult.Inserted)
    check(b2.admitHistoricalWithGrant(MessageCanonical.envelopeJson(legacy), bLegacyGrant, 3_110) == InsertMessageResult.Inserted)
    check(EidogramCanonical.contentHash(a2.timeline(conversation.conversationId).single().document) ==
        EidogramCanonical.contentHash(legacyDoc))
    check(legacy.messageId == MessageParser.parseCanonical(MessageCanonical.envelopeJson(legacy)).messageId)
    println("PASS historical key grants migrate legacy ciphertext without changing MessageId")

    // New traffic is encrypted for all active devices of both users.
    val syncBaseDoc = doc("outline.circle", 500_000)
    val syncBase = a1.send(conversation.conversationId, syncBaseDoc, 4_000)
    val keyIds = syncBase.body.recipientBoxes.map { it.encryptionKeyId }.toSet()
    check(keyIds == setOf(
        alice1.certificate.body.encryptionKeyId,
        alice2.certificate.body.encryptionKeyId,
        bob1.certificate.body.encryptionKeyId,
        bob2.certificate.body.encryptionKeyId,
    ))
    a2.admitIncoming(MessageCanonical.envelopeJson(syncBase), 4_010)
    b1.admitIncoming(MessageCanonical.envelopeJson(syncBase), 4_010)
    b2.admitIncoming(MessageCanonical.envelopeJson(syncBase), 4_010)
    println("PASS new message carries key boxes for all active local and remote devices")

    // Alice devices now branch concurrently from the same head.
    val aBranch = a1.send(conversation.conversationId, doc("outline.triangle", 350_000), 5_000)
    val a2Branch = a2.send(conversation.conversationId, doc("circle.violet.s", 650_000), 5_001)
    check(aBranch.body.aad.parentMessageIds == listOf(syncBase.messageId))
    check(a2Branch.body.aad.parentMessageIds == listOf(syncBase.messageId))
    check(aBranch.body.aad.senderDeviceId != a2Branch.body.aad.senderDeviceId)

    a1.admitIncoming(MessageCanonical.envelopeJson(a2Branch), 5_010)
    a2.admitIncoming(MessageCanonical.envelopeJson(aBranch), 5_010)
    b1.admitIncoming(MessageCanonical.envelopeJson(aBranch), 5_010)
    b1.admitIncoming(MessageCanonical.envelopeJson(a2Branch), 5_011)
    b2.admitIncoming(MessageCanonical.envelopeJson(aBranch), 5_010)
    b2.admitIncoming(MessageCanonical.envelopeJson(a2Branch), 5_011)

    val expectedBranchHeads = setOf(aBranch.messageId, a2Branch.messageId)
    check(a1.currentHeads(conversation.conversationId).toSet() == expectedBranchHeads)
    check(a2.currentHeads(conversation.conversationId).toSet() == expectedBranchHeads)
    check(b1.currentHeads(conversation.conversationId).toSet() == expectedBranchHeads)
    check(b2.currentHeads(conversation.conversationId).toSet() == expectedBranchHeads)
    println("PASS concurrent same-user devices converge to identical branch-head set")

    val merge = a1.send(conversation.conversationId, doc("stick.black.pose090.l.thick"), 6_000)
    check(merge.body.aad.parentMessageIds.toSet() == expectedBranchHeads)
    a2.admitIncoming(MessageCanonical.envelopeJson(merge), 6_010)
    b1.admitIncoming(MessageCanonical.envelopeJson(merge), 6_010)
    b2.admitIncoming(MessageCanonical.envelopeJson(merge), 6_010)
    listOf(a1,a2,b1,b2).forEach {
        check(it.currentHeads(conversation.conversationId) == listOf(merge.messageId))
    }
    val ids = a1.timeline(conversation.conversationId).map { it.messageId }
    check(ids == a2.timeline(conversation.conversationId).map { it.messageId })
    check(ids == b1.timeline(conversation.conversationId).map { it.messageId })
    check(ids == b2.timeline(conversation.conversationId).map { it.messageId })
    println("PASS merge message collapses concurrent branches and all devices have identical causal timeline")

    // Prospective revocation: old accepted history stays valid, new messages are rejected.
    val aRoster2 = DeviceRosterAuthority.issue(
        aRoot, aRoster1,
        listOf(alice1.certificate.deviceId),
        listOf(alice2.certificate.deviceId),
    )
    listOf(a1,a2,b1,b2).forEach {
        it.importDeviceRoster(DeviceRosterCanonical.json(aRoster2), 7_000)
    }
    check(!a1.isDeviceActive(alice1.user.userId, alice2.certificate.deviceId))
    check(runCatching { a2.send(conversation.conversationId, doc("dot.black.m"), 7_100) }.isFailure)

    val revokedNew = MessageCrypto.seal(
        document = doc("circle.blue.l"),
        conversation = conversation,
        sender = alice2,
        recipients = listOf(
            MessageCrypto.recipient(alice1.certificate),
            MessageCrypto.recipient(bob1.certificate),
            MessageCrypto.recipient(bob2.certificate),
        ),
        senderSeq = 1,
        createdAtMs = 7_101,
        parentMessageIds = listOf(merge.messageId),
        random = random,
    )
    check(runCatching {
        a1.admitIncoming(MessageCanonical.envelopeJson(revokedNew), 7_110)
    }.isFailure)
    check(a1.admitIncoming(MessageCanonical.envelopeJson(a2Branch), 7_111) == InsertMessageResult.Duplicate)
    check(a1.timeline(conversation.conversationId).any { it.messageId == a2Branch.messageId })
    println("PASS revocation blocks new device messages but preserves/idempotently replays accepted history")

    val postRevocation = a1.send(conversation.conversationId, doc("outline.square"), 8_000)
    check(postRevocation.body.recipientBoxes.none {
        it.encryptionKeyId == alice2.certificate.body.encryptionKeyId
    })
    println("PASS revoked device removed from future recipient key boxes")

    check(runCatching {
        a1.importDeviceRoster(DeviceRosterCanonical.json(aRoster1), 8_100)
    }.isFailure)
    println("PASS roster rollback/re-activation attempt rejected")

    println("ALICE_USER_ID=${alice1.user.userId}")
    println("ALICE_DEVICE_1=${alice1.certificate.deviceId}")
    println("ALICE_DEVICE_2=${alice2.certificate.deviceId}")
    println("BOB_DEVICE_1=${bob1.certificate.deviceId}")
    println("BOB_DEVICE_2=${bob2.certificate.deviceId}")
    println("FINAL_HEAD=${postRevocation.messageId}")
    println("ALL R20 MULTI-DEVICE CONFORMANCE CHECKS PASS")
}
