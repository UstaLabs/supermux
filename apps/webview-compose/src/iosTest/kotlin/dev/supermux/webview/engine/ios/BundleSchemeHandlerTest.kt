package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLErrorCannotFindHost
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleSchemeHandlerTest {
    private val asked = mutableListOf<String>()
    private var gate: CompletableDeferred<Unit>? = null
    private val files = WebResourceProvider { path ->
        asked += path
        gate?.await()
        when (path) {
            "index.html" -> WebResource("<p>hi</p>".encodeToByteArray(), "text/html; charset=utf-8", mapOf("Content-Security-Policy" to "default-src 'self'"))
            "data.json" -> WebResource("{}".encodeToByteArray(), "application/json")
            else -> null
        }
    }
    private val handler = BundleSchemeHandler(CoroutineScope(SupervisorJob() + Dispatchers.Main)).also {
        it.bundle = WebContent.Bundle(WebOrigin("plugin-1"), files)
    }
    private val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 10.0, 10.0), configuration = WKWebViewConfiguration())

    private fun serve(url: String): FakeTask {
        val task = FakeTask(url)
        handler.webView(webView, startURLSchemeTask = task)
        pumpUntil { task.finished || task.error != null }
        return task
    }

    private val FakeTask.status: Long? get() = (response as? NSHTTPURLResponse)?.statusCode

    @Test fun aFileOfTheBundleIsServedWithItsTypeAndHeaders() {
        val task = serve("webview-bundle://plugin-1.invalid/index.html")
        assertEquals(200L, task.status)
        assertEquals("<p>hi</p>", task.body.decodeToString())
        val headers = (task.response as NSHTTPURLResponse).allHeaderFields
        assertEquals("text/html; charset=utf-8", headers["Content-Type"])
        assertEquals("default-src 'self'", headers["Content-Security-Policy"])
        assertEquals("9", headers["Content-Length"])
    }

    @Test fun theRootServesTheEntry() {
        assertEquals("<p>hi</p>", serve("webview-bundle://plugin-1.invalid/").body.decodeToString())
    }

    @Test fun aMissingFileIsA404() {
        assertEquals(404L, serve("webview-bundle://plugin-1.invalid/nope.js").status)
    }

    @Test fun aTraversalNeverReachesTheProvider() {
        assertEquals(404L, serve("webview-bundle://plugin-1.invalid/%2e%2e%2fsecret").status)
        assertEquals(404L, serve("webview-bundle://plugin-1.invalid/a%5c..%5csecret").status)
        assertTrue(asked.none { ".." in it || '\\' in it }, "asked: $asked")
    }

    @Test fun anotherBundleOrNoneIsAnUnknownHost() {
        assertEquals(NSURLErrorCannotFindHost, serve("webview-bundle://plugin-2.invalid/index.html").error?.code)
        handler.bundle = null
        assertEquals(NSURLErrorCannotFindHost, serve("webview-bundle://plugin-1.invalid/index.html").error?.code)
    }

    @Test fun aStoppedTaskIsNeverAnswered() {
        gate = CompletableDeferred()
        val task = FakeTask("webview-bundle://plugin-1.invalid/data.json")
        handler.webView(webView, startURLSchemeTask = task)
        pumpUntil { asked.isNotEmpty() }
        handler.webView(webView, stopURLSchemeTask = task)
        gate?.complete(Unit)
        pumpUntil(0.3) { false }
        assertNull(task.response)
        assertTrue(!task.finished)
    }

    @Test fun aBundleFileAnswersAfterTheProviderDoes() {
        gate = CompletableDeferred()
        val task = FakeTask("webview-bundle://plugin-1.invalid/data.json")
        handler.webView(webView, startURLSchemeTask = task)
        pumpUntil(0.2) { false }
        assertNull(task.response)
        gate?.complete(Unit)
        pumpUntil { task.finished }
        assertNotNull(task.response)
        assertEquals("{}", task.body.decodeToString())
    }
}
