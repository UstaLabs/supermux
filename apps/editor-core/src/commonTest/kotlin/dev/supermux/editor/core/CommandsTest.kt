package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandsTest {
    @Test fun modResolvesPerPlatform() {
        assertEquals(KeyChord("s", meta = true), KeyChord.parse("Mod-s", apple = true))
        assertEquals(KeyChord("s", ctrl = true), KeyChord.parse("Mod-s", apple = false))
        assertEquals(KeyChord("z", ctrl = true, shift = true), KeyChord.parse("Mod-Shift-z", apple = false))
        assertEquals(KeyChord("-", meta = true), KeyChord.parse("Mod--", apple = true))
        assertEquals(KeyChord("ArrowUp", alt = true), KeyChord.parse("Alt-ArrowUp", apple = true))
        assertEquals(KeyChord("S"[0].lowercase()), KeyChord.parse("S", apple = true))
    }

    private class Target(override var state: EditorState) : CommandTarget {
        override fun dispatch(spec: TransactionSpec) { state = state.update(spec).state }
    }

    @Test fun firstCommandThatHandlesTheKeyWins() {
        val log = ArrayList<String>()
        val declines = Command { log += "declines"; false }
        val handles = Command { t -> log += "handles"; t.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, "!")))); true }
        val never = Command { log += "never"; true }
        val target = Target(EditorState.create("x", extensions = extensionOf(
            keymapOf(KeyBinding("Mod-k", handles), KeyBinding("Mod-k", never)),
            Prec.high(keymapOf(KeyBinding("Mod-k", declines))),
        )))
        assertTrue(runKey(target, KeyChord("k", ctrl = true), apple = false))
        assertEquals(listOf("declines", "handles"), log)
        assertEquals("!x", target.state.doc.toString())
        assertFalse(runKey(target, KeyChord("j", ctrl = true), apple = false))
    }

}
