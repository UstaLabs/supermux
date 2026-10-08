// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/ShellShortcuts.kt —
// keep in sync until a shared UI module exists. Pure androidx.compose.ui.input.key, available on
// desktop, so this is a verbatim copy of the Android original except for the package name.
//
// Editor keys vs. these shortcuts: [Modifier.shellShortcuts] is attached to the outer Compose
// window and only fires via `onKeyEvent`'s BUBBLE phase — i.e. only for chords a focused Compose
// descendant left unhandled. The native editor (M5) is such a descendant: a chord its keymap binds
// (zoom, search, the CM6 default bindings) is consumed there, and everything else bubbles up here.
package dev.supermux.ui.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    ToggleSidebar, NewSession, MoveToNewWindow,
}

/**
 * Pure key→action mapping (platform-independent, keyed on the letter so it is unit-testable off the
 * device). Returns null when the letter is not a bound shortcut.
 *
 * Ctrl/Cmd+Shift+N is MoveToNewWindow; Ctrl/Cmd+N remains NewSession. No collision.
 */
fun mapShellShortcut(letter: Char, shift: Boolean = false): ShellShortcut? =
    when (letter.uppercaseChar()) {
        'B' -> ShellShortcut.ToggleSidebar
        'N' -> if (shift) ShellShortcut.MoveToNewWindow else ShellShortcut.NewSession
        else -> null
    }

/** Runs a resolved [shortcut] against the shared [ui] / [onNewSession]. */
fun applyShellShortcut(
    shortcut: ShellShortcut,
    ui: ShellUiState,
    onNewSession: () -> Unit,
    onMoveToNewWindow: () -> Unit = {},
) {
    when (shortcut) {
        ShellShortcut.ToggleSidebar -> ui.sidebarCollapsed = !ui.sidebarCollapsed
        ShellShortcut.NewSession -> onNewSession()
        ShellShortcut.MoveToNewWindow -> onMoveToNewWindow()
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
    onMoveToNewWindow: () -> Unit = {},
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    if (!event.isCtrlPressed && !event.isMetaPressed) return@onKeyEvent false
    val letter = event.key.shortcutLetter() ?: return@onKeyEvent false
    val shortcut = mapShellShortcut(letter, shift = event.isShiftPressed) ?: return@onKeyEvent false
    applyShellShortcut(shortcut, ui, onNewSession, onMoveToNewWindow)
    true
}

/** Ctrl/Cmd+T, exactly: Shift or Alt makes it some other chord. */
fun isNewTerminalChord(key: Key, ctrlOrMeta: Boolean, shift: Boolean, alt: Boolean): Boolean =
    key == Key.T && ctrlOrMeta && !shift && !alt

/**
 * Ctrl/Cmd+T opens a terminal tab in the workspace on screen ([ShellUiState.newTerminalAction];
 * nothing to do, and the key passes through, when no workspace is open). Unlike [shellShortcuts]
 * this runs in the PREVIEW phase: a focused terminal would otherwise eat Ctrl+T as ^T.
 */
fun Modifier.newTerminalShortcut(ui: ShellUiState): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val chord = isNewTerminalChord(
        event.key,
        ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )
    if (!chord) return@onPreviewKeyEvent false
    val action = ui.newTerminalAction ?: return@onPreviewKeyEvent false
    action()
    true
}
