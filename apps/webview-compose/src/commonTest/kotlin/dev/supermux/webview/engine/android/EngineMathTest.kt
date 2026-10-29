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

    @Test fun memoryIsShortAtTheLevelsThatSaySo() {
        assertFalse(isMemoryPressure(5))
        assertTrue(isMemoryPressure(10))
        assertTrue(isMemoryPressure(15))
        assertFalse(isMemoryPressure(20))
        assertTrue(isMemoryPressure(40))
        assertTrue(isMemoryPressure(60))
        assertTrue(isMemoryPressure(80))
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
