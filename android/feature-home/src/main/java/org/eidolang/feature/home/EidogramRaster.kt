package org.eidolang.feature.home

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.EidogramReplay
import org.eidolang.core.render.drawGlyph

/**
 * Draw an eidogram into a bitmap, away from the screen.
 *
 * The same `drawGlyph` the app renders with, so an exported document shows exactly what the chat
 * shows — a second drawing routine for export would be a second thing to keep correct, and the
 * first to drift.
 *
 * White background on purpose: the export lands in Word, on paper, in a mail client, none of which
 * carry the app's theme, and glyphs drawn for a light surface on transparency come out invisible.
 */
fun renderEidogram(document: EidogramDocumentV1, sizePx: Int = 512): Bitmap? = runCatching {
    val snapshot = EidogramReplay.replay(document)
    val image = ImageBitmap(sizePx, sizePx)
    CanvasDrawScope().draw(
        Density(1f), LayoutDirection.Ltr, Canvas(image), Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        drawRect(Color.White, size = size)
        snapshot.instances.forEach { drawGlyph(it) }
    }
    image.asAndroidBitmap()
}.getOrNull()
