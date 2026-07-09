package dev.supermux.desktop.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI + logic contract for [DesktopComposer]. Written to verify the composer WITHOUT xdotool: the
 * desktop Compose test harness ([runComposeUiTest]) drives typing, clicks, and key injection in
 * process. The Enter/Shift+Enter decision is ALSO covered as a pure function ([isComposerSendKey])
 * so the send-on-Enter contract holds even if the harness's key routing ever regresses.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopComposerTest {

    // ── (b') pure Enter-key contract — harness-independent ──────────────────────
    @Test fun sendKeyPredicate_enterDownNoShift_sends() {
        assertTrue(isComposerSendKey(Key.Enter, KeyEventType.KeyDown, shiftPressed = false))
        assertTrue(isComposerSendKey(Key.NumPadEnter, KeyEventType.KeyDown, shiftPressed = false))
    }

    @Test fun sendKeyPredicate_shiftEnter_isNewlineNotSend() {
        assertTrue(!isComposerSendKey(Key.Enter, KeyEventType.KeyDown, shiftPressed = true))
    }

    @Test fun sendKeyPredicate_keyUpAndOtherKeys_dontSend() {
        assertTrue(!isComposerSendKey(Key.Enter, KeyEventType.KeyUp, shiftPressed = false))
        assertTrue(!isComposerSendKey(Key.A, KeyEventType.KeyDown, shiftPressed = false))
    }

    // ── (a) typing + click Send fires trimmed text and clears via callback ──────
    @Test fun typingThenClickSend_firesTrimmed_andClears() = runComposeUiTest {
        var draft by mutableStateOf("")
        var sent: String? = null
        setContent {
            DesktopComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { sent = it; draft = "" },
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-input").performTextInput("  hello world  ")
        onNodeWithTag("composer-send").performClick()
        assertEquals("hello world", sent)   // trimmed
        assertEquals("", draft)             // cleared through onSend callback
    }

    // ── (b) Enter sends; Shift+Enter does NOT (in-process key injection) ────────
    @Test fun enterKey_sends_shiftEnter_doesNot() = runComposeUiTest {
        var draft by mutableStateOf("")
        var sendCount = 0
        setContent {
            DesktopComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { sendCount++; draft = "" },
                onInterrupt = {},
            )
        }
        // Shift+Enter — must NOT send (newline).
        onNodeWithTag("composer-input").performTextInput("keep")
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
        }
        assertEquals(0, sendCount)

        // Plain Enter — sends.
        onNodeWithTag("composer-input").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, sendCount)
    }

    // ── (c) Send disabled when blank or while sending ───────────────────────────
    @Test fun sendDisabled_whenBlank() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = {},
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    @Test fun sendDisabled_whileSending_evenWithText() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "ready",
                onDraftChange = {},
                sending = true,
                agentWorking = false,
                onSend = {},
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    @Test fun sendEnabled_whenNonBlankAndNotSending() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "ready",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = {},
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    // ── (d) Stop shown while agentWorking + fires onInterrupt ───────────────────
    @Test fun stopShown_whileAgentWorking_firesInterrupt() = runComposeUiTest {
        var interrupted = false
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = true,
                onSend = {},
                onInterrupt = { interrupted = true },
            )
        }
        onNodeWithTag("composer-stop").performClick()
        assertTrue(interrupted)
    }
}
