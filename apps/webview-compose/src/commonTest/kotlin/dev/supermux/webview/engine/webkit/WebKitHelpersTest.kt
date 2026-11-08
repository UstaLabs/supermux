package dev.supermux.webview.engine.webkit

import dev.supermux.webview.WebError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebKitHelpersTest {
    @Test fun aNamedProfileHasOneStableVersion5Uuid() {
        val a = profileUuidString("alice")
        assertEquals(a, profileUuidString("alice"))
        assertNotEquals(a, profileUuidString("bob"))
        assertEquals(36, a.length)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(a), a)
    }

    @Test fun webKitErrorsMapAsTheEnginesReportThem() {
        assertEquals(
            WebError.Certificate("https://a.test/", "bad cert"),
            WebKitErrors.toWebError(WebKitErrors.URL_DOMAIN, -1202, "bad cert", "https://a.test/"),
        )
        assertEquals(
            WebError.UnsupportedOnPlatform("x-app://a"),
            WebKitErrors.toWebError(WebKitErrors.WEBKIT_DOMAIN, 101, "can't show", "x-app://a"),
        )
        assertEquals(
            WebError.Network("https://a.test/", "offline"),
            WebKitErrors.toWebError(WebKitErrors.URL_DOMAIN, -1009, "offline", "https://a.test/"),
        )
    }

    @Test fun aReplacedNavigationOrAPolicyInterruptionIsNoError() {
        assertTrue(WebKitErrors.isIgnorable(WebKitErrors.URL_DOMAIN, -999))
        assertTrue(WebKitErrors.isIgnorable(WebKitErrors.WEBKIT_DOMAIN, 102))
        assertFalse(WebKitErrors.isIgnorable(WebKitErrors.URL_DOMAIN, -1009))
        assertFalse(WebKitErrors.isIgnorable(WebKitErrors.WEBKIT_DOMAIN, 101))
    }

    @Test fun navigationTypesAreWebKitsRawValues() {
        assertEquals(NavigationKind.Link, navigationKindOf(0))
        assertEquals(NavigationKind.FormSubmit, navigationKindOf(1))
        assertEquals(NavigationKind.BackForward, navigationKindOf(2))
        assertEquals(NavigationKind.Reload, navigationKindOf(3))
        assertEquals(NavigationKind.FormResubmit, navigationKindOf(4))
        assertEquals(NavigationKind.Other, navigationKindOf(-1))
        assertEquals(NavigationKind.Other, navigationKindOf(42))
    }

    @Test fun snapshotsAreTakenAtTheScreensOwnScaleUpToThreePixelsAPoint() {
        assertEquals(390.0, snapshotWidthPoints(390.0, 1.0))
        assertEquals(390.0, snapshotWidthPoints(390.0, 2.0))
        assertEquals(390.0, snapshotWidthPoints(390.0, 3.0))
        assertEquals(292.5, snapshotWidthPoints(390.0, 4.0), "a screen denser than 3x gets 3 pixels a point")
    }

    @Test fun bundlePathsAreEscapedAsFoundationEscapesAPath() {
        assertEquals("index.html", escapeUrlPath("index.html"))
        assertEquals("a%20b/%C3%A7%3F%23%25.html", escapeUrlPath("a b/ç?#%.html"))
        assertEquals("-._~!\$&'()*+,;=:@/", escapeUrlPath("-._~!\$&'()*+,;=:@/"))
        assertEquals("%F0%9F%99%82", escapeUrlPath("🙂"))
    }

    @Test fun theRepaintScriptWaitsTwoAnimationFrames() {
        assertTrue(WebKitScripts.REPAINT_BODY.split("requestAnimationFrame").size == 3, WebKitScripts.REPAINT_BODY)
    }

    @Test fun theFloorIsABlankPageThatFollowsTheTheme() {
        assertTrue("color-scheme" in WebKitUrls.FLOOR_HTML)
        assertTrue("<body></body>" in WebKitUrls.FLOOR_HTML)
    }
}
