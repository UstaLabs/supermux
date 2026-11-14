package dev.supermux.webview.engine

import dev.supermux.webview.FindResult
import dev.supermux.webview.WebCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserOpsTest {
    private val url = "https://host/p/"

    @Test fun aCookieHeaderHasEveryAttributeInOrder() {
        val c = WebCookie("cmux_preview", "tok", url, path = "/p/", domain = "host", secure = true, httpOnly = true, sameSite = "Strict", maxAgeSeconds = 3600)
        assertEquals("cmux_preview=tok; Path=/p/; Domain=host; Secure; HttpOnly; SameSite=Strict; Max-Age=3600", cookieHeader(c))
    }

    @Test fun theDefaultsMakeAHostOnlySessionCookie() {
        assertEquals("a=b; Path=/; Secure; HttpOnly; SameSite=Lax", cookieHeader(WebCookie("a", "b", url)))
    }

    @Test fun flagsLeftOffLeaveTheirAttributesOut() {
        assertEquals("a=b; Path=/; SameSite=None", cookieHeader(WebCookie("a", "b", url, secure = false, httpOnly = false, sameSite = "None")))
    }

    @Test fun sameSiteIsCaseInsensitiveAndNormalised() {
        assertEquals("a=b; Path=/; Secure; HttpOnly; SameSite=Lax", cookieHeader(WebCookie("a", "b", url, sameSite = "lAX")))
    }

    @Test fun aNegativeOrZeroMaxAgeIsKeptForExpiry() {
        assertEquals("a=b; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0", cookieHeader(WebCookie("a", "b", url, maxAgeSeconds = 0)))
        assertEquals("a=b; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0", cookieHeader(WebCookie("a", "b", url, maxAgeSeconds = -5)))
    }

    @Test fun anythingThatCouldInjectAnAttributeIsRefused() {
        assertNull(cookieHeader(WebCookie("a", "b; Domain=evil", url)))
        assertNull(cookieHeader(WebCookie("a;b", "c", url)))
        assertNull(cookieHeader(WebCookie("a=b", "c", url)))
        assertNull(cookieHeader(WebCookie("a", "b\r\nSet-Cookie: x=y", url)))
        assertNull(cookieHeader(WebCookie("", "b", url)))
        assertNull(cookieHeader(WebCookie("a b", "b", url)))
        assertNull(cookieHeader(WebCookie("a", "b", url, path = "/x;y")))
        assertNull(cookieHeader(WebCookie("a", "b", url, domain = "h;x")))
        assertNull(cookieHeader(WebCookie("a", "b", url, sameSite = "Sometimes")))
    }

    @Test fun anEmptyValueIsFine() {
        assertEquals("a=; Path=/; Secure; HttpOnly; SameSite=Lax", cookieHeader(WebCookie("a", "", url)))
    }

    @Test fun findResultsCountFromOneAndNoMatchesIsNotFound() {
        assertEquals(FindResult(found = true, count = 5, current = 1), findResultOf(activeMatchOrdinal = 0, numberOfMatches = 5))
        assertEquals(FindResult(found = true, count = 5, current = 5), findResultOf(4, 5))
        assertEquals(FindResult(found = false, count = 0, current = null), findResultOf(0, 0))
    }

    @Test fun anOutOfRangeOrdinalIsClamped() {
        assertEquals(FindResult(true, 3, 3), findResultOf(9, 3))
        assertEquals(FindResult(true, 3, 1), findResultOf(-1, 3))
    }

    @Test fun zoomIsRoundedToWholePercent() {
        assertEquals(100, textZoomPercent(1f))
        assertEquals(125, textZoomPercent(1.25f))
        assertEquals(110, textZoomPercent(1.1f))
        assertEquals(33, textZoomPercent(0.333f))
    }

    @Test fun zoomStaysInTheNavigatorsRange() {
        assertEquals(25, textZoomPercent(0.01f))
        assertEquals(500, textZoomPercent(50f))
        assertEquals(100, textZoomPercent(Float.NaN))
    }

    // #155: WebView2 sets a cookie through the DevTools protocol, by the same rules as the other engines.
    @Test fun theDevToolsCookieHasEveryPartAndExpiresByItsMaxAge() {
        val c = WebCookie("cmux_preview", "tok", url, path = "/p/", domain = ".host", sameSite = "lax", maxAgeSeconds = 86400)
        assertEquals(
            """{"name":"cmux_preview","value":"tok","url":"https://host/p/","path":"/p/","domain":".host","secure":true,"httpOnly":true,"sameSite":"Lax","expires":1086400}""",
            cdpSetCookieParams(c, nowEpochSeconds = 1_000_000),
        )
        assertEquals(
            """{"name":"a","value":"b","url":"https://host/p/","path":"/","secure":true,"httpOnly":true,"sameSite":"Lax"}""",
            cdpSetCookieParams(WebCookie("a", "b", url), nowEpochSeconds = 0),
            "host-only and for the session",
        )
    }

    @Test fun theDevToolsCookieRefusesWhatTheHeaderRefuses() {
        assertNull(cdpSetCookieParams(WebCookie("a", "b; Domain=evil", url), 0))
        assertNull(cdpSetCookieParams(WebCookie("a", "b", url, sameSite = "sometimes"), 0))
    }

    @Test fun theDevToolsAnswerSaysWhetherTheCookieWentIn() {
        kotlin.test.assertTrue(cdpCookieSet("{}"))
        kotlin.test.assertTrue(cdpCookieSet("""{"success":true}"""))
        kotlin.test.assertFalse(cdpCookieSet("""{"success":false}"""))
    }
}
