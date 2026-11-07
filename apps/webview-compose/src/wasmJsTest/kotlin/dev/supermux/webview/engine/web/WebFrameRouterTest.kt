package dev.supermux.webview.engine.web

import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** A sandboxed srcdoc frame whose page makes a handshake by hand, with [text], and has a button to focus. */
private fun handshakingFrame(text: String = "hi"): HTMLElement = js("""(function () {
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.style.position = 'fixed';
  f.style.left = '0px';
  f.style.top = '620px';
  f.style.width = '300px';
  f.style.height = '150px';
  f.style.border = '0';
  f.srcdoc = '<!doctype html><html><body><button id="b">b</button><script>var c = new MessageChannel(); parent.postMessage({ webviewBridge: 1, secret: "x", text: ' + JSON.stringify(text) + ' }, "*", [c.port2]);<\/script></body></html>';
  document.body.appendChild(f);
  return f;
})()""")

private fun removeFrame(frame: HTMLElement): Unit = js("{ frame.remove(); }")

private class RecordingRoutee(val frame: HTMLElement) : FrameRoutee {
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
    /** Each routee gets its own frame's handshake, once, and the other's never: the window that sent it decides. */
    @Test fun aHandshakeReachesOnlyTheRouteeWhoseFrameSentIt() = runTest(timeout = 1.minutes) {
        val first = RecordingRoutee(handshakingFrame("first"))
        val second = RecordingRoutee(handshakingFrame("second"))
        WebFrameRouter.register(first)
        WebFrameRouter.register(second)
        pause(800)
        assertEquals(listOf("null x first"), first.handshakes)
        assertEquals(listOf("null x second"), second.handshakes)
        WebFrameRouter.unregister(first)
        WebFrameRouter.unregister(second)
        removeFrame(first.frame)
        removeFrame(second.frame)
    }

    /** A released engine leaves the router: the router keeps no engine alive, nor asks one that is gone. */
    @Test fun aReleasedEngineLeavesTheRouter() = runTest(timeout = 1.minutes) {
        val before = WebFrameRouter.registered
        val engine = IframeWebEngine(dev.supermux.webview.engine.EngineConfig(dev.supermux.webview.WebProfile.Default))
        assertEquals(before + 1, WebFrameRouter.registered)
        engine.release()
        assertEquals(before, WebFrameRouter.registered)
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
