package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web runs the syntax worker on the UI thread: it must give the thread back between parse
 * slices. A MessageChannel ping-pong (startGapMonitor) measures the longest the thread was held.
 */
class MainThreadTest {
    private val backend = testBackend()

    private val cases get() = listOf(PerfCases.kotlin, PerfCases.markdown, PerfCases.vue, PerfCases.php)

    private val samples = mapOf(
        "kotlin" to HighlightSamples.KOTLIN,
        "markdown" to HighlightSamples.MARKDOWN + HighlightSamples.KOTLIN,
        "vue" to HighlightSamples.repeatTo(HighlightSamples.VUE_SCRIPT_UNIT, 20, "<script lang=\"ts\">\n", "</script>\n<style>\n.a{}\n</style>\n"),
        "php" to HighlightSamples.repeatTo(HighlightSamples.PHP_UNIT, 20, "<html><body>\n<?php\n", "?>\n</body></html>\n"),
    )

    /**
     * The queries and tables are compiled / inflated once per backend, and the browser compiles the
     * code on its first run: neither is part of a document's first parse. A small document through
     * the highlighter and through a worker warms both.
     */
    private suspend fun warm(backend: NativeBackend, lang: String) {
        val sample = samples.getValue(lang)
        Highlighter(backend, lang).use { h ->
            val r = RopeText(Rope.of(sample))
            h.parse(r, sample.length, null, r).use { d -> h.spans(d, 0, sample.length, r); h.folds(d, 0, sample.length, r) }
        }
        val host = Host(sample, lang, backend)
        try {
            host.viewport(0 until sample.length)
            host.settle()
            host.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, " "))))
            host.settle()
        } finally {
            host.close()
        }
    }

    private suspend fun firstParse(backend: NativeBackend, lang: String, text: String, keystrokes: Int = 10): Pair<Gaps, Gaps?> {
        val rope = Rope.of(text)
        val mid = rope.lineCount / 2
        val host = Host(text, lang, backend)
        try {
            val m = startGapMonitor()
            host.viewport(rope.lineStart(mid - 30) until rope.lineStart(mid + 30))
            host.settle()
            val first = m.stop()
            println("MAINTHREAD $lang first cycle " + host.worker.lastCycle.entries.joinToString(" ") { "${it.key}=${fmt(it.value)}ms" })
            assertFalse(host.spans.isEmpty, "$lang: spans")
            if (keystrokes == 0) return first to null
            val k = startGapMonitor()
            repeat(keystrokes) {
                val at = host.state.doc.lineStart(mid) + 2
                host.dispatch(TransactionSpec(listOf(if (it % 2 == 0) ChangeSpec(at, at, "x") else ChangeSpec(at, at + 1, ""))))
                host.settle()
            }
            return first to k.stop()
        } finally {
            // the worker frees its handles on its own coroutine: wait, it may be a fresh runtime's
            host.worker.close()
            host.worker.join()
            host.close()
        }
    }

    private fun Gaps.show() = "maxHeld=${fmt(max)}ms over ${fmt(total)}ms (${ticks} turns; longest $top)"

    /** Warm (queries compiled, code compiled): no first parse and no keystroke cycle holds the thread 16 ms. */
    @Test fun noFirstParseOrKeystrokeHoldsTheThreadFor16ms() = runSuspendTest {
        val over = ArrayList<String>()
        for ((lang, text) in cases) {
            warm(backend, lang)
            val (first, typing) = firstParse(backend, lang, text)
            println("MAINTHREAD warm $lang first parse ${first.show()}; 10 keystrokes ${typing!!.show()}")
            if (first.max > 16.0) over += "$lang first parse ${fmt(first.max)} ms"
            if (typing.max > 16.0) over += "$lang keystrokes ${fmt(typing.max)} ms"
        }
        assertTrue(over.isEmpty(), "held the thread over 16 ms: $over")
    }

    /**
     * Cold: a fresh wasm instance and backend per language (the grammar's tables inflated, its
     * queries compiled during the first parse), no warm-up. Reported, not asserted: in a full run
     * the browser has compiled the code already; `--tests '*MainThreadTest.cold*'` alone measures
     * a cold page too.
     */
    @Test fun coldFirstParses() = runSuspendTest {
        for ((lang, text) in cases) {
            withFreshRuntime {
                val fresh = NativeBackend()
                fresh.ensureLanguageNow(lang)
                val (first, _) = firstParse(fresh, lang, text, keystrokes = 0)
                println("MAINTHREAD cold $lang first parse ${first.show()}")
            }
        }
    }
}
