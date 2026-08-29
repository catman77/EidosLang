package org.eidolang.tools

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.recovery.*
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: R19GoldenGenerator <golden/r16> <golden/r19/archive-package.json>" }
    val r16 = Path.of(args[0])
    val out = Path.of(args[1])
    val ids = Files.readAllLines(r16.resolve("ids.txt")).associate {
        val p = it.indexOf('='); it.substring(0,p) to it.substring(p+1)
    }
    val segmentRoot = r16.resolve("segment")
    val files = Files.walk(segmentRoot).use { stream ->
        stream.filter { Files.isRegularFile(it) }.map { p ->
            TorrentFileV1(segmentRoot.relativize(p).toString().replace('\\','/'), Files.readAllBytes(p))
        }.toList()
    }
    val manifest = SegmentParser.parseCanonical(files.single { it.path == "segment-manifest.json" }.bytes.toString(Charsets.UTF_8))
    val torrentName = "eidolang-${manifest.conversationId.take(12)}-${manifest.segmentId.take(12)}"
    val artifact = ConversationSegmentLoader.loadAndVerify(files, torrentName, expectedInfoHashV2Hex = ids.getValue("torrent_infohash_v2"))
    val blob = ArchiveSegmentCodec.fromArtifact(artifact)

    val bundles = manifest.identityEntries.map { e ->
        val raw = files.single { it.path == e.path }.bytes.toString(Charsets.UTF_8)
        IdentityParser.parsePublicBundleCanonical(raw) to raw
    }.sortedWith(compareBy<Pair<PublicIdentityBundleV1,String>> { it.first.user.userId }.thenBy { it.first.device.deviceId })
    val owner = bundles.first().first
    val contacts = bundles.drop(1).mapIndexed { index, (b, raw) ->
        ContactPresentationV1(b.user.userId, "contact-${index+1}", listOf(raw))
    }
    val p = ArchivePackageCodec.withId(
        ownerUserId = owner.user.userId,
        recoveryDeviceId = owner.device.deviceId,
        recoveryEncryptionKeyId = owner.device.body.encryptionKeyId,
        contacts = contacts,
        conversations = listOf(ConversationPresentationV1(
            manifest.conversationId,
            "fixed-r16-conversation",
            1_700_000_000_000L,
            files.single { it.path == "conversation.json" }.bytes.toString(Charsets.UTF_8),
        )),
        segments = listOf(blob),
        pinnedSegmentIds = listOf(blob.segmentId),
    )
    val canonical = ArchivePackageCodec.json(p)
    ArchivePackageCodec.parseCanonical(canonical)
    Files.createDirectories(out.parent)
    Files.writeString(out, canonical)
    println("PASS R19 fixed recovery package generated and canonical-parsed")
    println("PACKAGE_ID=${p.packageId}")
    println("SEGMENT_ID=${blob.segmentId}")
    println("TORRENT_INFOHASH_V2=${blob.torrentInfoHashV2}")
}
