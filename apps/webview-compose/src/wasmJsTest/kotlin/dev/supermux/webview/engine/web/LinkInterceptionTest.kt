package dev.supermux.webview.engine.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The last fragment a demo reported (`{hash}` posted to this window), or null before any. */
private fun reportedHash(): String? = js("(window.__hash === undefined) ? null : window.__hash")

private fun listenForHashReports(): Unit = js("""{
  if (!window.__hashReports) {
    window.__hashReports = true;
    window.addEventListener('message', function (e) { if (e.data && typeof e.data.hash === 'string') window.__hash = e.data.hash; });
  }
  window.__hash = undefined;
}""")

/**
 * The links a user clicks in a demo go through the app's `WebClient`, whatever they are written with, and a link to a
 * place in the demo stays in it. The shim here takes the page's synthetic clicks for the user's, and the app has
 * activation: only the shim's reading of the link is under test (web-check's overlay mode clicks for real).
 */
class LinkInterceptionTest {
    /**
     * A srcdoc demo's base is the app's own page, so `#here` resolved to the app's URL: the shim asked the app to open
     * it, and the default policy opened the app in a new tab. It moves within the demo instead.
     */
    @Test fun anAnchorInADemoMovesWithinIt() = runTest(timeout = 1.minutes) {
        listenForHashReports()
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live(
            "<a id='in' href='#here'>in</a><div style='height:2000px'></div><p id='here'>here</p>" +
                "<script>addEventListener('hashchange', function () { parent.postMessage({ hash: location.hash }, '*'); });</script>",
        )
        h.tell("""{"click": "in"}""")
        waitUntil("the demo's fragment") { reportedHash() != null }
        assertEquals("#here", reportedHash())
        pause(300)
        assertEquals(emptyList(), h.client.requests, "the app was asked to open a page")
        h.close()
    }

    /** An SVG link's `href` is an `SVGAnimatedString`: the request carried "[object SVGAnimatedString]". */
    @Test fun anSvgLinkAsksForItsUrl() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live(
            "<svg width='100' height='100'><a href='https://example.com/svg'><rect id='r' width='100' height='100'/></a></svg>" +
                "<script>window.clickRect = function () {" +
                " document.getElementById('r').dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, composed: true, button: 0 })); };</script>",
        )
        h.tell("""{"call": "clickRect"}""")
        waitUntil("the request") { h.client.requests.isNotEmpty() }
        assertEquals(listOf("navigation https://example.com/svg"), h.client.requests)
        h.close()
    }

    /** A link in an open shadow root: the click reaches the document retargeted to the host, with no link above it. */
    @Test fun aLinkInShadowDomAsksForItsUrl() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live(
            "<div id='host'></div><script>" +
                "var root = document.getElementById('host').attachShadow({ mode: 'open' });" +
                "root.innerHTML = \"<a id='inner' href='https://example.com/shadow'>inside</a>\";" +
                "window.clickInner = function () { root.getElementById('inner').click(); };</script>",
        )
        h.tell("""{"call": "clickInner"}""")
        waitUntil("the request") { h.client.requests.isNotEmpty() }
        assertEquals(listOf("navigation https://example.com/shadow"), h.client.requests)
        h.close()
    }
}
