package dev.supermux.editor.plugins.fold

import dev.supermux.editor.compose.DefaultCommands
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.widgetClickFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FoldRange
import dev.supermux.editor.core.FoldService
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.foldServiceFacet
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class FoldTest {
    private val text = "fun a() {\n    one\n    two\n}\n\nfun b() {\n    three\n}\nend\n"
    private val aLineEnd = text.indexOf('\n')
    private val aTo = text.indexOf("\n}\n\nfun b") + 1 // the "}" of a()

    private fun view(t: String = text, cursor: Int = 0, vararg ext: Extension) =
        EditorView(EditorState.create(t, EditorSelection.cursor(cursor), extensionOf(fold(), *ext)))

    private fun EditorView.folds() = Fold.folded(state)
    private val EditorView.doc get() = state.doc.toString()

    // ------------------------------------------------------------------------ fold ranges --

    @Test fun indentationGivesLineBasedRanges() {
        val st = EditorState.create(text)
        // "fun a() {": hides from its end to the end of the last deeper line ("    two").
        assertEquals(FoldRange(aLineEnd, text.indexOf("two") + 3), Fold.foldable(st, 0, aLineEnd))
        // A line with nothing deeper after it does not fold; neither does a blank one.
        val oneEnd = text.indexOf("one") + 3
        assertEquals(null, Fold.foldable(st, oneEnd - 7, oneEnd))
        val blank = text.indexOf("\n\n") + 1
        assertEquals(null, Fold.foldable(st, blank, blank))
    }

    @Test fun aLanguageFoldServiceComesFirst() {
        val service = foldServiceFacet.of(FoldService { _, lf, _ -> if (lf == 0) FoldRange(aLineEnd, aTo) else null })
        val st = EditorState.create(text, extensions = service)
        assertEquals(FoldRange(aLineEnd, aTo), Fold.foldable(st, 0, aLineEnd))
        // Where it has nothing, indentation still answers.
        val b = text.indexOf("fun b")
        assertNotNull(Fold.foldable(st, b, text.indexOf('\n', b)))
    }

    @Test fun whereTheLanguageHasParsedIndentationIsNotMixedIn() {
        // The service knows every line and folds only a(): b() gets no indentation fold (and no arrow).
        val service = foldServiceFacet.of(object : FoldService {
            override fun foldable(state: EditorState, lineFrom: Int, lineTo: Int) = if (lineFrom == 0) FoldRange(aLineEnd, aTo) else null
            override fun knows(state: EditorState, lineFrom: Int) = true
        })
        val v = view(text, 0, service)
        val b = text.indexOf("fun b")
        assertEquals(null, Fold.foldable(v.state, b, text.indexOf('\n', b)))
        assertEquals(listOf(0), markers(v).map { it.from })
    }

    // ------------------------------------------------------------------ commands and keys --

    @Test fun foldAndUnfoldAtTheCursor() {
        val v = view(cursor = 2)
        assertTrue(Fold.foldCode.run(v))
        assertEquals(listOf(FoldRange(aLineEnd, text.indexOf("two") + 3)), v.folds())
        val replace = v.state.field(Fold.field).first().value as Decoration.Replace
        assertTrue(replace.fold)
        assertEquals(Fold.WIDGET_TYPE, replace.widget?.type)
        assertTrue(Fold.unfoldCode.run(v))
        assertTrue(v.folds().isEmpty())
        assertFalse(Fold.unfoldCode.run(v), "nothing left to unfold")
        // toggle does both.
        Fold.toggleFold.run(v); assertEquals(1, v.folds().size)
        Fold.toggleFold.run(v); assertEquals(0, v.folds().size)
        assertEquals(text, v.doc)
    }

    @Test fun cm6sFoldKeys() {
        val v = view(cursor = 2)
        assertTrue(runKey(v, KeyChord("[", ctrl = true, shift = true), apple = false))
        assertEquals(1, v.folds().size)
        assertTrue(runKey(v, KeyChord("]", meta = true, alt = true), apple = true))
        assertEquals(0, v.folds().size)
        assertTrue(runKey(v, KeyChord("[", ctrl = true, alt = true), apple = false))
        assertEquals(2, v.folds().size, "fold all")
        assertTrue(runKey(v, KeyChord("]", ctrl = true, alt = true), apple = true))
        assertEquals(0, v.folds().size, "unfold all")
    }

    @Test fun foldAllFoldsTopLevelRangesAndUnfoldAllClears() {
        val v = view()
        assertTrue(Fold.foldAll.run(v))
        assertEquals(2, v.folds().size)
        assertTrue(Fold.unfoldAll.run(v))
        assertTrue(v.folds().isEmpty())
    }

    /** Correct on every platform; the time budget is asserted on the JVM (FoldPerfTest: iOS tests are Debug builds). */
    @Test fun foldAllOn10kLinesFoldsEveryTopLevelBlock() {
        val v = view(FoldPerf.bigText)
        val ms = FoldPerf.bestOf3(v)
        println("PERF foldAll over 10,500 lines: best of 3 $ms ms")
        Fold.foldAll.run(v)
        assertEquals(1500, v.folds().size)
    }

    // ------------------------------------------------------------------ gutter and chip --

    private fun markers(v: EditorView) = v.state.facet(gutterMarkersFacet).flatMap { it.toList() }.filter { it.value.column == Fold.COLUMN }

    @Test fun theGutterShowsOpenAndClosedArrowsAndAClickToggles() {
        val v = view()
        val open = markers(v)
        assertEquals(listOf(0, text.indexOf("fun b")), open.map { it.from })
        assertTrue(open.all { it.value.kind == "fold-open" })
        val click = v.state.facet(gutterClickFacet).first()
        assertTrue(click.click(v, Fold.COLUMN, 0, open[0].value))
        assertEquals(1, v.folds().size)
        assertEquals("fold-closed", markers(v).first { it.from == 0 }.value.kind)
        assertTrue(v.state.facet(gutterClickFacet).first().click(v, Fold.COLUMN, 0, markers(v).first().value))
        assertTrue(v.folds().isEmpty())
        // Another column is not the fold plugin's.
        assertFalse(v.state.facet(gutterClickFacet).first().click(v, "lint", 0, null))
    }

    @Test fun markersStayInTheViewport() {
        val big = "fun f() {\n    x\n}\n".repeat(2000)
        val v = view(big)
        v.dispatch(TransactionSpec(effects = listOf(EditorViewport.set.of(big.length / 2 until big.length / 2 + 600))))
        val m = markers(v)
        assertTrue(m.size in 1..60, "markers outside the viewport: ${m.size}")
        assertTrue(m.all { it.from >= big.length / 2 - 20 && it.from <= big.length / 2 + 600 })
    }

    @Test fun clickingThePlaceholderUnfolds() {
        val v = view(cursor = 2)
        Fold.foldCode.run(v)
        val f = v.folds().single()
        val handler = v.state.facet(widgetClickFacet).first()
        assertFalse(handler.click(v, WidgetKey("thread", "t1"), f.from, f.to), "another widget type is not the fold plugin's")
        assertTrue(handler.click(v, WidgetKey(Fold.WIDGET_TYPE, "x"), f.from, f.to))
        assertTrue(v.folds().isEmpty())
    }

    // --------------------------------------------------------------------- edits and folds --

    @Test fun foldsSurviveEditsOutsideThem() {
        val v = view(cursor = 2)
        Fold.foldCode.run(v)
        val before = v.folds().single()
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "// top\n")), userEvent = "input"))
        assertEquals(FoldRange(before.from + 7, before.to + 7), v.folds().single(), "an edit above did not move the fold")
        val endAt = v.state.doc.length
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(endAt, endAt, "more\n")), userEvent = "input"))
        assertEquals(FoldRange(before.from + 7, before.to + 7), v.folds().single(), "an edit below moved the fold")
    }

    @Test fun remoteAgentLspAndProgrammaticEditsInsideAFoldKeepIt() {
        for (spec in listOf(
            { at: Int -> TransactionSpec(changes = listOf(ChangeSpec(at, at, "X")), userEvent = "agent") },
            { at: Int -> TransactionSpec(changes = listOf(ChangeSpec(at, at, "X")), userEvent = "lsp") },
            { at: Int -> TransactionSpec(changes = listOf(ChangeSpec(at, at, "X")), userEvent = "disk") },
            { at: Int -> TransactionSpec(changes = listOf(ChangeSpec(at, at, "X")), userEvent = "input", annotations = listOf(dev.supermux.editor.compose.EditorAnnotations.remote.of(true))) },
            { at: Int -> TransactionSpec(changes = listOf(ChangeSpec(at, at, "X"))) },
        )) {
            val v = view(cursor = 2)
            Fold.foldCode.run(v)
            val f = v.folds().single()
            val s = spec(f.from + 6)
            v.dispatch(s)
            assertTrue(v.doc.contains("X"))
            assertEquals(listOf(FoldRange(f.from, f.to + 1)), v.folds(), "${s.userEvent}: the fold opened (or did not grow with the edit)")
        }
    }

    @Test fun aRemoteDeleteAcrossAFoldsFirstLineKeepsItLineBased() {
        // An agent deletes from the middle of "fun a() {" into the hidden text: the fold would start
        // mid-line; it snaps to the end of its (new) first line, or goes when nothing is left.
        val v = view(cursor = 2)
        Fold.foldCode.run(v)
        val f = v.folds().single()
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(3, f.from + 6)), userEvent = "agent"))
        val doc = v.state.doc
        for (g in v.folds()) {
            assertTrue(g.from == doc.length || doc.charAt(g.from) == '\n', "a fold starts mid-line: $g in '${doc}'")
            assertTrue(g.to > g.from)
        }
        // Deleting the whole first line's end and all the fold's lines but the last: nothing left.
        val w = view(cursor = 2)
        Fold.foldCode.run(w)
        val h = w.folds().single()
        w.dispatch(TransactionSpec(changes = listOf(ChangeSpec(2, h.to - 1)), userEvent = "agent"))
        for (g in w.folds()) assertTrue(w.state.doc.charAt(g.from) == '\n' && g.to > g.from)
    }

    @Test fun aLocalDeleteTouchingAFoldClearsIt() {
        // CM6's rule (clearTouchedFolds for user deletes), for local edits that reach a fold's text
        // without covering it (the surface normally refuses them; the field is the last word).
        val st0 = EditorState.create(text, EditorSelection.cursor(2), fold())
        val st1 = st0.update(TransactionSpec(effects = listOf(Fold.foldEffect.of(FoldRange(aLineEnd, text.indexOf("two") + 3))))).state
        val f = Fold.folded(st1).single()
        val st2 = st1.update(TransactionSpec(changes = listOf(ChangeSpec(f.to - 2, f.to + 1)), userEvent = "delete.forward")).state
        assertTrue(Fold.folded(st2).isEmpty())
    }

    @Test fun aSelectionScrolledIntoAFoldUnfoldsItAndStays() {
        val v = view(cursor = 0)
        Fold.foldAll.run(v)
        val inside = text.indexOf("two")
        // What a search does: the match selected, scrolled into view.
        v.dispatch(TransactionSpec(selection = EditorSelection.single(inside, inside + 3), scrollIntoView = true))
        assertEquals(1, v.folds().size, "the fold with the match did not open (or the other one did)")
        assertEquals(inside to inside + 3, v.state.selection.main.let { it.from to it.to })
    }

    // ---------------------------------------------------------------- deletion and undo --

    @Test fun backspaceAtAFoldsEndUnfoldsByDefaultAndUndoRestores() {
        val v = view(text, 2, history())
        Fold.foldCode.run(v)
        val f = v.folds().single()
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(f.to), userEvent = "select"))
        DefaultCommands.deleteBackward.run(v)
        assertEquals(text, v.doc, "the first Backspace deleted hidden text")
        assertTrue(v.folds().isEmpty(), "not unfolded")
        DefaultCommands.deleteBackward.run(v)
        assertEquals(text.removeRange(f.to - 1, f.to), v.doc)
        History.undo.run(v)
        assertEquals(text, v.doc)
    }

    @Test fun deleteFoldWholeIsAnOptionAndUndoBringsTheTextBack() {
        val v = EditorView(EditorState.create(text, EditorSelection.cursor(2), extensionOf(fold(FoldConfig(deleteFoldWhole = true)), history())))
        Fold.foldCode.run(v)
        val f = v.folds().single()
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(f.to), userEvent = "select"))
        DefaultCommands.deleteBackward.run(v)
        assertEquals(text.removeRange(f.from, f.to), v.doc, "the fold was not deleted whole")
        assertTrue(v.folds().isEmpty())
        History.undo.run(v)
        assertEquals(text, v.doc, "undo did not restore the fold's text")
    }

    @Test fun undoOfAWholeFoldDeleteRestoresTheFoldToo() {
        val v = EditorView(EditorState.create(text, EditorSelection.cursor(2), extensionOf(fold(FoldConfig(deleteFoldWhole = true)), history())))
        Fold.foldCode.run(v)
        val f = v.folds().single()
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(f.to), userEvent = "select"))
        DefaultCommands.deleteBackward.run(v)
        assertTrue(v.folds().isEmpty())
        History.undo.run(v)
        assertEquals(text, v.doc)
        assertEquals(listOf(f), v.folds(), "undo did not fold it again")
        History.redo.run(v)
        assertTrue(v.folds().isEmpty())
        assertEquals(text.removeRange(f.from, f.to), v.doc)
    }

    @Test fun undoRestoresAFoldALocalEditOpenedByReachingIntoIt() {
        // A replace all lets its edit into folds (EditorAnnotations.atomicWhole): the fold opens with
        // that same transaction, and its undo folds it again.
        val v = view(text, 2, history())
        Fold.foldCode.run(v)
        val f = v.folds().single()
        val at = text.indexOf("one")
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at + 3, "ONE")), userEvent = "input.replace.all",
            annotations = listOf(dev.supermux.editor.compose.EditorAnnotations.atomicWhole.of(true))))
        assertEquals(text.replace("one", "ONE"), v.doc)
        assertTrue(v.folds().isEmpty(), "the fold holding the edit opened")
        History.undo.run(v)
        assertEquals(text, v.doc)
        assertEquals(listOf(f), v.folds(), "undo did not fold it again")
    }

    @Test fun undoAcrossAFoldIsNotPoliced() {
        // Type inside a block, fold it, undo: the undo edits hidden text; it applies, the fold stays (CM6).
        val v = view(text, text.indexOf("two") + 3, history())
        v.typeText("!")
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(2), userEvent = "select"))
        Fold.foldCode.run(v)
        assertEquals(1, v.folds().size)
        History.undo.run(v)
        assertEquals(text, v.doc)
        assertEquals(1, v.folds().size)
    }
}

/** foldAll over 10,500 lines (1,500 top-level classes), the best of three runs after a warm-up. */
internal object FoldPerf {
    val bigText: String = "class C {\n    fun f() {\n        if (x) {\n            y()\n        }\n    }\n}\n".repeat(1500)

    fun bestOf3(v: EditorView): Long {
        Fold.foldAll.run(v); Fold.unfoldAll.run(v)
        var best = Long.MAX_VALUE
        repeat(3) {
            val t = TimeSource.Monotonic.markNow()
            Fold.foldAll.run(v)
            best = minOf(best, t.elapsedNow().inWholeMilliseconds)
            check(Fold.folded(v.state).size == 1500)
            Fold.unfoldAll.run(v)
        }
        return best
    }
}
