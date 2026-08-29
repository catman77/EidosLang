package org.eidolang.tools

import org.eidolang.core.model.*
import org.eidolang.core.canonical.*

private fun add(seq:Int,id:String,glyph:String,cx:Int=500_000,cy:Int=500_000,z:Int=0,scale:Int=1_000_000,rot:Int=0) =
    EidogramAction.Add(seq,id,glyph,FixedTransform(cx,cy,scale,scale,rot),z)

private fun check(name:String,d:EidogramDocumentV1,content:String,snapshot:String) {
    val c=EidogramCanonical.contentHash(d)
    val s=EidogramCanonical.snapshotHash(d)
    check(c==content) { "$name content hash mismatch: $c != $content" }
    check(s==snapshot) { "$name snapshot hash mismatch: $s != $snapshot" }
    println("PASS $name content=$c snapshot=$s")
}

fun main() {
    // Growth is expected; loss is not. Additions must never remove or rename what V1 shipped,
    // because eidograms drawn against it reference those ids by name.
    val present = EidoGlyphCatalogV1.glyphs.map { it.glyphId }.toSet()
    check(present.size == EidoGlyphCatalogV1.glyphs.size) { "duplicate glyph id" }
    val lost = FROZEN_V1_GLYPH_IDS - present
    check(lost.isEmpty()) { "catalogue lost ${lost.size} glyph(s) from V1: ${lost.take(5)}" }
    println("catalogue: ${present.size} glyphs, all ${FROZEN_V1_GLYPH_IDS.size} from V1 still present")
    check(ProtocolLineage.GLYPH_CATALOG_HASH.length == 64)

    // The R14 golden bytes are the canonicalisation of these documents *against the catalogue
    // revision they were recorded on*, and the revision is part of what is canonicalised. Building
    // them with whatever the current default happens to be would mean the goldens quietly changed
    // meaning every time the catalogue grew — and would report that as a regression. Pinning it
    // keeps the frozen bytes frozen and states which catalogue they belong to.
    val r14Catalog = "da2d3b72ff8d41abb8c293ce1deca44e99cb1d1a696e9834997395c6f6218005"

    check("00_empty", EidogramDocumentV1(emptyList(), catalogHash = r14Catalog), GoldenExpected._00_EMPTY_CONTENT, GoldenExpected._00_EMPTY_SNAPSHOT)
    check("01_red_circle", EidogramDocumentV1(listOf(add(0,"g000001","circle.red.m")), catalogHash = r14Catalog), GoldenExpected._01_RED_CIRCLE_CONTENT, GoldenExpected._01_RED_CIRCLE_SNAPSHOT)
    check("02_stick_dot_outline", EidogramDocumentV1(listOf(
        add(0,"g000001","stick.black.pose045.m.normal",350_000,500_000,0),
        add(1,"g000002","dot.black.s",500_000,500_000,1),
        add(2,"g000003","outline.triangle",680_000,500_000,2),
    ), catalogHash = r14Catalog), GoldenExpected._02_STICK_DOT_OUTLINE_CONTENT, GoldenExpected._02_STICK_DOT_OUTLINE_SNAPSHOT)
    check("03_red_circle_direct", EidogramDocumentV1(listOf(add(0,"g000001","circle.red.m")), catalogHash = r14Catalog), GoldenExpected._03_RED_CIRCLE_DIRECT_CONTENT, GoldenExpected._03_RED_CIRCLE_DIRECT_SNAPSHOT)
    check("04_red_circle_via_blue", EidogramDocumentV1(listOf(
        add(0,"g000001","circle.blue.m"),
        EidogramAction.SetGlyph(1,"g000001","circle.red.m"),
    ), catalogHash = r14Catalog), GoldenExpected._04_RED_CIRCLE_VIA_BLUE_CONTENT, GoldenExpected._04_RED_CIRCLE_VIA_BLUE_SNAPSHOT)
    check("05_group_transform", EidogramDocumentV1(listOf(
        add(0,"g000001","circle.violet.s",350_000,420_000,0),
        add(1,"g000002","stick.black.pose090.l.thick",560_000,530_000,1),
        EidogramAction.Group(2,"grp000001",listOf("g000001","g000002")),
        EidogramAction.SetTransform(3,"g000002",FixedTransform(600_000,530_000,1_200_000,1_200_000,45_000)),
    ), catalogHash = r14Catalog), GoldenExpected._05_GROUP_TRANSFORM_CONTENT, GoldenExpected._05_GROUP_TRANSFORM_SNAPSHOT)

    val direct=EidogramDocumentV1(listOf(add(0,"g000001","circle.red.m")), catalogHash = r14Catalog)
    val via=EidogramDocumentV1(listOf(add(0,"g000001","circle.blue.m"),EidogramAction.SetGlyph(1,"g000001","circle.red.m")), catalogHash = r14Catalog)
    check(EidogramCanonical.snapshotHash(direct)==EidogramCanonical.snapshotHash(via))
    check(EidogramCanonical.contentHash(direct)!=EidogramCanonical.contentHash(via))

    var e=EditorDraft()
    e=EditorReducer.reduce(e,EditorCommand.AddGlyph("circle.red.m"))
    e=EditorReducer.reduce(e,EditorCommand.TranslateSelected(10_000,-20_000))
    val moved=e.snapshot.instances.single().transform
    check(moved.cxFp==510_000 && moved.cyFp==480_000)
    e=EditorReducer.reduce(e,EditorCommand.Undo)
    check(e.snapshot.instances.single().transform.cxFp==500_000)
    e=EditorReducer.reduce(e,EditorCommand.Redo)
    check(e.snapshot.instances.single().transform.cxFp==510_000)
    println("PASS editor reducer undo/redo")
    
    // Strict canonical import must be byte-preserving.
    listOf(direct, via).forEach { doc ->
        val bytes = EidogramCanonical.documentJson(doc)
        val parsed = EidogramParser.parseCanonical(bytes)
        check(EidogramCanonical.documentJson(parsed) == bytes)
        check(EidogramCanonical.contentHash(parsed) == EidogramCanonical.contentHash(doc))
    }
    println("PASS strict canonical parser round-trip")

    // Non-canonical whitespace is intentionally rejected.
    val nonCanonical = EidogramCanonical.documentJson(direct).replaceFirst("{", "{ ")
    check(runCatching { EidogramParser.parseCanonical(nonCanonical) }.isFailure)
    println("PASS non-canonical import rejection")
    val duplicateKey = EidogramCanonical.documentJson(direct).replaceFirst(
        "\"catalog_id\":\"${ProtocolLineage.GLYPH_CATALOG_ID}\"",
        "\"catalog_id\":\"${ProtocolLineage.GLYPH_CATALOG_ID}\",\"catalog_id\":\"${ProtocolLineage.GLYPH_CATALOG_ID}\""
    )
    check(runCatching { EidogramParser.parseCanonical(duplicateKey) }.isFailure)

    val wrongLineage = EidogramCanonical.documentJson(direct).replace(
        ProtocolLineage.PRODUCTION_GRAMMAR_HASH,
        "0".repeat(64)
    )
    check(runCatching { EidogramParser.parseCanonical(wrongLineage) }.isFailure)
    println("PASS duplicate-key and lineage rejection")

    // Actual-bounds hit testing: the 45° stick is hittable on the stick itself,
    // while a point near its old radial approximation but outside geometry is rejected.
    val hs = EidogramReplay.replay(EidogramDocumentV1(listOf(
        add(0, "g000001", "stick.black.pose045.m.normal", 500_000, 500_000, 0)
    ), catalogHash = r14Catalog))
    check(EidogramHitTest.hitTest(hs, 540_000, 540_000) == "g000001")
    check(EidogramHitTest.hitTest(hs, 500_000, 590_000, touchSlopFp = 2_000) == null)

    val cs = EidogramReplay.replay(EidogramDocumentV1(listOf(
        add(0, "g000001", "circle.red.m", 500_000, 500_000, 0, scale = 1_500_000)
    ), catalogHash = r14Catalog))
    check(EidogramHitTest.hitTest(cs, 560_000, 500_000) == "g000001")
    println("PASS geometry-aware hit testing")

    val grouped = EidogramReplay.replay(EidogramDocumentV1(listOf(
        add(0, "g000001", "circle.red.m", 350_000, 500_000, 0),
        add(1, "g000002", "dot.black.m", 650_000, 500_000, 1),
        EidogramAction.Group(2, "grp000001", listOf("g000001", "g000002")),
    )))
    check(EditorSelection.single(grouped, "g000001") == setOf("g000001", "g000002"))
    check(EditorSelection.toggle(grouped, emptySet(), "g000001") == setOf("g000001", "g000002"))
    check(EditorSelection.toggle(grouped, setOf("g000001", "g000002"), "g000002").isEmpty())
    println("PASS grouped selection semantics")

    val restored = EditorDraftFactory.fromDocument(grouped.let {
        EidogramDocumentV1(listOf(
            add(0, "g000007", "circle.red.m"),
            add(1, "g000011", "dot.black.s", z = 1),
            EidogramAction.Group(2, "grp000004", listOf("g000007", "g000011")),
        ))
    })
    val after = EditorReducer.reduce(restored, EditorCommand.AddGlyph("outline.circle"))
    check(after.snapshot.instances.any { it.instanceId == "g000012" })
    check(after.nextGroupNumber == 5)
    println("PASS draft restore counters")

    println("ALL R14.1 PURE-KOTLIN CONFORMANCE CHECKS PASS")
}
