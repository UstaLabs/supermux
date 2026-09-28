package dev.supermux.terminal.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Something the SURFACE does for a key chord, instead of sending the chord to the program. */
enum class TerminalShortcut { COPY, PASTE, SELECT_ALL, FIND, ZOOM_IN, ZOOM_OUT, ZOOM_RESET }

/** One font-size step a host is asked for: a zoom shortcut or a pinch. */
enum class TerminalZoom { IN, OUT, RESET }

/**
 * The chord [key] + modifiers as a surface shortcut, or null when it belongs to the program.
 *
 * Pure, so the table is testable without a key event. Both families are accepted on every platform
 * rather than picked per OS — the terminal package has no platform source sets, and the two do not
 * collide:
 *
 * - **Cmd** (Compose's Meta): the macOS convention, and the one a Mac user's hands already know.
 *   On Linux and Windows Meta is the Super/Windows key, which the desktop takes for itself and a
 *   program in a terminal practically never sees.
 * - **Ctrl+Shift**: the Linux/Windows terminal convention (GNOME Terminal, Windows Terminal,
 *   Ghostty on Linux). Ctrl+Shift+letter reaches a program as the SAME control code as Ctrl+letter
 *   under the legacy encoding, so taking it costs the program nothing it could tell apart — except
 *   under the kitty keyboard protocol, which is the price every one of those terminals pays too.
 * - **Ctrl+C with a selection** copies (Windows Terminal's rule). The selection is visible, so the
 *   user can see the chord will not interrupt; the copy clears it, so a second Ctrl+C does.
 * - **Ctrl+Insert / Shift+Insert**: the IBM CUA copy and paste, still what X11 users reach for.
 * - **Zoom** on Ctrl+=/−/0 as well as Cmd: Ghostty's Linux bindings. Ctrl+− is `^_` in the legacy
 *   encoding (emacs' undo), which is why it is only taken WITHOUT Shift or Alt on top.
 */
fun terminalShortcutOf(
    key: Key,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    meta: Boolean,
    hasSelection: Boolean,
): TerminalShortcut? {
    if (alt) return null
    if (meta && !ctrl) {
        return when (key) {
            Key.C -> TerminalShortcut.COPY
            Key.V -> TerminalShortcut.PASTE
            Key.A -> TerminalShortcut.SELECT_ALL
            Key.F -> TerminalShortcut.FIND
            Key.Equals, Key.Plus, Key.NumPadAdd -> TerminalShortcut.ZOOM_IN
            Key.Minus, Key.NumPadSubtract -> TerminalShortcut.ZOOM_OUT
            Key.Zero, Key.NumPad0 -> TerminalShortcut.ZOOM_RESET
            else -> null
        }
    }
    if (meta) return null
    if (ctrl && shift) {
        return when (key) {
            Key.C -> TerminalShortcut.COPY
            Key.V -> TerminalShortcut.PASTE
            Key.A -> TerminalShortcut.SELECT_ALL
            Key.F -> TerminalShortcut.FIND
            // Ctrl+Shift+= is Ctrl+Plus on a US layout.
            Key.Equals, Key.Plus -> TerminalShortcut.ZOOM_IN
            else -> null
        }
    }
    if (ctrl) {
        return when (key) {
            Key.C -> if (hasSelection) TerminalShortcut.COPY else null
            Key.Insert -> TerminalShortcut.COPY
            Key.Equals, Key.Plus, Key.NumPadAdd -> TerminalShortcut.ZOOM_IN
            Key.Minus, Key.NumPadSubtract -> TerminalShortcut.ZOOM_OUT
            Key.Zero, Key.NumPad0 -> TerminalShortcut.ZOOM_RESET
            else -> null
        }
    }
    if (shift && key == Key.Insert) return TerminalShortcut.PASTE
    return null
}

/**
 * Which key presses the surface took as shortcuts, so their RELEASES (and the platform's
 * auto-repeat of them) are swallowed too.
 *
 * Without it the program would see a key-up for a key it never saw go down — and under the kitty
 * keyboard protocol with release reporting on, that is a real event it would act on — and holding
 * Cmd+V would paste once per repeat.
 */
internal class TerminalShortcutGate {
    private val taken = mutableSetOf<Long>()

    /**
     * The shortcut a key-DOWN [event] would trigger, or null. Records nothing: the caller [take]s
     * the key only once it actually performed the shortcut, so a chord nobody handles still reaches
     * the program.
     */
    fun shortcutOf(event: KeyEvent, hasSelection: Boolean): TerminalShortcut? {
        if (event.type != KeyEventType.KeyDown) return null
        if (event.key.keyCode in taken) return null
        return terminalShortcutOf(
            key = event.key,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
            hasSelection = hasSelection,
        )
    }

    /** [event]'s key was performed as a shortcut: swallow its repeats and its release. */
    fun take(event: KeyEvent) {
        taken += event.key.keyCode
    }

    /** True for the repeat or release of a key that was [take]n; a release also forgets it. */
    fun swallows(event: KeyEvent): Boolean {
        val code = event.key.keyCode
        if (code !in taken) return false
        if (event.type == KeyEventType.KeyUp) taken -= code
        return true
    }

    /** Focus left: a held key's release will never arrive here. */
    fun reset() {
        taken.clear()
    }
}
