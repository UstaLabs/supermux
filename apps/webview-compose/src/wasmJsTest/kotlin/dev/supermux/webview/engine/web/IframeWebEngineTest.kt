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

private fun sandboxOf(iframe: HTMLElement): String = js("String(iframe.getAttribute('sandbox'))")

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

/** [content], live on screen: configured, loaded, told its view is on screen (as the runtime tells it), and shown. */
private suspend fun Harness.live(content: WebContent, javaScript: Boolean = true) {
    engine.configure(setupFor(content, javaScript))
    engine.load(content)
    engine.setVisible(true)
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
        // No scripts, so no shim to report a paint: its load is what it shows at.
        val content = demo("<p>covered until painted</p>")
        val h = Harness()
        showSurface(h.engine)
        h.engine.configure(setupFor(content, javaScript = false))
        h.engine.load(content)
        h.engine.setVisible(true)
        val log = watch(h.engine.frame.outer, h.engine.frame.cut)
        frames(1)
        assertEquals("compose", hitAt(100.0, 100.0), "covered, the page takes no clicks")
        waitUntil("the first paint") { EngineEvent.FirstPaint in h.events }
        assertEquals("1", styleValue(h.engine.frame.outer, "opacity"), "seen by its first paint")
        frames(2)
        stopWatching(log)
        assertTrue(loadFrame(log) >= 0, "it loaded")
        assertTrue(drawingAtLoad(log), "drawing in its place, unseen, as it loaded")
        // A frame displayed covered, then one more: the frame the cover gives way to is one the page has drawn.
        assertTrue(seenFrame(log) >= loadFrame(log) + 2, "seen at frame ${seenFrame(log)}, loaded at ${loadFrame(log)}")
        assertTrue(hitAt(100.0, 100.0).startsWith("iframe:webview-"), hitAt(100.0, 100.0))
        TestViewport.clear()
        h.close()
    }

    /**
     * A demo's first paint doesn't wait for its slow image (it waited for the iframe's load: 3,050 ms in the review's
     * probe, the page ready in 22–44 ms). With text, its first contentful paint tells; blank, its DOM and two frames do.
     */
    @Test fun aDemoPaintsBeforeItsSlowImageArrives() = runTest(timeout = 2.minutes) {
        for (body in listOf("<p>text first</p>", "")) {
            val content = demo("$body<img src='${testServer(false, "/webview-test/slow?ms=3000")}'>")
            val h = Harness()
            showSurface(h.engine)
            h.engine.configure(setupFor(content))
            val start = now()
            h.engine.load(content)
            h.engine.setVisible(true)
            waitUntil("the first paint", 10_000) { EngineEvent.FirstPaint in h.events }
            val took = now() - start
            assertTrue(took < 1_500.0, "the first paint came ${took.toInt()} ms after the load of '$body', the image 3000 ms")
            assertEquals(EngineEvent.UrlChanged(content.origin.url), h.all<EngineEvent.UrlChanged>().first())
            TestViewport.clear()
            h.close()
        }
    }

    /**
     * A page that hasn't loaded 3 s after it started shows anyway, as on iOS, instead of keeping its view covered until
     * the network gives up. Its commit can't be seen before its load, so it commits as its URL then.
     */
    @Test fun aUrlPageThatHasNotLoadedAfterThreeSecondsShowsAnyway() = runTest(timeout = 1.minutes) {
        val content = WebContent.Url(testServer(true, "/webview-test/slow-page?ms=8000"))
        val h = Harness(EngineConfig(WebProfile.Default))
        showSurface(h.engine)
        h.engine.configure(setupFor(content))
        val start = now()
        h.engine.load(content)
        h.engine.setVisible(true)
        waitUntil("the first paint", 7_000) { EngineEvent.FirstPaint in h.events }
        val took = now() - start
        assertTrue(took in 2_900.0..5_000.0, "the first paint came ${took.toInt()} ms after the load")
        assertEquals(listOf(EngineEvent.UrlChanged(content.url)), h.all<EngineEvent.UrlChanged>())
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

    /**
     * A demo whose image is slow speaks before its load, as any demo with a CDN script, font or image does, and then
     * reloads itself. Its second document speaks before its load too: it keeps its bridge, and its message after its load
     * arrives. (Only the first load used to count a handshake before it as early: the commits were [url, url, null], and
     * the second document's port was closed at its load.)
     */
    @Test fun aDemoThatReloadsItselfKeepsItsBridge() = runTest(timeout = 1.minutes) {
        val content = demo(
            "<img src='${testServer(false, "/webview-test/slow?ms=700")}'><script>" +
                "if (window.name !== 'reloaded') { window.name = 'reloaded'; addEventListener('load', function () { location.reload(); }); }" +
                " else addEventListener('load', function () { hostBridge.post('loaded', 2); });</script>",
        )
        val h = Harness()
        h.live(content)
        waitUntil("the second document's message", 15_000) { h.all<EngineEvent.PageMessage>().any { "loaded" in it.json } }
        assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url), EngineEvent.UrlChanged(content.origin.url)), h.all<EngineEvent.UrlChanged>())
        TestViewport.clear()
        h.close()
    }

    /**
     * A document that spoke, then left before its own load: the load that comes is its successor's, a new document,
     * which commits (as unreadable). It used to be taken for the first one's own load, and nothing committed.
     */
    @Test fun aDocumentThatLeavesBeforeItsLoadIsFollowedByACommit() = runTest(timeout = 1.minutes) {
        val content = demo(
            "<img src='${testServer(false, "/webview-test/slow?ms=3000")}'><script>" +
                "setTimeout(function () { location.href = 'about:blank'; }, 300);</script>",
        )
        val h = Harness()
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        h.engine.setVisible(true)
        showSurface(h.engine)
        waitUntil("the second commit", 10_000) { h.all<EngineEvent.UrlChanged>().size == 2 }
        assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url), EngineEvent.UrlChanged(null)), h.all<EngineEvent.UrlChanged>())
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
     * A demo's documents speak only through the shim the engine wrote, which holds the secret. A page that runs no shim
     * (its origin may not talk) but makes a handshake of its own, with a secret it guessed, gets no port: it commits
     * once, at its load, and nothing it sends is queued. It used to commit again, as unreadable, and speak as the opaque
     * origin.
     */
    @Test fun aDocumentWithoutTheSecretHandsOverNoPort() = runTest(timeout = 1.minutes) {
        val forged = BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "forged") }), from = "forged")
        val content = demo(
            "<p>x</p><script>addEventListener('load', function () { setTimeout(function () { " +
                "parent.postMessage({ webviewBridge: 1, secret: 'a guess', text: ${JsonPrimitive(forged)} }, '*', [new MessageChannel().port2]);" +
                " }, 100); });</script>",
        )
        val h = Harness()
        try {
            h.engine.configure(setupFor(content).copy(bridgeOrigins = emptySet()))
            h.engine.load(content)
            h.engine.setVisible(true)
            showSurface(h.engine)
            waitUntil("its commit") { h.all<EngineEvent.UrlChanged>().isNotEmpty() }
            pause(1_000)
            assertEquals(listOf(EngineEvent.UrlChanged(content.origin.url)), h.all<EngineEvent.UrlChanged>())
            assertTrue(h.all<EngineEvent.PageMessage>().isEmpty(), "${h.events}")
        } finally {
            TestViewport.clear()
            h.close()
        }
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
            h.engine.setVisible(true)
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
            h.engine.setVisible(true)
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
        h.engine.setVisible(true)
        waitUntil("an answer") { h.all<EngineEvent.Error>().isNotEmpty() || EngineEvent.FirstPaint in h.events }
        assertEquals(1, hitsOf(id), "the redirect reached the app's origin")
        assertTrue(h.all<EngineEvent.Error>().singleOrNull()?.error is WebError.BlockedByPolicy, "${h.events}")
        assertEquals(0, readableIframes(h.engine.frame.cut), "a document on the app's origin is still in the frame")
        assertTrue(h.all<EngineEvent.UrlChanged>().isEmpty(), "it committed: ${h.events}")
        TestViewport.clear()
        h.close()
    }

    /**
     * Each kind of content gets exactly its sandbox, and nothing more: no popups, no top navigation, no modals, no
     * downloads, for a demo or a page of another site; nothing at all for a refusal.
     */
    @Test fun eachKindOfContentGetsExactlyItsSandbox() = runTest(timeout = 1.minutes) {
        val cases = listOf(
            Triple(demo("<p>x</p>"), true, setOf("allow-scripts")),
            Triple(demo("<p>x</p>"), false, emptySet()),
            Triple(WebContent.Url(otherOriginUrl()), true, setOf("allow-scripts", "allow-same-origin", "allow-forms")),
            Triple(WebContent.Url(otherOriginUrl()), false, setOf("allow-same-origin", "allow-forms")),
            Triple(WebContent.Bundle(WebOrigin("plugin-c"), WebResourceProvider { null }), true, emptySet()),
        )
        for ((content, javaScript, expected) in cases) {
            val engine = IframeWebEngine(EngineConfig(WebProfile.Default))
            engine.configure(setupFor(content, javaScript))
            engine.load(content)
            engine.setVisible(true)
            val sandbox = sandboxOf(firstIframe(engine.frame.cut))
            assertEquals(expected, sandbox.split(' ').filter { it.isNotEmpty() }.toSet(), "$content with scripts $javaScript: '$sandbox'")
            engine.release()
        }
    }

    @Test fun anEphemeralEngineRefusesNetworkPages() = runTest(timeout = 1.minutes) {
        val h = Harness(EngineConfig(WebProfile.Ephemeral))
        showSurface(h.engine)
        val content = WebContent.Url(otherOriginUrl())
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        h.engine.setVisible(true)
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
        h.engine.setVisible(true)
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
        h.engine.setVisible(true)
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

    /**
     * A message whose source is null (its window is gone) isn't from an engine whose frame has no window yet either: it
     * isn't in the page (no surface placed it), or its page waits for a slot. Both were null, and null was taken as the
     * frame's own window.
     */
    @Test fun anEngineWhoseFrameHasNoWindowOwnsNoMessage() = runTest(timeout = 1.minutes) {
        val content = demo("<p>x</p>")
        val engine = IframeWebEngine(EngineConfig(WebProfile.Default))
        engine.configure(setupFor(content))
        engine.load(content)
        assertFalse(engine.owns(null))
        engine.release()
    }

    @Test fun anEngineWhoseLayerLeftThePageCrashes() = runTest(timeout = 1.minutes) {
        val h = Harness()
        var x by mutableIntStateOf(40)
        val content = demo("<p>x</p>")
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        h.engine.setVisible(true)
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
