package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private fun srcOf(iframe: HTMLElement): String = js("iframe.getAttribute('src') || ''")

private fun sandboxOf(iframe: HTMLElement): String = js("iframe.getAttribute('sandbox') || ''")

/** Whether the app can still read [url]: a revoked `blob:` URL can't be fetched. */
private fun canFetch(url: String): Promise<JsBoolean> = js("fetch(url).then(function (r) { return r.ok; }, function () { return false; })")

/** What the demo last reported to this window with `{report}`, or null before any. */
private fun reported(): String? = js("(window.__report === undefined) ? null : window.__report")

private fun listenForReports(): Unit = js("""{
  if (!window.__docReports) {
    window.__docReports = true;
    window.addEventListener('message', function (e) { if (e.data && typeof e.data.report === 'string') window.__report = e.data.report; });
  }
  window.__report = undefined;
}""")

/** A page whose `data:` URL is over Chrome's 2 MiB limit, so it loads from a `blob:` URL. */
private fun longPage(body: String): String = body + "<!--" + "x".repeat(2 * 1024 * 1024 + 1) + "-->"

/**
 * An `Html` document is a `data:` URL in a sandboxed frame, not a `srcdoc` (spec Isolation 1: WebKit sends the app's
 * SameSite=Lax cookie from a sandboxed srcdoc frame, and from no data: or blob: one), and not a `blob:` URL while it fits
 * in a `data:` one: Chrome refuses an opaque document's reload and fragment moves to the app's `blob:` URL. A page too
 * long for a `data:` URL loads from a `blob:` one, revoked when its document goes. Either way the frame is an opaque
 * origin, and the sandbox, the shim and the bridge are what they were.
 */
class HtmlDocumentUrlTest {
    @Test fun anHtmlDocumentLoadsFromADataUrlInAnOpaqueSandbox() = runTest(timeout = 1.minutes) {
        listenForReports()
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<script>parent.postMessage({ report: String(origin) }, '*');</script>")
        val iframe = iframeIn(h.engine.frame.cut)
        assertTrue(srcOf(iframe).startsWith("data:text/html"), "src was '${srcOf(iframe).take(40)}'")
        assertFalse(hasAttribute(iframe, "srcdoc"), "a srcdoc is what WebKit sends the SameSite cookie from")
        assertEquals("allow-scripts", sandboxOf(iframe), "no allow-same-origin")
        waitUntil("the demo's origin") { reported() != null }
        assertEquals("null", reported(), "the demo's own origin is opaque")
        h.close()
    }

    @Test fun withScriptsOffTheSandboxIsEmptyAndTheDocumentStillComesFromADataUrl() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        val content = WebContent.Html("<!doctype html><html><body>no script</body></html>")
        h.engine.configure(
            PageSetup(
                javaScriptEnabled = false,
                inspectable = false,
                dark = false,
                transparentBackground = false,
                userAgentSuffix = null,
                bridgeOrigins = emptySet(),
            ),
        )
        h.engine.load(content)
        h.engine.setVisible(true)
        TestViewport.show { Box(Modifier.offset(40.dp, 40.dp).size(300.dp, 200.dp)) { h.engine.Surface(Modifier.fillMaxSize()) } }
        waitUntil("the first paint", 20_000) { EngineEvent.FirstPaint in h.events }
        val iframe = iframeIn(h.engine.frame.cut)
        assertTrue(srcOf(iframe).startsWith("data:"))
        assertEquals("", sandboxOf(iframe))
        h.close()
    }

    /** What the URL escapes (`%`, `#`, line breaks, tabs, non-ASCII) comes back as the page wrote it. */
    @Test fun theDocumentsTextSurvivesTheDataUrl() = runTest(timeout = 1.minutes) {
        listenForReports()
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        val text = "100% #x é 漢字 😀\ttab ?q=a b \"quoted\" <>"
        h.live(
            "<pre id='t'>$text\nline 2</pre>" +
                "<script>parent.postMessage({ report: document.getElementById('t').textContent }, '*');</script> ",
        )
        waitUntil("the demo's text") { reported() != null }
        assertEquals("$text\nline 2", reported())
        h.close()
    }

    /** The document commits as its own URL, hands over its port, and a call to the page and its reply come back through it. */
    @Test fun theBridgeWorksInADataFrame() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        bridgeRoundTrip(h, WebContent.Html("<!doctype html><html><body>$DOUBLER</body></html>"))
        assertTrue(srcOf(iframeIn(h.engine.frame.cut)).startsWith("data:"))
        h.close()
    }

    /** A `data:` document has no usable base: a relative link resolves against the content's URL instead. */
    @Test fun aRelativeLinkResolvesAgainstTheContentsUrl() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<a id='r' href='docs/page.html?x=1'>relative</a>")
        h.tell("""{"click": "r"}""")
        waitUntil("the request") { h.client.requests.isNotEmpty() }
        assertEquals(listOf("navigation ${h.origin}/docs/page.html?x=1"), h.client.requests)
        h.close()
    }

    /** The move a `blob:` document can't make in Chrome: its `hashchange` comes, and the app is asked for nothing. */
    @Test fun aFragmentLinkMovesWithinTheDocument() = runTest(timeout = 1.minutes) {
        listenForReports()
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live(
            "<a id='in' href='#here'>in</a><div style='height:2000px'></div><p id='here'>here</p>" +
                "<script>addEventListener('hashchange', function () { parent.postMessage({ report: location.hash }, '*'); });</script>",
        )
        h.tell("""{"click": "in"}""")
        waitUntil("the fragment") { reported() != null }
        assertEquals("#here", reported())
        pause(300)
        assertEquals(emptyList(), h.client.requests)
        h.close()
    }

    @Test fun aPageTooLongForADataUrlLoadsFromABlobUrlAndKeepsItsBridge() = runTest(timeout = 1.minutes) {
        listenForReports()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        bridgeRoundTrip(h, WebContent.Html(longPage("<!doctype html><html><body>$DOUBLER<script>parent.postMessage({ report: String(origin) }, '*');</script>")))
        val iframe = iframeIn(h.engine.frame.cut)
        assertTrue(srcOf(iframe).startsWith("blob:"), "src was '${srcOf(iframe).take(40)}'")
        assertEquals("allow-scripts", sandboxOf(iframe))
        waitUntil("the demo's origin") { reported() != null }
        assertEquals("null", reported(), "the blob: demo's own origin is opaque too")
        h.close()
    }

    @Test fun aBlobUrlIsRevokedWhenTheEngineIsReleased() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live(longPage("<p>release me</p>"))
        val url = srcOf(iframeIn(h.engine.frame.cut))
        assertTrue(url.startsWith("blob:"))
        assertTrue(canFetch(url).await<JsBoolean>().toBoolean(), "the URL names the page while it shows")
        h.close()
        assertFalse(canFetch(url).await<JsBoolean>().toBoolean(), "released: the URL is revoked")
    }

    @Test fun aBlobUrlIsRevokedWhenTheDocumentIsReplaced() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live(longPage("<p>first</p>"))
        val first = srcOf(iframeIn(h.engine.frame.cut))
        assertTrue(first.startsWith("blob:"))
        assertTrue(canFetch(first).await<JsBoolean>().toBoolean())
        h.engine.load(WebContent.Html(longPage("<!doctype html><html><body>second</body></html>"), origin = WebOrigin("second")))
        waitUntil("the second document", 20_000) { srcOf(iframeIn(h.engine.frame.cut)) != first }
        val second = srcOf(iframeIn(h.engine.frame.cut))
        assertTrue(second.startsWith("blob:"))
        var tries = 0
        while (canFetch(first).await<JsBoolean>().toBoolean() && tries++ < 50) pause(100)
        assertFalse(canFetch(first).await<JsBoolean>().toBoolean(), "replaced: the old URL is revoked")
        assertTrue(canFetch(second).await<JsBoolean>().toBoolean(), "the new document's URL is live")
        h.close()
    }

    private companion object {
        const val DOUBLER = "<script>hostBridge.handle('double', function (x) { return x * 2; });</script>"
    }
}

/** [content] live in [h]: it commits as its own URL, speaks as its origin, and answers a call over its port. */
private suspend fun bridgeRoundTrip(h: ListHarness, content: WebContent.Html) {
    h.live(content)
    assertTrue(EngineEvent.UrlChanged(content.origin.url) in h.events, "the commit names the content's URL, not the frame's")
    val ready = h.events.first { it is EngineEvent.PageMessage && it.json.startsWith(WebBridge.READY_PREFIX) } as EngineEvent.PageMessage
    assertEquals(content.origin.origin, ready.origin, "it speaks as the content's origin, which the shim's secret vouches for")
    val nonce = (BridgeCodec.decode(ready.json) as BridgeMessage.Event).data.jsonObject["nonce"]!!.jsonPrimitive.content
    h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(7, "double", JsonPrimitive(21)), to = nonce), content.origin.origin)
    waitUntil("the reply") {
        h.events.filterIsInstance<EngineEvent.PageMessage>().any { (BridgeCodec.decode(it.json) as? BridgeMessage.Reply)?.id == 7L }
    }
    val reply = h.events.filterIsInstance<EngineEvent.PageMessage>().mapNotNull { BridgeCodec.decode(it.json) as? BridgeMessage.Reply }.first { it.id == 7L }
    assertEquals(JsonPrimitive(42), reply.result)
}
