package dev.supermux.editor.plugins.search

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A sliced find racing the user: a caret move wins, an edit re-runs the find once. */
class SearchRunnerRaceTest {
    /** Runs nothing until asked: each [runOne] is one UI-thread task (one slice). */
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runOne(): Boolean = queue.removeFirstOrNull()?.let { it.run(); true } ?: false
        fun drain() { var n = 0; while (runOne()) check(++n < 1_000_000) }
    }

    private val text = "x".repeat(3_000_000) + " needle"
    private val needle = text.indexOf("needle")

    private fun setup(block: (EditorView, SearchRunner, QueueDispatcher) -> Unit) {
        val d = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + d)
        try {
            val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), search()))
            // sliceMs = 0: a pause point after every step, so the find takes many tasks.
            val runner = SearchRunner(view, scope, sliceMs = 0)
            runner.attach()
            Search.openSearchPanel.run(view)
            Search.setQuery(view, SearchQuery("needle"))
            d.drain()
            block(view, runner, d)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aCaretMoveDuringASlicedFindIsNotOverridden() = setup { view, runner, d ->
        runner.find(1)
        repeat(3) { d.runOne() }
        assertTrue(runner.searching, "the find should still be running")
        view.dispatch(TransactionSpec(selection = EditorSelection.cursor(100), userEvent = "select"))
        d.drain()
        assertEquals(EditorSelection.cursor(100), view.state.selection, "the find moved the user's caret")
        assertTrue(!runner.searching)
    }

    @Test fun anEditDuringASlicedFindRunsItAgainFromTheNewState() = setup { view, runner, d ->
        runner.find(1)
        repeat(3) { d.runOne() }
        assertTrue(runner.searching)
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "ab")), userEvent = "input"))
        d.drain()
        assertEquals(EditorSelection.single(needle + 2, needle + 8), view.state.selection, "the find was dropped or found a stale position")
    }

    @Test fun aSecondEditDuringTheReRunCancelsIt() = setup { view, runner, d ->
        runner.find(1)
        repeat(2) { d.runOne() }
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "a")), userEvent = "input"))
        repeat(2) { d.runOne() }
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "b")), userEvent = "input"))
        d.drain()
        assertTrue(!runner.searching)
        assertEquals(EditorSelection.cursor(0), view.state.selection, "re-run once only: the caret stays where typing left it")
    }
}
