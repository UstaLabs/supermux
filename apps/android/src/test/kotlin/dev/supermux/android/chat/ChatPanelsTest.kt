package dev.supermux.android.chat

import dev.supermux.state.RetainedUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The session-only chat's tab through a configuration change (card #89). A recreated [ChatScreen]
 * reads its [ChatPanels] from the fleet's retained state again, under [chatPanelsKey]: the same
 * session finds the tab it was on (and the panels kept composed beside it), another session opens
 * on its chat. ChatScreen itself needs an activity to draw, which these JVM tests do not have.
 */
class ChatPanelsTest {

    /** The fleet's retained state, which the view model keeps through a recreation. */
    private fun retained() = RetainedUiState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun a_recreated_chat_screen_finds_the_tab_it_was_on() {
        val retained = retained()
        val before = retained.getOrPut(chatPanelsKey("s1")) { ChatPanels() }
        before.active = SessionPanel.Terminal
        before.opened = before.opened + SessionPanel.Terminal

        val after = retained.getOrPut(chatPanelsKey("s1")) { ChatPanels() }

        assertEquals(SessionPanel.Terminal, after.active)
        assertEquals(setOf(SessionPanel.Chat, SessionPanel.Terminal), after.opened)
    }

    @Test fun another_session_opens_on_its_chat() {
        val retained = retained()
        retained.getOrPut(chatPanelsKey("s1")) { ChatPanels() }.active = SessionPanel.Display

        val other = retained.getOrPut(chatPanelsKey("s2")) { ChatPanels() }

        assertEquals(SessionPanel.Chat, other.active)
        assertEquals(setOf(SessionPanel.Chat), other.opened)
    }
}
