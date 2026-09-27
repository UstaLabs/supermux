package dev.supermux.editor.compose

import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A clipboard in memory: never the host's. */
internal class FakeClipboard(var text: String? = null) : EditorClipboard {
    /** read() calls: on iOS a read is what shows the paste permission prompt. */
    var reads = 0
    override fun write(text: String) { this.text = text }
    override suspend fun read(): String? { reads++; return text }
    override fun hasText(): Boolean = text != null
}

class ClipboardCommandsTest {
    private fun view(text: String, sel: EditorSelection, clip: FakeClipboard) = EditorView(EditorState.create(text, sel)).also {
        it.clipboard = clip
        it.scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @Test fun cutAndPasteOfADocumentFarLargerThanTheFieldWindow() {
        val text = (0 until 400).joinToString("\n") { "line $it with some words" }
        val clip = FakeClipboard()
        val v = view(text, EditorSelection.single(0, text.length), clip)
        assertTrue(DefaultCommands.cut.run(v))
        assertEquals(text, clip.text, "cut copied only part of the selection")
        assertEquals("", v.state.doc.toString())
        val events = ArrayList<Transaction>()
        v.addListener { events += it }
        assertTrue(DefaultCommands.paste.run(v))
        assertEquals(text, v.state.doc.toString(), "paste did not restore the document")
        assertEquals(EditorSelection.cursor(text.length), v.state.selection)
        assertTrue(events.single().isUserEvent("paste"))
    }

    @Test fun copyPutsOneLinePerRangeAndPasteDistributesThem() {
        val clip = FakeClipboard()
        val v = view("alpha\nbeta\ngamma", EditorSelection.create(listOf(SelectionRange(0, 5), SelectionRange(6, 10))), clip)
        DefaultCommands.copy.run(v)
        assertEquals("alpha\nbeta", clip.text)
        assertEquals("alpha\nbeta\ngamma", v.state.doc.toString(), "copy changed the document")
        // Two lines, two cursors: one line each (CM6).
        val w = view("[]\n[]", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(4))), clip)
        DefaultCommands.paste.run(w)
        assertEquals("[alpha]\n[beta]", w.state.doc.toString())
        // Two lines, three cursors: the whole text at each.
        val x = view("a\nb\nc", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(3), SelectionRange(5))), clip)
        DefaultCommands.paste.run(x)
        assertEquals("aalpha\nbeta\nbalpha\nbeta\ncalpha\nbeta", x.state.doc.toString())
        assertEquals(3, x.state.selection.ranges.size)
    }

    @Test fun nothingSelectedCopiesNothingAndTheKeysAreBound() {
        val clip = FakeClipboard("keep")
        val v = view("abc", EditorSelection.cursor(1), clip)
        assertTrue(DefaultCommands.copy.run(v), "the key is still handled")
        assertEquals("keep", clip.text)
        for (apple in listOf(true, false)) for (k in listOf("c", "x", "v")) {
            val chord = KeyChord.parse("Mod-$k", apple)
            assertTrue(defaultBindings(apple).any { it.chord(apple) == chord }, "Mod-$k is not bound (apple=$apple)")
        }
    }

    @Test fun pastedCarriageReturnsBecomeLineFeeds() {
        val v = view("", EditorSelection.cursor(0), FakeClipboard())
        v.paste("a\r\nb\rc")
        assertEquals("a\nb\nc", v.state.doc.toString())
        // And the line count seen by the one-line-per-cursor rule is the normalized one.
        val w = view("[]\n[]", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(4))), FakeClipboard())
        w.paste("x\r\ny")
        assertEquals("[x]\n[y]", w.state.doc.toString())
    }

    @Test fun theWebsCopyWithNothingSelectedLeavesTheClipboardAlone() {
        val v = view("abc", EditorSelection.cursor(1), FakeClipboard()).also { it.focused = true }
        assertEquals(null, webClipboardText(v, cut = false), "the browser's own copy must run (and copy nothing)")
        val w = view("abc", EditorSelection.single(0, 2), FakeClipboard()).also { it.focused = true }
        assertEquals("ab", webClipboardText(w, cut = true))
        assertEquals("c", w.state.doc.toString())
    }

    @Test fun readOnlyCopiesButNeverCutsOrPastes() {
        val clip = FakeClipboard()
        val v = view("abc", EditorSelection.single(0, 3), clip).also { it.readOnly = true }
        DefaultCommands.cut.run(v)
        assertEquals("abc", clip.text)
        assertEquals("abc", v.state.doc.toString())
        DefaultCommands.paste.run(v)
        assertEquals("abc", v.state.doc.toString())
    }
}
