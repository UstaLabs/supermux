package dev.supermux.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Composer Enter policy (iOS `ComposerKeyboard` / web `enterSends` parity), shared by the chat
 * composer and the new-session launcher on every host:
 *  - Soft (virtual) keyboard Return → insert a newline only, never send.
 *  - Physical keyboard Enter → send; Shift+Enter → newline.
 *
 * Desktop's old `isComposerSendKey` was exactly the `fromPhysicalKeyboard = true` case of this, so
 * the two rules are one rule now. The flag is answered PER EVENT by [isFromPhysicalKeyboard], not
 * once per window: a phone with a Bluetooth keyboard attached is `LocalInputMode.Pointer`, but its
 * SOFT keyboard is still on screen and its Return must still insert a newline — deciding at the
 * window level would send on every soft Return the moment a keyboard is paired.
 */

/** Whether an Enter-class key-down without Shift should submit (send) rather than insert a newline. */
fun shouldComposerSendOnEnter(
    isEnterKey: Boolean,
    shiftPressed: Boolean,
    fromPhysicalKeyboard: Boolean,
): Boolean = isEnterKey && !shiftPressed && fromPhysicalKeyboard

/** Key-down Enter / NumPadEnter (Shift state ignored — use [shouldComposerSendOnEnter]). */
fun KeyEvent.isComposerEnterKey(): Boolean =
    type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter)

/**
 * Did THIS event come from a real keyboard rather than the on-screen IME?
 *
 * Android inspects the native event's device id, flags and sources (a soft IME uses device 0/-1 or
 * sets `FLAG_SOFT_KEYBOARD`); desktop has no soft keyboard at all, so every key event is physical.
 */
expect fun KeyEvent.isFromPhysicalKeyboard(): Boolean

/**
 * True when this event should submit the composer: an Enter-class key-down, no Shift, from a real
 * keyboard. A soft-IME Return returns false so the multiline field inserts a newline.
 */
fun KeyEvent.isComposerSendEnter(): Boolean =
    shouldComposerSendOnEnter(
        isEnterKey = isComposerEnterKey(),
        shiftPressed = isShiftPressed,
        fromPhysicalKeyboard = isFromPhysicalKeyboard(),
    )
