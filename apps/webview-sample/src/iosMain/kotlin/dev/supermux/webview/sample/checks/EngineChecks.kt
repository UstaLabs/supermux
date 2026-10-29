package dev.supermux.webview.sample.checks

import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
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

    val all: List<Check> = basics
}
