// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/ShellShortcuts.kt —
// keep in sync until a shared UI module exists. Pure androidx.compose.ui.input.key, available on
// desktop, so this is a verbatim copy of the Android original except for the package name.
//
// M3-T5 conflict check (Ctrl+E vs. the cm6 bundle's own Ctrl+/−/0 font-zoom): NO conflict, for two
// independent reasons —
//   1. Different keys entirely. Ctrl+E toggles the editor PANE (this file); the bundle's own zoom
//      binds Ctrl+Plus/Minus/0 (EDITOR_FONT_MIN..MAX, see EditorBridgeShims.kt) inside its own JS
//      keydown handler. No letter/symbol overlaps.
//   2. Even if they DID share a key, [Modifier.shellShortcuts] is attached to the outer Compose
//      window and only fires via `onKeyEvent`'s BUBBLE phase — i.e. only for chords a focused
//      Compose descendant left unhandled. The editor's JCEF surface is a HEAVYWEIGHT AWT child
//      (SwingPanel, see WebCodeEditor.kt/EditorSwingHost): once it has native AWT focus, key events
//      go straight to the embedded Chromium widget and never reach Compose's onKeyEvent dispatch at
//      all — so a chord typed while the CodeMirror surface is focused can't be "stolen" by
//      shellShortcuts, and vice versa a chord typed while a Compose control has focus (search
//      field, tree, etc.) never reaches the CEF-hosted bundle's own JS handler. The two shortcut
//      surfaces are focus-partitioned by construction, not by a specific-key coincidence.
package dev.supermux.desktop.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Hardware-keyboard actions for the wide shell (all gated behind Ctrl or Cmd/Meta).
 *
 * Ctrl/Cmd + L/E/T/D used to toggle the old shell's four fixed panes. Those panes are gone: a
 * workspace's panes are created, split, tabbed and closed through its own layout tree, and there
 * is no fixed set of four to flip. The chords are unbound rather than rebound to something
 * approximate — see mapShellShortcut.
 */
enum class ShellShortcut {
    ToggleSidebar, NewSession,
}

/**
 * Pure key→action mapping (platform-independent, keyed on the letter so it is unit-testable off the
 * device). Returns null when the letter is not a bound shortcut.
 */
fun mapShellShortcut(letter: Char): ShellShortcut? =
    when (letter.uppercaseChar()) {
        'B' -> ShellShortcut.ToggleSidebar
        'N' -> ShellShortcut.NewSession
        else -> null
    }

/** Runs a resolved [shortcut] against the shared [ui] / [onNewSession]. */
fun applyShellShortcut(
    shortcut: ShellShortcut,
    ui: ShellUiState,
    onNewSession: () -> Unit,
) {
    when (shortcut) {
        ShellShortcut.ToggleSidebar -> ui.sidebarCollapsed = !ui.sidebarCollapsed
        ShellShortcut.NewSession -> onNewSession()
    }
}

/** The bound Compose [Key]s → their logical letter; anything else is not a shortcut. */
private fun Key.shortcutLetter(): Char? = when (this) {
    Key.B -> 'B'
    Key.N -> 'N'
    else -> null
}

/**
 * Intercepts Ctrl/Cmd + {B,N} on key-down and drives the shell [ui]. Uses `onKeyEvent` (bubble
 * phase) so focused descendants — the chat composer, the terminal — consume their keys FIRST; only
 * chords they leave unhandled reach the shell. Returns true ONLY for a handled combo.
 */
fun Modifier.shellShortcuts(
    ui: ShellUiState,
    onNewSession: () -> Unit,
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    if (!event.isCtrlPressed && !event.isMetaPressed) return@onKeyEvent false
    val letter = event.key.shortcutLetter() ?: return@onKeyEvent false
    val shortcut = mapShellShortcut(letter) ?: return@onKeyEvent false
    applyShellShortcut(shortcut, ui, onNewSession)
    true
}
