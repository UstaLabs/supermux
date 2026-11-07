package dev.supermux.webview.sample.checks

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlin.time.TimeSource

/**
 * Where a lease's first load spends its time on iOS (the review's item 6: from the floor about 400 ms to commit, against
 * 47–63 ms between two pages of other sites in one engine). Each prints `MEASURE` lines, load to commit and to first
 * paint; nothing is asserted.
 */
object IosMeasurements {
    private fun percentile(values: List<Double>, p: Double): Double = values.sorted()[((values.size - 1) * p).toInt()]

    private fun line(name: String, values: List<Double>) = println(
        "MEASURE $name p50=${percentile(values, 0.5).rounded()} ms p90=${percentile(values, 0.9).rounded()} ms " +
            "min=${values.min().rounded()} ms max=${values.max().rounded()} ms n=${values.size}",
    )

    private fun Double.rounded(): Double = (this * 10).toLong() / 10.0

    /** Loads a page of a site of its own in [engine], on screen: milliseconds to its commit, and to its first paint. */
    private suspend fun CheckScope.timedLoad(engine: WebEngine, label: String): Pair<Double, Double> {
        val events = record(engine)
        val content = page(label)
        val started = TimeSource.Monotonic.markNow()
        engine.configure(setupFor(content))
        engine.load(content)
        events.await("the commit") { it is EngineEvent.UrlChanged }
        val commit = started.elapsedNow().inWholeMicroseconds / 1000.0
        events.await("first paint") { it == EngineEvent.FirstPaint }
        return commit to started.elapsedNow().inWholeMicroseconds / 1000.0
    }

    /** One engine of [profile]: a page, then six resets each followed by a page of another site, timed. */
    private suspend fun CheckScope.afterResets(profile: WebProfile, name: String) {
        val engine = engine(profile)
        show(engine)
        timedLoad(engine, "$name first")
        val commits = mutableListOf<Double>()
        val paints = mutableListOf<Double>()
        repeat(6) { i ->
            check(engine.reset()) { "reset" }
            val (commit, paint) = timedLoad(engine, "$name $i")
            commits += commit
            paints += paint
        }
        line("$name-load-commit", commits)
        line("$name-load-paint", paints)
    }

    val all: List<Check> = listOf(
        Check("measure-a-spares-first-load", timeoutMillis = 180_000) {
            // A new engine, as a spare is: its floor loaded, then the lease's page.
            val commits = mutableListOf<Double>()
            val paints = mutableListOf<Double>()
            repeat(6) { i ->
                val engine = engine()
                show(engine)
                val (commit, paint) = timedLoad(engine, "spare $i")
                commits += commit
                paints += paint
                show(null)
                engine.release()
            }
            line("spare-first-load-commit", commits)
            line("spare-first-load-paint", paints)
        },
        Check("measure-loads-after-a-reset", timeoutMillis = 180_000) { afterResets(WebProfile.Ephemeral, "after-reset") },
        // A Default profile's reset wipes no data: if its loads after a reset are fast, the wipe is what costs.
        Check("measure-loads-after-a-reset-default-profile", timeoutMillis = 180_000) { afterResets(WebProfile.Default, "after-reset-default") },
        Check("measure-loads-of-other-sites-in-one-engine", timeoutMillis = 180_000) {
            // No floor between them: each page another site's, in the engine the last one loaded in.
            val engine = engine()
            show(engine)
            timedLoad(engine, "site first")
            val commits = mutableListOf<Double>()
            val paints = mutableListOf<Double>()
            repeat(6) { i ->
                val (commit, paint) = timedLoad(engine, "site $i")
                commits += commit
                paints += paint
            }
            line("other-site-load-commit", commits)
            line("other-site-load-paint", paints)
        },
    )
}
