package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A port nothing listens to, for handshakes made by hand. */
private fun loosePort(): JsAny = js("new MessageChannel().port1")

/** Records when [outer] first turns visible, and when the first iframe in [cut] loads. Call it right after load(). */
private fun watch(outer: HTMLElement, cut: HTMLElement): JsAny = js("""(function () {
  var log = { visibleAt: -1, loadAt: -1 };
  new MutationObserver(function () {
    if (log.visibleAt < 0 && outer.style.visibility === 'visible') log.visibleAt = performance.now();
  }).observe(outer, { attributes: true, attributeFilter: ['style'] });
  var iframe = cut.querySelector('iframe');
  if (iframe) iframe.addEventListener('load', function () { if (log.loadAt < 0) log.loadAt = performance.now(); });
  return log;
})()""")

private fun visibleAt(log: JsAny): Double = js("log.visibleAt")

private fun loadAt(log: JsAny): Double = js("log.loadAt")

private fun iframeCount(cut: HTMLElement): Int = js("cut.querySelectorAll('iframe').length")

/** A URL of another origin on the test server (localhost ↔ 127.0.0.1): it answers 404, which still loads a page. */
private fun otherOriginUrl(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-missing-page'")

private fun removeLayers(): Unit = js("{ var all = document.querySelectorAll('[data-webview-layer]'); for (var i = 0; i < all.length; i++) all[i].remove(); }")

/** An engine whose events are collected as the runtime collects them, on Dispatchers.Main. */
private class Harness(config: EngineConfig = EngineConfig(WebProfile.Ephemeral)) {
    val engine = IframeWebEngine(config)
    val events = ArrayList<EngineEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch { engine.events.collect { events += it } }
    }

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

    @Test fun theFrameStaysHiddenUntilItsDocumentLoadedSoTheRuntimesCoverShows() = runTest(timeout = 1.minutes) {
        val content = demo("<p>covered until painted</p>")
        val h = Harness()
        showSurface(h.engine)
        h.engine.configure(setupFor(content))
        h.engine.load(content)
        val log = watch(h.engine.frame.outer, h.engine.frame.cut)
        waitUntil("the first paint") { EngineEvent.FirstPaint in h.events }
        frames(2)
        assertTrue(loadAt(log) > 0 && visibleAt(log) >= loadAt(log), "visible at ${visibleAt(log)}, loaded at ${loadAt(log)}")
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

    @Test fun aDocumentWithoutTheSecretCommitsAndSpeaksAsAnOpaqueOrigin() = runTest(timeout = 1.minutes) {
        val content = demo("<p>x</p>")
        val h = Harness()
        h.live(content)
        val forged = BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "forged") }), from = "forged")
        h.engine.onHandshake("null", "a guess", forged, loosePort())
        waitUntil("the forged document's commit") { h.all<EngineEvent.UrlChanged>().size == 2 }
        assertEquals(EngineEvent.UrlChanged(null), h.all<EngineEvent.UrlChanged>().last())
        assertEquals("null", h.all<EngineEvent.PageMessage>().last().origin)
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

    @Test fun aHiddenEngineLeavesTheLayoutAndComesBack() = runTest(timeout = 1.minutes) {
        val h = Harness()
        h.live(demo("<p>x</p>"))
        h.engine.setVisible(false)
        assertEquals("none", styleValue(h.engine.frame.outer, "display"))
        h.engine.setVisible(true)
        assertEquals("block", styleValue(h.engine.frame.outer, "display"))
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
