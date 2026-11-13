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

class PreviewCookieCacheCancellationTest {
    // Ahmet's Fold, 2026-11-10: the request runs in the pane's load effect, which restarts when the blank page goes live.
    // Over the relay the request was still in flight, so it was cancelled, and the cancellation was cached as a failure:
    // the tab loaded /p/3077/ without its cookie.
    @kotlin.test.Test fun aCancelledAskIsNeitherAFailureNorRemembered() = kotlinx.coroutines.test.runTest {
        var asks = 0
        var cancel = true
        val cache = PreviewCookieCache(fetch = {
            asks++
            if (cancel) throw kotlinx.coroutines.CancellationException("the coroutine scope left the composition")
            "c"
        }, now = { 0L })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { cache.get() }
        cancel = false
        kotlin.test.assertEquals("c", cache.get(), "asked again at once, not after the retry wait")
        kotlin.test.assertEquals(2, asks)
    }

    @kotlin.test.Test fun aFailedAskIsAFailure() = kotlinx.coroutines.test.runTest {
        var asks = 0
        val cache = PreviewCookieCache<String>(fetch = { asks++; throw IllegalStateException("HTTP 500") }, now = { 0L })
        kotlin.test.assertNull(cache.get())
        kotlin.test.assertNull(cache.get())
        kotlin.test.assertEquals(1, asks, "a failure waits for the retry")
    }
}
