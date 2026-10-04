package org.eidolang.app

import android.os.Debug
import android.system.Os
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.model.EditorCommand
import org.eidolang.core.model.EditorDraft
import org.eidolang.core.model.EditorReducer
import org.eidolang.feature.editor.DraftStore
import org.eidolang.feature.home.EidoHomeApp
import org.eidolang.feature.messenger.EidoMessengerApp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MemoryLifecycleTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun queuedDraftWriteDoesNotRetainUndoHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "memory-queued-draft"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Hold the real I/O queue to model a slow disk/Keystore. Only undo history should be
        // collectable: the current document must survive until the queued write finishes.
        val field = DraftStore::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }
        val dispatcher = field.get(null) as ExecutorCoroutineDispatcher
        dispatcher.executor.execute {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val (history, pending) = enqueueDraftWithUndo(key)
            repeat(10) {
                if (history.get() != null) {
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    Thread.sleep(50)
                }
            }
            assertTrue("Очередь записи удерживает историю отмены закрытого редактора", history.get() == null)
            release.countDown()
            runBlocking { pending.await().getOrThrow() }
            val loaded = runBlocking { DraftStore.loadInBackground(context, key).getOrThrow() }
            assertTrue(loaded.snapshot.instances.single().glyphId == "square.blue.m")
        } finally {
            release.countDown()
            runBlocking { DraftStore.clearInBackground(context, key).await() }
        }
    }

    private fun enqueueDraftWithUndo(key: String): Pair<WeakReference<Any>, Deferred<Result<Unit>>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seeded = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("square.blue.m"))
        val history = List(2_000) { seeded.actions.toList() }
        val draft = seeded.copy(undo = history)
        return WeakReference<Any>(history) to DraftStore.saveInBackground(context, draft, key)
    }

    @Test
    fun repeatedHomeAndProtocolScreensDoNotAccumulateDatabaseConnections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = AndroidKeystoreIdentityStore(context).loadIfPresent()
        assumeNotNull(identity)
        var screen by mutableStateOf("closed")
        rule.setContent {
            when (screen) {
                "home" -> EidoHomeApp(identity!!, onOpenAdvanced = {})
                "protocol" -> EidoMessengerApp(identity!!)
                else -> Text("Закрыт")
            }
        }
        rule.waitForIdle()
        // Warm both screens first: the shared process database deliberately stays available to
        // delivery jobs. The number of connections must then stay constant across navigation.
        for (next in listOf("home", "protocol")) {
            rule.runOnIdle { screen = next }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText(if (next == "home") "Ещё" else "Диагностика")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.runOnIdle { screen = "closed" }
            rule.waitForIdle()
        }
        val baseline = databaseDescriptors()
        val samples = mutableListOf<Int>()
        repeat(12) { cycle ->
            for (next in listOf("home", "protocol")) {
                rule.runOnIdle { screen = next }
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithText(if (next == "home") "Ещё" else "Диагностика")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                rule.runOnIdle { screen = "closed" }
                rule.waitForIdle()
                samples += databaseDescriptors()
            }
            val runtime = Runtime.getRuntime()
            Log.i("MemoryAudit", "cycle=$cycle dbFds=${samples.last()} " +
                "javaBytes=${runtime.totalMemory() - runtime.freeMemory()} " +
                "nativeBytes=${Debug.getNativeHeapAllocatedSize()}")
        }
        assertTrue("Соединения с БД остаются после закрытия экранов: baseline=$baseline samples=$samples",
            samples.all { it <= baseline })
    }

    private fun databaseDescriptors(): Int = File("/proc/self/fd").listFiles().orEmpty().count {
        runCatching { Os.readlink(it.absolutePath).contains("eidolang-messenger-r18.db") }
            .getOrDefault(false)
    }
}
