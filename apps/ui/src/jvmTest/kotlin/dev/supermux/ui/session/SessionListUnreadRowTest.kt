package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test

/**
 * End-to-end row wiring: the shared [WorkspaceRow] + last message ts + lastRead map must paint the
 * correct leading rail icon for unread / working / read.
 *
 * Was desktop's `SessionListUnreadRowTest` over `SessionListPanel` (dead, deleted in F4); the same
 * four cases now drive the shared row under [dev.supermux.ui.adaptive.InputMode.Pointer], which is
 * the sidebar row that carries this wiring.
 *
 * Row chrome merges semantics into the parent clickable, so rail test tags are looked up with
 * [useUnmergedTree] (same pattern as other list UI tests).
 */
@OptIn(ExperimentalTestApi::class)
class SessionListUnreadRowTest {

    private fun session(id: String, name: String = id) =
        SessionInfo(
            id = id,
            name = name,
            workdir = "/home/u/proj",
            agent = "claude",
            userStatus = "in_progress",
        )

    private fun log(ts: String) =
        LogEntry(id = "m-$ts", ts = ts, direction = "outbound", text = "hello")

    @Composable
    private fun Row(
        name: String,
        active: Boolean,
        lastMessage: LogEntry?,
        lastReadAt: String?,
        agentState: Map<String, AgentStatus> = emptyMap(),
    ) {
        val w = workspaceDto(
            id = "w1",
            name = name,
            views = listOf(workspaceChatView("v1", "s1", "w1")),
            primarySessionId = "s1",
        )
        val model = deriveWorkspaceRow(
            w = w,
            sessionsById = mapOf("s1" to session("s1", name)),
            agentState = agentState,
            lastBySession = mapOf("s1" to lastMessage),
            lastRead = lastReadAt?.let { mapOf("s1" to it) } ?: emptyMap(),
            home = "/home/u",
            selectedSessionId = null,
        )
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorkspaceRow(
                model = model,
                active = active,
                preview = lastMessage,
                lastReadAt = lastReadAt,
                onClick = {},
            )
        }
    }

    @Test fun unread_row_shows_green_rail_icon() = runComposeUiTest {
        setContent {
            Row(
                "Unread Chat", active = false,
                lastMessage = log("2026-08-01T12:00:00.000Z"),
                lastReadAt = "2026-08-01T11:00:00.000Z",
            )
        }
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("unread", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun read_row_shows_neutral_not_unread() = runComposeUiTest {
        setContent {
            Row(
                "Read Chat", active = false,
                lastMessage = log("2026-08-01T12:00:00.000Z"),
                lastReadAt = "2026-08-01T12:00:00.000Z",
            )
        }
        onNodeWithTag("session_rail_neutral", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun working_row_shows_spinner_not_unread_despite_newer_message() = runComposeUiTest {
        setContent {
            Row(
                "Working Chat", active = false,
                lastMessage = log("2026-08-01T12:00:00.000Z"),
                lastReadAt = "2026-08-01T11:00:00.000Z",
                agentState = mapOf(
                    "s1" to AgentStatus(phase = "running", state = "working", working = true),
                ),
            )
        }
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("unread", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun active_row_does_not_show_unread() = runComposeUiTest {
        setContent {
            Row(
                "Active Chat", active = true,
                lastMessage = log("2026-08-01T12:00:00.000Z"),
                lastReadAt = null,
            )
        }
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
    }
}
