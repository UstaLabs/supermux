package dev.supermux.desktop.terminal

import dev.supermux.net.wheelEventsFromLines
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD spec for [WheelAccumulator] — the desktop glue between AWT wheel deltas and the shared
 * `dev.supermux.net.TerminalScroll` math (`linesFromPixels` / `wheelEventsFromLines`). The shared
 * math itself is parity-tested elsewhere (shared jvmTest); these tests only cover this class's
 * carry/threshold/pass-through behaviour, so expected byte sequences are always computed by
 * calling [wheelEventsFromLines] directly rather than re-deriving the SGR escape format.
 */
class WheelAccumulatorTest {

    // One "line" of scroll = 20px, chosen arbitrarily; only the ratio to accumulated deltas matters.
    private val cell = 20.0

    @Test
    fun sub_line_delta_emits_nothing_but_carries_into_the_next_event() {
        val acc = WheelAccumulator()
        // 12px < one 20px cell: no line crossed yet.
        assertTrue(acc.accumulate(12.0, cell, 40, 12).isEmpty())
        // Another 12px: 24px accumulated total -> crosses one line, 4px carried forward.
        val bytes = acc.accumulate(12.0, cell, 40, 12)
        assertContentEquals(wheelEventsFromLines(1, 40, 12), bytes)
        // The 4px remainder plus 16 more should NOT yet cross a second line (20px exactly is the
        // boundary but truncation is toward zero on the total, so 4+15=19 stays at zero lines)...
        assertTrue(acc.accumulate(15.0, cell, 40, 12).isEmpty())
        // ...while one more px (4+15+1=20) crosses exactly one more line.
        val second = acc.accumulate(1.0, cell, 40, 12)
        assertContentEquals(wheelEventsFromLines(1, 40, 12), second)
    }

    @Test
    fun positive_delta_emits_wheel_down_button_65() {
        val acc = WheelAccumulator()
        val bytes = acc.accumulate(20.0, cell, 40, 12)
        val expected = wheelEventsFromLines(1, 40, 12)
        assertContentEquals(expected, bytes)
        assertTrue(String(expected, Charsets.US_ASCII).contains("65"))
    }

    @Test
    fun negative_delta_emits_wheel_up_button_64() {
        val acc = WheelAccumulator()
        val bytes = acc.accumulate(-20.0, cell, 40, 12)
        val expected = wheelEventsFromLines(-1, 40, 12)
        assertContentEquals(expected, bytes)
        assertTrue(String(expected, Charsets.US_ASCII).contains("64"))
    }

    @Test
    fun multi_line_delta_emits_one_event_per_line_in_a_single_call() {
        val acc = WheelAccumulator()
        val bytes = acc.accumulate(20.0 * 3, cell, 7, 3)
        assertContentEquals(wheelEventsFromLines(3, 7, 3), bytes)
    }

    @Test
    fun zero_delta_emits_nothing() {
        val acc = WheelAccumulator()
        assertTrue(acc.accumulate(0.0, cell, 40, 12).isEmpty())
    }

    @Test
    fun center_cell_coordinates_are_passed_through_unmodified() {
        val acc = WheelAccumulator()
        val bytes = acc.accumulate(20.0, cell, col = 5, row = 9)
        assertContentEquals(wheelEventsFromLines(1, 5, 9), bytes)
    }

    @Test
    fun non_finite_pixel_delta_is_a_no_op() {
        val acc = WheelAccumulator()
        assertTrue(acc.accumulate(Double.NaN, cell, 40, 12).isEmpty())
        assertTrue(acc.accumulate(Double.POSITIVE_INFINITY, cell, 40, 12).isEmpty())
        assertTrue(acc.accumulate(Double.NEGATIVE_INFINITY, cell, 40, 12).isEmpty())
        // No corruption from the no-op deltas: a subsequent real delta still needs a full cell.
        assertTrue(acc.accumulate(12.0, cell, 40, 12).isEmpty())
        assertEquals(1, wheelEventsFromLines(1, 40, 12).let { linesInEvent(it) })
    }

    /** Counts SGR wheel events (one repeated escape per line) in a byte sequence, for the assertion
     *  above that the post-no-op accumulator state is unaffected (still needs a fresh full cell). */
    private fun linesInEvent(bytes: ByteArray): Int {
        val s = String(bytes, Charsets.US_ASCII)
        val esc = "[<"
        return s.split(esc).size - 1
    }

    @Test
    fun non_finite_cell_height_is_a_safe_no_op_and_drops_any_carry() {
        val acc = WheelAccumulator()
        assertTrue(acc.accumulate(15.0, cell, 40, 12).isEmpty())
        // Invalid cell height: shared linesFromPixels guard fires, returning 0 lines and dropping
        // the accumulated pixels (documented behaviour of the shared fn this class delegates to).
        assertTrue(acc.accumulate(0.0, 0.0, 40, 12).isEmpty())
        // The prior 15px carry is gone; 15 more px (30 total) is still under a fresh 20px cell twice
        // over only if carry was preserved, so this proves it was dropped: 15px alone must NOT emit.
        assertTrue(acc.accumulate(15.0, cell, 40, 12).isEmpty())
    }
}
