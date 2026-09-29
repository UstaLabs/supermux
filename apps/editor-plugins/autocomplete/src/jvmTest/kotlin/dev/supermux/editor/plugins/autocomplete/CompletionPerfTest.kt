package dev.supermux.editor.plugins.autocomplete

import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Plan M4c: a 5,000-item list filters in under 5 ms per keystroke on the JVM. */
class CompletionPerfTest {
    @Test fun fiveThousandOptionsFilterUnderFiveMillisecondsPerKeystroke() {
        val rnd = Random(7)
        val parts = listOf("get", "set", "is", "to", "from", "text", "line", "range", "doc", "state", "view", "node", "item", "list", "map", "value", "key", "index", "size", "count")
        val options = (0 until 5000).map { i ->
            val w = (0 until 2 + rnd.nextInt(3)).joinToString("") { k -> parts[rnd.nextInt(parts.size)].let { if (k == 0) it else it.replaceFirstChar(Char::uppercase) } }
            Completion("$w$i", detail = "fun $w(): Unit", type = "method")
        }
        val typed = "getTextRange"
        val src = CompletionSource { null }
        var best = Double.MAX_VALUE
        repeat(12) { round ->
            var worst = 0.0
            for (n in 1..typed.length) {
                val st = EditorState.create(typed.substring(0, n), EditorSelection.cursor(n))
                val r = ActiveResult(src, CompletionResult(0, options, validFor = Regex("\\w*")), 0, n, explicit = false)
                val t0 = System.nanoTime()
                val out = Autocomplete.filterAndSort(listOf(r), st)
                val ms = (System.nanoTime() - t0) / 1e6
                if (round > 0) worst = maxOf(worst, ms)
                if (round == 11) println("COMPLETION-PERF n=$n ${"%.2f".format(ms)} ms, ${out.size} shown")
                check(n > 1 || out.isNotEmpty())
            }
            if (round > 0) best = minOf(best, worst)
        }
        println("COMPLETION-PERF 5000 options, worst keystroke (best of 11 rounds): ${"%.2f".format(best)} ms")
        assertTrue(best < 5.0, "filtering 5,000 options took $best ms per keystroke")
    }
}
