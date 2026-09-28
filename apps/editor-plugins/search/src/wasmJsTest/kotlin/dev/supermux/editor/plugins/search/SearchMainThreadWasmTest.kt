package dev.supermux.editor.plugins.search

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

/** A JS Promise, non-generic, which kotlin-test awaits (editor-syntax's MainThreadTest pattern). */
@JsName("Promise")
external class SearchTestPromise : JsAny

private fun jsError(message: String): JsAny = js("new Error(message)")

private fun runSuspendTest(block: suspend CoroutineScope.() -> Unit): SearchTestPromise = Promise<JsAny?> { resolve, reject ->
    CoroutineScope(Dispatchers.Default).launch {
        try { block(); resolve(null) } catch (t: Throwable) { reject(jsError(t.stackTraceToString())) }
    }
}.unsafeCast<SearchTestPromise>()

/**
 * The MessageChannel gap monitor (M2c's `MainThreadTest`): a ping-pong that runs whenever the event
 * loop is free; the longest interval between two pings is the longest the thread was held.
 */
private fun startGapMonitor(): JsAny = js("""(() => {
  const ch = new MessageChannel();
  let last = performance.now(), max = 0, ticks = 0, on = true;
  ch.port1.onmessage = () => { const now = performance.now(); max = Math.max(max, now - last); last = now; ticks++; if (on) ch.port2.postMessage(0); };
  ch.port2.postMessage(0);
  return { stop() { on = false; ch.port1.close(); return Math.max(max, performance.now() - last); }, ticks: () => ticks };
})()""")

private fun stopGapMonitor(m: JsAny): Double = js("m.stop()")

private fun now(): Double = js("performance.now()")

/** A keystroke in the find field, 10 MB, no match anywhere: the page is never held 16 ms. */
class SearchMainThreadWasmTest {
    private val text = buildString {
        val line = "    val someIdentifier = computeSomething(argument, 42) // comment\n"
        while (length < 10_000_000) append(line)
    }

    private suspend fun keystroke(query: SearchQuery, typed: String): Pair<Double, Double> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val view = EditorView(EditorState.create(text, EditorSelection.cursor(text.length / 2), search()))
            val runner = SearchRunner(view, scope)
            runner.attach()
            Search.openSearchPanel.run(view)
            Search.setQuery(view, query)
            while (!runner.idle) delay(5)
            val m = startGapMonitor()
            val t0 = now()
            runner.commitFind(typed)
            while (!runner.idle || runner.searching || runner.info == null) delay(2)
            val took = now() - t0
            return stopGapMonitor(m) to took
        } finally {
            scope.cancel()
        }
    }

    @Test fun noKeystrokeInTheFindFieldHoldsThePageFor16ms() = runSuspendTest {
        val cases = listOf(
            SearchQuery("zzzNotThere") to "zzzNotTherx",
            SearchQuery("zz+q", regexp = true) to "zz+qq",
            SearchQuery("zz\\sq", regexp = true) to "zz\\sqq",
            SearchQuery("comment", wholeWord = true) to "commentx",
        )
        keystroke(cases[1].first, cases[1].second) // compile the code
        val out = ArrayList<String>()
        val over = ArrayList<String>()
        for ((q, typed) in cases) {
            val (held, took) = keystroke(q, typed)
            out += "${if (q.regexp) "regex" else "literal"} '$typed' longest held ${(held * 10).toInt() / 10.0} ms, done in ${took.toInt()} ms"
            if (held > 16.0) over += "$typed: $held ms"
        }
        println("SEARCH-MAINTHREAD wasm 10 MB keystroke: " + out.joinToString("; "))
        assertTrue(over.isEmpty(), "held the page over 16 ms: $over")
    }
}
