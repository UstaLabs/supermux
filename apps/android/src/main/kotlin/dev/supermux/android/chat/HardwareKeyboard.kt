package dev.supermux.android.chat

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import dev.supermux.ui.chat.isComposerEnterKey
import dev.supermux.ui.chat.shouldComposerSendOnEnter

/**
 * "This KeyEvent came from a real keyboard, not the soft IME" — Android's native heuristic behind
 * the shared Enter policy ([shouldComposerSendOnEnter], in `:ui`).
 *
 * The shared chat composer does NOT use this: it asks `LocalInputMode` instead, which is the same
 * question answered once per window by `InputModeDetector` rather than per key event. This copy
 * stays for the Android-only surfaces that still inspect `nativeKeyEvent` directly (the new-session
 * launcher), and for the device-level tests that pin the flag/source matrix.
 */

/**
 * Soft keyboards commonly use deviceId 0/-1 and/or FLAG_SOFT_KEYBOARD; physical USB/BT/DeX
 * keyboards use a non-virtual InputDevice with SOURCE_KEYBOARD.
 */
fun isPhysicalKeyboardSource(
    deviceId: Int,
    flags: Int,
    isVirtualDevice: Boolean?,
    sources: Int?,
): Boolean {
    if (flags and AndroidKeyEvent.FLAG_SOFT_KEYBOARD != 0) return false
    if (flags and AndroidKeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) return false
    if (deviceId <= 0) return false
    if (isVirtualDevice == true) return false
    // API 36 android.jar no longer exposes SOURCE_CLASS_KEYBOARD; SOURCE_KEYBOARD is the
    // concrete keyboard source (includes the class bits). Soft IMEs typically don't set it.
    if (sources != null && (sources and InputDevice.SOURCE_KEYBOARD) == 0) return false
    return true
}

/** Compose [KeyEvent] originated from a physical/hardware keyboard (not the soft IME). */
fun KeyEvent.isFromPhysicalKeyboard(): Boolean {
    // On Android, KeyEvent is a value class wrapping android.view.KeyEvent as nativeKeyEvent.
    val native: AndroidKeyEvent = nativeKeyEvent
    val device = if (native.deviceId > 0) InputDevice.getDevice(native.deviceId) else null
    return isPhysicalKeyboardSource(
        deviceId = native.deviceId,
        flags = native.flags,
        isVirtualDevice = device?.isVirtual,
        sources = device?.sources,
    )
}

/**
 * True when this event should submit the composer (hardware Enter, no Shift).
 * Soft-IME Enter returns false so the field inserts a newline.
 */
fun KeyEvent.isComposerSendEnter(): Boolean =
    shouldComposerSendOnEnter(
        isEnterKey = isComposerEnterKey(),
        shiftPressed = isShiftPressed,
        fromPhysicalKeyboard = isFromPhysicalKeyboard(),
    )
