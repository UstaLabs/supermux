package dev.supermux.webview.sample.checks

import dev.supermux.webview.engine.EngineEvent
import kotlin.time.TimeSource

/**
 * The spec's §10 targets that a simulator can measure, through the engine SPI: a settled view's first paint from a
 * warm engine (target: 200 ms on mobile) and the bridge's round trip (target: 5 ms p50 on mobile). Each prints a
 * `MEASURE` line; nothing is asserted: a simulator on a shared Mac isn't a phone, and the README says so.
 */
object Measurements {
    private fun percentile(values: List<Double>, p: Double): Double = values.sorted()[((values.size - 1) * p).toInt()]

    private fun line(name: String, values: List<Double>) = println(
        "MEASURE $name p50=${percentile(values, 0.5).format()} ms p90=${percentile(values, 0.9).format()} ms " +
            "min=${values.min().format()} ms max=${values.max().format()} ms n=${values.size}",
    )

    private fun Double.format(): String = ((this * 10).toLong() / 10.0).toString()

    val all: List<Check> = listOf(
        Check("measure-first-paint-from-a-warm-engine") {
            val engine = engine()
            show(engine)
            val times = mutableListOf<Double>()
            repeat(10) { i ->
                val events = record(engine)
                val started = TimeSource.Monotonic.markNow()
                val content = page("warm $i")
                engine.configure(setupFor(content))
                engine.load(content)
                events.await("first paint") { it == EngineEvent.FirstPaint }
                times += started.elapsedNow().inWholeMicroseconds / 1000.0
                check(engine.reset()) { "reset" }
            }
            line("first-paint-warm", times)
        },
        Check("measure-snapshot-at-the-screens-density") {
            // A still page in a 320 × 480 pt view: what one snapshot costs at the screen's own scale (3× on the phone
            // simulators), WebKit's render and the pixels' copy into an image together, over 20.
            val engine = engine()
            val events = record(engine)
            show(engine)
            engine.configure(setupFor(articlePage))
            engine.load(articlePage)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            kotlinx.coroutines.delay(1_000)
            repeat(3) { engine.snapshot() }
            var size = ""
            val times = List(20) {
                val started = TimeSource.Monotonic.markNow()
                val image = checkNotNull(engine.snapshot()) { "a snapshot" }
                size = "${image.width}x${image.height}"
                started.elapsedNow().inWholeMicroseconds / 1000.0
            }
            line("snapshot-$size", times)
        },
        Check("measure-bridge-round-trip") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            engine.configure(setupFor(echoPage))
            engine.load(echoPage)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            val times = mutableListOf<Double>()
            repeat(60) { i ->
                val from = events.events.size
                val started = TimeSource.Monotonic.markNow()
                engine.postMessage(hostEvent("ping", i, nonce), echoPage.origin.origin)
                events.await("pong $i", from = from) { isPong(it, i) }
                if (i >= 10) times += started.elapsedNow().inWholeMicroseconds / 1000.0
            }
            line("bridge-round-trip", times)
        },
    )
}
