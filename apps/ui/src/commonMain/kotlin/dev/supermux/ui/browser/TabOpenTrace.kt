package dev.supermux.ui.browser

import dev.supermux.util.PerfTrace

/**
 * How long a Browser tab takes to show its page, step by step (#155): each step once, as a `browser.<step>` PerfTrace
 * event with the ms since the pane opened, under one trace per open. They show in the Load Timings panel, and in the
 * broker's perf log while its tracing is on (`bun scripts/perf-timeline.ts on`).
 */
internal class TabOpenTrace(viewId: String, private val now: () -> Long = PerfTrace::elapsedMs) {
    private val trace = "B" + viewId.takeLast(6) + "-" + now()
    private val start = now()
    private val seen = HashSet<String>()

    /** The ms since the pane opened. */
    fun elapsed(): Long = now() - start

    fun mark(step: String, vararg fields: Pair<String, Any?>) {
        if (!seen.add(step)) return
        PerfTrace.event("browser.$step", "ms" to elapsed(), *fields, trace = trace)
    }

    fun has(step: String): Boolean = step in seen
}
