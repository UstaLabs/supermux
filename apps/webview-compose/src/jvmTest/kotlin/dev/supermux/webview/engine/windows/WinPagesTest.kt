package dev.supermux.webview.engine.windows

import dev.supermux.webview.bridge.WebBridge
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinPagesTest {
    private val ran = ArrayDeque<Runnable>()

    @AfterTest fun reset() = WinPages.resetForTests()

    @Test fun eventsReachTheirPageInOrderOnTheExecutor() {
        WinPages.resetForTests(Executor { ran += it })
        val heard = mutableListOf<WinEvent>()
        WinPages.listen(7) { heard += it }
        WinGlue.onEvent(7, WinGlue.TITLE, 1, 0, 0, "one", null, null, null)
        WinGlue.onEvent(7, WinGlue.TITLE, 1, 0, 0, "two", null, null, null)
        assertTrue(heard.isEmpty(), "nothing runs on the glue's thread")
        while (ran.isNotEmpty()) ran.removeFirst().run()
        assertEquals(listOf<WinEvent>(WinEvent.Title(1, "one"), WinEvent.Title(1, "two")), heard)
    }

    @Test fun aFloodOfPageMessagesIsBoundedButReadyAlwaysPasses() {
        // L19: whatever a page posts beyond the bound waits nowhere; a $ready is never dropped.
        WinPages.resetForTests(Executor { ran += it })
        WinPages.listen(7) {}
        repeat(5_000) { WinGlue.onEvent(7, WinGlue.MESSAGE, 1, 0, 0, "0123456789abcdef msg {\"t\":\"event\",\"n\":\"tick\"}", null, null, null) }
        val ready = "0123456789abcdef msg " + WebBridge.READY_PREFIX + ",\"d\":{\"nonce\":\"n\"},\"from\":\"n\"}"
        WinGlue.onEvent(7, WinGlue.MESSAGE, 1, 0, 0, ready, null, null, null)
        assertTrue(ran.size <= WinPages.MESSAGE_BOUND + 1, "${ran.size} queued")
        val heard = mutableListOf<WinEvent>()
        WinPages.listen(7) { heard += it }
        while (ran.isNotEmpty()) ran.removeFirst().run()
        assertTrue(heard.any { it is WinEvent.Message && it.body == ready }, "the \$ready passed")
    }

    @Test fun aDecisionForAPageNobodyListensToIsARefusal() {
        assertEquals(WinGlue.REFUSE, WinGlue.decideNavigation(99, 1, WinGlue.NAV_MAIN_FRAME, "https://a.example/"))
        assertEquals(false, WinGlue.offerKey(99, 'K'.code, 0, 1))
    }

    @Test fun aDeciderThatThrowsRefuses() {
        WinPages.decide(5) { _, _, _ -> error("boom") }
        assertEquals(WinGlue.REFUSE, WinGlue.decideNavigation(5, 1, WinGlue.NAV_MAIN_FRAME, "https://a.example/"))
    }
}
