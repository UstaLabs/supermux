package dev.supermux.webview.wpetest

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

    @Test fun resultLinesSplitOnSpacesAndNameTheirHost() {
        val line = resultLine(
            "smoke", "S1-init", Verdict.PASS, "desktop",
            listOf("ms" to 12.344, "wpe" to "2.54.2 (x)", "n" to 3, "none" to null),
        )
        assertEquals("WPE smoke S1-init PASS host=desktop ms=12.34 wpe=2.54.2_(x) n=3 none=null", line)
    }
}
