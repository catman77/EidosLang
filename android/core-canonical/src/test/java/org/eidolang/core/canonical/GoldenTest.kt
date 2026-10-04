package org.eidolang.core.canonical

import org.eidolang.core.model.*
import org.junit.Assert.*
import org.junit.Test

class GoldenTest {
    @Test fun sameSceneDifferentHistory() {
        // These frozen hashes belong to the original catalogue, not the current growing one.
        val catalogHash = "da2d3b72ff8d41abb8c293ce1deca44e99cb1d1a696e9834997395c6f6218005"
        val direct=EidogramDocumentV1(listOf(EidogramAction.Add(0,"g000001","circle.red.m",FixedTransform(500_000,500_000),0)), catalogHash = catalogHash)
        val via=EidogramDocumentV1(listOf(
            EidogramAction.Add(0,"g000001","circle.blue.m",FixedTransform(500_000,500_000),0),
            EidogramAction.SetGlyph(1,"g000001","circle.red.m")
        ), catalogHash = catalogHash)
        assertEquals(EidogramCanonical.snapshotHash(direct),EidogramCanonical.snapshotHash(via))
        assertNotEquals(EidogramCanonical.contentHash(direct),EidogramCanonical.contentHash(via))
        assertEquals("e4fa9396a0bee8e4d7c42db0d2e3ec2800e115bc183b32f35f27692c678d4522",EidogramCanonical.snapshotHash(direct))
        assertEquals("41e46a7b1b69fd012cc21ed9020fb1119ee00c33dfcc9924a684a788577b099b",EidogramCanonical.contentHash(direct))
        assertEquals("32d054bedc25a6dbdafa802755430b00395716ee86858fa61e99f28dfc5ff76c",EidogramCanonical.contentHash(via))
        val bytes = EidogramCanonical.documentJson(direct)
        assertEquals(bytes, EidogramCanonical.documentJson(EidogramParser.parseCanonical(bytes)))
    }

    @Test fun squareDocumentRoundTripsWithItsTransformsAndCatalogueHash() {
        var draft = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("square.violet.l", 250_000, 700_000))
        draft = EditorReducer.reduce(draft, EditorCommand.ScaleSelected(1_500_000))
        draft = EditorReducer.reduce(draft, EditorCommand.RotateSelected(45_000))
        val bytes = EidogramCanonical.documentJson(draft.document)
        val parsed = EidogramParser.parseCanonical(bytes)
        assertEquals(bytes, EidogramCanonical.documentJson(parsed))
        assertEquals(draft.snapshot, EidogramReplay.replay(parsed))
        assertEquals(EidoGlyphCatalogV1.contentHash(), parsed.catalogHash)
    }
}
