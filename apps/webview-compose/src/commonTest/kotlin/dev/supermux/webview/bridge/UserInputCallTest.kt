package dev.supermux.webview.bridge

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The click-to-send cases of the spec, at the bridge: [WebBridge.handleWithUserInput] against a view's [InputStamp], the
 * way an engine feeds it. What an engine must NOT mark (a script's `click()`, a timer) is the engines' tests.
 */
class UserInputCallTest {
    private val origin = "https://card.invalid"

    private class View(val bridge: WebBridge, val stamp: InputStamp, val clock: LongArray, val sent: MutableList<String>) {
        val nonce = "n1"
        val seen = mutableListOf<Boolean>()

        fun page(message: BridgeMessage, from: String) = bridge.receive(from, BridgeCodec.encode(message, from = nonce))

        fun call(id: Long, origin: String, method: String = "send") =
            page(BridgeMessage.Call(id, method, JsonPrimitive("hi")), origin)

        fun replies() = sent.map { BridgeCodec.decode(it) }.filterIsInstance<BridgeMessage.Reply>()
    }

    private fun TestScope.view(origin: String, stamped: Boolean = true): View {
        val clock = longArrayOf(0)
        val stamp = InputStamp()
        val sent = mutableListOf<String>()
        val bridge = WebBridge({ _, _ -> }, BridgeLimits()) { clock[0] }
        bridge.connect(
            sink = { text, _ -> sent += text },
            originAllowed = { it == origin },
            scope = backgroundScope,
            userInput = if (stamped) ({ stamp.consume(clock[0]) }) else ({ false }),
        )
        val view = View(bridge, stamp, clock, sent)
        view.page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }), origin)
        bridge.handleWithUserInput("send") { _, fromUser ->
            view.seen += fromUser
            JsonPrimitive(fromUser)
        }
        return view
    }

    private fun View.answer(id: Long): JsonElement? = replies().firstOrNull { it.id == id }?.result

    @Test fun aCallAfterInputCountsAsTheUsers() = runTest {
        val v = view(origin)
        v.stamp.mark(v.clock[0])
        v.clock[0] += 50
        v.call(1, origin)
        runCurrent()
        assertEquals(listOf(true), v.seen)
        assertEquals(JsonPrimitive(true), v.answer(1))
    }

    @Test fun aCallOnLoadWithNoInputDoesNot() = runTest {
        val v = view(origin)
        v.call(1, origin)
        runCurrent()
        assertEquals(listOf(false), v.seen)
        assertEquals(JsonPrimitive(false), v.answer(1))
    }

    @Test fun aCallFromATimerLongAfterTheInputDoesNot() = runTest {
        val v = view(origin)
        v.stamp.mark(0)
        v.clock[0] = 1_001
        v.call(1, origin)
        runCurrent()
        assertEquals(listOf(false), v.seen)
    }

    @Test fun aSecondCallFromOneInputDoesNot() = runTest {
        val v = view(origin)
        v.stamp.mark(0)
        v.call(1, origin)
        v.call(2, origin)
        runCurrent()
        assertEquals(listOf(true, false), v.seen)
    }

    @Test fun aDifferentCardsInputDoesNotCount() = runTest {
        val a = view(origin)
        val b = view(origin)
        a.stamp.mark(0)
        b.call(1, origin)
        runCurrent()
        assertEquals(listOf(false), b.seen)
        a.call(1, origin)
        runCurrent()
        assertEquals(listOf(true), a.seen)
    }

    @Test fun anEngineThatCantTellOfInputNeverAllowsOne() = runTest {
        val v = view(origin, stamped = false)
        v.stamp.mark(0)
        v.call(1, origin)
        runCurrent()
        assertEquals(listOf(false), v.seen)
    }

    @Test fun theDecisionIsMadeWhenTheCallArrivesNotWhenTheHandlerRuns() = runTest {
        val v = view(origin)
        v.stamp.mark(0)
        v.call(1, origin)
        // The handler hasn't run yet; the window passes, and another input arrives, before it does.
        v.clock[0] = 5_000
        v.stamp.mark(5_000)
        runCurrent()
        assertEquals(listOf(true), v.seen)
        assertTrue(v.stamp.isMarked, "the later input is still unspent")
    }

    @Test fun aCallFromAnotherOriginIsDroppedAndTakesNoInput() = runTest {
        val v = view(origin)
        v.stamp.mark(0)
        v.call(1, "https://other.invalid")
        runCurrent()
        assertEquals(emptyList(), v.seen)
        assertTrue(v.stamp.isMarked)
    }

    @Test fun otherMethodsDoNotTakeTheInput() = runTest {
        val v = view(origin)
        v.bridge.handle("ping") { JsonNull }
        v.stamp.mark(0)
        v.call(1, origin, "ping")
        v.call(2, origin)
        runCurrent()
        assertEquals(listOf(true), v.seen)
    }

    @Test fun registeringAMethodAgainReplacesTheOtherKind() = runTest {
        val v = view(origin)
        v.bridge.handle("send") { JsonPrimitive("plain") }
        v.stamp.mark(0)
        v.call(1, origin)
        runCurrent()
        assertEquals(JsonPrimitive("plain"), v.answer(1))
        assertEquals(emptyList(), v.seen)
        assertTrue(v.stamp.isMarked, "a plain handler doesn't take input")
    }
}
