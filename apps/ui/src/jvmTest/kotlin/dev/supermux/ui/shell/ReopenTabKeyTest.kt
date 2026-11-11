package dev.supermux.ui.shell

import dev.supermux.editor.core.KeyChord
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.DefaultKeymap
import dev.supermux.ui.keys.KeyOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #122: ⌘⇧T / Ctrl+Shift+T reopens the last closed Browser tab. Off Apple the same key was (and inside a terminal stays)
 * New terminal, so it still makes a terminal there, and whenever there's nothing to reopen.
 */
class ReopenTabKeyTest {
    @Test fun theKeyIsChromes() {
        assertEquals(listOf(KeyChord.parse("Mod-Shift-t", apple = true)), DefaultKeymap.chordsFor(AppCommands.ReopenClosedTab, KeyOs.Apple))
        val other = DefaultKeymap.bindings.single { it.commandId == AppCommands.ReopenClosedTab.id }
        assertEquals(KeyChord.parse("Ctrl-Shift-t", apple = false), other.other)
        assertTrue(other.overOwner, "it reaches the app past a focused terminal, as New terminal's Ctrl+Shift+T did")
    }

    @Test fun onAppleItReopensOrDoesNothing() {
        assertEquals(ReopenKeyAction.Reopen, reopenKeyAction(KeyOs.Apple, terminalFocused = true, hasClosed = true))
        assertEquals(ReopenKeyAction.Nothing, reopenKeyAction(KeyOs.Apple, terminalFocused = false, hasClosed = false))
    }

    @Test fun offAppleATerminalKeepsItsNewTerminalKey() {
        assertEquals(ReopenKeyAction.NewTerminal, reopenKeyAction(KeyOs.Other, terminalFocused = true, hasClosed = true))
        assertEquals(ReopenKeyAction.Reopen, reopenKeyAction(KeyOs.Other, terminalFocused = false, hasClosed = true))
        assertEquals(ReopenKeyAction.NewTerminal, reopenKeyAction(KeyOs.Other, terminalFocused = false, hasClosed = false))
    }
}
