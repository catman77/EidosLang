package org.eidolang.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import org.eidolang.core.model.EditorCommand
import org.eidolang.core.model.EditorDraft
import org.eidolang.core.model.EditorReducer
import org.eidolang.core.model.EidoGlyphCatalogV1
import org.eidolang.core.model.GlyphRenderSpec
import org.eidolang.feature.editor.EidoEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Two things a person tried to do in the editor and could not.
 *
 * Both were reported from the phone, and neither had any test at all — the editor's reducer and its
 * gesture loop were covered only by whether the app compiled.
 */
class EditorOverlapAndLineScaleTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Making a line longer must not make it thicker.
     *
     * Thickness is chosen in the palette — thin, normal, thick — so a resize that changes it
     * overwrites a decision the author made on purpose. The assertion is on the scale factors rather
     * than on pixels because that is where the bug was: `ScaleSelected` multiplied both axes.
     */
    @Test
    fun scalingALineChangesItsLengthAndNotItsWeight() {
        val glyph = "stick.black.pose045.l.normal"
        var draft = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph(glyph))
        val id = draft.snapshot.instances.single().instanceId
        draft = EditorReducer.reduce(draft, EditorCommand.Select(setOf(id)))
        draft = EditorReducer.reduce(draft, EditorCommand.ScaleSelected(2_000_000))

        val after = draft.snapshot.instances.single()
        assertEquals("длина не выросла", 2_000_000, after.transform.scaleXFp)
        assertEquals("толщина изменилась вместе с длиной", 1_000_000, after.transform.scaleYFp)

        // The pose is folded into the instance's own rotation so that "length" really is the X axis;
        // a 45° capsule scaled along X in its own drawn frame would have been sheared, not lengthened.
        val capsule = EidoGlyphCatalogV1.requireGlyph(after.glyphId).render as GlyphRenderSpec.Capsule
        assertEquals("поза не свёрнута в поворот", 0, capsule.baseOrientationMdeg)
        assertEquals("линия развернулась", 45_000, after.transform.rotationMdeg)
        assertEquals(
            "толщина в единицах холста изменилась",
            (EidoGlyphCatalogV1.requireGlyph(glyph).render as GlyphRenderSpec.Capsule).thicknessFp,
            capsule.thicknessFp,
        )
        println("EDITOR PASS scaling a line changes only its length")
    }

    /** Everything that is not a line still resizes as a whole. */
    @Test
    fun scalingAShapeStillScalesBothAxes() {
        var draft = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("circle.red.m"))
        val id = draft.snapshot.instances.single().instanceId
        draft = EditorReducer.reduce(draft, EditorCommand.Select(setOf(id)))
        draft = EditorReducer.reduce(draft, EditorCommand.ScaleSelected(2_000_000))

        val t = draft.snapshot.instances.single().transform
        assertEquals(2_000_000, t.scaleXFp)
        assertEquals("круг перестал быть круглым", 2_000_000, t.scaleYFp)
        println("EDITOR PASS a shape still scales on both axes")
    }

    /**
     * A triangle can be put inside a circle.
     *
     * A tap on an occupied point can only mean one thing, and it meant "select what is there", so
     * overlap — the whole point of composing a figure out of glyphs — was unreachable. Long press
     * now places regardless of what is underneath.
     *
     * Driven through the real gesture loop rather than by calling the reducer, because the reducer
     * never refused this: the refusal was in the pointer handler, and only a gesture can show it.
     */
    @Test
    fun aLongPressPlacesOnTopOfWhatIsAlreadyThere() {
        // A draft left by an earlier run would make the count meaningless.
        org.eidolang.feature.editor.DraftStore.clear(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            "overlaptest",
        )
        rule.setContent { EidoEditorScreen(draftKey = "overlaptest") }
        rule.waitForIdle()

        val canvas = rule.onNodeWithTag("eidogramCanvas")

        // The editor opens with no pen, so touching the canvas must leave nothing behind. It used
        // to open armed with a red circle, and every exploratory tap stamped one.
        canvas.performTouchInput { longClick(Offset(centerX, centerY)) }
        canvas.performTouchInput { click(Offset(centerX, centerY)) }
        rule.waitForIdle()
        assertEquals("без выбранной фигуры нажатие всё равно что-то поставило", 0, countInstances("overlaptest"))
        println("EDITOR PASS with no shape chosen the canvas places nothing")

        rule.onNodeWithText("Круги").performClick()
        rule.waitForIdle()

        // Same point twice. The first lands on empty canvas, the second lands on the glyph the
        // first one placed — which is precisely the case that used to select instead of place.
        canvas.performTouchInput { longClick(Offset(centerX, centerY)) }
        rule.waitForIdle()
        canvas.performTouchInput { longClick(Offset(centerX, centerY)) }
        rule.waitForIdle()

        assertEquals("вторая фигура не поставилась поверх первой", 2, countInstances("overlaptest"))
        println("EDITOR PASS a long press places a second glyph on the same spot")

        // And it can be switched back off again, which is the point of it being a mode.
        rule.onNodeWithText("Перемещать").performClick()
        rule.waitForIdle()
        canvas.performTouchInput { longClick(Offset(centerX, centerY)) }
        rule.waitForIdle()
        assertEquals("режим перемещения не выключил постановку", 2, countInstances("overlaptest"))
        println("EDITOR PASS placement can be turned back off")
    }

    /** Read the persisted draft back, which is the only handle on what the screen actually did. */
    private fun countInstances(key: String): Int {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val draft = org.eidolang.feature.editor.DraftStore.load(ctx, key).getOrNull()
        assertTrue("черновик не сохранился", draft != null)
        return draft!!.snapshot.instances.size
    }
}
