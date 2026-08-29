package org.eidolang.tools

import org.eidolang.core.archive.*
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.SecureRandom

private fun restore(
    bundle: PublicIdentityBundleV1,
    rootPrivate: String,
    signPrivate: String,
    encPrivate: String,
): JvmPrivateIdentity = JvmPrivateIdentity.fromEncoded(
    rootPrivateB64 = rootPrivate,
    rootPublicB64 = bundle.user.rootSigningPublicKeyB64,
    deviceSigningPrivateB64 = signPrivate,
    deviceSigningPublicB64 = bundle.device.body.signingPublicKeyB64,
    deviceEncryptionPrivateB64 = encPrivate,
    deviceEncryptionPublicB64 = bundle.device.body.encryptionPublicKeyB64,
    certificate = bundle.device,
)

private fun largeDocument(): EidogramDocumentV1 {
    val glyphs = EidoGlyphCatalogV1.glyphs
    val actions = (0 until 220).map { i ->
        val x = 40_000 + (i % 20) * 46_000
        val y = 60_000 + (i / 20) * 78_000
        EidogramAction.Add(
            seq = i,
            instanceId = "g" + (i + 1).toString().padStart(6, '0'),
            glyphId = glyphs[i % glyphs.size].glyphId,
            transform = FixedTransform(x, y, 700_000 + (i % 4) * 100_000, 700_000 + (i % 3) * 120_000, (i % 8) * 45_000),
            zIndex = i,
        )
    }
    return EidogramDocumentV1(actions)
}

private fun replyDocument(): EidogramDocumentV1 = EidogramDocumentV1(listOf(
    EidogramAction.Add(0, "g000001", "circle.cyan.l", FixedTransform(380_000, 480_000), 0),
    EidogramAction.Add(1, "g000002", "stick.black.pose135.l.thick", FixedTransform(560_000, 520_000), 1),
    EidogramAction.Add(2, "g000003", "outline.diamond", FixedTransform(700_000, 500_000), 2),
))

private fun asciiContains(bytes: ByteArray, needle: String): Boolean =
    bytes.toString(Charsets.ISO_8859_1).contains(needle)

fun main(args: Array<String>) {
    val random = SecureRandom()
    val aliceBundle = IdentityParser.parsePublicBundleCanonical(GoldenR15.ALICE_BUNDLE_JSON)
    val bobBundle = IdentityParser.parsePublicBundleCanonical(GoldenR15.BOB_BUNDLE_JSON)
    val conversation = ConversationParser.parseCanonical(GoldenR15.CONVERSATION_JSON)
    val alice = restore(aliceBundle, GoldenR15.ALICE_ROOT_PRIVATE_B64, GoldenR15.ALICE_SIGN_PRIVATE_B64, GoldenR15.ALICE_ENC_PRIVATE_B64)
    val bob = restore(bobBundle, GoldenR15.BOB_ROOT_PRIVATE_B64, GoldenR15.BOB_SIGN_PRIVATE_B64, GoldenR15.BOB_ENC_PRIVATE_B64)

    val first = MessageCrypto.seal(
        largeDocument(), conversation, alice,
        listOf(MessageCrypto.recipient(bob.certificate)),
        senderSeq = 101,
        createdAtMs = 1_786_410_000_000L,
        random = random,
    )
    val firstJson = MessageCanonical.envelopeJson(first)
    check(firstJson.toByteArray().size > TorrentV2Builder.BLOCK_SIZE)

    val second = MessageCrypto.seal(
        replyDocument(), conversation, bob,
        listOf(MessageCrypto.recipient(alice.certificate)),
        senderSeq = 77,
        createdAtMs = 1_786_410_010_000L,
        parentMessageIds = listOf(first.messageId),
        random = random,
    )
    val secondJson = MessageCanonical.envelopeJson(second)

    val segment = ConversationSegmentBuilder.build(
        conversation = conversation,
        identityBundles = listOf(aliceBundle, bobBundle),
        messageCanonicalJson = listOf(secondJson, firstJson), // deliberately unsorted input
    )
    check(ConversationSegmentBuilder.verifyArtifact(segment))
    check(segment.manifest.messageEntries.map { it.messageId } == segment.manifest.messageEntries.map { it.messageId }.sorted())
    check(segment.manifest.headMessageIds == listOf(second.messageId))
    check(segment.manifest.externalParentMessageIds.isEmpty())
    println("PASS immutable segment build + message DAG head derivation")

    val rebuilt = ConversationSegmentBuilder.build(
        conversation,
        listOf(bobBundle, aliceBundle),
        listOf(firstJson, secondJson),
    )
    check(rebuilt.manifest.segmentId == segment.manifest.segmentId)
    check(rebuilt.torrent.infoHashV2Hex == segment.torrent.infoHashV2Hex)
    check(rebuilt.torrent.metainfoBytes.contentEquals(segment.torrent.metainfoBytes))
    println("PASS deterministic segment/torrent independent of input ordering")

    check(segment.torrent.infoHashV2Hex.matches(Regex("[0-9a-f]{64}")))
    check(segment.torrent.magnetUri.startsWith("magnet:?xt=urn:btmh:1220${segment.torrent.infoHashV2Hex}"))
    check(asciiContains(segment.torrent.metainfoBytes, "piece layers"))
    check(Bencode.encode(Bencode.decode(segment.torrent.metainfoBytes)).contentEquals(segment.torrent.metainfoBytes))
    println("PASS BEP52 v2 metainfo + btmh magnet + piece layers")

    val loaded = ConversationSegmentLoader.loadAndVerify(
        segment.files,
        segment.torrent.name,
        segment.torrent.pieceLength,
        segment.torrent.infoHashV2Hex,
    )
    check(loaded.manifest == segment.manifest)
    println("PASS downloaded-file reconstruction and cryptographic verification")

    val tamperedFiles = segment.files.map { f ->
        if (f.path == segment.manifest.messageEntries.first().path) {
            val b = f.bytes.copyOf(); b[b.lastIndex] = (b.last().toInt() xor 1).toByte(); TorrentFileV1(f.path, b)
        } else f
    }
    check(runCatching {
        ConversationSegmentLoader.loadAndVerify(tamperedFiles, segment.torrent.name, expectedInfoHashV2Hex = segment.torrent.infoHashV2Hex)
    }.isFailure)
    println("PASS archive file tamper rejection")

    check(runCatching {
        TorrentV2Builder.build("unsafe", listOf(TorrentFileV1("../escape", byteArrayOf(1))))
    }.isFailure)
    println("PASS torrent path traversal rejection")

    val publisher = JcaArchivePublisher.create(alice)
    check(ArchivePublisherVerifier.verify(publisher.certificate, aliceBundle))
    println("PASS R15-device-signed BEP44 publisher-key binding")

    val headValue = ConversationHeadValueV1(
        conversationId = conversation.conversationId,
        publisherKeyId = publisher.certificate.publisherKeyId,
        publisherDeviceId = alice.certificate.deviceId,
        segmentId = segment.manifest.segmentId,
        torrentInfoHashV2 = segment.torrent.infoHashV2Hex,
        headMessageIds = segment.manifest.headMessageIds,
    )
    val head1 = ConversationHeadCodec.create(headValue, 1, publisher)
    check(ConversationHeadCodec.verify(head1, publisher.certificate, aliceBundle))
    check(Bencode.encode(head1.value).size <= 1000)
    check(head1.targetSha1Hex.matches(Regex("[0-9a-f]{40}")))
    check(head1.salt.size == 32)
    check(Bencode.encode(Bencode.decode(Bencode.encode(ConversationHeadCodec.mutablePutFields(head1))))
        .contentEquals(Bencode.encode(ConversationHeadCodec.mutablePutFields(head1))))
    println("PASS BEP44 mutable head signature/target/value budget")

    val tamperedHead = head1.copy(seq = 2)
    check(!ConversationHeadCodec.verify(tamperedHead, publisher.certificate, aliceBundle))
    println("PASS BEP44 sequence tamper requires new signature")

    val head2 = ConversationHeadCodec.create(headValue, 2, publisher)
    check(head2.targetSha1Hex == head1.targetSha1Hex && head2.seq > head1.seq)
    check(ConversationHeadCodec.verify(head2, publisher.certificate, aliceBundle))
    println("PASS same publisher+conversation yields stable DHT target with monotonic seq")

    if (args.isNotEmpty()) {
        val out = Path.of(args[0])
        Files.createDirectories(out)
        Files.createDirectories(out.resolve("segment"))
        segment.files.forEach { f ->
            val path = out.resolve("segment").resolve(f.path)
            Files.createDirectories(path.parent)
            Files.write(path, f.bytes)
        }
        Files.write(out.resolve("segment.torrent"), segment.torrent.metainfoBytes)
        Files.write(out.resolve("head-put.bencode"), Bencode.encode(ConversationHeadCodec.mutablePutFields(head1)))
        Files.writeString(out.resolve("publisher-certificate.json"), ArchivePublisherCanonical.certificateJson(publisher.certificate))
        Files.writeString(out.resolve("magnet.txt"), segment.torrent.magnetUri + "\n")
        Files.writeString(out.resolve("ids.txt"), buildString {
            appendLine("conversation_id=${conversation.conversationId}")
            appendLine("segment_id=${segment.manifest.segmentId}")
            appendLine("torrent_infohash_v2=${segment.torrent.infoHashV2Hex}")
            appendLine("head_message_id=${second.messageId}")
            appendLine("bep44_target=${head1.targetSha1Hex}")
            appendLine("bep44_seq=${head1.seq}")
            appendLine("publisher_key_id=${publisher.certificate.publisherKeyId}")
        })
    }

    println("CONVERSATION_ID=${conversation.conversationId}")
    println("SEGMENT_ID=${segment.manifest.segmentId}")
    println("TORRENT_INFOHASH_V2=${segment.torrent.infoHashV2Hex}")
    println("MAGNET=${segment.torrent.magnetUri}")
    println("HEAD_MESSAGE_ID=${second.messageId}")
    println("BEP44_TARGET=${head1.targetSha1Hex}")
    println("BEP44_VALUE_BYTES=${Bencode.encode(head1.value).size}")
    println("ALL R16 PURE-KOTLIN/JCA ARCHIVE CHECKS PASS")
}
