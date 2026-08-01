package dev.supermux.session

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionListRailTest {
    @Test fun working_wins_over_unread() {
        assertEquals(
            SessionListRailIndicator.Working,
            sessionListRailIndicator(working = true, unread = true),
        )
    }

    @Test fun working_without_unread_is_still_working() {
        assertEquals(
            SessionListRailIndicator.Working,
            sessionListRailIndicator(working = true, unread = false),
        )
    }

    @Test fun idle_unread_is_unread() {
        assertEquals(
            SessionListRailIndicator.Unread,
            sessionListRailIndicator(working = false, unread = true),
        )
    }

    @Test fun idle_read_is_other() {
        assertEquals(
            SessionListRailIndicator.Other,
            sessionListRailIndicator(working = false, unread = false),
        )
    }

    @Test fun full_matrix_matches_product_rule() {
        // (working, unread) → expected
        val cases = listOf(
            Triple(true, true, SessionListRailIndicator.Working),
            Triple(true, false, SessionListRailIndicator.Working),
            Triple(false, true, SessionListRailIndicator.Unread),
            Triple(false, false, SessionListRailIndicator.Other),
        )
        for ((working, unread, expected) in cases) {
            assertEquals(
                expected,
                sessionListRailIndicator(working, unread),
                "working=$working unread=$unread",
            )
        }
    }

    @Test fun showsUnread_requires_idle_inactive_and_newer_message() {
        val msg = "2026-08-01T12:00:00.000Z"
        val older = "2026-08-01T11:00:00.000Z"
        assertEquals(true, sessionListShowsUnread(active = false, working = false, lastMessageTs = msg, lastReadAt = older))
        assertEquals(true, sessionListShowsUnread(active = false, working = false, lastMessageTs = msg, lastReadAt = null))
        assertEquals(false, sessionListShowsUnread(active = true, working = false, lastMessageTs = msg, lastReadAt = older))
        assertEquals(false, sessionListShowsUnread(active = false, working = true, lastMessageTs = msg, lastReadAt = older))
        assertEquals(false, sessionListShowsUnread(active = false, working = false, lastMessageTs = msg, lastReadAt = msg))
        assertEquals(false, sessionListShowsUnread(active = false, working = false, lastMessageTs = null, lastReadAt = null))
    }

    @Test fun showsUnread_then_rail_indicator_is_unread() {
        val show = sessionListShowsUnread(
            active = false,
            working = false,
            lastMessageTs = "2026-08-01T12:00:00.000Z",
            lastReadAt = "2026-08-01T11:00:00.000Z",
        )
        assertEquals(true, show)
        assertEquals(SessionListRailIndicator.Unread, sessionListRailIndicator(working = false, unread = show))
    }
}
