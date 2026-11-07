package dev.supermux.webview.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The page bridge on Android (Task 13), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineBridgeTest {
    private val echo = TestPages.echo
    private val origin = echo.origin.origin

    private fun nonceOf(event: EngineEvent): String? {
        val message = event as? EngineEvent.PageMessage ?: return null
        if (!message.json.startsWith(WebBridge.READY_PREFIX)) return null
        return BridgeCodec.decodeEnvelope(message.json)?.from
    }

    private fun replyOf(event: EngineEvent, id: Long): BridgeMessage.Reply? {
        val message = event as? EngineEvent.PageMessage ?: return null
        return (BridgeCodec.decodeEnvelope(message.json)?.message as? BridgeMessage.Reply)?.takeIf { it.id == id }
    }

    /** Loads the echo page and returns the nonce its document announced. */
    private suspend fun EngineHarness.loadEcho(): String {
        main {
            configure(setupFor(echo))
            load(echo)
        }
        val ready = seenOrAwait("the page's \$ready") { nonceOf(it) != null } as EngineEvent.PageMessage
        assertEquals(origin, ready.origin)
        return nonceOf(ready)!!
    }

    private suspend fun EngineHarness.call(id: Long, nonce: String, target: String = origin) = main {
        postMessage(BridgeCodec.encode(BridgeMessage.Call(id, "echo", JsonPrimitive("hi $id")), to = nonce), target)
    }

    @Test fun aDocumentAnnouncesItselfAndAnswersACall() = EngineHarness.run {
        val nonce = loadEcho()
        call(1, nonce)
        val reply = await("the reply") { replyOf(it, 1) != null } as EngineEvent.PageMessage
        assertEquals(origin, reply.origin)
        assertEquals(JsonPrimitive("hi 1"), replyOf(reply, 1)!!.result)
        assertEquals(nonce, BridgeCodec.decodeEnvelope(reply.json)!!.from)
    }

    @Test fun aMessageForAnotherOriginNeverArrives() = EngineHarness.run {
        val nonce = loadEcho()
        call(2, nonce, target = "https://other.invalid")
        expectNone("a reply to a message for another origin", millis = 1_500) { replyOf(it, 2) != null }
        call(3, nonce)
        await("the next reply") { replyOf(it, 3) != null }
    }

    @Test fun aReloadedDocumentHasANewNonceAndMessagesForTheOldOneReachNoOne() = EngineHarness.run {
        val first = loadEcho()
        main { reload() }
        val second = nonceOf(await("the new document's \$ready") { nonceOf(it) != null && nonceOf(it) != first })!!
        assertNotEquals(first, second)
        call(4, first)
        // The new document drops a message addressed to the old one, and announces itself again.
        await("the new document speaking up") { nonceOf(it) == second }
        expectNone("a reply from the old document", millis = 1_000) { replyOf(it, 4) != null }
    }

    @Test fun aDocumentOfAnOriginOffTheListGetsNoShim() = EngineHarness.run {
        loadAndPaint(echo, setupFor(echo, bridgeOrigins = emptySet()))
        assertEquals("\"undefined\"", main { evaluate("typeof window.hostBridge") })
        expectNone("a page message", millis = 1_000) { it is EngineEvent.PageMessage }
    }

    /**
     * A mid-lease change of the bridge's origins that keeps the page's own keeps its channel: calls still reach the page
     * and its answers come back (review, minor). Before, the engine took its listener away and added another, and
     * WebView drops what the documents of a removed listener send.
     */
    @Test fun aMidLeaseChangeOfOriginsThatKeepsThePagesKeepsItsBridge() = EngineHarness.run {
        val nonce = loadEcho()
        call(10, nonce)
        await("the first answer") { replyOf(it, 10) != null }
        main { configure(setupFor(echo, bridgeOrigins = setOf(origin, "https://other.example"))) }
        call(11, nonce)
        await("the answer after an origin was added") { replyOf(it, 11) != null }
        main { configure(setupFor(echo, bridgeOrigins = setOf(origin))) }
        call(12, nonce)
        await("the answer after it was taken off again") { replyOf(it, 12) != null }
    }

    /** JavaScript turned off and on again mid-lease leaves the page's channel as it was (review, minor). */
    @Test fun aMidLeaseJavaScriptToggleKeepsThePagesBridge() = EngineHarness.run {
        val nonce = loadEcho()
        main { configure(setupFor(echo).copy(javaScriptEnabled = false)) }
        main { configure(setupFor(echo)) }
        call(13, nonce)
        await("the answer after the toggle") { replyOf(it, 13) != null }
    }

    /** An origin taken off the list mid-lease is heard no more: its channel stays, but what it sends is dropped. */
    @Test fun anOriginTakenOffTheListMidLeaseIsHeardNoMore() = EngineHarness.run {
        val nonce = loadEcho()
        main { configure(setupFor(echo, bridgeOrigins = setOf("https://other.example"))) }
        call(14, nonce)
        expectNone("an answer from an origin off the list", millis = 1_500) { replyOf(it, 14) != null }
    }

    @Test fun aSubframeCantSpeakForThePage() = EngineHarness.run {
        loadAndPaint(TestPages.framed)
        expectNone("the subframe's message", millis = 2_000) { (it as? EngineEvent.PageMessage)?.json?.contains("spoof") == true }
    }
}
