package dev.supermux.android.chat

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Chat / launcher composer Enter policy (iOS [ComposerKeyboard] / web `enterSends` parity):
 * - Soft (virtual) keyboard Return → insert newline only (never send).
 * - Physical keyboard Enter → send; Shift+Enter → newline.
 *
 * Pure predicates below are unit-testable without Compose or a real InputDevice.
 */

/** Whether an Enter-class key-down without Shift should submit (send) rather than insert a newline. */
fun shouldComposerSendOnEnter(
    isEnterKey: Boolean,
    shiftPressed: Boolean,
    fromPhysicalKeyboard: Boolean,
): Boolean = isEnterKey && !shiftPressed && fromPhysicalKeyboard

/**
 * Heuristic for "this KeyEvent came from a real keyboard", not the soft IME.
 *
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
    if (sources != null && (sources and InputDevice.SOURCE_CLASS_KEYBOARD) == 0) return false
    return true
}

/** Compose [KeyEvent] originated from a physical/hardware keyboard (not the soft IME). */
fun KeyEvent.isFromPhysicalKeyboard(): Boolean {
    val native = nativeKeyEvent as? AndroidKeyEvent ?: return false
    val device = if (native.deviceId > 0) InputDevice.getDevice(native.deviceId) else null
    return isPhysicalKeyboardSource(
        deviceId = native.deviceId,
        flags = native.flags,
        isVirtualDevice = device?.isVirtual,
        sources = device?.sources,
    )
}

/** Key-down Enter / NumPadEnter (Shift state ignored — use [shouldComposerSendOnEnter]). */
fun KeyEvent.isComposerEnterKey(): Boolean =
    type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter)

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
