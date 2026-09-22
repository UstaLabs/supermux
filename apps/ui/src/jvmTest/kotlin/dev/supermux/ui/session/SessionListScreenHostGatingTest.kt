package dev.supermux.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The multi-host gating that stayed on the screen when the chips themselves moved to `:ui`
 * (desktop's `SessionListPanelHostGatingTest`, moved by name): the chip row appears only with more
 * than one host, and a per-row host badge only while the list is unfiltered
 * (`showRowHostBadge = multiHost && hostFilter == null`). Plus the host rename/forget rows Android
 * wired into the chips, which the shared screen now feeds from [SessionListActions] on both hosts.
 */
@OptIn(ExperimentalTestApi::class)
class SessionListScreenHostGatingTest {

    private fun session(id: String, wd: String = "/home/u/proj") =
        SessionInfo(id = id, name = "sess-$id", workdir = wd, agent = "claude")

    private val twoHosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1L),
    )

    @Test fun chipRowAndBadges_renderInMultiHostMode() = runComposeUiTest {
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
        onNodeWithTag("host_filter_chips").assertIsDisplayed()
        onNodeWithTag("host_chip_all").assertIsDisplayed()
        onNodeWithTag("host_chip_h1").assertIsDisplayed()
        onNodeWithTag("host_chip_add").assertIsDisplayed()
        // Decorative inside the clickable (merged) session row, so it lives in the unmerged tree.
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun chipRowHidden_withASingleHost() = runComposeUiTest {
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
        // One host → no chips, no badges (single-host desktop looks exactly as before).
        onNodeWithTag("host_filter_chips").assertDoesNotExist()
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
        // Filter pills stay; the per-row badge is redundant once a specific host is selected.
        onNodeWithTag("host_filter_chips").assertIsDisplayed()
        onNodeWithTag("host_chip_h1").assertIsDisplayed()
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun the_filter_chip_menu_renames_and_forgets_through_the_holder() = runComposeUiTest {
        var renamed: Pair<String, String>? = null
        var forgot: String? = null
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = listOf(session("s1")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1"),
                actions = SessionListActions(
                    renameHost = { id, name -> renamed = id to name },
                    forgetHost = { forgot = it },
                ),
            )
        }
        onNodeWithTag("host_chip_press_h2").performTouchInput { longClick() }
        onNodeWithText("Forget").performClick()
        onNodeWithTag("host_forget_confirm").performClick()
        assertEquals("h2", forgot)
        assertTrue(renamed == null, "forget must not also rename")

        onNodeWithTag("host_chip_press_h1").performTouchInput { longClick() }
        onNodeWithText("Rename").performClick()
        onNodeWithTag("host_rename_confirm").performClick()
        assertEquals("h1" to "MacBook", renamed)
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
