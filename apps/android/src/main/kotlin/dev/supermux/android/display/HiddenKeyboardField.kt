package dev.supermux.android.display

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * A near-zero-size [BasicTextField] that raises the soft keyboard over a Display
 * surface and forwards keystrokes as deltas — the Compose analog of iOS
 * `DisplayKeyboardField` (a zero-size UITextField first responder). It never retains
 * text: each committed character is forwarded via [onChar] and the buffer is reset to
 * a zero-width sentinel so the next Backspace (which deletes the sentinel) is
 * detectable even on an "empty" field. Hardware/soft special keys (arrows / Esc /
 * Backspace / Enter / Tab) flow through [onPreviewKeyEvent] → [onSpecial].
 *
 * Parameterized by [onChar]/[onSpecial] so BOTH transports share it (VNC emits RFB
 * keysyms; scrcpy emits text/key JSON).
 */
private const val SENTINEL = "​" // zero-width space

@Composable
fun HiddenKeyboardField(
    focusRequester: FocusRequester,
    enabled: Boolean,
    onChar: (Char) -> Unit,
    onSpecial: (VncInput.SpecialKey) -> Unit = {},
) {
    val anchor = remember { TextFieldValue(SENTINEL, TextRange(SENTINEL.length)) }
    var value by remember { mutableStateOf(anchor) }

    // Request/clear focus when the keyboard toggle flips.
    LaunchedEffect(enabled) {
        if (enabled) runCatching { focusRequester.requestFocus() }
    }

    BasicTextField(
        value = value,
        onValueChange = { new ->
            val text = new.text
            when {
                // Sentinel deleted (Backspace on the otherwise-empty buffer).
                text.length < SENTINEL.length -> onSpecial(VncInput.SpecialKey.BACKSPACE)
                // One or more characters typed beyond the sentinel — forward each.
                text.length > SENTINEL.length -> {
                    val typed = text.removePrefix(SENTINEL)
                    for (ch in typed) {
                        if (ch == '\n' || ch == '\r') onSpecial(VncInput.SpecialKey.ENTER) else onChar(ch)
                    }
                }
                else -> {}
            }
            // Always reset to the sentinel — it's an input tap, not a buffer.
            value = anchor
        },
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { ev -> handleSpecialKey(ev, onSpecial) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.None,
        ),
        keyboardActions = KeyboardActions(),
    )
}

/** Map a hardware/soft KeyEvent to a [VncInput.SpecialKey]; returns true if consumed. */
private fun handleSpecialKey(ev: KeyEvent, onSpecial: (VncInput.SpecialKey) -> Unit): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val special = when (ev.key) {
        Key.DirectionLeft -> VncInput.SpecialKey.ARROW_LEFT
        Key.DirectionUp -> VncInput.SpecialKey.ARROW_UP
        Key.DirectionRight -> VncInput.SpecialKey.ARROW_RIGHT
        Key.DirectionDown -> VncInput.SpecialKey.ARROW_DOWN
        Key.Escape -> VncInput.SpecialKey.ESCAPE
        Key.Tab -> VncInput.SpecialKey.TAB
        Key.Enter, Key.NumPadEnter -> VncInput.SpecialKey.ENTER
        Key.Backspace -> VncInput.SpecialKey.BACKSPACE
        else -> return false
    }
    onSpecial(special)
    return true
}
