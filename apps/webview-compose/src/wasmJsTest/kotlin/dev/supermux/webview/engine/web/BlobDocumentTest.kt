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

/**
 * An `Html` document is a `blob:` URL in a sandboxed frame, not a `srcdoc` (spec Isolation 1: WebKit sends the app's
 * SameSite=Lax cookie from a sandboxed srcdoc frame, and from no blob: one). The sandbox, the shim and the bridge are
 * what they were.
 */
class BlobDocumentTest {
    @Test fun anHtmlDocumentLoadsFromABlobUrlInAnOpaqueSandbox() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<p>blob</p>")
        val iframe = iframeIn(h.engine.frame.cut)
        assertTrue(srcOf(iframe).startsWith("blob:"), "src was '${srcOf(iframe)}'")
        assertFalse(hasAttribute(iframe, "srcdoc"), "a srcdoc is what WebKit sends the SameSite cookie from")
        assertEquals("allow-scripts", sandboxOf(iframe), "no allow-same-origin")
        h.close()
    }

    @Test fun withScriptsOffTheSandboxIsEmptyAndTheDocumentStillComesFromABlob() = runTest(timeout = 1.minutes) {
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
        assertTrue(srcOf(iframe).startsWith("blob:"))
        assertEquals("", sandboxOf(iframe))
        h.close()
    }

    /** The document commits as its own URL, hands over its port, and a call to the page and its reply come back through it. */
    @Test fun theBridgeStillWorksInABlobFrame() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        val content = WebContent.Html(
            "<!doctype html><html><body><script>hostBridge.handle('double', function (x) { return x * 2; });</script></body></html>",
        )
        h.live(content)
        assertTrue(EngineEvent.UrlChanged(content.origin.url) in h.events, "the commit names the content's URL, not the blob's")
        val ready = h.events.first { it is EngineEvent.PageMessage && it.json.startsWith(WebBridge.READY_PREFIX) } as EngineEvent.PageMessage
        assertEquals(content.origin.origin, ready.origin, "it speaks as the content's origin, which the shim's secret vouches for")
        val nonce = (BridgeCodec.decode(ready.json) as BridgeMessage.Event).data.jsonObject["nonce"]!!.jsonPrimitive.content
        h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(7, "double", JsonPrimitive(21)), to = nonce), content.origin.origin)
        waitUntil("the reply") {
            h.events.filterIsInstance<EngineEvent.PageMessage>().any { (BridgeCodec.decode(it.json) as? BridgeMessage.Reply)?.id == 7L }
        }
        val reply = h.events.filterIsInstance<EngineEvent.PageMessage>().mapNotNull { BridgeCodec.decode(it.json) as? BridgeMessage.Reply }.first { it.id == 7L }
        assertEquals(JsonPrimitive(42), reply.result)
        h.close()
    }

    /** A `blob:` document has no usable base: a relative link resolves against the content's URL instead. */
    @Test fun aRelativeLinkResolvesAgainstTheContentsUrl() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<a id='r' href='docs/page.html?x=1'>relative</a>")
        h.tell("""{"click": "r"}""")
        waitUntil("the request") { h.client.requests.isNotEmpty() }
        assertEquals(listOf("navigation ${h.origin}/docs/page.html?x=1"), h.client.requests)
        h.close()
    }

    @Test fun aFragmentLinkStillMovesWithinTheDocument() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<a id='in' href='#here'>in</a><div style='height:2000px'></div><p id='here'>here</p>")
        h.tell("""{"click": "in"}""")
        pause(400)
        assertEquals(emptyList(), h.client.requests)
        h.close()
    }

    @Test fun theUrlIsRevokedWhenTheEngineIsReleased() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<p>release me</p>")
        val url = srcOf(iframeIn(h.engine.frame.cut))
        assertTrue(url.startsWith("blob:"))
        assertTrue(canFetch(url).await<JsBoolean>().toBoolean(), "the URL names the page while it shows")
        h.close()
        assertFalse(canFetch(url).await<JsBoolean>().toBoolean(), "released: the URL is revoked")
    }

    @Test fun theUrlIsRevokedWhenTheDocumentIsReplaced() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<p>first</p>")
        val first = srcOf(iframeIn(h.engine.frame.cut))
        assertTrue(canFetch(first).await<JsBoolean>().toBoolean())
        h.engine.load(WebContent.Html("<!doctype html><html><body>second</body></html>", origin = WebOrigin("second")))
        waitUntil("the second document", 20_000) { srcOf(iframeIn(h.engine.frame.cut)) != first }
        val second = srcOf(iframeIn(h.engine.frame.cut))
        assertTrue(second.startsWith("blob:"))
        var tries = 0
        while (canFetch(first).await<JsBoolean>().toBoolean() && tries++ < 50) pause(100)
        assertFalse(canFetch(first).await<JsBoolean>().toBoolean(), "replaced: the old URL is revoked")
        assertTrue(canFetch(second).await<JsBoolean>().toBoolean(), "the new document's URL is live")
        h.close()
    }
}
