package org.eidolang.app

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.eidolang.core.model.*
import org.eidolang.feature.editor.DraftStore
import org.eidolang.feature.editor.EidoEditorScreen
import org.eidolang.feature.home.renderEidogram
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.min

class EditorSquareTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aSquareCanBeChosenColouredPlacedAndSelectedByItsCorner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "editor-square"
        runBlocking { DraftStore.clearInBackground(context, key).await() }
        try {
            rule.setContent { EidoEditorScreen(draftKey = key) }
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("eidogramCanvas").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("glyphFamilies").performScrollToNode(hasText("Квадраты"))
            rule.onNodeWithText("Квадраты").performClick()
            rule.onNodeWithTag("glyphColours").performScrollToNode(hasTestTag("glyphColour-square-blue"))
            rule.onNodeWithTag("glyphColour-square-blue").performClick()
            rule.onNodeWithText("L").performClick()
            rule.onNodeWithTag("eidogramCanvas").performTouchInput { click(center) }
            val restored = runBlocking { DraftStore.loadInBackground(context, key).getOrThrow() }
            assertEquals("square.blue.l", restored.snapshot.instances.single().glyphId)

            rule.onNodeWithTag("glyphFamilies").performScrollToNode(hasText("Перемещать"))
            rule.onNodeWithText("Перемещать").performClick()
            val canvas = rule.onNodeWithTag("eidogramCanvas")
            canvas.performTouchInput { click(Offset(10f, 10f)) }
            rule.onNodeWithContentDescription("Удалить").assertIsNotEnabled()
            canvas.performTouchInput {
                val corner = min(width, height) * 0.06f
                click(center + Offset(corner, corner))
            }
            rule.onNodeWithContentDescription("Удалить").assertIsEnabled()
        } finally {
            runBlocking { DraftStore.clearInBackground(context, key).await() }
        }
    }

    @Test
    fun squareRenderingFillsItsCornersInEveryPaletteColour() {
        EidoGlyphCatalogV1.palette.forEach { colour ->
            val document = EidogramDocumentV1(listOf(
                EidogramAction.Add(0, "g000001", "square.${colour.name}.m", FixedTransform(500_000, 500_000), 0),
            ))
            val bitmap = requireNotNull(renderEidogram(document, 200))
            try {
                val expected = Color.parseColor(colour.hexSrgb)
                assertEquals(colour.name, expected, bitmap.getPixel(100, 100))
                assertEquals("угол квадрата ${colour.name} не закрашен", expected, bitmap.getPixel(107, 107))
                assertEquals("квадрат вышел за свои границы", Color.WHITE, bitmap.getPixel(110, 100))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
