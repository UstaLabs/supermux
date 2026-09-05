package dev.supermux.ui.chat

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent

/**
 * Soft keyboards commonly use deviceId 0/-1 and/or `FLAG_SOFT_KEYBOARD`; physical USB/BT/DeX
 * keyboards use a non-virtual `InputDevice` with `SOURCE_KEYBOARD`.
 *
 * Pure so the flag/source matrix is unit-testable without a real `InputDevice`.
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
    // API 36 android.jar no longer exposes SOURCE_CLASS_KEYBOARD; SOURCE_KEYBOARD is the concrete
    // keyboard source (it includes the class bits). Soft IMEs typically don't set it.
    if (sources != null && (sources and InputDevice.SOURCE_KEYBOARD) == 0) return false
    return true
}

/** Compose [KeyEvent] originated from a physical/hardware keyboard (not the soft IME). */
actual fun KeyEvent.isFromPhysicalKeyboard(): Boolean {
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
