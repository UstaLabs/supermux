package dev.supermux.android.platform

import android.content.res.Configuration
import android.view.InputDevice
import dev.supermux.ui.adaptive.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // ── which devices count as a pointer ──────────────────────────────────────────────────────

    @Test
    fun anExternalMouseOrTouchpadIsAPointer() {
        assertTrue(isPointerDevice(InputDevice.SOURCE_MOUSE, external = true, virtual = false))
        assertTrue(isPointerDevice(InputDevice.SOURCE_TOUCHPAD, external = true, virtual = false))
        assertTrue(
            isPointerDevice(
                InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE,
                external = true,
                virtual = false,
            ),
        )
    }

    @Test
    fun aBuiltInTouchpadIsNotAPointer() {
        // Samsung's `sec_touchpad` on every Fold/Flip: internal, non-virtual, KEYBOARD|MOUSE|TOUCHPAD.
        val secTouchpad =
            InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHPAD
        assertFalse(isPointerDevice(secTouchpad, external = false, virtual = false))
    }

    @Test
    fun aVirtualOrPointerlessDeviceIsNotAPointer() {
        assertFalse(isPointerDevice(InputDevice.SOURCE_MOUSE, external = true, virtual = true))
        assertFalse(isPointerDevice(InputDevice.SOURCE_TOUCHSCREEN, external = true, virtual = false))
        assertFalse(isPointerDevice(InputDevice.SOURCE_KEYBOARD, external = true, virtual = false))
    }

    @Test
    fun pointerAvailabilityIsAnExternalPointerOrAChromebookOrADesk() {
        assertFalse(pointerAvailableFor(externalPointer = false, chromebook = false, deskUiMode = false))
        assertTrue(pointerAvailableFor(externalPointer = true, chromebook = false, deskUiMode = false))
        assertTrue(pointerAvailableFor(externalPointer = false, chromebook = true, deskUiMode = false))
        assertTrue(pointerAvailableFor(externalPointer = false, chromebook = false, deskUiMode = true))
    }

    @Test
    fun deskUiModeIsReadOffTheConfiguration() {
        assertTrue(isDeskUiMode(Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_NO))
        assertFalse(isDeskUiMode(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES))
    }
}
