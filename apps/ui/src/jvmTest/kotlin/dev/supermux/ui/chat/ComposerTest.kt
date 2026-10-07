package dev.supermux.ui.chat

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
import dev.supermux.ui.adaptive.InputMode
import kotlin.test.assertTrue

/**
 * UI + logic contract for [Composer] under a POINTER host (the desktop shape — `setPlatformContent`
 * defaults to a pointer + Expanded window, so `LocalInputMode == Pointer` and hardware Enter sends).
 * Written to verify the composer WITHOUT xdotool: the Compose test harness ([runComposeUiTest])
 * drives typing, clicks, and key injection in process. The Enter/Shift+Enter decision is ALSO
 * covered as a pure function ([shouldComposerSendOnEnter]) so the send-on-Enter contract holds even
 * if the harness's key routing ever regresses.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerTest {

    // ── (b') pure Enter-key contract — harness-independent ──────────────────────
    // `isComposerSendKey` (desktop's old predicate) is the `fromPhysicalKeyboard = true` case of
    // the shared rule; the key-CLASS half is `isComposerEnterKey`, exercised through real events.
    private fun sendsOnEnter(key: Key, type: KeyEventType, shift: Boolean): Boolean =
        shouldComposerSendOnEnter(
            isEnterKey = type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter),
            shiftPressed = shift,
            fromPhysicalKeyboard = true,
        )

    @Test fun sendKeyPredicate_enterDownNoShift_sends() {
        assertTrue(sendsOnEnter(Key.Enter, KeyEventType.KeyDown, shift = false))
        assertTrue(sendsOnEnter(Key.NumPadEnter, KeyEventType.KeyDown, shift = false))
    }

    @Test fun sendKeyPredicate_shiftEnter_isNewlineNotSend() {
        assertTrue(!sendsOnEnter(Key.Enter, KeyEventType.KeyDown, shift = true))
    }

    @Test fun sendKeyPredicate_keyUpAndOtherKeys_dontSend() {
        assertTrue(!sendsOnEnter(Key.Enter, KeyEventType.KeyUp, shift = false))
        assertTrue(!sendsOnEnter(Key.A, KeyEventType.KeyDown, shift = false))
    }

    // The soft-Return-inserts-a-newline half of the policy cannot be exercised here: the JVM actual
    // of `isFromPhysicalKeyboard()` is "always true" BY DESIGN (desktop has no on-screen keyboard),
    // so every injected Enter is a real one. That half is pinned where the seam actually branches —
    // `HardwareKeyboardTest` over the Android actual — plus `ComposerKeyboardTest`'s pure policy.

    // ── (a) typing + click Send fires trimmed text and clears via callback ──────
    @Test fun typingThenClickSend_firesTrimmed_andClears() = runComposeUiTest {
        var draft by mutableStateOf("")
        var sent: String? = null
        setPlatformContent {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { s, _ -> sent = s; draft = "" },
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
        setPlatformContent {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> sendCount++; draft = "" },
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
        setPlatformContent {
            Composer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    @Test fun sendDisabled_whileSending_evenWithText() = runComposeUiTest {
        setPlatformContent {
            Composer(
                draft = "ready",
                onDraftChange = {},
                sending = true,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    @Test fun sendEnabled_whenNonBlankAndNotSending() = runComposeUiTest {
        setPlatformContent {
            Composer(
                draft = "ready",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
            )
        }
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    // ── (d) Stop shown while agentWorking + fires onInterrupt ───────────────────
    @Test fun stopShown_whileAgentWorking_firesInterrupt() = runComposeUiTest {
        var interrupted = false
        setPlatformContent {
            Composer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = true,
                onSend = { _, _ -> },
                onInterrupt = { interrupted = true },
            )
        }
        onNodeWithTag("composer-stop").performClick()
        assertTrue(interrupted)
    }
}
