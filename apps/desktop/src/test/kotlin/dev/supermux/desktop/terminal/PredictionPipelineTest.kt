package dev.supermux.desktop.terminal

import dev.supermux.net.DisplayOp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [PredictionPipeline]'s ROUTING and latency-stamping — the pipeline's own
 * responsibilities — against a real headless model+connector ([TermTestHarness]). The engine's
 * reconcile logic is owned by the 26 shared parity tests and is NOT re-tested here; these tests
 * only assert which lane a byte-batch takes (engine-render vs. fallback) and that the pre-send tap
 * bootstraps the RTT clock.
 */
class PredictionPipelineTest {

    private val h = TermTestHarness()
    @AfterTest fun tearDown() = h.close()

    /** A [PredictionAdapter] whose render always throws, to drive the pipeline's fallback path. */
    private class ThrowingAdapter(h: TermTestHarness) :
        PredictionAdapter(h.terminal, h.buffer, h.connector) {
        override fun render(ops: List<DisplayOp>) {
            throw RuntimeException("boom")
        }
    }

    @Test
    fun handle_output_before_attach_uses_fallback() {
        val pipeline = PredictionPipeline()
        var fallbacks = 0
        pipeline.handleOutput("hi".toByteArray()) { fallbacks++ }
        assertEquals(1, fallbacks)
    }

    @Test
    fun handle_output_when_attached_renders_through_engine_not_fallback() {
        val pipeline = PredictionPipeline()
        pipeline.attachAdapter(PredictionAdapter(h.terminal, h.buffer, h.connector))

        var fallbacks = 0
        pipeline.handleOutput("hi".toByteArray()) { fallbacks++ }

        // Engine onServerData(empty pending) → Passthrough → adapter injects → emulator parses.
        h.awaitChar(1, 0, 'i')
        assertEquals("hi", h.textAt(0, 0, 2))
        // The bytes flowed through the engine/adapter, NOT the direct-offer fallback (double-render trap).
        assertEquals(0, fallbacks)
    }

    @Test
    fun handle_output_falls_back_when_the_adapter_throws() {
        val pipeline = PredictionPipeline()
        pipeline.attachAdapter(ThrowingAdapter(h))

        var fallbacks = 0
        pipeline.handleOutput("hi".toByteArray()) { fallbacks++ }

        // render() threw → runCatching.onFailure fired the fallback so the byte stream stays alive.
        assertEquals(1, fallbacks)
    }

    @Test
    fun input_tap_stamps_last_key_at_and_output_consumes_it_as_latency() {
        var clock = 0L
        val pipeline = PredictionPipeline(nowMs = { clock })
        pipeline.attachAdapter(PredictionAdapter(h.terminal, h.buffer, h.connector))

        clock = 1000L
        pipeline.handleInput("a".toByteArray())
        // The pre-send tap stamped the keystroke time for the RTT bootstrap.
        assertEquals(1000L, pipeline.peekLastKeyAt())

        clock = 1060L
        var fallbacks = 0
        pipeline.handleOutput("a".toByteArray()) { fallbacks++ }
        // The echo consumed the stamp (fed the 60ms RTT into setLatencyEstimate) and reset it.
        assertEquals(0L, pipeline.peekLastKeyAt())
        // Still the engine lane, not the fallback.
        assertEquals(0, fallbacks)
    }

    @Test
    fun teardown_routes_later_output_back_to_fallback() {
        val pipeline = PredictionPipeline()
        pipeline.attachAdapter(PredictionAdapter(h.terminal, h.buffer, h.connector))
        pipeline.teardown()

        var fallbacks = 0
        pipeline.handleOutput("hi".toByteArray()) { fallbacks++ }
        assertEquals(1, fallbacks)
        assertTrue(pipeline.peekLastKeyAt() == 0L)
    }

    /**
     * Reproduces the REAL production interleave the pipeline's monitor exists for: in production
     * [PredictionPipeline.handleInput] runs on JediTerm's write-executor thread while
     * [PredictionPipeline.handleOutput] runs on the EDT (see the pipeline's THREADING KDoc), so
     * typing-while-echoing mutates the non-thread-safe engine from two threads. Two threads hammer
     * the two entry points for ~200ms with an always-active engine (each nowMs() call advances a
     * shared clock by 50ms, keeping the >=40ms latency gate open so predictions genuinely mutate
     * pending/snapshot state).
     *
     * Deterministic corruption assertions aren't feasible for a race, so the achievable bar
     * (documented, per review) is: (a) NO exception escapes handleInput — pre-fix, an engine
     * exception there propagates out of connector.write() and DROPS the keystroke; (b) NO fallback
     * fires in handleOutput — pre-fix, silent corruption surfaced as runCatching-swallowed
     * exceptions, i.e. fallback calls; (c) the pipeline + terminal stay functional afterward.
     */
    @Test
    fun concurrent_input_and_output_do_not_corrupt_the_pipeline() {
        val clock = AtomicLong(0)
        val pipeline = PredictionPipeline(nowMs = { clock.addAndGet(50) })
        pipeline.attachAdapter(PredictionAdapter(h.terminal, h.buffer, h.connector))

        val inputError = AtomicReference<Throwable?>(null)
        val outputError = AtomicReference<Throwable?>(null)
        val fallbacks = AtomicInteger(0)
        val deadline = System.currentTimeMillis() + 200

        val inputThread = Thread {
            try {
                while (System.currentTimeMillis() < deadline) {
                    pipeline.handleInput("a".toByteArray()) // decodable single char → CharInput
                }
            } catch (t: Throwable) {
                inputError.set(t)
            }
        }
        val outputThread = Thread {
            try {
                while (System.currentTimeMillis() < deadline) {
                    pipeline.handleOutput("a".toByteArray()) { fallbacks.incrementAndGet() }
                }
            } catch (t: Throwable) {
                outputError.set(t)
            }
        }
        inputThread.start()
        outputThread.start()
        inputThread.join(5000)
        outputThread.join(5000)

        // (a) unguarded handleInput never threw (pre-fix: dropped keystrokes).
        assertNull(inputError.get(), "handleInput threw: ${inputError.get()}")
        assertNull(outputError.get(), "handleOutput threw: ${outputError.get()}")
        // (b) handleOutput never hit the fallback — an engine/adapter exception under the race
        // would surface here via runCatching.onFailure.
        assertEquals(0, fallbacks.get())

        // (c) post-run sanity: the pipeline still routes cleanly. Re-attach (fresh engine — the
        // stressed one may legitimately sit in cooldown/pending states that reposition the caret),
        // home + clear the screen, and check a passthrough renders at the expected cell.
        pipeline.teardown()
        pipeline.attachAdapter(PredictionAdapter(h.terminal, h.buffer, h.connector))
        h.connector.injectDisplayBytes("[2J[H".toByteArray())
        awaitCursorHome()
        var postFallbacks = 0
        pipeline.handleOutput("ok".toByteArray()) { postFallbacks++ }
        assertEquals('o', h.awaitChar(0, 0, 'o'))
        assertEquals('k', h.awaitChar(1, 0, 'k'))
        assertEquals(0, postFallbacks)
    }

    /** Poll until the drain thread has parsed the queued home escape (cursor back at 1,1). */
    private fun awaitCursorHome(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            h.buffer.lock()
            val home = try {
                h.terminal.cursorX == 1 && h.terminal.cursorY == 1
            } finally {
                h.buffer.unlock()
            }
            if (home) return
            Thread.sleep(5)
        }
    }
}
