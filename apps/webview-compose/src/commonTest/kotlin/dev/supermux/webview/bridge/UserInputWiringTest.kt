package dev.supermux.webview.bridge

import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.testing.FakeWebEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the host asks of an engine for [WebBridge.handleWithUserInput]: its [UserInputSource], or never. */
class UserInputWiringTest {
    /** The fake engine, with a stamp as an engine that tells of the user's input has one. */
    private class StampedFake(val fake: FakeWebEngine) : WebEngine by fake, UserInputSource {
        val stamp = InputStamp()

        override fun consumeUserInput(): Boolean = stamp.consume(inputClock())
    }

    @Test fun anEngineThatDoesntTellOfInputNeverAllowsOne() {
        val consume = userInputOf(FakeWebEngine(1))
        assertFalse(consume())
        assertFalse(consume())
    }

    @Test fun anEnginesInputIsWhatTheHostAsks() {
        val engine = StampedFake(FakeWebEngine(1))
        val consume = userInputOf(engine)
        assertFalse(consume(), "nothing yet: a send on load")
        engine.stamp.mark(inputClock())
        assertTrue(consume())
        assertFalse(consume(), "one input, one send")
    }

    @Test fun eachEnginesInputIsItsOwn() {
        val a = StampedFake(FakeWebEngine(1))
        val b = StampedFake(FakeWebEngine(2))
        a.stamp.mark(inputClock())
        assertFalse(userInputOf(b)(), "a different card")
        assertTrue(userInputOf(a)())
    }
}
