package org.eidolang.tools

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.transport.*
import java.nio.file.*

fun main(args:Array<String>) {
    require(args.size==1) { "usage: R17Main <golden-r16-dir>" }
    val g=Path.of(args[0])
    fun read(name:String)=Files.readAllBytes(g.resolve(name))
    val ids=Files.readAllLines(g.resolve("ids.txt")).associate { val p=it.split("=",limit=2); p[0] to p[1] }
    val segmentRoot=g.resolve("segment")
    val files=Files.walk(segmentRoot).filter{Files.isRegularFile(it)}.map { p -> TransportFile(segmentRoot.relativize(p).toString().replace('\\','/'),Files.readAllBytes(p)) }.toList()
    val swarm=SwarmObject(
        torrentName="eidolang-${ids.getValue("conversation_id").take(12)}-${ids.getValue("segment_id").take(12)}",
        torrentMetainfo=read("segment.torrent"),infoHashV2Hex=ids.getValue("torrent_infohash_v2"),files=files
    )
    val head=MutableHeadRecord(ids.getValue("bep44_target"),read("head-put.bencode"))
    val publisher=Files.readString(g.resolve("publisher-certificate.json"))
    val manifest=SegmentParser.parseCanonical(files.single{it.path=="segment-manifest.json"}.bytes.toString(Charsets.UTF_8))
    val senderDevice=manifest.identityEntries.first { it.deviceId==ArchivePublisherParser.parseCanonical(publisher).deviceId }
    val identity=files.single{it.path==senderDevice.path}.bytes.toString(Charsets.UTF_8)

    val network=ReferenceTransportNetwork(); val a=network.node("A"); val b=network.node("B")
    a.seed(swarm); check(a.putHead(head)); println("PASS node A seeds immutable segment and publishes mutable head")
    val resolved=b.getHead(head.targetSha1Hex)!!; val fetched=b.fetch(swarm.infoHashV2Hex)!!
    val accepted=NetworkAdmissionGate.admit(fetched,resolved,publisher,identity)
    check(accepted.artifact.manifest.segmentId==ids.getValue("segment_id")); println("PASS node B fetch -> R16 cryptographic admission")
    check(!a.putHead(head)); println("PASS mutable head rejects non-monotonic sequence")

    network.corrupt(swarm.infoHashV2Hex,manifest.messageEntries.first().path)
    val bad=b.fetch(swarm.infoHashV2Hex)!!
    check(runCatching{NetworkAdmissionGate.admit(bad,resolved,publisher,identity)}.isFailure)
    println("PASS transport success does not bypass corrupted-segment rejection")

    val wrongHead=resolved.copy(fieldsBencoded=resolved.fieldsBencoded.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
    check(runCatching{NetworkAdmissionGate.admit(fetched,wrongHead,publisher,identity)}.isFailure)
    println("PASS malformed mutable-head bytes rejected")
    a.close(); b.close()
    println("ALL R17 REFERENCE-TRANSPORT ADMISSION CHECKS PASS")
}
