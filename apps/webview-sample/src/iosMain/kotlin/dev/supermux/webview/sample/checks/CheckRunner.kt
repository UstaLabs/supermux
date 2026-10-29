package dev.supermux.webview.sample.checks

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.exit

/** One conformance check of this platform's engine, through the public engine SPI only. It fails by throwing. */
class Check(val name: String, val run: suspend CheckScope.() -> Unit)

/** What a check works with. Everything it made is released when it ends, pass or fail. */
class CheckScope internal constructor(private val host: CheckHost, private val scope: CoroutineScope) {
    private val made = mutableListOf<WebEngine>()
    private val recorders = mutableMapOf<WebEngine, Recorder>()

    /** A new engine of this platform, as the pool would get it. */
    suspend fun engine(profile: WebProfile = WebProfile.Ephemeral): WebEngine {
        val result = platformWebEngineFactory().create(EngineConfig(profile))
        val engine = (result as? WebEngineResult.Created)?.engine ?: throw AssertionError("no engine: $result")
        made += engine
        return engine
    }

    /**
     * Records [engine]'s events from now on, as the runtime does: subscribed before a load, one subscriber at a time (a
     * second one would share the events), so it stops the engine's previous recorder.
     */
    fun record(engine: WebEngine): Recorder {
        stopRecording(engine)
        return Recorder(engine, scope).also { recorders[engine] = it }
    }

    /** Stops recording [engine]'s events: nothing subscribes to them until the next [record], as for a parked page. */
    fun stopRecording(engine: WebEngine) {
        recorders.remove(engine)?.stop()
    }

    /** Shows [engine]'s surface on screen, 320 × 480 points, or nothing. A page paints only on screen. */
    fun show(engine: WebEngine?) {
        host.shown = engine
    }

    internal fun close() {
        recorders.values.forEach { it.stop() }
        host.shown = null
        made.forEach { runCatching { it.release() } }
    }
}

/** An engine's events, as they arrive. A waiter wakes the moment its event comes: the measurements rely on it. */
class Recorder(engine: WebEngine, scope: CoroutineScope) {
    private class Waiter(val from: Int, val matches: (EngineEvent) -> Boolean, val found: CompletableDeferred<EngineEvent>)

    val events = mutableListOf<EngineEvent>()
    private val waiters = mutableListOf<Waiter>()
    private val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { event ->
            events += event
            for (waiter in waiters.toList()) {
                if (events.size - 1 >= waiter.from && waiter.matches(event)) {
                    waiters -= waiter
                    waiter.found.complete(event)
                }
            }
        }
    }

    /** Waits up to [millis] for an event that [matches], from the [from]th on; returns it. */
    suspend fun await(what: String, from: Int = 0, millis: Long = 8_000, matches: (EngineEvent) -> Boolean): EngineEvent {
        events.drop(from).firstOrNull(matches)?.let { return it }
        val waiter = Waiter(from, matches, CompletableDeferred())
        waiters += waiter
        return withTimeoutOrNull(millis) { waiter.found.await() }
            ?: throw AssertionError("no $what within $millis ms; got ${events.drop(from)}").also { waiters -= waiter }
    }

    /** Waits [millis], then fails if an event that [matches] came from the [from]th on. */
    suspend fun expectNone(what: String, from: Int, millis: Long = 1_500, matches: (EngineEvent) -> Boolean) {
        delay(millis)
        val found = events.drop(from).filter(matches)
        if (found.isNotEmpty()) throw AssertionError("expected no $what, got $found")
    }

    fun stop() = job.cancel()
}

/**
 * Runs [checks] one after another on the main thread, each within a minute, and prints `CHECK PASS <name>` or
 * `CHECK FAIL <name>: <why>`, then `CHECKS DONE passed=<n> failed=<n>`, and exits: 0 if every check passed.
 * device-checks/ios-engine-checks.sh reads these lines from the app's console.
 */
internal suspend fun runChecks(checks: List<Check>, host: CheckHost, scope: CoroutineScope) {
    var passed = 0
    var failed = 0
    println("CHECKS START ${checks.size}")
    for (check in checks) {
        val context = CheckScope(host, scope)
        val failure = try {
            withTimeout(60_000) { check.run(context) }
            null
        } catch (e: Throwable) {
            e
        }
        context.close()
        delay(100)
        if (failure == null) {
            passed++
            println("CHECK PASS ${check.name}")
        } else {
            failed++
            println("CHECK FAIL ${check.name}: ${failure.message ?: failure}")
        }
    }
    println("CHECKS DONE passed=$passed failed=$failed")
    exit(if (failed == 0) 0 else 1)
}
