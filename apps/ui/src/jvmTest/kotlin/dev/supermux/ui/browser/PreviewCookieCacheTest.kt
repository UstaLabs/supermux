package dev.supermux.ui.browser

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewCookieCacheTest {
    @Test fun aCookieIsAskedForOnceARun() = runTest {
        var asks = 0
        val cache = PreviewCookieCache(fetch = { asks++; "c" }, now = { 0L })
        assertEquals("c", cache.get())
        assertEquals("c", cache.get())
        assertEquals(1, asks)
    }

    // Ahmet's test, 2026-11-10: a refused or failed ask was remembered for the whole run, so a fixed broker didn't help
    // until the app restarted.
    @Test fun aFailedAskIsTriedAgainAfterAMinuteNotRemembered() = runTest {
        var t = 0L
        var answer: String? = null
        var asks = 0
        val cache = PreviewCookieCache(fetch = { asks++; answer }, now = { t })
        assertNull(cache.get())
        t = 30_000
        assertNull(cache.get())
        assertEquals(1, asks, "not again within the minute")
        answer = "c"
        t = 61_000
        assertEquals("c", cache.get())
        assertEquals(2, asks)
    }
}
