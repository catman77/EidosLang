package org.eidolang.app

import android.os.StrictMode
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.eidolang.core.model.EditorCommand
import org.eidolang.core.model.EditorDraft
import org.eidolang.core.model.EditorReducer
import org.eidolang.feature.editor.DraftStore
import org.eidolang.feature.editor.EidoEditorScreen
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class EditorResponsivenessTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loadingAndDraggingADraftDoNotAccessDiskOnTheUiThread() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 28)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = "editor-responsiveness"
        DraftStore.clear(context, key)
        val seeded = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("circle.red.m"))
        DraftStore.save(context, seeded, key).getOrThrow()

        val violations = CopyOnWriteArrayList<String>()
        var previousPolicy: StrictMode.ThreadPolicy? = null
        instrumentation.runOnMainSync {
            previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener({ it.run() }) { violation ->
                        if (violation.stackTrace.any { it.className.startsWith(DraftStore::class.java.name) }) {
                            violations += violation.toString()
                        }
                    }
                    .build(),
            )
        }
        try {
            rule.setContent { EidoEditorScreen(draftKey = key) }
            waitForCanvas()
            rule.onNodeWithTag("eidogramCanvas").performTouchInput { click(center) }
            rule.waitForIdle()
            rule.onNodeWithTag("eidogramCanvas").performTouchInput {
                down(center)
                repeat(30) { step ->
                    moveTo(center + Offset((step + 1) * 2f, 0f))
                }
                up()
            }
            rule.waitForIdle()
            rule.waitUntil(5_000) {
                DraftStore.load(context, key).getOrNull()?.snapshot?.instances?.singleOrNull()
                    ?.transform?.cxFp?.let { it > 500_000 } == true
            }
            assertTrue("Запись черновика блокирует UI: $violations", violations.isEmpty())
            // One gesture remains one undo step even though it received many move events.
            rule.onNodeWithContentDescription("Отменить").performClick()
            rule.waitUntil(5_000) {
                DraftStore.load(context, key).getOrNull()?.actions == seeded.actions
            }
            rule.onNodeWithTag("eidogramCanvas").performTouchInput { click(Offset(10f, 10f)) }
            rule.waitForIdle()
        } finally {
            instrumentation.runOnMainSync { StrictMode.setThreadPolicy(previousPolicy!!) }
            runBlocking { DraftStore.clearInBackground(context, key).await() }
        }
    }

    @Test
    fun closingDuringADragSavesItsLastPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "editor-interrupted-drag"
        DraftStore.clear(context, key)
        val seeded = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("circle.red.m"))
        DraftStore.save(context, seeded, key).getOrThrow()
        var open by mutableStateOf(true)
        try {
            rule.setContent { if (open) EidoEditorScreen(draftKey = key) else Text("Закрыт") }
            waitForCanvas()
            val canvas = rule.onNodeWithTag("eidogramCanvas")
            canvas.performTouchInput { click(center) }
            rule.waitForIdle()
            canvas.performTouchInput {
                down(center)
                moveTo(center + Offset(50f, 0f))
                moveTo(center + Offset(80f, 0f))
            }
            rule.runOnIdle { open = false }
            rule.waitForIdle()
            val restored = runBlocking { DraftStore.loadInBackground(context, key).getOrThrow() }
            assertTrue("последнее движение потеряно при закрытии", restored.snapshot.instances.single().transform.cxFp > 500_000)
        } finally {
            runBlocking { DraftStore.clearInBackground(context, key).await() }
        }
    }

    @Test
    fun confirmingClearsTheDraftAfterAllPendingWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "editor-confirm-clear"
        DraftStore.clear(context, key)
        var open by mutableStateOf(true)
        var acceptedCount = 0
        try {
            rule.setContent {
                if (open) EidoEditorScreen(
                    draftKey = key,
                    onSend = { acceptedCount = it.actions.size; true },
                    onClose = { open = false },
                ) else Text("Закрыт")
            }
            waitForCanvas()
            rule.onNodeWithText("Круги").performClick()
            rule.onNodeWithTag("eidogramCanvas").performTouchInput { click(center) }
            rule.onNodeWithText("Отправить").performClick()
            rule.waitForIdle()
            assertEquals(1, acceptedCount)
            val restored = runBlocking { DraftStore.loadInBackground(context, key).getOrThrow() }
            assertTrue("фоновая запись вернула уже отправленный черновик", restored.actions.isEmpty())
            rule.runOnIdle { open = true }
            waitForCanvas()
            rule.onNodeWithText("Отправить").assertIsNotEnabled()
        } finally {
            runBlocking { DraftStore.clearInBackground(context, key).await() }
        }
    }

    private fun waitForCanvas() {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("eidogramCanvas").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
