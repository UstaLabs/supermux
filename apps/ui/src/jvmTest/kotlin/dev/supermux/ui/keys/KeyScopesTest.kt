package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyScopesTest {
    /** Pinned against terminal-compose's table (TerminalShortcuts.kt, `terminalShortcutOf`). */
    @Test fun theTerminalKeepsItsCopyPasteFindAndZoomAndNoAppKey() {
        val kept = listOf(
            KeyChord("c", meta = true), KeyChord("v", meta = true), KeyChord("f", meta = true),
            KeyChord("=", meta = true), KeyChord("0", meta = true),
            KeyChord("c", ctrl = true, shift = true), KeyChord("Insert", shift = true),
        )
        for (chord in kept) assertTrue(terminalKeeps(chord), "the terminal keeps $chord")
        val notKept = listOf(KeyChord("t", meta = true), KeyChord("t", ctrl = true), KeyChord("\\", ctrl = true), KeyChord("n", meta = true))
        for (chord in notKept) assertFalse(terminalKeeps(chord), "$chord is not the terminal's")
    }
}
