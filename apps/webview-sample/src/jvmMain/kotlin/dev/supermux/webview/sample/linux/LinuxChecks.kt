package dev.supermux.webview.sample.linux

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
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

    /** A page of one colour, edge to edge, with [body] over it. */
    internal fun solidPage(title: String, color: String, body: String = "", script: String = ""): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>$title</title><style>html,body{margin:0;height:100%;background:$color}</style></head>
<body>$body<script>$script</script></body></html>""",
    )

    /** Whether two `0xRRGGBB` colours are within [tolerance] in each channel. */
    internal fun near(expected: Int, actual: Int, tolerance: Int = 3): Boolean =
        (0..2).all { channel -> abs((expected shr (8 * channel) and 0xff) - (actual shr (8 * channel) and 0xff)) <= tolerance }

    /** Waits up to [millis] for [condition], looking every 50 ms. */
    internal suspend fun eventually(what: String, millis: Long = 3_000, condition: suspend () -> Boolean) {
        val until = System.nanoTime() + millis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("$what: not within $millis ms")
            delay(50)
        }
    }

    /** The surface: frames on screen, size, pointer, keys, wheel, focus (Task 24). */
    val surface: List<Check> = listOf(
        Check("the-surface-draws-the-page") {
            val engine = engine()
            live(engine, solidPage("blue", "#3366cc"))
            eventually("the page's colour on screen") { near(0x3366cc, pixel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)) }
        },
        Check("a-new-engines-first-page-is-laid-out-at-its-surfaces-size") {
            // Loaded before its surface is laid out, as the runtime loads a view's first page: laid out at the surface's
            // size, not at the size the view had when the load began.
            val engine = engine()
            val events = record(engine)
            start(engine, page("scale"))
            show(engine)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            assertEquals("$SURFACE_WIDTH", engine.evaluate("innerWidth"), "the page's width, in CSS pixels")
            assertEquals("$SURFACE_HEIGHT", engine.evaluate("innerHeight"))
            assertEquals("1", engine.evaluate("devicePixelRatio"))
        },
        Check("a-click-reaches-the-page") {
            val engine = engine()
            val button = "<button style='position:fixed;left:0;top:0;width:100%;height:100%' " +
                "onclick=\"document.title='clicked'\">click</button>"
            val events = live(engine, solidPage("click", "#ffffff", body = button))
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            events.await("the click's title") { it == EngineEvent.TitleChanged("clicked") }
        },
        Check("typing-reaches-a-focused-field") {
            val engine = engine()
            val field = "<input id=f style='position:fixed;left:0;top:0;width:100%;height:60px;font-size:24px'>"
            live(engine, solidPage("type", "#ffffff", body = field))
            click(SURFACE_WIDTH / 2, 30)
            eventually("the field focused") { engine.evaluate("document.activeElement.id") == "\"f\"" }
            type("Merhaba çğış İÖÜ")
            eventually("the typed text") { engine.evaluate("document.getElementById('f').value") == "\"Merhaba çğış İÖÜ\"" }
        },
        Check("the-wheel-scrolls-the-page") {
            val engine = engine()
            live(engine, solidPage("tall", "#ffffff", body = "<div style='height:4000px;background:linear-gradient(#fff,#000)'></div>"))
            wheel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2, 3)
            eventually("the page scrolled down") { (engine.evaluate("scrollY")?.toDoubleOrNull() ?: 0.0) > 0.0 }
        },
        Check("a-click-focuses-the-page") {
            val engine = engine()
            val events = live(engine, solidPage("focus", "#ffffff"))
            click(10, 10)
            events.await("focus") { it == EngineEvent.FocusChanged(true) }
        },
    )

    /** The handoff from the cover, and snapshots (Task 25). */
    val handoff: List<Check> = listOf(
        Check("first-paint-waits-for-the-screen") {
            // Off screen even the 3 s fallback waits: the first paint means drawn in place.
            val engine = engine()
            val events = record(engine)
            start(engine, page("paint"))
            assertNull(engine.snapshot(), "no snapshot before the first paint")
            events.expectNone("first paint off screen", from = 0, millis = 4_000) { it == EngineEvent.FirstPaint }
            show(engine)
            events.await("first paint on screen") { it == EngineEvent.FirstPaint }
            val image = assertNotNull(engine.snapshot())
            assertEquals(SURFACE_WIDTH to SURFACE_HEIGHT, image.width to image.height)
        },
        Check("the-page-is-on-screen-at-its-first-paint") {
            // A new engine shows its floor first: a first paint said before the page's own frame reached the screen
            // would find white here, not the page.
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, solidPage("blue", "#3366cc"))
            events.await("first paint") { it == EngineEvent.FirstPaint }
            val seen = pixel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            assertTrue(near(0x3366cc, seen), "the screen at the first paint: #${seen.toString(16)}")
        },
        Check("first-paint-waits-for-the-pages-content") {
            // A page with nothing to show but an image, served 1.5 s late: parsed at once, it paints only with its image.
            val engine = engine()
            val events = record(engine)
            val bundle = SlowImageBundle("late-image", delayMillis = 1_500)
            show(engine)
            start(engine, bundle.content)
            events.await("first paint", millis = 10_000) { it == EngineEvent.FirstPaint }
            val painted = bundle.elapsed()
            val served = assertNotNull(bundle.servedAt, "the first paint ($painted) came before the image was served")
            assertTrue(painted > served, "the first paint at $painted, the image served at $served")
        },
        Check("a-page-that-never-finishes-is-shown-after-three-seconds") {
            // Nothing contentful ever paints and it never loads (its image never comes): shown once 3 s have passed.
            val engine = engine()
            val events = record(engine)
            val bundle = SlowImageBundle("never-image", delayMillis = null)
            show(engine)
            start(engine, bundle.content)
            events.await("first paint", millis = 10_000) { it == EngineEvent.FirstPaint }
            val painted = bundle.elapsed()
            assertTrue(painted >= 2.9.seconds && painted <= 6.seconds, "the first paint at $painted")
        },
        Check("a-snapshot-is-the-page-as-drawn") {
            val engine = engine()
            live(engine, solidPage("blue", "#3366cc"))
            val image = assertNotNull(engine.snapshot())
            assertEquals(SURFACE_WIDTH to SURFACE_HEIGHT, image.width to image.height)
            val color = image.toPixelMap()[SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2].toArgb() and 0xFFFFFF
            assertTrue(near(0x3366cc, color), "the snapshot's colour: #${color.toString(16)}")
            show(null)
            delay(300)
            assertNull(engine.snapshot(), "no surface, no snapshot")
        },
    )

    /** Every check, in the order of the tasks that brought them. */
    val all: List<Check> get() = bridge + surface + handoff
}
