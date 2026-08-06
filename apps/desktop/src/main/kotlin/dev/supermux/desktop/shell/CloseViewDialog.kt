package dev.supermux.desktop.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString

/**
 * Spec §9.3 — a close ends the work behind the view, so the user is asked first.
 *
 * ONE question, TWO buttons. This is deliberately NOT the Finish flow: the user
 * was explicit that closing a chat settles only that view, with no Merge / Open
 * PR / Keep / Discard. The work tree and the branch stay on disk; Finish stays
 * available later from the archived row and the workspace menu.
 *
 * An editor view never reaches here — closing one stops nothing.
 */
@Composable
fun CloseViewDialog(
    view: ViewDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    sessionNames: Map<String, String> = emptyMap(),
) {
    val message = when (view.kind) {
        "chat" -> {
            val name = view.chatSessionId()?.let { sessionNames[it] } ?: "this session"
            "Close this chat? This archives the session $name."
        }
        "terminal" -> "Close this terminal? This stops the terminal ${view.stateString("terminalId") ?: "?"}."
        "display" -> "Close this display? This stops the stream."
        else -> "Close this view?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Close") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** True for the kinds whose close ends real work. An editor close is silent. */
fun ViewDto.closeNeedsConfirmation(): Boolean = kind != "editor"
