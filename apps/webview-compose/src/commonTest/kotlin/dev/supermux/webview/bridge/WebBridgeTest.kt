package dev.supermux.webview.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    ) {
        /** The nonce the page's shim signs its messages with: the one it announced last, whether or not it got through. */
        var nonce: String? = null
    }

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

    /** The page, of origin [from], sends [message] signed as [sender]: by default the document it announced last. */
    private fun Harness.page(message: BridgeMessage, from: String = origin, sender: String? = nonce) =
        bridge.receive(from, BridgeCodec.encode(message, from = sender))

    /** A document of origin [from] announces itself as [nonce], and signs what it sends with it from then on. */
    private fun Harness.ready(nonce: String, from: String = origin) {
        this.nonce = nonce
        page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", nonce) }), from)
    }

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

    // From the review of Tasks 6e and 9b.

    @Test fun aCommitWithNoDocumentCurrentRefillsTheReadyBudget() = runTest {
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false)
        h.ready("evil", from = "https://evil.test")
        h.bridge.documentCommitted("$origin/")
        h.ready("n1")
        assertTrue(h.bridge.isReady)
    }

    @Test fun aNewConnectionDoesNotAskThePreviousDocumentToAnnounce() = runTest {
        val h = harness()
        val second = mutableListOf<String>()
        h.bridge.connect(sink = { text, _ -> second += text }, originAllowed = { it == origin }, scope = backgroundScope)
        h.bridge.post("hi")
        assertTrue(second.isEmpty(), "sent: $second")
    }

    @Test fun aBusyReplyNamesItsOrigin() = runTest {
        val h = harness(limits = BridgeLimits(maxPageCallsInFlight = 1))
        h.bridge.handle("wait") { awaitCancellation() }
        repeat(2) { h.page(BridgeMessage.Call(it.toLong(), "wait")) }
        runCurrent()
        assertEquals(listOf(origin), h.targets)
        h.bridge.disconnect()
    }

    /** A caller on another dispatcher; the connection drops while [WebBridge.call] hops to the connection's dispatcher. */
    private fun TestScope.dropWhileHopping(): Pair<MutableList<String>, Deferred<Result<JsonElement>>> {
        val first = mutableListOf<String>()
        val bridge = WebBridge({ _, _ -> })
        val connScope = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))
        bridge.connect(sink = { text, _ -> first += text }, originAllowed = { it == origin }, scope = connScope)
        bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") })))
        bridge.documentCommitted("https://elsewhere.test/")
        first.clear()
        val result = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { bridge.call("x", timeoutMillis = 5_000) } }
        bridge.disconnect()
        runCurrent()
        return first to result
    }

    @Test fun aCallWhoseConnectionDropsWhileHoppingFailsFastAndSendsNothing() = runTest {
        val (first, result) = dropWhileHopping()
        val start = testScheduler.currentTime
        val error = result.await().exceptionOrNull()
        assertTrue(first.isEmpty(), "sent through the dead connection: $first")
        assertEquals("bridge disconnected", error?.message)
        assertEquals(0, testScheduler.currentTime - start)
    }

    // Round 5: messages are addressed per document.

    /** The document [text] is addressed to, read from the raw JSON, or null when it names none. */
    private fun addressOf(text: String): String? = Json.parseToJsonElement(text).jsonObject["to"]?.jsonPrimitive?.content

    @Test fun everyHostMessageIsAddressedToItsDocument() = runTest {
        val h = harness(limits = BridgeLimits(maxPageCallsInFlight = 2))
        h.bridge.post("hi")
        h.bridge.handle("echo") { it }
        h.bridge.handle("wait") { awaitCancellation() }
        h.page(BridgeMessage.Call(1, "echo", JsonPrimitive(2)))
        h.page(BridgeMessage.Call(2, "wait"))
        h.page(BridgeMessage.Call(3, "wait"))
        runCurrent()
        val call = async { runCatching { h.bridge.call("x") } }
        runCurrent()
        val kinds = h.decoded().map {
            when (it) {
                is BridgeMessage.Event -> "post"
                is BridgeMessage.Reply -> if (it.error == "busy") "busy" else "reply"
                is BridgeMessage.Call -> "call"
                null -> "?"
            }
        }
        assertEquals(setOf("post", "reply", "busy", "call"), kinds.toSet())
        assertEquals(List(h.sent.size) { "n1" }, h.sent.map(::addressOf))
        h.bridge.documentCommitted("https://old.test/")
        assertEquals("the page navigated away", call.await().exceptionOrNull()?.message)
        h.bridge.post("again")
        assertEquals(BridgeMessage.Event("\$hello"), BridgeCodec.decode(h.sent.last()))
        assertNull(addressOf(h.sent.last()))
        h.bridge.disconnect()
    }

    @Test fun aReplyGoesToTheDocumentThatCalled() = runTest {
        val h = harness()
        val release = CompletableDeferred<Unit>()
        h.bridge.handle("slow") {
            release.await()
            JsonPrimitive("done")
        }
        h.page(BridgeMessage.Call(1, "slow"))
        runCurrent()
        // The same origin's next document: its shim's ids start at 1 again.
        h.ready("n2")
        h.page(BridgeMessage.Call(1, "slow"))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        // Only n2's call is answered, and the answer is addressed to n2. Nothing is sent for n1's call.
        assertEquals(listOf<String?>("n2"), h.sent.filter { BridgeCodec.decode(it) is BridgeMessage.Reply }.map(::addressOf))
    }

    @Test fun queuedPostsAreAddressedWhenTheyLeave() = runTest {
        val h = harness(ready = false)
        h.bridge.post("a")
        h.bridge.post("b", JsonPrimitive(1))
        h.ready("n2")
        assertEquals(listOf("a", "b"), h.names())
        assertEquals(listOf<String?>("n2", "n2"), h.sent.map(::addressOf))
    }

    @Test fun aPinnedPostNeverGoesElsewhere() = runTest {
        val q = "https://q.invalid"
        // Queued, then a document of another origin announces itself first: dropped and counted.
        val toQ = harness(ready = false, allowed = setOf(origin, q))
        assertTrue(toQ.bridge.postPinned("\$restoreState", JsonPrimitive(1), origin))
        toQ.ready("n1", from = q)
        assertTrue(toQ.bridge.isReady)
        assertTrue(toQ.sent.isEmpty(), "sent: ${toQ.sent}")
        assertEquals(1L, toQ.bridge.droppedMessages)

        // Queued, then a document of its origin announces itself: sent there.
        val toP = harness(ready = false, allowed = setOf(origin, q))
        assertTrue(toP.bridge.postPinned("\$restoreState", JsonPrimitive(1), origin))
        toP.ready("n1")
        assertEquals(listOf<BridgeMessage?>(BridgeMessage.Event("\$restoreState", JsonPrimitive(1))), toP.decoded())
        assertEquals(listOf(origin), toP.targets)

        // Sent at once: refused and counted while a document of another origin is current, sent while one of its own is.
        val now = harness(ready = false, allowed = setOf(origin, q))
        now.ready("n1", from = q)
        assertFalse(now.bridge.postPinned("\$restoreState", JsonPrimitive(1), origin))
        assertTrue(now.sent.isEmpty(), "sent: ${now.sent}")
        assertEquals(1L, now.bridge.droppedMessages)
        assertTrue(now.bridge.postPinned("\$restoreState", JsonPrimitive(1), q))
        assertEquals(listOf(q), now.targets)
    }

    @Test fun theDocumentsOriginIsKnownWhileItIsCurrent() = runTest {
        val h = harness(ready = false)
        assertNull(h.bridge.documentOrigin)
        h.ready("n1")
        assertEquals(origin, h.bridge.documentOrigin)
        h.bridge.documentCommitted("https://other.test/")
        assertNull(h.bridge.documentOrigin)
    }

    @Test fun anOpaqueCommitClosesTheGate() = runTest {
        val h = harness()
        h.bridge.documentCommitted(null)
        assertFalse(h.bridge.isReady)
        h.bridge.post("secret")
        assertTrue(h.decoded().none { it is BridgeMessage.Event && it.name == "secret" })
    }

    @Test fun anOpaqueCommitClosesTheGateEvenOnAnOpaqueDocument() = runTest {
        // Two opaque origins are never the same origin, even when an app allows "null".
        val h = harness(ready = false, allowed = setOf("null"))
        h.ready("n1", from = "null")
        assertTrue(h.bridge.isReady)
        h.bridge.documentCommitted("about:blank")
        assertFalse(h.bridge.isReady)
    }

    @Test fun sameDocumentCommitsDoNotRefillTheReadyBudget() = runTest {
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false)
        h.ready("n1")
        assertTrue(h.bridge.isReady)
        h.bridge.documentCommitted("$origin/#a")
        h.bridge.documentCommitted("$origin/next")
        h.ready("n2")
        assertEquals(1L, h.bridge.droppedMessages)
    }

    @Test fun sameDocumentCommitsDoNotRefillTheReadyBudgetWhileNoDocumentIsCurrent() = runTest {
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false)
        h.bridge.documentCommitted("$origin/")
        h.ready("evil", from = "https://evil.test")
        h.bridge.documentCommitted("$origin/#a")
        h.bridge.documentCommitted("$origin/#b")
        h.ready("n1")
        assertFalse(h.bridge.isReady)
    }

    @Test fun anOpaqueCommitBetweenTwoDocumentsOfOneOriginRefillsTheReadyBudget() = runTest {
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false)
        h.bridge.documentCommitted("$origin/")
        h.ready("n1")
        h.bridge.documentCommitted(null)
        h.bridge.documentCommitted("$origin/")
        h.ready("n2")
        assertTrue(h.bridge.isReady)
        assertEquals(0L, h.bridge.droppedMessages)
    }

    @Test fun theHostAsksTheCommittedAndTheLastAnnouncedOrigin() = runTest {
        val q = "https://q.invalid"
        val h = harness(allowed = setOf(origin, q))
        h.bridge.documentCommitted("$q/")
        assertFalse(h.bridge.isReady)
        h.bridge.post("x")
        assertEquals(listOf("\$hello", "\$hello"), h.names())
        assertEquals(listOf(q, origin), h.targets)
    }

    @Test fun theHostAsksOnlyAllowedOriginsAndEachOnce() = runTest {
        val h = harness()
        h.bridge.documentCommitted("https://untrusted.test/")
        h.bridge.post("x")
        assertEquals(listOf(origin), h.targets)
        h.bridge.documentCommitted("$origin/")
        h.time[0] += 10_000
        h.bridge.post("y")
        assertEquals(listOf(origin, origin), h.targets)
    }

    @Test fun disconnectForgetsWhoAnnounced() = runTest {
        val h = harness()
        h.bridge.documentCommitted("$origin/page")
        h.bridge.disconnect()
        h.bridge.connect(sink = { text, target -> h.sent += text; h.targets += target }, originAllowed = { it == origin }, scope = backgroundScope)
        h.bridge.post("x")
        assertTrue(h.sent.isEmpty(), "sent: ${h.sent}")
    }

    // From the review of Tasks 6f and 9c.

    @Test fun aResultThatIsNotJsonIsAnsweredWithAnErrorAddressedToTheCaller() = runTest {
        val h = harness()
        h.bridge.handle("nan") { JsonPrimitive(Double.NaN) }
        h.page(BridgeMessage.Call(1, "nan"))
        runCurrent()
        val reply = h.sent.single()
        assertEquals(BridgeMessage.Reply(1, error = "result is not strict JSON"), BridgeCodec.decode(reply))
        assertEquals("n1", addressOf(reply))
    }

    @Test fun theHostAsksThePageThatSpoke() = runTest {
        val q = "https://q.invalid"
        val h = harness(allowed = setOf(origin, q))
        h.bridge.documentCommitted("https://elsewhere.test/")
        h.page(BridgeMessage.Event("tick"), from = q)
        assertEquals(listOf(q), h.targets)
    }

    @Test fun aPostThatWouldBeQueuedIsStillValidated() = runTest {
        val h = harness(ready = false)
        assertFalse(h.bridge.post("bad", JsonPrimitive(Double.NaN)))
        h.ready("n1")
        assertTrue(h.sent.isEmpty(), "sent: ${h.sent}")
        assertEquals(0L, h.bridge.droppedMessages)
    }

    // Round 6: pages sign their messages.

    @Test fun aLostReadyAfterASameOriginNavigationRecovers() = runTest {
        val h = harness(limits = BridgeLimits(readiesPerSecond = 1, readyBurst = 1))
        val seen = mutableListOf<JsonElement>()
        h.bridge.on("tick") { seen += it }
        // A same-origin navigation keeps the gate open, and the next document's $ready is lost (here, to the budget).
        h.bridge.documentCommitted("$origin/next")
        h.ready("n2")
        assertTrue(h.bridge.isReady)
        assertEquals(1L, h.bridge.droppedMessages)
        h.time[0] += 1_000
        // The new document speaks. Its message is dropped, and the host asks it to announce itself.
        h.page(BridgeMessage.Event("tick", JsonPrimitive(1)))
        assertTrue(seen.isEmpty())
        assertEquals(2L, h.bridge.droppedMessages)
        assertEquals(listOf("\$hello"), h.names())
        assertEquals(listOf(origin), h.targets)
        assertNull(addressOf(h.sent.single()))
        // It answers with its nonce, and the host talks with it from then on.
        h.ready("n2")
        h.page(BridgeMessage.Event("tick", JsonPrimitive(2)))
        assertEquals(listOf<JsonElement>(JsonPrimitive(2)), seen)
        h.bridge.post("hi")
        val call = async { h.bridge.call("x") }
        runCurrent()
        assertEquals(listOf<String?>("n2", "n2"), h.sent.drop(1).map(::addressOf))
        h.page(BridgeMessage.Reply(h.lastCall().id, JsonPrimitive(7)))
        assertEquals(JsonPrimitive(7), call.await())
    }

    @Test fun staleMessagesFromAReplacedDocumentAreDropped() = runTest {
        val h = harness()
        val seen = mutableListOf<JsonElement>()
        var handled = 0
        h.bridge.on("tick") { seen += it }
        h.bridge.handle("m") { handled++; it }
        h.ready("n2")
        val call = async { h.bridge.call("x") }
        runCurrent()
        val id = h.lastCall().id
        // What the first document sent before it was replaced arrives late. Its call ids restart at 1 like n2's do.
        h.page(BridgeMessage.Event("tick"), sender = "n1")
        h.page(BridgeMessage.Call(1, "m", JsonPrimitive(1)), sender = "n1")
        h.page(BridgeMessage.Reply(id, JsonPrimitive("forged")), sender = "n1")
        runCurrent()
        assertTrue(seen.isEmpty())
        assertEquals(0, handled)
        assertTrue(h.replies().isEmpty(), "sent: ${h.sent}")
        assertEquals(3L, h.bridge.droppedMessages)
        assertFalse(call.isCompleted)
        // Asked to announce itself, the current document answers with the nonce it has: nothing changes.
        h.ready("n2")
        h.page(BridgeMessage.Reply(id, JsonPrimitive("real")))
        assertEquals(JsonPrimitive("real"), call.await())
    }

    @Test fun aPageOfAnotherOriginIsNotAskedWhileADocumentIsCurrent() = runTest {
        // Only a page of the current document's origin can be that document's successor without a commit closing the gate.
        val q = "https://q.invalid"
        val h = harness(allowed = setOf(origin, q))
        h.page(BridgeMessage.Event("tick"), from = q, sender = "m1")
        assertEquals(1L, h.bridge.droppedMessages)
        assertTrue(h.sent.isEmpty(), "sent: ${h.sent}")
        assertTrue(h.bridge.isReady)
    }

    @Test fun messagesWithoutASenderAreDropped() = runTest {
        val h = harness()
        val seen = mutableListOf<JsonElement>()
        var handled = 0
        h.bridge.on("tick") { seen += it }
        h.bridge.handle("m") { handled++; it }
        val call = async { runCatching { h.bridge.call("x", timeoutMillis = 1_000) } }
        runCurrent()
        val id = h.lastCall().id
        h.page(BridgeMessage.Event("tick"), sender = null)
        h.page(BridgeMessage.Call(1, "m"), sender = null)
        h.page(BridgeMessage.Reply(id, JsonPrimitive(1)), sender = null)
        // Only a string names a document, even when a number reads like its nonce (fallback nonces are all digits).
        val digits = harness(ready = false)
        digits.bridge.on("tick") { seen += it }
        digits.ready("7")
        digits.bridge.receive(origin, """{"t":"event","n":"tick","d":1,"from":7}""")
        runCurrent()
        assertTrue(seen.isEmpty())
        assertEquals(0, handled)
        assertTrue(h.replies().isEmpty(), "sent: ${h.sent}")
        assertEquals(3L, h.bridge.droppedMessages)
        assertEquals(1L, digits.bridge.droppedMessages)
        assertTrue(call.await().exceptionOrNull() is BridgeTimeoutException)
    }

    @Test fun stateComesWithTheOriginThatAnsweredIt() = runTest {
        val q = "https://q.invalid"
        // Unconfined, so the caller resumes as soon as it is free to. The reply and the engine's next events are
        // delivered in one unconfined task, so it resumes only after all of them, as behind a busy main thread.
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val sent = mutableListOf<String>()
        val bridge = WebBridge({ _, _ -> })
        bridge.connect(
            sink = { text, _ -> sent += text },
            originAllowed = { it == origin || it == q },
            scope = CoroutineScope(backgroundScope.coroutineContext + unconfined),
        )
        fun ready(nonce: String) = BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", nonce) }), from = nonce)
        bridge.receive(origin, ready("n1"))
        val saved = async(unconfined) {
            val answer = bridge.callForState("\$saveState")
            answer to bridge.documentOrigin
        }
        val call = BridgeCodec.decode(sent.single()) as BridgeMessage.Call
        launch(unconfined) {
            bridge.receive(origin, BridgeCodec.encode(BridgeMessage.Reply(call.id, JsonPrimitive("p's state")), from = "n1"))
            bridge.documentCommitted("$q/")
            bridge.receive(q, ready("m1"))
        }
        val (answer, originWhenResumed) = saved.await()
        // The documents switched before the caller resumed, yet the state comes with the origin that answered.
        assertEquals(q, originWhenResumed)
        assertEquals(PageAnswer(JsonPrimitive("p's state"), origin), answer)
    }

    @Test fun badParamsFailAtOnce() = runTest {
        val h = harness(ready = false)
        // No document is current, and a call waiting for one would ask the committed origin to announce itself.
        h.bridge.documentCommitted("$origin/")
        val start = testScheduler.currentTime
        val e = assertFailsWith<BridgeException> { h.bridge.call("x", JsonPrimitive(Double.NaN)) }
        assertEquals("params for 'x' are not strict JSON", e.message)
        assertFailsWith<BridgeException> { h.bridge.callForState("x", JsonPrimitive(Double.POSITIVE_INFINITY)) }
        assertEquals(start, testScheduler.currentTime)
        assertTrue(h.sent.isEmpty(), "sent: ${h.sent}")
    }

    @Test fun everyOpaqueCommitRefillsTheReadyBudget() = runTest {
        // Two opaque documents never share an origin, so each opaque commit is a new origin for the refill too.
        val h = harness(limits = BridgeLimits(readiesPerSecond = 0, readyBurst = 1), ready = false, allowed = setOf("null"))
        h.bridge.documentCommitted("about:blank")
        h.ready("n1", from = "null")
        assertTrue(h.bridge.isReady)
        h.bridge.documentCommitted(null)
        assertFalse(h.bridge.isReady)
        h.ready("n2", from = "null")
        assertTrue(h.bridge.isReady)
        h.bridge.documentCommitted("about:blank")
        h.ready("n3", from = "null")
        assertTrue(h.bridge.isReady)
        assertEquals(0L, h.bridge.droppedMessages)
    }

    // Task 16b: a load detaches the bridge at once.

    /** The view connects the bridge to the engine that starts the new content's document. Returns what it sends. */
    private fun TestScope.connectNext(h: Harness): MutableList<String> {
        val next = mutableListOf<String>()
        h.bridge.connect(sink = { text, _ -> next += text }, originAllowed = { it == origin }, scope = backgroundScope)
        return next
    }

    @Test fun afterADetachNothingReachesTheCurrentDocumentAndWhatIsSentWaitsForTheNext() = runTest {
        val h = harness()
        h.bridge.detach()
        assertFalse(h.bridge.isReady)
        assertTrue(h.bridge.isConnected)
        assertTrue(h.bridge.post("config", JsonPrimitive("B's token")))
        val call = async { runCatching { h.bridge.call("x", timeoutMillis = 5_000) } }
        runCurrent()
        // The old document is still there until the new one loads: it speaks and announces itself again, in vain.
        h.ready("n1")
        h.page(BridgeMessage.Event("tick"))
        runCurrent()
        assertFalse(h.bridge.isReady)
        assertTrue(h.sent.isEmpty(), "sent to the old document: ${h.decoded()}")
        // The new content's document, on the engine the view connects for it.
        val next = connectNext(h)
        h.ready("n2")
        runCurrent()
        val decoded = next.map { BridgeCodec.decode(it) }
        assertEquals(BridgeMessage.Event("config", JsonPrimitive("B's token")), decoded.first())
        assertEquals("x", (decoded[1] as BridgeMessage.Call).method)
        assertEquals(List(next.size) { "n2" }, next.map(::addressOf))
        h.page(BridgeMessage.Reply((decoded[1] as BridgeMessage.Call).id, JsonPrimitive("from B")))
        assertEquals(JsonPrimitive("from B"), call.await().getOrNull())
    }

    @Test fun aDetachFailsWhatWasMeantForTheOldContent() = runTest {
        // A call the old document hasn't answered yet.
        val live = harness()
        val pending = async { runCatching { live.bridge.call("x") } }
        runCurrent()
        live.bridge.detach()
        assertEquals("the view loaded other content", pending.await().exceptionOrNull()?.message)

        // A call and a post still waiting for the old content's document.
        val h = harness(ready = false)
        val waiting = async { runCatching { h.bridge.call("y", timeoutMillis = 5_000) } }
        assertTrue(h.bridge.post("old"))
        runCurrent()
        h.bridge.detach()
        runCurrent()
        assertEquals("the view loaded other content", waiting.await().exceptionOrNull()?.message)
        val next = connectNext(h)
        h.ready("n2")
        runCurrent()
        assertTrue(next.isEmpty(), "the old content's call or post reached the new document: $next")
    }

    /** A call the old document's `$ready` released, which hadn't run yet when the view loaded other content. */
    @Test fun aCallReleasedForTheOldDocumentJustBeforeADetachFails() = runTest {
        val h = harness(ready = false)
        val result = async { runCatching { h.bridge.call("x", timeoutMillis = 5_000) } }
        runCurrent()
        h.ready("n1")
        h.bridge.detach()
        val next = connectNext(h)
        h.ready("n2")
        runCurrent()
        assertEquals("the view loaded other content", result.await().exceptionOrNull()?.message)
        assertTrue(h.decoded().none { it is BridgeMessage.Call }, "sent to the old document: ${h.sent}")
        assertTrue(next.none { BridgeCodec.decode(it) is BridgeMessage.Call }, "sent to the new document: $next")
    }

    @Test fun aDetachedBridgeIsEndedByADisconnect() = runTest {
        val h = harness()
        h.bridge.detach()
        assertTrue(h.bridge.post("queued"))
        val call = async { runCatching { h.bridge.call("x", timeoutMillis = 5_000) } }
        runCurrent()
        // The view went cold before the new document started: nothing waits any more.
        h.bridge.disconnect()
        assertEquals("bridge disconnected", call.await().exceptionOrNull()?.message)
        assertFalse(h.bridge.isConnected)
        assertFalse(h.bridge.post("later"))
        val next = connectNext(h)
        h.ready("n2")
        assertTrue(next.isEmpty(), "sent: $next")
    }

    @Test fun detachingABridgeThatIsNotConnectedChangesNothing() = runTest {
        val bridge = WebBridge({ _, _ -> })
        bridge.detach()
        assertFalse(bridge.isConnected)
        assertFalse(bridge.post("x"))
        assertFailsWith<BridgeException> { bridge.call("x") }
    }

    /** Handlers and listeners belong to the state, not to a document: they stay registered across loads. */
    @Test fun handlersAndListenersOutliveADetach() = runTest {
        val h = harness()
        val heard = mutableListOf<JsonElement>()
        h.bridge.on("tick") { heard += it }
        h.bridge.handle("echo") { it }
        h.bridge.detach()
        val next = connectNext(h)
        h.ready("n2")
        h.page(BridgeMessage.Event("tick", JsonPrimitive(1)))
        h.page(BridgeMessage.Call(1, "echo", JsonPrimitive(2)))
        runCurrent()
        assertEquals(listOf<JsonElement>(JsonPrimitive(1)), heard)
        assertEquals(BridgeMessage.Reply(1, JsonPrimitive(2)), next.map { BridgeCodec.decode(it) }.filterIsInstance<BridgeMessage.Reply>().single())
    }
}
