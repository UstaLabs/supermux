package dev.supermux.webview.layouttest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestPageTest {
    @Test fun thePageUsesThePalette() {
        assertTrue(Palette.PAGE_CSS in TestPage.html)
        assertTrue(Palette.BAND_CSS in TestPage.html)
        assertTrue("height:${Palette.BAND_CSS_PX}px" in TestPage.html)
    }

    @Test fun thePageSendsEveryMessageTheParserReads() {
        for (type in listOf("painted", "down", "scroll", "input", "frames", "layout")) assertTrue("t:'$type'" in TestPage.html, type)
        assertTrue("window.webkit.messageHandlers.probe" in TestPage.html)
    }

    @Test fun thePageLoadsNothing() = assertFalse(Regex("""(src|href)="https?:""").containsMatchIn(TestPage.html))
}
