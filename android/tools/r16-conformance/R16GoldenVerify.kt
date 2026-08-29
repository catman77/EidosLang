package org.eidolang.tools

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.IdentityParser
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 1) { "usage: R16GoldenVerify <golden/r16>" }
    val root = Path.of(args[0])
    val ids = Files.readAllLines(root.resolve("ids.txt")).associate {
        val p = it.indexOf('='); it.substring(0, p) to it.substring(p + 1)
    }
    val segmentDir = root.resolve("segment")
    val files = Files.walk(segmentDir).use { stream ->
        stream.filter { Files.isRegularFile(it) }.map { p ->
            TorrentFileV1(segmentDir.relativize(p).toString().replace('\\','/'), Files.readAllBytes(p))
        }.toList()
    }
    val manifestText = files.single { it.path == "segment-manifest.json" }.bytes.toString(Charsets.UTF_8)
    val manifest = SegmentParser.parseCanonical(manifestText)
    check(manifest.segmentId == ids.getValue("segment_id"))
    check(manifest.conversationId == ids.getValue("conversation_id"))

    val torrentName = "eidolang-${manifest.conversationId.take(12)}-${manifest.segmentId.take(12)}"
    val artifact = ConversationSegmentLoader.loadAndVerify(
        files, torrentName, expectedInfoHashV2Hex = ids.getValue("torrent_infohash_v2")
    )
    check(artifact.torrent.metainfoBytes.contentEquals(Files.readAllBytes(root.resolve("segment.torrent"))))
    check(artifact.manifest.headMessageIds.single() == ids.getValue("head_message_id"))
    println("PASS R16 fixed segment canonical reconstruction")
    println("PASS R16 fixed BEP52 metainfo/infohash reconstruction")

    val cert = ArchivePublisherParser.parseCanonical(Files.readString(root.resolve("publisher-certificate.json")))
    val identityEntry = artifact.manifest.identityEntries.single { it.deviceId == cert.deviceId }
    val identityBytes = files.single { it.path == identityEntry.path }.bytes
    val identity = IdentityParser.parsePublicBundleCanonical(identityBytes.toString(Charsets.UTF_8))
    check(ArchivePublisherVerifier.verify(cert, identity))
    println("PASS R16 fixed publisher certificate binding")

    val put = ConversationHeadCodec.parseMutablePutFields(Files.readAllBytes(root.resolve("head-put.bencode")))
    check(put.seq == ids.getValue("bep44_seq").toLong())
    check(put.targetSha1Hex == ids.getValue("bep44_target"))
    check(ConversationHeadCodec.verify(put, cert, identity))
    println("PASS R16 fixed BEP44 mutable-head signature/target")

    check(Files.readString(root.resolve("magnet.txt")).trim() == artifact.torrent.magnetUri)
    println("PASS R16 fixed btmh magnet")
    println("ALL R16 FIXED GOLDEN CHECKS PASS")
}
