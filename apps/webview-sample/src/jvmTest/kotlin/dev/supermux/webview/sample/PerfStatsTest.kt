package dev.supermux.webview.sample

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerfStatsTest {
    @Test fun frameSummariesTakeNearestRankPercentilesAndCountFramesOverOneAndAHalfMediansAsDropped() {
        val stats = FrameStats()
        assertNull(stats.summary())
        for (ms in 100 downTo 1) stats.add(ms * 1_000_000L)
        val summary = assertNotNull(stats.summary())
        assertEquals(100, summary.frames)
        assertEquals(50_000_000L, summary.p50)
        assertEquals(95_000_000L, summary.p95)
        assertEquals(99_000_000L, summary.p99)
        assertEquals(100_000_000L, summary.max)
        // Over 75 ms: 76 to 100.
        assertEquals(25, summary.dropped)
        assertEquals("p50 50.0 · p95 95.0 · p99 99.0 · max 100.0 ms", summary.percentiles())
        stats.clear()
        assertNull(stats.summary())
    }

    @Test fun numbersAreWrittenForPeople() {
        assertEquals("16.7", millis(16_666_667))
        assertEquals("0.0", millis(0))
        assertEquals("512 B", size(512))
        assertEquals("12 KB", size(12L * 1024))
        assertEquals("1.5 MB", size(3L shl 19))
        assertEquals("128.0 MB", size(128L shl 20))
    }

    @Test fun theCounterCountsChurnPagesStartedAndUnavailableEngines() = runBlocking {
        val fakes = FakeWebEngineFactory()
        val counter = EngineCounter(fakes)
        val content = WebContent.Url("https://example.test/")
        val setup = PageSetup(true, false, false, false, null, emptySet())
        val a = created(counter.create(EngineConfig(WebProfile.Default)))
        val b = created(counter.create(EngineConfig(WebProfile.Default)))
        assertEquals(2, counter.created)
        assertEquals(2, counter.existing)
        assertEquals(2, counter.peakEngines)
        a.configure(setup)
        a.load(content)
        val saved = assertNotNull(a.saveState())
        b.configure(setup)
        assertTrue(b.restoreState(saved, content))
        // A restore that doesn't take starts no page: the runtime loads instead, and that load counts.
        assertFalse(b.restoreState("not this page".encodeToByteArray(), content))
        assertEquals(2, counter.loads)
        // No surface, no snapshot: only the pictures taken count.
        assertNull(a.snapshot())
        assertEquals(0, counter.snapshots)
        a.release()
        assertEquals(1, counter.destroyed)
        assertEquals(1, counter.existing)
        assertTrue(fakes.created.first().released, "the release reached the engine")
        assertEquals(2, counter.peakEngines, "the peak stays until reset")
        counter.resetPeak()
        assertEquals(1, counter.peakEngines)
        fakes.unavailable = UnavailableReason.NotImplemented
        assertIs<WebEngineResult.Unavailable>(counter.create(EngineConfig(WebProfile.Default)))
        assertEquals(1, counter.unavailable)
        assertEquals(2, counter.created)
        fakes.created.forEach { assertEquals(emptyList(), it.violations) }
    }

    private fun created(result: WebEngineResult): WebEngine = assertIs<WebEngineResult.Created>(result).engine
}
