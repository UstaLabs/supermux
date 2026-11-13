package dev.supermux.webview.engine.web

import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.bridge.userInputOf
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A button and the page calls the tests drive (see [DRIVER], which these pages add): `sendNow` calls the host the way a
 * card's send does. The page in [loadSend] also sends once from a timer and once from a promise, at load.
 */
private const val SEND_PAGE = """<button id="b">send</button><script>
window.sendNow = function () { hostBridge.call('send', 'hi'); };
document.getElementById('b').addEventListener('click', window.sendNow);
addEventListener('message', function (e) {
  var d = e.data || {};
  if (d.key) document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
  if (d.dispatch === 'pointerdown') document.getElementById('b').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, pointerType: 'mouse', isPrimary: true }));
});
</script>"""

/** The app's side of one card: the engine, a bridge on it with a `send` method, and what the handler saw. */
private class Card(trustSyntheticInput: Boolean, activation: () -> Boolean = { true }) {
    val h = ListHarness(trustSyntheticInput, activation)
    val bridge = WebBridge({ _, _ -> })
    val seen = ArrayList<Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    suspend fun live(body: String = SEND_PAGE) {
        bridge.handleWithUserInput("send") { _, fromUser ->
            seen += fromUser
            JsonPrimitive(fromUser)
        }
        h.live(body)
        bridge.connect(h.engine::postMessage, { it == h.origin }, scope, userInputOf(h.engine))
        var next = 0
        scope.launch {
            while (true) {
                while (next < h.events.size) {
                    val event = h.events[next++]
                    if (event is EngineEvent.PageMessage) bridge.receive(event.origin, event.json)
                }
                pause(10)
            }
        }
        waitUntil("the document's ready") { bridge.isReady }
    }

    fun tell(message: String) = h.tell(message)

    suspend fun settle() = pause(150)

    suspend fun close() {
        bridge.disconnect()
        scope.cancel()
        h.close()
    }
}

/**
 * The click-to-send cases of the spec on the web engine, from the page's call to the host's answer: the shim's trusted
 * presses and keys are the input ([trustSyntheticInput] stands in for "trusted", which Karma can't make), the browser's
 * activation at the app is [Card]'s `activation`, and a script's own events are refused.
 */
class UserInputStampWebTest {
    @Test fun aSendOnLoadFromATimerOrAPromiseIsRefused() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live(
            SEND_PAGE.replace(
                "</script>",
                "setTimeout(sendNow, 0); Promise.resolve().then(sendNow); sendNow();</script>",
            ),
        )
        card.settle()
        assertTrue(card.seen.isNotEmpty())
        assertEquals(List(card.seen.size) { false }, card.seen)
    }

    @Test fun aPressInTheFrameAllowsOneSendAndOneOnly() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live()
        card.tell("""{"press": "b"}""")
        card.settle()
        card.tell("""{"call": "sendNow"}""")
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(true, false), card.seen, "one input, one send")
        card.close()
    }

    @Test fun aKeyInTheFrameAllowsOneSend() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live()
        card.tell("""{"key": true}""")
        card.settle()
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(true), card.seen)
        card.close()
    }

    /** `el.click()` makes a click and no press or key: the page can't make input by calling it. */
    @Test fun aScriptMadeClickIsRefused() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live()
        card.tell("""{"click": "b"}""")
        card.settle()
        assertEquals(listOf(false), card.seen)
        card.close()
    }

    /** What the browser didn't make (`isTrusted` false) is no input, whatever the page dispatches. */
    @Test fun aScriptDispatchedPressIsRefused() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = false)
        card.live()
        card.tell("""{"dispatch": "pointerdown"}""")
        card.tell("""{"key": true}""")
        card.settle()
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(false), card.seen)
        card.close()
    }

    @Test fun aSendAfterTheWindowIsRefused() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live()
        card.tell("""{"press": "b"}""")
        pause(1_250)
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(false), card.seen)
        card.close()
    }

    /** The browser's own word, at the app's window: a press alone isn't enough. */
    @Test fun aSendWithoutTheBrowsersActivationIsRefused() = runTest(timeout = 1.minutes) {
        var active = false
        val card = Card(trustSyntheticInput = true, activation = { active })
        card.live()
        card.tell("""{"press": "b"}""")
        card.settle()
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(false), card.seen)
        // The refusal took the press with it: activation that comes late doesn't revive it.
        active = true
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(false, false), card.seen)
        card.close()
    }

    /** Input the user gave the app since, anywhere outside the frame, means the frame is no longer the last thing touched. */
    @Test fun aSendAfterTheUserTouchedTheAppIsRefused() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live()
        card.tell("""{"press": "b"}""")
        card.settle()
        card.h.engine.appInput()
        card.tell("""{"call": "sendNow"}""")
        card.settle()
        assertEquals(listOf(false), card.seen)
        card.close()
    }

    @Test fun aNewDocumentStartsWithNoInput() = runTest(timeout = 1.minutes) {
        val card = Card(trustSyntheticInput = true)
        card.live()
        card.tell("""{"press": "b"}""")
        card.settle()
        assertTrue(card.h.engine.consumeUserInput(), "the press is waiting")
        card.tell("""{"press": "b"}""")
        card.settle()
        card.h.engine.load(dev.supermux.webview.WebContent.Html("<!doctype html><html><body>next</body></html>"))
        assertFalse(card.h.engine.consumeUserInput(), "a press in the old document is not the new one's")
        card.close()
    }

    /** Input in one card never allows a send from another. */
    @Test fun anotherCardsInputDoesNotAllowThisCardsSend() = runTest(timeout = 1.minutes) {
        val a = Card(trustSyntheticInput = true)
        a.live()
        a.tell("""{"press": "b"}""")
        a.settle()
        val b = Card(trustSyntheticInput = true)
        assertFalse(b.h.engine.consumeUserInput(), "b was never touched")
        assertTrue(a.h.engine.consumeUserInput(), "a's input is still a's")
        b.h.close()
        a.close()
    }
}
