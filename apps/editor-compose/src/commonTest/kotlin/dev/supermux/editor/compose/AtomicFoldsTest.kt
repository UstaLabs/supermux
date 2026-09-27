package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.atomicRangesFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folds are atomic on EVERY input path (the hidden field, typeText, paste, key commands): no edit
 * takes a piece of one; one that reaches into it unfolds it (the default policy) and does nothing else.
 */
class AtomicFoldsTest {
    // "fun f() {" then a folded body "\n  body\n" up to "}".
    private val text = "fun a() {\n  one\n}\nfun f() {\n  body 1\n  body 2\n}\nend"
    private val foldFrom = text.indexOf("{\n  body") + 1
    private val foldTo = text.indexOf("}\nend")

    /** A fold plugin as M4's: folds in a field, an unfold effect, a reveal handler that unfolds. */
    private class FoldPlugin(initial: List<Pair<Int, Int>>, val atomic: Boolean = true) {
        val unfold = StateEffectType<Int>("unfold")
        val fold = StateEffectType<Pair<Int, Int>>("fold")
        var reveals = 0
        val field: StateField<RangeSet<Decoration>> = StateField(
            "folds",
            { RangeSet.of(initial.map { (a, b) -> Ranged(a, b, Decoration.Replace(WidgetKey("fold", "$a"), fold = atomic) as Decoration) }) },
            { v, tr ->
                var out = v.map(tr.changes)
                for (e in tr.effects) {
                    e.valueIf(unfold)?.let { at -> out = out.update(filter = { it.from != at }) }
                    e.valueIf(fold)?.let { (a, b) -> out = out.update(add = listOf(Ranged(a, b, Decoration.Replace(WidgetKey("fold", "$a"), fold = true)))) }
                }
                out
            },
            { f -> decorationsFacet.compute(FacetDep.field(f)) { it.field(f) } },
        )
        val extension = extensionOf(field, revealFacet.of(RevealHandler { t, from, _ ->
            reveals++
            t.dispatch(TransactionSpec(effects = listOf(unfold.of(from))))
            true
        }))
        fun folds(st: EditorState) = st.field(field).toList()
    }

    private class Harness(text: String, cursor: Int, val plugin: FoldPlugin, extra: dev.supermux.editor.core.Extension = extensionOf()) {
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(cursor), extensionOf(plugin.extension, extra)))
        val sync = FieldSync(view, 40, 4)
        var field: FieldText = sync.initialField()
        init { view.addListener { sync.onStateChange()?.let { field = it } } }
        fun ime(t: String, sel: Int) { field = FieldText(t, sel, sel); sync.onFieldChange(t, sel, sel, null)?.let { field = it } }
        fun backspace() { val a = field.selStart; ime(field.text.substring(0, a - 1) + field.text.substring(a), a - 1) }
        fun delete() { val a = field.selStart; ime(field.text.substring(0, a) + field.text.substring(a + 1), a) }
        val doc get() = view.state.doc.toString()
        val head get() = view.state.selection.main.head
    }

    @Test fun theFieldShowsOnePlaceholderForAFold() {
        val h = Harness(text, foldTo, FoldPlugin(listOf(foldFrom to foldTo)))
        assertTrue(h.field.text.contains("{" + FieldWindow.PLACEHOLDER + "}"), "the field holds the hidden text: ${h.field.text}")
        assertTrue(!h.field.text.contains("body"))
        assertEquals(h.field.text.indexOf(FieldWindow.PLACEHOLDER) + 1, h.field.selStart, "the caret is not after the placeholder")
    }

    @Test fun aSoftBackspaceAtAFoldsEndUnfoldsItAndDeletesNothing() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val h = Harness(text, foldTo, p)
        h.backspace()
        assertEquals(text, h.doc, "the soft Backspace deleted hidden text")
        assertEquals(1, p.reveals)
        assertTrue(p.folds(h.view.state).isEmpty(), "not unfolded")
        assertEquals(foldTo, h.head, "the caret moved")
        assertTrue(h.field.text.contains("body 2"), "the field does not show the unfolded text")
        // The next Backspace deletes normally: the newline before "}".
        h.backspace()
        assertEquals(text.removeRange(foldTo - 1, foldTo), h.doc)
    }

    @Test fun aSoftDeleteAtAFoldsStartUnfoldsIt() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val h = Harness(text, foldFrom, p)
        h.delete()
        assertEquals(text, h.doc)
        assertEquals(1, p.reveals)
        assertEquals(foldFrom, h.head)
    }

    @Test fun anAutocorrectAcrossAFoldsEdgeIsRefused() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val h = Harness(text, foldTo + 1, p)
        // The IME rewrites "{<fold>}" to "{}" (a replacement touching the placeholder).
        val t = h.field.text
        val at = t.indexOf(FieldWindow.PLACEHOLDER)
        h.ime(t.removeRange(at, at + 1), at + 1)
        assertEquals(text, h.doc)
        assertEquals(1, p.reveals)
        // An autocorrect next to the fold, not touching it, applies.
        val h2 = Harness("say teh{x}", 7, FoldPlugin(listOf(8 to 9)))
        h2.ime(h2.field.text.replace("teh", "the"), 7)
        assertEquals("say the{x}", h2.doc)
    }

    @Test fun aFieldCaretMoveLandsOnAFoldsEdgesNeverInside() {
        val h = Harness(text, 0, FoldPlugin(listOf(foldFrom to foldTo)))
        val ph = h.field.text.indexOf(FieldWindow.PLACEHOLDER)
        h.field = FieldText(h.field.text, ph, ph)
        h.sync.onFieldChange(h.field.text, ph, ph, null)
        assertEquals(foldFrom, h.head)
        h.sync.onFieldChange(h.field.text, ph + 1, ph + 1, null)
        assertEquals(foldTo, h.head)
    }

    @Test fun hardwareBackspaceTypeTextAndPasteNeverReachIntoAFold() {
        for (path in listOf("backspace", "delete", "paste-over")) {
            val p = FoldPlugin(listOf(foldFrom to foldTo))
            val start = if (path == "delete") foldFrom else foldTo
            val view = EditorView(EditorState.create(text, EditorSelection.cursor(start), p.extension))
            when (path) {
                "backspace" -> DefaultCommands.deleteBackward.run(view)
                "delete" -> DefaultCommands.deleteForward.run(view)
                // A user edit from any path whose range ends inside the fold (the selection did not cover it).
                else -> view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(foldFrom - 3, foldTo - 2, "P")), userEvent = "paste"))
            }
            assertEquals(text, view.state.doc.toString(), "$path deleted hidden text")
            assertEquals(1, p.reveals, "$path: not unfolded")
        }
    }

    @Test fun aSelectionCoveringAFoldDeletesItWithTheSelection() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val view = EditorView(EditorState.create(text, EditorSelection.single(foldFrom - 2, foldTo + 1), p.extension))
        DefaultCommands.deleteBackward.run(view)
        assertEquals(text.removeRange(foldFrom - 2, foldTo + 1), view.state.doc.toString())
        assertEquals(0, p.reveals)
    }

    @Test fun deleteWholeIsTheOtherPolicy() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), extensionOf(p.extension, atomicDeleteFacet.of(AtomicDelete.deleteWhole))))
        DefaultCommands.deleteBackward.run(view)
        assertEquals(text.removeRange(foldFrom, foldTo), view.state.doc.toString(), "not deleted whole")
        assertEquals(0, p.reveals)
    }

    @Test fun aNonFoldReplaceIsAtomicOnlyWhenItOptsIn() {
        val plain = FoldPlugin(listOf(foldFrom to foldTo), atomic = false)
        val v1 = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), plain.extension))
        DefaultCommands.deleteBackward.run(v1)
        assertEquals(text.removeRange(foldTo - 1, foldTo), v1.state.doc.toString(), "a plain Replace was treated as atomic")
        // The same range listed in atomicRangesFacet: atomic.
        val listed = FoldPlugin(listOf(foldFrom to foldTo), atomic = false)
        val atomic = atomicRangesFacet.of(RangeSet.of(listOf(Ranged(foldFrom, foldTo, Unit))))
        val v2 = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), extensionOf(listed.extension, atomic)))
        DefaultCommands.deleteBackward.run(v2)
        assertEquals(text, v2.state.doc.toString())
        assertEquals(1, listed.reveals)
    }

    @Test fun multiCursorNextToAFold() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val other = text.indexOf("one") + 3
        val view = EditorView(EditorState.create(text, EditorSelection.create(listOf(SelectionRange(other), SelectionRange(foldTo)), 1), p.extension))
        DefaultCommands.deleteBackward.run(view)
        // One cursor reached into the fold: the keystroke only unfolds.
        assertEquals(text, view.state.doc.toString())
        assertEquals(1, p.reveals)
        // Typing at both cursors after the unfold works at both.
        view.typeText("X")
        assertEquals(2, Regex("X").findAll(view.state.doc.toString()).count())
    }

    @Test fun aCursorInsideARangeWhenItFoldsIsMovedOut() {
        val p = FoldPlugin(emptyList())
        val inside = text.indexOf("body 1") + 2
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(inside), p.extension))
        view.dispatch(TransactionSpec(effects = listOf(p.fold.of(foldFrom to foldTo))))
        val head = view.state.selection.main.head
        assertTrue(head == foldFrom || head == foldTo, "the caret stayed inside the fold: $head")
    }

    @Test fun aSelectionScrolledIntoAFoldAsksToRevealIt() {
        val p = FoldPlugin(listOf(foldFrom to foldTo))
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), p.extension))
        val inside = text.indexOf("body 2")
        // A search jumping to a match inside the fold.
        view.dispatch(TransactionSpec(selection = EditorSelection.single(inside, inside + 4), scrollIntoView = true))
        assertEquals(1, p.reveals)
        assertTrue(p.folds(view.state).isEmpty())
        assertEquals(SelectionRange(inside, inside + 4), view.state.selection.main, "the match was not kept")
        // An arrow key into a fold is moved out (a move is not a reveal).
        val p2 = FoldPlugin(listOf(foldFrom to foldTo))
        val v2 = EditorView(EditorState.create(text, EditorSelection.cursor(foldFrom), p2.extension))
        v2.dispatch(TransactionSpec(selection = EditorSelection.cursor(foldFrom + 1), userEvent = "select"))
        assertEquals(foldTo, v2.state.selection.main.head)
        assertEquals(0, p2.reveals)
    }
}
