package dev.supermux.webview.sample.linux

import com.sun.net.httpserver.HttpServer
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDialog
import dev.supermux.webview.engine.EngineClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
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

    /** Reset, restore, profiles, release, and a hidden page (Task 26). */
    val lifecycle: List<Check> = listOf(
        Check("reset-refuses-the-history-before-it") {
            val engine = engine(WebProfile.Default)
            live(engine, page("before"))
            engine.evaluate("location.hash = 'two'; 1")
            delay(300)
            assertTrue(engine.reset(), "reset")
            val after = page("after")
            val events = live(engine, after)
            val history = events.events.filterIsInstance<EngineEvent.HistoryChanged>().last()
            assertFalse(history.canGoBack, "history begins with the first load after the reset")
            val before = events.events.size
            engine.evaluate("history.back(); 1")
            engine.goBack()
            events.expectNone("a navigation into the history before the reset", from = before) { it is EngineEvent.UrlChanged }
            assertEquals("\"${after.origin.url}\"", engine.evaluate("location.href"))
        },
        Check("reset-wipes-its-own-ephemeral-store-only") {
            val content = page("storage")
            val first = engine(WebProfile.Ephemeral)
            val second = engine(WebProfile.Ephemeral)
            live(first, content)
            first.evaluate("localStorage.setItem('k', 'first'); 1")
            live(second, content)
            second.evaluate("localStorage.setItem('k', 'second'); 1")
            assertTrue(first.reset(), "reset")
            live(first, content)
            assertEquals("null", first.evaluate("localStorage.getItem('k')"))
            assertEquals("\"second\"", second.evaluate("localStorage.getItem('k')"))
        },
        Check("restore-brings-back-a-bundles-history") {
            val bundle = CheckBundle("plugin-restore")
            val first = engine()
            first.client = CheckClient(navigation = NavigationDecision.Allow)
            val events = live(first, bundle.content)
            first.evaluate("document.getElementById('next').click(); 1")
            events.await("page 2") { it == EngineEvent.UrlChanged("https://plugin-restore.invalid/page2.html") }
            val state = assertNotNull(first.saveState())
            val second = engine()
            val restored = record(second)
            second.configure(setupFor(bundle.content))
            assertFalse(second.restoreState(state, CheckBundle("plugin-other").content), "another content's state")
            assertTrue(second.restoreState(state, bundle.content))
            restored.await("the restored page") { it == EngineEvent.UrlChanged("https://plugin-restore.invalid/page2.html") }
            restored.await("history to go back to") { it is EngineEvent.HistoryChanged && it.canGoBack }
            second.goBack()
            restored.await("the entry") { it == EngineEvent.UrlChanged("https://plugin-restore.invalid/index.html") }
        },
        Check("in-memory-html-saves-no-engine-state") {
            val engine = engine()
            live(engine, page("html-state"))
            assertNull(engine.saveState())
        },
        Check("named-profiles-are-separate") {
            val content = page("profiles")
            val a = engine(WebProfile.Named("check-a"))
            val b = engine(WebProfile.Named("check-b"))
            live(a, content)
            a.evaluate("localStorage.setItem('who', 'a'); 1")
            live(b, content)
            assertEquals("null", b.evaluate("localStorage.getItem('who')"))
            a.evaluate("localStorage.clear(); 1")
        },
        Check("release-is-final") {
            val engine = engine()
            engine.release()
            assertFalse(engine.isUsable)
            engine.load(page("after release"))
            assertNull(engine.evaluate("1"))
        },
        Check("a-hidden-page-is-hidden-from-its-scripts") {
            val engine = engine()
            live(engine, page("hidden"))
            engine.setVisible(false)
            eventually("hidden") { engine.evaluate("document.visibilityState") == "\"hidden\"" }
            engine.setVisible(true)
            eventually("visible again") { engine.evaluate("document.visibilityState") == "\"visible\"" }
        },
    )

    /** Parks [engine]'s live page as the runtime does when its view leaves: unbound, hidden, no surface. */
    private fun CheckScope.park(engine: WebEngine) {
        stopRecording(engine)
        engine.client = null
        engine.setVisible(false)
        show(null)
    }

    /** A new view adopts [engine]'s parked page as the runtime does: subscribed anew, replayed, on screen, shown. */
    private fun CheckScope.adopt(engine: WebEngine): Recorder {
        val events = record(engine)
        engine.replayState()
        show(engine)
        engine.setVisible(true)
        return events
    }

    /** Keeping pages alive: a parked page, adopted by a new view, unreloaded (Task 26). */
    val keepAlive: List<Check> = listOf(
        Check("a-parked-page-is-adopted-live-with-its-state") {
            // A plugin's page: loaded with a request, so a same-document URL is an entry in WebKit's history too.
            val bundle = CheckBundle("plugin-kept")
            val engine = engine()
            val events = live(engine, bundle.content)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            // What only this document has: a value, a scroll position, a same-document URL and a title.
            val steps = listOf(
                "window.__kept = 42",
                "document.body.style.height = '3000px'",
                "scrollTo(0, 600)",
                "history.pushState({}, '', '#kept')",
                "document.title = 'kept now'",
            )
            for (step in steps) assertEquals("\"ok\"", engine.evaluate("try { $step; 'ok' } catch (e) { String(e) }"), step)
            val moved = EngineEvent.UrlChanged("https://plugin-kept.invalid/index.html#kept")
            events.await("the same-document URL") { it == moved }
            events.await("the title") { it == EngineEvent.TitleChanged("kept now") }
            events.await("history to go back to") { it == EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false) }
            park(engine)
            delay(1_000)
            val adopted = adopt(engine)
            adopted.await("the replayed URL") { it == moved }
            adopted.await("the replayed history") { it == EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false) }
            adopted.await("the replayed title") { it == EngineEvent.TitleChanged("kept now") }
            adopted.await("the replayed first paint") { it == EngineEvent.FirstPaint }
            assertTrue(adopted.events.none { it is EngineEvent.Progress }, "no navigation: ${adopted.events}")
            assertEquals("\"42|600\"", engine.evaluate("window.__kept + '|' + scrollY"), "the same document, scrolled as it was")
            // Its bridge is the same document's too: its nonce still reaches it.
            engine.postMessage(hostEvent("ping", 5, nonce), bundle.content.origin.origin)
            adopted.await("pong") { isPong(it, 5) }
        },
        Check("an-adopted-pages-first-paint-waits-for-the-screen") {
            val engine = engine()
            val content = page("parked")
            live(engine, content)
            park(engine)
            delay(500)
            val events = record(engine)
            // Adopted by a view that isn't on screen yet: the page is told, but it can't have been drawn there.
            engine.replayState()
            events.await("the replayed commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            events.expectNone("a first paint off screen", from = 0) { it == EngineEvent.FirstPaint }
            show(engine)
            engine.setVisible(true)
            events.await("the first paint on screen") { it == EngineEvent.FirstPaint }
            assertNotNull(engine.snapshot(), "a snapshot, once drawn again")
        },
    )

    /** The page's requests: navigations, private schemes, windows, dialogs, permissions, files, downloads (Task 27). */
    val requests: List<Check> = listOf(
        Check("only-page-navigations-ask-the-client") {
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = live(engine, page("nav"))
            val target = "${LocalServer.origin}/page"
            val before = events.events.size
            engine.evaluate("location.href = '$target'; 1")
            events.expectNone("a navigation the client blocked", from = before) { it == EngineEvent.UrlChanged(target) }
            assertTrue(client.asked.any { it is NavigationRequest && it.url == target && it.isMainFrame }, "${client.asked}")
        },
        Check("a-subframe-is-asked-about-as-one") {
            // W4: the frame is known at the response, so a subframe's navigation is asked as one, and then refused.
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/framed"))
            events.await("first paint") { it == EngineEvent.FirstPaint }
            val sub = "${LocalServer.origin}/sub"
            eventually("the subframe asked about") { client.asked.any { it is NavigationRequest && it.url == sub && !it.isMainFrame } }
            assertTrue(client.asked.none { it is NavigationRequest && it.url.endsWith("/framed") }, "the engine's own load isn't asked")
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
            val button = "<button style='position:fixed;left:0;top:0;width:100%;height:100%' " +
                "onclick=\"window.open('https://example.org/popup')\">open</button>"
            live(engine, solidPage("popup", "#ffffff", body = button))
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            eventually("the client asked") {
                client.asked.any { it is NewWindowRequest && it.url == "https://example.org/popup" && it.isUserGesture }
            }
        },
        Check("dialogs-reach-the-client") {
            val engine = engine()
            val client = CheckClient(dialog = CompletableDeferred(DialogResult.Confirmed))
            engine.client = client
            live(engine, page("dialogs"))
            assertEquals("true", engine.evaluate("confirm('Sure?')"))
            assertEquals(WebDialog.Confirm("Sure?", page("dialogs").origin.url), client.asked.filterIsInstance<WebDialog>().single())
        },
        Check("reset-answers-a-pending-dialog") {
            val engine = engine()
            engine.client = CheckClient(dialog = CompletableDeferred())
            live(engine, page("stuck"))
            coroutineScope {
                // The alert holds the page's script until it is answered: the reset answers it, so the evaluation ends.
                val alert = async { engine.evaluate("alert('never answered'); 'done'") }
                delay(500)
                assertTrue(engine.reset(), "reset")
                withTimeout(5_000) { alert.await() }
            }
        },
        Check("permissions-ask-the-client") {
            val engine = engine()
            val client = CheckClient()
            engine.client = client
            val content = page("permission")
            live(engine, content)
            engine.evaluate("Notification.requestPermission().then(function (p) { document.title = p; }); 1")
            eventually("the page told no") { engine.evaluate("document.title") == "\"denied\"" }
            assertTrue(PermissionRequest(content.origin.origin, setOf(PermissionKind.Notifications)) in client.asked, "${client.asked}")
        },
        Check("a-file-chooser-asks-the-client-and-takes-its-files") {
            val picked = File(System.getProperty("user.home"), "pick.txt").apply { writeText("picked") }
            val asked = mutableListOf<FileChooserRequest>()
            val engine = engine()
            engine.client = object : EngineClient by CheckClient() {
                override suspend fun onFileChooser(request: FileChooserRequest): List<String> {
                    asked += request
                    return listOf(picked.toURI().toString())
                }
            }
            val input = "<input type=file id=f accept='text/plain' style='position:fixed;left:0;top:0;width:100%;height:100%'>"
            live(engine, solidPage("files", "#ffffff", body = input))
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            val name = "(function (f) { return f.files.length ? f.files[0].name : ''; })(document.getElementById('f'))"
            eventually("the chosen file") { engine.evaluate(name) == "\"pick.txt\"" }
            assertEquals(listOf(FileChooserRequest(listOf("text/plain"), allowMultiple = false)), asked)
        },
        Check("a-download-is-refused-and-the-client-told") {
            val engine = engine()
            val client = CheckClient()
            engine.client = client
            val events = live(engine, page("download"))
            val target = "${LocalServer.origin}/file.zip"
            engine.evaluate("location.href = '$target'; 1")
            eventually("the client told") { client.asked.any { it is DownloadRequest && it.url == target } }
            assertEquals("file.zip", client.asked.filterIsInstance<DownloadRequest>().single().suggestedFileName)
            events.expectNone("a commit of the download", from = 0, millis = 500) { it == EngineEvent.UrlChanged(target) }
        },
    )

    /** Every check, in the order of the tasks that brought them. */
    val all: List<Check> get() = bridge + surface + handoff + lifecycle + keepAlive + requests
}

/** A web server on the container's loopback, for what a page navigates to: WebKit asks about it at its response (W4). */
internal object LocalServer {
    private class Reply(val type: String, val body: String, val headers: Map<String, String> = emptyMap())

    private val replies = mapOf(
        "/page" to Reply("text/html; charset=utf-8", "<!doctype html><title>server</title><p>page</p>"),
        "/framed" to Reply("text/html; charset=utf-8", "<!doctype html><title>framed</title><iframe src='/sub'></iframe>"),
        "/sub" to Reply("text/html; charset=utf-8", "<!doctype html><p>sub</p>"),
        "/file.zip" to Reply("application/zip", "PK-not-really", mapOf("Content-Disposition" to "attachment; filename=\"file.zip\"")),
    )

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            createContext("/") { exchange ->
                val reply = replies[exchange.requestURI.path]
                val body = (reply?.body ?: "not found").encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", reply?.type ?: "text/plain")
                reply?.headers?.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.sendResponseHeaders(if (reply != null) 200 else 404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    /** `http://127.0.0.1:<port>`, the server started on first use. */
    val origin: String get() = "http://127.0.0.1:${server.address.port}"
}
