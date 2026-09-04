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

/**
 * True when any connected [InputDevice] reports a mouse or touchpad source. The device list is a
 * cross-process query, so a failure degrades to "no pointer" (Touch) rather than taking the app
 * down at composition time.
 */
fun hasPointerDevice(): Boolean = runCatching {
    InputDevice.getDeviceIds().any { id ->
        val sources = InputDevice.getDevice(id)?.sources ?: 0
        sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
            sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD
    }
}.getOrDefault(false)

/**
 * Android's `LocalInputMode` value, re-evaluated on every configuration change. Docking (which
 * re-emits the configuration) flips the mode live; hot-plugging a Bluetooth mouse on its own does
 * NOT emit a configuration change, so that case is only picked up at the next recomposition
 * triggered by something else — good enough while the mode drives affordances, not layout.
 * Wire an `InputManager.InputDeviceListener` here if a screen ever needs it to be exact.
 */
@Composable
fun rememberInputMode(): InputMode {
    val configuration = LocalConfiguration.current
    return remember(configuration) { inputModeFor(configuration.keyboard, hasPointerDevice()) }
}

/**
 * Android's `LocalPointerAvailable` value: a mouse or touchpad ONLY — the keyboard deliberately does
 * not count, because it does not make a 28dp hit target reachable. Re-evaluated on every
 * configuration change, with the same hot-plug caveat as [rememberInputMode].
 */
@Composable
fun rememberPointerAvailable(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration) { hasPointerDevice() }
}
