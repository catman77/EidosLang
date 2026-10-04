package org.eidolang.core.model

import org.junit.Assert.*
import org.junit.Test

class SquareGlyphTest {
    @Test
    fun squaresOfferTheSameColourAndSizeCombinationsAsCirclesAndTriangles() {
        val circles = EidoGlyphCatalogV1.family(GlyphFamily.COLORED_CIRCLE)
        val triangles = EidoGlyphCatalogV1.family(GlyphFamily.COLORED_TRIANGLE)
        val squares = EidoGlyphCatalogV1.glyphs.filter { it.glyphId.startsWith("square.") }
        assertEquals(circles.map { it.glyphId.removePrefix("circle.") }.toSet(), squares.map { it.glyphId.removePrefix("square.") }.toSet())
        assertEquals(triangles.map { it.glyphId.removePrefix("triangle.") }.toSet(), squares.map { it.glyphId.removePrefix("square.") }.toSet())
        assertEquals(30, squares.size)
        assertEquals(setOf("COLORED_SQUARE"), squares.map { it.family.name }.toSet())
        squares.forEach { square ->
            val suffix = square.glyphId.removePrefix("square.")
            val render = square.render as GlyphRenderSpec.FilledSquare
            val circle = EidoGlyphCatalogV1.requireGlyph("circle.$suffix").render as GlyphRenderSpec.FilledCircle
            val triangle = EidoGlyphCatalogV1.requireGlyph("triangle.$suffix").render as GlyphRenderSpec.FilledTriangle
            assertEquals(circle.paletteIndex, render.paletteIndex)
            assertEquals(triangle.paletteIndex, render.paletteIndex)
            assertEquals(circle.diameterFp, render.edgeFp)
            assertEquals(triangle.bboxWFp, render.edgeFp)
            assertEquals(triangle.bboxHFp, render.edgeFp)
        }
    }

    @Test
    fun squareCornersAreSelectableAndPointsOutsideItsEdgesAreNot() {
        val square = GlyphInstance("g000001", "square.blue.m", FixedTransform(500_000, 500_000), 0)
        assertTrue(EidogramHitTest.contains(square, 544_000, 544_000, touchSlopFp = 0))
        assertTrue(EidogramHitTest.contains(square, 455_000, 455_000, touchSlopFp = 0))
        assertFalse(EidogramHitTest.contains(square, 546_000, 500_000, touchSlopFp = 0))
        assertFalse(EidogramHitTest.contains(square, 500_000, 454_000, touchSlopFp = 0))
    }

    @Test
    fun squareSelectionFollowsItsRotationAndNonUniformScale() {
        val square = GlyphInstance(
            "g000001", "square.green.m",
            FixedTransform(500_000, 500_000, scaleXFp = 2_000_000, rotationMdeg = 90_000), 0,
        )
        assertTrue(EidogramHitTest.contains(square, 460_000, 588_000, touchSlopFp = 0))
        assertFalse(EidogramHitTest.contains(square, 500_000, 592_000, touchSlopFp = 0))
        assertFalse(EidogramHitTest.contains(square, 454_000, 500_000, touchSlopFp = 0))
    }
}
