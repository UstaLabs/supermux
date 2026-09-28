package dev.supermux.editor.plugins.search

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A keystroke in the find field on a 10 MB document with no match anywhere (the find wraps the
 * whole document, the count reads all of it): every task the UI thread runs stays under 16 ms. The
 * "UI thread" is one executor thread; each dispatched task is timed (the web's MessageChannel gap
 * monitor is `SearchMainThreadWasmTest`).
 */
class SearchMainThreadTest {
    private class TimingDispatcher : CoroutineDispatcher() {
        val thread = Executors.newSingleThreadExecutor()
        @Volatile var max = 0.0
        @Volatile var tasks = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            thread.execute {
                val t0 = System.nanoTime()
                block.run()
                val ms = (System.nanoTime() - t0) / 1e6
                if (ms > max) max = ms
                tasks++
            }
        }
    }

    private val text = buildString {
        val line = "    val someIdentifier = computeSomething(argument, 42) // comment\n"
        while (length < 10_000_000) append(line)
    }

    /** The longest UI task (ms) and how many there were, for typing [typed] into the find field. */
    private fun keystroke(query: SearchQuery, typed: String): Pair<Double, Int> {
        val d = TimingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + d)
        try {
            val view = EditorView(EditorState.create(text, EditorSelection.cursor(text.length / 2), search()))
            lateinit var runner: SearchRunner
            val ready = java.util.concurrent.CountDownLatch(1)
            scope.launch {
                runner = SearchRunner(view, scope)
                runner.attach()
                Search.openSearchPanel.run(view)
                Search.setQuery(view, query)
                ready.countDown()
            }
            ready.await()
            while (!runner.idle) Thread.sleep(2)
            d.max = 0.0; d.tasks = 0
            scope.launch { runner.commitFind(typed) }
            Thread.sleep(20)
            val deadline = System.currentTimeMillis() + 60_000
            while ((!runner.idle || runner.searching || runner.info == null) && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue(runner.info != null, "the count never finished")
            return d.max to d.tasks
        } finally {
            scope.cancel()
            d.thread.shutdown()
        }
    }

    @Test fun noKeystrokeInTheFindFieldHoldsTheUiThreadFor16ms() {
        val cases = listOf(
            SearchQuery("zzzNotThere") to "zzzNotTherx",
            SearchQuery("zz+q", regexp = true) to "zz+qq",
            SearchQuery("zz\\sq", regexp = true) to "zz\\sqq",
            SearchQuery("comment", wholeWord = true) to "commentx",
        )
        keystroke(cases[0].first, cases[0].second) // warm the JIT
        val out = ArrayList<String>()
        val over = ArrayList<String>()
        for ((q, typed) in cases) {
            val (max, tasks) = (0 until 3).map { keystroke(q, typed) }.minBy { it.first }
            out += "${if (q.regexp) "regex" else "literal"} '$typed' longest ${"%.1f".format(max)} ms over $tasks tasks"
            if (max > 16.0) over += "$typed: $max ms"
        }
        println("SEARCH-MAINTHREAD jvm 10 MB keystroke: " + out.joinToString("; "))
        assertTrue(over.isEmpty(), "held the UI thread over 16 ms: $over")
    }
}
