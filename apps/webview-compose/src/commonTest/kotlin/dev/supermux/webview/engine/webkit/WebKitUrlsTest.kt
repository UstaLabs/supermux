package dev.supermux.webview.engine.webkit

import dev.supermux.webview.WebOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebKitUrlsTest {
    private val plugin = WebOrigin("plugin-1")

    @Test fun aBundleIsServedOnItsOwnSchemeAndHost() {
        assertEquals("webview-bundle://plugin-1.invalid/index.html", WebKitUrls.bundleUrl(plugin, "index.html"))
        assertEquals("webview-bundle://plugin-1.invalid", WebKitUrls.bundleOrigin(plugin))
        assertTrue(WebKitUrls.isBundleUrl("webview-bundle://plugin-1.invalid/a/b.js?x#y", plugin))
        assertTrue(WebKitUrls.isBundleUrl("WEBVIEW-BUNDLE://Plugin-1.INVALID/", plugin))
        assertFalse(WebKitUrls.isBundleUrl("webview-bundle://plugin-2.invalid/", plugin))
        assertFalse(WebKitUrls.isBundleUrl("https://plugin-1.invalid/", plugin))
    }

    @Test fun bundleUrlsAreReportedInTheLibrarysTerms() {
        assertEquals("https://plugin-1.invalid/a/b.html?q=1#f", WebKitUrls.toLibraryUrl("webview-bundle://plugin-1.invalid/a/b.html?q=1#f", plugin))
        assertEquals("webview-bundle://plugin-2.invalid/", WebKitUrls.toLibraryUrl("webview-bundle://plugin-2.invalid/", plugin))
        assertEquals("https://example.com/", WebKitUrls.toLibraryUrl("https://example.com/", plugin))
        assertEquals("webview-bundle://plugin-1.invalid/", WebKitUrls.toLibraryUrl("webview-bundle://plugin-1.invalid/", null))
    }

    @Test fun originsAreSerializedAsBrowsersDo() {
        assertEquals("https://example.com", WebKitUrls.libraryOrigin("https", "Example.COM", 0, null))
        assertEquals("https://example.com", WebKitUrls.libraryOrigin("https", "example.com", 443, null))
        assertEquals("http://localhost:8080", WebKitUrls.libraryOrigin("http", "localhost", 8080, null))
        assertEquals("http://[::1]:8080", WebKitUrls.libraryOrigin("http", "::1", 8080, null))
        assertEquals("null", WebKitUrls.libraryOrigin("", "", 0, null))
        assertEquals("null", WebKitUrls.libraryOrigin("file", "", 0, null))
    }

    @Test fun theLeasesBundleOriginIsItsLocalOrigin() {
        assertEquals("https://plugin-1.invalid", WebKitUrls.libraryOrigin("webview-bundle", "plugin-1.invalid", 0, plugin))
        // Another bundle's origin is never passed off as a local one.
        assertEquals("webview-bundle://plugin-2.invalid", WebKitUrls.libraryOrigin("webview-bundle", "plugin-2.invalid", 0, plugin))
        assertEquals("webview-bundle://plugin-1.invalid", WebKitUrls.libraryOrigin("webview-bundle", "plugin-1.invalid", 0, null))
    }

    @Test fun pagesSeeTheirBundleOrigin() {
        assertEquals("webview-bundle://plugin-1.invalid", WebKitUrls.pageOrigin("https://plugin-1.invalid", plugin))
        assertEquals("https://app.example.com", WebKitUrls.pageOrigin("https://app.example.com", plugin))
        assertEquals("https://plugin-1.invalid", WebKitUrls.pageOrigin("https://plugin-1.invalid", null))
        assertEquals(
            setOf("https://c1.invalid", "https://app.example.com", "webview-bundle://c1.invalid"),
            WebKitUrls.pageOrigins(setOf("https://c1.invalid", "https://app.example.com")),
        )
    }

    @Test fun theFloorIsNeverPartOfALeasesHistory() {
        val floor = WebKitUrls.FLOOR_URL
        assertTrue(WebKitUrls.isFloor(floor))
        assertTrue(WebKitUrls.isPrivate(floor))
        assertTrue(WebKitUrls.isPrivate("webview-bundle://x.invalid/"))
        assertFalse(WebKitUrls.isPrivate("https://x.invalid/"))
        assertFalse(WebKitUrls.canGoBack(listOf(floor)))
        assertTrue(WebKitUrls.canGoBack(listOf(floor, "https://a.example/")))
        assertTrue(WebKitUrls.canGoBack(listOf("https://a.example/")))
        assertFalse(WebKitUrls.canGoBack(emptyList()))
        assertFalse(WebKitUrls.canGoForward(listOf(floor)))
        assertTrue(WebKitUrls.canGoForward(listOf("https://b.example/")))
    }

    @Test fun rawPathKeepsEscapesQueryAndFragment() {
        assertEquals("/a%2Fb/c.js?x=1#y", WebKitUrls.rawPath("webview-bundle://p.invalid/a%2Fb/c.js?x=1#y"))
        assertEquals("", WebKitUrls.rawPath("webview-bundle://p.invalid"))
        assertEquals("?x", WebKitUrls.rawPath("https://example.com?x"))
    }

    @Test fun urlsCompareAsWebKitHandsThemBack() {
        assertTrue(WebKitUrls.sameUrl("https://Example.com", "https://example.com/"))
        assertTrue(WebKitUrls.sameUrl("https://example.com:443/a", "https://example.com/a"))
        assertTrue(WebKitUrls.sameUrl("https://example.com?x=1", "https://example.com/?x=1"))
        assertTrue(WebKitUrls.sameUrl("https://c1.invalid/", "https://c1.invalid/"))
        assertFalse(WebKitUrls.sameUrl("https://example.com/a", "https://example.com/b"))
        assertFalse(WebKitUrls.sameUrl("https://example.com/", null))
    }
}
