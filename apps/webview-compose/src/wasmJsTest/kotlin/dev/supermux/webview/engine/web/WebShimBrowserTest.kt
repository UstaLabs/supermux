package dev.supermux.webview.engine.web

import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.WebBridge
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A sandboxed srcdoc frame holding [html], below the test viewport. */
private fun shimFrame(html: String): HTMLElement = js("""(function () {
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.style.position = 'fixed';
  f.style.left = '0px';
  f.style.top = '620px';
  f.style.width = '300px';
  f.style.height = '150px';
  f.style.border = '0';
  f.srcdoc = html;
  document.body.appendChild(f);
  return f;
})()""")

/** The first handshake from [frame]: `{origin, secret, text, port}`. Call it right after creating the frame. */
private fun handshakeFrom(frame: HTMLElement): Promise<JsAny?> = js("""new Promise(function (resolve) {
  function on(e) {
    if (e.source !== frame.contentWindow || !e.data || e.data.webviewBridge !== 1) return;
    window.removeEventListener('message', on);
    resolve({ origin: String(e.origin), secret: String(e.data.secret), text: String(e.data.text), port: e.ports[0] });
  }
  window.addEventListener('message', on);
})""")

private fun field(handshake: JsAny, key: String): String = js("String(handshake[key])")

private fun portIn(handshake: JsAny): JsAny = js("handshake.port")

/** Everything that arrives on [port], text or not, as JSON strings in arrival order. */
private fun record(port: JsAny): JsAny = js("""(function () {
  var got = [];
  port.onmessage = function (e) { got.push(typeof e.data === 'string' ? e.data : JSON.stringify(e.data)); };
  return got;
})()""")

private fun countOf(log: JsAny): Int = js("log.length")

private fun itemOf(log: JsAny, index: Int): String = js("log[index]")

private fun send(port: JsAny, text: String): Unit = js("{ port.postMessage(text); }")

private fun syntheticClick(frame: HTMLElement): Unit = js("{ frame.contentWindow.postMessage('click-the-link', '*'); }")

private fun removeFrame(frame: HTMLElement): Unit = js("{ frame.remove(); }")

class WebShimBrowserTest {
    private fun document(body: String, secret: String = "s3cret"): String =
        WebDocuments.withScript("<!doctype html><html><head></head><body>$body</body></html>", WebShim.source(secret, pageOrigin()))

    private fun messages(log: JsAny): List<BridgeMessage> = (0 until countOf(log)).mapNotNull { BridgeCodec.decode(itemOf(log, it)) }

    @Test fun theDocumentHandsItsPortToItsParentWithItsSecretAndItsReady() = runTest(timeout = 1.minutes) {
        val frame = shimFrame(document("<p>hi</p>"))
        val handshake = checkNotNull(handshakeFrom(frame).await<JsAny?>())
        assertEquals("null", field(handshake, "origin"), "a sandboxed srcdoc is an opaque origin")
        assertEquals("s3cret", field(handshake, "secret"))
        val ready = field(handshake, "text")
        assertTrue(ready.startsWith(WebBridge.READY_PREFIX) && ready.length <= WebBridge.READY_MAX_CHARS, ready)
        removeFrame(frame)
    }

    @Test fun hostMessagesOnThePortReachThePageAndItsAnswersComeBackOnIt() = runTest(timeout = 1.minutes) {
        val frame = shimFrame(document("<script>hostBridge.handle('double', function (x) { return x * 2; });</script>"))
        val handshake = checkNotNull(handshakeFrom(frame).await<JsAny?>())
        val port = portIn(handshake)
        val log = record(port)
        val nonce = (BridgeCodec.decode(field(handshake, "text")) as BridgeMessage.Event).data.jsonObject["nonce"]!!.jsonPrimitive.content
        send(port, BridgeCodec.encode(BridgeMessage.Call(7, "double", JsonPrimitive(21)), to = nonce))
        waitUntil("the page's answer") { messages(log).any { it is BridgeMessage.Reply } }
        assertEquals(BridgeMessage.Reply(7, JsonPrimitive(42)), messages(log).filterIsInstance<BridgeMessage.Reply>().single())
        removeFrame(frame)
    }

    @Test fun thePagesOwnScriptsCannotFindTheSecret() = runTest(timeout = 1.minutes) {
        // The probe spells the secret in two halves: written whole, its own source would contain it.
        val probe = "<script>hostBridge.post('found', document.documentElement.outerHTML.indexOf('s3' + 'cret') >= 0 || " +
            "document.scripts.length !== 1);</script>"
        val frame = shimFrame(document(probe))
        val handshake = checkNotNull(handshakeFrom(frame).await<JsAny?>())
        val log = record(portIn(handshake))
        waitUntil("the page's report") { messages(log).any { it is BridgeMessage.Event && it.name == "found" } }
        val found = messages(log).filterIsInstance<BridgeMessage.Event>().single { it.name == "found" }
        assertEquals(JsonPrimitive(false), found.data, "the shim's script removed itself before the page's ran")
        removeFrame(frame)
    }

    @Test fun aClickThePageMakesItselfOpensNothing() = runTest(timeout = 1.minutes) {
        // Only trusted clicks become requests (web-check's overlay mode clicks for real). A synthetic one, or a link to
        // a place in the same document, passes through untouched.
        val page = "<a id='out' href='about:blank#elsewhere'>out</a><a id='in' href='#here'>in</a>" +
            "<script>addEventListener('message', function () { document.getElementById('in').click(); document.getElementById('out').click(); });</script>"
        val frame = shimFrame(document(page))
        val handshake = checkNotNull(handshakeFrom(frame).await<JsAny?>())
        val log = record(portIn(handshake))
        syntheticClick(frame)
        pause(500)
        val requests = (0 until countOf(log)).map { itemOf(log, it) }.filter { "webviewOpen" in it }
        assertEquals(emptyList(), requests)
        removeFrame(frame)
    }
}
