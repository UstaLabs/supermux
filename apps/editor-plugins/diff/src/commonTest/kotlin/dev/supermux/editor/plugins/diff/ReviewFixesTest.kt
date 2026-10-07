package dev.supermux.editor.plugins.diff

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The M4d review's findings, each a regression test. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {
    private val base = (1..100).joinToString("\n") { "line $it" }
    private val working = base.split('\n').toMutableList().also {
        it[49] = "line 50 changed"
        it.add(70, "inserted")
        it.removeAt(90); it.removeAt(90)
    }.joinToString("\n")

    private fun view(config: DiffConfig = DiffConfig(), host: DiffHost? = null, text: String = working) =
        EditorView(EditorState.create(text, extensions = extensionOf(inlineDiff(base, config, host), review(host))))

    private fun thread(id: String, line: Int) = ReviewThread(id, line, comments = listOf(ReviewComment("$id-1", "user", "hi")))

    // ------------------------------------------------------------------ 3: threads never fold away --

    @Test fun threadAndComposerLinesStayUnfolded() {
        val v = view()
        assertEquals(0 until 46, Diff.collapsed(v.state).first().let { it.bFrom until it.bTo })
        Review.setThreads(v, listOf(thread("t", 20)))
        Review.setComposer(v, ReviewComposer(40, "", focus = false))
        val runs = Diff.collapsed(v.state)
        // Line 20 ± 3 and line 40 ± 3 stay open; the pieces between still fold.
        assertEquals(listOf(0 until 17, 24 until 37), runs.take(2).map { it.bFrom until it.bTo })
        assertTrue(runs.none { r -> 20 in r.bFrom until r.bTo || 40 in r.bFrom until r.bTo })
        assertTrue(runs.all { it.comments == 0 })
        // The widgets are on visible lines: no Replace hides them.
        val replaces = v.state.facet(decorationsFacet).flatMap { it.toList() }.filter { it.value is Decoration.Replace }
        val doc = v.state.doc
        assertTrue(replaces.none { it.from <= doc.lineStart(20) && doc.lineStart(20) <= it.to })
    }

    @Test fun aFoldedRunCountsTheThreadsItHides() {
        val v = view(DiffConfig(unfoldComments = false))
        Review.setThreads(v, listOf(thread("a", 10), thread("b", 12), thread("c", 60)))
        val runs = Diff.collapsed(v.state)
        assertEquals(2, runs.first { it.bFrom == 0 }.comments)
        assertEquals(1, runs.first { 60 in it.bFrom until it.bTo }.comments)
    }

    // ------------------------------------------------------------------ 6: walkthrough steps --

    @Test fun aStepRangeShowsOnlyItsLinesAndContext() {
        val v = view(DiffConfig(range = 60..62, context = 3))
        Review.setThreads(v, listOf(thread("far", 80)))
        val runs = Diff.collapsed(v.state)
        assertEquals(listOf(0 until 57, 66 until 99), runs.map { it.bFrom until it.bTo })
        assertTrue(runs[0].hasChanges, "the change at line 50 is outside the step")
        assertTrue(runs[1].hasChanges && runs[1].comments == 1, "the insertion, the deletion and a thread outside it")
        // ↑ 20 on the leading run reveals the lines just above the step (today's diffContextBefore += 20).
        Diff.expand(v, runs[0], Expand.UP)
        assertEquals(0 until 37, Diff.collapsed(v.state)[0].let { it.bFrom until it.bTo })
        // The next step: another range, a new slice.
        Diff.load(v, base, working, DiffConfig(range = 10..10, context = 3))
        assertEquals(listOf(0 until 7, 14 until 99), Diff.collapsed(v.state).map { it.bFrom until it.bTo })
    }

    @Test fun aPlainContextStepHasNoDiffDecorations() {
        val v = view(DiffConfig(plain = true, range = 30..31, context = 2))
        v.dispatch(TransactionSpec(effects = listOf(EditorViewport.set.of(0 until v.state.doc.length))))
        val m = Diff.model(v.state)!!
        assertTrue(m.ready && m.hunks.isEmpty())
        val decos = v.state.facet(decorationsFacet).flatMap { it.toList() }
        assertTrue(decos.all { it.value is Decoration.Replace }, "only the folding: $decos")
        assertEquals(2, decos.size)
        assertTrue(v.state.facet(gutterMarkersFacet).flatMap { it.toList() }.none { it.value.column == Diff.DIFF_COLUMN || it.value.column == Diff.REVERT_COLUMN })
        assertEquals(listOf(0 until 28, 34 until 99), Diff.collapsed(v.state).map { it.bFrom until it.bTo })
    }

    @Test fun pagingGoesToTheHost() {
        val pages = ArrayList<DiffPage>()
        val v = view(host = object : DiffHost { override fun onDiffPage(direction: DiffPage) { pages += direction } })
        assertTrue(Diff.pageNext.run(v)); assertTrue(Diff.pagePrevious.run(v))
        assertEquals(listOf(DiffPage.NEXT, DiffPage.PREVIOUS), pages)
        assertFalse(Diff.pageNext.run(view()), "no host: nothing to page")
    }

    // ------------------------------------------------------------------ minors --

    @Test fun aDroppedRevertIsNotReported() {
        val log = ArrayList<String>()
        val v = view(host = object : DiffHost { override fun onRevert(hunk: DiffHunk) { log += "revert" } })
        v.readOnly = true // what Editor(readOnly = true) sets: user edits dropped
        assertFalse(Diff.revert(v, Diff.model(v.state)!!.hunks[0]))
        assertEquals(working, v.state.doc.toString())
        assertEquals(emptyList(), log)
    }

    @Test fun aPairOnAReusedBaseViewIsWiredToItsNewWorkingView() {
        val a = EditorView(EditorState.create(base))
        val first = DiffPair(a, EditorView(EditorState.create(working)))
        val b2 = EditorView(EditorState.create(working))
        val second = DiffPair(a, b2)
        Diff.expand(a, Diff.collapsed(a.state)[0], Expand.ALL)
        assertTrue(Diff.collapsed(b2.state).none { it.bFrom == 0 }, "A's expand went to the NEW working view")
        assertTrue(Diff.collapsed(first.working.state).any { it.bFrom == 0 }, "not to the old one")
        // And the old pair no longer pushes into A.
        first.working.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x\n"))))
        assertTrue(Diff.hunks(a.state) == second.model!!.hunks)
    }

    @Test fun aBigBaseIsSplitAndDiffedOffTheTransaction() = runTest {
        val v = EditorView(EditorState.create(working, extensions = inlineDiff(base, DiffConfig(syncChars = 100, offThread = false))))
        assertFalse(Diff.model(v.state)!!.ready, "too big to split in the transaction")
        v.startPlugins(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        val m = Diff.model(v.state)!!
        assertTrue(m.ready)
        assertEquals(3, m.hunks.size)
        // It edits and reverts like any other.
        assertTrue(Diff.revert(v, m.hunks[0]))
    }

    @Test fun anIdleFullReDiffMakesRegionReDiffsMinimalAgain() = runTest {
        // A 10-line vocabulary: region re-diffs are valid but not always minimal.
        val rnd = Random(21)
        val a = List(300) { "v${rnd.nextInt(10)}" }
        val v = EditorView(EditorState.create(a.joinToString("\n"), extensions = inlineDiff(a.joinToString("\n"), DiffConfig(offThread = false, idleRediffMs = 500))))
        v.startPlugins(backgroundScope)
        repeat(60) {
            val doc = v.state.doc
            val at = doc.lineStart(rnd.nextInt(doc.lineCount))
            v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "v${rnd.nextInt(10)}\n"))))
        }
        assertTrue(Diff.model(v.state)!!.spliced)
        advanceTimeBy(200); runCurrent()
        assertTrue(Diff.model(v.state)!!.spliced, "not before the typing has stopped 500 ms")
        advanceTimeBy(1_000); runCurrent()
        val m = Diff.model(v.state)!!
        assertFalse(m.spliced)
        assertEquals(LineDiff.diff(a, v.state.doc.toString().split('\n')).hunks, m.hunks)
    }
}
