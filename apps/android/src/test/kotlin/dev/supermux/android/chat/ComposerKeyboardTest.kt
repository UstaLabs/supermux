package dev.supermux.android.chat

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the composer Enter policy: soft IME → newline only; physical keyboard
 * Enter → send. Mirrors iOS ComposerKeyboardTests.
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

    @Test fun softImeFlagsAreNotPhysical() {
        assertFalse(
            isPhysicalKeyboardSource(
                deviceId = 0,
                flags = 0,
                isVirtualDevice = null,
                sources = null,
            ),
        )
        assertFalse(
            isPhysicalKeyboardSource(
                deviceId = -1,
                flags = 0,
                isVirtualDevice = null,
                sources = null,
            ),
        )
        assertFalse(
            isPhysicalKeyboardSource(
                deviceId = 5,
                flags = AndroidKeyEvent.FLAG_SOFT_KEYBOARD,
                isVirtualDevice = false,
                sources = InputDevice.SOURCE_KEYBOARD,
            ),
        )
        assertFalse(
            isPhysicalKeyboardSource(
                deviceId = 5,
                flags = AndroidKeyEvent.FLAG_VIRTUAL_HARD_KEY,
                isVirtualDevice = false,
                sources = InputDevice.SOURCE_KEYBOARD,
            ),
        )
        assertFalse(
            isPhysicalKeyboardSource(
                deviceId = 5,
                flags = 0,
                isVirtualDevice = true,
                sources = InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    @Test fun realKeyboardDeviceIsPhysical() {
        assertTrue(
            isPhysicalKeyboardSource(
                deviceId = 3,
                flags = 0,
                isVirtualDevice = false,
                sources = InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    @Test fun nonKeyboardSourceIsNotPhysical() {
        assertFalse(
            isPhysicalKeyboardSource(
                deviceId = 3,
                flags = 0,
                isVirtualDevice = false,
                sources = InputDevice.SOURCE_TOUCHSCREEN,
            ),
        )
    }
}
