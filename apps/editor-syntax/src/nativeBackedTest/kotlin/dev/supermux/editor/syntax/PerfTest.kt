package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The numbers native/README.md records (not a pass/fail budget: the budget, incremental reparse +
 * viewport highlight under 8 ms on the Mac JVM, is checked by reading them). Medians after warm-up.
 */
class PerfTest {
    private val backend = testBackend()

    private fun ms(block: () -> Unit): Double {
        val t = TimeSource.Monotonic.markNow()
        block()
        return t.elapsedNow().inWholeNanoseconds / 1e6
    }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    @Test
    fun kotlin10kLines() {
        val text = HighlightSamples.kotlinLines(10_000)
        val lines = text.count { it == '\n' }
        var rope = Rope.of(text)
        fun src(r: Rope) = TextSource { i -> if (i >= r.length) "" else r.chunkAt(i) }
        Highlighter(backend, "kotlin").use { h ->
            val full = ArrayList<Double>()
            var doc: ParsedDocument? = null
            h.parse(src(rope), rope.length, null).use { assertTrue(!it.layers[0].tree.hasError, "the benchmark file must parse cleanly") }
            repeat(5) {
                doc?.close()
                full += ms {
                    doc = h.parse(src(rope), rope.length, null)
                    h.spans(doc!!, 0, rope.length, src(rope))
                }
            }
            // one keystroke in the middle, then the viewport around it: 60 lines, and 180 (the worker's
            // viewport plus one screen above and below)
            val incr = ArrayList<Double>()
            val view60 = ArrayList<Double>()
            val view180 = ArrayList<Double>()
            repeat(40) { k ->
                val at = rope.lineStart(lines / 2) + 4
                // type an "x", then delete it again
                val cs = ChangeSet.of(rope.length, if (k % 2 == 0) ChangeSpec(at, at, "x") else ChangeSpec(at, at + 1, ""))
                val next = cs.apply(rope)
                var parsed: ParsedDocument? = null
                incr += ms {
                    for (e in textEditsFor(cs, rope, next)) doc!!.edit(e)
                    parsed = h.parse(src(next), next.length, doc)
                }
                doc!!.close()
                doc = parsed
                rope = next
                val v0 = rope.lineStart(lines / 2 - 30)
                val v1 = rope.lineStart(lines / 2 + 30)
                view60 += ms { h.spans(doc!!, v0, v1, src(rope)) }
                val w0 = rope.lineStart(lines / 2 - 90)
                val w1 = rope.lineStart(lines / 2 + 90)
                view180 += ms { h.spans(doc!!, w0, w1, src(rope)) }
            }
            val warm = { xs: List<Double> -> median(xs.drop(xs.size / 4)) }
            println(
                "PERF kotlin lines=$lines units=${rope.length} full(parse+highlight all)=${fmt(median(full.drop(1)))}ms " +
                    "(first ${fmt(full[0])}) incremental=${fmt(warm(incr))}ms viewport60=${fmt(warm(view60))}ms " +
                    "viewport180=${fmt(warm(view180))}ms incremental+viewport60=${fmt(warm(incr) + warm(view60))}ms " +
                    "incremental+viewport180=${fmt(warm(incr) + warm(view180))}ms",
            )
            doc?.close()
            assertTrue(full.isNotEmpty())
        }
    }

    private fun fmt(x: Double): String = (kotlin.math.round(x * 100) / 100).toString()
}
