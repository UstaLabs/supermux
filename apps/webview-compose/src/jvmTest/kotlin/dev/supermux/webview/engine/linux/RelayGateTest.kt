package dev.supermux.webview.engine.linux

import dev.supermux.webview.engine.webkit.NavigationKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayGateTest {
    private val token = "0123456789abcdef"
    private fun page(json: String) = WpeEvent.Relay("$token msg $json")

    @Test fun aFloodOfPageMessagesStopsAtTheBoundButNothingElseDoes() {
        // In front of the engine's queue, which bounds them too: the channel from WebKit's thread grew without limit.
        val gate = RelayGate(limit = 3)
        repeat(3) { assertTrue(gate.admit(page("{\"t\":\"event\",\"n\":\"tick\",\"d\":$it}"))) }
        assertFalse(gate.admit(page("{\"t\":\"event\",\"n\":\"tick\",\"d\":4}")), "past the bound")
        assertTrue(gate.admit(page("{\"t\":\"event\",\"n\":\"\$ready\",\"d\":{\"nonce\":\"n\"}}")), "a \$ready is never dropped")
        assertTrue(gate.admit(WpeEvent.Relay("$token paint")), "the relay's own signals")
        assertTrue(gate.admit(WpeEvent.Policy(1, "https://a.example/", NavigationKind.Link, false, true, false)), "what WebKit waits on")
        gate.done(page("{\"t\":\"event\",\"n\":\"tick\",\"d\":0}"))
        assertTrue(gate.admit(page("{\"t\":\"event\",\"n\":\"tick\",\"d\":5}")), "room again, once the main thread took one")
    }
}
