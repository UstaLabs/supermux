package dev.supermux.editor.syntax

import dev.supermux.editor.core.Rope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertTrue

/** A parse's budget counts the time spent working, never the time the thread was given away. */
class SliceBudgetTest {
    private val backend = testBackend()

    @Test fun timeGivenAwayDoesNotCountAgainstTheBudget() = runSuspendTest {
        val text = HighlightSamples.kotlinLines(2000)
        val r = RopeText(Rope.of(text))
        Highlighter(backend, "kotlin").use { h ->
            h.parse(r, text.length, null, r).close() // warm
            val work = ms { h.parse(r, text.length, null, r).close() }
            h.sliceMicros = 1_000
            h.budgetMicros = (work * 3_000).toLong() + 30_000 // three times the work, plus 30 ms
            var yields = 0
            h.yieldBetweenSlices = { yields++; delay(40) }
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            h.parseSuspending(r, text.length, null, r).close() // over budget would throw SyntaxException(TIMEOUT)
            val took = t0.elapsedNow().inWholeMicroseconds / 1000.0
            println("BUDGET work=${fmt(work)}ms budget=${h.budgetMicros / 1000}ms wall=${fmt(took)}ms yields=$yields")
            assertTrue(took * 1000 > h.budgetMicros, "the yields took longer than the budget (${fmt(took)} ms): the test tests something")
        }
    }
}
