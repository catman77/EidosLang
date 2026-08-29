package org.eidolang.feature.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.EidogramReplay
import org.eidolang.core.render.drawGlyph

@Composable
fun EidogramViewer(
    document: EidogramDocumentV1,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
) {
    val snapshot = EidogramReplay.replay(document)
    Canvas(modifier.background(background)) {
        snapshot.instances.forEach { drawGlyph(it) }
    }
}
