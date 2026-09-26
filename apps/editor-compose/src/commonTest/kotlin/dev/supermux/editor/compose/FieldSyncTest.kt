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
        // "aa" -> "aaa" with the caret at 2 is an insert AT 2, not at 0.
        assertEquals(FieldEdit(2, 2, "a"), diffField("aa", "aaa", 2, 2))

    @Test fun theEditAlwaysCoversThePreviousSelection() {
        // Typing the selection's own first character over it: the whole selection is replaced.
        assertEquals(FieldEdit(0, 5, "a"), diffField("abcde", "a", 0, 5))
        assertEquals(FieldEdit(0, 3, "f"), diffField("foo foo", "f foo", 0, 3))
        // A Backspace in indentation (repeated spaces) deletes just before the caret.
        assertEquals(FieldEdit(3, 4, ""), diffField("    x", "   x", 4, 4))
    }

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

    @Test fun theInputEventPathAppliesTheEditAtOnceAndTheCommitPathFinishesIt() {
        // The field's input transformation (inside the input event) reports each edit first: the
        // document changes at once, and nothing re-windows there (the composition is unknown).
        val text = (0 until 30).joinToString("") { "word$it " }
        val h = Harness(text, 200)
        repeat(12) {
            val f = h.field
            val t = f.text.removeRange(f.selStart - 1, f.selStart)
            assertNull(h.sync.onFieldChange(t, f.selStart - 1, f.selStart - 1, null, deferRewindow = true), "re-windowed inside the input event")
            h.field = FieldText(t, f.selStart - 1, f.selStart - 1)
        }
        assertEquals(text.removeRange(188, 200), h.doc, "the document changed at once")
        h.assertInSync()
        // The committed state follows: near the window's start now, so it re-windows there.
        val f = h.field
        h.sync.onFieldChange(f.text, f.selStart, f.selEnd, null)?.let { h.field = it }
        h.assertInSync()
        assertTrue(h.field.selStart >= 4, "still at the window's edge after the commit")
    }

    @Test fun aCompositionStartedInTheInputEventIsLabelledOnceItIsKnown() {
        val h = Harness("x ", 2)
        h.sync.onFieldChange("x に", 3, 3, null, deferRewindow = true)
        h.sync.onFieldChange("x に", 3, 3, 2..2)
        assertEquals(2 until 3, h.sync.composition)
        h.sync.onFieldChange("x 日本", 4, 4, 2..2, deferRewindow = true)
        assertEquals("x 日本", h.doc)
        assertTrue(h.transactions.last().isUserEvent("input.ime"), "an edit while composing is input.ime")
    }

    @Test fun selectAllThenTypingTheWindowsFirstCharacterReplacesEverything() {
        val text = (0 until 50).joinToString("\n") { "line $it" }
        val h = Harness(text, 0)
        DefaultCommands.selectAll.run(h.view)
        val first = h.field.text.substring(0, 1)
        h.type(first)
        assertEquals(first, h.doc, "the selection was not replaced whole")
        h.assertInSync()
    }

    @Test fun typingOverSeveralSelectionsReplacesEachOne() {
        val h = Harness("foo foo foo", 0)
        h.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.create(listOf(SelectionRange(0, 3), SelectionRange(4, 7), SelectionRange(8, 11)))))
        h.type("f")
        assertEquals("f f f", h.doc)
        assertEquals(listOf(1, 3, 5), h.view.state.selection.ranges.map { it.head })
    }

    @Test fun softBackspaceInsideIndentationAtSeveralCursors() {
        val h = Harness("        a\n        b", 4)
        h.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.create(listOf(SelectionRange(4), SelectionRange(14)))))
        h.backspace()
        assertEquals("       a\n       b", h.doc)
        assertEquals(listOf(3, 12), h.view.state.selection.ranges.map { it.head })
    }

    @Test fun composingAtSeveralCursorsKeepsEveryCursor() {
        // Gboard composes nearly every word: the composition must happen at every cursor.
        val h = Harness("a\nb\nc", 1)
        h.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.create(listOf(SelectionRange(1), SelectionRange(3), SelectionRange(5)))))
        val f = h.field
        val at = f.selStart
        h.ime(f.text.substring(0, at) + "に" + f.text.substring(at), at + 1, composition = at..at)
        h.ime(f.text.substring(0, at) + "日本" + f.text.substring(at), at + 2, composition = at..at + 1)
        h.ime(f.text.substring(0, at) + "日本" + f.text.substring(at), at + 2, composition = null)
        assertEquals("a日本\nb日本\nc日本", h.doc)
        assertEquals(3, h.view.state.selection.ranges.size, "the cursors collapsed")
        assertTrue(h.transactions.filter { it.docChanged }.all { it.isUserEvent("input.ime") })
    }

    @Test fun anEditAwayFromTheCaretNeverCollapsesTheCursors() {
        val h = Harness("teh cat\nx\ny", 7)
        h.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.create(listOf(SelectionRange(7), SelectionRange(9)), 0)))
        // Autocorrect rewrites a word the caret is not next to.
        val f = h.field
        h.ime(f.text.replaceFirst("teh", "the"), f.selStart)
        assertEquals("the cat\nx\ny", h.doc)
        assertEquals(2, h.view.state.selection.ranges.size)
    }

    @Test fun theWindowShrinksAfterAHugeEdit() {
        val h = Harness("ab", 1)
        h.sync.onFieldChange("a" + "x".repeat(5000) + "b", 5001, 5001, null, deferRewindow = true)?.let { h.field = it }
        assertEquals(5002, h.doc.length)
        assertTrue(h.field.text.length <= 4 * 20, "a ${h.field.text.length}-unit field after a paste")
        h.assertInSync()
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
