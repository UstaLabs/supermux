package dev.supermux.webview.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebBridgeTest {
    private val origin = "https://p.invalid"

    private class Harness(
        val bridge: WebBridge,
        val sent: MutableList<String>,
        val targets: MutableList<String>,
        val reserved: MutableList<Pair<String, JsonElement>>,
        val time: LongArray,
    )

    private fun TestScope.harness(
        limits: BridgeLimits = BridgeLimits(),
        ready: Boolean = true,
        allowed: Set<String> = setOf(origin),
        onReserved: (String, JsonElement) -> Unit = { _, _ -> },
    ): Harness {
        val sent = mutableListOf<String>()
        val targets = mutableListOf<String>()
        val reserved = mutableListOf<Pair<String, JsonElement>>()
        val time = longArrayOf(0)
        val bridge = WebBridge({ name, data -> reserved += name to data; onReserved(name, data) }, limits) { time[0] }
        // backgroundScope, not `this`: connect() parents a SupervisorJob that never completes on its own to the scope,
        // and runTest waits for its scope's children. It would fail every test that doesn't disconnect() by hand.
        bridge.connect(sink = { text, target -> sent += text; targets += target }, originAllowed = { it in allowed }, scope = backgroundScope)
        val harness = Harness(bridge, sent, targets, reserved, time)
        if (ready) harness.ready("n1")
        return harness
    }

    private fun Harness.page(message: BridgeMessage, from: String = origin) = bridge.receive(from, BridgeCodec.encode(message))

    private fun Harness.ready(nonce: String, from: String = origin) =
        page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", nonce) }), from)

    private fun Harness.lastCall() = BridgeCodec.decode(sent.last()) as BridgeMessage.Call

    private fun Harness.decoded() = sent.map { BridgeCodec.decode(it) }

    private fun Harness.replies() = decoded().filterIsInstance<BridgeMessage.Reply>()

    @Test fun aCallResolvesWithThePagesReply() = runTest {
        val h = harness()
        val result = async { h.bridge.call("add", buildJsonArray { add(1); add(2) }) }
        runCurrent()
        val call = h.lastCall()
        assertEquals("add", call.method)
        h.page(BridgeMessage.Reply(call.id, JsonPrimitive(3)))
        assertEquals(JsonPrimitive(3), result.await())
    }

    @Test fun aPageErrorFailsTheCall() = runTest {
        val h = harness()
        val result = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        h.page(BridgeMessage.Reply(h.lastCall().id, error = "nope"))
        assertEquals("nope", result.await().exceptionOrNull()?.message)
    }

    @Test fun aCallTimesOutWithABridgeTimeoutException() = runTest {
        val h = harness()
        val e = assertFailsWith<BridgeTimeoutException> { h.bridge.call("slow", timeoutMillis = 50) }
        assertTrue("timed out" in e.message.orEmpty())
    }

    @Test fun anEnclosingTimeoutIsNotReportedAsTheCallsOwn() = runTest {
        val h = harness()
        assertNull(withTimeoutOrNull(100) { h.bridge.call("slow", timeoutMillis = 10_000) })
    }

    @Test fun callingWhileDisconnectedFailsFast() = runTest {
        assertFailsWith<BridgeException> { WebBridge({ _, _ -> }).call("x") }
    }

    @Test fun disconnectingFailsPendingCalls() = runTest {
        val h = harness()
        val result = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        h.bridge.disconnect()
        assertEquals("bridge disconnected", result.await().exceptionOrNull()?.message)
    }

    @Test fun callsWaitForTheDocumentToAnnounceItself() = runTest {
        val h = harness(ready = false)
        val result = async { h.bridge.call("x") }
        runCurrent()
        assertTrue(h.sent.isEmpty())
        h.ready("n1")
        runCurrent()
        h.page(BridgeMessage.Reply(h.lastCall().id, JsonPrimitive("ok")))
        assertEquals(JsonPrimitive("ok"), result.await())
    }

    @Test fun postsBeforeTheDocumentIsReadyAreQueuedThenFlushed() = runTest {
        val h = harness(ready = false)
        assertTrue(h.bridge.post("theme", JsonPrimitive("dark")))
        assertTrue(h.sent.isEmpty())
        h.ready("n1")
        assertEquals(listOf<BridgeMessage?>(BridgeMessage.Event("theme", JsonPrimitive("dark"))), h.decoded())
    }

    @Test fun theQueueKeepsOnlyTheNewestPosts() = runTest {
        val h = harness(limits = BridgeLimits(maxQueuedPosts = 2), ready = false)
        listOf("a", "b", "c").forEach { h.bridge.post(it) }
        h.ready("n1")
        assertEquals(listOf("b", "c"), h.decoded().map { (it as BridgeMessage.Event).name })
        assertEquals(1L, h.bridge.droppedMessages)
    }

    @Test fun aNewDocumentFailsTheOldDocumentsPendingCalls() = runTest {
        val h = harness()
        val result = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        h.ready("n2")
        assertEquals("the page navigated away", result.await().exceptionOrNull()?.message)
    }

    @Test fun repliesComputedForAnOldDocumentNeverReachANewOne() = runTest {
        val h = harness()
        val release = CompletableDeferred<Unit>()
        h.bridge.handle("slow") {
            release.await()
            JsonPrimitive("old")
        }
        h.page(BridgeMessage.Call(1, "slow"))
        runCurrent()
        h.ready("n2")
        release.complete(Unit)
        runCurrent()
        assertTrue(h.replies().isEmpty())
    }

    @Test fun aReadyFromAnUntrustedOriginStopsTrafficToThePage() = runTest {
        val h = harness()
        h.ready("evil-doc", from = "https://evil.test")
        assertFalse(h.bridge.isReady)
        h.bridge.post("secret", JsonPrimitive(1))
        assertTrue(h.decoded().none { it is BridgeMessage.Event && it.name == "secret" })
    }

    @Test fun messagesFromOtherOriginsAreDroppedAndCounted() = runTest {
        val h = harness()
        val seen = mutableListOf<JsonElement>()
        h.bridge.on("ping") { seen += it }
        h.bridge.handle("x") { JsonPrimitive(1) }
        val pending = async { runCatching { h.bridge.call("y", timeoutMillis = 1_000) } }
        runCurrent()
        val id = h.lastCall().id
        val evil = "https://evil.test"
        h.page(BridgeMessage.Event("ping"), from = evil)
        h.page(BridgeMessage.Call(7, "x"), from = evil)
        h.page(BridgeMessage.Reply(id, JsonPrimitive("forged")), from = evil)
        runCurrent()
        assertTrue(seen.isEmpty())
        assertEquals(3L, h.bridge.droppedMessages)
        assertTrue(h.replies().isEmpty())
        assertTrue(pending.await().exceptionOrNull() is BridgeTimeoutException)
    }

    @Test fun malformedDeepAndOrphanMessagesAreDroppedWithoutThrowing() = runTest {
        val h = harness()
        h.bridge.receive(origin, "{not json")
        h.bridge.receive(origin, """{"t":"event","n":"x","d":${"[".repeat(100_000)}${"]".repeat(100_000)}}""")
        h.bridge.receive(origin, """{"t":"event","n":"x","d":alert(1)}""")
        h.page(BridgeMessage.Reply(999, JsonPrimitive(1)))
        assertEquals(4L, h.bridge.droppedMessages)
    }

    @Test fun aFloodIsRateLimited() = runTest {
        val h = harness(limits = BridgeLimits(messagesPerSecond = 1, messageBurst = 4))
        val seen = mutableListOf<JsonElement>()
        h.bridge.on("tick") { seen += it }
        repeat(5) { h.page(BridgeMessage.Event("tick", JsonPrimitive(it))) }
        assertEquals(4, seen.size)
        assertEquals(1L, h.bridge.droppedMessages)
        h.time[0] += 1_000
        h.page(BridgeMessage.Event("tick", JsonPrimitive(9)))
        assertEquals(5, seen.size)
    }

    @Test fun pageEventsReachListenersUntilUnsubscribed() = runTest {
        val h = harness()
        val seen = mutableListOf<JsonElement>()
        val off = h.bridge.on("tick") { seen += it }
        h.page(BridgeMessage.Event("tick", JsonPrimitive(1)))
        off()
        h.page(BridgeMessage.Event("tick", JsonPrimitive(2)))
        assertEquals(listOf<JsonElement>(JsonPrimitive(1)), seen)
    }

    @Test fun reservedEventsGoToTheRuntimeNotToListeners() = runTest {
        val h = harness()
        val seen = mutableListOf<JsonElement>()
        h.bridge.on("\$console") { seen += it }
        h.page(BridgeMessage.Event("\$console", JsonPrimitive("hi")))
        assertEquals(listOf<Pair<String, JsonElement>>("\$console" to JsonPrimitive("hi")), h.reserved)
        assertTrue(seen.isEmpty())
    }

    @Test fun unknownReservedEventsAndAThrowingRuntimeAreContained() = runTest {
        val h = harness(onReserved = { _, _ -> throw IllegalStateException("runtime bug") })
        h.page(BridgeMessage.Event("\$unknown"))
        h.page(BridgeMessage.Event("\$size", buildJsonObject { put("w", 1); put("h", 1) }))
        assertEquals(2L, h.bridge.droppedMessages)
    }

    @Test fun postSendsAnEventAndRefusesDataThatIsNotJson() = runTest {
        val h = harness()
        assertTrue(h.bridge.post("theme", JsonPrimitive("dark")))
        assertEquals(BridgeMessage.Event("theme", JsonPrimitive("dark")), BridgeCodec.decode(h.sent.single()))
        assertFalse(h.bridge.post("bad", JsonPrimitive(Double.NaN)))
        assertFalse(WebBridge({ _, _ -> }).post("x"))
    }

    @Test fun thePageCanCallHandlersUntilTheyAreRemoved() = runTest {
        val h = harness()
        val remove = h.bridge.handle("double") { JsonPrimitive((it as JsonPrimitive).int * 2) }
        h.page(BridgeMessage.Call(4, "double", JsonPrimitive(21)))
        runCurrent()
        assertEquals(BridgeMessage.Reply(4, JsonPrimitive(42)), BridgeCodec.decode(h.sent.last()))
        remove()
        h.page(BridgeMessage.Call(5, "double", JsonPrimitive(1)))
        runCurrent()
        assertEquals(BridgeMessage.Reply(5, error = "no such method: double"), BridgeCodec.decode(h.sent.last()))
    }

    @Test fun handlerFailuresReplyWithoutLeakingHostDetails() = runTest {
        val h = harness()
        h.bridge.handle("leaky") { throw IllegalStateException("/home/user/.secrets/token.json") }
        h.bridge.handle("explained") { throw BridgeException("try again later") }
        h.page(BridgeMessage.Call(6, "leaky"))
        h.page(BridgeMessage.Call(7, "explained"))
        runCurrent()
        val replies = h.replies().associateBy { it.id }
        assertEquals("handler failed", replies[6]?.error)
        assertEquals("try again later", replies[7]?.error)
    }

    @Test fun aHandlersOwnTimeoutStillGetsAReply() = runTest {
        val h = harness()
        h.bridge.handle("inner") { withTimeout(10) { awaitCancellation() } }
        h.page(BridgeMessage.Call(8, "inner"))
        advanceTimeBy(20)
        runCurrent()
        assertEquals("cancelled", h.replies().single().error)
    }

    @Test fun aSlowHandlerIsCutOff() = runTest {
        val h = harness(limits = BridgeLimits(handlerTimeoutMillis = 100))
        h.bridge.handle("forever") { awaitCancellation() }
        h.page(BridgeMessage.Call(9, "forever"))
        advanceTimeBy(150)
        runCurrent()
        assertEquals("timeout", h.replies().single().error)
    }

    @Test fun pageCallsBeyondTheInFlightLimitAreAnsweredBusy() = runTest {
        val h = harness(limits = BridgeLimits(maxPageCallsInFlight = 2))
        h.bridge.handle("wait") { awaitCancellation() }
        repeat(3) { h.page(BridgeMessage.Call(it.toLong(), "wait")) }
        runCurrent()
        assertEquals(BridgeMessage.Reply(2, error = "busy"), h.replies().single())
        h.bridge.disconnect()
    }

    @Test fun paramsThatAreNotJsonFailTheCall() = runTest {
        val h = harness()
        assertFailsWith<BridgeException> { h.bridge.call("x", JsonPrimitive(Double.NaN)) }
    }

    @Test fun connectingAgainFailsTheOldConnectionsCalls() = runTest {
        val h = harness()
        val result = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        h.bridge.connect(sink = { _, _ -> }, originAllowed = { true }, scope = this)
        assertEquals("bridge disconnected", result.await().exceptionOrNull()?.message)
        h.bridge.disconnect()
    }

    @Test fun aCallNeverReachesADocumentThatTurnedUntrustedWhileItWaited() = runTest {
        val h = harness(ready = false)
        val result = async { runCatching { h.bridge.call("x", timeoutMillis = 5_000) } }
        runCurrent()
        h.ready("n1")
        h.ready("evil", from = "https://evil.test")
        runCurrent()
        assertTrue(h.decoded().none { it is BridgeMessage.Call })
        h.ready("n3")
        runCurrent()
        assertEquals(1, h.decoded().count { it is BridgeMessage.Call })
        h.page(BridgeMessage.Reply(h.lastCall().id, JsonPrimitive("ok")))
        assertEquals(JsonPrimitive("ok"), result.await().getOrNull())
    }

    @Test fun aCallIssuedForOneConnectionNeverMigratesToTheNext() = runTest {
        val h = harness(ready = false)
        val result = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        val second = mutableListOf<String>()
        h.bridge.connect(sink = { text, _ -> second += text }, originAllowed = { true }, scope = backgroundScope)
        h.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "b1") })))
        runCurrent()
        assertEquals("bridge disconnected", result.await().exceptionOrNull()?.message)
        assertTrue(second.none { BridgeCodec.decode(it) is BridgeMessage.Call })
    }

    @Test fun aCallReleasedJustBeforeTheConnectionChangedDoesNotMigrate() = runTest {
        val h = harness(ready = false)
        val result = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        h.ready("n1")
        val second = mutableListOf<String>()
        h.bridge.connect(sink = { text, _ -> second += text }, originAllowed = { true }, scope = backgroundScope)
        h.bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "b1") })))
        runCurrent()
        assertEquals("bridge disconnected", result.await().exceptionOrNull()?.message)
        assertTrue(h.decoded().none { it is BridgeMessage.Call })
        assertTrue(second.none { BridgeCodec.decode(it) is BridgeMessage.Call })
    }

    @Test fun aCommittedNavigationToAnotherOriginClosesTheGate() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://other.test/")
        assertFalse(h.bridge.isReady)
        h.bridge.post("secret")
        assertTrue(h.decoded().none { it is BridgeMessage.Event && it.name == "secret" })
    }

    @Test fun aSameOriginNavigationKeepsTheGateOpen() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://p.invalid/page2#x")
        assertTrue(h.bridge.isReady)
    }

    @Test fun aReadyIsNotStarvedByAFlood() = runTest {
        val h = harness(limits = BridgeLimits(messagesPerSecond = 1, messageBurst = 2, charsPerSecond = 1, charBurst = 100), ready = false)
        repeat(10) { h.page(BridgeMessage.Event("noise", JsonPrimitive("x".repeat(50)))) }
        h.ready("n1")
        assertTrue(h.bridge.isReady)
    }

    @Test fun largeMessagesAreChargedByTheirSize() = runTest {
        val h = harness(limits = BridgeLimits(charsPerSecond = 1, charBurst = 30_000))
        val seen = mutableListOf<JsonElement>()
        h.bridge.on("big") { seen += it }
        repeat(3) { h.page(BridgeMessage.Event("big", JsonPrimitive("x".repeat(10_000)))) }
        assertEquals(2, seen.size)
    }

    @Test fun eventsAndCallsAreCappedSmallerThanReplies() = runTest {
        val h = harness()
        val seen = mutableListOf<JsonElement>()
        h.bridge.on("big") { seen += it }
        h.page(BridgeMessage.Event("big", JsonPrimitive("x".repeat(70_000))))
        assertTrue(seen.isEmpty())
        val result = async { h.bridge.call("state") }
        runCurrent()
        h.page(BridgeMessage.Reply(h.lastCall().id, JsonPrimitive("y".repeat(70_000))))
        assertEquals(70_000, (result.await() as JsonPrimitive).content.length)
    }

    @Test fun postsQueuedBeforeReadyGoOutBeforeAWaitingCall() = runTest {
        // Unconfined, so a call resumed by ready.complete() would send at once, inside onReady. A flush that ran after
        // it would show up as the wrong order.
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val sent = mutableListOf<String>()
        val bridge = WebBridge({ _, _ -> })
        bridge.connect(
            sink = { text, _ -> sent += text },
            originAllowed = { it == origin },
            scope = CoroutineScope(backgroundScope.coroutineContext + unconfined),
        )
        bridge.post("first")
        val result = async(unconfined) { runCatching { bridge.call("second", timeoutMillis = 1_000) } }
        bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") })))
        assertEquals(listOf("event", "call"), sent.map { if (BridgeCodec.decode(it) is BridgeMessage.Event) "event" else "call" })
        result.await()
    }

    @Test fun disconnectCancelsRunningHandlers() = runTest {
        val h = harness()
        var cancelled = false
        h.bridge.handle("wait") {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        h.page(BridgeMessage.Call(1, "wait"))
        runCurrent()
        h.bridge.disconnect()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test fun handlerErrorsReachTheErrorHookAndTodoStillGetsAReply() = runTest {
        val h = harness()
        val errors = mutableListOf<Throwable>()
        h.bridge.onError = { errors += it }
        h.bridge.handle("broken") { throw IllegalStateException("bug") }
        h.bridge.handle("unfinished") { TODO() }
        h.page(BridgeMessage.Call(1, "broken"))
        h.page(BridgeMessage.Call(2, "unfinished"))
        runCurrent()
        assertEquals(listOf("handler failed", "handler failed"), h.replies().map { it.error })
        assertEquals(2, errors.size)
    }

    private fun Harness.names() = decoded().map { (it as BridgeMessage.Event).name }

    @Test fun everyMessageNamesTheOriginItIsFor() = runTest {
        val h = harness()
        h.bridge.post("hi")
        h.bridge.handle("echo") { it }
        h.page(BridgeMessage.Call(1, "echo", JsonPrimitive(2)))
        runCurrent()
        val result = async { h.bridge.call("x") }
        runCurrent()
        h.page(BridgeMessage.Reply(h.lastCall().id, JsonPrimitive(1)))
        result.await()
        assertEquals(listOf(origin, origin, origin), h.targets)
    }

    @Test fun queuedPostsGoToTheDocumentThatAnnounces() = runTest {
        val q = "https://q.invalid"
        val h = harness(ready = false, allowed = setOf(origin, q))
        h.bridge.post("hi")
        h.ready("n1", from = q)
        assertEquals(listOf(q), h.targets)
    }

    @Test fun aStaleCommitAfterReadyRecoversWhenThePageSpeaks() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://old.test/")
        assertFalse(h.bridge.isReady)
        h.page(BridgeMessage.Event("tick"))
        assertEquals(listOf("\$hello"), h.names())
        assertEquals(listOf(origin), h.targets)
        h.ready("n1")
        assertTrue(h.bridge.isReady)
    }

    @Test fun aStaleCommitAfterReadyRecoversWhenTheHostCalls() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://old.test/")
        val result = async { h.bridge.call("x") }
        runCurrent()
        assertEquals(listOf("\$hello"), h.names())
        assertEquals(listOf(origin), h.targets)
        h.ready("n1")
        runCurrent()
        h.page(BridgeMessage.Reply(h.lastCall().id, JsonPrimitive(7)))
        assertEquals(JsonPrimitive(7), result.await())
    }

    @Test fun aStaleCommitAfterReadyRecoversWhenTheHostPosts() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://old.test/")
        h.bridge.post("hi")
        assertEquals(listOf("\$hello"), h.names())
        h.ready("n1")
        assertEquals(listOf("\$hello", "hi"), h.names())
    }

    @Test fun nothingAsksToAnnounceBeforeAnyDocumentHas() = runTest {
        val h = harness(ready = false)
        h.bridge.post("hi")
        assertTrue(h.sent.isEmpty())
    }

    @Test fun helloIsRateLimited() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://old.test/")
        repeat(10) { h.page(BridgeMessage.Event("tick")) }
        assertEquals(2, h.names().count { it == "\$hello" })
        h.time[0] = 500
        h.page(BridgeMessage.Event("tick"))
        assertEquals(3, h.names().count { it == "\$hello" })
    }

    @Test fun anUntrustedPageSpeakingGetsNoHello() = runTest {
        val h = harness(ready = false)
        h.page(BridgeMessage.Event("tick"), from = "https://evil.test")
        assertTrue(h.sent.isEmpty())
    }

    @Test fun aCommitToAnotherOriginRefillsTheReadyBudget() = runTest {
        val q = "https://q.invalid"
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false, allowed = setOf(origin, q))
        h.ready("n1")
        assertTrue(h.bridge.isReady)
        h.bridge.documentCommitted("$q/")
        h.ready("n2", from = q)
        assertTrue(h.bridge.isReady)
    }

    @Test fun aSameOriginCommitDoesNotRefillTheReadyBudget() = runTest {
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false)
        h.ready("n1")
        h.bridge.documentCommitted("$origin/next")
        h.ready("n2")
        assertEquals(1, h.bridge.droppedMessages)
    }

    @Test fun blobCommitsKeepTheirCreatorsOriginAndOpaqueOnesCloseTheGate() = runTest {
        val h = harness()
        h.bridge.documentCommitted("blob:$origin/123")
        assertTrue(h.bridge.isReady)
        h.bridge.documentCommitted("about:blank")
        assertFalse(h.bridge.isReady)
    }

    @Test fun handlerErrorsOfAnyKindGetAReply() = runTest {
        val h = harness()
        val errors = mutableListOf<Throwable>()
        h.bridge.onError = { errors += it }
        h.bridge.handle("assert") { throw AssertionError("bug") }
        h.page(BridgeMessage.Call(1, "assert"))
        runCurrent()
        assertEquals("handler failed", h.replies().single().error)
        assertTrue(errors.single() is AssertionError)
    }
}
