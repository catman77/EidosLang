package org.eidolang.core.model

enum class GlyphFamily { COLORED_CIRCLE, COLORED_TRIANGLE, BLACK_DOT, BLACK_STICK, BLACK_OUTLINE }

data class PaletteEntry(val index: Int, val name: String, val hexSrgb: String)

sealed interface GlyphRenderSpec {
    data class FilledCircle(val diameterFp: Int, val paletteIndex: Int) : GlyphRenderSpec
    /** Solid triangle, offered in the same colours and sizes as the circles. */
    data class FilledTriangle(val bboxWFp: Int, val bboxHFp: Int, val paletteIndex: Int) : GlyphRenderSpec
    data class Capsule(
        val lengthFp: Int,
        val thicknessFp: Int,
        val baseOrientationMdeg: Int,
        val paletteIndex: Int,
    ) : GlyphRenderSpec
    data class BlackOutline(
        val shape: String,
        val bboxWFp: Int,
        val bboxHFp: Int,
        val strokeWidthFp: Int,
        val paletteIndex: Int,
    ) : GlyphRenderSpec
}

data class GlyphDefinition(
    val glyphId: String,
    val family: GlyphFamily,
    val render: GlyphRenderSpec,
)

object EidoGlyphCatalogV1 {
    val palette = listOf(
        PaletteEntry(0, "red", "#E53935"),
        PaletteEntry(1, "orange", "#FB8C00"),
        PaletteEntry(2, "yellow", "#FDD835"),
        PaletteEntry(3, "green", "#43A047"),
        PaletteEntry(4, "cyan", "#00ACC1"),
        PaletteEntry(5, "blue", "#1E88E5"),
        PaletteEntry(6, "violet", "#8E24AA"),
        PaletteEntry(7, "white", "#FFFFFF"),
        PaletteEntry(8, "gray", "#9E9E9E"),
        PaletteEntry(9, "black", "#000000"),
    )

    private val circleDiameters = linkedMapOf("s" to 60_000, "m" to 90_000, "l" to 135_000)
    // Same steps as the circles, so a triangle reads as the same "size" as a circle beside it.
    private val triangleSizes = linkedMapOf("s" to 60_000, "m" to 90_000, "l" to 135_000)
    private val dotDiameters = linkedMapOf("s" to 24_000, "m" to 36_000, "l" to 54_000)
    private val stickLengths = linkedMapOf("s" to 90_000, "m" to 135_000, "l" to 202_500)
    private val stickThickness = linkedMapOf("thin" to 9_000, "normal" to 15_000, "thick" to 24_000)
    private val poses = listOf(0, 45, 90, 135, 180, 225, 270, 315)
    private val outline = linkedMapOf(
        "triangle" to (160_000 to 160_000),
        "square" to (150_000 to 150_000),
        "rectangle" to (180_000 to 120_000),
        "diamond" to (150_000 to 150_000),
        "circle" to (150_000 to 150_000),
        "ellipse" to (180_000 to 120_000),
        "cross" to (150_000 to 150_000),
        "plus" to (150_000 to 150_000),
    )

    val glyphs: List<GlyphDefinition> = buildList {
        palette.forEach { color ->
            circleDiameters.forEach { (size, diameter) ->
                add(GlyphDefinition(
                    "circle.${color.name}.$size",
                    GlyphFamily.COLORED_CIRCLE,
                    GlyphRenderSpec.FilledCircle(diameter, color.index),
                ))
            }
        }
        palette.forEach { color ->
            triangleSizes.forEach { (size, edge) ->
                add(GlyphDefinition(
                    "triangle.${color.name}.$size",
                    GlyphFamily.COLORED_TRIANGLE,
                    GlyphRenderSpec.FilledTriangle(edge, edge, color.index),
                ))
            }
        }
        dotDiameters.forEach { (size, diameter) ->
            add(GlyphDefinition(
                "dot.black.$size",
                GlyphFamily.BLACK_DOT,
                GlyphRenderSpec.FilledCircle(diameter, 9),
            ))
        }
        // Sticks in every palette colour, black included and first so that every identifier the
        // catalogue already had survives untouched — `stick.black.pose000.l.normal` still means
        // exactly what it always meant, and every document ever written still resolves.
        //
        // This is what changes `GLYPH_CATALOG_HASH`, and the hash is declared per document and per
        // message rather than compiled in, so material authored against the old catalogue keeps
        // verifying against the old hash it carries. Nothing frozen is invalidated; the catalogue
        // grows.
        (listOf(palette.last { it.name == "black" }) + palette.filter { it.name != "black" })
            .forEach { colour ->
                poses.forEach { angle ->
                    val pose = "pose" + angle.toString().padStart(3, '0')
                    stickLengths.forEach { (lengthName, length) ->
                        stickThickness.forEach { (thicknessName, thickness) ->
                            add(GlyphDefinition(
                                "stick.${colour.name}.$pose.$lengthName.$thicknessName",
                                GlyphFamily.BLACK_STICK,
                                GlyphRenderSpec.Capsule(length, thickness, angle * 1000, colour.index),
                            ))
                        }
                    }
                }
            }
        outline.forEach { (shape, dims) ->
            add(GlyphDefinition(
                "outline.$shape",
                GlyphFamily.BLACK_OUTLINE,
                GlyphRenderSpec.BlackOutline(shape, dims.first, dims.second, 9_000, 9),
            ))
        }
    }

    /**
     * A digest of what this catalogue actually contains.
     *
     * `GLYPH_CATALOG_HASH` was a constant maintained by hand, so nothing stopped the catalogue and
     * its identifier drifting apart — and that identifier is what every document and every message
     * declares its lineage against. Derived from the contents, the two cannot disagree: change a
     * glyph and the hash changes with it, and `CatalogTest` fails until the constant is updated
     * deliberately.
     */
    fun contentHash(): String {
        val text = glyphs.joinToString("\n") { g ->
            val spec = when (val r = g.render) {
                is GlyphRenderSpec.FilledCircle -> "circle:${r.diameterFp}:${r.paletteIndex}"
                is GlyphRenderSpec.FilledTriangle -> "triangle:${r.bboxWFp}:${r.bboxHFp}:${r.paletteIndex}"
                is GlyphRenderSpec.Capsule ->
                    "capsule:${r.lengthFp}:${r.thicknessFp}:${r.baseOrientationMdeg}:${r.paletteIndex}"
                is GlyphRenderSpec.BlackOutline ->
                    "outline:${r.shape}:${r.bboxWFp}:${r.bboxHFp}:${r.strokeWidthFp}:${r.paletteIndex}"
            }
            "${g.glyphId}|${g.family}|$spec"
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val byId = glyphs.associateBy { it.glyphId }
    fun requireGlyph(id: String): GlyphDefinition = requireNotNull(byId[id]) { "Unknown glyph_id: $id" }
    fun family(family: GlyphFamily): List<GlyphDefinition> = glyphs.filter { it.family == family }
}
