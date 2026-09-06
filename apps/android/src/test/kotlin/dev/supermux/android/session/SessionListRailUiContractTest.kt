package dev.supermux.android.session

import dev.supermux.ui.session.WorkspaceListTestIds

import dev.supermux.android.workspace.WorkspaceChatPaneTestIds
import dev.supermux.session.SessionListRailIndicator
import dev.supermux.session.sessionListRailIndicator
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.ui.TestIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-contract tests for the Android session-list leading rail.
 *
 * Full Compose UI tests live in `:ui` (`dev.supermux.ui.session.SessionStatusRailTest`) and share
 * the same pure helpers; these Android unit tests lock the decision matrix the shared
 * `dev.supermux.ui.session.SessionStatusRail` composable must paint (working spinner / green
 * unread / gray idle) for the phone list this module owns.
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

    @Test
    fun workspace_chat_pane_carries_shared_chat_view_test_id() {
        assertEquals(TestIds.CHAT_VIEW, WorkspaceChatPaneTestIds.CHAT_VIEW)
        assertEquals("chat-view", WorkspaceChatPaneTestIds.CHAT_VIEW)
        assertEquals("view_chat", WorkspaceChatPaneTestIds.VIEW_CHAT)
    }
}
