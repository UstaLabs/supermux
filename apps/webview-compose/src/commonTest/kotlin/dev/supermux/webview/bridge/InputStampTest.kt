package dev.supermux.webview.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The click-to-send rule on its own, as pure logic: spec "Click-to-send", the native rule. */
class InputStampTest {
    @Test fun noInputAllowsNoSend() {
        // A call on load, or from a timer or a promise, has had no input at all.
        assertFalse(InputStamp().consume(0))
        assertFalse(InputStamp().consume(5_000))
    }

    @Test fun aSendRightAfterInputIsAllowed() {
        val stamp = InputStamp()
        stamp.mark(1_000)
        assertTrue(stamp.consume(1_010))
    }

    @Test fun theWindowIsOneSecondAndItsEdgeCounts() {
        assertEquals(1000, InputStamp.DEFAULT_WINDOW_MILLIS)
        val edge = InputStamp().apply { mark(500) }
        assertTrue(edge.consume(1_500))
        val past = InputStamp().apply { mark(500) }
        assertFalse(past.consume(1_501))
    }

    @Test fun aSendAfterTheWindowIsRefusedAndTheOldInputIsGone() {
        val stamp = InputStamp()
        stamp.mark(0)
        assertFalse(stamp.consume(2_000))
        // The late call took the stale input with it, so it can't serve a later call either.
        assertFalse(stamp.isMarked)
        assertFalse(stamp.consume(2_001))
    }

    @Test fun oneInputAllowsOneSend() {
        val stamp = InputStamp()
        stamp.mark(100)
        assertTrue(stamp.consume(150))
        assertFalse(stamp.consume(160), "a second send from one input")
        assertFalse(stamp.consume(170))
    }

    @Test fun eachInputAllowsOneMore() {
        val stamp = InputStamp()
        stamp.mark(100)
        assertTrue(stamp.consume(110))
        stamp.mark(120)
        assertTrue(stamp.consume(130))
    }

    @Test fun theNewestInputCountsAndInputsDoNotAdd() {
        val stamp = InputStamp()
        stamp.mark(0)
        stamp.mark(900)
        // The first would be 1_200 old; only the newest counts, and two inputs make one send, not two.
        assertTrue(stamp.consume(1_200))
        assertFalse(stamp.consume(1_201))
    }

    @Test fun aClockThatWentBackwardsRefuses() {
        val stamp = InputStamp()
        stamp.mark(1_000)
        assertFalse(stamp.consume(999))
    }

    @Test fun clearForgetsTheInput() {
        val stamp = InputStamp()
        stamp.mark(100)
        stamp.clear()
        assertFalse(stamp.consume(110), "the user touched something else since")
    }

    @Test fun aDifferentCardsInputDoesNotAllowThisCardsSend() {
        val a = InputStamp()
        val b = InputStamp()
        a.mark(100)
        assertFalse(b.consume(110))
        assertTrue(a.consume(110), "and a's own input is still a's")
    }

    @Test fun aRefusalDoesNotTakeAnotherViewsInput() {
        val a = InputStamp()
        val b = InputStamp()
        b.mark(100)
        assertFalse(a.consume(110))
        assertTrue(b.consume(120))
    }
}
