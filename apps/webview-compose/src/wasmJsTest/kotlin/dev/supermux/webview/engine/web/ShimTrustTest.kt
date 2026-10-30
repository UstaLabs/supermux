package dev.supermux.webview.engine.web

import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Records what the page asks of the app, and refuses it all. */
internal class RecordingEngineClient : EngineClient {
    val requests = ArrayList<String>()

    override fun onNavigation(request: NavigationRequest): NavigationDecision {
        requests += "navigation ${request.url}"
        return NavigationDecision.Block
    }

    override suspend fun onDialog(dialog: WebDialog): DialogResult = DialogResult.Dismissed

    override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null

    override suspend fun onPermission(request: PermissionRequest): Boolean = false

    override suspend fun onDownload(request: DownloadRequest): Boolean = false

    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        requests += "new window ${request.url}"
        return NewWindowDecision.Block
    }
}

/** What a page reported about its theft (`{stole}` posted to this window): null before any report. */
private fun reportedTheft(): Boolean? = js("(window.__stole === undefined) ? null : window.__stole")

private fun listenForTheftReports(): Unit = js("""{
  if (!window.__theftReports) {
    window.__theftReports = true;
    window.addEventListener('message', function (e) { if (e.data && typeof e.data.stole === 'boolean') window.__stole = e.data.stole; });
  }
  window.__stole = undefined;
}""")

/**
 * With the port, a page could scroll the list and ask the app to open any URL as if the user had clicked it. It tries
 * both, and then tells the test whether it had the port, when asked (`{reportTheft}`).
 */
private const val ABUSE = """
function abuse(port) {
  var post = Object.getPrototypeOf(port).postMessage;
  post.call(port, { webviewScroll: [0, 300] });
  post.call(port, { webviewOpen: 'https://attacker.example/phish', newWindow: false });
}
addEventListener('message', function (e) { if (e.data && e.data.reportTheft) parent.postMessage({ stole: stolen !== null }, '*'); });
"""

/** A page that patches `MessagePort.prototype.postMessage`, then makes the shim send something when told (`{poke}`). */
private const val THIEF_BY_POST = """<p id='x'>a demo that patches postMessage</p><script>
var stolen = null, original = MessagePort.prototype.postMessage;
$ABUSE
MessagePort.prototype.postMessage = function () {
  if (stolen === null && this instanceof MessagePort) { stolen = this; abuse(this); }
  return original.apply(this, arguments);
};
addEventListener('message', function (e) { if (e.data && e.data.poke) hostBridge.post('poke', 1); });
</script>"""

/** A page that patches the `MessageEvent` `data` getter, which the shim reads when the app sends it something. */
private const val THIEF_BY_DATA = """<p id='x'>a demo that patches MessageEvent.data</p><script>
var stolen = null, get = Object.getOwnPropertyDescriptor(MessageEvent.prototype, 'data').get;
$ABUSE
Object.defineProperty(MessageEvent.prototype, 'data', { configurable: true, get: function () {
  var port = this.target;
  if (stolen === null && port instanceof MessagePort) { stolen = port; abuse(port); }
  return get.call(this);
} });
</script>"""

/** [html] as a `data:` URL: a document the engine writes no shim into, and gives no secret. */
internal fun dataUrlOf(html: String): String = js("'data:text/html,' + encodeURIComponent(html)")

/** A demo that navigates its own frame to [url] when told `{go}`, as any demo may. */
internal fun navigatingDemo(url: String): String =
    "<p>a demo</p><script>addEventListener('message', function (e) { if (e.data && e.data.go) location.href = ${JsonPrimitive(url)}; });</script>"

/**
 * A document with no shim that speaks the shim's protocol (the review's probe): it hands over a port of its own, with
 * no secret, then tells of a scroll and a fling the user never made.
 */
private const val FORGED_SCROLL = """<p>forged</p><script>
var ch = new MessageChannel();
parent.postMessage({ webviewBridge: 1, secret: '', text: 'forged' }, '*', [ch.port2]);
setTimeout(function () {
  ch.port1.postMessage({ webviewScroll: [0, 300] });
  ch.port1.postMessage({ webviewFling: [0, 3000] });
}, 100);
</script>"""

/**
 * The review's probe of the one-handshake-per-document bound: a document with no shim that hands over a port of its
 * own, then 40 times says on it that it left, and hands over another.
 */
private const val FORGED_DOCUMENTS = """<p>forged</p><script>
var port = null, n = 0;
function handshake() {
  var ch = new MessageChannel();
  parent.postMessage({ webviewBridge: 1, secret: '', text: 'forged' }, '*', [ch.port2]);
  port = ch.port1;
}
function again() {
  if (n++ >= 40) return;
  port.postMessage({ webviewGone: 1 });
  setTimeout(function () { handshake(); setTimeout(again, 20); }, 20);
}
handshake();
setTimeout(again, 400);
</script>"""

/** A page of another site on the test server (karma.config.d). */
private fun otherSitePage(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-test/page?id=trust'")

/**
 * The shim's port is the page's only way to scroll the list or ask the app to open a link, so a page script must not
 * get it. The engines here trust the app's activation ([ListHarness]'s `userActivation`), so only the shim stands in
 * the way: the probes the review ran, which scrolled the list 300 px and got a phishing link asked for as a click.
 */
class ShimTrustTest {
    @Test fun aPageThatPatchesPostMessageCannotTakeTheShimsPort() = runTest(timeout = 1.minutes) {
        listenForTheftReports()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        h.live(THIEF_BY_POST)
        h.tell("""{"poke": true}""")
        pause(500)
        h.tell("""{"reportTheft": true}""")
        waitUntil("the page's report") { reportedTheft() != null }
        assertEquals(false, reportedTheft(), "the page got the shim's port")
        assertEquals(0, h.scrolled, "the list moved with no user input")
        assertEquals(emptyList(), h.client.requests)
        h.close()
    }

    @Test fun aPageThatPatchesTheMessageDataGetterCannotTakeTheShimsPort() = runTest(timeout = 1.minutes) {
        listenForTheftReports()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        h.live(THIEF_BY_DATA)
        // The shim reads what the app sends it.
        h.engine.postMessage(BridgeCodec.encode(BridgeMessage.Event("\$hello")), h.origin)
        pause(500)
        h.tell("""{"reportTheft": true}""")
        waitUntil("the page's report") { reportedTheft() != null }
        assertEquals(false, reportedTheft(), "the page got the shim's port")
        assertEquals(0, h.scrolled, "the list moved with no user input")
        assertEquals(emptyList(), h.client.requests)
        h.close()
    }

    /**
     * A link a page's user clicks reaches the app only while the app's window has transient activation, which a click
     * in the frame gives it (web-check's overlay mode clicks for real). The shim here takes a synthetic click for the
     * user's, so only the engine's own check stands: Karma's window has none.
     */
    @Test fun aLinkRequestNeedsTheAppsTransientActivation() = runTest(timeout = 1.minutes) {
        val page = "<a id='out' href='https://example.com/page'>out</a>"
        val trusting = ListHarness(trustSyntheticInput = true, userActivation = { true })
        trusting.live(page)
        trusting.tell("""{"click": "out"}""")
        waitUntil("the request, with activation") { trusting.client.requests.isNotEmpty() }
        assertEquals(listOf("navigation https://example.com/page"), trusting.client.requests)
        trusting.close()

        val h = ListHarness(trustSyntheticInput = true)
        h.live(page)
        h.tell("""{"click": "out"}""")
        pause(600)
        assertEquals(emptyList(), h.client.requests, "a request with no activation in the app")
        h.close()
    }

    /**
     * A demo may navigate its own frame anywhere, here to a `data:` URL: to a document the engine wrote no shim into,
     * and gave no secret. Its handshake gets no port. The review's probe, which handed over a port of its own, scrolled
     * the list 300 px with no input.
     */
    @Test fun aDocumentADemoNavigatesToCannotScrollTheList() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = false, userActivation = { false })
        try {
            h.live(navigatingDemo(dataUrlOf(FORGED_SCROLL)))
            h.tell("""{"go": true}""")
            pause(2_000)
            assertEquals(0, h.scrolled, "the list moved with no user input")
        } finally {
            h.close()
        }
    }

    /**
     * One handshake per document: a document a demo navigates to gets no port, so it can't say on one that it left
     * either. The review's probe, 40 such notices each followed by a new handshake, made 41 commits. It commits once,
     * at its load, as a document that can't be read, and nothing it sends is queued.
     */
    @Test fun aDocumentADemoNavigatesToCannotFloodCommits() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = false, userActivation = { false })
        try {
            h.live(navigatingDemo(dataUrlOf(FORGED_DOCUMENTS)))
            val before = h.events.size
            h.tell("""{"go": true}""")
            pause(4_000)
            val after = h.events.drop(before)
            assertEquals(listOf(EngineEvent.UrlChanged(null)), after.filterIsInstance<EngineEvent.UrlChanged>(), "$after")
            assertTrue(after.none { it is EngineEvent.PageMessage && it.origin == "null" }, "$after")
        } finally {
            h.close()
        }
    }

    /**
     * One handshake per document holds for a `Url` page's own documents too: a port one of them hands over can't say
     * that it left, which only a shim tells. The review's probe, in a `Url` page's frame, gets one port, and one message
     * queued.
     */
    @Test fun aUrlPagesOwnDocumentCannotFloodCommits() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = false, userActivation = { false })
        try {
            h.live(WebContent.Url(otherSitePage()))
            val before = h.events.size
            setSrc(iframeIn(h.engine.frame.cut), dataUrlOf(FORGED_DOCUMENTS))
            pause(4_000)
            val after = h.events.drop(before)
            assertEquals(1, after.count { it is EngineEvent.PageMessage && it.json == "forged" }, "$after")
            assertTrue(after.count { it is EngineEvent.UrlChanged } <= 2, "only its load and its one handshake commit: $after")
        } finally {
            h.close()
        }
    }

    /**
     * A `Url` page runs no shim and has no secret: a document in its frame that hands over a port of its own is heard
     * for bridge messages only. Here one the engine didn't start, which also tells of a scroll and a fling the user
     * never made.
     */
    @Test fun aUrlPagesOwnPortCarriesBridgeMessagesOnly() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = false, userActivation = { false })
        try {
            h.live(WebContent.Url(otherSitePage()))
            setSrc(iframeIn(h.engine.frame.cut), dataUrlOf(FORGED_SCROLL))
            waitUntil("its message") { h.events.any { it is EngineEvent.PageMessage && it.json == "forged" } }
            pause(1_000)
            assertEquals(0, h.scrolled, "the list moved with no user input")
        } finally {
            h.close()
        }
    }
}
