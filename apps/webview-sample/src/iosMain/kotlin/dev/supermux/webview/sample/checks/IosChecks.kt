package dev.supermux.webview.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import platform.Foundation.NSData
import platform.posix.memcpy
import platform.UIKit.UIApplication
import platform.UIKit.UIView
import platform.WebKit.WKContentWorld
import platform.WebKit.WKMediaPlaybackState
import platform.WebKit.WKWebView
import kotlin.coroutines.resume

/**
 * The iOS engine's own checks, beyond those it shares with macOS ([EngineChecks]): what the independent review of the iOS
 * engine found (2026-10-30), each with real WebKit pages in the app, through the engine SPI, a `WebView`, or the engine's
 * `WKWebView` where only it shows what happened.
 */
object IosChecks {
    /** A page that never loads: `.invalid` never resolves. */
    private val OFFLINE = WebContent.Url("https://nowhere.invalid/page")

    private fun start(engine: WebEngine, content: WebContent) {
        engine.configure(setupFor(content))
        engine.load(content)
    }

    /** [engine] loads [content] on screen; its recorder, once the page has painted and announced itself. */
    private suspend fun CheckScope.live(engine: WebEngine, content: WebContent): Recorder {
        val events = record(engine)
        show(engine)
        start(engine, content)
        events.await("first paint") { it == EngineEvent.FirstPaint }
        events.await("\$ready", matches = ::isReady)
        return events
    }

    /** Parks [engine]'s page as the runtime does when its view leaves: unbound, hidden, no Surface. */
    private fun CheckScope.park(engine: WebEngine) {
        stopRecording(engine)
        engine.client = null
        engine.setVisible(false)
        show(null)
    }

    /** A view adopts [engine]'s parked page as the runtime does: subscribed anew, replayed, on screen and shown. */
    private fun CheckScope.adopt(engine: WebEngine): Recorder {
        val events = record(engine)
        engine.replayState()
        show(engine)
        engine.setVisible(true)
        return events
    }

    /** Failed loads recover (review item 2). */
    val failedLoads: List<Check> = listOf(
        Check("a-retry-after-a-failed-load-loads-the-content-again") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, OFFLINE)
            events.await("the load's error", millis = 20_000) { it is EngineEvent.Error }
            val before = events.events.size
            // WebKit's own reload would reload the floor, still the committed document, which the rules refuse: silence.
            engine.reload()
            val again = events.await("the retry's error", from = before, millis = 20_000) { it is EngineEvent.Error }
            assertEquals(OFFLINE.url, (again as EngineEvent.Error).error.url, "the retry loads the content")
        },
        Check("a-page-that-never-loaded-saves-no-state") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, OFFLINE)
            events.await("the load's error", millis = 20_000) { it is EngineEvent.Error }
            assertNull(engine.saveState(), "WebKit's state holds the floor, which restored shows nothing of the content")
        },
        Check("a-state-saved-on-the-floor-restores-nothing") {
            // What a view of a page that failed saved before: WebKit's state of the floor, in the engine's envelope.
            val engine = engine()
            show(engine)
            val web = assertNotNull(awaitWebView(), "the engine's web view in the window")
            val floor = assertNotNull(web.interactionState as? NSData, "the floor's state").toByteArray()
            val events = record(engine)
            engine.configure(setupFor(OFFLINE))
            assertFalse(engine.restoreState(savedState(OFFLINE.key, floor), OFFLINE), "a state whose page is the floor")
            // The runtime loads the content instead, which reports its error rather than sit under its cover.
            engine.load(OFFLINE)
            events.await("the load's error", millis = 20_000) { it is EngineEvent.Error }
        },
        Check("a-view-of-a-page-that-failed-shows-its-error-again-not-a-cover") {
            // An app starting offline: the page fails, the view goes cold (its engine given back), and shown again it
            // shows the error again, never its cover for good.
            val stage = ViewStage(OFFLINE)
            compose { stage.Scene() }
            try {
                waitFor("the error, live") { stage.state.phase == WebViewPhase.Live && stage.state.error != null }
                stage.shown = false
                waitFor("cold, its engine given back") { stage.state.phase == WebViewPhase.Cold && stage.runtime.pool.liveCount == 0 }
                stage.shown = true
                waitFor("the error again, live") { stage.state.phase == WebViewPhase.Live && stage.state.error != null }
            } finally {
                stage.close()
                compose(null)
            }
        },
    )

    /** `reset()` succeeds while a page is still loading (review item 4). */
    val resets: List<Check> = listOf(
        Check("reset-while-a-page-loads-succeeds") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            // Its image never comes: committed, it stays loading.
            start(engine, SlowImageBundle("reset-loading", delayMillis = null).content)
            events.await("the commit") { it is EngineEvent.UrlChanged }
            assertEquals("\"interactive\"", engine.evaluate("document.readyState"), "still loading")
            assertTrue(engine.reset(), "a reset while the page loads")
            nextLeaseWorks(engine)
        },
        Check("reset-before-a-page-commits-succeeds") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            // Its page never comes: the load stays provisional.
            start(engine, WebContent.Bundle(dev.supermux.webview.WebOrigin("reset-provisional"), WebResourceProvider { awaitCancellation() }))
            events.await("the load's start") { it is EngineEvent.Progress && it.progress != null }
            delay(300)
            assertTrue(engine.reset(), "a reset before the page commits")
            nextLeaseWorks(engine)
        },
    )

    /** After a reset, [engine] shows the next lease's page. */
    private suspend fun CheckScope.nextLeaseWorks(engine: WebEngine) {
        val next = record(engine)
        start(engine, page("after the reset"))
        next.await("the next page's first paint") { it == EngineEvent.FirstPaint }
    }

    /** A released engine leaves nothing behind (review item 5). */
    val release: List<Check> = listOf(
        Check("a-released-engine-unloads-its-page-at-once") {
            val engine = engine()
            live(engine, page("released", script = "window.__alive = 1;"))
            val web = assertNotNull(awaitWebView(), "the engine's web view")
            // Work pending: a parked page, adopted by a view not in a window yet, waits to draw again.
            park(engine)
            delay(300)
            record(engine)
            engine.replayState()
            engine.release()
            // The page stops now, not when the collector frees the engine: its scripts, its timers, its media.
            val gone = withTimeoutOrNull(5_000) {
                while (evaluate(web, "typeof window.__alive") != "undefined") delay(100)
                true
            }
            assertNotNull(gone, "the released engine's page still runs: ${evaluate(web, "location.href")}")
        },
        Check("release-answers-a-pending-snapshot") {
            val engine = engine()
            live(engine, articlePage)
            coroutineScope {
                val shot = async { engine.snapshot() }
                yield()
                // Out of its window at once, then released: a snapshot WebKit may never finish is answered anyway.
                show(null)
                engine.release()
                assertNotNull(withTimeoutOrNull(3_000) { shot.await(); true }, "the snapshot still waits after release")
            }
        },
    )

    /** No stale first paint reaches a view that adopts a page (review item 3). */
    val adoption: List<Check> = listOf(
        Check("an-adopting-view-never-hears-a-first-paint-from-before-its-replay", timeoutMillis = 180_000) {
            val engine = engine()
            // Parked as its handoff runs (unbound and hidden at once, its surface out of the window a frame or two
            // later), then adopted: a first paint queued meanwhile would lift the adopter's cover before its page drew.
            for (round in 0 until 12) {
                val content = page("race $round")
                val events = record(engine)
                show(engine)
                start(engine, content)
                events.await("the commit") { it is EngineEvent.UrlChanged }
                delay(round * 15L)
                park(engine)
                delay(50)
                val adopter = adopt(engine)
                delay(1_200)
                val heard = adopter.events.toList()
                val paint = heard.indexOfFirst { it == EngineEvent.FirstPaint }
                val replay = heard.indexOfFirst { it is EngineEvent.UrlChanged }
                if (paint >= 0) assertTrue(replay in 0 until paint, "round $round: a first paint before the replay: $heard")
                stopRecording(engine)
                assertTrue(engine.reset(), "reset after round $round")
            }
        },
    )

    /** A deep-blue page, its own background everywhere. */
    private val DEEP_BLUE = WebContent.Html(
        "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>blue</title>" +
            "<style>html,body{margin:0;height:100%;background:#0b1f5c}</style></head><body></body></html>",
    )

    /** Resizes show the page's own background, never a light band (review item 7). */
    val resizes: List<Check> = listOf(
        Check("widening-a-page-shows-no-light-band") {
            val engine = engine()
            var width by mutableStateOf(320.dp)
            var place: Rect? = null
            compose {
                Box(Modifier.width(380.dp).height(300.dp).background(Color(0xFF0B1F5C)).onGloballyPositioned { place = it.boundsInWindow() }) {
                    Box(Modifier.width(width).height(300.dp)) { engine.Surface(Modifier.fillMaxSize()) }
                }
            }
            val events = record(engine)
            start(engine, DEEP_BLUE)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            delay(800)
            val region = assertNotNull(place, "the page's place")
            val lights = mutableListOf<Int>()
            platform.everyFrame {
                val pixels = platform.copy(region)?.pixels()
                if (pixels != null) lights += lightPixels(pixels)
                if (lights.size == 3) width = 380.dp
                lights.size < 40
            }
            println("MEASURE resize-light-pixels widening 320 to 380 pt, per frame: $lights")
            assertTrue(lights.drop(3).all { it == 0 }, "light pixels in the frames after the width changed: $lights")
        },
    )

    /** Named profiles are separate on every iOS, and shared by the engines of one (review item 8). */
    val profiles: List<Check> = listOf(
        Check("named-profiles-are-separate-on-every-ios-and-shared-within-one") {
            val content = page("profiles-ios")
            val a = engine(WebProfile.Named("ios-check-a"))
            val alsoA = engine(WebProfile.Named("ios-check-a"))
            val b = engine(WebProfile.Named("ios-check-b"))
            live(a, content)
            a.evaluate("localStorage.setItem('who', 'a'); 1")
            show(alsoA)
            live(alsoA, content)
            val sameProfile = alsoA.evaluate("localStorage.getItem('who')")
            show(b)
            live(b, content)
            val otherProfile = b.evaluate("localStorage.getItem('who')")
            a.evaluate("localStorage.clear(); 1")
            assertEquals("null", otherProfile, "another named profile's storage")
            assertEquals("\"a\"", sameProfile, "the same named profile's, in another engine")
        },
    )

    /** The relay acts on the user's own events only, and keeps a flooding page from the app's main thread. */
    val relay: List<Check> = listOf(
        Check("a-pages-own-focus-and-blur-are-not-the-views") {
            val engine = engine()
            val events = live(engine, page("synthetic-focus"))
            val before = events.events.size
            engine.evaluate("window.dispatchEvent(new FocusEvent('blur')); window.dispatchEvent(new FocusEvent('focus')); 1")
            events.expectNone("a focus change the page made up", from = before) { it is EngineEvent.FocusChanged }
        },
        Check("a-page-that-floods-the-bridge-is-capped-before-the-app") {
            val engine = engine()
            val events = live(engine, page("flood"))
            val before = events.events.size
            engine.evaluate("for (var i = 0; i < 20000; i++) hostBridge.post('flood', i); 1")
            delay(2_000)
            val heard = events.events.drop(before).count { it is EngineEvent.PageMessage }
            println("MEASURE relay-flood 20000 posted, $heard reached the engine in 2 s")
            // The relay lets a burst of 500 through, then 500 a second: 20 000 in a loop is a burst and the loop's
            // fraction of a second.
            assertTrue(heard in 1..1_500, "$heard of 20000 messages reached the app's main thread")
        },
    )

    /** A dialog the page left pending when its view went doesn't hold the next view's page (a minor of the review). */
    val dialogs: List<Check> = listOf(
        Check("a-dialog-pending-at-park-doesnt-block-the-adopted-page") {
            val engine = engine()
            // The parking view's dialog UI is gone with it: its question is never answered.
            engine.client = CheckClient(dialog = CompletableDeferred())
            live(engine, page("dialog-park"))
            coroutineScope {
                val alert = async { engine.evaluate("alert('pending'); 'answered'") }
                delay(500)
                park(engine)
                delay(300)
                val adopted = adopt(engine)
                adopted.await("the adopted page's first paint", millis = 6_000) { it == EngineEvent.FirstPaint }
                assertNotNull(withTimeoutOrNull(3_000) { alert.await() }, "the alert is answered")
            }
        },
    )

    /** A plugin's page with a video of its own, which the browser loads in byte ranges; [playing], it plays in a loop. */
    private class VideoBundle(host: String, private val playing: Boolean = false) : WebResourceProvider {
        val content: WebContent.Bundle = WebContent.Bundle(dev.supermux.webview.WebOrigin(host), this)

        override suspend fun load(path: String): dev.supermux.webview.WebResource? = when (path) {
            "index.html" -> dev.supermux.webview.WebResource(
                ("<!doctype html><html><head><meta charset='utf-8'><title>video</title></head><body>" +
                    "<video id='v' src='clip.mp4' muted playsinline preload='auto' width='64' height='64'" +
                    (if (playing) " autoplay loop" else "") + "></video><script>" +
                    "var v = document.getElementById('v'); window.__video = 'waiting';" +
                    "v.addEventListener('loadeddata', function () { window.__video = 'loaded'; });" +
                    "v.addEventListener('error', function () { window.__video = 'error ' + (v.error && v.error.code); });" +
                    "</script></body></html>").encodeToByteArray(),
                "text/html; charset=utf-8",
            )
            "clip.mp4" -> dev.supermux.webview.WebResource(CLIP, "video/mp4")
            else -> null
        }

        private companion object {
            /** One second of 64 × 64 H.264, 1631 bytes (ffmpeg's lavfi colour source, baseline profile, faststart). */
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val CLIP: ByteArray = kotlin.io.encoding.Base64.decode("AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAANLbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAnZ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAHubWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAKABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABmW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAVlzdGJsAAAAuXN0c2QAAAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABFUxhdmM2Mi4xMS4xMDAgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAF2dCwArZBCbARAAAAwAEAAADAFA8SJkgAQAFaMuDyyAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAXIAAAAAAAAAAYc3R0cwAAAAAAAAABAAAACgAABAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAACgAAAAEAAAA8c3RzegAAAAAAAAAAAAAACgAAAooAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAUc3RjbwAAAAAAAAABAAADewAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjIuMy4xMDAAAAAIZnJlZQAAAuxtZGF0AAACYwYF//9f3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDE6MHgxMTEgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0yIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTI1MCBrZXlpbnRfbWluPTEwIHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAH2WIhA/xGKAAJtMcAASpo4AAimycnJ1111111111114AAAAGQZo4H+EYAAAABkGaVAf4RgAAAAZBmmA/wjAAAAAGQZqAP8IwAAAABkGaoD/CMAAAAAZBmsA/wjAAAAAGQZrgP8IwAAAABkGbADvCMAAAAAZBmyA3wjA=")
        }
    }

    /** Local content the engine serves from the app (a review minor: bundle responses honour `Range`). */
    val media: List<Check> = listOf(
        Check("a-bundled-video-loads") {
            // iOS loads a video's data only to play it: a muted one that plays at once, in an engine that lets it.
            withMediaEngine { engine ->
                live(engine, VideoBundle("video-check", playing = true).content)
                var state = engine.evaluate("window.__video")
                withTimeoutOrNull(10_000) {
                    while (state == "\"waiting\"") {
                        delay(100)
                        state = engine.evaluate("window.__video")
                    }
                }
                assertEquals("\"loaded\"", state, "the bundled video")
            }
        },
    )

    /**
     * A page WebKit restores from its back-forward cache is a document anew: its announcement (a new nonce) reaches the
     * host, and the bridge reaches it. Prints whether WebKit restored it from the cache (a review minor: its `$ready`
     * must not go under the old document's token).
     */
    val history: List<Check> = listOf(
        Check("a-page-back-from-history-announces-itself-and-hears-the-host") {
            val bundle = CheckBundle("history-check")
            val engine = engine()
            engine.client = CheckClient(navigation = dev.supermux.webview.NavigationDecision.Allow)
            val events = live(engine, bundle.content)
            engine.evaluate("window.__restored = 'no'; addEventListener('pageshow', function (e) { if (e.persisted) window.__restored = 'yes'; }); 1")
            engine.evaluate("document.getElementById('next').click(); 1")
            events.await("page 2") { it == EngineEvent.UrlChanged("https://history-check.invalid/page2.html") }
            delay(300)
            val from = events.events.size
            engine.goBack()
            events.await("the entry again", from = from) { it == EngineEvent.UrlChanged("https://history-check.invalid/index.html") }
            val ready = events.await("its announcement", from = from, matches = ::isReady)
            println("MEASURE back-forward-cache: the entry came back from the cache: ${engine.evaluate("window.__restored")}")
            engine.postMessage(hostEvent("ping", 9, nonceOf(ready)), bundle.content.origin.origin)
            events.await("pong", from = from) { isPong(it, 9) }
        },
    )

    /** What the review's mutants of the engine showed no check of. */
    val untested: List<Check> = listOf(
        Check("no-snapshot-before-the-first-paint-even-on-screen") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            // Nothing contentful until its image comes: committed, on screen, and not painted yet.
            start(engine, SlowImageBundle("snapshot-early", delayMillis = 2_500).content)
            events.await("the commit") { it is EngineEvent.UrlChanged }
            delay(300)
            assertFalse(EngineEvent.FirstPaint in events.events, "painted already")
            assertNull(engine.snapshot(), "a snapshot of a page that hasn't painted shows its bare background")
        },
        Check("a-hidden-pages-media-is-suspended-and-plays-again-when-shown") {
            withMediaEngine { engine ->
                live(engine, VideoBundle("media-check", playing = true).content)
                val web = assertNotNull(awaitWebView(), "the engine's web view")
                awaitPlayback(web, "playing") { it == WKMediaPlaybackState.WKMediaPlaybackStatePlaying }
                engine.setVisible(false)
                awaitPlayback(web, "suspended while hidden") { it == WKMediaPlaybackState.WKMediaPlaybackStateSuspended }
                engine.setVisible(true)
                awaitPlayback(web, "playing again once shown") { it == WKMediaPlaybackState.WKMediaPlaybackStatePlaying }
            }
        },
        Check("a-released-engine-leaves-no-message-handler-in-its-web-view") {
            val engine = engine()
            live(engine, page("handler"))
            val web = assertNotNull(awaitWebView(), "the engine's web view")
            engine.release()
            val blank = withTimeoutOrNull(5_000) {
                while (evaluate(web, "location.href") != "about:blank") delay(100)
                true
            }
            assertNotNull(blank, "the released engine's page is still there: ${evaluate(web, "location.href")}")
            val world = WKContentWorld.worldWithName("webview")
            assertEquals(
                "none",
                evaluate(web, "(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.webview) ? 'there' : 'none'", world),
                "the engine's message handler, in its isolated world",
            )
        },
    )

    /**
     * Runs [block] with a new engine whose pages may play media without the user's gesture
     * (`mediaPlaybackRequiresGesture = false`), as the pool would get it, and releases it after.
     */
    private suspend fun CheckScope.withMediaEngine(block: suspend (WebEngine) -> Unit) {
        val result = platformWebEngineFactory().create(EngineConfig(WebProfile.Ephemeral, mediaPlaybackRequiresGesture = false))
        val engine = (result as? WebEngineResult.Created)?.engine ?: throw AssertionError("no engine: $result")
        try {
            block(engine)
        } finally {
            stopRecording(engine)
            show(null)
            engine.release()
        }
    }

    /** Waits up to 8 s for [web]'s media to be as [wanted] says ([what], for the failure). */
    private suspend fun awaitPlayback(web: WKWebView, what: String, wanted: (WKMediaPlaybackState) -> Boolean) {
        var state: WKMediaPlaybackState? = null
        withTimeoutOrNull(8_000) {
            while (state?.let(wanted) != true) {
                if (state != null) delay(200)
                state = suspendCancellableCoroutine<WKMediaPlaybackState> { continuation ->
                    web.requestMediaPlaybackStateWithCompletionHandler { now -> if (continuation.isActive) continuation.resume(now) }
                }
            }
        }
        if (state?.let(wanted) != true) throw AssertionError("the page's media isn't $what: $state")
    }

    val all: List<Check> = failedLoads + resets + release + adoption + resizes + profiles + relay + dialogs + media + history + untested

    /** A `WebView` of [content] in a runtime of its own, which gives a hidden view's engine back at once. */
    private class ViewStage(content: WebContent) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            config = RuntimeConfig(maxLive = 2, maxIdle = 1, snapshotCacheBytes = 8L shl 20, hiddenReleaseMillis = 0),
            scope = scope,
        )
        val state = WebViewState(content)
        var shown by mutableStateOf(true)

        @androidx.compose.runtime.Composable
        fun Scene() {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(320.dp, 300.dp).clipToBounds()) {
                    WebView(state, Modifier.size(320.dp, 300.dp).offset(y = if (shown) 0.dp else 400.dp), activation = Activation.WhenVisible(0))
                }
            }
        }

        fun close() {
            runtime.clear()
            scope.cancel()
        }
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(20_000) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("no $what within 20 s")
    }

    /** The engine's `WKWebView` in the app's window, once it is there. */
    private suspend fun awaitWebView(): WKWebView? = withTimeoutOrNull(5_000) {
        var found: WKWebView? = null
        while (found == null) {
            found = UIApplication.sharedApplication.keyWindow?.let(::findWebView)
            if (found == null) delay(50)
        }
        found
    }

    private fun findWebView(view: UIView): WKWebView? {
        if (view is WKWebView) return view
        for (child in view.subviews) findWebView(child as UIView)?.let { return it }
        return null
    }

    /** [script]'s result in [web]'s [world] (the page's own by default), as a string; "error" if it fails. */
    private suspend fun evaluate(web: WKWebView, script: String, world: WKContentWorld = WKContentWorld.pageWorld): String =
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavaScript(script, inFrame = null, inContentWorld = world) { result, error ->
                if (continuation.isActive) continuation.resume(if (error != null) "error" else result.toString())
            }
        }

    /** The engine's saved state of [key]: its envelope (`SavedWebKitState`, internal to the library) around WebKit's [state]. */
    private fun savedState(key: String, state: ByteArray): ByteArray {
        val name = key.encodeToByteArray()
        val length = ByteArray(4) { (name.size ushr (24 - 8 * it)).toByte() }
        return "WKS1".encodeToByteArray() + length + name + state
    }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    /** How many of [pixels] are light: each channel 200 or more. */
    private fun lightPixels(pixels: Pixels): Int {
        var n = 0
        var i = 0
        while (i < pixels.rgba.size) {
            if ((pixels.rgba[i].toInt() and 0xff) >= 200 && (pixels.rgba[i + 1].toInt() and 0xff) >= 200 && (pixels.rgba[i + 2].toInt() and 0xff) >= 200) n++
            i += 4
        }
        return n
    }
}
