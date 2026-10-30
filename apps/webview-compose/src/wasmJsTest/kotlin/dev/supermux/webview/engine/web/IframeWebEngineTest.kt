package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Records when [outer] first turns visible, and when the first iframe in [cut] loads. Call it right after load(). */
/**
 * Watches the frame of [outer] around its iframe's first load, frame by frame: the animation frame it loaded in,
 * whether the page was drawing in its place unseen then (laid out and `visible`, at `opacity: 0`), and the animation
 * frame it was first seen in (`visible` at `opacity: 1`). It counts frames until [stopWatching].
 */
private fun watch(outer: HTMLElement, cut: HTMLElement): JsAny = js("""(function () {
  var log = { frame: 0, loadFrame: -1, drawingAtLoad: false, seenFrame: -1, stop: false };
  (function tick() { if (log.stop) return; log.frame++; requestAnimationFrame(tick); })();
  function seen() { return outer.style.visibility === 'visible' && outer.style.opacity === '1'; }
  new MutationObserver(function () {
    if (log.seenFrame < 0 && seen()) log.seenFrame = log.frame;
  }).observe(outer, { attributes: true, attributeFilter: ['style'] });
  var iframe = cut.querySelector('iframe');
  if (iframe) iframe.addEventListener('load', function () {
    if (log.loadFrame >= 0) return;
    log.loadFrame = log.frame;
    log.drawingAtLoad = outer.style.display === 'block' && outer.style.visibility === 'visible' && outer.style.opacity === '0';
  });
  return log;
})()""")

private fun loadFrame(log: JsAny): Int = js("log.loadFrame")

private fun seenFrame(log: JsAny): Int = js("log.seenFrame")

private fun drawingAtLoad(log: JsAny): Boolean = js("log.drawingAtLoad")

private fun stopWatching(log: JsAny): Unit = js("{ log.stop = true; }")

private fun iframeCount(cut: HTMLElement): Int = js("cut.querySelectorAll('iframe').length")

private fun firstIframe(cut: HTMLElement): HTMLElement = js("cut.querySelector('iframe')")

private fun clientWidthOf(element: HTMLElement): Int = js("element.clientWidth")

private fun countLoads(iframe: HTMLElement): Unit = js("{ iframe.__loads = 0; iframe.addEventListener('load', function () { iframe.__loads++; }); }")

private fun loadsOf(iframe: HTMLElement): Int = js("iframe.__loads | 0")

/** A URL of another origin on the test server (localhost ↔ 127.0.0.1): it answers 404, which still loads a page. */
private fun otherOriginUrl(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-missing-page'")

private fun removeLayers(): Unit = js("{ var all = document.querySelectorAll('[data-webview-layer]'); for (var i = 0; i < all.length; i++) all[i].remove(); }")

/** [path] on the test server, under its other name (another site) when [otherSite]. See karma.config.d. */
private fun testServer(otherSite: Boolean, path: String): String =
    js("location.protocol + '//' + (otherSite === (location.hostname === 'localhost') ? '127.0.0.1' : 'localhost') + ':' + location.port + path")

/**
 * The app's own origin, written as a browser reads it but the common URL parser doesn't: a port with a leading zero,
 * and a host with a percent-escaped letter. One per line.
 */
private fun appOriginVariants(path: String): String = js("""(function () {
  var host = location.hostname, port = location.port || (location.protocol === 'https:' ? '443' : '80');
  return [
    location.protocol + '//' + host + ':0' + port + path,
    location.protocol + '//%' + host.charCodeAt(0).toString(16) + host.slice(1) + ':' + port + path
  ].join('\n');
})()""")

private fun encodeComponent(text: String): String = js("encodeURIComponent(text)")

private fun fetchText(url: String): Promise<JsString> = js("fetch(url, { cache: 'no-store' }).then(function (r) { return r.text(); })")

/** How many times the test server served its page with [id]. */
private suspend fun hitsOf(id: String): Int = fetchText(testServer(false, "/webview-test/hits?id=$id")).await<JsString>().toString().toInt()

/** The iframes in [cut] whose document the app can read: only one on the app's own origin. */
private fun readableIframes(cut: HTMLElement): Int = js("""(function () {
  var n = 0, all = cut.querySelectorAll('iframe');
  for (var i = 0; i < all.length; i++) { try { if (all[i].contentDocument) n++; } catch (e) {} }
  return n;
})()""")

/** An engine whose events are collected as the runtime collects them, on Dispatchers.Main. */
private class Harness(config: EngineConfig = EngineConfig(WebProfile.Ephemeral)) {
    val engine = IframeWebEngine(config)
    val events = ArrayList<EngineEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The subscription, as the runtime's view holds it: cancelled when the view parks the page ([stopListening]). */
    private val collector = scope.launch { engine.events.collect { events += it } }

    fun stopListening() = collector.cancel()

    inline fun <reified T : EngineEvent> all(): List<T> = events.filterIsInstance<T>()

    fun close() {
        scope.cancel()
        engine.release()
    }
}

private fun setupFor(content: WebContent, javaScript: Boolean = true) = PageSetup(
    javaScriptEnabled = javaScript,
    inspectable = false,
    dark = false,
    transparentBackground = false,
    userAgentSuffix = null,
    bridgeOrigins = (content as? WebContent.Html)?.let { setOf(it.origin.origin) } ?: emptySet(),
)

private fun demo(body: String) = WebContent.Html("<!doctype html><html><body style=\"margin:0\">$body</body></html>")

private fun isReady(event: EngineEvent) = event is EngineEvent.PageMessage && event.json.startsWith(WebBridge.READY_PREFIX)

private fun nonceOf(ready: EngineEvent.PageMessage): String =
    (BridgeCodec.decode(ready.json) as BridgeMessage.Event).data.jsonObject["nonce"]!!.jsonPrimitive.content

private suspend fun showSurface(engine: IframeWebEngine) = TestViewport.show {
    Box(Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) { engine.Surface(Modifier.fillMaxSize()) }
}

private suspend fun Harness.live(content: WebContent, javaScript: Boolean = true) {
    engine.configure(setupFor(content, javaScript))
    engine.load(content)
    showSurface(engine)
    waitUntil("the first paint", 20_000) { EngineEvent.FirstPaint in events }
}

class IframeWebEngineTest {
    @Test fun aDemoCommitsAsItsLocalOriginBeforeItsReadyAndPaintsAfterItLoads() = runTest(timeout = 1.minutes) {
        val content = demo("<p>hi</p>")
        val h = Harness()
        h.live(content)
        val commit = h.events.indexOf(EngineEvent.UrlChanged(content.origin.url))
        val ready = h.events.indexOfFirst(::isReady)
        assertTrue(commit >= 0, "it commits as its content's local origin: ${h.events}")
        assertTrue(ready > commit, "the commit comes before the document's ready: ${h.events}")
        assertEquals(content.origin.origin, (h.events[ready] as EngineEvent.PageMessage).origin)
        assertTrue(h.events.indexOf(EngineEvent.FirstPaint) > ready)
        assertTrue(hitAt(100.0, 100.0).startsWith("iframe:webview-"), hitAt(100.0, 100.0))
        TestViewport.clear()
        h.close()
    }

    /**
     * Seamless handoff: a document the runtime starts draws in its place, unseen, under the runtime's cover (a frame in a
     * hidden box draws nothing), and is seen only a frame after its load and commit, so the frame the cover gives way to
     * is one the page has drawn. Its first paint comes once it is seen. Takes no clicks meanwhile.
     */
    @Test fun aDocumentDrawsUnseenUnderTheCoverAndShowsAFrameAfterItLoads() = runTest(timeout = 1.minutes) {
        val content = demo("<p>covered until painted</p>")
        val h = Harness()
        showSurface(h.engine)
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        val log = watch(h.engine.frame.outer, h.engine.frame.cut)
        frames(1)
        assertEquals("compose", hitAt(100.0, 100.0), "covered, the page takes no clicks")
        waitUntil("the first paint") { EngineEvent.FirstPaint in h.events }
        assertEquals("1", styleValue(h.engine.frame.outer, "opacity"), "seen by its first paint")
        frames(2)
        stopWatching(log)
        assertTrue(loadFrame(log) >= 0, "it loaded")
        assertTrue(drawingAtLoad(log), "drawing in its place, unseen, as it loaded")
        assertTrue(seenFrame(log) >= loadFrame(log) + 1, "seen at frame ${seenFrame(log)}, loaded at ${loadFrame(log)}")
        assertTrue(hitAt(100.0, 100.0).startsWith("iframe:webview-"), hitAt(100.0, 100.0))
        TestViewport.clear()
        h.close()
    }

    @Test fun aDocumentThatNeverSpeaksCommitsAtItsLoad() = runTest(timeout = 1.minutes) {
        val content = demo("<p>no scripts</p>")
        val h = Harness()
        h.live(content, javaScript = false)
        assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url)), h.all<EngineEvent.UrlChanged>())
        assertTrue(h.all<EngineEvent.PageMessage>().isEmpty())
        TestViewport.clear()
        h.close()
    }

    @Test fun aSilentPageAfterASpeakingOneCommitsAsOpaqueAndNothingCommitsTwice() = runTest(timeout = 1.minutes) {
        // The iframe's load comes before its document's handshake (the feasibility test: 0 of 30 the other way round).
        // So a load waits a grace for the handshake, and the load of a document that comes after one that spoke is a new
        // document's, which commits as opaque when it stays silent.
        val content = demo("<p>leaving</p><script>setTimeout(function () { location.href = 'about:blank'; }, 300);</script>")
        val h = Harness()
        h.live(content)
        waitUntil("the next document's commit", 10_000) { h.all<EngineEvent.UrlChanged>().size == 2 }
        assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url), EngineEvent.UrlChanged(null)), h.all<EngineEvent.UrlChanged>())
        assertTrue(h.events.indexOf(EngineEvent.UrlChanged(null)) > h.events.indexOfFirst(::isReady), "${h.events}")
        pause(800)
        assertEquals(2, h.all<EngineEvent.UrlChanged>().size, "nothing commits twice: ${h.events}")
        TestViewport.clear()
        h.close()
    }

    @Test fun hostMessagesReachOnlyADocumentOfTheirOrigin() = runTest(timeout = 1.minutes) {
        val content = demo("<script>hostBridge.handle('double', function (x) { return x * 2; });</script>")
        val h = Harness()
        h.live(content)
        val nonce = nonceOf(h.events.first(::isReady) as EngineEvent.PageMessage)
        fun replies() = h.all<EngineEvent.PageMessage>().mapNotNull { BridgeCodec.decode(it.json) as? BridgeMessage.Reply }
        h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(1, "double", JsonPrimitive(2)), to = nonce), "https://other.invalid")
        pause(300)
        assertTrue(replies().isEmpty(), "nothing reached the page for another origin")
        h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(2, "double", JsonPrimitive(21)), to = nonce), content.origin.origin)
        waitUntil("the reply") { replies().isNotEmpty() }
        assertEquals(listOf(BridgeMessage.Reply(2, JsonPrimitive(42))), replies())
        TestViewport.clear()
        h.close()
    }

    /**
     * A page that runs no shim (its origin may not talk) but makes a handshake of its own, with a secret it guessed:
     * after its silent commit at its load, it commits again as unreadable, and speaks as the opaque origin.
     */
    @Test fun aDocumentWithoutTheSecretCommitsAndSpeaksAsAnOpaqueOrigin() = runTest(timeout = 1.minutes) {
        val forged = BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "forged") }), from = "forged")
        val content = demo(
            "<p>x</p><script>addEventListener('load', function () { setTimeout(function () { " +
                "parent.postMessage({ webviewBridge: 1, secret: 'a guess', text: ${JsonPrimitive(forged)} }, '*', [new MessageChannel().port2]);" +
                " }, 100); });</script>",
        )
        val h = Harness()
        h.engine.configure(setupFor(content).copy(bridgeOrigins = emptySet()))
        h.engine.load(content)
        showSurface(h.engine)
        waitUntil("the forged document's commit") { h.all<EngineEvent.UrlChanged>().size == 2 }
        assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url), EngineEvent.UrlChanged(null)), h.all<EngineEvent.UrlChanged>())
        waitUntil("its message") { h.all<EngineEvent.PageMessage>().isNotEmpty() }
        assertEquals("null", h.all<EngineEvent.PageMessage>().single().origin)
        TestViewport.clear()
        h.close()
    }

    /**
     * One handshake per document: a page that floods them, each with a port of its own, gets nothing queued for them.
     * Each was a commit and a message before, which the buffer never drops: 3,000 left 9,006 events queued.
     */
    @Test fun aPageThatFloodsHandshakesQueuesNothingForThem() = runTest(timeout = 1.minutes) {
        val content = demo(
            "<p>flood</p><script>for (var i = 0; i < 3000; i++) " +
                "parent.postMessage({ webviewBridge: 1, secret: 'forged', text: 'forged ' + i }, '*', [new MessageChannel().port2]);</script>",
        )
        val h = Harness()
        h.live(content)
        pause(1_000)
        assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url)), h.all<EngineEvent.UrlChanged>())
        assertEquals(0, h.all<EngineEvent.PageMessage>().count { it.json.startsWith("forged") }, "${h.events.size} events")
        TestViewport.clear()
        h.close()
    }

    @Test fun contentTheWebCannotShowSaysSoAndReportsWhy() = runTest(timeout = 2.minutes) {
        val h = Harness(EngineConfig(WebProfile.Default))
        showSurface(h.engine)
        val cases: List<Pair<WebContent, (WebError) -> Boolean>> = listOf(
            WebContent.Bundle(WebOrigin("plugin-a"), WebResourceProvider { null }) to { it is WebError.UnsupportedOnPlatform },
            WebContent.Url("https://example.com/", mapOf("Authorization" to "Bearer x")) to { it is WebError.UnsupportedOnPlatform },
            WebContent.Url(pageOrigin() + "/inside-the-app") to { it is WebError.BlockedByPolicy },
        )
        for ((index, case) in cases.withIndex()) {
            val (content, expected) = case
            h.engine.configure(setupFor(content))
            h.engine.load(content)
            waitUntil("the error for $content") { h.all<EngineEvent.Error>().size == index + 1 }
            assertTrue(expected(h.all<EngineEvent.Error>().last().error), "${h.all<EngineEvent.Error>().last()}")
            assertEquals("visible", styleValue(h.engine.frame.outer, "visibility"), "the error page shows for $content")
        }
        TestViewport.clear()
        h.close()
    }

    /**
     * A page on the app's own origin could script the app, so it is refused before anything loads, however its URL is
     * written: the browser's own parser decides what is the app's origin. The test server counts the page's requests.
     */
    @Test fun aUrlOnTheAppsOriginIsRefusedBeforeItLoadsHoweverItIsWritten() = runTest(timeout = 2.minutes) {
        val h = Harness(EngineConfig(WebProfile.Default))
        showSurface(h.engine)
        for ((index, written) in appOriginVariants("/webview-test/page?id=ID").split('\n').withIndex()) {
            val id = randomHex()
            val content = WebContent.Url(written.replace("ID", id))
            h.engine.configure(setupFor(content))
            h.engine.load(content)
            waitUntil("an answer for ${content.url}") { h.all<EngineEvent.Error>().size > index || h.all<EngineEvent.FirstPaint>().isNotEmpty() }
            assertTrue(h.all<EngineEvent.FirstPaint>().isEmpty(), "${content.url} was shown")
            assertTrue(h.all<EngineEvent.Error>().last().error is WebError.BlockedByPolicy, "${h.all<EngineEvent.Error>().last()}")
            assertEquals(0, hitsOf(id), "${content.url} was asked for")
        }
        TestViewport.clear()
        h.close()
    }

    /**
     * A page of another site that redirects to the app's own origin gets there, with the app's authority: its scripts
     * have run by its load. Defence in depth: once it has loaded, its frame goes, and the view says why.
     */
    @Test fun aUrlPageThatEndsUpOnTheAppsOriginLosesItsFrame() = runTest(timeout = 1.minutes) {
        val id = randomHex()
        val target = testServer(false, "/webview-test/page?id=$id")
        val content = WebContent.Url(testServer(true, "/webview-test/redirect?to=" + encodeComponent(target)))
        val h = Harness(EngineConfig(WebProfile.Default))
        showSurface(h.engine)
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        waitUntil("an answer") { h.all<EngineEvent.Error>().isNotEmpty() || EngineEvent.FirstPaint in h.events }
        assertEquals(1, hitsOf(id), "the redirect reached the app's origin")
        assertTrue(h.all<EngineEvent.Error>().singleOrNull()?.error is WebError.BlockedByPolicy, "${h.events}")
        assertEquals(0, readableIframes(h.engine.frame.cut), "a document on the app's origin is still in the frame")
        assertTrue(h.all<EngineEvent.UrlChanged>().isEmpty(), "it committed: ${h.events}")
        TestViewport.clear()
        h.close()
    }

    @Test fun anEphemeralEngineRefusesNetworkPages() = runTest(timeout = 1.minutes) {
        val h = Harness(EngineConfig(WebProfile.Ephemeral))
        showSurface(h.engine)
        val content = WebContent.Url(otherOriginUrl())
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        waitUntil("the error") { h.all<EngineEvent.Error>().isNotEmpty() }
        assertTrue(h.all<EngineEvent.Error>().single().error is WebError.UnsupportedOnPlatform)
        TestViewport.clear()
        h.close()
    }

    @Test fun aUrlPageCommitsAsTheUrlItWasGivenAndPaints() = runTest(timeout = 1.minutes) {
        val content = WebContent.Url(otherOriginUrl())
        val h = Harness(EngineConfig(WebProfile.Default))
        h.live(content)
        assertEquals(listOf(EngineEvent.UrlChanged(content.url)), h.all<EngineEvent.UrlChanged>())
        TestViewport.clear()
        h.close()
    }

    @Test fun focusInThePageIsReported() = runTest(timeout = 1.minutes) {
        val h = Harness()
        h.live(demo("<button>b</button>"))
        h.engine.requestFocus()
        waitUntil("focus to be reported") { EngineEvent.FocusChanged(true) in h.events }
        TestViewport.clear()
        h.close()
    }

    /** Hidden, the frame stays laid out, only invisible: the page keeps its viewport, and nothing reflows or reloads. */
    @Test fun aHiddenEngineKeepsItsLayoutHiddenAndComesBack() = runTest(timeout = 1.minutes) {
        val h = Harness()
        h.live(demo("<p>x</p>"))
        val iframe = firstIframe(h.engine.frame.cut)
        val width = clientWidthOf(iframe)
        h.engine.setVisible(false)
        assertEquals("block", styleValue(h.engine.frame.outer, "display"))
        assertEquals("hidden", styleValue(h.engine.frame.outer, "visibility"))
        assertEquals(width, clientWidthOf(iframe), "the page's viewport keeps its size")
        h.engine.setVisible(true)
        frames(2)
        assertEquals("visible", styleValue(h.engine.frame.outer, "visibility"))
        TestViewport.clear()
        h.close()
    }

    /**
     * Parked as the runtime parks a page (hidden, its surface gone), then adopted by a new surface: the frame waits in
     * its layer, hidden, with the same iframe and document; the replay tells the commit, and the first paint once the
     * frame is displayed again. Nothing reloads, and the page answers a hello as the document it was.
     */
    @Test fun replayStateAfterParking() = runTest(timeout = 1.minutes) {
        val content = demo("<p>parked</p>")
        val h = Harness()
        var shown by mutableStateOf(true)
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        TestViewport.show {
            if (shown) Box(Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) { h.engine.Surface(Modifier.fillMaxSize()) }
        }
        waitUntil("the first paint", 20_000) { EngineEvent.FirstPaint in h.events }
        waitUntil("the page's ready") { h.events.any(::isReady) }
        val nonce = nonceOf(h.events.first(::isReady) as EngineEvent.PageMessage)
        val iframe = firstIframe(h.engine.frame.cut)
        countLoads(iframe)
        val layer = parentOf(h.engine.frame.outer)
        h.engine.setVisible(false)
        shown = false
        frames(3)
        assertTrue(connected(iframe), "the iframe stays in the page")
        assertTrue(same(layer, parentOf(h.engine.frame.outer)), "and its frame in its layer")
        assertEquals("hidden", styleValue(h.engine.frame.outer, "visibility"))
        assertTrue(clientWidthOf(iframe) > 0, "laid out, so the page keeps its viewport")
        val parkedAt = h.events.size
        // Adopted: a new surface, the replay, shown.
        shown = true
        h.engine.replayState()
        h.engine.setVisible(true)
        // Drawn again in its place, unseen, before it shows: a hidden frame drew nothing meanwhile.
        assertEquals("0", styleValue(h.engine.frame.outer, "opacity"), "covered until it has drawn again")
        waitUntil("the replayed first paint") { EngineEvent.FirstPaint in h.events.drop(parkedAt) }
        val replayed = h.events.drop(parkedAt)
        assertEquals(EngineEvent.UrlChanged(content.origin.url), replayed.first(), "$replayed")
        assertEquals("visible", styleValue(h.engine.frame.outer, "visibility"))
        assertEquals("1", styleValue(h.engine.frame.outer, "opacity"), "seen by its replayed first paint")
        assertEquals(0, loadsOf(iframe), "the page never reloaded")
        // The same document: asked to announce itself, it gives the nonce it gave before.
        val hello = h.events.size
        h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Event("\$hello")), content.origin.origin)
        waitUntil("the page's ready again") { h.events.drop(hello).any(::isReady) }
        assertEquals(nonce, nonceOf(h.events.drop(hello).first(::isReady) as EngineEvent.PageMessage))
        TestViewport.clear()
        h.close()
    }

    @Test fun resetEndsTheDocumentAndTheNextLeaseStartsClean() = runTest(timeout = 1.minutes) {
        val first = demo("<p>first</p>")
        val second = demo("<p>second</p>")
        val h = Harness()
        h.live(first)
        assertEquals(WebEngineDecisions.RESET_ISOLATES_HISTORY, h.engine.reset())
        assertEquals(0, iframeCount(h.engine.frame.cut), "the page is gone")
        val seen = h.events.size
        pause(300)
        assertEquals(seen, h.events.size, "nothing of it arrives after the reset")
        h.engine.configure(setupFor(second))
        h.engine.load(second)
        waitUntil("the next lease's paint") { h.all<EngineEvent.FirstPaint>().size == 2 }
        assertEquals(EngineEvent.UrlChanged(second.origin.url), h.all<EngineEvent.UrlChanged>().last())
        TestViewport.clear()
        h.close()
    }

    @Test fun afterReleaseEveryMemberDoesNothing() = runTest(timeout = 1.minutes) {
        val content = demo("<p>x</p>")
        val h = Harness()
        h.live(content)
        h.engine.release()
        assertFalse(h.engine.isUsable)
        assertFalse(connected(h.engine.frame.outer))
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        h.engine.postMessage("{}", content.origin.origin)
        h.engine.applyOverlays(OverlayCutouts.Hidden)
        h.engine.setVisible(false)
        assertEquals(0, iframeCount(h.engine.frame.cut))
        TestViewport.clear()
        h.close()
    }

    /**
     * A parked page that floods its bridge: past what the queue holds while no view subscribes, the engine goes the way
     * of a crash (unusable, its frame and slot given up), so the pool discards it rather than hand it to a view.
     */
    @Test fun aParkedPageThatFloodsItsBridgeCrashes() = runTest(timeout = 1.minutes) {
        val content = demo(
            "<script>hostBridge.handle('flood', function () { for (var i = 0; i < 1100; i++) hostBridge.post('tick', i); return true; });</script>",
        )
        val h = Harness()
        h.live(content)
        waitUntil("the page's ready") { h.events.any(::isReady) }
        val nonce = nonceOf(h.events.first(::isReady) as EngineEvent.PageMessage)
        h.stopListening()
        frames(2)
        assertTrue(h.engine.isUsable)
        h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(1, "flood"), to = nonce), content.origin.origin)
        waitUntil("the flood to end the page") { !h.engine.isUsable }
        TestViewport.clear()
        h.close()
    }

    @Test fun anEngineWhoseLayerLeftThePageCrashes() = runTest(timeout = 1.minutes) {
        val h = Harness()
        var x by mutableIntStateOf(40)
        val content = demo("<p>x</p>")
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        TestViewport.show { Box(Modifier.offset(x.dp, 40.dp).size(600.dp, 400.dp)) { h.engine.Surface(Modifier.fillMaxSize()) } }
        waitUntil("the first paint") { EngineEvent.FirstPaint in h.events }
        removeLayers()
        x = 60
        waitUntil("the crash") { EngineEvent.Crashed in h.events }
        assertFalse(h.engine.isUsable)
        TestViewport.clear()
        h.close()
    }
}
