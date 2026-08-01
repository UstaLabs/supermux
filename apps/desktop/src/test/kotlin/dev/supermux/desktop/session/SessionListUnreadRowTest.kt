package dev.supermux.desktop.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import kotlin.test.Test

/**
 * End-to-end row wiring: [SessionListPanel] + last message ts + lastRead map must paint the
 * correct leading rail icon for unread / working / read.
 *
 * Row chrome merges semantics into the parent clickable, so rail test tags are looked up with
 * [useUnmergedTree] (same pattern as other desktop list UI tests).
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

    @Test fun unread_row_shows_green_rail_icon() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionListPanel(
                    sessions = listOf(session("s1", "Unread Chat")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    lastBySession = mapOf("s1" to log("2026-08-01T12:00:00.000Z")),
                    lastRead = mapOf("s1" to "2026-08-01T11:00:00.000Z"),
                    agentState = emptyMap(),
                )
            }
        }
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("unread", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun read_row_shows_neutral_not_unread() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionListPanel(
                    sessions = listOf(session("s1", "Read Chat")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    lastBySession = mapOf("s1" to log("2026-08-01T12:00:00.000Z")),
                    lastRead = mapOf("s1" to "2026-08-01T12:00:00.000Z"),
                    agentState = emptyMap(),
                )
            }
        }
        onNodeWithTag("session_rail_neutral", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun working_row_shows_spinner_not_unread_despite_newer_message() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionListPanel(
                    sessions = listOf(session("s1", "Working Chat")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    lastBySession = mapOf("s1" to log("2026-08-01T12:00:00.000Z")),
                    lastRead = mapOf("s1" to "2026-08-01T11:00:00.000Z"),
                    agentState = mapOf(
                        "s1" to AgentStatus(
                            phase = "running",
                            state = "working",
                            working = true,
                        ),
                    ),
                )
            }
        }
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("unread", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun active_row_does_not_show_unread() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionListPanel(
                    sessions = listOf(session("s1", "Active Chat")),
                    home = "/home/u",
                    activeId = "s1",
                    onOpen = {},
                    lastBySession = mapOf("s1" to log("2026-08-01T12:00:00.000Z")),
                    lastRead = emptyMap(),
                    agentState = emptyMap(),
                )
            }
        }
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
    }
}
