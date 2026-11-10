package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `NSHTTPCookie` a [WebCookie] becomes: no page needed, Foundation builds it. */
class BrowserOpsTest {
    @Test fun aDefaultCookieIsHostOnlySecureHttpOnlyAndLaxForTheSession() {
        val cookie = assertNotNull(httpCookieOf(WebCookie(name = "cmux_preview", value = "tok", url = "https://host.example:8443/p/", path = "/p/")))
        assertEquals("cmux_preview", cookie.name)
        assertEquals("tok", cookie.value)
        assertEquals("/p/", cookie.path)
        assertEquals("host.example", cookie.domain.removePrefix("."), "the URL's host")
        assertTrue(cookie.secure)
        assertTrue(cookie.HTTPOnly)
        assertEquals("lax", cookie.sameSitePolicy?.lowercase())
        assertTrue(cookie.sessionOnly, "no max age: a session cookie")
    }

    @Test fun anExplicitDomainStrictPolicyAndMaxAgeAreKept() {
        val cookie = assertNotNull(
            httpCookieOf(
                WebCookie(
                    name = "a", value = "b", url = "https://x.example/", domain = ".example.org", secure = false, httpOnly = false,
                    sameSite = "Strict", maxAgeSeconds = 3600,
                ),
            ),
        )
        assertEquals(".example.org", cookie.domain)
        assertFalse(cookie.secure)
        assertFalse(cookie.HTTPOnly)
        assertEquals("strict", cookie.sameSitePolicy?.lowercase())
        assertFalse(cookie.sessionOnly)
        assertNotNull(cookie.expiresDate)
    }

    @Test fun sameSiteNoneSetsNoPolicy() {
        val cookie = assertNotNull(httpCookieOf(WebCookie(name = "a", value = "b", url = "https://x.example/", sameSite = "None")))
        assertNull(cookie.sameSitePolicy)
    }

    @Test fun aCookieWithNoHostIsRefused() {
        assertNull(httpCookieOf(WebCookie(name = "a", value = "b", url = "")))
    }
}
