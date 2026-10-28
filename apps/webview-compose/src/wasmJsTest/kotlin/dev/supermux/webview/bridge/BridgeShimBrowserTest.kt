package dev.supermux.webview.bridge

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun newFrame(width: Int, height: Int): JsAny =
    js("(function () { var f = document.createElement('iframe'); f.style.width = width + 'px'; f.style.height = height + 'px'; f.style.border = '0'; document.body.appendChild(f); return f.contentWindow; })()")
private fun runIn(w: JsAny, code: String): Unit = js("{ w.eval(code); }")
private fun evalIn(w: JsAny, code: String): String = js("String(w.eval(code))")
private fun sentCount(w: JsAny): Int = js("w.__sent.length")
private fun sentAt(w: JsAny, index: Int): String = js("w.__sent[index]")
private fun pageCall(w: JsAny, method: String, value: Int): Promise<JsAny?> = js("w.hostBridge.call(method, value)")
private fun deliver(w: JsAny, json: String): Unit = js("{ w.__hostBridgeReceive(json); }")
private fun asInt(value: JsAny?): Int = js("Number(value)")
private fun outcome(promise: Promise<JsAny?>): Promise<JsAny?> =
    js("promise.then(function () { return 'resolved'; }, function (e) { return 'rejected: ' + String(e && e.message || e); })")
private fun wait(millis: Int): Promise<JsAny?> = js("new Promise(function (resolve) { setTimeout(resolve, millis); })")
private fun jsString(value: JsAny?): String = js("String(value)")

class BridgeShimBrowserTest {
    private fun frame(width: Int = 300, height: Int = 200, document: String? = null): JsAny {
        val w = newFrame(width, height)
        if (document != null) runIn(w, "document.open(); document.write(${jsonString(document)}); document.close();")
        runIn(w, "window.__sent = [];")
        runIn(w, BridgeShim.source("function (s) { var a = window.__sent; a[a.length] = s; }", mainFrameExpression = "true"))
        return w
    }

    private fun jsonString(text: String) = JsonPrimitive(text).toString()

    private fun sent(w: JsAny): List<BridgeMessage> = (0 until sentCount(w)).mapNotNull { BridgeCodec.decode(sentAt(w, it)) }

    private fun events(w: JsAny, name: String) = sent(w).filterIsInstance<BridgeMessage.Event>().filter { it.name == name }

    private fun replies(w: JsAny) = sent(w).filterIsInstance<BridgeMessage.Reply>()

    private fun consoleTexts(w: JsAny) = events(w, "\$console").map { it.data.jsonObject["text"]!!.jsonPrimitive.content }

    @Test fun eachDocumentAnnouncesItselfFirstWithItsOwnNonce() = runTest {
        val a = frame()
        val b = frame()
        val readyA = sent(a).first() as BridgeMessage.Event
        val readyB = sent(b).first() as BridgeMessage.Event
        assertEquals("\$ready", readyA.name)
        assertNotEquals(readyA.data.jsonObject["nonce"], readyB.data.jsonObject["nonce"])
    }

    @Test fun aPageCallBecomesACallMessageAndResolvesWithTheReply() = runTest {
        val w = frame()
        val promise = pageCall(w, "add", 5)
        val call = sent(w).filterIsInstance<BridgeMessage.Call>().single()
        assertEquals("add", call.method)
        assertEquals(JsonPrimitive(5), call.params)
        deliver(w, BridgeCodec.encode(BridgeMessage.Reply(call.id, JsonPrimitive(7))))
        assertEquals(7, asInt(promise.await<JsAny?>()))
    }

    @Test fun anErrorReplyRejectsThePagesPromise() = runTest {
        val w = frame()
        val promise = pageCall(w, "x", 1)
        val call = sent(w).filterIsInstance<BridgeMessage.Call>().single()
        deliver(w, BridgeCodec.encode(BridgeMessage.Reply(call.id, error = "nope")))
        assertEquals("rejected: nope", jsString(outcome(promise).await<JsAny?>()))
    }

    @Test fun aHostCallReachesAPageHandlerAndRepliesWithItsResult() = runTest {
        val w = frame()
        runIn(w, "hostBridge.handle('double', function (x) { return x * 2; })")
        deliver(w, BridgeCodec.encode(BridgeMessage.Call(9, "double", JsonPrimitive(21))))
        wait(0).await<JsAny?>()
        assertEquals(BridgeMessage.Reply(9, JsonPrimitive(42)), replies(w).single())
    }

    @Test fun unknownAndPrototypeNamedMethodsAreNotFound() = runTest {
        val w = frame()
        deliver(w, BridgeCodec.encode(BridgeMessage.Call(3, "nope")))
        deliver(w, BridgeCodec.encode(BridgeMessage.Call(4, "toString")))
        deliver(w, BridgeCodec.encode(BridgeMessage.Call(5, "constructor")))
        wait(0).await<JsAny?>()
        assertEquals(3, replies(w).count { it.error.orEmpty().startsWith("no such method") })
    }

    @Test fun reservedNamesCannotBeUsedFromThePage() = runTest {
        val w = frame()
        assertEquals("refused", evalIn(w, "(function () { try { hostBridge.post('\$size', {w: 1, h: 1}); return 'posted'; } catch (e) { return 'refused'; } })()"))
        assertEquals("refused", evalIn(w, "(function () { try { hostBridge.handle('\$saveState', function () {}); return 'set'; } catch (e) { return 'refused'; } })()"))
        runIn(w, "window.__outcome = 'pending'; hostBridge.call('\$x', 1).then(function () { window.__outcome = 'resolved'; }, function () { window.__outcome = 'rejected'; });")
        wait(0).await<JsAny?>()
        assertEquals("rejected", evalIn(w, "window.__outcome"))
        assertTrue(events(w, "\$size").none { it.data.jsonObject["w"]?.jsonPrimitive?.content == "1" })
    }

    @Test fun postAndOnWorkAndUnsubscribingStopsDelivery() = runTest {
        val w = frame()
        runIn(w, "window.__pings = 0; window.__off = hostBridge.on('ping', function () { window.__pings++; });")
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("ping")))
        runIn(w, "window.__off();")
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("ping")))
        assertEquals("1", evalIn(w, "window.__pings"))
        runIn(w, "hostBridge.post('pong', 2)")
        assertEquals(JsonPrimitive(2), events(w, "pong").single().data)
    }

    @Test fun aCallThatCannotBeSentRejectsInsteadOfHanging() = runTest {
        val w = frame()
        runIn(w, "window.__a = 'pending'; var c = {}; c.self = c; hostBridge.call('x', c).then(null, function () { window.__a = 'rejected'; });")
        runIn(w, "window.__b = 'pending'; hostBridge.call('x', 'z'.repeat(300000)).then(null, function () { window.__b = 'rejected'; });")
        wait(0).await<JsAny?>()
        assertEquals("rejected", evalIn(w, "window.__a"))
        assertEquals("rejected", evalIn(w, "window.__b"))
    }

    @Test fun consoleOutputIsForwardedReadably() = runTest {
        val w = frame()
        runIn(w, "console.log('hello', {a: 1}); console.log(undefined, NaN); console.error(new Error('boom'));")
        val texts = consoleTexts(w)
        assertEquals("hello {\"a\":1}", texts[0])
        assertEquals("undefined NaN", texts[1])
        assertTrue("boom" in texts[2], texts[2])
    }

    @Test fun consoleTextIsCappedAndResizeObserverNoiseDropped() = runTest {
        val w = frame()
        runIn(w, "console.log('x'.repeat(20000)); console.error('ResizeObserver loop completed with undelivered notifications.');")
        val texts = consoleTexts(w)
        assertEquals(1, texts.size)
        assertEquals(BridgeShim.CONSOLE_TEXT_MAX + 1, texts.single().length)
    }

    @Test fun uncaughtErrorsAndRejectionsAreForwarded() = runTest {
        val w = frame()
        runIn(w, "setTimeout(function () { throw new Error('kaput'); }); Promise.reject(new Error('refused'));")
        wait(50).await<JsAny?>()
        val uncaught = events(w, "\$console").filter { it.data.jsonObject["uncaught"]?.jsonPrimitive?.content == "true" }
            .map { it.data.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(uncaught.any { "kaput" in it }, uncaught.toString())
        assertTrue(uncaught.any { "refused" in it }, uncaught.toString())
    }

    @Test fun theDocumentsHeightAndViewportAreReported() = runTest {
        val w = frame(document = "<!doctype html><html><body style=\"margin:0\"><div style=\"height:500px\"></div></body></html>")
        wait(100).await<JsAny?>()
        val size = events(w, "\$size").last().data as JsonObject
        assertEquals(500, size["h"]!!.jsonPrimitive.int)
        assertEquals(200, size["vh"]!!.jsonPrimitive.int)
        assertEquals(300, size["w"]!!.jsonPrimitive.int)
    }

    @Test fun absolutelyPositionedContentCounts() = runTest {
        val w = frame(document = "<!doctype html><html><body style=\"margin:0\"><div style=\"position:absolute;top:0;height:800px;width:10px\"></div><p style=\"margin:0;height:50px\">x</p></body></html>")
        wait(100).await<JsAny?>()
        assertEquals(800, (events(w, "\$size").last().data as JsonObject)["h"]!!.jsonPrimitive.int)
    }

    @Test fun aZeroSizedFrameReportsNoSize() = runTest {
        val w = frame(width = 0, height = 0, document = "<!doctype html><html><body><div style=\"height:500px\"></div></body></html>")
        wait(100).await<JsAny?>()
        assertTrue(events(w, "\$size").isEmpty())
    }

    @Test fun restoredStateReachesAListenerThatRegistersLate() = runTest {
        val w = frame()
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$restoreState", JsonPrimitive(42))))
        runIn(w, "window.__restored = 'none'; hostBridge.onRestoreState(function (s) { window.__restored = s; });")
        wait(0).await<JsAny?>()
        assertEquals("42", evalIn(w, "window.__restored"))
    }

    @Test fun aSaveProviderAnnouncesItselfAndAnswersSaveState() = runTest {
        val w = frame()
        runIn(w, "hostBridge.onSaveState(function () { return {n: 1}; })")
        assertEquals(1, events(w, "\$saveStateProvider").size)
        deliver(w, BridgeCodec.encode(BridgeMessage.Call(11, "\$saveState")))
        wait(0).await<JsAny?>()
        assertEquals("1", replies(w).single().result!!.jsonObject["n"]!!.jsonPrimitive.content)
    }

    @Test fun thePageCannotReplaceTheBridgeOrItsReceiver() = runTest {
        val w = frame()
        runIn(w, "try { window.hostBridge = null; } catch (e) {} try { window.__hostBridgeReceive = null; } catch (e) {}")
        runIn(w, "window.__got = 0; hostBridge.on('still', function () { window.__got++; });")
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("still")))
        assertEquals("1", evalIn(w, "window.__got"))
    }

    @Test fun patchedArrayMethodsDoNotBreakTheBridge() = runTest {
        val w = frame()
        runIn(w, "window.__got = 0; hostBridge.on('still', function () { window.__got++; });")
        runIn(w, "Array.prototype.map = function () { throw new Error('patched'); }; Array.prototype.forEach = function () { throw new Error('patched'); }; Array.prototype.slice = function () { throw new Error('patched'); };")
        runIn(w, "console.log('still logging')")
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("still")))
        assertTrue("still logging" in consoleTexts(w))
        assertEquals("1", evalIn(w, "window.__got"))
    }
}
