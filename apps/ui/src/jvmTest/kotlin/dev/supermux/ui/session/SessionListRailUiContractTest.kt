package dev.supermux.ui.session

import dev.supermux.session.SessionListRailIndicator
import dev.supermux.session.sessionListRailIndicator
import dev.supermux.session.sessionListShowsUnread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-contract tests for the session-list leading rail.
 *
 * Full Compose UI tests are [SessionStatusRailTest]; these lock the decision matrix the shared
 * [SessionStatusRail] composable must paint (working spinner / green unread / gray idle) for the
 * list, and the test-id vocabulary both hosts' rows use. Moved out of `:android` with the list in
 * cluster F4 — only the Android chat-pane id check stayed behind, since that id is Android's.
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

    @Test
    fun workspace_row_test_ids_match_desktop() {
        assertEquals("workspace_row_abc", WorkspaceListTestIds.row("abc"))
        assertEquals("workspace-children-abc", WorkspaceListTestIds.children("abc"))
        assertEquals("workspace-multiagent-abc", WorkspaceListTestIds.multiAgent("abc"))
        assertEquals("archived_workspace_abc", WorkspaceListTestIds.archived("abc"))
        assertEquals("archived_fold", WorkspaceListTestIds.ARCHIVED_FOLD)
        assertEquals("workspaces_list", WorkspaceListTestIds.LIST)
        assertEquals("workspace_row_new_chat", WorkspaceListTestIds.ROW_NEW_CHAT)
    }
}
