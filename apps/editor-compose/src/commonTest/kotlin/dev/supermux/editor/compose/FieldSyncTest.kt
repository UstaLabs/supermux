package dev.supermux.editor.compose

import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M0's WindowDiffTest, ported: the smallest edit between two field states, and the window. */
class WindowDiffTest {
    @Test fun insertInMiddle() = assertEquals(FieldEdit(2, 2, "X"), diffField("abcd", "abXcd"))
    // M0's version expected (4, 7, "the"); the minimal edit keeps the common "t". Same document either way.
    @Test fun autocorrectReplacesWord() {
        val e = diffField("hi, teh.", "hi, the.")!!
        assertEquals(FieldEdit(5, 7, "he"), e)
        assertEquals("hi, the.", FieldWindow(0, "hi, teh.").applyTo("hi, teh.", e))
    }
    @Test fun deleteAtStart() = assertEquals(FieldEdit(0, 1, ""), diffField("ab", "b"))
    @Test fun identical() = assertNull(diffField("ab", "ab"))
    @Test fun repeatedCharsPreferTheCursorSide() =
        // "aa" -> "aaa" with the cursor at 3 is an insert AT 2, not at 0.
        assertEquals(FieldEdit(2, 2, "a"), diffField("aa", "aaa", cursorAfter = 3))

    @Test fun windowMapsBackToDocument() {
        val doc = Rope.of("0123456789abcdef")
        val w = FieldWindow.around(doc, cursor = 10, radius = 3) // one line: the whole line fits in 2 x radius? no: clamped
        assertTrue(w.base <= 7 && w.base + w.text.length >= 13, "window $w")
        val edit = diffField(w.text, w.text.substring(0, 10 - w.base) + "Z" + w.text.substring(10 - w.base))!!
        assertEquals("0123456789Zabcdef", w.applyTo(doc.toString(), edit))
    }

    @Test fun theWindowPrefersWholeLinesAndNeverSplitsASurrogatePair() {
        val doc = Rope.of("first line\nsecond line here\nthird")
        val w = FieldWindow.around(doc, cursor = 15, radius = 8)
        assertTrue(w.base == 0 || w.base == 11, "starts at a line's start: ${w.base}")
        assertTrue(w.text.endsWith("second line here"), "ends at a line's end: '${w.text}'")
        val emoji = Rope.of("😀".repeat(50))
        for (c in 0..100 step 2) {
            val e = FieldWindow.around(emoji, c, radius = 5)
            assertTrue(!e.text.first().isLowSurrogate() && !e.text.last().isHighSurrogate(), "split pair at $c: base ${e.base}")
        }
    }
}

class FieldSyncTest {
    private class Harness(text: String, cursor: Int, radius: Int = 20, margin: Int = 4) {
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(cursor)))
        val sync = FieldSync(view, radius, margin)
        /** What the platform field holds right now. */
        var field: FieldText = sync.initialField()
        val transactions = ArrayList<Transaction>()

        init {
            view.addListener { tr -> transactions += tr; sync.onStateChange()?.let { field = it } }
        }

        /** The IME edits the field to [text] with the caret at [sel], composing [composition]. */
        fun ime(text: String, sel: Int = text.length, composition: IntRange? = null) {
            field = FieldText(text, sel, sel)
            sync.onFieldChange(text, sel, sel, composition)?.let { field = it }
        }

        /** Type [s] at the field's caret. */
        fun type(s: String) {
            val t = field.text.substring(0, field.selStart) + s + field.text.substring(field.selEnd)
            ime(t, field.selStart + s.length)
        }

        /** A soft Backspace: the field deletes the character before its caret (a no-op at 0). */
        fun backspace() {
            if (field.selStart == 0 && field.selEnd == 0) return
            val from = if (field.selStart == field.selEnd) field.selStart - 1 else field.selStart
            ime(field.text.substring(0, from) + field.text.substring(field.selEnd), from)
        }

        val doc: String get() = view.state.doc.toString()
        val head: Int get() = view.state.selection.main.head

        /** The field always shows the document's text at its window, and the caret where the doc's is. */
        fun assertInSync() {
            val w = sync.window
            assertEquals(view.state.doc.slice(w.base, w.base + w.text.length), field.text, "the field drifted from the document")
            assertEquals(head - w.base, field.selEnd, "the field's caret is not the document's")
        }
    }

    @Test fun typingBecomesInputTransactions() {
        val h = Harness("hello world", 5)
        h.type(",")
        h.type(" dear")
        assertEquals("hello, dear world", h.doc)
        assertEquals(11, h.head)
        assertTrue(h.transactions.all { it.isUserEvent("input") && !it.isUserEvent("input.ime") })
        h.assertInSync()
    }

    @Test fun autocorrectReplacingTheWordBeforeTheCaretIsOneChange() {
        val h = Harness("say teh", 7)
        h.ime("say the", 7)
        assertEquals("say the", h.doc)
        assertEquals(1, h.transactions.size)
        h.assertInSync()
    }

    @Test fun aCompositionIsInTheDocumentMarkedAndLabelledIme() {
        val h = Harness("x ", 2)
        h.ime("x に", 3, composition = 2..2)
        assertEquals("x に", h.doc)
        assertEquals(2 until 3, h.sync.composition)
        h.ime("x 日本", 4, composition = 2..3)
        assertEquals("x 日本", h.doc)
        assertEquals(2 until 4, h.sync.composition)
        // Committed: the text stays, the underline goes, nothing else changes.
        h.ime("x 日本", 4, composition = null)
        assertEquals("x 日本", h.doc)
        assertNull(h.sync.composition)
        assertTrue(h.transactions.all { it.isUserEvent("input.ime") }, "composition edits are input.ime")
        assertEquals(2, h.transactions.size)
        h.assertInSync()
    }

    @Test fun aCancelledCompositionLeavesNothingBehind() {
        val h = Harness("ab", 2)
        h.ime("abにほ", 4, composition = 2..3)
        h.ime("ab", 2, composition = null)
        assertEquals("ab", h.doc)
        assertNull(h.sync.composition)
    }

    @Test fun softBackspaceWalksPastTheWindowEdge() {
        // M0 checklist step 3: a held Backspace deletes far past where the field's text began.
        val text = (0 until 40).joinToString("") { "word$it " }
        val h = Harness(text, text.length - 1)
        repeat(text.length - 1) { h.backspace() }
        assertEquals(" ", h.doc, "every character before the caret was deleted")
        assertEquals(0, h.head)
        h.assertInSync()
        h.backspace() // at the document start: nothing happens
        assertEquals(" ", h.doc)
    }

    @Test fun softReturnInsertsAnIndentedNewline() {
        val h = Harness("    foo", 7)
        h.type("\n")
        assertEquals("    foo\n    ", h.doc)
        assertEquals(12, h.head)
        h.assertInSync()
    }

    @Test fun anExternalChangeRewindowsTheField() {
        val h = Harness("abc def", 3)
        h.view.dispatch(dev.supermux.editor.core.TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(0, 0, "XYZ "))))
        h.assertInSync()
        h.type("!")
        assertEquals("XYZ abc! def", h.doc)
        // A cursor move by a key (the field did not do it) moves the field's caret.
        DefaultCommands.cursorLineEnd.run(h.view)
        h.assertInSync()
    }

    @Test fun typingReplacesTheSelectionEvenWhenItIsBiggerThanTheWindow() {
        val text = "x".repeat(500)
        val h = Harness(text, 0)
        DefaultCommands.selectAll.run(h.view)
        h.type("y")
        assertEquals("y", h.doc)
        h.assertInSync()
    }

    @Test fun typingWithSeveralCursorsInsertsAtEveryOne() {
        val h = Harness("ab\nab\nab", 1)
        h.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.create(listOf(SelectionRange(1), SelectionRange(4), SelectionRange(7)))))
        h.type("X")
        assertEquals("aXb\naXb\naXb", h.doc)
        assertEquals(listOf(2, 6, 10), h.view.state.selection.ranges.map { it.head })
        h.backspace()
        assertEquals("ab\nab\nab", h.doc)
    }

    @Test fun theFieldMovingItsCaretMovesTheDocumentsCaret() {
        val h = Harness("hello world", 11)
        // iOS's space-bar trackpad: the text is the same, only the caret moved.
        h.ime(h.field.text, 2)
        assertEquals(h.sync.window.base + 2, h.head)
    }

    @Test fun readOnlyIgnoresTheFieldAndPutsItBack() {
        val h = Harness("abc", 3)
        h.view.readOnly = true
        val before = h.field
        val restore = assertNotNull(h.sync.onFieldChange("abcd", 4, 4, null))
        assertEquals(before.text, restore.text)
        assertEquals("abc", h.doc)
    }
}
