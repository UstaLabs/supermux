package dev.supermux.webview.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebBridgeTest {
    private val origin = "https://p.invalid"

    private class Connected(val bridge: WebBridge, val sent: MutableList<String>, val reserved: MutableList<Pair<String, JsonElement>>)

    private fun connected(scope: CoroutineScope): Connected {
        val sent = mutableListOf<String>()
        val reserved = mutableListOf<Pair<String, JsonElement>>()
        val bridge = WebBridge { name, data -> reserved += name to data }
        bridge.connect(sink = { sent += it }, originAllowed = { it == origin }, scope = scope)
        return Connected(bridge, sent, reserved)
    }

    private fun Connected.lastCall() = BridgeCodec.decode(sent.last()) as BridgeMessage.Call

    @Test fun aCallResolvesWithThePagesReply() = runTest {
        val c = connected(this)
        val result = async { c.bridge.call("add", buildJsonArray { add(1); add(2) }) }
        runCurrent()
        val call = c.lastCall()
        assertEquals("add", call.method)
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Reply(call.id, JsonPrimitive(3))))
        assertEquals(JsonPrimitive(3), result.await())
    }

    @Test fun aPageErrorFailsTheCall() = runTest {
        val c = connected(this)
        val result = async { runCatching { c.bridge.call("x") } }
        runCurrent()
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Reply(c.lastCall().id, error = "nope")))
        assertEquals("nope", result.await().exceptionOrNull()?.message)
    }

    @Test fun aCallTimesOut() = runTest {
        val c = connected(this)
        val e = assertFailsWith<BridgeException> { c.bridge.call("slow", timeoutMillis = 50) }
        assertTrue("timed out" in e.message.orEmpty())
    }

    @Test fun callingWhileDisconnectedFailsFast() = runTest {
        assertFailsWith<BridgeException> { WebBridge { _, _ -> }.call("x") }
    }

    @Test fun disconnectingFailsPendingCalls() = runTest {
        val c = connected(this)
        val result = async { runCatching { c.bridge.call("x") } }
        runCurrent()
        c.bridge.disconnect()
        assertTrue(result.await().exceptionOrNull() is BridgeException)
    }

    @Test fun messagesFromOtherOriginsAreDroppedAndCounted() = runTest {
        val c = connected(this)
        val seen = mutableListOf<JsonElement>()
        c.bridge.on("ping") { seen += it }
        c.bridge.receive("https://evil.test", BridgeCodec.encode(BridgeMessage.Event("ping")))
        assertTrue(seen.isEmpty())
        assertEquals(1, c.bridge.droppedMessages)
    }

    @Test fun malformedMessagesAndUnknownRepliesAreDroppedAndCounted() = runTest {
        val c = connected(this)
        c.bridge.receive(origin, "{not json")
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Reply(999, JsonPrimitive(1))))
        assertEquals(2, c.bridge.droppedMessages)
    }

    @Test fun pageEventsReachListenersUntilUnsubscribed() = runTest {
        val c = connected(this)
        val seen = mutableListOf<JsonElement>()
        val off = c.bridge.on("tick") { seen += it }
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("tick", JsonPrimitive(1))))
        off()
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("tick", JsonPrimitive(2))))
        assertEquals(listOf<JsonElement>(JsonPrimitive(1)), seen)
    }

    @Test fun reservedEventsGoToTheRuntimeNotToListeners() = runTest {
        val c = connected(this)
        val seen = mutableListOf<JsonElement>()
        c.bridge.on("\$console") { seen += it }
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("\$console", JsonPrimitive("hi"))))
        assertEquals(listOf<Pair<String, JsonElement>>("\$console" to JsonPrimitive("hi")), c.reserved)
        assertTrue(seen.isEmpty())
    }

    @Test fun postSendsAnEventToThePage() = runTest {
        val c = connected(this)
        c.bridge.post("theme", JsonPrimitive("dark"))
        assertEquals(BridgeMessage.Event("theme", JsonPrimitive("dark")), BridgeCodec.decode(c.sent.single()))
    }

    @Test fun thePageCanCallHandlers() = runTest {
        val c = connected(this)
        c.bridge.handle("double") { JsonPrimitive((it as JsonPrimitive).int * 2) }
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Call(4, "double", JsonPrimitive(21))))
        runCurrent()
        assertEquals(BridgeMessage.Reply(4, JsonPrimitive(42)), BridgeCodec.decode(c.sent.single()))
    }

    @Test fun anUnknownMethodRepliesWithAnError() = runTest {
        val c = connected(this)
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Call(5, "missing")))
        runCurrent()
        assertEquals(BridgeMessage.Reply(5, error = "no such method: missing"), BridgeCodec.decode(c.sent.single()))
    }

    @Test fun aThrowingHandlerRepliesWithItsMessage() = runTest {
        val c = connected(this)
        c.bridge.handle("bad") { throw IllegalStateException("broken") }
        c.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Call(6, "bad")))
        runCurrent()
        assertEquals(BridgeMessage.Reply(6, error = "broken"), BridgeCodec.decode(c.sent.single()))
    }
}
