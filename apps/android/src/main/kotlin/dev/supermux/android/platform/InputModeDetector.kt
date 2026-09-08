package dev.supermux.android.platform

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
 * Pure decision for one [InputDevice]: a pointer is an EXTERNAL, non-virtual device with a mouse or
 * touchpad source. Built-in ones do not count: every Samsung Fold/Flip ships `sec_touchpad`, the
 * phone's own touch controller advertising `KEYBOARD | MOUSE | TOUCHPAD` for the cover-screen /
 * DeX touchpad mode. Counting it put the unfolded Fold in [InputMode.Pointer] — drag-to-reorder
 * fired on a plain scroll, and the chat took desktop's layout, which never pads for a soft
 * keyboard. A mouse plugged into DeX or a dock is external, so it still counts.
 */
fun isPointerDevice(sources: Int, external: Boolean, virtual: Boolean): Boolean =
    external && !virtual && (
        sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
            sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD
        )

/**
 * True when any connected [InputDevice] passes [isPointerDevice]. The device list is a
 * cross-process query, so a failure degrades to "no pointer" (Touch) rather than taking the app
 * down at composition time. Before API 29 there is no `isExternal`, so every device is taken as
 * external there (the pre-fix behaviour).
 */
fun hasPointerDevice(): Boolean = runCatching {
    InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        val external = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
        isPointerDevice(device.sources, external = external, virtual = device.isVirtual)
    }
}.getOrDefault(false)

/**
 * Pure decision behind `LocalPointerAvailable`: a real pointer is an external mouse/touchpad
 * ([isPointerDevice]), a Chromebook (its built-in trackpad is reported as internal, so the ARC
 * system feature stands in for it), or a desk-class UI mode (a docked / DeX-style desktop, where
 * the phone's own screen can be the touchpad and is, again, internal).
 */
fun pointerAvailableFor(externalPointer: Boolean, chromebook: Boolean, deskUiMode: Boolean): Boolean =
    externalPointer || chromebook || deskUiMode

/** `org.chromium.arc` is the feature every Android-on-ChromeOS runtime declares. */
fun isChromebook(context: Context): Boolean = runCatching {
    val pm = context.packageManager
    pm.hasSystemFeature("org.chromium.arc") || pm.hasSystemFeature("org.chromium.arc.device_management")
}.getOrDefault(false)

/** `Configuration.uiMode` carries a desk-class type on a docked / DeX-style desktop. */
fun isDeskUiMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK

/**
 * One evaluation of pointer availability per configuration: the device list is a cross-process
 * query, so both locals below share this instead of each asking again.
 */
@Composable
private fun rememberPointerProbe(): Boolean {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current.applicationContext
    return remember(configuration) {
        pointerAvailableFor(
            externalPointer = hasPointerDevice(),
            chromebook = isChromebook(context),
            deskUiMode = isDeskUiMode(configuration.uiMode),
        )
    }
}

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
    val pointer = rememberPointerProbe()
    return remember(configuration, pointer) { inputModeFor(configuration.keyboard, pointer) }
}

/**
 * Android's `LocalPointerAvailable` value: a mouse or touchpad ONLY — the keyboard deliberately does
 * not count, because it does not make a 28dp hit target reachable. Re-evaluated on every
 * configuration change, with the same hot-plug caveat as [rememberInputMode].
 */
@Composable
fun rememberPointerAvailable(): Boolean = rememberPointerProbe()
