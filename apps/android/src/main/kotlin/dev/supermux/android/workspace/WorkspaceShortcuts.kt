package dev.supermux.android.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Hardware-keyboard actions for the wide workspace (all gated behind Ctrl or Cmd/Meta). */
enum class WorkspaceShortcut {
    ToggleSidebar, NewSession, ToggleChat, ToggleEditor, ToggleTerminal, ToggleDisplay,
}

/**
 * Pure key→action mapping (Android-independent, keyed on the letter so it is unit-testable off the
 * device). [hasSelection] gates the per-session pane toggles — with no selected session only the
 * global B/N shortcuts resolve. Returns null when the letter is not a bound shortcut.
 */
fun mapWorkspaceShortcut(letter: Char, hasSelection: Boolean): WorkspaceShortcut? =
    when (letter.uppercaseChar()) {
        'B' -> WorkspaceShortcut.ToggleSidebar
        'N' -> WorkspaceShortcut.NewSession
        'L' -> WorkspaceShortcut.ToggleChat.takeIf { hasSelection }
        'E' -> WorkspaceShortcut.ToggleEditor.takeIf { hasSelection }
        'T' -> WorkspaceShortcut.ToggleTerminal.takeIf { hasSelection }
        'D' -> WorkspaceShortcut.ToggleDisplay.takeIf { hasSelection }
        else -> null
    }

/** Runs a resolved [shortcut] against the shared [layout] / [selectedId] / [onNewSession]. */
fun applyWorkspaceShortcut(
    shortcut: WorkspaceShortcut,
    layout: WorkspaceLayout,
    selectedId: String?,
    onNewSession: () -> Unit,
) {
    when (shortcut) {
        WorkspaceShortcut.ToggleSidebar -> layout.sidebarCollapsed = !layout.sidebarCollapsed
        WorkspaceShortcut.NewSession -> onNewSession()
        WorkspaceShortcut.ToggleChat -> selectedId?.let { layout.toggleChat(it) }
        WorkspaceShortcut.ToggleEditor -> selectedId?.let { layout.toggleEditor(it) }
        WorkspaceShortcut.ToggleTerminal -> selectedId?.let { layout.toggleTerminal(it) }
        WorkspaceShortcut.ToggleDisplay -> selectedId?.let { layout.toggleDisplay(it) }
    }
}

/** The bound Compose [Key]s → their logical letter; anything else is not a shortcut. */
private fun Key.shortcutLetter(): Char? = when (this) {
    Key.B -> 'B'
    Key.N -> 'N'
    Key.L -> 'L'
    Key.E -> 'E'
    Key.T -> 'T'
    Key.D -> 'D'
    else -> null
}

/**
 * Intercepts Ctrl/Cmd + {B,N,L,E,T,D} on key-down and drives the workspace [layout]. Uses
 * `onPreviewKeyEvent` so it sees events before focused descendants (text fields, terminal), and
 * returns true ONLY for a handled combo — every other key propagates so normal typing is untouched.
 */
fun Modifier.workspaceShortcuts(
    layout: WorkspaceLayout,
    selectedId: String?,
    onNewSession: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
    val letter = event.key.shortcutLetter() ?: return@onPreviewKeyEvent false
    val shortcut = mapWorkspaceShortcut(letter, hasSelection = selectedId != null)
        ?: return@onPreviewKeyEvent false
    applyWorkspaceShortcut(shortcut, layout, selectedId, onNewSession)
    true
}
