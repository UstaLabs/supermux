package dev.supermux.android.session

import dev.supermux.android.workspace.WorkspaceChatPaneTestIds
import dev.supermux.ui.TestIds
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one case of the old `SessionListRailUiContractTest` that could not move to `:ui` with the
 * list (cluster F4): it binds Android's own chat-pane test ids to the shared vocabulary.
 */
class WorkspaceChatPaneTestIdsTest {

    @Test
    fun workspace_chat_pane_carries_shared_chat_view_test_id() {
        assertEquals(TestIds.CHAT_VIEW, WorkspaceChatPaneTestIds.CHAT_VIEW)
        assertEquals("chat-view", WorkspaceChatPaneTestIds.CHAT_VIEW)
        assertEquals("view_chat", WorkspaceChatPaneTestIds.VIEW_CHAT)
    }
}
