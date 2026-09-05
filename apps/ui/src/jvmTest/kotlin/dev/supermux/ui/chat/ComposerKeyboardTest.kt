package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure tests for the composer Enter policy: soft IME / touch → newline only; physical keyboard
 * (== `LocalInputMode.Pointer`) Enter → send. Mirrors iOS ComposerKeyboardTests.
 */
class ComposerKeyboardTest {

    @Test fun softKeyboardEnterDoesNotSend() {
        assertFalse(
            shouldComposerSendOnEnter(
                isEnterKey = true,
                shiftPressed = false,
                fromPhysicalKeyboard = false,
            ),
        )
    }

    @Test fun physicalEnterSends() {
        assertTrue(
            shouldComposerSendOnEnter(
                isEnterKey = true,
                shiftPressed = false,
                fromPhysicalKeyboard = true,
            ),
        )
    }

    @Test fun physicalShiftEnterDoesNotSend() {
        assertFalse(
            shouldComposerSendOnEnter(
                isEnterKey = true,
                shiftPressed = true,
                fromPhysicalKeyboard = true,
            ),
        )
    }

    @Test fun nonEnterNeverSends() {
        assertFalse(
            shouldComposerSendOnEnter(
                isEnterKey = false,
                shiftPressed = false,
                fromPhysicalKeyboard = true,
            ),
        )
    }
}
