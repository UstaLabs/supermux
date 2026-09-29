package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The height map at 1,000,000 lines: queries in O(log n), an edit in O(changed lines + log n). */
class HeightMapPerfTest {
    private val lines = 1_000_000

    @Test fun lineAtOnAMillionLinesTakesUnderFiveMicroseconds() {
        val m = HeightMap(lines, 19f)
        val rnd = Random(1)
        for (i in 0 until lines step 3) m.setMeasured(i, 19f + (i % 7))
        val ys = FloatArray(100_000) { rnd.nextFloat() * m.totalHeight }
        var sink = 0L
        val best = bestOf(5) {
            val t = System.nanoTime()
            for (y in ys) sink += m.lineAt(y)
            (System.nanoTime() - t).toDouble() / ys.size / 1000.0
        }
        println("HeightMap.lineAt, 1M lines: best of 5 = %.3f µs/query (sink $sink)".format(best))
        assertTrue(best < 5.0, "lineAt took $best µs")
    }

    @Test fun anEditTouchingOneLineOnAMillionLinesTakesUnderFiftyMicroseconds() {
        // A doc of a million short lines, edited in the middle of one line (and typing a newline).
        val doc = Rope.of(buildString { repeat(lines) { if (it > 0) append('\n'); append("line") } })
        val rnd = Random(2)
        val best = bestOf(5) {
            val m = HeightMap(doc.lineCount, 19f)
            var cur = doc
            var total = 0L
            repeat(1000) {
                val line = rnd.nextInt(cur.lineCount)
                val at = cur.lineStart(line) + 2
                val changes = ChangeSet.of(cur.length, ChangeSpec(at, at, if (it % 2 == 0) "x" else "\n"))
                val after = changes.apply(cur)
                val t = System.nanoTime()
                m.applyChanges(changes, cur, after)
                total += System.nanoTime() - t
                cur = after
            }
            total.toDouble() / 1000 / 1000.0
        }
        println("HeightMap.applyChanges, 1M lines, one line: best of 5 = %.3f µs/edit".format(best))
        assertTrue(best < 50.0, "an edit took $best µs")
    }

    private fun bestOf(n: Int, run: () -> Double): Double = (0 until n).minOf { run() }
}
