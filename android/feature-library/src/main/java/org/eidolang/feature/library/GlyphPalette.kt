package org.eidolang.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.eidolang.core.model.EidoGlyphCatalogV1
import org.eidolang.core.model.GlyphFamily
import org.eidolang.core.render.GlyphPreview

/**
 * Choosing a glyph along the axes it actually has, instead of scrolling 113 thumbnails.
 *
 * `EidoGlyphCatalogV1` is frozen and structured: 10 colours x 3 sizes of circle, 3 dot sizes,
 * 8 slopes x 3 lengths x 3 thicknesses of line, 8 outlines. A flat list hides that structure and
 * makes picking a specific line hopeless — there are 72 of them. Here each axis is its own control,
 * and the result is the "pen" the next tap on the canvas places. Slope is the one axis with no
 * control: the editor draws a line between two points, so the gesture sets the angle.
 *
 * Colour is offered only where the frozen catalogue has it: circles. Lines, dots and outlines are
 * black by definition of the catalogue, and giving them colours would change `catalog_hash` and
 * invalidate every existing eidogram.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphPalette(
    /** Empty means no pen: the canvas is for moving and selecting, and a tap places nothing. */
    pen: String,
    onPen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var family by remember(pen) { mutableStateOf(familyOf(pen)) }

    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        // A scrolling row, not a segmented button: the catalogue gains families over time and a
        // fixed row wrapped "Контуры" onto two lines on top of its neighbour as soon as a fifth
        // one appeared.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Turning placement off belongs here, next to the thing being turned off, and not in
            // the toolbar: nine controls at 36dp already fill that row on a 393dp screen, and a
            // tenth wraps it onto a second line at the permanent cost of canvas height.
            item {
                FilterChip(
                    selected = family == null,
                    onClick = { family = null; onPen("") },
                    label = { Text("Перемещать", maxLines = 1) },
                )
            }
            items(GlyphFamily.entries.toList()) { f ->
                FilterChip(
                    selected = family == f,
                    onClick = {
                        family = f
                        onPen(defaultOf(f))
                    },
                    label = {
                        Text(
                            when (f) {
                                GlyphFamily.COLORED_CIRCLE -> "Круги"
                                GlyphFamily.COLORED_TRIANGLE -> "Треугольники"
                                GlyphFamily.BLACK_DOT -> "Точки"
                                GlyphFamily.BLACK_STICK -> "Линии"
                                GlyphFamily.BLACK_OUTLINE -> "Контуры"
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        when (family) {
            // No controls, and deliberately not an empty box: the reserved height stays the same
            // either way, so this is space that would otherwise say nothing about why tapping the
            // canvas has stopped producing shapes.
            null -> Text(
                "Нажатие по холсту ничего не ставит. Двигайте фигуры пальцем, выделяйте нажатием, " +
                    "меняйте размер и поворот двумя пальцами.",
                style = MaterialTheme.typography.bodySmall,
            )
            GlyphFamily.COLORED_CIRCLE -> ShapeControls("circle", CIRCLE_COLOURS, CIRCLE_SIZES, pen, onPen)
            GlyphFamily.COLORED_TRIANGLE -> ShapeControls("triangle", TRIANGLE_COLOURS, TRIANGLE_SIZES, pen, onPen)
            GlyphFamily.BLACK_DOT -> SizeRow("Размер", DOT_SIZES, sizeOf(pen)) { onPen("dot.black.$it") }
            GlyphFamily.BLACK_STICK -> StickControls(pen, onPen)
            GlyphFamily.BLACK_OUTLINE -> OutlineRow(pen, onPen)
        }
    }
}

// ---------------------------------------------------------------- circles

@Composable
private fun ShapeControls(
    prefix: String,
    colours: List<String>,
    sizes: List<String>,
    pen: String,
    onPen: (String) -> Unit,
) {
    val colour = pen.split('.').getOrElse(1) { colours.first() }
    val size = pen.substringAfterLast('.').takeIf { it in sizes } ?: sizes[sizes.size / 2]
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(colours) { name ->
                val swatch = swatchOf("$prefix.$name.l")
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            if (name == colour) 3.dp else 1.dp,
                            if (name == colour) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                        .clickable { onPen("$prefix.$name.$size") }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SizeRow("Размер", sizes, size) { onPen("$prefix.$colour.$it") }
    }
}

// ------------------------------------------------------------------ lines

@Composable
private fun StickControls(pen: String, onPen: (String) -> Unit) {
    val parts = pen.split('.')
    val length = parts.getOrElse(3) { "m" }
    val thickness = parts.getOrElse(4) { STICK_THICKNESS[STICK_THICKNESS.size / 2] }

    // Always the flat pose. The catalogue's eight slopes are a wire-format detail now: a line is
    // drawn between two points and carries its own angle in its transform, and a tapped one is
    // rotated by pinching. Offering the eight as a control just asked a question the gesture had
    // already answered.
    // Thickness is the only axis left worth asking about. Slope comes from the two points of the
    // drag, and length from their distance — offering either as a control asked a question the
    // gesture had already answered, and the "Длина" chips did nothing at all while drawing.
    val colour = parts.getOrElse(1) { "black" }
    Column {
        // Sticks were black by definition of a frozen catalogue. The catalogue now carries them in
        // every palette colour, so the same colour row the circles have belongs here too.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(STICK_COLOURS) { name ->
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(swatchOf("stick.$name.pose000.l.thick"))
                        .border(
                            if (name == colour) 3.dp else 1.dp,
                            if (name == colour) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                        .clickable { onPen("stick.$name.pose000.$length.$thickness") }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SizeRow("Толщина", STICK_THICKNESS, thickness) { onPen("stick.$colour.pose000.$length.$it") }
    }
}

// --------------------------------------------------------------- outlines

@Composable
private fun OutlineRow(pen: String, onPen: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(EidoGlyphCatalogV1.family(GlyphFamily.BLACK_OUTLINE), key = { it.glyphId }) { g ->
            val selected = g.glyphId == pen
            OutlinedCard(
                onClick = { onPen(g.glyphId) },
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) { GlyphPreview(g.glyphId, Modifier.size(48.dp)) }
        }
    }
}

// ---------------------------------------------------------------- shared

@Composable
private fun SizeRow(
    label: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    label = { Text(humanSize(o)) },
                )
            }
        }
    }
}

private fun humanSize(code: String) = when (code) {
    "s" -> "S"; "m" -> "M"; "l" -> "L"
    "thin" -> "тонкая"; "normal" -> "средняя"; "thick" -> "толстая"
    else -> code
}

private fun familyOf(pen: String): GlyphFamily? = when (pen.substringBefore('.')) {
    "" -> null
    "triangle" -> GlyphFamily.COLORED_TRIANGLE
    "dot" -> GlyphFamily.BLACK_DOT
    "stick" -> GlyphFamily.BLACK_STICK
    "outline" -> GlyphFamily.BLACK_OUTLINE
    else -> GlyphFamily.COLORED_CIRCLE
}

private fun defaultOf(f: GlyphFamily) = when (f) {
    GlyphFamily.COLORED_CIRCLE -> "circle.${CIRCLE_COLOURS.first()}.m"
    GlyphFamily.COLORED_TRIANGLE -> "triangle.${TRIANGLE_COLOURS.first()}.m"
    GlyphFamily.BLACK_DOT -> "dot.black.m"
    GlyphFamily.BLACK_STICK -> "stick.black.pose000.m.${STICK_THICKNESS[STICK_THICKNESS.size / 2]}"
    GlyphFamily.BLACK_OUTLINE -> EidoGlyphCatalogV1.family(GlyphFamily.BLACK_OUTLINE).first().glyphId
}

private fun sizeOf(pen: String) = pen.substringAfterLast('.').takeIf { it in CIRCLE_SIZES } ?: "m"

private fun swatchOf(glyphId: String): Color = runCatching {
    val g = EidoGlyphCatalogV1.requireGlyph(glyphId)
    val idx = when (val r = g.render) {
        is org.eidolang.core.model.GlyphRenderSpec.FilledCircle -> r.paletteIndex
        is org.eidolang.core.model.GlyphRenderSpec.FilledTriangle -> r.paletteIndex
        is org.eidolang.core.model.GlyphRenderSpec.Capsule -> r.paletteIndex
        else -> 9
    }
    Color(android.graphics.Color.parseColor(EidoGlyphCatalogV1.palette[idx].hexSrgb))
}.getOrDefault(Color.Gray)

/** Colour names present in the frozen catalogue, in its own order. */
private val CIRCLE_COLOURS: List<String> = EidoGlyphCatalogV1
    .family(GlyphFamily.COLORED_CIRCLE)
    .map { it.glyphId.split('.')[1] }
    .distinct()

/**
 * Every axis is read out of the catalogue rather than written down here.
 *
 * Hard-coding the vocabulary is how this file first shipped a "medium" line thickness that does not
 * exist — the catalogue calls it "normal", and the default pen pointed at a glyph id that would
 * have failed `requireGlyph`.
 */
private fun axis(family: GlyphFamily, part: Int): List<String> = EidoGlyphCatalogV1
    .family(family).map { it.glyphId.split('.')[part] }.distinct()

private val CIRCLE_SIZES = axis(GlyphFamily.COLORED_CIRCLE, 2)
private val TRIANGLE_COLOURS = axis(GlyphFamily.COLORED_TRIANGLE, 1)
private val TRIANGLE_SIZES = axis(GlyphFamily.COLORED_TRIANGLE, 2)
private val DOT_SIZES = axis(GlyphFamily.BLACK_DOT, 2)
private val STICK_THICKNESS = axis(GlyphFamily.BLACK_STICK, 4)
private val STICK_COLOURS = axis(GlyphFamily.BLACK_STICK, 1)
