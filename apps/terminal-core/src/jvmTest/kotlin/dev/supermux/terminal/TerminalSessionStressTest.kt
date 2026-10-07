package dev.supermux.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the common fixtures structurally cannot check: a session on a REAL multi-threaded dispatcher,
 * hammered by many concurrent callers.
 *
 * [VirtualClock] is single-threaded by construction, so `TerminalSessionTest` proves ordering and
 * cadence but never a data race. These run on [Dispatchers.Default] with a
 * [SerializationProbe] between the session and the engine, which fails the test if two threads are
 * ever inside the engine at once (the st_* contract: one handle, externally serialized) — and every
 * case is wrapped in a timeout, so a deadlock fails instead of hanging the build.
 */
class TerminalSessionStressTest {
    private val size = TerminalSize(40, 10, 8, 16)
    private val key = TerminalKey(TerminalKeys.A, "a", Modifiers.NONE, KeyAction.PRESS)

    /** Fails if the engine is ever entered concurrently or re-entered; records the threads used. */
    private class SerializationProbe(private val delegate: TerminalEngine) : TerminalEngine {
        private val inside = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private inline fun <T> guard(block: () -> T): T {
            if (!inside.compareAndSet(0, 1)) overlaps.incrementAndGet()
            threads += Thread.currentThread().name
            try {
                return block()
            } finally {
                inside.set(0)
            }
        }

        override fun feed(bytes: ByteArray, origin: OutputOrigin) = guard { delegate.feed(bytes, origin) }
        override fun reset() = guard { delegate.reset() }
        override fun resize(size: TerminalSize) = guard { delegate.resize(size) }
        override fun colors(colors: TerminalColors) = guard { delegate.colors(colors) }
        override fun viewport(forceFull: Boolean, breakHold: Boolean) = guard { delegate.viewport(forceFull, breakHold) }
        override fun acknowledge(generation: Long) = guard { delegate.acknowledge(generation) }
        override fun scrollTo(row: Long) = guard { delegate.scrollTo(row) }
        override fun key(key: TerminalKey) = guard { delegate.key(key) }
        override fun mouse(mouse: TerminalMouse) = guard { delegate.mouse(mouse) }
        override fun paste(text: String, allowUnsafe: Boolean) = guard { delegate.paste(text, allowUnsafe) }
        override fun focus(focused: Boolean) = guard { delegate.focus(focused) }
        override fun select(selection: TerminalSelection?) = guard { delegate.select(selection) }
        override fun selectedText() = guard { delegate.selectedText() }
        override fun drainEffects() = guard { delegate.drainEffects() }
        override fun close() = guard { delegate.close() }
    }

    private suspend fun openSession(
        probe: SerializationProbe,
        effects: (TerminalEffect) -> Unit = {},
        errors: (Throwable) -> Unit = {},
    ): TerminalSession = TerminalSession.open(
        size = size,
        context = Dispatchers.Default,
        effects = effects,
        onEngineError = errors,
        engineFactory = { _, _ -> probe },
    )

    @Test fun eightConcurrentProducersNeverRaceTheEngine() = runBlocking {
        withTimeout(120_000) {
            val recording = RecordingTerminalEngine(size)
            val probe = SerializationProbe(recording)
            val effects = AtomicInteger(0)
            val errors = mutableListOf<Throwable>()
            val session = openSession(probe, effects = { effects.incrementAndGet() }, errors = { synchronized(errors) { errors += it } })

            // A renderer acknowledging on its own thread, so frames keep flowing under the load.
            val renderer = launch(Dispatchers.Default) {
                while (true) {
                    session.acknowledge(session.viewports.value.generation)
                    delay(1)
                }
            }
            val producers = List(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(300) { n ->
                        session.receive("w$worker-$n;".encodeToByteArray())
                        session.key(key)
                        if (n % 25 == 0) session.mouse(TerminalMouse(0, 0, MouseButton.LEFT, Modifiers.NONE, MouseAction.PRESS))
                        if (n % 97 == 0) session.acknowledge(session.viewports.value.generation)
                    }
                }
            }
            producers.joinAll()
            renderer.cancel()
            session.close()

            assertEquals(0, probe.overlaps.get(), "the engine was entered concurrently")
            assertEquals(1, recording.closeCount)
            assertTrue(errors.isEmpty(), "unexpected engine errors: $errors")
            assertEquals(2400, recording.calls.count { it.startsWith("feed(") }, "every accepted chunk is fed exactly once")
            assertTrue(probe.threads.size >= 1)
        }
    }

    @Test fun concurrentCloseIsIdempotentAndUnblocksEveryone() = runBlocking {
        withTimeout(120_000) {
            val recording = RecordingTerminalEngine(size)
            val probe = SerializationProbe(recording)
            // A tight output budget so producers really do sit in the backpressure path when close lands.
            val session = TerminalSession.open(
                size = size,
                config = TerminalSessionConfig(maxPendingOutputBytes = 8 * 1024, mailboxCapacity = 8),
                context = Dispatchers.Default,
                engineFactory = { _, _ -> probe },
            )
            val chunk = ByteArray(2048) { 'x'.code.toByte() }

            val producers = List(6) {
                launch(Dispatchers.Default) {
                    repeat(200) {
                        // After close() these fail; that is the contract, not a hang.
                        runCatching { session.receive(chunk) }
                        session.key(key)
                    }
                }
            }
            val closers = List(4) { async(Dispatchers.Default) { runCatching { session.close() } } }
            closers.awaitAll()
            producers.joinAll()
            session.close()

            assertEquals(0, probe.overlaps.get(), "the engine was entered concurrently")
            assertEquals(1, recording.closeCount, "close() is idempotent across threads")
            assertEquals(
                EnqueueResult.Rejected(RejectionReason.CLOSED),
                session.key(key),
                "non-blocking enqueues fail cleanly after close",
            )
            val failure = runCatching { session.receive(chunk) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "suspending calls fail cleanly after close, got $failure")
        }
    }

    @Test fun repliesAndBackpressureSurviveAConcurrentClose() = runBlocking {
        withTimeout(120_000) {
            val recording = RecordingTerminalEngine(size)
            val probe = SerializationProbe(recording)
            val session = openSession(probe)

            // paste()/selectedText() park on a reply; close() must answer or fail every one of them.
            val replies = List(16) { i ->
                async(Dispatchers.Default) {
                    runCatching { if (i % 2 == 0) session.paste("p$i") else session.selectedText() }
                }
            }
            val noise = launch(Dispatchers.Default) { repeat(500) { session.key(key) } }
            session.close()
            val outcomes = replies.awaitAll()
            noise.join()

            assertEquals(16, outcomes.size, "no reply may be left unanswered")
            assertEquals(0, probe.overlaps.get())
            assertEquals(1, recording.closeCount)
        }
    }
}
