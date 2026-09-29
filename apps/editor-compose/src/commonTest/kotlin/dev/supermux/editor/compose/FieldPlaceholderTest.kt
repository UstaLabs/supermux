package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The field's U+FFFC placeholder for a fold never reaches the document, and the folded text is never
 * lost: an IME edit that hands the placeholder back (a case transform, an autocorrect) keeps the
 * hidden text as it was; U+FFFC the document really holds still round-trips.
 */
class FieldPlaceholderTest {
    private val ph = FieldWindow.PLACEHOLDER

    private class Harness(text: String, sel: EditorSelection, folds: List<Pair<Int, Int>>) {
        val view = EditorView(EditorState.create(text, sel, decorationsFacet.of(RangeSet.of(folds.map { (a, b) -> Ranged(a, b, Decoration.Replace(WidgetKey("fold", "$a"), fold = true) as Decoration) }))))
        val sync = FieldSync(view, 60, 4)
        var field: FieldText = sync.initialField()
        init { view.addListener { sync.onStateChange()?.let { field = it } } }
        fun ime(t: String, a: Int, b: Int = a) { field = FieldText(t, a, b); sync.onFieldChange(t, a, b, null)?.let { field = it } }
        val doc get() = view.state.doc.toString()
    }

    private val text = "fun f() {\n a()\n b()\n}\nafter"
    private val from = text.indexOf('{') + 1
    private val to = text.indexOf("}\nafter")

    @Test fun aCaseTransformAcrossAFoldKeepsTheHiddenTextUntransformed() {
        val h = Harness(text, EditorSelection.single(0, text.length), listOf(from to to))
        val f = h.field
        assertEquals(0 to f.text.length, f.selStart to f.selEnd, "the whole field is selected")
        // The IME uppercases the selection, placeholder included (it has no case).
        h.ime(f.text.uppercase(), 0, f.text.length)
        assertEquals("FUN F() {\n a()\n b()\n}\nAFTER", h.doc)
        assertTrue(ph !in h.doc, "the placeholder reached the document")
    }

    @Test fun anAutocorrectThatHandsThePlaceholderBackKeepsTheFold() {
        val t = "say teh{x y}ok"
        val h = Harness(t, EditorSelection.cursor(t.indexOf('}')), listOf(t.indexOf('{') + 1 to t.indexOf('}')))
        // The IME rewrites "teh{<FFFC>" as "the{<FFFC>" (a replacement covering the placeholder).
        val f = h.field.text
        val a = f.indexOf("teh")
        h.ime(f.replaceRange(a, f.indexOf(ph) + 1, "the{$ph"), f.indexOf(ph) + 1)
        assertEquals("say the{x y}ok", h.doc)
        // Still folded: the field still shows a placeholder for it.
        assertTrue(ph in h.field.text && "x y" !in h.field.text, h.field.text)
    }

    @Test fun anImeEditWhosePlaceholdersDoNotMapIsRefusedAndResynced() {
        val h = Harness(text, EditorSelection.single(0, text.length), listOf(from to to))
        val f = h.field.text
        // Two placeholders for one fold: ambiguous.
        h.ime(f.replace("$ph", "$ph$ph"), 0)
        assertEquals(text, h.doc)
        assertEquals(1, h.field.text.count { it == ph }, "the field was not resynced")
    }

    @Test fun pastingOverASelectionHoldingAFoldReplacesIt() {
        val h = Harness(text, EditorSelection.single(from - 2, to + 1), listOf(from to to))
        val f = h.field
        h.ime(f.text.replaceRange(f.selStart, f.selEnd, "PASTED"), f.selStart + 6)
        assertEquals(text.replaceRange(from - 2, to + 1, "PASTED"), h.doc)
        val v = EditorView(EditorState.create(text, EditorSelection.single(from - 2, to + 1), decorationsFacet.of(RangeSet.of(listOf(Ranged(from, to, Decoration.Replace(WidgetKey("fold", "f"), fold = true) as Decoration))))))
        v.paste("PASTED")
        assertEquals(text.replaceRange(from - 2, to + 1, "PASTED"), v.state.doc.toString())
    }

    @Test fun aRealU_FFFC_InTheDocumentRoundTrips() {
        val t = "obj $ph here\nmore"
        val h = Harness(t, EditorSelection.single(0, 10), emptyList())
        h.ime(h.field.text.substring(0, 10).uppercase() + h.field.text.substring(10), 0, 10)
        assertEquals("OBJ $ph HERE\nmore", h.doc, "a U+FFFC the document holds was lost")
    }

    @Test fun theViewRefusesAFieldEditThatInsertsAPlaceholder() {
        val v = EditorView(EditorState.create("abc"))
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(1, 1, "$ph")), userEvent = "input", annotations = listOf(EditorAnnotations.fieldInput.of(true))))
        assertEquals("abc", v.state.doc.toString(), "a field edit inserted U+FFFC")
        // The same text from anything else (a paste, a disk reload) is the document's.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(1, 1, "$ph")), userEvent = "paste"))
        assertEquals("a${ph}bc", v.state.doc.toString())
    }
}
