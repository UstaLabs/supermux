package dev.supermux.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The chrome the shared [SessionListScreen] paints, and the gate it paints it behind.
 *
 * The first three cases are desktop's `SessionListPanelTest` (the dead panel's "Start a new
 * session" card + footer rail), moved by name. The rest are new: cluster E's
 * `(standalone || compact) && !topBarShown` rule applied to this screen, and where the overflow
 * destinations live in each of its two states.
 */
@OptIn(ExperimentalTestApi::class)
class SessionListScreenChromeTest {

    private fun session(id: String) =
        SessionInfo(id = id, name = "sess-$id", workdir = "/home/u/proj", agent = "claude")

    @Test fun newSessionRow_rendersAndFires_inPopulatedList() = runComposeUiTest {
        var fired = false
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
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
            SessionListScreen(
                mode = SessionListMode.Fleet,
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

    @Test fun footer_rendersAndFiresThemeToggle() = runComposeUiTest {
        var toggled = false
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = emptyList(),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                footer = {
                    SessionListFooter(
                        appearance = AppearanceMode.DARK,
                        onToggleTheme = { toggled = true },
                        onUsage = {},
                        onDevices = {},
                        onSettings = {},
                    )
                },
            )
        }
        onNodeWithTag("sidebar_footer").assertIsDisplayed()
        // Add project was removed — new session lives only in the header card.
        onNodeWithTag("sidebar_add_project").assertDoesNotExist()
        onNodeWithTag("sidebar_footer_theme").performClick()
        assertTrue(toggled)
    }

    // ── The chrome gate ───────────────────────────────────────────────────────

    @Test fun compact_paints_the_top_bar_and_the_fab() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    sessions = listOf(session("a")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    onNavigate = {},
                )
            }
        }
        onNodeWithText("supermux").assertIsDisplayed()
        onNodeWithTag("new_session_fab").assertIsDisplayed()
        onNodeWithTag("list_overflow").assertIsDisplayed()
        // The compact list is Android's: chips/new-session/group-by live INSIDE the list.
        onNodeWithTag("group_by_project").assertIsDisplayed()
        onNodeWithTag("sidebar_group_toggle").assertDoesNotExist()
    }

    @Test fun a_standalone_wide_mount_paints_the_bar_too() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Expanded) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    sessions = listOf(session("a")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    standalone = true,
                )
            }
        }
        onNodeWithTag("new_session_fab").assertIsDisplayed()
    }

    @Test fun a_caller_that_already_painted_a_bar_gets_none() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    sessions = listOf(session("a")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    onNavigate = {},
                    standalone = true,
                    topBarShown = true,
                )
            }
        }
        onNodeWithText("supermux").assertDoesNotExist()
        onNodeWithTag("new_session_fab").assertDoesNotExist()
    }

    /**
     * The list is the phone's BACK DESTINATION, not a back consumer: it must register no handler of
     * its own, or the gesture that should leave the app would be swallowed. Pinned by the absence
     * of a navigation icon in the bar it does paint.
     */
    @Test fun the_compact_bar_carries_no_back_affordance() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    sessions = listOf(session("a")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                )
            }
        }
        onNodeWithText("supermux").assertIsDisplayed()
        onNodeWithTag("nav_back").assertDoesNotExist()
        onNodeWithText("Back").assertDoesNotExist()
    }

    /**
     * Without the top bar (a tablet's 320dp sidebar) the overflow destinations would be unreachable,
     * so they move into the section header. Absent entirely where there is no router (desktop).
     */
    @Test fun the_overflow_nav_follows_the_chrome() = runComposeUiTest {
        val seen = mutableListOf<String>()
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                sessions = listOf(session("a")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNavigate = { seen += it },
            )
        }
        onNodeWithTag("list_overflow").assertIsDisplayed().performClick()
        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("list_overflow").performClick()
        onNodeWithTag("nav_archived").performClick()
        assertTrue(seen == listOf("settings", "archived"), "overflow nav fired $seen")
    }

    @Test fun no_router_means_no_overflow() = runComposeUiTest {
        setContent {
            SessionListScreen(
                home = "/home/u",
                activeId = null,
                onOpen = {},
            )
        }
        onNodeWithTag("list_overflow").assertDoesNotExist()
    }
}
