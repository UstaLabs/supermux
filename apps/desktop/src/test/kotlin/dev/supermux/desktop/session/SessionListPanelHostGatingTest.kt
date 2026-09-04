package dev.supermux.desktop.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import kotlin.test.Test

/**
 * The multi-host gating that stayed on the desktop side when the chips themselves moved to `:ui`:
 * [SessionListPanel] shows the chip row only with more than one host, and a per-row host badge only
 * while the list is unfiltered (`showRowHostBadge = multiHost && hostFilter == null`). These are the
 * two cases from the old `host/FleetListUiTest` that the shared components cannot answer alone.
 */
@OptIn(ExperimentalTestApi::class)
class SessionListPanelHostGatingTest {

    private fun session(id: String, wd: String = "/home/u/proj") =
        SessionInfo(id = id, name = "sess-$id", workdir = wd, agent = "claude")

    private val twoHosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1L),
    )

    @Test fun chipRowAndBadges_renderInMultiHostMode() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
            )
        }
        onNodeWithTag("host_filter_chips").assertIsDisplayed()
        onNodeWithTag("host_chip_all").assertIsDisplayed()
        onNodeWithTag("host_chip_h1").assertIsDisplayed()
        onNodeWithTag("host_chip_add").assertIsDisplayed()
        // Decorative inside the clickable (merged) session row, so it lives in the unmerged tree.
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun chipRowHidden_withASingleHost() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = listOf(twoHosts[0]),
                sessionHost = mapOf("s1" to "h1"),
            )
        }
        // One host → no chips, no badges (single-host desktop looks exactly as before).
        onNodeWithTag("host_filter_chips").assertDoesNotExist()
        onNodeWithTag("host_badge_h1").assertDoesNotExist()
    }

    @Test fun rowBadgeHidden_whenHostPillSelected() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
                hostFilter = "h1",
            )
        }
        // Filter pills stay; the per-row badge is redundant once a specific host is selected.
        onNodeWithTag("host_filter_chips").assertIsDisplayed()
        onNodeWithTag("host_chip_h1").assertIsDisplayed()
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertDoesNotExist()
    }
}
