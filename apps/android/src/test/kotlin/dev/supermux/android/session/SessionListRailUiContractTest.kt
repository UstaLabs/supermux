package dev.supermux.android.session

import dev.supermux.session.SessionListRailIndicator
import dev.supermux.session.sessionListRailIndicator
import dev.supermux.session.sessionListShowsUnread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-contract tests for the Android session-list leading rail.
 *
 * Full Compose UI tests live on desktop ([SessionStatusRailTest]) and share the same pure
 * helpers; Android unit tests lock the decision matrix the [SessionStatusRail] composable
 * must paint (working spinner / green unread / gray idle).
 */
class SessionListRailUiContractTest {

    @Test fun working_indicator_hides_unread_mark() {
        assertEquals(
            SessionListRailIndicator.Working,
            sessionListRailIndicator(working = true, unread = true),
        )
        assertFalse(
            sessionListShowsUnread(
                active = false,
                working = true,
                lastMessageTs = "2026-08-01T12:00:00.000Z",
                lastReadAt = "2026-08-01T11:00:00.000Z",
            ),
        )
    }

    @Test fun idle_with_newer_message_shows_unread_indicator() {
        assertTrue(
            sessionListShowsUnread(
                active = false,
                working = false,
                lastMessageTs = "2026-08-01T12:00:00.000Z",
                lastReadAt = "2026-08-01T11:00:00.000Z",
            ),
        )
        assertEquals(
            SessionListRailIndicator.Unread,
            sessionListRailIndicator(working = false, unread = true),
        )
    }

    @Test fun idle_read_shows_other_neutral_or_git() {
        assertFalse(
            sessionListShowsUnread(
                active = false,
                working = false,
                lastMessageTs = "2026-08-01T12:00:00.000Z",
                lastReadAt = "2026-08-01T12:00:00.000Z",
            ),
        )
        assertEquals(
            SessionListRailIndicator.Other,
            sessionListRailIndicator(working = false, unread = false),
        )
    }

    @Test fun selected_row_never_shows_unread_mark() {
        assertFalse(
            sessionListShowsUnread(
                active = true,
                working = false,
                lastMessageTs = "2026-08-01T12:00:00.000Z",
                lastReadAt = null,
            ),
        )
    }
}
