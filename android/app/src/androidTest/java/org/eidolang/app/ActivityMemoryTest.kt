package org.eidolang.app

import android.os.Debug
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.ref.WeakReference

class ActivityMemoryTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun reopeningEditorAndRecreatingActivityDoesNotRetainDestroyedActivities() {
        waitForHome()
        val destroyed = mutableListOf<WeakReference<MainActivity>>()
        // Read the owner's draft without editing it, then return through the editor's close button.
        repeat(20) { cycle ->
            rule.onNodeWithText("Создать", useUnmergedTree = true).performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("eidogramCanvas").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("←").performClick()
            waitForHome()
            if ((cycle + 1) % 5 == 0) {
                collectGarbage()
                val runtime = Runtime.getRuntime()
                Log.i("MemoryAudit", "editorCycle=${cycle + 1} " +
                    "javaBytes=${runtime.totalMemory() - runtime.freeMemory()} " +
                    "nativeBytes=${Debug.getNativeHeapAllocatedSize()}")
                destroyed += currentActivityReference()
                rule.activityRule.scenario.recreate()
                waitForHome()
            }
        }
        // Coroutine cancellation can wait for an in-flight I/O operation before releasing its
        // activity. Repeated GC checks distinguish a retained screen from a temporary allocation.
        rule.waitUntil(30_000) {
            collectGarbage()
            destroyed.all { it.get() == null }
        }
        assertTrue("Уничтоженные Activity удерживаются после пересоздания", destroyed.all { it.get() == null })
        Log.i("MemoryAudit", "destroyedActivities=${destroyed.size} retainedActivities=0")
    }

    private fun currentActivityReference(): WeakReference<MainActivity> = WeakReference(rule.activity)

    private fun waitForHome() {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Создать", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun collectGarbage() {
        Runtime.getRuntime().gc()
        System.runFinalization()
    }
}
