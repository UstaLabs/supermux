package dev.supermux.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserZoomTest {
    @Test fun zoomStepsThroughTheBrowsersLevels() {
        assertEquals(1.1f, nextZoom(1f, +1))
        assertEquals(0.9f, nextZoom(1f, -1))
        assertEquals(1.25f, nextZoom(1.17f, +1), "a level between steps goes to the next one")
    }

    @Test fun zoomStopsAtEitherEnd() {
        assertEquals(5f, nextZoom(5f, +1))
        assertEquals(0.25f, nextZoom(0.25f, -1))
    }
}
