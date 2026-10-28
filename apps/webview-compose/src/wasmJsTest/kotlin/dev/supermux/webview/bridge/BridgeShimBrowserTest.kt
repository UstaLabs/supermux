package dev.supermux.webview.bridge

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun evalScript(code: String): Unit = js("{ (0, eval)(code); }")
private fun resetSent(): Unit = js("{ window.__sent = []; }")
private fun sentCount(): Int = js("window.__sent.length")
private fun sentAt(index: Int): String = js("window.__sent[index]")
private fun pageCall(method: String, value: Int): Promise<JsAny?> = js("window.hostBridge.call(method, value)")
private fun deliver(json: String): Unit = js("{ window.__hostBridgeReceive(json); }")
private fun asInt(value: JsAny?): Int = js("Number(value)")
private fun nextTask(): Promise<JsAny?> = js("new Promise(function (resolve) { setTimeout(resolve, 0); })")

class BridgeShimBrowserTest {
    private fun install() {
        resetSent()
        evalScript(BridgeShim.source("function (s) { window.__sent.push(s); }"))
        resetSent()
    }

    private fun sent(): List<BridgeMessage> = (0 until sentCount()).mapNotNull { BridgeCodec.decode(sentAt(it)) }

    @Test fun aPageCallBecomesACallMessageAndResolvesWithTheReply() = runTest {
        install()
        val promise = pageCall("add", 5)
        val call = sent().single() as BridgeMessage.Call
        assertEquals("add", call.method)
        assertEquals(JsonPrimitive(5), call.params)
        deliver(BridgeCodec.encode(BridgeMessage.Reply(call.id, JsonPrimitive(7))))
        assertEquals(7, asInt(promise.await()))
    }

    @Test fun aHostCallReachesAPageHandlerAndRepliesWithItsResult() = runTest {
        install()
        evalScript("window.hostBridge.handle('double', function (x) { return x * 2; })")
        deliver(BridgeCodec.encode(BridgeMessage.Call(9, "double", JsonPrimitive(21))))
        nextTask().await<JsAny?>()
        assertEquals(BridgeMessage.Reply(9, JsonPrimitive(42)), sent().filterIsInstance<BridgeMessage.Reply>().last())
    }

    @Test fun anUnknownHostCallRepliesWithAnError() = runTest {
        install()
        deliver(BridgeCodec.encode(BridgeMessage.Call(3, "nope")))
        nextTask().await<JsAny?>()
        val reply = sent().filterIsInstance<BridgeMessage.Reply>().last()
        assertEquals(3L, reply.id)
        assertTrue(reply.error.orEmpty().contains("no such method"))
    }

    @Test fun consoleOutputIsForwardedAsAReservedEvent() = runTest {
        install()
        evalScript("console.log('hello', {a: 1})")
        val event = sent().filterIsInstance<BridgeMessage.Event>().last { it.name == "\$console" }
        assertEquals("log", event.data.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals("hello {\"a\":1}", event.data.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun registeringASaveProviderAnnouncesItAndAnswersSaveState() = runTest {
        install()
        evalScript("window.hostBridge.onSaveState(function () { return {n: 1}; })")
        assertTrue(sent().any { it is BridgeMessage.Event && it.name == "\$saveStateProvider" })
        deliver(BridgeCodec.encode(BridgeMessage.Call(11, "\$saveState")))
        nextTask().await<JsAny?>()
        val reply = sent().filterIsInstance<BridgeMessage.Reply>().last()
        assertEquals("1", reply.result!!.jsonObject["n"]!!.jsonPrimitive.content)
    }

    @Test fun thePageCannotReplaceTheBridge() = runTest {
        install()
        evalScript("try { window.hostBridge = null; } catch (e) {}")
        evalScript("window.hostBridge.post('still', 1)")
        assertTrue(sent().any { it is BridgeMessage.Event && it.name == "still" })
    }
}
