package dev.supermux.editor.plugins.diff

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.lineMappingFacet
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SideBySideTest {
    private val base = (1..100).joinToString("\n") { "line $it" }
    private val working = base.split('\n').toMutableList().also {
        it[49] = "line 50 changed"
        it.add(70, "inserted")
        it.removeAt(90); it.removeAt(90)
    }.joinToString("\n")

    private fun pair(config: DiffConfig = DiffConfig()): DiffPair {
        val a = EditorView(EditorState.create(base))
        val b = EditorView(EditorState.create(working, extensions = history()))
        return DiffPair(a, b, config)
    }

    private fun EditorView.showAll() = dispatch(TransactionSpec(effects = listOf(EditorViewport.set.of(0 until state.doc.length))))
    private fun EditorView.decos() = state.facet(decorationsFacet).flatMap { it.toList() }
    private fun EditorView.lineClasses(line: Int) = decos().filter { it.from == state.doc.lineStart(line) && it.value is Decoration.LineStyle }.flatMap { (it.value as Decoration.LineStyle).classes }

    @Test fun bothSidesAreSetUpAndBCarriesTheMapping() {
        val p = pair(DiffConfig(collapseUnchanged = false))
        p.base.showAll(); p.working.showAll()
        assertEquals(LineMapping(listOf(LineMapping.Hunk(49, 50, 49, 50), LineMapping.Hunk(70, 70, 70, 71), LineMapping.Hunk(89, 91, 90, 90))), p.working.state.facet(lineMappingFacet))
        // A: the replaced and deleted base lines are red, the changed characters of a change marked; no deleted-lines widget.
        assertEquals(listOf("diff-remove"), p.base.lineClasses(49))
        assertEquals(listOf("diff-remove"), p.base.lineClasses(89))
        assertEquals(listOf("diff-remove"), p.base.lineClasses(90))
        assertTrue(p.base.decos().none { it.value is Decoration.BlockWidget })
        assertTrue(p.working.decos().none { it.value is Decoration.BlockWidget }, "side by side: the deleted lines are A's")
        // B: changed amber, inserted green, char marks on both sides.
        assertEquals(listOf("diff-change"), p.working.lineClasses(49))
        assertEquals(listOf("diff-add"), p.working.lineClasses(70))
        // " changed" was inserted: nothing to mark on A, the insertion marked on B.
        assertTrue(p.base.decos().none { it.value is Decoration.Mark })
        val markB = p.working.decos().single { it.value is Decoration.Mark }
        assertEquals(" changed", p.working.state.doc.slice(markB.from, markB.to))
        // Revert arrows only in B's gutter.
        assertTrue(p.base.state.facet(gutterMarkersFacet).flatMap { it.toList() }.none { it.value.column == Diff.REVERT_COLUMN })
        assertEquals(3, p.working.state.facet(gutterMarkersFacet).flatMap { it.toList() }.count { it.value.column == Diff.REVERT_COLUMN })
    }

    @Test fun anEditOfBUpdatesTheMappingAndA() {
        val p = pair()
        val at = p.working.state.doc.lineStart(20)
        p.working.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "new line\n"))))
        val m = assertNotNull(p.model)
        assertEquals(LineMapping.Hunk(20, 20, 20, 21), p.working.state.facet(lineMappingFacet)!!.hunks.first())
        assertTrue(p.base.state.field(Diff.baseField) === m, "A follows B's model")
        assertValidDiff(base.split('\n'), p.working.state.doc.toString().split('\n'), m.hunks)
    }

    @Test fun foldedRunsAreTheSameOnBothSidesAndExpandTogether() {
        val p = pair(DiffConfig(context = 3))
        fun replaces(v: EditorView) = v.decos().filter { it.value is Decoration.Replace }.map { v.state.doc.lineIndexAt(it.from) to v.state.doc.lineIndexAt(it.to) }
        // The last run: B lines 93..98 pair with A lines 94..99 (two lines deleted, one inserted above).
        assertEquals(listOf(0 to 45, 53 to 66, 74 to 86, 93 to 98), replaces(p.working))
        assertEquals(listOf(0 to 45, 53 to 66, 73 to 85, 94 to 99), replaces(p.base))
        // Expanding on A (its widget's action) expands both.
        val run = p.model!!.collapsed[1]
        Diff.expand(p.base, run, Expand.ALL)
        assertEquals(listOf(0 to 45, 74 to 86, 93 to 98), replaces(p.working))
        assertEquals(listOf(0 to 45, 73 to 85, 94 to 99), replaces(p.base))
    }

    @Test fun theRevertArrowRevertsIntoBAsOneUndoStep() {
        val p = pair()
        assertTrue(p.working.state.facet(gutterClickFacet).any { it.click(p.working, Diff.REVERT_COLUMN, 49, null) })
        assertEquals(2, p.model!!.hunks.size)
        assertEquals("line 50", p.working.state.doc.line(50).text)
        assertEquals(2, p.base.state.field(Diff.baseField)!!.hunks.size, "A follows")
        assertTrue(History.undo.run(p.working))
        assertEquals("line 50 changed", p.working.state.doc.line(50).text)
        assertEquals(3, p.model!!.hunks.size)
    }

    @Test fun nextHunkWorksOnBothSides() {
        val p = pair()
        assertTrue(Diff.nextHunk.run(p.base))
        assertEquals(49, p.base.state.doc.lineIndexAt(p.base.state.selection.main.head))
        Diff.nextHunk.run(p.base); Diff.nextHunk.run(p.base)
        assertEquals(89, p.base.state.doc.lineIndexAt(p.base.state.selection.main.head), "A's own lines")
    }

    @Test fun loadingNewTextsIsANewSlice() {
        val p = pair()
        Diff.expand(p.working, p.model!!.collapsed[0], Expand.ALL)
        p.load("x\ny\nz", "x\nY\nz")
        assertEquals("x\ny\nz", p.base.state.doc.toString())
        assertEquals(listOf(DiffHunk(1, 2, 1, 2)), p.model!!.hunks.map { it.copy(chars = null) })
        assertEquals(p.model, p.base.state.field(Diff.baseField))
        assertEquals(0, History.undoDepth(p.working.state), "a load is never undone")
    }
}
