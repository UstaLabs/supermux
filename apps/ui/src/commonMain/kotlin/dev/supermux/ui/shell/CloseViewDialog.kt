package dev.supermux.ui.shell

import dev.supermux.ui.widgets.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.supermux.net.WorktreeForWorkdirDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.ui.worktree.WorktreeCleanupSection
import dev.supermux.workspace.viewTitle

/**
 * Spec §9.3 — a close ends the work behind the view, so the user is asked first.
 *
 * ONE question, TWO buttons. This is deliberately NOT the Finish flow: the user
 * was explicit that closing a chat settles only that view, with no Merge / Open
 * PR / Keep / Discard. The work tree and the branch stay on disk UNLESS the user
 * ticks "also delete worktree" (spec 2026-09-22-explicit-worktree-cleanup); Finish
 * stays available later from the archived row and the workspace menu.
 *
 * An editor view never reaches here — closing one stops nothing.
 */
@Composable
fun CloseViewDialog(
    view: ViewDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    sessionNames: Map<String, String> = emptyMap(),
    /** Chat views only: the session's workdir, and how to look up its worktree. */
    chatWorkdir: String? = null,
    worktreeForWorkdir: (suspend (String) -> WorktreeForWorkdirDto?)? = null,
    /** Close, then delete exactly the worktree ids the section displayed and the user confirmed. */
    onConfirmDeletingWorktree: ((worktreeIds: List<String>) -> Unit)? = null,
) {
    var deleteWt by remember(view.id) { mutableStateOf(false) }
    var deleteIds by remember(view.id) { mutableStateOf(emptyList<String>()) }
    val sessionId = view.chatSessionId()
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Text(closeConfirmText(view, sessionId?.let { sessionNames[it] }))
                if (view.kind == "chat" && chatWorkdir != null && worktreeForWorkdir != null) {
                    WorktreeCleanupSection(
                        listOf(chatWorkdir), setOfNotNull(sessionId), worktreeForWorkdir,
                        onDeleteChange = { c, ids -> deleteWt = c; deleteIds = ids },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (deleteWt && deleteIds.isNotEmpty() && onConfirmDeletingWorktree != null) {
                    onConfirmDeletingWorktree(deleteIds)
                } else {
                    onConfirm()
                }
            }) { Text(if (deleteWt) "Close & delete" else "Close") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The one question, naming what the close stops.
 *
 * Desktop's wording throughout (it names the terminal being killed, which Android's "This kills
 * it." did not); [sessionName] is the chat's session name where the caller could resolve one.
 */
fun closeConfirmText(view: ViewDto, sessionName: String?): String = when (view.kind) {
    // Android's fallback (the view's own title) rather than desktop's "this session" — it names
    // something the user can see on the tab.
    "chat" -> "Close this chat? This archives the session ${sessionName?.takeIf { it.isNotBlank() } ?: viewTitle(view)}."
    "terminal" -> "Close this terminal? This stops the terminal ${view.stateString("terminalId") ?: "?"}."
    "display" -> "Close this display? This stops the stream."
    else -> "Close this view?"
}

/**
 * True for the kinds whose close ends real work. An editor close is silent.
 *
 * Desktop's rule (`kind != "editor"`) is the union: Android listed chat/terminal/display and let
 * an UNKNOWN kind close without a question, which is the wrong way round for a kind this client
 * does not understand yet.
 */
fun closeNeedsConfirmation(kind: String): Boolean = kind != "editor"

fun ViewDto.closeNeedsConfirmation(): Boolean = closeNeedsConfirmation(kind)
