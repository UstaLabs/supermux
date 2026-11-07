package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLErrorCannotFindHost
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.AfterTest
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
            "clip.bin" -> WebResource(ByteArray(10) { it.toByte() }, "video/mp4")
            else -> null
        }
    }
    private val handler = BundleSchemeHandler(CoroutineScope(SupervisorJob() + Dispatchers.Main)).also {
        it.bundle = WebContent.Bundle(WebOrigin("plugin-1"), files)
    }
    private val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 10.0, 10.0), configuration = WKWebViewConfiguration())

    private fun serve(url: String, headers: Map<String, String> = emptyMap()): FakeTask {
        val task = FakeTask(url, headers)
        handler.webView(webView, startURLSchemeTask = task)
        pumpUntil { task.finished || task.error != null }
        return task
    }

    private val FakeTask.status: Long? get() = (response as? NSHTTPURLResponse)?.statusCode

    @AfterTest fun freeWhatTheTestLeft() = collectGarbage()

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

    private fun FakeTask.header(name: String): Any? = (response as NSHTTPURLResponse).allHeaderFields[name]

    /** Media (a bundled `<video>`) loads in ranges: each is answered with its own bytes, as a server that has them would. */
    @Test fun aRangeIsAnsweredWithItsBytes() {
        val clip = "webview-bundle://plugin-1.invalid/clip.bin"
        val middle = serve(clip, mapOf("Range" to "bytes=2-5"))
        assertEquals(206L, middle.status)
        assertEquals(listOf<Byte>(2, 3, 4, 5), middle.body.toList())
        assertEquals("bytes 2-5/10", middle.header("Content-Range"))
        assertEquals("4", middle.header("Content-Length"))
        assertEquals("video/mp4", middle.header("Content-Type"))
        assertEquals(listOf<Byte>(7, 8, 9), serve(clip, mapOf("Range" to "bytes=7-")).body.toList(), "to the end")
        assertEquals(listOf<Byte>(8, 9), serve(clip, mapOf("Range" to "bytes=-2")).body.toList(), "the last bytes")
        assertEquals(listOf<Byte>(8, 9), serve(clip, mapOf("Range" to "bytes=8-99")).body.toList(), "cut at the end")
        val whole = serve(clip)
        assertEquals(200L, whole.status)
        assertEquals("bytes", whole.header("Accept-Ranges"), "it says ranges can be asked for")
    }

    @Test fun aRangeBeyondTheFileIsUnsatisfiable() {
        val task = serve("webview-bundle://plugin-1.invalid/clip.bin", mapOf("Range" to "bytes=10-"))
        assertEquals(416L, task.status)
        assertEquals("bytes */10", task.header("Content-Range"))
    }

    /** What isn't one plain range (several, or another unit) gets the whole file, as HTTP lets a server do. */
    @Test fun aRangeItCantServeGetsTheWholeFile() {
        for (range in listOf("bytes=0-1,4-5", "items=0-1", "bytes=x-y")) {
            val task = serve("webview-bundle://plugin-1.invalid/clip.bin", mapOf("Range" to range))
            assertEquals(200L, task.status, range)
            assertEquals(10, task.body.size, range)
        }
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

    /**
     * A provider that goes on after its task stopped (one that ignores cancellation, as blocking IO does) gets no answer
     * out to WebKit: touching a stopped task raises.
     */
    @Test fun aStoppedTaskIsNeverAnsweredEvenByAProviderThatGoesOn() {
        val going = CompletableDeferred<Unit>()
        val handler = BundleSchemeHandler(CoroutineScope(SupervisorJob() + Dispatchers.Main)).also {
            it.bundle = WebContent.Bundle(
                WebOrigin("plugin-1"),
                WebResourceProvider { withContext(NonCancellable) { going.await() }; WebResource("{}".encodeToByteArray(), "application/json") },
            )
        }
        val task = FakeTask("webview-bundle://plugin-1.invalid/data.json")
        handler.webView(webView, startURLSchemeTask = task)
        pumpUntil(0.2) { false }
        handler.webView(webView, stopURLSchemeTask = task)
        going.complete(Unit)
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
