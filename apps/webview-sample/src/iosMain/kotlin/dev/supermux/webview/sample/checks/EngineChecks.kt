package dev.supermux.webview.sample.checks

import dev.supermux.webview.DialogResult
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS engine's conformance checks: the WebEngine contract, with real WebKit pages, in the app (a Kotlin/Native test
 * process can't load a page). Each goes through the public engine SPI only, as the runtime does.
 */
object EngineChecks {
    /** Starts [content] in [engine] as the runtime does: configured, then loaded. */
    private fun start(engine: WebEngine, content: WebContent, javaScript: Boolean = true) {
        engine.configure(setupFor(content, javaScript))
        engine.load(content)
    }

    /** [engine] loads [content] on screen, and returns its recorder once the page has painted and announced itself. */
    private suspend fun CheckScope.live(engine: WebEngine, content: WebContent): Recorder {
        val events = record(engine)
        show(engine)
        start(engine, content)
        events.await("first paint") { it == EngineEvent.FirstPaint }
        events.await("\$ready", matches = ::isReady)
        return events
    }

    /** Creation, commits, first paint, and the bridge both ways (Task 15). */
    val basics: List<Check> = listOf(
        Check("factory-makes-a-native-view-engine") {
            val engine = engine()
            assertTrue(engine.isUsable)
            assertEquals(EmbeddingMode.NativeView, engine.mode)
            assertFalse(engine.needsCutouts)
        },
        Check("html-commits-on-its-secure-origin") {
            val engine = engine()
            val events = record(engine)
            val content = page("origin")
            start(engine, content)
            events.await("the commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            assertEquals("\"${content.origin.origin}|true\"", engine.evaluate("self.origin + '|' + isSecureContext"))
        },
        Check("first-paint-waits-for-the-screen") {
            val engine = engine()
            val events = record(engine)
            start(engine, page("paint"))
            assertNull(engine.snapshot(), "no snapshot before the first paint")
            events.expectNone("first paint off screen", from = 0) { it == EngineEvent.FirstPaint }
            show(engine)
            events.await("first paint on screen") { it == EngineEvent.FirstPaint }
            val image = assertNotNull(engine.snapshot())
            assertTrue(image.width in 320..640, "a 320-point page at most 2x: ${image.width} px")
        },
        Check("the-bridge-delivers-to-the-current-document") {
            val engine = engine()
            val events = live(engine, echoPage)
            val ready = events.await("\$ready", matches = ::isReady) as EngineEvent.PageMessage
            assertEquals(echoPage.origin.origin, ready.origin)
            engine.postMessage(hostEvent("ping", 7, nonceOf(ready)), echoPage.origin.origin)
            events.await("pong") { isPong(it, 7) }
        },
    )

    /** The bridge's isolation and delivery, documents, and the navigation policy (Task 16). */
    val bridgeAndPolicy: List<Check> = listOf(
        Check("delivery-checks-the-origin-when-it-arrives") {
            val engine = engine()
            val events = live(engine, echoPage)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            // The page lies about its origin in its own world; the delivering script, in the isolated world, can't be fooled.
            engine.evaluate("Object.defineProperty(window, 'origin', { get: function () { return 'https://evil.example'; } }); 1")
            val before = events.events.size
            engine.postMessage(hostEvent("ping", 1, nonce), "https://evil.example")
            events.expectNone("a pong for the wrong origin", from = before) { isPong(it, 1) }
            engine.postMessage(hostEvent("ping", 2, nonce), echoPage.origin.origin)
            events.await("pong", from = before) { isPong(it, 2) }
        },
        Check("page-scripts-cannot-reach-the-channel") {
            val engine = engine()
            live(engine, echoPage)
            assertEquals("true", engine.evaluate("!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.webview)"))
            assertEquals("\"object\"", engine.evaluate("typeof hostBridge"))
        },
        Check("subframes-get-no-shim") {
            val engine = engine()
            live(engine, page("frames", body = "<iframe srcdoc='<p>inner</p>'></iframe>"))
            delay(300)
            assertEquals("\"undefined\"", engine.evaluate("typeof document.querySelector('iframe').contentWindow.hostBridge"))
        },
        Check("javascript-off-means-no-shim-and-no-page-scripts") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, page("js-off", script = "document.title = 'ran';"), javaScript = false)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            assertEquals("\"undefined\"", engine.evaluate("typeof hostBridge"))
            assertEquals("\"js-off\"", engine.evaluate("document.title"))
            assertTrue(events.events.none(::isReady))
        },
        Check("a-load-leaves-the-old-documents-events-behind") {
            val engine = engine()
            live(engine, page("chatty", script = "setInterval(function () { hostBridge.post('tick', 1); }, 5);"))
            val next = record(engine)
            engine.load(page("quiet"))
            next.await("the new commit") { it is EngineEvent.UrlChanged }
            next.expectNone("the old document's ticks", from = 0, millis = 1_000) {
                it is EngineEvent.PageMessage && it.json.contains("\"tick\"")
            }
        },
        Check("only-page-navigations-ask-the-client") {
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = live(engine, page("nav"))
            val before = events.events.size
            engine.evaluate("location.href = 'https://example.org/'; 1")
            events.expectNone("a navigation the client blocked", from = before) { it == EngineEvent.UrlChanged("https://example.org/") }
            assertTrue(NavigationRequest("https://example.org/", isMainFrame = true, isUserGesture = false) in client.asked, "${client.asked}")
        },
        Check("private-schemes-are-refused-whatever-the-client-says") {
            val engine = engine()
            engine.client = CheckClient(navigation = NavigationDecision.Allow)
            val events = live(engine, page("private"))
            val before = events.events.size
            engine.evaluate("location.href = 'webview-floor://reset/'; 1")
            engine.evaluate("location.href = 'webview-bundle://other.invalid/'; 1")
            events.expectNone("a navigation to a private scheme", from = before) { it is EngineEvent.UrlChanged }
        },
        Check("new-windows-ask-the-client") {
            val engine = engine()
            val client = CheckClient()
            engine.client = client
            live(engine, page("popup"))
            engine.evaluate("window.open('https://example.org/popup'); 1")
            delay(500)
            assertTrue(client.asked.any { it is NewWindowRequest && it.url == "https://example.org/popup" }, "${client.asked}")
        },
        Check("dialogs-reach-the-client") {
            val engine = engine()
            val client = CheckClient(dialog = CompletableDeferred(DialogResult.Confirmed))
            engine.client = client
            live(engine, page("dialogs"))
            assertEquals("true", engine.evaluate("confirm('Sure?')"))
            assertEquals(WebDialog.Confirm("Sure?", page("dialogs").origin.url), client.asked.filterIsInstance<WebDialog>().single())
        },
    )

    val all: List<Check> = basics + bridgeAndPolicy
}
