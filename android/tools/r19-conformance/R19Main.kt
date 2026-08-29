package org.eidolang.tools

import org.eidolang.core.archive.ConversationSegmentBuilder
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.recovery.*
import org.eidolang.core.repository.*
import java.security.KeyPair
import java.security.SecureRandom

private fun doc(glyph: String, x: Int = 500_000): EidogramDocumentV1 = EidogramDocumentV1(listOf(
    EidogramAction.Add(0, "g000001", glyph, FixedTransform(x, 500_000), 0)
))

private fun setup(random: SecureRandom): Triple<JvmPrivateIdentity, JvmPrivateIdentity, Pair<LocalMessengerService,LocalMessengerService>> {
    val aliceId = JvmPrivateIdentity.generate(random)
    val bobId = JvmPrivateIdentity.generate(random)
    val alice = LocalMessengerService(InMemoryMessengerRepository(), aliceId, random)
    val bob = LocalMessengerService(InMemoryMessengerRepository(), bobId, random)
    alice.importContact(bob.exportLocalIdentityCanonical(), "Bob", 100)
    bob.importContact(alice.exportLocalIdentityCanonical(), "Alice", 100)
    return Triple(aliceId, bobId, alice to bob)
}

fun main() {
    val random = SecureRandom()
    val aliceId = JvmPrivateIdentity.generate(random)
    val bobId = JvmPrivateIdentity.generate(random)
    val aliceRepo = InMemoryMessengerRepository()
    val bobRepo = InMemoryMessengerRepository()
    val alice = LocalMessengerService(aliceRepo, aliceId, random)
    val bob = LocalMessengerService(bobRepo, bobId, random)
    alice.importContact(bob.exportLocalIdentityCanonical(), "Bob", 100)
    bob.importContact(alice.exportLocalIdentityCanonical(), "Alice", 100)

    val conversation = alice.createConversation(
        listOf(bobId.user.userId),
        seed = ByteArray(16) { (it + 1).toByte() },
        title = "Основной диалог",
        createdAtMs = 1_000,
    )
    bob.importConversation(ConversationCanonical.descriptorJson(conversation), "Основной диалог", 1_001)

    val m1 = alice.send(conversation.conversationId, doc("circle.red.m", 360_000), 2_000)
    bob.admitIncoming(MessageCanonical.envelopeJson(m1), 2_010)
    val m2 = bob.send(conversation.conversationId, doc("stick.black.pose045.m.normal", 520_000), 3_000)
    alice.admitIncoming(MessageCanonical.envelopeJson(m2), 3_010)

    val archiveStore = InMemoryArchiveStore()
    val archive = ArchiveCoordinator(aliceRepo, archiveStore, aliceId, random)
    val s1 = (archive.archiveConversation(conversation.conversationId, 3_100) as ArchiveConversationResult.Created).summary
    check(s1.messageCount == 2)
    check(s1.previousSegmentIds.isEmpty())
    println("PASS first R18 history chunk archived as verified R16 segment")

    val m3 = alice.send(conversation.conversationId, doc("outline.triangle", 680_000), 4_000)
    check(runCatching { archive.exportPackageCanonical() }.isFailure)
    println("PASS complete archive export refuses locally committed but unarchived messages")
    val s2 = (archive.archiveConversation(conversation.conversationId, 4_100) as ArchiveConversationResult.Created).summary
    check(s2.messageCount == 1)
    check(s2.previousSegmentIds == listOf(s1.segmentId))
    check(archive.archiveConversation(conversation.conversationId, 4_200) == ArchiveConversationResult.NoChanges)
    println("PASS incremental archive emits only unarchived messages and advances segment lineage")

    archive.setPinned(s1.segmentId, true)
    check(archive.summaries().first { it.segmentId == s1.segmentId }.pinned)
    println("PASS archive pin state is independent of immutable R16 segment identity")

    val beforeIds = alice.timeline(conversation.conversationId).map { it.messageId }
    val beforeHashes = alice.timeline(conversation.conversationId).map { EidogramCanonical.contentHash(it.document) }
    val beforeHeads = alice.currentHeads(conversation.conversationId)

    // Complete-package control: a contact and a conversation with no messages cannot exist in R16 segments,
    // so R19 recovery metadata must preserve them explicitly.
    val charlieId = JvmPrivateIdentity.generate(random)
    val charlieBundle = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(charlieId.user, charlieId.certificate))
    alice.importContact(charlieBundle, "Charlie", 4_500)
    val emptyConversation = alice.createConversation(
        listOf(charlieId.user.userId),
        seed = ByteArray(16) { (0x30 + it).toByte() },
        title = "Пустой диалог",
        createdAtMs = 4_600,
    )
    check(alice.timeline(emptyConversation.conversationId).isEmpty())

    val packageCanonical = archive.exportPackageCanonical()
    val parsedPackage = ArchivePackageCodec.parseCanonical(packageCanonical)
    check(ArchivePackageCodec.json(parsedPackage) == packageCanonical)
    check(parsedPackage.segments.size == 2)
    check(parsedPackage.pinnedSegmentIds == listOf(s1.segmentId))
    check(!packageCanonical.contains(B64Url.encode(aliceId.rootSigning.private.encoded)))
    check(!packageCanonical.contains(B64Url.encode(aliceId.deviceSigning.private.encoded)))
    check(!packageCanonical.contains(B64Url.encode(aliceId.deviceEncryption.private.encoded)))
    println("PASS full archive package canonical round-trip excludes private key material")

    // Destructive-recovery model: fresh repository and fresh archive index, same surviving device keys.
    val restoredRepo = InMemoryMessengerRepository()
    val restoredStore = InMemoryArchiveStore()
    val restoredArchive = ArchiveCoordinator(restoredRepo, restoredStore, aliceId, random)
    val restoredReport = restoredArchive.importAndRestorePackage(packageCanonical, 9_000)
    val restoredMessenger = LocalMessengerService(restoredRepo, aliceId, random)
    check(restoredReport.uniqueMessageCount == 3)
    check(restoredMessenger.contactSummaries().map { it.alias }.toSet() == setOf("Bob", "Charlie"))
    check(restoredMessenger.conversationSummaries().associate { it.conversationId to it.title } == mapOf(
        conversation.conversationId to "Основной диалог",
        emptyConversation.conversationId to "Пустой диалог",
    ))
    check(restoredMessenger.timeline(conversation.conversationId).map { it.messageId } == beforeIds)
    check(restoredMessenger.timeline(conversation.conversationId).map { EidogramCanonical.contentHash(it.document) } == beforeHashes)
    check(restoredMessenger.currentHeads(conversation.conversationId) == beforeHeads)
    check(restoredArchive.summaries().first { it.segmentId == s1.segmentId }.pinned)
    check(restoredMessenger.timeline(emptyConversation.conversationId).isEmpty())
    println("PASS delete-local-state model restores contacts/conversations/messages/timeline, including empty local state")

    val secondRestore = restoredArchive.importAndRestorePackage(packageCanonical, 9_100)
    check(secondRestore.uniqueMessageCount == 3)
    check(restoredMessenger.timeline(conversation.conversationId).size == 3)
    check(restoredArchive.summaries().size == 2)
    println("PASS exact archive package replay is idempotent")

    // Segment-only recovery: aliases/titles are presentation metadata, cryptographic history is R16-only.
    val segmentOnlyRepo = InMemoryMessengerRepository()
    val segmentOnlyStore = InMemoryArchiveStore()
    parsedPackage.segments.forEach { segmentOnlyStore.put(it, false, 10_000) }
    val segmentOnlyArchive = ArchiveCoordinator(segmentOnlyRepo, segmentOnlyStore, aliceId, random)
    val segmentOnlyReport = segmentOnlyArchive.restoreFromStoredSegments(10_100)
    val segmentOnlyMessenger = LocalMessengerService(segmentOnlyRepo, aliceId, random)
    check(segmentOnlyReport.uniqueMessageCount == 3)
    check(segmentOnlyMessenger.timeline(conversation.conversationId).map { it.messageId } == beforeIds)
    check(segmentOnlyMessenger.timeline(conversation.conversationId).map { EidogramCanonical.contentHash(it.document) } == beforeHashes)
    check(segmentOnlyMessenger.currentHeads(conversation.conversationId) == beforeHeads)
    println("PASS verified R16 segments alone reconstruct exact cryptographic message DAG and eidograms")

    // Overlap control: a third valid segment repeats m2/m3. Restore must deduplicate by MessageId/body.
    val overlapArtifact = ConversationSegmentBuilder.build(
        conversation = conversation,
        identityBundles = listOf(
            PublicIdentityBundleV1(aliceId.user, aliceId.certificate),
            PublicIdentityBundleV1(bobId.user, bobId.certificate),
        ),
        messageCanonicalJson = listOf(
            alice.messageCanonical(m2.messageId),
            alice.messageCanonical(m3.messageId),
        ),
        knownExternalMessageIds = listOf(m1.messageId),
    )
    val overlapStore = InMemoryArchiveStore()
    parsedPackage.segments.forEach { overlapStore.put(it, false, 11_000) }
    overlapStore.put(ArchiveSegmentCodec.fromArtifact(overlapArtifact), false, 11_000)
    val overlapRepo = InMemoryMessengerRepository()
    val overlapArchive = ArchiveCoordinator(overlapRepo, overlapStore, aliceId, random)
    val overlapReport = overlapArchive.restoreFromStoredSegments(11_100)
    val overlapMessenger = LocalMessengerService(overlapRepo, aliceId, random)
    check(overlapReport.uniqueMessageCount == 3)
    check(overlapReport.duplicateMessageCopies == 2)
    check(overlapMessenger.timeline(conversation.conversationId).map { it.messageId } == beforeIds)
    println("PASS deterministic overlapping-segment recovery deduplicates repeated messages")

    // Message identity correction: ECDSA proof can be re-issued over the same body without forking MessageId.
    val m3Body = MessageCanonical.bodyJson(m3.body).toByteArray(Charsets.UTF_8)
    val alternateProof = m3.copy(
        signature = MessageSignatureV1(CryptoSuiteV1.SIGNATURE, B64Url.encode(aliceId.signMessage(m3Body)))
    )
    check(alternateProof.messageId == m3.messageId)
    check(MessageCanonical.envelopeJson(alternateProof) != MessageCanonical.envelopeJson(m3))
    check(MessageCrypto.verify(alternateProof, aliceId.user, aliceId.certificate, conversation))
    check(bob.admitIncoming(MessageCanonical.envelopeJson(m3), 11_490) == InsertMessageResult.Inserted)
    check(bob.admitIncoming(MessageCanonical.envelopeJson(alternateProof), 11_500) == InsertMessageResult.Duplicate)
    println("PASS same MessageId/body with refreshed valid ECDSA proof is an idempotent duplicate")

    // Package preflight is fail-closed and transactional: corrupt a segment but recompute package ID.
    val victim = parsedPackage.segments.first()
    val victimFile = victim.files.first { it.path.startsWith("messages/") }
    val brokenBytes = B64Url.decode(victimFile.bytesB64).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    val brokenFile = victimFile.copy(bytesB64 = B64Url.encode(brokenBytes), sha256 = HexSha256.of(brokenBytes))
    val brokenSegment = victim.copy(files = victim.files.map { if (it.path == victimFile.path) brokenFile else it })
    val brokenPackage = ArchivePackageCodec.withId(
        parsedPackage.ownerUserId,
        parsedPackage.recoveryDeviceId,
        parsedPackage.recoveryEncryptionKeyId,
        parsedPackage.contacts,
        parsedPackage.conversations,
        parsedPackage.segments.map { if (it.segmentId == victim.segmentId) brokenSegment else it },
        parsedPackage.pinnedSegmentIds,
    )
    val rejectedRepo = InMemoryMessengerRepository()
    val rejectedStore = InMemoryArchiveStore()
    val rejectedArchive = ArchiveCoordinator(rejectedRepo, rejectedStore, aliceId, random)
    check(runCatching { rejectedArchive.importAndRestorePackage(ArchivePackageCodec.json(brokenPackage), 12_000) }.isFailure)
    check(rejectedRepo.contactDevices().isEmpty() && rejectedRepo.conversations().isEmpty() && rejectedStore.list().isEmpty())
    println("PASS corrupt archive package is rejected before repository/archive mutation")

    // R19 recovery is intentionally same-device-key recovery; another device needs R20 onboarding/key migration.
    val newSign = JcaCrypto.p256(random)
    val newEnc = JcaCrypto.rsa2048(random)
    val newBody = IdentityFactory.deviceBody(aliceId.user, newSign.public, newEnc.public)
    val newCert = IdentityFactory.certificate(newBody, aliceId.rootSigning.private, random)
    val aliceSecondDevice = JvmPrivateIdentity(
        rootSigning = aliceId.rootSigning,
        deviceSigning = newSign,
        deviceEncryption = newEnc,
        user = aliceId.user,
        certificate = newCert,
        random = random,
    )
    val wrongDeviceArchive = ArchiveCoordinator(InMemoryMessengerRepository(), InMemoryArchiveStore(), aliceSecondDevice, random)
    check(runCatching { wrongDeviceArchive.importAndRestorePackage(packageCanonical, 13_000) }.isFailure)
    println("PASS R19 package refuses restore on a device lacking the bound historical decryption key")

    println("SEGMENT_1=${s1.segmentId}")
    println("SEGMENT_2=${s2.segmentId}")
    println("PACKAGE_ID=${parsedPackage.packageId}")
    println("PRIMARY_HEAD=${beforeHeads.single()}")
    println("ALL R19 ARCHIVE/RECOVERY CHECKS PASS")
}
