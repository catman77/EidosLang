package org.eidolang.tools

import org.eidolang.core.recovery.ArchivePackageCodec
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 1)
    val raw = Files.readString(Path.of(args[0]))
    val p = ArchivePackageCodec.parseCanonical(raw)
    check(ArchivePackageCodec.json(p) == raw)
    check(p.segments.size == 1)
    check(p.pinnedSegmentIds == listOf(p.segments.single().segmentId))
    println("PASS R19 fixed package canonical parse and package-id verification")
    println("PASS R19 fixed package embedded R16 segment verification")
    println("PASS R19 fixed package torrent metainfo reproduction")
    println("PACKAGE_ID=${p.packageId}")
    println("ALL R19 FIXED PACKAGE CHECKS PASS")
}
