package dev.supermux.editor.compose

import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.keymapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web's fast typing path decides per key-down: only plain characters into a focused, editable view. */
class FastTypingTest {
    private fun view(ext: dev.supermux.editor.core.Extension? = null) =
        EditorView(if (ext == null) EditorState.create("ab", EditorSelection.cursor(1)) else EditorState.create("ab", EditorSelection.cursor(1), ext))
            .also { it.focused = true }

    @Test fun aPlainCharacterIsTypedAtOnce() {
        val v = view()
        assertTrue(fastTypeKey(v, false, "x", "KeyX", 0, apple = false))
        assertEquals("axb", v.state.doc.toString())
        assertTrue(fastTypeKey(v, false, "Ş", "Semicolon", KeyFlags.SHIFT, apple = false))
        assertTrue(fastTypeKey(v, false, " ", "Space", 0, apple = false))
        assertTrue(fastTypeKey(v, false, "😀", "", 0, apple = false))
        assertEquals("axŞ 😀b", v.state.doc.toString())
        // Option-letter on a Mac is a character layer: typed.
        assertTrue(fastTypeKey(v, false, "å", "KeyA", KeyFlags.ALT, apple = true))
    }

    @Test fun everythingElseGoesTheOrdinaryWay() {
        val v = view()
        assertFalse(fastTypeKey(v, false, "x", "KeyX", KeyFlags.CTRL, apple = false), "a shortcut")
        assertFalse(fastTypeKey(v, false, "x", "KeyX", KeyFlags.META, apple = true), "a shortcut")
        assertFalse(fastTypeKey(v, false, "x", "KeyX", KeyFlags.ALT, apple = false), "Alt is a shortcut modifier off Apple")
        assertFalse(fastTypeKey(v, false, "x", "KeyX", KeyFlags.COMPOSING, apple = false), "the IME is composing")
        assertFalse(fastTypeKey(v, true, "x", "KeyX", 0, apple = false), "the field is composing")
        assertFalse(fastTypeKey(v, false, "Dead", "Quote", 0, apple = false), "a dead key")
        assertFalse(fastTypeKey(v, false, "Enter", "Enter", 0, apple = false), "a named key")
        assertFalse(fastTypeKey(v, false, "\t", "Tab", 0, apple = false), "a control character")
        assertEquals("ab", v.state.doc.toString())
        v.focused = false
        assertFalse(fastTypeKey(v, false, "x", "KeyX", 0, apple = false), "not focused")
        v.focused = true
        v.readOnly = true
        assertFalse(fastTypeKey(v, false, "x", "KeyX", 0, apple = false), "read-only")
    }

    @Test fun aKeyBindingForTheChordWins() {
        var ran = false
        val v = view(keymapOf(KeyBinding("x", Command { ran = true; true })))
        assertFalse(fastTypeKey(v, false, "x", "KeyX", 0, apple = false))
        assertFalse(ran, "the fast path must not run the binding itself; the ordinary key path does")
        assertEquals("ab", v.state.doc.toString())
    }
}
