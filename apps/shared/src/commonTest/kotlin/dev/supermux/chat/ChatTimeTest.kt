package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The chat time label, pinned against the `java.time` output it replaced. Every case passes an
 * explicit `offsetMs` so the suite is identical in every timezone a CI box might sit in.
 */
class ChatTimeTest {

    // 2026-09-05T12:34:56Z
    private val now = 1_788_611_696_000L
    private val utc = 0L

    @Test fun today_isJustHoursAndMinutes() {
        assertEquals("12:34", formatChatTime(now, now, utc))
        assertEquals("00:00", formatChatTime(now - 45_296_000L, now, utc)) // same day, midnight
    }

    @Test fun yesterday_isLabelled() {
        assertEquals("Yesterday 12:34", formatChatTime(now - 86_400_000L, now, utc))
    }

    @Test fun older_isADate() {
        assertEquals("2026-09-01 12:34", formatChatTime(now - 4 * 86_400_000L, now, utc))
    }

    @Test fun offsetMovesTheLocalDay() {
        // 23:34Z with a +02:00 zone is 01:34 the NEXT local day — and "today" moves with it.
        val late = now + 11 * 3_600_000L
        assertEquals("01:34", formatChatTime(late, late, 2 * 3_600_000L))
    }

    @Test fun parseChatTs_acceptsEpochSecondsMillisAndIso() {
        assertEquals(1_788_611_696_000L, parseChatTs("1788611696"))
        assertEquals(1_788_611_696_000L, parseChatTs("1788611696000"))
        assertEquals(1_788_611_696_000L, parseChatTs("2026-09-05T12:34:56Z"))
        assertEquals(1_788_611_696_000L, parseChatTs("2026-09-05T14:34:56+02:00"))
        assertEquals(1_788_611_696_123L, parseChatTs("2026-09-05T12:34:56.123Z"))
    }

    @Test fun parseChatTs_nullOnJunk() {
        assertNull(parseChatTs(null))
        assertNull(parseChatTs(""))
        assertNull(parseChatTs("not a time"))
    }

    @Test fun civilRoundTrip() {
        val days = daysFromCivil(2026, 9, 5)
        assertEquals(Triple(2026, 9, 5), civilFromDays(days))
        assertEquals(Triple(1970, 1, 1), civilFromDays(0))
    }

    @Test fun chatTimeLabel_nullWhenUnparseable() {
        assertNull(chatTimeLabel("junk", now))
        // A parseable ts always produces a label; its wording depends on the host zone, so only
        // the shape is asserted here (formatChatTime's own cases pin the wording).
        val label = chatTimeLabel("1788611696000", now)
        kotlin.test.assertTrue(label != null && label.contains(":"), "expected an HH:mm-shaped label, got $label")
    }
}
