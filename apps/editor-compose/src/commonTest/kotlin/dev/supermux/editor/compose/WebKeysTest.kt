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

    private fun facts(key: String = "x", code: String = "KeyX", pointer: String = "", touchPoints: Int = 0, aimed: Boolean = true) =
        WebKeyFacts(aimed, key, code, 0, pointer, touchPoints)

    @Test fun theHardwareKeyboardHeuristic() {
        val st = WebKeyboardState()
        // A desktop: a physical key is a hardware key; a key not aimed at the field never is.
        assertEquals(true, isHardwareKey(WebKeyboard.AUTO, facts(), st))
        assertEquals(false, isHardwareKey(WebKeyboard.AUTO, facts(aimed = false), st))
        // Right after a touch or a pen: the soft keyboard's.
        assertEquals(false, isHardwareKey(WebKeyboard.AUTO, facts(pointer = "touch"), WebKeyboardState()))
        assertEquals(false, isHardwareKey(WebKeyboard.AUTO, facts(pointer = "pen"), WebKeyboardState()))
        // A touch device (iOS Safari) before any physical key: a soft keyboard's key without a code.
        val phone = WebKeyboardState()
        assertEquals(false, isHardwareKey(WebKeyboard.AUTO, facts(code = "", touchPoints = 5), phone))
        // ...until a key with a physical code shows up (an iPad keyboard), then hardware.
        assertEquals(true, isHardwareKey(WebKeyboard.AUTO, facts(code = "KeyA", touchPoints = 5, pointer = "mouse"), phone))
        // Forced either way.
        assertEquals(true, isHardwareKey(WebKeyboard.HARDWARE, facts(pointer = "touch"), WebKeyboardState()))
        assertEquals(false, isHardwareKey(WebKeyboard.SOFT, facts(), WebKeyboardState()))
    }

    @Test fun everyKeysPathIsLogged() {
        val v = view(text = "ab", at = 1)
        val log = ArrayList<Pair<String, KeyPath>>()
        v.onKeyPath = { k, p -> log += k to p }
        val st = WebKeyboardState()
        assertEquals(WebKey.HANDLED, webKeyPath(v, false, facts("x", "KeyX"), st, apple = false))
        assertEquals(WebKey.BROWSER, webKeyPath(v, false, WebKeyFacts(true, "c", "KeyC", KeyFlags.CTRL, "", 0), st, apple = false))
        assertEquals(WebKey.PASS, webKeyPath(v, false, facts("Dead", "Quote"), st, apple = false))
        v.webKeyboard = WebKeyboard.SOFT
        assertEquals(WebKey.PASS, webKeyPath(v, false, facts("y", "KeyY"), st, apple = false))
        assertEquals(listOf("x" to KeyPath.WEB_FAST, "c" to KeyPath.WEB_CLIPBOARD, "Dead" to KeyPath.WEB_COMPOSE, "y" to KeyPath.WEB_SOFT), log)
        assertEquals("axb", v.state.doc.toString(), "the soft keyboard's key was typed by the fast path")
    }
}
