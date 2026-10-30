package dev.supermux.webview.engine.web

import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private fun countLoads(iframe: HTMLElement): Unit = js("{ iframe.__loads = 0; iframe.addEventListener('load', function () { iframe.__loads++; }); }")

private fun loadsOf(iframe: HTMLElement): Int = js("iframe.__loads | 0")

/** A page of another site on the test server (karma.config.d). */
private fun otherSitePage(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-test/page?id=link'")

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

    /** A link the app is asked to open doesn't also navigate the demo: the demo stays where it was. */
    @Test fun anInterceptedLinkLeavesTheDemoWhereItIs() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<a id='out' href='${otherSitePage()}'>out</a>")
        val iframe = iframeIn(h.engine.frame.cut)
        countLoads(iframe)
        h.tell("""{"click": "out"}""")
        waitUntil("the request") { h.client.requests.isNotEmpty() }
        pause(1_000)
        assertEquals(0, loadsOf(iframe), "the demo navigated to the link too")
        h.close()
    }

    /** One click can't become many tabs: a second link within half a second of the first opens nothing. */
    @Test fun linksOpenAtMostOnceEachHalfSecond() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        h.live("<a id='a' href='https://example.com/a'>a</a> <a id='b' href='https://example.com/b'>b</a>")
        h.tell("""{"click": "a"}""")
        h.tell("""{"click": "b"}""")
        pause(400)
        assertEquals(listOf("navigation https://example.com/a"), h.client.requests)
        h.close()
    }

    /**
     * Only a document's own shim tells of a link its user clicked. A demo's document without the secret gets no port at
     * all; this is a document in a `Url` page's frame (a `data:` one, so it also speaks as the opaque origin), which
     * runs no shim, makes a handshake of its own with a guessed secret, and asks on its own port.
     */
    @Test fun aLinkRequestFromADocumentWithoutTheSecretIsRefused() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true, userActivation = { true })
        try {
            h.live(WebContent.Url(otherSitePage()))
            setSrc(
                iframeIn(h.engine.frame.cut),
                dataUrlOf(
                    "<p>opaque</p><script>var c = new MessageChannel();" +
                        "parent.postMessage({ webviewBridge: 1, secret: 'a guess', text: 'x' }, '*', [c.port2]);" +
                        "setTimeout(function () { c.port1.postMessage({ webviewOpen: 'https://example.com/opaque', newWindow: false }); }, 300);" +
                        "</script>",
                ),
            )
            waitUntil("its handshake") { h.events.any { it is EngineEvent.PageMessage && it.json == "x" } }
            pause(800)
            assertEquals(emptyList(), h.client.requests)
        } finally {
            h.close()
        }
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
