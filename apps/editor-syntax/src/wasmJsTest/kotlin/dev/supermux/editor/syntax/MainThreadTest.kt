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

    /**
     * The queries and tables are compiled / inflated once per backend, and the browser compiles the
     * code on its first run: neither is part of a document's first parse. A small document through
     * the highlighter and through a worker warms both.
     */
    private suspend fun warm(lang: String, sample: String) {
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

    private suspend fun firstParse(lang: String, text: String): Pair<Gaps, Gaps> {
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
            val k = startGapMonitor()
            repeat(10) {
                val at = host.state.doc.lineStart(mid) + 2
                host.dispatch(TransactionSpec(listOf(if (it % 2 == 0) ChangeSpec(at, at, "x") else ChangeSpec(at, at + 1, ""))))
                host.settle()
            }
            return first to k.stop()
        } finally {
            host.close()
        }
    }

    private fun Gaps.show() = "maxHeld=${fmt(max)}ms over ${fmt(total)}ms (${ticks} turns; longest $top)"

    @Test fun aFirstParseOf10kKotlinLinesNeverHoldsTheThreadFor16ms() = runSuspendTest {
        warm("kotlin", HighlightSamples.KOTLIN)
        val (first, typing) = firstParse("kotlin", PerfCases.kotlin.second)
        println("MAINTHREAD kotlin first parse ${first.show()}; 10 keystrokes ${typing.show()}")
        assertTrue(first.max <= 16.0, "kotlin first parse held the thread ${fmt(first.max)} ms")
        assertTrue(typing.max <= 16.0, "kotlin keystroke cycle held the thread ${fmt(typing.max)} ms")
    }
}
