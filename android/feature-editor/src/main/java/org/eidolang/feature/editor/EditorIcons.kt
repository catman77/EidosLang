package org.eidolang.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The editor's own toolbar pictograms.
 *
 * Material ships these only in `material-icons-extended`, which is deprecated and would pull a few
 * thousand unused vectors in for the sake of eight. Font symbols (`↶`, `⊞`) were the other option
 * and are worse: whether they render at all depends on the device's font coverage, and a missing
 * glyph shows up as a blank box on a user's phone and on nobody's emulator.
 *
 * Stroked rather than filled, so they read at chip size and match each other.
 */
private fun icon(name: String, build: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply(build).build()

private fun ImageVector.Builder.stroke(
    width: Float = 2f,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) = path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

/** An arrow bending back on itself: the shape everything uses for undo. */
val IconUndo: ImageVector = icon("undo") {
    stroke {
        moveTo(4f, 11f); lineTo(13f, 11f); quadTo(20f, 11f, 20f, 18f)
    }
    stroke {
        moveTo(9f, 6f); lineTo(4f, 11f); lineTo(9f, 16f)
    }
}

val IconRedo: ImageVector = icon("redo") {
    stroke {
        moveTo(20f, 11f); lineTo(11f, 11f); quadTo(4f, 11f, 4f, 18f)
    }
    stroke {
        moveTo(15f, 6f); lineTo(20f, 11f); lineTo(15f, 16f)
    }
}

/** A bin: lid, body, and two staves so it is not mistaken for a plain box. */
val IconDelete: ImageVector = icon("delete") {
    stroke { moveTo(4f, 7f); lineTo(20f, 7f) }
    stroke { moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f) }
    stroke { moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f) }
    stroke(1.5f) { moveTo(10f, 10f); lineTo(10f, 17f) }
    stroke(1.5f) { moveTo(14f, 10f); lineTo(14f, 17f) }
}

/** Two shapes pulled together until they overlap. */
val IconGroup: ImageVector = icon("group") {
    stroke { moveTo(4f, 4f); lineTo(14f, 4f); lineTo(14f, 14f); lineTo(4f, 14f); close() }
    stroke { moveTo(10f, 10f); lineTo(20f, 10f); lineTo(20f, 20f); lineTo(10f, 20f); close() }
}

/** The same two shapes driven apart. */
val IconUngroup: ImageVector = icon("ungroup") {
    stroke { moveTo(3f, 3f); lineTo(11f, 3f); lineTo(11f, 11f); lineTo(3f, 11f); close() }
    stroke { moveTo(13f, 13f); lineTo(21f, 13f); lineTo(21f, 21f); lineTo(13f, 21f); close() }
    stroke(1.2f) { moveTo(12.5f, 11.5f); lineTo(11.5f, 12.5f) }
}

/** A marquee with a tick: choosing things. */
val IconSelect: ImageVector = icon("select") {
    stroke(1.4f) { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close() }
    stroke { moveTo(8f, 12.5f); lineTo(11f, 15.5f); lineTo(16.5f, 9f) }
}

/** A drawn segment with its two endpoints. */
val IconLine: ImageVector = icon("line") {
    stroke { moveTo(5f, 19f); lineTo(19f, 5f) }
    stroke(3f) { moveTo(5f, 19f); lineTo(5.1f, 19f) }
    stroke(3f) { moveTo(19f, 5f); lineTo(19.1f, 5f) }
}

/** Z-order: the arrow says which way, the bar says what it moves past. */
val IconBringForward: ImageVector = icon("bringForward") {
    stroke { moveTo(12f, 20f); lineTo(12f, 6f) }
    stroke { moveTo(7f, 11f); lineTo(12f, 6f); lineTo(17f, 11f) }
}

val IconSendBackward: ImageVector = icon("sendBackward") {
    stroke { moveTo(12f, 4f); lineTo(12f, 18f) }
    stroke { moveTo(7f, 13f); lineTo(12f, 18f); lineTo(17f, 13f) }
}
