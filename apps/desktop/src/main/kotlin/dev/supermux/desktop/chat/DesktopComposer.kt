// The desktop chat composer — a lean, keyboard-first input, deliberately NOT a port of Android's
// upload/dictation-heavy ChatPanel composer (M1 desktop has no attachment/mic surface yet; those
// arrive with M4). One OutlinedTextField + a trailing Send/Stop icon. Enter sends, Shift+Enter
// inserts a newline — the same preview-phase key handling as OnboardingScreen.submitOnEnter, so
// hardware Enter never also drops a newline and a blank/sending draft lets the field keep the key.
package dev.supermux.desktop.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag

/**
 * Pure Enter-key predicate for the composer: `true` only for a KeyDown Enter / NumPad-Enter with
 * Shift NOT held — i.e. the "send" chord. Shift+Enter (newline), key-up, and every other key are
 * `false`. Extracted from [DesktopComposer]'s `onPreviewKeyEvent` so the send-on-Enter contract is
 * unit-testable as plain logic, independent of whether the desktop UI-test harness can inject key
 * events into a focused field.
 */
internal fun isComposerSendKey(key: Key, type: KeyEventType, shiftPressed: Boolean): Boolean =
    type == KeyEventType.KeyDown &&
        (key == Key.Enter || key == Key.NumPadEnter) &&
        !shiftPressed

/**
 * Lean desktop composer.
 *
 * @param draft current draft text (hoisted — per-session in [ChatPanel]/WorkspaceRoot).
 * @param sending true while the client-local "Sending…" marker is up (blocks re-send).
 * @param agentWorking true while the broker says the agent is busy — flips the trailing icon to
 *   Stop so the user can interrupt without leaving the composer.
 * @param onSend fired with the TRIMMED, non-empty draft; only while `!sending`.
 * @param onInterrupt fired by the Stop icon while [agentWorking].
 */
@Composable
fun DesktopComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    agentWorking: Boolean,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Send is allowed only for a non-blank draft that isn't already in flight.
    val canSend = draft.isNotBlank() && !sending
    val doSend = {
        val trimmed = draft.trim()
        if (trimmed.isNotEmpty() && !sending) onSend(trimmed)
    }

    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = modifier
            .fillMaxWidth()
            .testTag("composer-input")
            .onPreviewKeyEvent { e: KeyEvent ->
                if (isComposerSendKey(e.key, e.type, e.isShiftPressed)) {
                    // Consume ONLY when we actually send; a blank/sending draft falls through so
                    // the multiline field handles Enter itself (no stray newline, no double-send).
                    if (canSend) { doSend(); true } else false
                } else {
                    false
                }
            },
        placeholder = { Text("Message the agent…") },
        maxLines = 8,
        trailingIcon = {
            if (agentWorking) {
                IconButton(onClick = onInterrupt, modifier = Modifier.testTag("composer-stop")) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = doSend,
                    enabled = canSend,
                    modifier = Modifier.testTag("composer-send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        },
    )
}
