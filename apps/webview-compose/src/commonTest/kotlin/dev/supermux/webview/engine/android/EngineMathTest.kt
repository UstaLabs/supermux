package dev.supermux.webview.engine.android

import dev.supermux.webview.WebError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineMathTest {
    @Test fun theWebViewMajorVersionIsTheVersionNamesFirstNumber() {
        assertEquals(156, webViewMajor("156.0.8078.47"))
        assertNull(webViewMajor("canary"))
        assertNull(webViewMajor(null))
    }

    @Test fun theKeyboardCoversWhatIsBelowItsTop() {
        assertEquals(500, imeOverlap(viewBottom = 2000, windowHeight = 2400, imeBottom = 900))
        assertEquals(0, imeOverlap(viewBottom = 1400, windowHeight = 2400, imeBottom = 900))
        assertEquals(900, imeOverlap(viewBottom = 2400, windowHeight = 2400, imeBottom = 900))
        assertEquals(0, imeOverlap(viewBottom = 2400, windowHeight = 2400, imeBottom = 0))
    }

    /**
     * Only the running levels say memory is short (final review, item 5): BACKGROUND (40), close to the only level
     * Android 14+ still sends, comes at every app switch, and as pressure it evicted views and dropped covers each time.
     */
    @Test fun memoryIsShortAtTheRunningLevelsOnly() {
        assertFalse(isMemoryPressure(5))
        assertTrue(isMemoryPressure(10))
        assertTrue(isMemoryPressure(15))
        assertFalse(isMemoryPressure(20))
        assertFalse(isMemoryPressure(40))
        assertFalse(isMemoryPressure(60))
        assertFalse(isMemoryPressure(80))
    }

    @Test fun aCachedAppsLevelsAskForALightTrim() {
        for (level in listOf(5, 10, 15, 20)) assertFalse(isBackgroundTrim(level), "level $level")
        for (level in listOf(40, 60, 80)) assertTrue(isBackgroundTrim(level), "level $level")
    }

    @Test fun aTitleThatIsTheUrlIsNoTitle() {
        assertEquals("Demo 1", pageTitle("Demo 1", "https://cabc.invalid/"))
        assertNull(pageTitle("cabc.invalid/", "https://cabc.invalid/"))
        assertNull(pageTitle("cabc.invalid", "https://cabc.invalid/"))
        assertNull(pageTitle("https://a.test/x", "https://a.test/x"))
        assertNull(pageTitle("  ", "https://a.test/"))
        assertNull(pageTitle(null, "https://a.test/"))
    }

    @Test fun profilesAreNamedApartFromTheAppsOwn() {
        assertEquals("wvc_named_work", namedProfile("work"))
        assertEquals("wvc_ephemeral_3", ephemeralProfile(3))
    }

    @Test fun loadErrorsAreTheCertificatesThePolicysOrTheNetworks() {
        assertEquals(WebError.Certificate("https://a.test/", "bad cert"), loadError("https://a.test/", -11, "bad cert"))
        assertEquals(WebError.BlockedByPolicy("https://a.test/"), loadError("https://a.test/", -16, "unsafe"))
        assertEquals(WebError.Network("https://a.test/", "no such host"), loadError("https://a.test/", -2, "no such host"))
    }
}
