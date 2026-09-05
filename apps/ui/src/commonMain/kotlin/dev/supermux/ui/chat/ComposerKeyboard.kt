package dev.supermux.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Composer Enter policy (iOS `ComposerKeyboard` / web `enterSends` parity), shared by the chat
 * composer and the new-session launcher on every host:
 *  - Soft (virtual) keyboard Return → insert a newline only, never send.
 *  - Physical keyboard Enter → send; Shift+Enter → newline.
 *
 * Desktop's old `isComposerSendKey` was exactly the `fromPhysicalKeyboard = true` case of this, so
 * the two rules are one rule now: the shared composer passes
 * `fromPhysicalKeyboard = (LocalInputMode.current == InputMode.Pointer)`, which is always true on
 * desktop and true on a phone only when a keyboard/mouse is attached (DeX, Chromebook, docked
 * tablet). Android's `isFromPhysicalKeyboard()` device heuristic still exists in the app module for
 * the surfaces that inspect the native event directly.
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
