package dev.supermux.android.platform

import android.content.res.Configuration
import dev.supermux.ui.adaptive.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure decision function behind Android's `LocalInputMode`: a phone/tablet is Touch unless a
 * hardware keyboard or a mouse/touchpad is attached (DeX, Chromebook, docked tablet).
 */
class InputModeDetectorTest {
    @Test
    fun plainTouchDeviceIsTouch() {
        assertEquals(
            InputMode.Touch,
            inputModeFor(Configuration.KEYBOARD_NOKEYS, hasPointerDevice = false),
        )
        assertEquals(
            InputMode.Touch,
            inputModeFor(Configuration.KEYBOARD_UNDEFINED, hasPointerDevice = false),
        )
    }

    @Test
    fun hardwareKeyboardIsPointer() {
        assertEquals(
            InputMode.Pointer,
            inputModeFor(Configuration.KEYBOARD_QWERTY, hasPointerDevice = false),
        )
    }

    @Test
    fun mouseOrTouchpadIsPointer() {
        assertEquals(
            InputMode.Pointer,
            inputModeFor(Configuration.KEYBOARD_NOKEYS, hasPointerDevice = true),
        )
    }

    @Test
    fun twelveKeyKeypadIsStillTouch() {
        // KEYBOARD_12KEY is a dialpad, not a desktop-class keyboard.
        assertEquals(
            InputMode.Touch,
            inputModeFor(Configuration.KEYBOARD_12KEY, hasPointerDevice = false),
        )
    }
}
