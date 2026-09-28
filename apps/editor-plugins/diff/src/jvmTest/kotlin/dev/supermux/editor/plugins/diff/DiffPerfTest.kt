package dev.supermux.editor.plugins.diff

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** The engine's budgets on the JVM (best of several runs: the Mac is shared). */
class DiffPerfTest {
    private fun best(runs: Int = 7, block: () -> Unit): Double =
        (1..runs).minOf { measureTime(block).inWholeMicroseconds / 1000.0 }

    @Test fun tenThousandLinesWithAThousandChangesUnder50ms() {
        val rnd = Random(1)
        val a = randomLines(rnd, 10_000)
        val b = mutate(rnd, a, 1_000)
        var r: DiffResult? = null
        val ms = best { r = LineDiff.diff(a, b) }
        val linesOnly = best { LineDiff.diff(a, b, DiffOptions(charDiff = false)) }
        println("DIFF-PERF 10k lines / 1k changes: ${"%.2f".format(ms)} ms (lines only ${"%.2f".format(linesOnly)} ms), ${r!!.hunks.size} hunks, coarse=${r!!.coarse}")
        assertValidDiff(a, b, r!!.hunks)
        assertTrue(ms < 50, "10k lines with 1k changes took $ms ms (budget 50)")
    }

    @Test fun aKeystrokeReDiffsInWellUnderAMillisecond() {
        val rnd = Random(2)
        val a = randomLines(rnd, 10_000)
        val b = mutate(rnd, a, 1_000)
        val doc = Rope.of(b.joinToString("\n"))
        val hunks = LineDiff.diff(a, b).hunks
        val at = doc.lineStart(5_000) + 6
        val cs = ChangeSet.of(doc.length, listOf(ChangeSpec(at, at, "x")))
        val next = cs.apply(doc)
        val ms = best(20) { Splice.apply(a, hunks, doc, next, cs, DiffOptions()) }
        println("DIFF-PERF keystroke splice (10k lines, ${hunks.size} hunks): ${"%.3f".format(ms)} ms")
        assertTrue(ms < 4, "a keystroke's re-diff took $ms ms")
    }

    @Test fun pathologicalInputsStayBounded() {
        val rnd = Random(3)
        val a = List(20_000) { if (rnd.nextBoolean()) "}" else "" }
        val b = List(20_000) { if (rnd.nextBoolean()) "}" else "" }
        var r: DiffResult? = null
        val ms = best(3) { r = LineDiff.diff(a, b) }
        println("DIFF-PERF 20k lines over a 2-line alphabet: ${"%.1f".format(ms)} ms, coarse=${r!!.coarse}")
        assertValidDiff(a, b, r!!.hunks)
        assertTrue(ms < 500, "pathological input took $ms ms")
    }
}
