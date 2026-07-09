package dev.supermux.desktop.terminal

import dev.supermux.net.DisplayOp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
