package dev.supermux.web

import kotlinx.browser.window
import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun runsInARealBrowser() {
        assertTrue(window.location.href.startsWith("http"), "Karma should serve the test page over http")
    }
}
