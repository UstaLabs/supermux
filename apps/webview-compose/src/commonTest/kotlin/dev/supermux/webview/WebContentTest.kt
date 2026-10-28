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

    @Test fun theSameHtmlAlwaysGetsTheSameOrigin() {
        assertEquals(WebOrigin.forContent("<p>hi</p>"), WebOrigin.forContent("<p>hi</p>"))
    }

    @Test fun differentHtmlGetsDifferentOrigins() {
        assertNotEquals(WebOrigin.forContent("<p>a</p>"), WebOrigin.forContent("<p>b</p>"))
    }

    @Test fun aContentOriginIsAValidLowercaseLabel() {
        val host = WebOrigin.forContent("anything at all").host
        assertEquals(17, host.length)
        assertTrue(host.all { it.isDigit() || it in 'a'..'z' })
    }

    @Test fun originAndUrlUseTheInvalidSuffix() {
        val origin = WebOrigin("demo-1")
        assertEquals("https://demo-1.webview.invalid", origin.origin)
        assertEquals("https://demo-1.webview.invalid/", origin.url)
    }

    @Test fun anOriginOwnsOnlyItsOwnUrls() {
        val origin = WebOrigin("demo")
        assertTrue(origin.owns("https://demo.webview.invalid"))
        assertTrue(origin.owns("https://demo.webview.invalid/a/b?c#d"))
        assertTrue(origin.owns("https://demo.webview.invalid?x"))
        assertFalse(origin.owns("https://demo.webview.invalid.evil.test/"))
        assertFalse(origin.owns("http://demo.webview.invalid/"))
    }

    @Test fun anInvalidHostIsRejected() {
        assertFailsWith<IllegalArgumentException> { WebOrigin("Bad_Host") }
        assertFailsWith<IllegalArgumentException> { WebOrigin("-x") }
        assertFailsWith<IllegalArgumentException> { WebOrigin("") }
    }

    @Test fun keysDistinguishContentKinds() {
        val origin = WebOrigin("p1")
        assertEquals("url:https://a.test/", WebContent.Url("https://a.test/").key)
        assertEquals("html:p1", WebContent.Html("<p/>", origin).key)
        assertEquals("bundle:p1/index.html", WebContent.Bundle(origin, NoResources).key)
    }
}
