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

    @Test fun aMalformedBindingFailsWhenRegisteredAndLeavesOthersAlone() {
        val ok = Command { true }
        val e = assertFailsWith<IllegalArgumentException> { keymapOf(KeyBinding("Mod-k", ok), KeyBinding("Hyper-k", ok)) }
        assertTrue("Hyper-k" in e.message.orEmpty(), e.message)
        val target = Target(EditorState.create(extensions = keymapOf(KeyBinding("Mod-k", ok))))
        assertTrue(runKey(target, KeyChord("k", meta = true), apple = true))
    }

    @Test fun bindingsAreParsedOnceForBothPlatforms() {
        val b = KeyBinding("Mod-Shift-z", Command { true })
        assertEquals(KeyChord("z", meta = true, shift = true), b.chord(apple = true))
        assertEquals(KeyChord("z", ctrl = true, shift = true), b.chord(apple = false))
        assertSame(b.chord(apple = true), b.chord(apple = true))
    }

    @Test fun aBindingCanNameAnotherKeyForApplePlatforms() {
        // CM6's `{key: "Mod-y", mac: "Mod-Shift-z"}`: redo is Ctrl-y elsewhere, Cmd-Shift-z on a Mac.
        val b = KeyBinding("Mod-y", Command { true }, mac = "Mod-Shift-z")
        assertEquals(KeyChord("y", ctrl = true), b.chord(apple = false))
        assertEquals(KeyChord("z", meta = true, shift = true), b.chord(apple = true))
        assertFailsWith<IllegalArgumentException> { KeyBinding("Mod-y", Command { true }, mac = "Hyper-y") }
    }
}
