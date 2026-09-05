package dev.supermux.android.chat

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android's physical-vs-soft keyboard heuristic (the device/flag/source matrix). The pure Enter
 * policy it feeds now lives in `:ui` — see `ComposerKeyboardTest` there.
 */
class HardwareKeyboardTest {



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
