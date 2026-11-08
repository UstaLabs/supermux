package dev.supermux.webview.bridge

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    private fun frame(
        width: Int = 300,
        height: Int = 200,
        document: String? = null,
        mainFrame: String = "true",
        callTimeoutMillis: Long = 30_000,
    ): JsAny {
        val w = newFrame(width, height)
        if (document != null) runIn(w, "document.open(); document.write(${jsonString(document)}); document.close();")
        runIn(w, "window.__sent = [];")
        runIn(
            w,
            BridgeShim.source(
                "function (s) { var a = window.__sent; a[a.length] = s; }",
                mainFrameExpression = mainFrame,
                callTimeoutMillis = callTimeoutMillis,
            ),
        )
        return w
    }

    private fun jsonString(text: String) = JsonPrimitive(text).toString()

    private fun sent(w: JsAny): List<BridgeMessage> = (0 until sentCount(w)).mapNotNull { BridgeCodec.decode(sentAt(w, it)) }

    private fun events(w: JsAny, name: String) = sent(w).filterIsInstance<BridgeMessage.Event>().filter { it.name == name }

    private fun replies(w: JsAny) = sent(w).filterIsInstance<BridgeMessage.Reply>()

    private fun consoleTexts(w: JsAny) = events(w, "\$console").map { it.data.jsonObject["text"]!!.jsonPrimitive.content }

    /** The frame's document nonce, from its first `$ready`. */
    private fun nonceOf(w: JsAny): String = events(w, "\$ready").first().data.jsonObject["nonce"]!!.jsonPrimitive.content

    /** Delivers [message] addressed [to] a document, by default the frame's own, as the host does. */
    private fun deliver(w: JsAny, message: BridgeMessage, to: String? = nonceOf(w)) = deliver(w, BridgeCodec.encode(message, to = to))

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
        deliver(w, BridgeMessage.Reply(call.id, JsonPrimitive(7)))
        assertEquals(7, asInt(promise.await<JsAny?>()))
    }

    @Test fun anErrorReplyRejectsThePagesPromise() = runTest {
        val w = frame()
        val promise = pageCall(w, "x", 1)
        val call = sent(w).filterIsInstance<BridgeMessage.Call>().single()
        deliver(w, BridgeMessage.Reply(call.id, error = "nope"))
        assertEquals("rejected: nope", jsString(outcome(promise).await<JsAny?>()))
    }

    @Test fun aHostCallReachesAPageHandlerAndRepliesWithItsResult() = runTest {
        val w = frame()
        runIn(w, "hostBridge.handle('double', function (x) { return x * 2; })")
        deliver(w, BridgeMessage.Call(9, "double", JsonPrimitive(21)))
        wait(0).await<JsAny?>()
        assertEquals(BridgeMessage.Reply(9, JsonPrimitive(42)), replies(w).single())
    }

    @Test fun unknownAndPrototypeNamedMethodsAreNotFound() = runTest {
        val w = frame()
        deliver(w, BridgeMessage.Call(3, "nope"))
        deliver(w, BridgeMessage.Call(4, "toString"))
        deliver(w, BridgeMessage.Call(5, "constructor"))
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
        deliver(w, BridgeMessage.Event("ping"))
        runIn(w, "window.__off();")
        deliver(w, BridgeMessage.Event("ping"))
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
        // The page's own width: 300 under overlay scrollbars (macOS), 285 under Linux Chrome's classic ones, whose
        // vertical scrollbar takes its 15 px from the layout.
        assertEquals(evalIn(w, "document.documentElement.clientWidth").toInt(), size["w"]!!.jsonPrimitive.int)
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
        deliver(w, BridgeMessage.Event("\$restoreState", JsonPrimitive(42)))
        runIn(w, "window.__restored = 'none'; hostBridge.onRestoreState(function (s) { window.__restored = s; });")
        wait(0).await<JsAny?>()
        assertEquals("42", evalIn(w, "window.__restored"))
    }

    @Test fun aSaveProviderAnnouncesItselfAndAnswersSaveState() = runTest {
        val w = frame()
        runIn(w, "hostBridge.onSaveState(function () { return {n: 1}; })")
        assertEquals(1, events(w, "\$saveStateProvider").size)
        deliver(w, BridgeMessage.Call(11, "\$saveState"))
        wait(0).await<JsAny?>()
        assertEquals("1", replies(w).single().result!!.jsonObject["n"]!!.jsonPrimitive.content)
    }

    @Test fun thePageCannotReplaceTheBridgeOrItsReceiver() = runTest {
        val w = frame()
        runIn(w, "try { window.hostBridge = null; } catch (e) {} try { window.__hostBridgeReceive = null; } catch (e) {}")
        runIn(w, "window.__got = 0; hostBridge.on('still', function () { window.__got++; });")
        deliver(w, BridgeMessage.Event("still"))
        assertEquals("1", evalIn(w, "window.__got"))
    }

    @Test fun patchedArrayMethodsDoNotBreakTheBridge() = runTest {
        val w = frame()
        runIn(w, "window.__got = 0; hostBridge.on('still', function () { window.__got++; });")
        runIn(w, "Array.prototype.map = function () { throw new Error('patched'); }; Array.prototype.forEach = function () { throw new Error('patched'); }; Array.prototype.slice = function () { throw new Error('patched'); };")
        runIn(w, "console.log('still logging')")
        deliver(w, BridgeMessage.Event("still"))
        assertTrue("still logging" in consoleTexts(w))
        assertEquals("1", evalIn(w, "window.__got"))
    }

    @Test fun aSubFrameDoesNotInstallTheBridge() = runTest {
        val w = frame(mainFrame = "false")
        assertEquals(0, sentCount(w))
        assertEquals("undefined", evalIn(w, "typeof hostBridge"))
    }

    @Test fun aPageRestoredFromTheBackForwardCacheAnnouncesItselfAgain() = runTest {
        val w = frame()
        runIn(w, "window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));")
        val readies = events(w, "\$ready")
        assertEquals(2, readies.size)
        assertNotEquals(readies[0].data.jsonObject["nonce"], readies[1].data.jsonObject["nonce"])
    }

    @Test fun aHelloMakesThePageAnnounceItselfAgainWithTheSameNonce() = runTest {
        val w = frame()
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$hello")))
        val readies = events(w, "\$ready")
        assertEquals(2, readies.size)
        assertEquals(readies[0].data.jsonObject["nonce"], readies[1].data.jsonObject["nonce"])
    }

    @Test fun onAndHandleRequireAFunction() = runTest {
        val w = frame()
        assertEquals("refused", evalIn(w, "(function () { try { hostBridge.on('x'); return 'set'; } catch (e) { return 'refused'; } })()"))
        assertEquals("refused", evalIn(w, "(function () { try { hostBridge.handle('m'); return 'set'; } catch (e) { return 'refused'; } })()"))
    }

    @Test fun aPageSideCallTimesOutWhenTheHostNeverAnswers() = runTest {
        val w = frame(callTimeoutMillis = 50)
        val outcomeText = outcome(pageCall(w, "x", 1))
        wait(150).await<JsAny?>()
        assertEquals("rejected: hostBridge: the host did not answer in time", jsString(outcomeText.await<JsAny?>()))
    }

    @Test fun aConsoleFloodIsSummarized() = runTest {
        val w = frame()
        runIn(w, "for (var i = 0; i < 150; i++) console.log('line ' + i);")
        assertEquals(100, consoleTexts(w).count { it.startsWith("line ") })
        wait(1_200).await<JsAny?>()
        assertTrue(consoleTexts(w).any { it.startsWith("50 console messages were dropped") }, consoleTexts(w).toString())
        assertTrue(sentTexts(w).all { fromOf(it) == nonceOf(w) }, "a message without its sender")
    }

    @Test fun sharedObjectsAreNotMistakenForCircularOnes() = runTest {
        val w = frame()
        runIn(w, "var x = {v: 1}; console.log({a: x, b: x}); var c = {}; c.self = c; console.log(c);")
        val texts = consoleTexts(w)
        assertEquals("{\"a\":{\"v\":1},\"b\":{\"v\":1}}", texts[0])
        assertEquals("{\"self\":\"[Circular]\"}", texts[1])
    }

    @Test fun eachFailingListenerReportsItsOwnError() = runTest {
        val w = frame()
        runIn(w, "hostBridge.on('go', function () { throw new Error('first listener failed'); }); hostBridge.on('go', function () { throw new Error('second listener failed'); });")
        deliver(w, BridgeMessage.Event("go"))
        wait(50).await<JsAny?>()
        val texts = consoleTexts(w)
        assertTrue(texts.any { "first listener failed" in it } && texts.any { "second listener failed" in it }, texts.toString())
    }

    @Test fun unsubscribingRemovesOneRegistration() = runTest {
        val w = frame()
        runIn(w, "window.__n = 0; var f = function () { window.__n++; }; hostBridge.on('go', f); var off = hostBridge.on('go', f); off();")
        deliver(w, BridgeMessage.Event("go"))
        assertEquals("1", evalIn(w, "window.__n"))
    }

    @Test fun messagesForAnotherDocumentAreDropped() = runTest {
        val w = frame()
        val own = nonceOf(w)
        runIn(w, "window.__pings = 0; hostBridge.on('ping', function () { window.__pings++; });")
        runIn(w, "window.__handled = 0; hostBridge.handle('m', function (x) { window.__handled++; return x; });")
        runIn(w, "window.__call = 'pending'; hostBridge.call('x', 1).then(function (r) { window.__call = 'resolved: ' + r; }, function (e) { window.__call = 'rejected'; });")
        val call = sent(w).filterIsInstance<BridgeMessage.Call>().single()

        for (to in listOf("other", null)) {
            deliver(w, BridgeMessage.Event("ping"), to = to)
            deliver(w, BridgeMessage.Call(5, "m", JsonPrimitive(1)), to = to)
            deliver(w, BridgeMessage.Reply(call.id, JsonPrimitive(7)), to = to)
        }
        wait(0).await<JsAny?>()
        assertEquals("0", evalIn(w, "window.__pings"))
        assertEquals("0", evalIn(w, "window.__handled"))
        assertTrue(replies(w).isEmpty())
        assertEquals("pending", evalIn(w, "window.__call"))

        deliver(w, BridgeMessage.Event("ping"), to = own)
        deliver(w, BridgeMessage.Call(6, "m", JsonPrimitive(2)), to = own)
        deliver(w, BridgeMessage.Reply(call.id, JsonPrimitive(7)), to = own)
        wait(0).await<JsAny?>()
        assertEquals("1", evalIn(w, "window.__pings"))
        assertEquals("1", evalIn(w, "window.__handled"))
        assertEquals(BridgeMessage.Reply(6, JsonPrimitive(2)), replies(w).single())
        assertEquals("resolved: 7", evalIn(w, "window.__call"))
    }

    @Test fun aRestoredStateForAnotherDocumentIsDropped() = runTest {
        val w = frame()
        deliver(w, BridgeMessage.Event("\$restoreState", JsonPrimitive(42)), to = "other")
        runIn(w, "window.__restored = 'none'; hostBridge.onRestoreState(function (s) { window.__restored = s; });")
        wait(0).await<JsAny?>()
        assertEquals("none", evalIn(w, "window.__restored"))
    }

    @Test fun aPageRestoredFromTheBackForwardCacheTakesOnlyMessagesForItsNewNonce() = runTest {
        val w = frame()
        val before = nonceOf(w)
        runIn(w, "window.__pings = 0; hostBridge.on('ping', function () { window.__pings++; });")
        runIn(w, "window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));")
        val after = events(w, "\$ready").last().data.jsonObject["nonce"]!!.jsonPrimitive.content
        deliver(w, BridgeMessage.Event("ping"), to = before)
        assertEquals("0", evalIn(w, "window.__pings"))
        deliver(w, BridgeMessage.Event("ping"), to = after)
        assertEquals("1", evalIn(w, "window.__pings"))
    }

    /** A host that asks may be a new connection to a page already running: the page reports its size to it again. */
    @Test fun aHelloMakesThePageReportItsSizeAgain() = runTest {
        val w = frame(document = "<!doctype html><html><body style=\"margin:0\"><div style=\"height:500px\"></div></body></html>")
        wait(100).await<JsAny?>()
        assertEquals(1, events(w, "\$size").size)
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$hello")))
        wait(100).await<JsAny?>()
        val sizes = events(w, "\$size")
        assertEquals(2, sizes.size)
        assertEquals(500, (sizes.last().data as JsonObject)["h"]!!.jsonPrimitive.int)
    }

    /**
     * A host that asks may be a new connection to a page that registered its save provider before it, or while nobody
     * listened (a parked page, whose messages reach no one): the page tells it of the provider again, right after its
     * `$ready`, signed like every message. A page without one says nothing of one.
     */
    @Test fun aHelloMakesThePageTellOfItsSaveProviderAgain() = runTest {
        val w = frame()
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$hello")))
        assertEquals(0, events(w, "\$saveStateProvider").size, "a page without a save provider told of one")
        runIn(w, "hostBridge.onSaveState(function () { return {draft: 1}; })")
        val before = sentCount(w)
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$hello")))
        val answer = (before until sentCount(w)).map { sentAt(w, it) }.take(2)
        assertEquals(listOf("\$ready", "\$saveStateProvider"), answer.map { (BridgeCodec.decode(it) as BridgeMessage.Event).name })
        assertEquals(listOf(nonceOf(w), nonceOf(w)), answer.map { fromOf(it) })
        runIn(w, "hostBridge.onSaveState(null)")
        val unregistered = sentCount(w)
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$hello")))
        assertTrue((unregistered until sentCount(w)).none { "\$saveStateProvider" in sentAt(w, it) }, "a provider taken back was told of")
    }

    @Test fun helloNeedsNoAddress() = runTest {
        val w = frame()
        deliver(w, BridgeCodec.encode(BridgeMessage.Event("\$hello")))
        assertEquals(2, events(w, "\$ready").size)
    }

    // Round 6: pages sign their messages.

    private fun sentTexts(w: JsAny): List<String> = (0 until sentCount(w)).map { sentAt(w, it) }

    /** The document [text] says it is from, read from the raw JSON, or null when it names none. */
    private fun fromOf(text: String): String? = Json.parseToJsonElement(text).jsonObject["from"]?.jsonPrimitive?.content

    @Test fun everyPageMessageSaysWhoSentIt() = runTest {
        val w = frame(document = "<!doctype html><html><body style=\"margin:0\"><div style=\"height:50px\"></div></body></html>")
        val own = nonceOf(w)
        runIn(w, "hostBridge.handle('m', function (x) { return x; }); hostBridge.onSaveState(function () { return 1; });")
        runIn(w, "hostBridge.handle('circular', function () { var c = {}; c.self = c; return c; });")
        runIn(w, "hostBridge.call('x', 1).then(null, function () {}); hostBridge.post('pong', 2); console.log('hi');")
        deliver(w, BridgeMessage.Call(5, "m", JsonPrimitive(1)))
        deliver(w, BridgeMessage.Call(6, "nope"))
        deliver(w, BridgeMessage.Call(7, "circular"))
        wait(100).await<JsAny?>()
        val texts = sentTexts(w)
        val kinds = texts.map {
            when (val message = BridgeCodec.decode(it)) {
                is BridgeMessage.Call -> "call"
                is BridgeMessage.Reply -> when {
                    message.error == null -> "reply"
                    message.error.startsWith("the reply could not be sent") -> "unsendable reply"
                    else -> "error reply"
                }
                is BridgeMessage.Event -> message.name
                null -> "?"
            }
        }.toSet()
        val expected = setOf("\$ready", "call", "pong", "reply", "error reply", "unsendable reply", "\$console", "\$saveStateProvider", "\$size")
        assertTrue(kinds.containsAll(expected), "kinds: $kinds")
        assertEquals(List(texts.size) { own }, texts.map(::fromOf))
    }

    @Test fun aRestoredPageRejectsItsPendingCalls() = runTest {
        val w = frame()
        val before = nonceOf(w)
        runIn(w, "window.__r = 'pending'; hostBridge.call('x', 1).then(function () { window.__r = 'resolved'; }, function (e) { window.__r = 'rejected: ' + e.message; });")
        runIn(w, "window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));")
        wait(0).await<JsAny?>()
        assertEquals("rejected: hostBridge: the page was restored from the back-forward cache", evalIn(w, "window.__r"))
        val readies = events(w, "\$ready")
        assertEquals(2, readies.size)
        val after = readies[1].data.jsonObject["nonce"]!!.jsonPrimitive.content
        assertNotEquals(before, after)
        assertEquals(after, fromOf(sentTexts(w).last()))
    }

    /** The bridge and the real shim together: a lost `$ready` after a same-origin navigation recovers. */
    @Test fun aSameOriginDocumentWhoseReadyWasLostRecoversWhenItSpeaks() = runTest {
        val origin = "https://p.invalid"
        val time = longArrayOf(0)
        val bridge = WebBridge({ _, _ -> }, BridgeLimits(readiesPerSecond = 1, readyBurst = 1)) { time[0] }
        var main: JsAny? = null
        var read = 0
        bridge.connect(sink = { text, _ -> main?.let { deliver(it, text) } }, originAllowed = { it == origin }, scope = backgroundScope)
        // Hands the main frame's messages to the host as an engine does, including those it sends meanwhile.
        fun pump() {
            val w = main ?: return
            while (read < sentCount(w)) bridge.receive(origin, sentAt(w, read++))
        }

        main = frame()
        bridge.documentCommitted("$origin/")
        pump()
        assertTrue(bridge.isReady)

        // A same-origin navigation whose $ready the budget drops: the host keeps the first document's nonce.
        bridge.documentCommitted("$origin/next")
        val next = frame()
        main = next
        read = 0
        pump()
        assertEquals(1L, bridge.droppedMessages)
        runIn(next, "window.__pings = 0; hostBridge.on('ping', function () { window.__pings++; });")
        bridge.post("ping")
        assertEquals("0", evalIn(next, "window.__pings"))

        // Later the new document speaks. The host asks it to announce itself, and reaches it again.
        time[0] += 1_000
        runIn(next, "hostBridge.post('tick', 1);")
        pump()
        bridge.post("ping")
        assertEquals("1", evalIn(next, "window.__pings"))
        bridge.disconnect()
    }

    // A misaddressed page speaks up, and the shim keeps its own Error.

    /** The nonces the frame announced, oldest first. */
    private fun readyNonces(w: JsAny): List<String> = events(w, "\$ready").map { it.data.jsonObject["nonce"]!!.jsonPrimitive.content }

    @Test fun aMisaddressedMessageMakesThePageAnnounce() = runTest {
        val w = frame()
        val own = nonceOf(w)
        runIn(w, "window.__pings = 0; hostBridge.on('ping', function () { window.__pings++; });")
        // Without an address, a message isn't for another document: it is dropped, and that is all.
        deliver(w, BridgeMessage.Event("ping"), to = null)
        wait(0).await<JsAny?>()
        assertEquals(listOf(own), readyNonces(w))
        // One for another document means the host has a stale nonce: it is dropped, and the page announces its own.
        deliver(w, BridgeMessage.Event("ping"), to = "other")
        wait(0).await<JsAny?>()
        assertEquals("0", evalIn(w, "window.__pings"))
        assertEquals(listOf(own, own), readyNonces(w))
        assertTrue(sentTexts(w).all { fromOf(it) == own }, "a message without its sender")
        // So does a call or a reply for another document.
        for (message in listOf(BridgeMessage.Call(5, "m"), BridgeMessage.Reply(1, JsonPrimitive(7)))) {
            val v = frame()
            deliver(v, message, to = "other")
            wait(0).await<JsAny?>()
            assertTrue(replies(v).isEmpty(), "$message was answered")
            assertEquals(List(2) { nonceOf(v) }, readyNonces(v), "after $message")
        }
    }

    @Test fun misaddressedAnnouncementsAreRateLimited() = runTest {
        val w = frame()
        val own = nonceOf(w)
        repeat(10) { deliver(w, BridgeMessage.Event("ping"), to = "other") }
        wait(0).await<JsAny?>()
        assertEquals(listOf(own, own), readyNonces(w))
        // A second later, the page may speak up again.
        wait(1_100).await<JsAny?>()
        deliver(w, BridgeMessage.Event("ping"), to = "other")
        wait(0).await<JsAny?>()
        assertEquals(listOf(own, own, own), readyNonces(w))
    }

    @Test fun aReplacedErrorConstructorIsNotUsed() = runTest {
        val w = frame(callTimeoutMillis = 50)
        // After install, the page replaces Error and TypeError with constructors that make no errors at all.
        runIn(w, "window.__Error = Error; window.__TypeError = TypeError; window.Error = window.TypeError = function () { return { fake: true }; };")
        runIn(w, "window.__seen = {}; window.__kind = function (e) { return (e instanceof __TypeError ? 'TypeError: ' : e instanceof __Error ? 'Error: ' : 'not an Error: ') + e.message; };")
        runIn(w, "window.__track = function (name, promise) { __seen[name] = 'pending'; promise.then(function () { __seen[name] = 'resolved'; }, function (e) { __seen[name] = __kind(e); }); };")
        runIn(w, "try { hostBridge.on('\$x', function () {}); __seen.thrown = 'nothing thrown'; } catch (e) { __seen.thrown = __kind(e); }")
        runIn(w, "__track('reserved', hostBridge.call('\$x', 1)); var c = {}; c.self = c; __track('unsendable', hostBridge.call('x', c)); __track('error reply', hostBridge.call('x', 1));")
        deliver(w, BridgeMessage.Reply(sent(w).filterIsInstance<BridgeMessage.Call>().single().id, error = "nope"))
        deliver(w, BridgeMessage.Call(9, "nope"))
        runIn(w, "__track('timeout', hostBridge.call('x', 2)); console.error(new __Error('boom'));")
        wait(100).await<JsAny?>()
        runIn(w, "__track('restored', hostBridge.call('x', 3)); window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));")
        wait(0).await<JsAny?>()

        assertEquals("TypeError: hostBridge.on: names starting with \$ are reserved", evalIn(w, "__seen.thrown"))
        assertEquals("TypeError: hostBridge.call: names starting with \$ are reserved", evalIn(w, "__seen.reserved"))
        assertEquals("Error: hostBridge: the call could not be sent (too large, not JSON, or no channel)", evalIn(w, "__seen.unsendable"))
        assertEquals("Error: nope", evalIn(w, "__seen['error reply']"))
        assertEquals("Error: hostBridge: the host did not answer in time", evalIn(w, "__seen.timeout"))
        assertEquals("Error: hostBridge: the page was restored from the back-forward cache", evalIn(w, "__seen.restored"))
        // The errors it makes for itself read right too: a missing method's, and a real Error the page logs.
        assertEquals("no such method: nope", replies(w).single().error)
        assertTrue(consoleTexts(w).any { "boom" in it }, consoleTexts(w).toString())
    }
}
