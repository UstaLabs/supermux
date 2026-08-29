package dev.supermux.android.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.supermux.workspace.NewViewKind

/** Hardware-keyboard actions for the wide workspace (all gated behind Ctrl or Cmd/Meta). */
enum class WorkspaceShortcut {
    ToggleSidebar, NewSession, ToggleChat, ToggleEditor, ToggleTerminal, ToggleDisplay,
}

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

fun applyWorkspaceShortcut(
    shortcut: WorkspaceShortcut,
    sidebar: SidebarState,
    selectedId: String?,
    onNewSession: () -> Unit,
    onAddKind: (NewViewKind) -> Unit = {},
) {
    when (shortcut) {
        WorkspaceShortcut.ToggleSidebar -> sidebar.sidebarCollapsed = !sidebar.sidebarCollapsed
        WorkspaceShortcut.NewSession -> onNewSession()
        WorkspaceShortcut.ToggleChat -> if (selectedId != null) onAddKind(NewViewKind.CHAT)
        WorkspaceShortcut.ToggleEditor -> if (selectedId != null) onAddKind(NewViewKind.EDITOR)
        WorkspaceShortcut.ToggleTerminal -> if (selectedId != null) onAddKind(NewViewKind.TERMINAL)
        WorkspaceShortcut.ToggleDisplay -> if (selectedId != null) onAddKind(NewViewKind.DISPLAY)
    }
}

private fun Key.shortcutLetter(): Char? = when (this) {
    Key.B -> 'B'
    Key.N -> 'N'
    Key.L -> 'L'
    Key.E -> 'E'
    Key.T -> 'T'
    Key.D -> 'D'
    else -> null
}

fun Modifier.workspaceShortcuts(
    sidebar: SidebarState,
    selectedId: String?,
    onNewSession: () -> Unit,
    onAddKind: (NewViewKind) -> Unit = {},
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    if (!event.isCtrlPressed && !event.isMetaPressed) return@onKeyEvent false
    val letter = event.key.shortcutLetter() ?: return@onKeyEvent false
    val shortcut = mapWorkspaceShortcut(letter, hasSelection = selectedId != null)
        ?: return@onKeyEvent false
    applyWorkspaceShortcut(shortcut, sidebar, selectedId, onNewSession, onAddKind)
    true
}
