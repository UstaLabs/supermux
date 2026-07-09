// Ported from apps/android/src/main/kotlin/dev/supermux/android/workspace/WorkspaceShortcuts.kt —
// keep in sync until a shared UI module exists. Pure androidx.compose.ui.input.key, available on
// desktop, so this is a verbatim copy of the Android original except for the package name.
//
// M3-T5 conflict check (Ctrl+E vs. the cm6 bundle's own Ctrl+/−/0 font-zoom): NO conflict, for two
// independent reasons —
//   1. Different keys entirely. Ctrl+E toggles the editor PANE (this file); the bundle's own zoom
//      binds Ctrl+Plus/Minus/0 (EDITOR_FONT_MIN..MAX, see EditorBridgeShims.kt) inside its own JS
//      keydown handler. No letter/symbol overlaps.
//   2. Even if they DID share a key, [Modifier.workspaceShortcuts] is attached to the outer Compose
//      window and only fires via `onKeyEvent`'s BUBBLE phase — i.e. only for chords a focused
//      Compose descendant left unhandled. The editor's KCEF surface is a HEAVYWEIGHT AWT child
//      (SwingPanel, see WebCodeEditor.kt/EditorSwingHost): once it has native AWT focus, key events
//      go straight to the embedded Chromium widget and never reach Compose's onKeyEvent dispatch at
//      all — so a chord typed while the CodeMirror surface is focused can't be "stolen" by
//      workspaceShortcuts, and vice versa a chord typed while a Compose control has focus (search
//      field, tree, etc.) never reaches the CEF-hosted bundle's own JS handler. The two shortcut
//      surfaces are focus-partitioned by construction, not by a specific-key coincidence.
package dev.supermux.desktop.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/** Hardware-keyboard actions for the wide workspace (all gated behind Ctrl or Cmd/Meta). */
enum class WorkspaceShortcut {
    ToggleSidebar, NewSession, ToggleChat, ToggleEditor, ToggleTerminal, ToggleDisplay,
}

/**
 * Pure key→action mapping (platform-independent, keyed on the letter so it is unit-testable off the
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
 * `onKeyEvent` (bubble phase) so focused descendants — the chat composer, the terminal — consume
 * their keys FIRST; only chords they leave unhandled reach the workspace, so terminal control keys
 * (Ctrl+D/L/E/T) aren't stolen. Returns true ONLY for a handled combo.
 */
fun Modifier.workspaceShortcuts(
    layout: WorkspaceLayout,
    selectedId: String?,
    onNewSession: () -> Unit,
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    if (!event.isCtrlPressed && !event.isMetaPressed) return@onKeyEvent false
    val letter = event.key.shortcutLetter() ?: return@onKeyEvent false
    val shortcut = mapWorkspaceShortcut(letter, hasSelection = selectedId != null)
        ?: return@onKeyEvent false
    applyWorkspaceShortcut(shortcut, layout, selectedId, onNewSession)
    true
}
