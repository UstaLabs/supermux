package dev.supermux.webview.bridge

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun runIn(w: JsAny, code: String): Unit = js("{ w.eval(code); }")
private fun evalIn(w: JsAny, code: String): String = js("String(w.eval(code))")
private fun sentCount(w: JsAny): Int = js("w.__sent.length")
private fun sentAt(w: JsAny, index: Int): String = js("w.__sent[index]")

/**
 * The shim's click proof (spec "Click-to-send", open decision 4 option c), run by real Chrome with real input: a
 * `hostBridge.callAfterClick` carries `c: true` only within 1 s of a trusted click on a `button` or
 * `input[type=button|submit]` of that document, once per click, while the engine reports transient activation. The cases
 * are the eval harness's (evals/show-html/click.test.ts), here against the library's shim.
 *
 * Chrome runs the shim in the page's world, as Android, Windows and the web engine do: what defends it there is that it
 * captured every accessor it uses before any page script ran. WebKit's relay makes its own verdict in its own world
 * (WebKitRelayBrowserTest).
 */
class ClickProofBrowserTest {
    @AfterTest fun clear() = removePinnedFrames()

    /** A document whose first script is the shim, as engines inject it at document start, then [body]. */
    private fun card(body: String, left: Int = 0): JsAny {
        val w = pinnedFrame(left = left)
        val shim = BridgeShim.source("(function (a) { return function (s) { a[a.length] = s; }; })(window.__sent = [])", mainFrameExpression = "true")
        check("</script" !in shim)
        val page = "<!doctype html><html><head><script>$shim</script></head><body style=\"margin:0\">$body</body></html>"
        runIn(w, "document.open(); document.write(${JsonPrimitive(page)}); document.close();")
        return w
    }

    /** Every `callAfterClick` the document sent, as (its text, the shim's verdict). */
    private fun verdicts(w: JsAny): List<Pair<String?, Boolean?>> = (0 until sentCount(w))
        .mapNotNull { BridgeCodec.decode(sentAt(w, it)) as? BridgeMessage.Call }
        .map { (it.params as? JsonPrimitive)?.contentOrNull to it.clicked }

    private fun verdictOf(w: JsAny, text: String): List<Boolean?> = verdicts(w).filter { it.first == text }.map { it.second }

    @Test fun aRealClickOnAButtonCountsOnce() = runTest {
        val w = card("""<button id="b" style="width:120px;height:40px" onclick="hostBridge.callAfterClick('send', 'real')">Use</button>""")
        trustedClick(w, "#b")
        assertEquals(listOf<Boolean?>(true), verdictOf(w, "real"))
    }

    @Test fun refusedOnLoadFromATimerFromAScriptClickAfterTheWindowAndASecondSendFromOneClick() = runTest {
        val w = card(
            """
            <button id="real" onclick="hostBridge.callAfterClick('send', 'real')">real</button>
            <button id="twice" onclick="hostBridge.callAfterClick('send', 'first'); hostBridge.callAfterClick('send', 'second')">twice</button>
            <button id="late" onclick="setTimeout(function () { hostBridge.callAfterClick('send', 'late') }, 1300)">late</button>
            <script>
              hostBridge.callAfterClick('send', 'on load');
              setTimeout(function () { hostBridge.callAfterClick('send', 'timer') }, 150);
              setTimeout(function () { document.getElementById('real').click() }, 250);
              setTimeout(function () { document.getElementById('real').dispatchEvent(new MouseEvent('click', { bubbles: true })) }, 300);
            </script>
            """.trimIndent(),
        )
        waitMillis(500).await<JsAny?>()
        trustedClick(w, "#twice")
        waitMillis(150).await<JsAny?>()
        trustedClick(w, "#late")
        waitMillis(1600).await<JsAny?>()
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "on load"))
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "timer"))
        assertEquals(listOf<Boolean?>(false, false), verdictOf(w, "real"), "el.click() and dispatchEvent")
        assertEquals(listOf<Boolean?>(true), verdictOf(w, "first"))
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "second"), "one click, one send")
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "late"), "1.3 s after the click")
    }

    @Test fun aClickInOneCardAllowsNoSendFromAnother() = runTest {
        val a = card("""<button id="b" style="width:120px;height:40px">just a button</button>""")
        val b = card("""<script>setInterval(function () { hostBridge.callAfterClick('send', 'from b') }, 100)</script>""", left = 400)
        trustedClick(a, "#b")
        waitMillis(400).await<JsAny?>()
        val fromB = verdictOf(b, "from b")
        assertTrue(fromB.isNotEmpty())
        assertTrue(fromB.all { it == false }, "$fromB")
    }

    @Test fun refusedAClickNotOnAButtonAndAKeyThatIsNotAClickButEnterOrSpaceOnAButtonCounts() = runTest {
        val w = card(
            """
            <div id="swatch" style="cursor:pointer;padding:20px" onclick="hostBridge.callAfterClick('send', 'swatch')">a swatch</div>
            <div id="role" role="button" style="padding:10px" onclick="hostBridge.callAfterClick('send', 'role')">role button</div>
            <input id="field" value="x">
            <button id="b" onclick="hostBridge.callAfterClick('send', 'button')">Use</button>
            <input id="s" type="submit" value="Go" onclick="hostBridge.callAfterClick('send', 'submit'); return false">
            <script>document.getElementById('field').addEventListener('keydown', function () { hostBridge.callAfterClick('send', 'typed') });</script>
            """.trimIndent(),
        )
        trustedClick(w, "#swatch")
        trustedClick(w, "#role")
        trustedClick(w, "#field")
        trustedKey("Enter")
        waitMillis(1100).await<JsAny?>()
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "swatch"))
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "role"), "[role=button] is not a button")
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "typed"))
        // Enter and Space on a focused button are clicks the browser makes: accessible, and allowed.
        runIn(w, "document.getElementById('b').focus()")
        trustedKey("Enter")
        assertEquals(listOf<Boolean?>(true), verdictOf(w, "button"))
        runIn(w, "document.getElementById('s').focus()")
        trustedKey("Space")
        assertEquals(listOf<Boolean?>(true), verdictOf(w, "submit"))
    }

    @Test fun aPageThatRedefinesEveryAccessorStillCannotFakeAClick() = runTest {
        val w = card(
            """
            <div id="plain" style="padding:20px" onclick="hostBridge.callAfterClick('send', 'plain'); hostBridge.call('send', 'call')">not a button</div>
            <button id="b" onclick="hostBridge.callAfterClick('send', 'real')">Use</button>
            <script>
              var fake = document.getElementById('b'), own = Object.getOwnPropertyDescriptor;
              Object.defineProperty(Event.prototype, 'target', { configurable: true, get: function () { return fake; } });
              Object.defineProperty(Event.prototype, 'srcElement', { configurable: true, get: function () { return fake; } });
              Object.defineProperty(Node.prototype, 'nodeType', { configurable: true, get: function () { return 1; } });
              Element.prototype.closest = function () { return fake; };
              Element.prototype.matches = function () { return true; };
              if (window.UserActivation) Object.defineProperty(UserActivation.prototype, 'isActive', { configurable: true, get: function () { return true; } });
              var realApply = Reflect.apply;
              Reflect.apply = function (f, t, a) { return f === Element.prototype.closest ? fake : realApply(f, t, a); };
              Date.now = function () { return 0; };
              performance.now = function () { return 0; };
              // A serializer that rewrites what the shim sends, to mark a call as clicked.
              Object.defineProperty(Object.prototype, 'toJSON', { configurable: true, writable: true, value: function () {
                var copy = {};
                for (var k in this) if (k !== 'toJSON') copy[k] = this[k];
                if (copy.t === 'call') copy.c = true;
                return copy;
              } });
              Object.defineProperty(Object.prototype, 'c', { configurable: true, get: function () { return true; }, set: function () {} });
              hostBridge.callAfterClick('send', 'patched on load');
            </script>
            """.trimIndent(),
        )
        trustedClick(w, "#plain")
        waitMillis(100).await<JsAny?>()
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "patched on load"))
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "plain"), "a faked target, closest and activation made no difference")
        assertEquals(listOf<Boolean?>(null), verdictOf(w, "call"), "a plain call stays a plain call")
        trustedClick(w, "#b")
        assertEquals(listOf<Boolean?>(true), verdictOf(w, "real"))
    }

    @Test fun aClickWhoseActivationThePageSpentIsRefused() = runTest {
        val w = card(
            """<button id="b" style="width:120px;height:40px" onclick="var p = window.open('about:blank'); if (p) p.close(); window.__active = String(navigator.userActivation.isActive); hostBridge.callAfterClick('send', 'spent')">Use</button>""",
        )
        trustedClick(w, "#b")
        assertEquals("false", evalIn(w, "window.__active"), "opening a window spends the click's activation")
        assertEquals(listOf<Boolean?>(false), verdictOf(w, "spent"))
    }

    @Test fun theVerdictIsOnlyEverOnCallAfterClick() = runTest {
        val w = card("""<button id="b" style="width:120px;height:40px" onclick="hostBridge.call('send', 'plain'); hostBridge.callAfterClick('send', 'after')">Use</button>""")
        trustedClick(w, "#b")
        assertEquals(listOf<Boolean?>(null), verdictOf(w, "plain"))
        assertEquals(listOf<Boolean?>(true), verdictOf(w, "after"), "a plain call takes no click")
        runIn(w, "window.__r = 'pending'; hostBridge.callAfterClick('\$x', 1).then(null, function () { window.__r = 'rejected'; });")
        waitMillis(0).await<JsAny?>()
        assertEquals("rejected", evalIn(w, "window.__r"), "names starting with \$ are reserved")
    }
}
