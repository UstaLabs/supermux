package dev.supermux.webview.wintest

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsTest {
    @Test fun nearestRankPercentilesOfOneToAHundred() {
        val samples = Samples("x")
        (1..100).forEach { samples.add(it.toDouble()) }
        assertEquals(50.0, samples.p50)
        assertEquals(95.0, samples.p95)
        assertEquals(100.0, samples.max)
        assertEquals(100, samples.count)
    }

    @Test fun percentilesDoNotDependOnInsertionOrder() {
        val samples = Samples("x")
        listOf(9.0, 1.0, 5.0).forEach(samples::add)
        assertEquals(5.0, samples.p50)
        assertEquals(9.0, samples.p95)
    }

    @Test fun emptySamplesReportACountOfZero() {
        val samples = Samples("lat")
        assertTrue(samples.p50.isNaN())
        assertEquals(listOf<Pair<String, Any?>>("lat_n" to 0), samples.pairs())
    }

    @Test fun samplePairsCarryTheirName() {
        val samples = Samples("copy_ms")
        samples.add(1.0)
        samples.add(3.0)
        assertEquals(
            listOf<Pair<String, Any?>>("copy_ms_n" to 2, "copy_ms_p50" to 1.0, "copy_ms_p95" to 3.0, "copy_ms_max" to 3.0),
            samples.pairs(),
        )
    }

    @Test fun numbersUseADotInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("3.14", fmt(3.14159))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun resultLinesSplitOnSpaces() {
        val line = resultLine(
            "smoke", "S1-first-frame", Verdict.PASS,
            listOf("ms" to 12.344, "runtime" to "155.0 (x)", "n" to 3, "none" to null),
        )
        assertEquals("WV2 smoke S1-first-frame PASS ms=12.34 runtime=155.0_(x) n=3 none=null", line)
    }
}
