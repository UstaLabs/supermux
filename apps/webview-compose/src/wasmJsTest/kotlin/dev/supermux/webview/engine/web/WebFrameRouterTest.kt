package dev.supermux.webview.engine.web

import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** A sandboxed srcdoc frame whose page makes a handshake by hand, and has a button to focus. */
private fun handshakingFrame(): HTMLElement = js("""(function () {
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.style.position = 'fixed';
  f.style.left = '0px';
  f.style.top = '620px';
  f.style.width = '300px';
  f.style.height = '150px';
  f.style.border = '0';
  f.srcdoc = '<!doctype html><html><body><button id="b">b</button><script>var c = new MessageChannel(); parent.postMessage({ webviewBridge: 1, secret: "x", text: "hi" }, "*", [c.port2]);<\/script></body></html>';
  document.body.appendChild(f);
  return f;
})()""")

private fun removeFrame(frame: HTMLElement): Unit = js("{ frame.remove(); }")

private class RecordingRoutee(private val frame: HTMLElement) : FrameRoutee {
    val handshakes = mutableListOf<String>()
    var focusChecks = 0

    override fun owns(source: JsAny?): Boolean = same(source, windowOf(frame))

    override fun onHandshake(origin: String, secret: String, text: String, port: JsAny) {
        handshakes += "$origin $secret $text"
    }

    override fun checkFocus() {
        focusChecks++
    }
}

class WebFrameRouterTest {
    @Test fun aHandshakeReachesOnlyTheRouteeWhoseFrameSentIt() = runTest(timeout = 1.minutes) {
        val mine = handshakingFrame()
        val other = handshakingFrame()
        val routee = RecordingRoutee(mine)
        WebFrameRouter.register(routee)
        waitUntil("the handshake") { routee.handshakes.isNotEmpty() }
        pause(300)
        assertEquals(listOf("null x hi"), routee.handshakes, "only its own frame's, once")
        WebFrameRouter.unregister(routee)
        removeFrame(mine)
        removeFrame(other)
    }

    @Test fun focusMovingIntoAFrameIsCheckedByEveryRoutee() = runTest(timeout = 1.minutes) {
        val frame = handshakingFrame()
        val routee = RecordingRoutee(frame)
        WebFrameRouter.register(routee)
        pause(300)
        val before = routee.focusChecks
        focusElement(frame)
        waitUntil("a focus check") { routee.focusChecks > before }
        WebFrameRouter.unregister(routee)
        removeFrame(frame)
    }
}
