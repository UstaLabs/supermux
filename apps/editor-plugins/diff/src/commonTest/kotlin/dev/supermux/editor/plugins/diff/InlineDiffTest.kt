package dev.supermux.editor.plugins.diff

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InlineDiffTest {
    private val base = (1..100).joinToString("\n") { "line $it" }

    /** [base] with line 50 changed, a line inserted after 70, and lines 90-91 deleted. */
    private val working = base.split('\n').toMutableList().also {
        it[49] = "line 50 changed"
        it.add(70, "inserted")
        it.removeAt(90); it.removeAt(90)
    }.joinToString("\n")

    private fun view(text: String = working, config: DiffConfig = DiffConfig(), vararg ext: Extension) =
        EditorView(EditorState.create(text, extensions = extensionOf(inlineDiff(base, config), *ext)))

    private fun <T> set(v: EditorView, facet: dev.supermux.editor.core.Facet<*, List<dev.supermux.editor.core.RangeSet<T>>>) =
        v.state.facet(facet).flatMap { it.toList() }

    /** Decorations of the whole document (the viewport window over everything). */
    private fun EditorView.showAll() = dispatch(TransactionSpec(effects = listOf(EditorViewport.set.of(0 until state.doc.length))))

    @Test fun hunksAreTheLineDiff() {
        val v = view()
        val m = assertNotNull(Diff.model(v.state))
        assertTrue(m.ready && !m.pending)
        assertEquals(listOf(DiffHunk(49, 50, 49, 50), DiffHunk(70, 70, 70, 71), DiffHunk(89, 91, 90, 90)), m.hunks.map { it.copy(chars = null) })
        assertEquals(listOf(CharChange(7, 7, 7, 15)), m.hunks[0].chars, "' changed' inserted")
    }

    @Test fun decorationsMatchTheHunks() {
        val v = view(config = DiffConfig(collapseUnchanged = false))
        v.showAll()
        val doc = v.state.doc
        val decos = set(v, decorationsFacet)
        fun lineClasses(line: Int) = decos.filter { it.from == doc.lineStart(line) && it.value is Decoration.LineStyle }.flatMap { (it.value as Decoration.LineStyle).classes }
        assertEquals(listOf("diff-change"), lineClasses(49))
        assertEquals(listOf("diff-add"), lineClasses(70))
        assertEquals(emptyList(), lineClasses(48))
        // The changed characters inside line 50.
        val marks = decos.filter { it.value is Decoration.Mark }
        assertEquals(listOf(doc.lineStart(49) + 7 to doc.lineStart(49) + 15), marks.map { it.from to it.to })
        assertEquals(" changed", doc.slice(marks[0].from, marks[0].to))
        // The deleted lines: a block widget above their place (line 91 of the working copy, 0-based 90), and the change's old line above line 50.
        val blocks = decos.filter { it.value is Decoration.BlockWidget }.map { it.from to (it.value as Decoration.BlockWidget) }
        assertEquals(listOf(doc.lineStart(49) to "d49", doc.lineStart(90) to "d89"), blocks.map { it.first to it.second.key.id })
        assertTrue(blocks.all { it.second.above && it.second.key.type == Diff.DELETED })
        // Gutter: bars on the changed and added lines, a remove bar where lines went, revert arrows.
        val markers = set(v, gutterMarkersFacet)
        fun kinds(line: Int) = markers.filter { it.from == doc.lineStart(line) }.map { it.value.column to it.value.kind }
        assertEquals(listOf("diff" to "diff-change", "revert" to "diff-revert"), kinds(49))
        assertEquals(listOf("diff" to "diff-add", "revert" to "diff-revert"), kinds(70))
        assertEquals(listOf("diff" to "diff-remove", "revert" to "diff-revert"), kinds(90))
    }

    @Test fun unchangedRunsFoldBehindAWidgetAndExpandBy20() {
        val v = view(config = DiffConfig(context = 3, expandStep = 20))
        val m0 = Diff.model(v.state)!!
        assertEquals(listOf(
            CollapsedRun(0, 46, 0, atStart = true, atEnd = false),
            CollapsedRun(53, 67, 53, atStart = false, atEnd = false),
            CollapsedRun(74, 87, 73, atStart = false, atEnd = false),
            // Lines 90..98 after the deletion: 93..98 fold (6 lines, the document's end).
            CollapsedRun(93, 99, 94, atStart = false, atEnd = true),
        ), m0.collapsed)
        val replaces = set(v, decorationsFacet).filter { it.value is Decoration.Replace }
        assertEquals(4, replaces.size)
        assertTrue(replaces.all { (it.value as Decoration.Replace).atomic })
        val doc = v.state.doc
        assertEquals(doc.lineStart(0) to doc.lineStart(46) - 1, replaces[0].from to replaces[0].to)
        // ↑ 20 on the first run reveals its last 20 lines; all reveals the rest.
        Diff.expand(v, m0.collapsed[0], Expand.UP)
        assertEquals(CollapsedRun(0, 26, 0, true, false), Diff.model(v.state)!!.collapsed[0])
        Diff.expand(v, Diff.model(v.state)!!.collapsed[0], Expand.DOWN)
        assertEquals(CollapsedRun(20, 26, 20, false, false), Diff.model(v.state)!!.collapsed[0])
        Diff.expand(v, Diff.model(v.state)!!.collapsed[0], Expand.ALL)
        assertEquals(53, Diff.model(v.state)!!.collapsed[0].bFrom)
    }

    @Test fun expandedContextSurvivesEditsButNotANewSlice() {
        val v = view(config = DiffConfig(context = 3))
        Diff.expand(v, Diff.model(v.state)!!.collapsed[0], Expand.ALL)
        assertEquals(3, Diff.model(v.state)!!.collapsed.size)
        // Typing near the end moves nothing that was expanded; the recompute keeps it open.
        val at = v.state.doc.lineStart(91)
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "x")), userEvent = "input.type"))
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "top\n")), userEvent = "input.type"))
        assertTrue(Diff.model(v.state)!!.collapsed.none { it.bFrom < 40 }, "${Diff.model(v.state)!!.collapsed}")
        // A new pair (the host loads other texts): folded again.
        Diff.load(v, base, working)
        assertEquals(0, Diff.model(v.state)!!.collapsed[0].bFrom)
    }

    @Test fun anEditReDiffsAtOnce() {
        val v = view(config = DiffConfig(collapseUnchanged = false))
        val at = v.state.doc.lineStart(10)
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "new ")), userEvent = "input.type"))
        val m = Diff.model(v.state)!!
        assertFalse(m.pending)
        assertEquals(DiffHunk(10, 11, 10, 11), m.hunks[0].copy(chars = null))
        assertValidDiff(base.split('\n'), v.state.doc.toString().split('\n'), m.hunks)
        // Undoing the edit by hand removes the hunk.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at + 4)), userEvent = "delete.backward"))
        assertEquals(3, Diff.model(v.state)!!.hunks.size)
    }

    @Test fun revertIsOneUndoStepForEveryKindOfHunk() {
        val v = view(ext = arrayOf(history()))
        val baseLines = base.split('\n')
        for (i in 0 until 3) {
            val h = Diff.model(v.state)!!.hunks.first()
            assertTrue(Diff.revert(v, h))
            assertValidDiff(baseLines, v.state.doc.toString().split('\n'), Diff.model(v.state)!!.hunks)
            assertEquals(2 - i, Diff.model(v.state)!!.hunks.size)
        }
        assertEquals(base, v.state.doc.toString())
        assertEquals(3, History.undoDepth(v.state))
        assertTrue(History.undo.run(v))
        assertEquals(1, Diff.model(v.state)!!.hunks.size, "one undo brings one hunk back")
        History.undo.run(v); History.undo.run(v)
        assertEquals(working, v.state.doc.toString())
    }

    @Test fun revertAtTheEdgesOfTheDocument() {
        fun check(b: String, w: String) {
            val v = EditorView(EditorState.create(w, extensions = inlineDiff(b)))
            while (true) { val h = Diff.model(v.state)!!.hunks.firstOrNull() ?: break; assertTrue(Diff.revert(v, h)) }
            assertEquals(b, v.state.doc.toString(), "'$w' reverted to '$b'")
        }
        check("a\nb", "a")       // deleted at the end
        check("a", "a\nb")       // inserted at the end
        check("a\nb", "b")       // deleted at the start
        check("b", "a\nb")       // inserted at the start
        check("x", "")           // everything
        check("", "y\nz")
    }

    @Test fun theRevertArrowRevertsFromTheGutter() {
        var reverted: DiffHunk? = null
        val v = EditorView(EditorState.create(working, extensions = inlineDiff(base, host = object : DiffHost { override fun onRevert(hunk: DiffHunk) { reverted = hunk } })))
        val handled = v.state.facet(gutterClickFacet).any { it.click(v, Diff.REVERT_COLUMN, 70, null) }
        assertTrue(handled)
        assertEquals(DiffHunk(70, 70, 70, 71), reverted)
        assertEquals(2, Diff.model(v.state)!!.hunks.size)
        assertFalse(v.state.doc.toString().contains("inserted"))
    }

    @Test fun readOnlyOffersNoRevert() {
        val v = view(config = DiffConfig(editable = false))
        v.showAll()
        assertTrue(set(v, gutterMarkersFacet).none { it.value.column == Diff.REVERT_COLUMN })
        assertFalse(Diff.revert(v, Diff.model(v.state)!!.hunks[0]))
    }

    @Test fun nextAndPreviousHunkWrapAround() {
        val v = view()
        fun key(s: String) = runKey(v, KeyChord.parse(s, isApplePlatform), isApplePlatform)
        fun line() = v.state.doc.lineIndexAt(v.state.selection.main.head)
        assertTrue(key("F7")); assertEquals(49, line())
        assertTrue(key("F7")); assertEquals(70, line())
        assertTrue(key("Alt-F5")); assertEquals(90, line(), "the deletion's line")
        assertTrue(key("F7")); assertEquals(49, line(), "wrapped")
        assertTrue(key("Shift-F7")); assertEquals(90, line(), "wrapped back")
        assertTrue(key("Shift-Alt-F5")); assertEquals(70, line())
    }

    @Test fun aBigFirstDiffAndABigEditRunInTheBackground() = runTest {
        val rnd = kotlin.random.Random(4)
        val a = randomLines(rnd, 3_000)
        val b = mutate(rnd, a, 100)
        val v = EditorView(EditorState.create(b.joinToString("\n"), extensions = inlineDiff(a.joinToString("\n"), DiffConfig(syncLines = 1_000, offThread = false))))
        assertFalse(Diff.model(v.state)!!.ready, "too big for the transaction")
        v.startPlugins(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        val m = Diff.model(v.state)!!
        assertTrue(m.ready && !m.pending)
        assertEquals(LineDiff.diff(a, b).hunks, m.hunks)
        // A paste of 5,000 lines: coarse at once (the mapping stays right), refined in the background.
        val paste = randomLines(rnd, 5_000).joinToString("\n") + "\n"
        val at = v.state.doc.lineStart(10)
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, paste)), selection = EditorSelection.cursor(at)))
        assertTrue(Diff.model(v.state)!!.pending)
        assertValidDiff(a, v.state.doc.toString().split('\n'), Diff.model(v.state)!!.hunks)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(Diff.idle(v.state))
        assertEquals(LineDiff.diff(a, v.state.doc.toString().split('\n')).hunks, Diff.model(v.state)!!.hunks)
    }
}
