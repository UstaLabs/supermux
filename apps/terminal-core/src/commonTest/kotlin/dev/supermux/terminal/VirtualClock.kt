@file:OptIn(InternalCoroutinesApi::class)

package dev.supermux.terminal

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A single-threaded dispatcher whose delays, timeouts AND [TimeSource] all run on one virtual
 * clock, so a [TerminalSession] under test never sleeps for real and its hold timeout is exact.
 *
 * This is deliberately NOT `kotlinx-coroutines-test`: that artifact's 1.9.0 wasm-js klib does not
 * link against the Kotlin 2.4.10 stdlib, and the session fixtures have to run unchanged on jvmTest,
 * wasmJsBrowserTest and iosSimulatorArm64Test.
 */
class VirtualClock : CoroutineDispatcher(), Delay, TimeSource {
    var currentTimeMillis: Long = 0L
        private set

    private val ready = ArrayDeque<Runnable>()
    private val scheduled = mutableListOf<Scheduled>()
    private var sequence = 0L

    private class Scheduled(val at: Long, val order: Long, val block: Runnable) {
        var cancelled = false
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        ready.addLast(block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        schedule(timeMillis, Runnable { continuation.resume(Unit) })
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = schedule(timeMillis, block)
        return object : DisposableHandle {
            override fun dispose() {
                task.cancelled = true
            }
        }
    }

    override fun markNow(): TimeMark {
        val start = currentTimeMillis
        return object : TimeMark {
            override fun elapsedNow(): Duration = (currentTimeMillis - start).milliseconds
        }
    }

    private fun schedule(delayMillis: Long, block: Runnable): Scheduled {
        val task = Scheduled(currentTimeMillis + maxOf(0L, delayMillis), sequence++, block)
        scheduled += task
        return task
    }

    private fun nextScheduled(): Scheduled? {
        scheduled.removeAll { it.cancelled }
        return scheduled.minWithOrNull(compareBy({ it.at }, { it.order }))
    }

    /** Run everything that is runnable right now, without moving the clock. */
    fun runCurrent() {
        while (ready.isNotEmpty()) ready.removeFirst().run()
    }

    /** Run until nothing is runnable and nothing is scheduled, moving the clock as needed. */
    fun advanceUntilIdle() {
        runCurrent()
        while (true) {
            val next = nextScheduled() ?: return
            scheduled.remove(next)
            currentTimeMillis = maxOf(currentTimeMillis, next.at)
            next.block.run()
            runCurrent()
        }
    }

    /** Run everything scheduled up to and including `now + [millis]`, then park the clock there. */
    fun advanceTimeBy(millis: Long) {
        val target = currentTimeMillis + millis
        runCurrent()
        while (true) {
            val next = nextScheduled() ?: break
            if (next.at > target) break
            scheduled.remove(next)
            currentTimeMillis = maxOf(currentTimeMillis, next.at)
            next.block.run()
            runCurrent()
        }
        currentTimeMillis = maxOf(currentTimeMillis, target)
    }
}

/**
 * Scope of a [terminalTest] body: a [CoroutineScope] on [clock] whose child coroutines' failures are
 * surfaced by the test instead of being swallowed.
 *
 * It deliberately installs no `CoroutineExceptionHandler`: that factory is an inline function whose
 * 1.9.0 wasm-js body does not link against the Kotlin 2.4.10 stdlib (IR linker:
 * "Key kotlin.text/substring … is missing in the map"). A failing child cancels [job] with its own
 * cause, which is just as good here.
 */
class VirtualClockScope(val clock: VirtualClock) : CoroutineScope {
    private val job = Job()
    private var childFailure: Throwable? = null
    override val coroutineContext: CoroutineContext = clock + job

    init {
        job.invokeOnCompletion { cause ->
            val failure = if (cause is CancellationException) cause.cause else cause
            if (childFailure == null && failure != null) childFailure = failure
        }
    }

    internal fun shutdown(): Throwable? {
        job.cancel()
        return childFailure
    }
}

/**
 * Run a suspending test body on a [VirtualClock] and drive it to completion. Everything the body
 * and the session under test wait for must resolve on that clock — a body that never finishes fails
 * the test instead of hanging it.
 */
fun terminalTest(body: suspend VirtualClockScope.() -> Unit) {
    val scope = VirtualClockScope(VirtualClock())
    var outcome: Result<Unit>? = null
    body.startCoroutine(scope, Continuation(scope.coroutineContext) { outcome = it })
    scope.clock.advanceUntilIdle()
    val result = outcome
    val failure = scope.shutdown()
    scope.clock.advanceUntilIdle()
    checkNotNull(result) {
        "the test body never completed (virtual time ${scope.clock.currentTimeMillis} ms) — " +
            "something is waiting on real time or on a coroutine that never runs"
    }.getOrThrow()
    failure?.let { throw it }
}
