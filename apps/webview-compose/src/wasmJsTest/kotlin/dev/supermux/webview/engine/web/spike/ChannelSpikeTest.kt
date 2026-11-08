package dev.supermux.webview.engine.web.spike

import dev.supermux.webview.bridge.BridgeLimits
import dev.supermux.webview.bridge.BridgeShim
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.web.now
import dev.supermux.webview.engine.web.waitUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** A sandboxed srcdoc frame below the test viewport whose first script is [script]. Counts its load events. */
private fun channelFrame(name: String, script: String): HTMLElement = js("""(function () {
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.setAttribute('data-test-name', name);
  f.style.position = 'fixed';
  f.style.left = '0px';
  f.style.top = '620px';
  f.style.width = '300px';
  f.style.height = '150px';
  f.style.border = '0';
  f.__loads = 0;
  f.addEventListener('load', function () { f.__loads++; });
  f.srcdoc = '<!doctype html><html><head><meta charset="utf-8"><script>' + script + '<\/script></head><body>channel</body></html>';
  document.body.appendChild(f);
  return f;
})()""")

/**
 * The first handshake from [frame]'s window: `{origin, secret, text, port, loadsBefore}`. `loadsBefore` is 0 when it
 * came before the iframe's `load` event. Call it right after creating the frame, in the same task.
 */
private fun nextHandshake(frame: HTMLElement): Promise<JsAny?> = js("""new Promise(function (resolve) {
  function on(e) {
    if (e.source !== frame.contentWindow) return;
    var d = e.data;
    if (!d || d.webviewBridge !== 1 || !e.ports || e.ports.length !== 1) return;
    window.removeEventListener('message', on);
    resolve({ origin: String(e.origin), secret: String(d.secret), text: String(d.text), port: e.ports[0], loadsBefore: frame.__loads });
  }
  window.addEventListener('message', on);
})""")

private fun textOf(handshake: JsAny, key: String): String = js("String(handshake[key])")

private fun intOf(handshake: JsAny, key: String): Int = js("handshake[key] | 0")

private fun portOf(handshake: JsAny): JsAny = js("handshake.port")

private fun onPortText(port: JsAny, handler: (String) -> Unit): Unit =
    js("{ port.onmessage = function (e) { handler(String(e.data)); }; }")

private fun postText(port: JsAny, text: String): Unit = js("{ port.postMessage(text); }")

private fun removeFrame(frame: HTMLElement): Unit = js("{ frame.remove(); }")

/** [count] round trips on [port], one at a time, timed in JS alone: the sorted times, in ms. */
private fun jsEchoTimes(port: JsAny, count: Int): Promise<JsAny?> = js("""new Promise(function (resolve) {
  var times = [], i = 0, t0 = 0;
  port.onmessage = function () {
    times.push(performance.now() - t0);
    if (++i < count) { t0 = performance.now(); port.postMessage('ping'); }
    else { port.onmessage = null; times.sort(function (a, b) { return a - b; }); resolve(times); }
  };
  t0 = performance.now();
  port.postMessage('ping');
})""")

private fun sortedAt(times: JsAny, p: Double): Double = js("times[Math.min(times.length - 1, Math.round((times.length - 1) * p))]")

/** A page that hands over a port and echoes everything on it. */
private const val ECHO_PAGE =
    "var c = new MessageChannel(); c.port1.onmessage = function (e) { c.port1.postMessage(e.data); };" +
        " parent.postMessage({ webviewBridge: 1, secret: 's', text: 'hi' }, '*', [c.port2]);"

/** The web shim's prototype: the common shim over a port it creates on its first send, and an `echo` handler. */
private fun shimPage(secret: String): String = """
(function () {
  var link = (function () {
    var parentWindow = window.parent, port = null;
    return { send: function (text) {
      if (port === null) {
        var channel = new MessageChannel();
        parentWindow.postMessage({ webviewBridge: 1, secret: ${JsonPrimitive(secret)}, text: text }, '*', [channel.port2]);
        port = channel.port1;
        port.onmessage = function (e) { var receive = window.__hostBridgeReceive; if (typeof e.data === 'string' && typeof receive === 'function') receive(e.data); };
        return;
      }
      port.postMessage(text);
    } };
  })();
  ${BridgeShim.source("link.send", mainFrameExpression = "window.parent !== window")}
  hostBridge.handle('echo', function (x) { return x; });
})();
""".trimIndent()

/** Feasibility G5, and whether the handshake comes before the iframe's `load` event. */
class ChannelSpikeTest {
    @Test fun aSandboxedSrcdocHandsOverItsPortAndTheRoundTripIsFast() = runTest(timeout = 4.minutes) {
        // Ordering and identity, over 30 fresh documents.
        var first = 0
        repeat(30) { i ->
            val frame = channelFrame("order-$i", ECHO_PAGE)
            val handshake = checkNotNull(nextHandshake(frame).await<JsAny?>())
            assertEquals("null", textOf(handshake, "origin"), "a sandboxed srcdoc speaks as an opaque origin")
            assertEquals("s", textOf(handshake, "secret"))
            if (intOf(handshake, "loadsBefore") == 0) first++
            removeFrame(frame)
        }
        println("SPIKE info handshake-before-load=$first/30")

        // G5, raw: 1,000 echoes timed in JS, and 1,000 driven from Kotlin as the engine will.
        val echo = channelFrame("echo", ECHO_PAGE)
        val echoPort = portOf(checkNotNull(nextHandshake(echo).await<JsAny?>()))
        val jsTimes = checkNotNull(jsEchoTimes(echoPort, 1_000).await<JsAny?>())
        println("SPIKE G5 raw-js-echo n=1000 p50=${sortedAt(jsTimes, 0.5).ms()} p95=${sortedAt(jsTimes, 0.95).ms()}")
        val kotlinTimes = ArrayList<Double>()
        withContext(Dispatchers.Main) {
            var waiting: CompletableDeferred<String>? = null
            onPortText(echoPort) { waiting?.complete(it) }
            repeat(1_000) {
                val reply = CompletableDeferred<String>()
                waiting = reply
                val start = now()
                postText(echoPort, "ping")
                reply.await()
                kotlinTimes += now() - start
            }
        }
        println("SPIKE G5 kotlin-port-echo n=1000 p50=${percentile(kotlinTimes, 0.5).ms()} p95=${percentile(kotlinTimes, 0.95).ms()}")
        removeFrame(echo)

        // G5, the whole bridge: WebBridge.call → port → BridgeShim → the page's handler → back. Limits raised so the
        // run measures latency, not the rate limiter.
        val origin = "https://spike.invalid"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val bridge = WebBridge(
            { _, _ -> },
            BridgeLimits(
                messagesPerSecond = 1_000_000, messageBurst = 1_000_000,
                charsPerSecond = 1 shl 30, charBurst = 1 shl 30,
                readiesPerSecond = 1_000, readyBurst = 1_000,
            ),
        )
        val page = channelFrame("bridge", shimPage("s3cret"))
        val handshake = checkNotNull(nextHandshake(page).await<JsAny?>())
        assertEquals("s3cret", textOf(handshake, "secret"))
        val port = portOf(handshake)
        val bridgeTimes = ArrayList<Double>()
        withContext(Dispatchers.Main) {
            bridge.connect(sink = { text, _ -> postText(port, text) }, originAllowed = { it == origin }, scope = scope)
            bridge.documentCommitted("$origin/")
            onPortText(port) { text -> bridge.receive(origin, text) }
            bridge.receive(origin, textOf(handshake, "text"))
            waitUntil("the page's ready") { bridge.isReady }
            repeat(1_000) { i ->
                val start = now()
                val result = bridge.call("echo", JsonPrimitive(i))
                bridgeTimes += now() - start
                assertEquals(i, result.jsonPrimitive.int)
            }
            bridge.disconnect()
        }
        scope.cancel()
        removeFrame(page)
        println("SPIKE G5 bridge-call n=1000 p50=${percentile(bridgeTimes, 0.5).ms()} p95=${percentile(bridgeTimes, 0.95).ms()}")
    }
}
