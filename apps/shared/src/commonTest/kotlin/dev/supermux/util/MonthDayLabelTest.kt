package dev.supermux.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [shortMonthDayLabel] — the "resets Jul 14" half of the Usage screen's reset line (cluster E6).
 *
 * It replaces `Instant.ofEpochMilli(ms).atZone(systemDefault()).month.getDisplayName(SHORT, US)`,
 * which `:ui` commonMain cannot call. The offset is passed explicitly here so the expectations are
 * the same on every target.
 */
class MonthDayLabelTest {

    private val DAY = 86_400_000L

    @Test fun formats_short_month_and_day_at_utc() {
        // 2026-07-14T00:00:00Z
        assertEquals("Jul 14", shortMonthDayLabel(1_783_987_200_000L, offsetMs = 0L))
    }

    @Test fun day_of_month_is_not_zero_padded() {
        // 2026-08-01T00:00:00Z
        assertEquals("Aug 1", shortMonthDayLabel(1_785_542_400_000L, offsetMs = 0L))
    }

    @Test fun the_epoch_itself_is_jan_1() {
        assertEquals("Jan 1", shortMonthDayLabel(0L, offsetMs = 0L))
    }

    @Test fun a_negative_local_time_still_lands_on_the_previous_day() {
        // One hour before the epoch, read in UTC: 1969-12-31.
        assertEquals("Dec 31", shortMonthDayLabel(-3_600_000L, offsetMs = 0L))
    }

    @Test fun the_offset_can_push_the_label_across_midnight() {
        // 2026-07-13T23:00:00Z is still Jul 13 in UTC but Jul 14 two hours east.
        val ms = 1_783_987_200_000L - 3_600_000L
        assertEquals("Jul 13", shortMonthDayLabel(ms, offsetMs = 0L))
        assertEquals("Jul 14", shortMonthDayLabel(ms, offsetMs = 2 * 3_600_000L))
    }

    @Test fun every_month_name_is_the_short_english_one() {
        val names = (0..11).map { m ->
            // 2026-<m+1>-15, built by walking whole days from 2026-01-15.
            shortMonthDayLabel(daysFor(m), offsetMs = 0L).substringBefore(' ')
        }
        assertEquals(
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
            names,
        )
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
