package dev.supermux.editor.compose

import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.keymapOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web's DOM key-down decision ([webKeyDown]): what the surface does with a hardware key before
 * Compose for the web would (a frame late) see it.
 */
class WebKeysTest {
    private fun view(ext: Extension? = null, text: String = "ab", at: Int = 1) =
        EditorView(if (ext == null) EditorState.create(text, EditorSelection.cursor(at)) else EditorState.create(text, EditorSelection.cursor(at), ext))
            .also { it.focused = true }

    private fun key(v: EditorView, key: String, code: String, flags: Int = 0, apple: Boolean = false, composing: Boolean = false) =
        webKeyDown(v, composing, key, code, flags, apple)

    @Test fun plainCharactersAreTypedAtOnce() {
        val v = view()
        assertEquals(WebKey.HANDLED, key(v, "x", "KeyX"))
        assertEquals(WebKey.HANDLED, key(v, "Ş", "Semicolon", KeyFlags.SHIFT))
        assertEquals(WebKey.HANDLED, key(v, " ", "Space"))
        assertEquals(WebKey.HANDLED, key(v, "😀", ""))
        assertEquals(WebKey.HANDLED, key(v, "å", "KeyA", KeyFlags.ALT, apple = true), "Option-letter on a Mac is a character layer")
        assertEquals("axŞ 😀åb", v.state.doc.toString())
    }

    @Test fun boundKeysRunTheirCommandInTheSameEvent() {
        val v = view(text = "abc\ndef", at = 4)
        assertEquals(WebKey.HANDLED, key(v, "Backspace", "Backspace"))
        assertEquals("abcdef", v.state.doc.toString())
        assertEquals(WebKey.HANDLED, key(v, "Enter", "Enter"))
        assertEquals("abc\ndef", v.state.doc.toString())
        assertEquals(WebKey.HANDLED, key(v, "ArrowRight", "ArrowRight"))
        assertEquals(WebKey.HANDLED, key(v, "ArrowLeft", "ArrowLeft", KeyFlags.SHIFT))
        assertEquals(4, v.state.selection.main.head)
        assertEquals(WebKey.HANDLED, key(v, "Tab", "Tab"), "Tab indents instead of moving focus")
        assertEquals(WebKey.HANDLED, key(v, "a", "KeyA", KeyFlags.CTRL), "Mod-a is a binding")
        assertEquals(0, v.state.selection.main.from)
    }

    @Test fun clipboardChordsAreLeftToTheBrowsersClipboardEvents() {
        val v = view()
        for (k in listOf("c", "x", "v")) assertEquals(WebKey.BROWSER, key(v, k, "Key${k.uppercase()}", KeyFlags.CTRL), "Mod-$k")
        assertEquals(WebKey.BROWSER, key(v, "v", "KeyV", KeyFlags.META, apple = true))
        assertEquals("ab", v.state.doc.toString())
    }

    @Test fun everythingElseGoesTheOrdinaryWay() {
        val v = view()
        assertEquals(WebKey.PASS, key(v, "k", "KeyK", KeyFlags.META, apple = true), "an unbound shortcut")
        assertEquals(WebKey.PASS, key(v, "x", "KeyX", KeyFlags.ALT), "Alt is a shortcut modifier off Apple")
        assertEquals(WebKey.PASS, key(v, "x", "KeyX", KeyFlags.COMPOSING), "the IME is composing")
        assertEquals(WebKey.PASS, key(v, "Backspace", "Backspace", composing = true), "the field is composing")
        assertEquals(WebKey.PASS, key(v, "Dead", "Quote"), "a dead key")
        assertEquals(WebKey.PASS, key(v, "Escape", "Escape"), "an unbound named key")
        assertEquals("ab", v.state.doc.toString())
        v.focused = false
        assertEquals(WebKey.PASS, key(v, "x", "KeyX"), "not focused")
        v.focused = true
        v.readOnly = true
        assertEquals(WebKey.PASS, key(v, "x", "KeyX"), "read-only: nothing is typed")
        assertEquals(WebKey.HANDLED, key(v, "ArrowLeft", "ArrowLeft"), "read-only still moves")
    }

    @Test fun aKeyBindingForACharacterWinsOverTyping() {
        var ran = 0
        val v = view(keymapOf(KeyBinding("x", Command { ran++; true })))
        assertEquals(WebKey.HANDLED, key(v, "x", "KeyX"))
        assertEquals(1, ran)
        assertEquals("ab", v.state.doc.toString())
    }

    @Test fun altGrCharactersAreNotShadowedByModAltBindings() {
        // Windows reports AltGr as Ctrl+Alt: "Mod-Alt-q" must not eat the '@' of a German layout.
        var ran = 0
        val v = view(keymapOf(KeyBinding("Mod-Alt-q", Command { ran++; true }), KeyBinding("Ctrl-Alt-e", Command { ran += 10; true })))
        assertEquals(WebKey.PASS, key(v, "@", "KeyQ", KeyFlags.CTRL or KeyFlags.ALT))
        assertEquals(0, ran)
        // A binding that names Ctrl-Alt explicitly still runs.
        assertEquals(WebKey.HANDLED, key(v, "€", "KeyE", KeyFlags.CTRL or KeyFlags.ALT))
        assertEquals(10, ran)
    }
}
