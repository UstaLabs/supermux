package dev.supermux.webview.sample.checks

import androidx.compose.ui.graphics.asSkiaBitmap
import dev.supermux.webview.DialogResult
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * The engines' conformance checks, on iOS and macOS: the WebEngine contract, with real WebKit pages, in the app (a test
 * process can't load a page, nor show one in a window). Each goes through the public engine SPI only, as the runtime does.
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
            assertEquals(platform.needsCutouts, engine.needsCutouts, "whether ${platform.name}'s pages take overlay cut-outs")
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
            assertTrue(image.width in 320..960, "a 320-point page at the screen's scale, 3x at most: ${image.width} px")
        },
        Check("a-new-engines-first-page-is-shown-at-its-own-scale") {
            // A page sized to the device's width, in a new engine, loaded before its view is laid out, as the runtime
            // loads a view's first page: shown at a scale of 1 and laid out at the view's width, not at the size the web
            // view had when the load began.
            val engine = engine()
            val events = record(engine)
            start(engine, page("scale"))
            show(engine)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            assertEquals("1", engine.evaluate("visualViewport.scale"), "the page's scale")
            assertEquals("320", engine.evaluate("innerWidth"), "the page's width, in CSS pixels")
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
        Check("the-bridge-delivers-to-the-current-document") {
            val engine = engine()
            val events = live(engine, echoPage)
            val ready = events.await("\$ready", matches = ::isReady) as EngineEvent.PageMessage
            assertEquals(echoPage.origin.origin, ready.origin)
            engine.postMessage(hostEvent("ping", 7, nonceOf(ready)), echoPage.origin.origin)
            events.await("pong") { isPong(it, 7) }
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
            // What comes once load() has returned: subscribing, the recorder took what was queued before the load, which
            // may be the old document's (the contract keeps only later events from it).
            val from = next.events.size
            next.await("the new commit", from = from) { it is EngineEvent.UrlChanged }
            next.expectNone("the old document's ticks", from = from, millis = 1_000) {
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

    /** Reset, restore, local content, profiles and release (Task 17). */
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
        Check("reset-wipes-its-own-ephemeral-store-only") {
            val content = page("storage")
            val first = engine(WebProfile.Ephemeral)
            val second = engine(WebProfile.Ephemeral)
            live(first, content)
            first.evaluate("localStorage.setItem('k', 'first'); 1")
            show(second)
            live(second, content)
            second.evaluate("localStorage.setItem('k', 'second'); 1")
            assertTrue(first.reset(), "reset")
            live(first, content)
            assertEquals("null", first.evaluate("localStorage.getItem('k')"))
            assertEquals("\"second\"", second.evaluate("localStorage.getItem('k')"))
        },
        Check("a-bundle-is-served-safely-on-its-scheme-and-reported-as-its-origin") {
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
        Check("named-profiles-are-separate-where-the-platform-has-stores") {
            val content = page("profiles")
            val a = engine(WebProfile.Named("check-a"))
            val b = engine(WebProfile.Named("check-b"))
            live(a, content)
            a.evaluate("localStorage.setItem('who', 'a'); 1")
            show(b)
            live(b, content)
            val seen = b.evaluate("localStorage.getItem('who')")
            if (platform.namedProfilesSeparate) {
                assertEquals("null", seen)
            } else {
                assertEquals("\"a\"", seen, "this ${platform.name} shares the default store")
            }
            a.evaluate("localStorage.clear(); 1")
        },
        Check("release-is-final") {
            val engine = engine()
            engine.release()
            assertFalse(engine.isUsable)
            engine.load(page("after release"))
            assertNull(engine.evaluate("1"))
        },
    )

    /** Parks [engine]'s live page as the runtime does when its view leaves the composition: unbound, hidden, no Surface. */
    private fun CheckScope.park(engine: WebEngine) {
        stopRecording(engine)
        engine.client = null
        engine.setVisible(false)
        show(null)
    }

    /** A new view adopts [engine]'s parked page as the runtime does: subscribed anew, replayed, on screen and shown. */
    private fun CheckScope.adopt(engine: WebEngine): Recorder {
        val events = record(engine)
        engine.replayState()
        show(engine)
        engine.setVisible(true)
        return events
    }

    /** Keeping pages alive: a parked page, adopted by a new view, unreloaded (the keep-alive plan). */
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
            // Adopted by a view that isn't on screen yet: the page is told, but it can't have drawn there.
            engine.replayState()
            events.await("the replayed commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            events.expectNone("a first paint off screen", from = 0) { it == EngineEvent.FirstPaint }
            show(engine)
            engine.setVisible(true)
            events.await("the first paint on screen") { it == EngineEvent.FirstPaint }
            assertNotNull(engine.snapshot(), "a snapshot, once painted again")
        },
    )

    /** Sharp snapshots: a cover, as the runtime keeps and draws it, looks like the page on screen (the sharp-snapshot plan). */
    val snapshots: List<Check> = listOf(
        Check("the-cover-is-sharp") {
            val engine = engine()
            live(engine, articlePage)
            // A still page: painted, laid out, its fonts in.
            delay(1_000)
            val bounds = assertNotNull(shownBounds, "the page's place on screen")
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            val snapshot = assertNotNull(engine.snapshot(), "a snapshot")
            val screen = assertNotNull(platform.copy(bounds)?.pixels(), "a copy of the screen")
            assertEquals(width to height, screen.width to screen.height, "the screen's copy, at the view's pixels")
            val covers = List(20) { coverOf(snapshot, width, height) }
            val cover = covers.last()
            val kept = Difference.of(screen, cover.pixels)
            val drawn = Difference.of(screen, pixelsOf(org.jetbrains.skia.Image.makeFromBitmap(snapshot.asSkiaBitmap()), width, height))
            fun List<Double>.p50() = sorted()[size / 2]
            println(
                "MEASURE snapshot-quality ${snapshot.width}x${snapshot.height} of a ${width}x$height view: the cover against " +
                    "the screen: $kept; the page's own draw against the screen: $drawn; stored ${cover.stored.size} bytes " +
                    "(${snapshot.width.toLong() * snapshot.height * 4} as pixels); encode p50 ${covers.map { it.encodeMillis }.p50()} ms, " +
                    "max ${covers.maxOf { it.encodeMillis }} ms; decode p50 ${covers.map { it.decodeMillis }.p50()} ms, " +
                    "max ${covers.maxOf { it.decodeMillis }} ms",
            )
            assertTrue(kept.mean <= 2.0, "the cover against the screen: $kept")
            assertTrue(kept.p99 <= 16, "the cover against the screen: $kept")
        },
    )

    /** The handoff from a cover to the live page, frame by frame, once per scenario ([HandoffChecks]). */
    val handoff: List<Check> = HandoffChecks.all

    val all: List<Check> = basics + bridgeAndPolicy + lifecycle + keepAlive + snapshots + handoff

    /** Checks too long for every run, run only by name (`-only`): the handoff, five rounds in a row. */
    val onDemand: List<Check> = listOf(HandoffChecks.fiveRuns)
}
