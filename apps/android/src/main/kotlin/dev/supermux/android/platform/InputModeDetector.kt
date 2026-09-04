package dev.supermux.android.platform

import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import dev.supermux.ui.adaptive.InputMode

/**
 * Pure decision: a desktop-class keyboard (`KEYBOARD_QWERTY`) or an attached mouse/touchpad puts
 * the device in [InputMode.Pointer] (DeX, Chromebook, docked tablet); everything else is
 * [InputMode.Touch]. `KEYBOARD_12KEY` is a dialpad, not a keyboard, so it stays Touch.
 */
fun inputModeFor(keyboard: Int, hasPointerDevice: Boolean): InputMode =
    if (keyboard == Configuration.KEYBOARD_QWERTY || hasPointerDevice) {
        InputMode.Pointer
    } else {
        InputMode.Touch
    }

/** True when any connected [InputDevice] reports a mouse or touchpad source. */
fun hasPointerDevice(): Boolean = InputDevice.getDeviceIds().any { id ->
    val sources = InputDevice.getDevice(id)?.sources ?: 0
    sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
        sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD
}

/**
 * Android's `LocalInputMode` value. Re-evaluated on every configuration change, so plugging a
 * keyboard/mouse into a dock (which re-emits the configuration) flips the mode live.
 */
@Composable
fun rememberInputMode(): InputMode {
    val configuration = LocalConfiguration.current
    return remember(configuration) { inputModeFor(configuration.keyboard, hasPointerDevice()) }
}
