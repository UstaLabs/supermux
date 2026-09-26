package dev.supermux.editor.syntax

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The budgets, checked on the Mac JVM (other platforms print their numbers in PerfTest). Each is
 * the best of three medians: the Mac is shared with other sessions, and their load is not ours.
 */
class BudgetTest {
    private val backend = testBackend()

    private fun best(case: Pair<String, String>): PerfCases.Numbers =
        List(3) { runBlocking { PerfCases.measure(backend, case.first, case.second) } }.minBy { it.cycle }

    @Test fun markdownKeystrokeWorkerCycleUnder20ms() {
        val n = best(PerfCases.markdown)
        println("BUDGET markdown $n")
        assertTrue(n.cycle < 20.0, "markdown worker cycle ${n.cycle} ms")
        assertTrue(n.incremental + n.view180 < 20.0, "markdown keystroke + 180 lines ${n.incremental + n.view180} ms")
    }

    @Test fun kotlinKeystrokePlusViewportUnder8ms() {
        val n = best(PerfCases.kotlin)
        println("BUDGET kotlin $n")
        assertTrue(n.incremental + n.view60 < 8.0, "kotlin keystroke + 60 lines ${n.incremental + n.view60} ms")
    }

    @Test fun uiCostAt70kSpansUnder1ms() {
        val (key, upd) = List(3) { PerfCases.uiCost(backend) }.minBy { it.first + it.second }
        println("BUDGET ui keystroke(map)=${fmt(key)}ms update(replace)=${fmt(upd)}ms")
        assertTrue(key < 1.0, "a keystroke maps spans in $key ms")
        assertTrue(upd < 1.0, "an update replaces spans in $upd ms")
    }
}
