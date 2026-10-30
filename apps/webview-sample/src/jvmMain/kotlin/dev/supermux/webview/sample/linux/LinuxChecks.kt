package dev.supermux.webview.sample.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/**
 * The Linux engine's conformance checks: the WebEngine contract with real WebKit pages, in the app, as iOS's
 * `EngineChecks` are, and named as they are where they check the same thing. Each goes through the public engine SPI
 * only, as the runtime does. Grouped by the plan task that brought them.
 */
object LinuxChecks {
    /** Starts [content] in [engine] as the runtime does: configured, then loaded. */
    internal fun start(engine: WebEngine, content: WebContent, javaScript: Boolean = true) {
        engine.configure(setupFor(content, javaScript))
        engine.load(content)
    }

    /** [engine] loads [content] on screen, and returns its recorder once the page has painted and announced itself. */
    internal suspend fun CheckScope.live(engine: WebEngine, content: WebContent): Recorder {
        val events = record(engine)
        show(engine)
        start(engine, content)
        events.await("first paint") { it == EngineEvent.FirstPaint }
        events.await("\$ready", matches = ::isReady)
        return events
    }

    /** Creation, commits, the bridge both ways and its isolation, documents (Task 23). */
    val bridge: List<Check> = listOf(
        Check("factory-makes-a-frames-engine") {
            val engine = engine()
            assertTrue(engine.isUsable)
            assertEquals(EmbeddingMode.Frames, engine.mode)
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
        Check("the-bridge-delivers-to-the-current-document") {
            val engine = engine()
            val events = live(engine, echoPage)
            val ready = events.await("\$ready", matches = ::isReady) as EngineEvent.PageMessage
            assertEquals(echoPage.origin.origin, ready.origin)
            engine.postMessage(hostEvent("ping", 7, nonceOf(ready)), echoPage.origin.origin)
            events.await("pong") { isPong(it, 7) }
        },
        Check("delivery-checks-the-origin-when-it-arrives") {
            val engine = engine()
            val events = live(engine, echoPage)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            // The page lies about its origin in its own world; the delivering script, in the engine's world, can't be fooled.
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
        Check("titles-and-same-document-urls-are-reported") {
            val engine = engine()
            val content = page("titled")
            val events = live(engine, content)
            events.await("the page's title") { it == EngineEvent.TitleChanged("titled") }
            assertEquals("\"ok\"", engine.evaluate("history.pushState({}, '', '#moved'); document.title = 'moved'; 'ok'"))
            events.await("the same-document URL") { it == EngineEvent.UrlChanged(content.origin.url + "#moved") }
            events.await("the new title") { it == EngineEvent.TitleChanged("moved") }
        },
        Check("a-bundle-is-served-on-its-scheme-and-reported-as-its-origin") {
            val bundle = CheckBundle("plugin-check")
            val engine = engine()
            val events = live(engine, bundle.content)
            val ready = events.events.first(::isReady) as EngineEvent.PageMessage
            assertEquals("https://plugin-check.invalid", ready.origin)
            assertTrue(EngineEvent.UrlChanged("https://plugin-check.invalid/index.html") in events.events, "${events.events}")
            assertEquals("\"webview-bundle://plugin-check.invalid\"", engine.evaluate("self.origin"))
            engine.evaluate(
                "window.__statuses = null; Promise.all([fetch('data.json'), fetch('/%2e%2e%2fsecret')])" +
                    ".then(function (r) { window.__statuses = r[0].status + '|' + r[1].status; }); 1",
            )
            var statuses: String? = "null"
            repeat(60) { if (statuses == "null") statuses = engine.evaluate("window.__statuses").also { delay(50) } }
            assertEquals("\"200|404\"", statuses)
            assertTrue(bundle.asked.none { ".." in it }, "asked: ${bundle.asked}")
        },
    )

    /** Every check, in the order of the tasks that brought them. */
    val all: List<Check> get() = bridge
}
