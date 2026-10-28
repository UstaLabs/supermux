package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebContentTest {
    private object NoResources : WebResourceProvider {
        override suspend fun load(path: String): WebResource? = null
    }

    @Test fun contentOriginsComeFromSha256AndAreTheSameOnEveryPlatform() {
        assertEquals("cbjdtkka5w4aceoxwhk6dq7bvd5", WebOrigin.forContent("<p>hi</p>").host)
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

    @Test fun aUrlMustBeHttpOrHttps() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "", "example.test", "data:text/html,x").forEach {
            assertFailsWith<IllegalArgumentException>(it) { WebContent.Url(it) }
        }
        WebContent.Url("HTTPS://a.test/")
    }

    @Test fun printingContentLeaksNeitherHeaderValuesNorTheWholePage() {
        val url = WebContent.Url("https://a.test/", mapOf("Authorization" to "Bearer secret"))
        assertFalse("secret" in url.toString())
        assertTrue("Authorization" in url.toString())
        assertTrue(WebContent.Html("<p>" + "x".repeat(10_000) + "</p>").toString().length < 100)
    }
}
