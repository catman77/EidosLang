package org.eidolang.core.model

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    @Test fun catalogGlyphIdsAreUnique() {
        assertEquals(EidoGlyphCatalogV1.glyphs.size,EidoGlyphCatalogV1.glyphs.map{it.glyphId}.toSet().size)
        // The declared lineage must match what the catalogue actually holds. This was a constant
        // maintained by hand, so nothing stopped the two drifting — and that value is what every
        // document and message declares itself against.
        assertEquals(ProtocolLineage.GLYPH_CATALOG_HASH, EidoGlyphCatalogV1.contentHash())
        // Colours reach the sticks now, and the black ones keep the exact ids they always had.
        assertEquals(true, EidoGlyphCatalogV1.glyphs.any { it.glyphId == "stick.black.pose000.l.normal" })
        assertEquals(true, EidoGlyphCatalogV1.glyphs.any { it.glyphId == "stick.blue.pose000.l.normal" })
        assertEquals(30,EidoGlyphCatalogV1.family(GlyphFamily.COLORED_CIRCLE).size)
        assertEquals(3,EidoGlyphCatalogV1.family(GlyphFamily.BLACK_DOT).size)
        // 10 colours x 8 poses x 3 lengths x 3 thicknesses. It was 72 while sticks were black by
        // definition of the catalogue; the black ones are still all 72 of them, untouched.
        assertEquals(720,EidoGlyphCatalogV1.family(GlyphFamily.BLACK_STICK).size)
        assertEquals(
            72,
            EidoGlyphCatalogV1.family(GlyphFamily.BLACK_STICK).count { it.glyphId.startsWith("stick.black.") },
        )
        assertEquals(8,EidoGlyphCatalogV1.family(GlyphFamily.BLACK_OUTLINE).size)
    }
    @Test fun editorUndoRedo() {
        var d=EditorDraft()
        d=EditorReducer.reduce(d,EditorCommand.AddGlyph("circle.red.m"))
        d=EditorReducer.reduce(d,EditorCommand.TranslateSelected(10_000,20_000))
        assertEquals(510_000,d.snapshot.instances.single().transform.cxFp)
        d=EditorReducer.reduce(d,EditorCommand.Undo)
        assertEquals(500_000,d.snapshot.instances.single().transform.cxFp)
        d=EditorReducer.reduce(d,EditorCommand.Redo)
        assertEquals(510_000,d.snapshot.instances.single().transform.cxFp)
    }
}
