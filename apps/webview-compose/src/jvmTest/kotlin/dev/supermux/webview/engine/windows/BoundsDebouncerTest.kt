package dev.supermux.webview.engine.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundsDebouncerTest {
    private val ms = 1_000_000L
    private fun rect(x: Int, y: Int, w: Int, h: Int) = BoundsDebouncer.Rect(x, y, w, h)

    @Test fun theFirstBoundsApplyAtOnce() {
        val d = BoundsDebouncer(150 * ms)
        assertEquals(rect(0, 0, 800, 600), d.update(rect(0, 0, 800, 600), 0))
    }

    @Test fun aMoveAppliesAtOnce() {
        val d = BoundsDebouncer(150 * ms)
        d.update(rect(0, 0, 800, 600), 0)
        assertEquals(rect(10, 20, 800, 600), d.update(rect(10, 20, 800, 600), 1 * ms))
    }

    @Test fun unchangedBoundsApplyNothing() {
        val d = BoundsDebouncer(150 * ms)
        d.update(rect(0, 0, 800, 600), 0)
        assertNull(d.update(rect(0, 0, 800, 600), 5 * ms))
    }

    @Test fun aResizeWaitsUntilTheSizeSettles() {
        val d = BoundsDebouncer(150 * ms)
        d.update(rect(0, 0, 800, 600), 0)
        assertNull(d.update(rect(0, 0, 700, 500), 0))
        assertTrue(d.hasPending)
        assertNull(d.poll(100 * ms))
        assertEquals(rect(0, 0, 700, 500), d.poll(150 * ms))
        assertFalse(d.hasPending)
        assertNull(d.poll(400 * ms))
    }

    @Test fun aDragKeepsPostponingAndAppliesOnceAtTheEnd() {
        val d = BoundsDebouncer(150 * ms)
        d.update(rect(0, 0, 800, 600), 0)
        var applied = 0
        for (step in 1..30) {
            val now = step * 16 * ms
            if (d.update(rect(0, 0, 800 - step * 5, 600 - step * 3), now) != null) applied++
            if (d.poll(now) != null) applied++
        }
        assertEquals(0, applied)
        assertEquals(rect(0, 0, 650, 510), d.poll(30 * 16 * ms + 150 * ms))
        assertNull(d.poll(30 * 16 * ms + 300 * ms))
    }

    @Test fun aMoveDuringAWaitKeepsTheWaitAndTakesTheLatestPosition() {
        val d = BoundsDebouncer(150 * ms)
        d.update(rect(0, 0, 800, 600), 0)
        d.update(rect(0, 0, 700, 500), 0)
        assertNull(d.update(rect(5, 5, 700, 500), 100 * ms))
        assertEquals(rect(5, 5, 700, 500), d.poll(150 * ms))
    }

    @Test fun resizingBackToTheAppliedSizeCancelsTheWait() {
        val d = BoundsDebouncer(150 * ms)
        d.update(rect(0, 0, 800, 600), 0)
        d.update(rect(0, 0, 700, 500), 0)
        assertNull(d.update(rect(0, 0, 800, 600), 50 * ms))
        assertFalse(d.hasPending)
        assertNull(d.poll(500 * ms))
    }
}
