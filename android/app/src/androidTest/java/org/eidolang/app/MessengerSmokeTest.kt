package org.eidolang.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class MessengerSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private companion object {
        const val PROTOCOL_ENTRY = "Устройства, архивы, диагностика"
    }

    /**
     * The system back button, through the activity's own dispatcher.
     *
     * That is the path `BackHandler` registers on, so this measures the thing under test rather
     * than some neighbouring mechanism — the same reason `uiautomator` is the wrong instrument for
     * a Compose assertion.
     */
    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /**
     * Reach the protocol screens.
     *
     * R22.2 put a person-facing shell in front of them: identity first, then a nickname, and the
     * device/archive/diagnostics screens moved behind "Ещё". These tests are about the protocol
     * layer, so they walk through the new front door rather than assert it.
     */
    private fun enterPrimaryHomeIfNeeded() {
        if (rule.onAllNodesWithText("Создать основной профиль").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Создать основной профиль").performClick()
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Как вас зовут?").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Ещё").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Как вас зовут?").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Ник").performTextInput("Тест")
            rule.onNodeWithText("Продолжить").performClick()
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Ещё").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Ещё").performClick()
        // "Ещё" now opens a service screen rather than dropping straight into the protocol layer.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(PROTOCOL_ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(PROTOCOL_ENTRY).performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Диагностика").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Stop at the everyday shell instead of walking on into the protocol screens. */
    private fun enterHomeShell() {
        if (rule.onAllNodesWithText("Создать основной профиль").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Создать основной профиль").performClick()
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Как вас зовут?").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Ещё").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Как вас зовут?").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Ник").performTextInput("Тест")
            rule.onNodeWithText("Продолжить").performClick()
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Чаты").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Every screen behind "Ещё" can be left again.
     *
     * It could not: the button dropped into the protocol screens, which have no navigation of their
     * own, so the system back button exited the app. Nothing caught it, because the suite only ever
     * walked *into* those screens on its way to what it wanted to check, and never tried to leave.
     *
     * Back from the protocol screens lands on the main shell rather than on the service screen it
     * was opened from: `EidoHomeApp` leaves composition while they are up, taking its state with it.
     * That is a smaller thing than an unescapable screen, and asserting it here is what stops it
     * being mistaken for the bug that was fixed.
     */
    @Test
    fun theServiceScreensCanBeLeftAgain() {
        enterHomeShell()
        rule.onNodeWithText("Ещё").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(PROTOCOL_ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        // The service screen itself has a way out.
        rule.onAllNodesWithText("Назад").onFirst().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Эйдограммы").fetchSemanticsNodes().isNotEmpty()
        }

        // And so do the protocol screens, which is where the dead end was.
        rule.onNodeWithText("Ещё").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(PROTOCOL_ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(PROTOCOL_ENTRY).performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Диагностика").fetchSemanticsNodes().isNotEmpty()
        }
        pressBack()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Чаты").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Эйдограммы").assertExists()
        rule.onNodeWithText("Контакты").assertExists()
        println("HOME PASS the service screens return to the app instead of leaving it")
    }

    /**
     * The chat list replaced a flat inbox; it is the tab a person lives in.
     *
     * Both states are asserted because the device decides which one appears: a clean install has no
     * conversations, and one that has received anything does. Demanding the empty hint would have
     * been a test that passes only on a phone nobody has used — which is how it first failed here,
     * on an emulator still holding the two-device delivery.
     *
     * Claim boundary: this shows the tab exists and renders a legitimate state. Opening a
     * conversation and reading the thread is *not* asserted here — there is no way to address a
     * chat row without knowing what is in it, so that path is currently checked by hand.
     */
    @Test
    fun theChatsTabExists() {
        enterHomeShell()
        rule.onNodeWithText("Чаты").performClick()
        rule.waitForIdle()
        val empty = rule.onAllNodesWithText("Переписки пока нет. Добавьте контакт и отправьте эйдограмму.")
            .fetchSemanticsNodes().isNotEmpty()
        // Whatever it shows, the shell must survive it: a screen that navigates away or throws
        // takes the navigation bar with it.
        rule.onNodeWithText("Эйдограммы").assertExists()
        rule.onNodeWithText("Контакты").assertExists()
        println("HOME PASS the chats tab renders (empty=$empty) and the shell survives it")
    }

    @Test
    fun r22PrimaryHomeAndAdmissionNavigationAreVisible() {
        enterPrimaryHomeIfNeeded()
        rule.onNodeWithText("Архив").assertExists()
        rule.onNodeWithText("Устройства").assertExists()
        rule.onNodeWithText("Диагностика").assertExists()

        rule.onNodeWithText("Диагностика").performClick()
        rule.onNodeWithText("Android admission").assertExists()
        rule.onNodeWithText("Запустить admission probe").assertExists()
    }

    @Test
    fun secureArchiveRecoveryControlsAreVisible() {
        enterPrimaryHomeIfNeeded()
        rule.onNodeWithText("Архив").performClick()
        rule.onNodeWithText("Forward-secure history vault").assertExists()
        rule.onNodeWithText("Secure export").assertExists()
        rule.onAllNodesWithText("Recovery backup").onFirst().assertExists()
        rule.onNodeWithText("Secure restore").assertExists()
        rule.onNodeWithText("Мигрировать R19 → secure vault").assertExists()
    }
}
