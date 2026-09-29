package dev.supermux.editor.sample

import java.lang.management.ManagementFactory
import kotlin.test.fail

/**
 * A time budget on the shared Mac: over it fails, unless the machine is loaded above 1.5x its cores
 * (webColdStartTest's rule), when the run is INCONCLUSIVE (printed, not failed): the no-change
 * baseline misses its budget then too.
 */
internal fun assertBudget(ms: Double, budget: Double, what: String) {
    if (ms <= budget) return
    val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
    val cores = Runtime.getRuntime().availableProcessors()
    if (load > 1.5 * cores) { println("PERF INCONCLUSIVE $what: $ms ms over $budget ms at load $load on $cores cores"); return }
    fail("$what: $ms ms over the $budget ms budget (load $load on $cores cores)")
}
