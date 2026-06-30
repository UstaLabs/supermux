package dev.supermux.net

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the web reference suite (src/web-app/src/lib/touch-scroll.test.ts) so
 * the native math stays in lockstep with the PWA's.
 */
class TerminalScrollTest {
    private fun assertClose(expected: Double, actual: Double, eps: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= eps, "expected ~$expected but was $actual")
    }

    // --- linesFromPixels ---

    @Test fun drag_shorter_than_one_row_scrolls_nothing_but_carries_the_pixels_forward() {
        val s = linesFromPixels(10.0, 15.6)
        assertEquals(0, s.lines)
        assertClose(10.0, s.remainderPx)
    }

    @Test fun slow_drags_accumulate_across_moves_instead_of_being_rounded_away() {
        val cell = 15.6
        var accum = 0.0
        var scrolled = 0
        repeat(3) {
            accum += 6.0
            val r = linesFromPixels(accum, cell)
            accum = r.remainderPx
            scrolled += r.lines
        }
        assertEquals(1, scrolled)
        assertClose(18.0 - 15.6, accum)
    }

    @Test fun a_fast_swipe_scrolls_multiple_rows_at_once() {
        val s = linesFromPixels(50.0, 15.6)
        assertEquals(3, s.lines) // trunc(50/15.6) == 3
        assertClose(50.0 - 3 * 15.6, s.remainderPx)
    }

    @Test fun an_exact_multiple_leaves_no_remainder() {
        val s = linesFromPixels(31.2, 15.6)
        assertEquals(2, s.lines)
        assertClose(0.0, s.remainderPx)
    }

    @Test fun negative_pixels_scroll_back_into_history_symmetrically() {
        val s = linesFromPixels(-50.0, 15.6)
        assertEquals(-3, s.lines)
        assertClose(-50.0 + 3 * 15.6, s.remainderPx)
    }

    @Test fun a_non_positive_or_non_finite_cell_height_is_a_safe_noop() {
        for (bad in listOf(0.0, -15.6, Double.NaN, Double.POSITIVE_INFINITY)) {
            val r = linesFromPixels(100.0, bad)
            assertEquals(0, r.lines)
            assertEquals(0.0, r.remainderPx)
        }
    }

    // --- wheelEventsFromLines ---

    @Test fun no_scroll_delta_emits_no_wheel_events() {
        assertEquals("", wheelEventsFromLines(0, 10, 5).decodeToString())
    }

    @Test fun scrolling_back_into_history_emits_one_sgr_wheel_up_per_row() {
        assertEquals("\u001B[<64;10;5M", wheelEventsFromLines(-1, 10, 5).decodeToString())
    }

    @Test fun scrolling_toward_newer_output_emits_one_sgr_wheel_down_per_row() {
        assertEquals("\u001B[<65;10;5M", wheelEventsFromLines(1, 10, 5).decodeToString())
    }

    @Test fun a_multi_row_swipe_emits_one_wheel_event_per_row_matching_the_line_sign() {
        assertEquals("\u001B[<64;1;1M".repeat(3), wheelEventsFromLines(-3, 1, 1).decodeToString())
        assertEquals("\u001B[<65;8;4M".repeat(2), wheelEventsFromLines(2, 8, 4).decodeToString())
    }

    @Test fun pointer_coordinates_are_clamped_to_a_valid_1_based_cell() {
        // Callers floor pixel coords to Ints before calling; sub-1 values clamp to 1
        // (tmux ignores 0/out-of-range).
        assertEquals("\u001B[<64;1;1M", wheelEventsFromLines(-1, 0, 0).decodeToString())
        assertEquals("\u001B[<64;3;9M", wheelEventsFromLines(-1, 3, 9).decodeToString())
    }
}
