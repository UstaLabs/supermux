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
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.retain.rememberRetained
import dev.supermux.ui.worktree.WorktreeCleanupSection
import dev.supermux.ui.worktree.WorktreeCleanupTick
import dev.supermux.workspace.viewTitle
import dev.supermux.ui.i18n.localized

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
    worktreeSize: suspend (worktreeId: String) -> Long? = { null },
    /** Close, then delete exactly the worktree ids the section displayed and the user confirmed. */
    onConfirmDeletingWorktree: ((worktreeIds: List<String>) -> Unit)? = null,
    /**
     * Names this asking, so its "also delete worktree" tick survives a rotation or a width change
     * (#89); the section reports it again, with the ids it finds, once it has reloaded. Null keeps
     * the tick in this composition.
     */
    retainKey: String? = null,
) {
    var deleteWt by remember(view.id) { mutableStateOf(false) }
    var deleteIds by remember(view.id) { mutableStateOf(emptyList<String>()) }
    val tick = if (retainKey != null) rememberRetained(retainKey) { WorktreeCleanupTick() } else null
    val sessionId = view.chatSessionId()
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Text(localizedCloseConfirmText(view, sessionId?.let { sessionNames[it] }))
                if (view.kind == "chat" && chatWorkdir != null && worktreeForWorkdir != null) {
                    WorktreeCleanupSection(
                        listOf(chatWorkdir), setOfNotNull(sessionId), worktreeForWorkdir,
                        onDeleteChange = { c, ids -> deleteWt = c; deleteIds = ids },
                        size = worktreeSize,
                        tick = tick,
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
            }) { Text(localized(if (deleteWt) Str.shell_close_and_delete else Str.shell_close)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(Str.shell_cancel)) } },
    )
}

/** [closeConfirmText] in the app's language: what the dialog shows. */
@Composable
fun localizedCloseConfirmText(view: ViewDto, sessionName: String?): String = when (view.kind) {
    "chat" -> localized(Str.shell_close_chat_confirm, sessionName?.takeIf { it.isNotBlank() } ?: viewTitle(view))
    "terminal" -> localized(Str.shell_close_terminal_confirm, view.stateString("terminalId") ?: "?")
    "display" -> localized(Str.shell_close_display_confirm)
    else -> localized(Str.shell_close_view_confirm)
}

/**
 * The one question, naming what the close stops. English, the tests' twin of [localizedCloseConfirmText].
 *
 * Desktop's wording throughout (it names the terminal being killed, which Android's "This kills
 * it." did not); [sessionName] is the chat's session name where the caller could resolve one.
 */
fun closeConfirmText(view: ViewDto, sessionName: String?): String = when (view.kind) {
    // Android's fallback (the view's own title) rather than desktop's "this session" — it names
    // something the user can see on the tab.
    "chat" -> "Close this chat? This archives the session ${sessionName?.takeIf { it.isNotBlank() } ?: viewTitle(view)}." // i18n-ok: test twin
    "terminal" -> "Close this terminal? This stops the terminal ${view.stateString("terminalId") ?: "?"}." // i18n-ok: test twin
    "display" -> "Close this display? This stops the stream." // i18n-ok: test twin
    else -> "Close this view?" // i18n-ok: test twin
}

/**
 * True for the kinds whose close ends real work. An editor close is silent. A Browser tab's close is
 * silent too: its page is a link, and closing it stops nothing.
 *
 * Desktop's rule (`kind != "editor"`) is the union: Android listed chat/terminal/display and let
 * an UNKNOWN kind close without a question, which is the wrong way round for a kind this client
 * does not understand yet.
 */
fun closeNeedsConfirmation(kind: String): Boolean = kind != "editor" && kind != "browser" && kind != NEW_TAB_KIND

fun ViewDto.closeNeedsConfirmation(): Boolean = closeNeedsConfirmation(kind)
