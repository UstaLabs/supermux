package dev.supermux.webview.bridge

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Click-to-send at the host (spec open decision 4, option c): [WebBridge.handleAfterButtonClick] gets the verdict the
 * page's shim (or, on WebKit, the relay in its own world) put on a `hostBridge.callAfterClick`: whether a trusted click
 * on a button in that document came within the last second, unused, with the engine's transient activation. The host
 * input stamp plays no part, so a screen reader's activation, which delivers no input to the view, counts. Which clicks
 * make a true verdict is the shim's tests (ClickProofBrowserTest).
 */
class AfterClickCallTest {
    private val origin = "https://card.invalid"

    private class View(val bridge: WebBridge, val sent: MutableList<String>) {
        val nonce = "n1"
        val seen = mutableListOf<Boolean>()

        fun call(id: Long, clicked: Boolean?, method: String = "send", from: String = nonce, origin: String = "https://card.invalid") =
            bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Call(id, method, JsonPrimitive("hi"), clicked), from = from))

        fun answer(id: Long): JsonElement? =
            sent.map { BridgeCodec.decode(it) }.filterIsInstance<BridgeMessage.Reply>().firstOrNull { it.id == id }?.result
    }

    /** A view whose host input stamp says "the user just pressed" at every call: the click verdict must not care. */
    private fun TestScope.view(stampSaysInput: Boolean = false): View {
        val sent = mutableListOf<String>()
        val bridge = WebBridge({ _, _ -> }, BridgeLimits()) { 0L }
        bridge.connect(sink = { text, _ -> sent += text }, originAllowed = { it == origin }, scope = backgroundScope, userInput = { stampSaysInput })
        val view = View(bridge, sent)
        bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }), from = "n1"))
        bridge.handleAfterButtonClick("send") { _, clicked ->
            view.seen += clicked
            JsonPrimitive(clicked)
        }
        return view
    }

    @Test fun theShimsVerdictReachesTheHandler() = runTest {
        val v = view()
        v.call(1, clicked = true)
        v.call(2, clicked = false)
        runCurrent()
        assertEquals(listOf(true, false), v.seen)
        assertEquals(JsonPrimitive(true), v.answer(1))
        assertEquals(JsonPrimitive(false), v.answer(2))
    }

    /** A screen reader's activation: a trusted click on the button, and no input delivered to the view. */
    @Test fun aClickWithNoHostInputStampStillCounts() = runTest {
        val v = view(stampSaysInput = false)
        v.call(1, clicked = true)
        runCurrent()
        assertEquals(listOf(true), v.seen)
    }

    @Test fun theHostInputStampDoesNotMakeAClick() = runTest {
        val v = view(stampSaysInput = true)
        v.call(1, clicked = false)
        runCurrent()
        assertEquals(listOf(false), v.seen)
    }

    @Test fun aPlainCallToTheMethodIsNoClick() = runTest {
        val v = view(stampSaysInput = true)
        v.call(1, clicked = null)
        runCurrent()
        assertEquals(listOf(false), v.seen)
    }

    @Test fun aCallFromAnotherDocumentIsDroppedUnheard() = runTest {
        val v = view()
        v.call(1, clicked = true, from = "someone-else")
        v.call(2, clicked = true, origin = "https://other.invalid")
        runCurrent()
        assertEquals(emptyList(), v.seen)
        assertNull(v.answer(1))
    }

    @Test fun eachRegistrationReplacesTheOthers() = runTest {
        val v = view()
        v.bridge.handleWithUserInput("send") { _, _ -> JsonPrimitive("input") }
        v.call(1, clicked = true)
        v.bridge.handleAfterButtonClick("send") { _, clicked -> JsonPrimitive("click $clicked") }
        v.call(2, clicked = true)
        val off = v.bridge.handleAfterButtonClick("send") { _, _ -> JsonPrimitive("last") }
        off()
        v.call(3, clicked = true)
        runCurrent()
        assertEquals(JsonPrimitive("input"), v.answer(1))
        assertEquals(JsonPrimitive("click true"), v.answer(2))
        assertNull(v.answer(3))
        val missing = v.sent.map { BridgeCodec.decode(it) }.filterIsInstance<BridgeMessage.Reply>().single { it.id == 3L }
        assertEquals("no such method: send", missing.error)
    }

    @Test fun theWireCarriesTheVerdictAsCAndOnlyJsonTrueIsAClick() {
        val clicked = BridgeCodec.encode(BridgeMessage.Call(1, "send", JsonPrimitive("x"), clicked = true), from = "n")
        assertEquals("{\"t\":\"call\",\"id\":1,\"m\":\"send\",\"p\":\"x\",\"c\":true,\"from\":\"n\"}", clicked)
        assertEquals(true, (BridgeCodec.decode(clicked) as BridgeMessage.Call).clicked)
        val plain = BridgeCodec.encode(BridgeMessage.Call(1, "send", JsonPrimitive("x")), from = "n")
        assertEquals(null, (BridgeCodec.decode(plain) as BridgeMessage.Call).clicked)
        for (c in listOf("false", "\"true\"", "1", "null", "{}")) {
            val forged = "{\"t\":\"call\",\"id\":1,\"m\":\"send\",\"p\":null,\"c\":$c,\"from\":\"n\"}"
            assertEquals(false, (BridgeCodec.decode(forged) as BridgeMessage.Call).clicked, c)
        }
    }
}
