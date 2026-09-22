package dev.supermux.terminal.sample

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixtures and the measurement arithmetic, on every platform the sample builds for.
 *
 * These run in `:terminal-sample:jvmTest` AND `:terminal-sample:wasmJsBrowserTest`. They
 * deliberately need no engine: the browser check exists to prove the sample's common code compiles
 * and behaves identically under Kotlin/Wasm, and an engine test there would only re-run
 * `:terminal-core:wasmJsBrowserTest`.
 *
 * What they are actually guarding: a benchmark whose input is not byte-identical between runs, or
 * whose percentiles are computed wrong, produces numbers that look like measurements and are not.
 */
class SampleFixturesTest {

    @Test
    fun everyFixtureProducesNonEmptyChunks() {
        for (fixture in SampleFixture.entries) {
            val stream = fixture.stream()
            repeat(8) {
                val chunk = stream.next()
                assertTrue(chunk.isNotEmpty(), "${fixture.id} produced an empty chunk")
            }
        }
    }

    @Test
    fun theSameSeedProducesTheSameBytes() {
        for (fixture in SampleFixture.entries) {
            val a = fixture.stream(seed = 42)
            val b = fixture.stream(seed = 42)
            repeat(6) { assertContentEquals(a.next(), b.next(), "${fixture.id} is not deterministic") }
        }
    }

    @Test
    fun differentSeedsProduceDifferentBytes() {
        // Not a property of every chunk (a fixture may start with a fixed banner), but over a few
        // chunks a seeded generator that ignores its seed is caught.
        val a = buildString { repeat(6) { append(SampleFixture.SHELL.stream(seed = 1).next().decodeToString()) } }
        val b = buildString { repeat(6) { append(SampleFixture.SHELL.stream(seed = 2).next().decodeToString()) } }
        assertTrue(a != b, "the shell fixture ignores its seed")
    }

    @Test
    fun theAlternateScreenFixtureEntersAndLeavesTheAlternateScreen() {
        val stream = SampleFixture.ALT_SCREEN.stream()
        val preamble = stream.preamble().decodeToString()
        val epilogue = stream.epilogue().decodeToString()
        // 1049 alt screen, 1002 button tracking, 1006 SGR encoding, 1007 alternate scroll.
        for (mode in listOf("1049", "1002", "1006", "1007")) {
            assertTrue(preamble.contains("[?${mode}h"), "preamble does not enable mode $mode")
            assertTrue(epilogue.contains("[?${mode}l"), "epilogue does not disable mode $mode")
        }
    }

    @Test
    fun theUnicodeFixtureCarriesWideGlyphsCombiningMarksAndEmoji() {
        val text = buildString {
            val stream = SampleFixture.UNICODE.stream()
            repeat(10) { append(stream.next().decodeToString()) }
        }
        assertTrue(text.any { it.code in 0x4E00..0x9FFF }, "no CJK")
        assertTrue(text.contains('́'), "no combining acute accent")
        assertTrue(text.contains("😀") || text.contains("🚀"), "no emoji")
        assertTrue(text.contains('─') || text.contains('━'), "no box drawing")
    }

    @Test
    fun benchmarkFixturesAreExactlyTheRequestedSizeAndRepeatable() {
        for (fixture in BenchmarkFixture.entries) {
            val bytes = 256 * 1024
            val first = benchmarkFixture(fixture, totalBytes = bytes, chunkBytes = 4096)
            val second = benchmarkFixture(fixture, totalBytes = bytes, chunkBytes = 4096)
            assertEquals(bytes, first.sumOf { it.size }, "${fixture.id} is not exactly $bytes bytes")
            assertEquals(first.size, second.size)
            for (index in first.indices) {
                assertContentEquals(first[index], second[index], "${fixture.id} chunk $index differs")
            }
        }
    }

    @Test
    fun theAnsiBenchmarkFixtureContainsEscapesAndThePlainOneDoesNot() {
        val plain = benchmarkFixture(BenchmarkFixture.PLAIN, totalBytes = 64 * 1024)
            .joinToString("") { it.decodeToString() }
        val ansi = benchmarkFixture(BenchmarkFixture.ANSI, totalBytes = 64 * 1024)
            .joinToString("") { it.decodeToString() }
        assertTrue(!plain.contains('\u001b'), "the plain fixture carries escape sequences")
        assertTrue(ansi.contains('\u001b'), "the ansi fixture carries no escape sequences")
    }
}

class SampleDiagnosticsTest {

    @Test
    fun percentilesComeOutOfTheRecordedIntervals() {
        val diagnostics = SampleDiagnostics(frameWindow = 512)
        // 100 frames, 1 ms apart except the last which is 50 ms: p50 is 1 ms, the max is 50 ms.
        var nanos = 0L
        repeat(100) {
            nanos += 1_000_000
            diagnostics.onFrameNanos(nanos)
        }
        nanos += 50_000_000
        diagnostics.onFrameNanos(nanos)
        val snapshot = diagnostics.snapshot()
        assertEquals(100, snapshot.framesObserved.toInt(), "the first call only sets the baseline")
        assertEquals(1.0, snapshot.frameP50Millis, 0.01)
        assertEquals(50.0, snapshot.worstFrameMillis, 0.01)
        assertEquals(0, snapshot.stallsOver100ms.toInt())
        assertEquals(1, snapshot.stallsOver33ms.toInt())
    }

    @Test
    fun aStallOverAHundredMillisecondsIsCounted() {
        val diagnostics = SampleDiagnostics()
        diagnostics.onFrameNanos(0)
        diagnostics.onFrameNanos(150_000_000)
        assertEquals(1, diagnostics.snapshot().stallsOver100ms.toInt())
    }

    @Test
    fun theOutputQueueHighWaterIsTheLargestSimultaneousInFlight() {
        val diagnostics = SampleDiagnostics()
        diagnostics.onReceiveStart(4096)
        diagnostics.onReceiveStart(4096)
        diagnostics.onReceiveEnd(4096, 0)
        diagnostics.onReceiveStart(1024)
        diagnostics.onReceiveEnd(4096, 5_000)
        diagnostics.onReceiveEnd(1024, 0)
        val snapshot = diagnostics.snapshot()
        assertEquals(8192, snapshot.pendingOutputHighWaterBytes)
        assertEquals(0, snapshot.pendingOutputBytes, "every receive returned")
        assertEquals(1, snapshot.backpressureEvents.toInt())
        assertEquals(5_000, snapshot.backpressureMicros)
    }

    @Test
    fun aResetKeepsTheLifecycleTotalsAndClearsTheMeasurements() {
        val diagnostics = SampleDiagnostics()
        diagnostics.onSessionOpened()
        diagnostics.onOutput(1024)
        diagnostics.onFrameNanos(0)
        diagnostics.onFrameNanos(16_000_000)
        diagnostics.reset()
        val snapshot = diagnostics.snapshot()
        assertEquals(1, snapshot.sessionsOpened.toInt(), "a leak is invisible if this is zeroed too")
        assertEquals(0L, snapshot.outputBytes)
        assertEquals(0L, snapshot.framesObserved)
    }

    @Test
    fun byteFormattingIsStableAcrossPlatforms() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.00 KiB", formatBytes(1024))
        assertEquals("10.00 MiB", formatBytes(10L * 1024 * 1024))
        assertEquals("1.50 GiB", formatBytes(1536L * 1024 * 1024))
        assertEquals("16.70", formatDecimal(16.7, 2))
        assertEquals("0.05", formatDecimal(0.048, 2))
        assertEquals("—", formatDecimal(Double.NaN, 2))
    }
}
