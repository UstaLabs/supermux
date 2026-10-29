package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebContentTest {
    private object NoResources : WebResourceProvider {
        override suspend fun load(path: String): WebResource? = null
    }

    @Test fun contentOriginsComeFromSha256AndAreTheSameOnEveryPlatform() {
        assertEquals("cosbotl52d3xrikdmbhvwvhwpoq", WebOrigin.forContent("<p>hi</p>").host)
        assertEquals("c4oymiquy7qobjgx36tejs35zeq", WebOrigin.forContent("").host)
    }

    @Test fun differentHtmlGetsDifferentOrigins() {
        assertNotEquals(WebOrigin.forContent("<p>a</p>"), WebOrigin.forContent("<p>b</p>"))
    }

    @Test fun eachOriginIsItsOwnSiteUnderTheInvalidTopLevelDomain() {
        val origin = WebOrigin("demo-1")
        assertEquals("https://demo-1.invalid", origin.origin)
        assertEquals("https://demo-1.invalid/", origin.url)
    }

    @Test fun anOriginOwnsExactlyTheUrlsABrowserWouldPutOnIt() {
        val origin = WebOrigin("demo")
        listOf(
            "https://demo.invalid",
            "https://demo.invalid/a/b?c#d",
            "https://demo.invalid?x",
            "https://DEMO.invalid/x",
            "https://demo.invalid:443/x",
            "https:\\\\demo.invalid\\x",
            " https://demo.invalid/",
        ).forEach { assertTrue(origin.owns(it), it) }
        listOf(
            "https://demo.invalid.evil.test/",
            "http://demo.invalid/",
            "https://demo.invalid@evil.test/",
            "https://demo.invalid:8080/",
            "https://evil.test/#https://demo.invalid",
            "https://demo.invalid./",
            "blob:https://demo.invalid/x",
        ).forEach { assertFalse(origin.owns(it), it) }
    }

    @Test fun anInvalidHostIsRejected() {
        listOf("Bad_Host", "-x", "", "a.b", "x".repeat(64), "ok\n").forEach {
            assertFailsWith<IllegalArgumentException>(it) { WebOrigin(it) }
        }
    }

    @Test fun keysDistinguishKindsAndHtmlRevisions() {
        val origin = WebOrigin("p1")
        assertEquals("url:https://a.test/", WebContent.Url("https://a.test/").key)
        assertTrue(WebContent.Html("<p/>", origin).key.startsWith("html:p1:"))
        assertNotEquals(WebContent.Html("v1", origin).key, WebContent.Html("v2", origin).key)
        assertEquals(WebContent.Html("v1", origin).key, WebContent.Html("v1", origin).key)
        assertEquals("bundle:p1/index.html", WebContent.Bundle(origin, NoResources).key)
    }

    @Test fun urlAndBundleKeysAreBuiltOnce() {
        // Snapshot keys and page memories hold the content's key: the same string, not a fresh copy per read.
        val url = WebContent.Url("https://a.test/")
        assertSame(url.key, url.key)
        val bundle = WebContent.Bundle(WebOrigin("p1"), NoResources)
        assertSame(bundle.key, bundle.key)
    }

    @Test fun aUrlMustBeHttpOrHttps() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "", "example.test", "data:text/html,x", "https://", "http:").forEach {
            assertFailsWith<IllegalArgumentException>(it) { WebContent.Url(it) }
        }
        WebContent.Url("HTTPS://a.test/")
    }

    @Test fun printingContentLeaksNeitherHeaderValuesNorTheWholePage() {
        val url = WebContent.Url("https://a.test/", mapOf("Authorization" to "Bearer secret"))
        assertFalse("secret" in url.toString())
        assertTrue("Authorization" in url.toString())
        assertTrue(WebContent.Html("<p>" + "x".repeat(10_000) + "</p>").toString().length < 100)
        val secretUrl = WebContent.Url("https://user:pw@a.test/x?token=SECRET").toString()
        assertFalse("pw" in secretUrl || "SECRET" in secretUrl, secretUrl)
        assertTrue("https://a.test/x" in secretUrl, secretUrl)
    }

    @Test fun parseTurnsTypedTextIntoAUrlOrNull() {
        assertEquals(WebContent.Url("https://a.test/x"), WebContent.Url.parse("  https://a.test/x "))
        assertEquals(null, WebContent.Url.parse("example.test"))
        assertEquals(null, WebContent.Url.parse("javascript:alert(1)"))
        assertEquals(null, WebContent.Url.parse("https://"))
    }

    @Test fun aBundleIsItsOriginAndEntryWhicheverProviderServesIt() {
        val origin = WebOrigin("plug")
        val a = WebContent.Bundle(origin, object : WebResourceProvider { override suspend fun load(path: String): WebResource? = null })
        val b = WebContent.Bundle(origin, object : WebResourceProvider { override suspend fun load(path: String): WebResource? = null })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, WebContent.Bundle(origin, NoResources, entry = "other.html"))
    }

    @Test fun htmlEqualsByContentAndOrigin() {
        assertEquals(WebContent.Html("a"), WebContent.Html("a"))
        assertNotEquals(WebContent.Html("a"), WebContent.Html("a", WebOrigin("x")))
        assertEquals(WebOrigin.forContent("a"), WebContent.Html("a").origin)
    }
}
