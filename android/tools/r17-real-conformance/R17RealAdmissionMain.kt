package org.eidolang.tools

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.transport.*
import java.nio.file.*

/**
 * R17.1 items 5 and 7 against bytes that actually crossed a BitTorrent connection.
 *
 * `R17Main` proves the same admission logic over `ReferenceTransportNetwork`, which its own
 * comment calls "NOT BitTorrent". This harness replaces that double with the output directory of
 * `tools/r17-real-swarm.py`, so the segment is re-derived from what libtorrent wrote to disk.
 *
 * The signed control plane (mutable head, publisher certificate) still comes from the golden
 * directory: it is the transferred payload that is under test here, not the head format.
 *
 * usage: R17RealAdmissionMain <golden-r16-dir> <downloaded-segment-dir>
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: R17RealAdmissionMain <golden-r16-dir> <downloaded-segment-dir>" }
    val g = Path.of(args[0])
    val dl = Path.of(args[1])

    val ids = Files.readAllLines(g.resolve("ids.txt"))
        .associate { val p = it.split("=", limit = 2); p[0] to p[1] }

    fun readDownloaded(): List<TransportFile> =
        Files.walk(dl).filter { Files.isRegularFile(it) }
            .map { p -> TransportFile(dl.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p)) }
            .toList()
            .sortedBy { it.path }

    val torrentName = "eidolang-${ids.getValue("conversation_id").take(12)}-${ids.getValue("segment_id").take(12)}"

    fun swarmOf(files: List<TransportFile>) = SwarmObject(
        torrentName = torrentName,
        torrentMetainfo = Files.readAllBytes(g.resolve("segment.torrent")),
        infoHashV2Hex = ids.getValue("torrent_infohash_v2"),
        files = files,
    )

    val head = MutableHeadRecord(ids.getValue("bep44_target"), Files.readAllBytes(g.resolve("head-put.bencode")))
    val publisher = Files.readString(g.resolve("publisher-certificate.json"))

    val downloaded = readDownloaded()
    check(downloaded.size == 6) { "expected 6 transferred files, got ${downloaded.map { it.path }}" }

    val manifest = SegmentParser.parseCanonical(
        downloaded.single { it.path == "segment-manifest.json" }.bytes.toString(Charsets.UTF_8)
    )
    val senderDevice = manifest.identityEntries
        .first { it.deviceId == ArchivePublisherParser.parseCanonical(publisher).deviceId }
    val identity = downloaded.single { it.path == senderDevice.path }.bytes.toString(Charsets.UTF_8)

    // Item 5: admission over the transferred bytes must reproduce the exact R16 segment id.
    val accepted = NetworkAdmissionGate.admit(
        FetchResult(swarmOf(downloaded), "libtorrent:127.0.0.1"), head, publisher, identity,
    )
    check(accepted.artifact.manifest.segmentId == ids.getValue("segment_id"))
    check(accepted.artifact.torrent.infoHashV2Hex == ids.getValue("torrent_infohash_v2"))
    check(accepted.head.conversationId == ids.getValue("conversation_id"))
    println("PASS libtorrent-transferred bytes reproduce the exact R16 segment_id under admission")
    println("PASS v2 infohash re-derived from the downloaded files matches the head")

    // Item 7: corrupt a downloaded file after libtorrent reported completion.
    val victimPath = manifest.messageEntries.first().path
    val victim = dl.resolve(victimPath)
    val pristine = Files.readAllBytes(victim)
    val tampered = pristine.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
    Files.write(victim, tampered)
    try {
        val rejected = runCatching {
            NetworkAdmissionGate.admit(
                FetchResult(swarmOf(readDownloaded()), "libtorrent:127.0.0.1"), head, publisher, identity,
            )
        }
        check(rejected.isFailure) { "admission accepted a post-download single-bit corruption" }
        println("PASS post-completion single-bit corruption of ${victimPath.substringAfterLast('/')} is rejected")
    } finally {
        Files.write(victim, pristine)
    }

    // Restoring the byte must restore admission, proving the rejection came from that byte alone.
    val reaccepted = NetworkAdmissionGate.admit(
        FetchResult(swarmOf(readDownloaded()), "libtorrent:127.0.0.1"), head, publisher, identity,
    )
    check(reaccepted.artifact.manifest.segmentId == ids.getValue("segment_id"))
    println("PASS restoring the byte restores admission, isolating the rejection to that byte")

    println("SEGMENT_ID=${accepted.artifact.manifest.segmentId}")
    println("ALL R17 REAL-SWARM ADMISSION CHECKS PASS")
}
