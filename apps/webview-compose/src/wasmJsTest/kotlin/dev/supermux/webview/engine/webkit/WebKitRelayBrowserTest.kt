package dev.supermux.webview.engine.webkit

import dev.supermux.webview.bridge.WebBridge
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun newFrame(): JsAny =
    js("(function () { var f = document.createElement('iframe'); f.style.width = '300px'; f.style.height = '200px'; f.style.border = '0'; document.body.appendChild(f); return f.contentWindow; })()")
private fun runIn(w: JsAny, code: String): Unit = js("{ w.eval(code); }")
private fun evalIn(w: JsAny, code: String): String = js("String(w.eval(code))")
private fun postedCount(w: JsAny): Int = js("w.__posted.length")
private fun postedAt(w: JsAny, index: Int): String = js("w.__posted[index]")
private fun wait(millis: Int): Promise<JsAny?> = js("new Promise(function (resolve) { setTimeout(resolve, millis); })")

/** Runs [body] as `callAsyncJavaScript` would, a function of `json`, `target` and `name`, in [w]; returns its result. */
private fun deliverIn(w: JsAny, body: String, json: String, target: String, name: String): Boolean =
    js("w.eval('(function (json, target, name) {' + body + '})')(json, target, name) === true")

/**
 * The relay and the shim's channel, executed by a real JavaScript engine. Chrome has no content worlds, so both run in
 * one world here: these tests check the scripts' logic (tokens, forwarding, the origin test at delivery, the hints),
 * and the engine checks in the iOS app check the worlds.
 */
class WebKitRelayBrowserTest {
    private val scripts = WebKitScripts("webview", "webview-out-test", "webview-in-test")

    /**
     * A frame with a fake `window.webkit` message handler, the relay and the shim, for [origins] (default: its own). The
     * relay runs first unless [shimFirst]: WebKit runs the two worlds' document-start scripts in either order.
     */
    private fun frame(origins: Set<String>? = null, shimFirst: Boolean = false, input: Boolean = false, hints: Boolean = false): JsAny {
        val w = newFrame()
        runIn(w, "window.__posted = []; window.webkit = { messageHandlers: { webview: { postMessage: function (s) { window.__posted.push(s); } } } };")
        val listed = origins ?: setOf(evalIn(w, "self.origin"))
        val relay = scripts.relay(listed, mainFrame = "true", input = input, hints = hints)
        val shim = scripts.shim(listed, mainFrame = "true")
        if (shimFirst) runIn(w, shim)
        runIn(w, relay)
        if (!shimFirst) runIn(w, shim)
        return w
    }

    private fun posted(w: JsAny): List<String> = (0 until postedCount(w)).map { postedAt(w, it) }

    private fun parsed(w: JsAny): List<RelayMessage> = posted(w).mapNotNull { RelayMessage.parse(it) }

    @Test fun theRelayStartsTheDocumentAndForwardsTheShimsReady() = runTest {
        val w = frame()
        val messages = parsed(w)
        val start = messages.first()
        assertTrue(start is RelayMessage.Start, "$messages")
        val ready = messages.filterIsInstance<RelayMessage.Page>().first()
        assertEquals(start.document, ready.document)
        assertTrue(ready.json.startsWith("{\"t\":\"event\",\"n\":\"\$ready\""), ready.json)
    }

    @Test fun theShimsReadyWaitsForARelayThatStartsAfterIt() = runTest {
        val w = frame(shimFirst = true)
        val messages = parsed(w)
        val start = messages.first()
        assertTrue(start is RelayMessage.Start, "$messages")
        val ready = messages.filterIsInstance<RelayMessage.Page>().first()
        assertEquals(start.document, ready.document)
        assertTrue(ready.json.startsWith("{\"t\":\"event\",\"n\":\"\$ready\""), ready.json)
    }

    @Test fun aDocumentOfAnUnlistedOriginGetsNoShimButStillStarts() = runTest {
        val w = frame(origins = setOf("https://other.example"))
        assertEquals("undefined", evalIn(w, "typeof hostBridge"))
        assertTrue(parsed(w).none { it is RelayMessage.Page })
        assertTrue(parsed(w).first() is RelayMessage.Start)
    }

    @Test fun deliveryReachesTheShimOnlyOnTheTargetOrigin() = runTest {
        val w = frame()
        val origin = evalIn(w, "self.origin")
        val hello = "{\"t\":\"event\",\"n\":\"\$hello\"}"
        val before = parsed(w).count { it is RelayMessage.Page }
        assertFalse(deliverIn(w, scripts.deliveryBody, hello, "https://other.example", scripts.inEvent))
        assertEquals(before, parsed(w).count { it is RelayMessage.Page })
        assertTrue(deliverIn(w, scripts.deliveryBody, hello, origin, scripts.inEvent))
        // The shim answers a $hello with its $ready, through the relay.
        assertEquals(before + 1, parsed(w).count { it is RelayMessage.Page })
    }

    @Test fun aPageThatOnlyKnowsTheApiCanUseIt() = runTest {
        val w = frame()
        assertEquals("true", evalIn(w, "hostBridge.post('clicked', {n: 1})"))
        val last = parsed(w).filterIsInstance<RelayMessage.Page>().last()
        assertTrue(last.json.startsWith("{\"t\":\"event\",\"n\":\"clicked\""), last.json)
    }

    @Test fun eachDocumentPaintsAfterTwoFrames() = runTest {
        val w = frame()
        wait(200).await<JsAny?>()
        val start = parsed(w).first()
        assertTrue(parsed(w).contains(RelayMessage.Paint(start.document)), "${posted(w)}")
    }

    /** A frame whose document is still loading (opened, not closed), with the fake handler and the relay in it. */
    private fun loadingFrame(): JsAny {
        val w = newFrame()
        runIn(w, "document.open();")
        runIn(w, "window.__posted = []; window.webkit = { messageHandlers: { webview: { postMessage: function (s) { window.__posted.push(s); } } } };")
        runIn(w, scripts.relay(setOf(evalIn(w, "self.origin")), mainFrame = "true"))
        return w
    }

    @Test fun aDocumentStillLoadingWithNothingPaintedSaysNothingUntilItLoads() = runTest {
        val w = loadingFrame()
        assertEquals("loading", evalIn(w, "document.readyState"))
        wait(300).await<JsAny?>()
        assertTrue(parsed(w).none { it is RelayMessage.Paint }, "${posted(w)}")
        runIn(w, "document.close();")
        wait(300).await<JsAny?>()
        val start = parsed(w).first()
        assertTrue(parsed(w).contains(RelayMessage.Paint(start.document)), "${posted(w)}")
    }

    @Test fun aDocumentThatPaintsContentSaysSoBeforeItHasLoaded() = runTest {
        val w = loadingFrame()
        runIn(w, "document.write('<p style=\"font:20px sans-serif\">Quarterly numbers</p>');")
        wait(500).await<JsAny?>()
        assertEquals("loading", evalIn(w, "document.readyState"))
        val start = parsed(w).first()
        assertTrue(parsed(w).contains(RelayMessage.Paint(start.document)), "its first contentful paint: ${posted(w)}")
        runIn(w, "document.close();")
    }

    /** Only WebKit restores a page from the back-forward cache (a trusted pageshow): a page that says so re-keys nothing. */
    @Test fun aPagesOwnPageshowStartsNoNewDocument() = runTest {
        val w = frame()
        runIn(w, "window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));")
        assertEquals(1, parsed(w).count { it is RelayMessage.Start }, "${posted(w)}")
    }

    @Test fun titleAndSameDocumentUrlChangesAreHintedWhenAsked() = runTest {
        val w = frame(hints = true)
        runIn(w, "document.title = 'Changed'; location.hash = 'moved';")
        wait(600).await<JsAny?>()
        val kinds = parsed(w)
        assertTrue(kinds.any { it is RelayMessage.Title }, "${posted(w)}")
        assertTrue(kinds.any { it is RelayMessage.Url }, "${posted(w)}")
    }

    /** Engines that observe titles and URLs themselves (macOS, Linux) get no hints, and no timer looking for them. */
    @Test fun noHintsUnlessAsked() = runTest {
        val w = frame()
        runIn(w, "document.title = 'Changed'; location.hash = 'moved';")
        wait(600).await<JsAny?>()
        assertTrue(parsed(w).none { it is RelayMessage.Title || it is RelayMessage.Url }, "${posted(w)}")
    }

    /**
     * A page that posts in a loop is capped in the relay, before the app's main thread: at most a burst of
     * [WebKitScripts.PAGE_MESSAGE_BURST], then [WebKitScripts.PAGE_MESSAGES_PER_SECOND]. Its `$ready` always passes.
     */
    @Test fun aFloodingPageIsCappedButItsReadyAlwaysPasses() = runTest {
        val w = frame()
        val before = parsed(w).count { it is RelayMessage.Page }
        runIn(w, "for (var i = 0; i < 5000; i++) hostBridge.post('flood', i);")
        val flooded = parsed(w).count { it is RelayMessage.Page } - before
        assertTrue(flooded in WebKitScripts.PAGE_MESSAGE_BURST..WebKitScripts.PAGE_MESSAGE_BURST + 50, "$flooded of 5000 passed")
        // Its budget spent, the page announces itself again (asked by the host's $hello): that passes.
        val origin = evalIn(w, "self.origin")
        assertTrue(deliverIn(w, scripts.deliveryBody, "{\"t\":\"event\",\"n\":\"\$hello\"}", origin, scripts.inEvent))
        val ready = parsed(w).filterIsInstance<RelayMessage.Page>().last()
        assertTrue(ready.json.startsWith("{\"t\":\"event\",\"n\":\"\$ready\""), ready.json)
        // And the budget comes back with time.
        wait(500).await<JsAny?>()
        val later = parsed(w).count { it is RelayMessage.Page }
        runIn(w, "for (var i = 0; i < 5000; i++) hostBridge.post('later', i);")
        val refilled = parsed(w).count { it is RelayMessage.Page } - later
        assertTrue(refilled in 1..WebKitScripts.PAGE_MESSAGES_PER_SECOND, "$refilled passed half a second later")
    }

    private fun readies(w: JsAny): Int = parsed(w).filterIsInstance<RelayMessage.Page>().count { it.json.startsWith(WebBridge.READY_PREFIX) }

    /**
     * A page that asks itself to announce in a loop (calling the shim's `__hostBridgeReceive` with a `$hello`) mints no
     * `$ready` past the relay: a document's passes once, for its own announcement, and once more for each `$hello` the
     * host delivers (final review, minor). Each one is work on the app's main thread.
     */
    @Test fun aPageMintsNoReadyTheHostDidNotAskFor() = runTest {
        val w = frame()
        assertEquals(1, readies(w), "the document's own announcement")
        runIn(w, "for (var i = 0; i < 100; i++) window.__hostBridgeReceive(${jsString(WebBridge.HELLO)});")
        assertEquals(1, readies(w), "the page's own hellos")
        val origin = evalIn(w, "self.origin")
        assertTrue(deliverIn(w, scripts.deliveryBody, WebBridge.HELLO, origin, scripts.inEvent))
        assertEquals(2, readies(w), "the host's hello is answered")
        runIn(w, "for (var i = 0; i < 100; i++) window.__hostBridgeReceive(${jsString(WebBridge.HELLO)});")
        assertEquals(2, readies(w))
    }

    /** The relay hears the host's `$hello` before the shim answers it, whichever world's scripts WebKit ran first. */
    @Test fun theHostsHelloIsAnsweredWhenTheShimStartedFirst() = runTest {
        val w = frame(shimFirst = true)
        assertEquals(1, readies(w))
        assertTrue(deliverIn(w, scripts.deliveryBody, WebBridge.HELLO, evalIn(w, "self.origin"), scripts.inEvent))
        assertEquals(2, readies(w))
    }

    private fun jsString(text: String): String = kotlinx.serialization.json.JsonPrimitive(text).toString()

    /** Only the user makes trusted events: a page that taps, clicks, types or scrolls for itself is never the user. */
    @Test fun aPagesOwnPointersClicksKeysAndScrollsAreNotTheUsers() = runTest {
        val w = frame(input = true)
        runIn(
            w,
            "var b = document.body; b.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));" +
                "b.dispatchEvent(new PointerEvent('pointerup', { bubbles: true })); b.click();" +
                "b.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'a' }));" +
                "window.dispatchEvent(new Event('scroll')); b.dispatchEvent(new WheelEvent('wheel', { bubbles: true }));" +
                "b.style.height = '3000px'; scrollTo(0, 500);",
        )
        wait(300).await<JsAny?>()
        assertTrue(parsed(w).none { it is RelayMessage.Input }, "${posted(w)}")
    }

    @Test fun theFramesInputLeavesTheMainFrameToTheRelay() = runTest {
        val w = frame()
        runIn(w, scripts.frameInput(mainFrame = "true"))
        val before = posted(w).size
        runIn(w, "document.body.click();")
        assertEquals(before, posted(w).size)
    }

    /** Focus and blur the page makes up itself are not the view's: only the user's (trusted) ones are reported. */
    @Test fun aPagesOwnFocusAndBlurAreNotReported() = runTest {
        val w = frame()
        runIn(w, "window.dispatchEvent(new FocusEvent('focus')); window.dispatchEvent(new FocusEvent('blur'));")
        assertTrue(parsed(w).none { it is RelayMessage.Focus }, "${posted(w)}")
    }
}
