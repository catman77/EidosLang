package org.eidolang.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.repository.*
import org.eidolang.feature.home.ChatScreen
import org.eidolang.feature.home.GroupEditorDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatLayoutTest {
    @get:Rule val rule = createComposeRule()
    private fun item(i: Int) = TimelineItem("message$i", "author", "dev:author", i,
        1_700_000_000_000 + i, emptyList(), true, MessageLocalState.ACCEPTED,
        EidogramDocumentV1(listOf(EidogramAction.Add(0, "g1", "circle.blue.m", FixedTransform(500_000, 500_000), 0))), "Сообщение $i")

    @Test fun lastMessageStaysAboveWriteButtonAlsoWithLargeText() {
        val fontScale = mutableFloatStateOf(1f)
        var written = false
        rule.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                    Box(Modifier.fillMaxWidth().height(540.dp)) {
                        ChatScreen("Проверка", (0..19).map(::item), 0, onWrite = { written = true })
                    }
                }
            }
        }
        fun checkBounds() {
            rule.onNodeWithText("Сообщение 19").assertIsDisplayed()
            val bubble = rule.onNodeWithTag("chatMessage-message19").fetchSemanticsNode().boundsInRoot
            val write = rule.onNodeWithText("Написать").fetchSemanticsNode().boundsInRoot
            assertTrue("Последнее сообщение перекрыто кнопкой: $bubble / $write", bubble.bottom <= write.top)
        }
        rule.waitForIdle(); checkBounds()
        rule.runOnIdle { fontScale.floatValue = 1.8f }
        rule.waitForIdle(); checkBounds()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            instrumentation.targetContext.cacheDir.resolve("chat-layout-large-text.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        rule.onNodeWithText("Написать").performClick()
        rule.runOnIdle { assertTrue(written) }
    }

    @Test fun emptyGroupHasAnActiveWriteButton() {
        var written = false
        rule.setContent { MaterialTheme { ChatScreen("Новая группа", emptyList(), 0, onWrite = { written = true }) } }
        rule.onNodeWithText("Здесь пока пусто.").assertIsDisplayed()
        rule.onNodeWithText("Написать").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(written) }
    }

    @Test fun groupCannotBeCreatedWithoutANameAndASelectedContact() {
        var result: Pair<String, Set<String>>? = null
        rule.setContent { MaterialTheme {
            GroupEditorDialog(listOf(ContactSummary("bob", "Боб", 1), ContactSummary("charlie", "Чарли", 1)), "self",
                onDismiss = {}, onConfirm = { title, members -> result = title to members })
        } }
        rule.onNodeWithText("Создать", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Создать").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Друзья")
        rule.onNodeWithText("Создать").assertIsNotEnabled()
        rule.onNodeWithText("Боб").performClick()
        rule.onNodeWithText("Создать").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("Друзья" to setOf("bob"), result) }
    }
}
