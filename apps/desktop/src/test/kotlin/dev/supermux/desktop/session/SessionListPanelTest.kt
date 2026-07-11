package dev.supermux.desktop.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The in-list "Start a new session" affordance ([NewSessionListRow]) rendered by [SessionListPanel]
 * — the desktop port of Android's `NewSessionListRow`. It must render + fire `onNewSession` in BOTH
 * the populated list and the empty (zero-session) state so session creation is always reachable.
 */
@OptIn(ExperimentalTestApi::class)
class SessionListPanelTest {

    private fun session(id: String) =
        SessionInfo(id = id, name = "sess-$id", workdir = "/home/u/proj", agent = "claude")

    @Test fun newSessionRow_rendersAndFires_inPopulatedList() = runComposeUiTest {
        var fired = false
        setContent {
            SessionListPanel(
                sessions = listOf(session("a"), session("b")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNewSession = { fired = true },
            )
        }
        onNodeWithTag("new_session_row").assertIsDisplayed()
        onNodeWithText("Start a new session").assertIsDisplayed()
        onNodeWithTag("new_session_row").performClick()
        assertTrue(fired, "clicking the new-session row should fire onNewSession")
    }

    @Test fun newSessionRow_rendersAndFires_inEmptyState() = runComposeUiTest {
        var fired = false
        setContent {
            SessionListPanel(
                sessions = emptyList(),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNewSession = { fired = true },
            )
        }
        onNodeWithTag("new_session_row").assertIsDisplayed()
        onNodeWithTag("new_session_row").performClick()
        assertTrue(fired, "clicking the new-session row should fire onNewSession in the empty state")
    }
}
