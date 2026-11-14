package dev.supermux.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [localMonthDay] — the date half of the Usage screen's "resets Jul 14" line (cluster E6).
 *
 * It replaces `Instant.ofEpochMilli(ms).atZone(systemDefault())`, which `:ui` commonMain cannot
 * call. The offset is passed explicitly here so the expectations are the same on every target.
 */
class MonthDayLabelTest {

    private val DAY = 86_400_000L

    @Test fun month_and_day_at_utc() {
        // 2026-07-14T00:00:00Z
        assertEquals(7 to 14, localMonthDay(1_783_987_200_000L, offsetMs = 0L))
    }

    @Test fun the_first_of_a_month() {
        // 2026-08-01T00:00:00Z
        assertEquals(8 to 1, localMonthDay(1_785_542_400_000L, offsetMs = 0L))
    }

    @Test fun the_epoch_itself_is_jan_1() {
        assertEquals(1 to 1, localMonthDay(0L, offsetMs = 0L))
    }

    @Test fun a_negative_local_time_still_lands_on_the_previous_day() {
        // One hour before the epoch, read in UTC: 1969-12-31.
        assertEquals(12 to 31, localMonthDay(-3_600_000L, offsetMs = 0L))
    }

    @Test fun the_offset_can_push_the_day_across_midnight() {
        // 2026-07-13T23:00:00Z is still Jul 13 in UTC but Jul 14 two hours east.
        val ms = 1_783_987_200_000L - 3_600_000L
        assertEquals(7 to 13, localMonthDay(ms, offsetMs = 0L))
        assertEquals(7 to 14, localMonthDay(ms, offsetMs = 2 * 3_600_000L))
    }

    @Test fun every_month_comes_out_in_order() {
        // 2026-<m+1>-15, built by walking whole days from 2026-01-15.
        assertEquals((1..12).map { it to 15 }, (0..11).map { localMonthDay(daysFor(it), offsetMs = 0L) })
    }

    /** Epoch millis for the 15th of month [m] (0-based) in 2026, at UTC midnight. */
    private fun daysFor(m: Int): Long {
        val lengths = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var days = 0L
        for (i in 0 until m) days += lengths[i]
        // 2026-01-15T00:00:00Z
        return 1_768_435_200_000L + days * DAY
    }
}
