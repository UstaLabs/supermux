package dev.supermux.ui.keys

import androidx.compose.ui.input.key.Key
import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeymapTest {
    @Test fun modIsCmdOnAppleAndCtrlElsewhere() {
        val b = bind("Mod-Shift-n", AppCommands.MoveToNewWindow)
        assertEquals(KeyChord("n", shift = true, meta = true), b.chord(KeyOs.Apple))
        assertEquals(KeyChord("n", ctrl = true, shift = true), b.chord(KeyOs.Other))
    }

    @Test fun anOtherOnlyKeyHasNoAppleChord() {
        val b = bindOther("Ctrl-Shift-t", AppCommands.NewTerminal)
        assertNull(b.chord(KeyOs.Apple))
        assertEquals(KeyChord("t", ctrl = true, shift = true), b.chord(KeyOs.Other))
    }

    @Test fun newTerminalHasCtrlShiftTOnlyOffApple() {
        assertEquals(listOf(KeyChord("t", meta = true)), DefaultKeymap.chordsFor(AppCommands.NewTerminal, KeyOs.Apple))
        assertEquals(
            listOf(KeyChord("t", ctrl = true), KeyChord("t", ctrl = true, shift = true)),
            DefaultKeymap.chordsFor(AppCommands.NewTerminal, KeyOs.Other),
        )
    }

    @Test fun ctrlNIsNotAnAppKeyOnAMac() {
        assertEquals(listOf("session.new"), DefaultKeymap.bindingsFor(KeyChord("n", meta = true), KeyOs.Apple).map { it.commandId })
        assertTrue(DefaultKeymap.bindingsFor(KeyChord("n", ctrl = true), KeyOs.Apple).isEmpty())
    }

    @Test fun everyBindingNamesACatalogCommand() {
        val ids = AppCommands.all.map { it.id }.toSet()
        assertTrue(DefaultKeymap.bindings.all { it.commandId in ids })
    }

    @Test fun chordsAreDrawnTheWayEachOsWritesThem() {
        assertEquals("⇧⌘N", formatChord(KeyChord("n", shift = true, meta = true), KeyOs.Apple))
        assertEquals("⌃⌥⇧⌘K", formatChord(KeyChord("k", ctrl = true, alt = true, shift = true, meta = true), KeyOs.Apple))
        assertEquals("Ctrl+Shift+N", formatChord(KeyChord("n", ctrl = true, shift = true), KeyOs.Other))
        assertEquals("⌘,", formatChord(KeyChord(",", meta = true), KeyOs.Apple))
        assertEquals("Ctrl+Alt+\\", formatChord(KeyChord("\\", ctrl = true, alt = true), KeyOs.Other))
        assertEquals("⌘↩", formatChord(KeyChord("Enter", meta = true), KeyOs.Apple))
        assertEquals("Ctrl+Enter", formatChord(KeyChord("Enter", ctrl = true), KeyOs.Other))
    }

    @Test fun keyNamesRoundTripThroughComposeKeys() {
        assertEquals(Key.N, composeKeyOf("n"))
        assertEquals(Key.Comma, composeKeyOf(","))
        assertEquals(Key.Backslash, composeKeyOf("\\"))
        assertEquals("n", keyNameOf(Key.N))
        assertEquals("/", keyNameOf(Key.Slash))
        assertNull(composeKeyOf("NoSuchKey"))
    }
}
