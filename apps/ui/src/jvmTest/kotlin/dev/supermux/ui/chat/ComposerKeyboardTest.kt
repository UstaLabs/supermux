package dev.supermux.ui.chat

import androidx.compose.ui.input.key.KeyEvent
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

    // The JVM actual of the per-event probe: desktop has no soft keyboard, so an Enter that reaches
    // the composer is always a real one and always sends.
    @Test fun onDesktopEveryKeyEventIsPhysical() {
        assertTrue(
            shouldComposerSendOnEnter(
                isEnterKey = true,
                shiftPressed = false,
                fromPhysicalKeyboard = KeyEvent(
                    java.awt.event.KeyEvent(
                        java.awt.Label(), java.awt.event.KeyEvent.KEY_PRESSED, 0L, 0,
                        java.awt.event.KeyEvent.VK_ENTER, '\n',
                    ),
                ).isFromPhysicalKeyboard(),
            ),
        )
    }
}
