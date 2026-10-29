package dev.supermux.webview.engine.webkit

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    private fun frame(origins: Set<String>? = null, shimFirst: Boolean = false): JsAny {
        val w = newFrame()
        runIn(w, "window.__posted = []; window.webkit = { messageHandlers: { webview: { postMessage: function (s) { window.__posted.push(s); } } } };")
        val listed = origins ?: setOf(evalIn(w, "self.origin"))
        val relay = scripts.relay(listed, mainFrame = "true")
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

    @Test fun aBackForwardCacheRestoreStartsANewToken() = runTest {
        val w = frame()
        val first = parsed(w).first().document
        runIn(w, "window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));")
        val starts = parsed(w).filterIsInstance<RelayMessage.Start>()
        assertEquals(2, starts.size)
        assertNotEquals(first, starts.last().document)
    }

    @Test fun titleAndSameDocumentUrlChangesAreHinted() = runTest {
        val w = frame()
        runIn(w, "document.title = 'Changed'; location.hash = 'moved';")
        wait(600).await<JsAny?>()
        val kinds = parsed(w)
        assertTrue(kinds.any { it is RelayMessage.Title }, "${posted(w)}")
        assertTrue(kinds.any { it is RelayMessage.Url }, "${posted(w)}")
    }

    @Test fun focusAndBlurAreReported() = runTest {
        val w = frame()
        runIn(w, "window.dispatchEvent(new FocusEvent('focus')); window.dispatchEvent(new FocusEvent('blur'));")
        val focus = parsed(w).filterIsInstance<RelayMessage.Focus>()
        assertEquals(listOf(true, false), focus.map { it.focused })
    }
}
