package dev.supermux.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import kotlin.test.Test

/**
 * The multi-host gating on the screen (desktop's `SessionListPanelHostGatingTest`, moved by name):
 * a per-row host badge appears only with more than one host and only while the list is unfiltered
 * (`showRowHostBadge = multiHost && hostFilter == null`), and a selected host filters the rows.
 * The host switch itself lives in the sidebar footer ([dev.supermux.ui.host.HostSwitcher]).
 */
@OptIn(ExperimentalTestApi::class)
class SessionListScreenHostGatingTest {

    private fun session(id: String, wd: String = "/home/u/proj") =
        SessionInfo(id = id, name = "sess-$id", workdir = wd, agent = "claude")

    private val twoHosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1L),
    )

    @Test fun badges_renderInMultiHostMode() = runComposeUiTest {
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
            )
        }
        // Decorative inside the clickable (merged) session row, so it lives in the unmerged tree.
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun badgesHidden_withASingleHost() = runComposeUiTest {
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = listOf(session("s1")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = listOf(twoHosts[0]),
                sessionHost = mapOf("s1" to "h1"),
            )
        }
        // One host → no badges: every row is that host.
        onNodeWithTag("host_badge_h1").assertDoesNotExist()
    }

    @Test fun rowBadgeHidden_whenHostPillSelected() = runComposeUiTest {
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
                hostFilter = "h1",
            )
        }
        // The per-row badge is redundant once a specific host is selected.
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun a_selected_host_filters_the_rows() = runComposeUiTest {
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
                hostFilter = "h1",
            )
        }
        onNodeWithText("sess-s1").assertIsDisplayed()
        onNodeWithText("sess-s2").assertDoesNotExist()
    }
}
