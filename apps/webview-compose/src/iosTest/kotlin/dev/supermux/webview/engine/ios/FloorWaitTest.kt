package dev.supermux.webview.engine.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [awaitFloor], a reset's wait for its floor, in virtual time. A reset can be on an activation's path (the pool resets
 * an evicted engine of the same config for the view that evicted it), so the wait ends when the floor's load does.
 */
class FloorWaitTest {
    @Test fun theFloorsLoadEndsTheWaitAtOnce() = runTest {
        val loaded = CompletableDeferred<Boolean>()
        launch { delay(30); loaded.complete(true) }
        assertTrue(awaitFloor(loaded, millis = 4_000, settleMillis = 100) { false })
        assertEquals(30, testScheduler.currentTime, "when the wait ended")
    }

    @Test fun aFailedFloorLoadEndsTheWaitAtOnce() = runTest {
        val loaded = CompletableDeferred<Boolean>()
        launch { delay(30); loaded.complete(false) }
        assertFalse(awaitFloor(loaded, millis = 4_000, settleMillis = 100) { true })
        assertEquals(30, testScheduler.currentTime, "when the wait ended")
    }

    /** A navigation to the floor still in flight (WebKit doesn't show it alone yet) is waited for, to its load. */
    @Test fun aFloorStillLoadingIsWaitedForToItsLoad() = runTest {
        val loaded = CompletableDeferred<Boolean>()
        launch { delay(250); loaded.complete(true) }
        assertTrue(awaitFloor(loaded, millis = 4_000, settleMillis = 100) { false })
        assertEquals(250, testScheduler.currentTime, "when the wait ended")
    }

    /** WebKit loads nothing when it shows the floor already: shown, with nothing loading, once the settle time is up. */
    @Test fun aFloorShownAlreadyEndsTheWaitWithoutALoad() = runTest {
        assertTrue(awaitFloor(CompletableDeferred(), millis = 4_000, settleMillis = 100) { true })
        assertEquals(100, testScheduler.currentTime, "when the wait ended")
    }

    @Test fun noFloorInTimeIsFalse() = runTest {
        assertFalse(awaitFloor(CompletableDeferred(), millis = 4_000, settleMillis = 100) { false })
        assertEquals(4_000, testScheduler.currentTime, "when the wait ended")
    }
}
