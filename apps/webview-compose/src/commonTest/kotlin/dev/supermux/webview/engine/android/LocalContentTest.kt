package dev.supermux.webview.engine.android

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResourceProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class LocalContentTest {
    private val html = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val files = WebResourceProvider { null }
    private val bundle = WebContent.Bundle(WebOrigin("plugin"), files)

    @Test fun networkHostsAreTheNetworks() {
        assertEquals(LocalAnswer.Network, answerFor("https://example.com/", "GET", html))
        assertEquals(LocalAnswer.Network, answerFor("https://example.com/x.invalid/", "GET", bundle))
        assertEquals(LocalAnswer.Network, answerFor("data:text/html,hi", "GET", html))
    }

    @Test fun htmlIsServedAtItsRootOnly() {
        assertEquals("<p>hi</p>", assertIs<LocalAnswer.Html>(answerFor("https://demo.invalid/", "GET", html)).html)
        assertIs<LocalAnswer.Html>(answerFor("https://demo.invalid/?v=1#a", "GET", html))
        assertIs<LocalAnswer.Html>(answerFor("https://demo.invalid", "GET", html))
        assertEquals(LocalAnswer.NotFound, answerFor("https://demo.invalid/app.js", "GET", html))
        assertEquals(LocalAnswer.NotFound, answerFor("https://demo.invalid/favicon.ico", "GET", html))
    }

    @Test fun otherInvalidOriginsAreNotFoundNeverTheNetworks() {
        assertEquals(LocalAnswer.NotFound, answerFor("https://other.invalid/", "GET", html))
        assertEquals(LocalAnswer.NotFound, answerFor("http://demo.invalid/", "GET", html))
        assertEquals(LocalAnswer.NotFound, answerFor("https://demo.invalid./", "GET", html))
        assertEquals(LocalAnswer.NotFound, answerFor("https://demo.invalid:8443/", "GET", html))
        assertEquals(LocalAnswer.NotFound, answerFor("https://demo.invalid/", "GET", WebContent.Url("https://example.com/")))
        assertEquals(LocalAnswer.NotFound, answerFor("https://demo.invalid/", "GET", null))
    }

    @Test fun onlyGetAndHeadAreServed() {
        assertEquals(LocalAnswer.MethodNotAllowed, answerFor("https://demo.invalid/", "POST", html))
        assertIs<LocalAnswer.Html>(answerFor("https://demo.invalid/", "HEAD", html))
        assertIs<LocalAnswer.Html>(answerFor("https://demo.invalid/", "get", html))
    }

    @Test fun bundlesServeNormalizedPathsAndTheirEntryAtTheRoot() {
        val root = assertIs<LocalAnswer.BundleFile>(answerFor("https://plugin.invalid/", "GET", bundle))
        assertEquals("index.html", root.path)
        assertSame(files, root.resources)
        assertEquals("js/app.js", assertIs<LocalAnswer.BundleFile>(answerFor("https://plugin.invalid/js/app.js?v=3", "GET", bundle)).path)
        assertEquals("a b.css", assertIs<LocalAnswer.BundleFile>(answerFor("https://plugin.invalid/a%20b.css", "GET", bundle)).path)
    }

    @Test fun bundlePathsThatTryToLeaveAreNotFound() {
        assertEquals(LocalAnswer.NotFound, answerFor("https://plugin.invalid/%2e%2e%2fsecret", "GET", bundle))
        assertEquals(LocalAnswer.NotFound, answerFor("https://plugin.invalid/a%00.js", "GET", bundle))
        assertEquals(LocalAnswer.NotFound, answerFor("https://plugin.invalid/..%5cx", "GET", bundle))
    }

    @Test fun hostsCompareAsBrowsersCompareThem() {
        assertIs<LocalAnswer.BundleFile>(answerFor("https://PLUGIN.INVALID/x.js", "GET", bundle))
    }
}
