package dev.supermux.desktop.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.host.HostView
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun chatView(id: String, sessionId: String, wid: String) = ViewDto(
    id = id, workspaceId = wid, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun ws(id: String, name: String, workdir: String, branch: String? = null, views: List<ViewDto> = emptyList()) =
    WorkspaceDto(
        id = id, name = name, workdir = workdir, branch = branch, views = views,
        layout = LayoutNodeDto.Group(id = "g", viewIds = views.map { it.id }),
    )

@OptIn(ExperimentalTestApi::class)
class WorkspaceListPanelTest {

    @Test
    fun showsOneRowPerWorkspaceUnderItsProjectHeader() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(
                    ws("w1", "Fix Renaming", "/home/u/projects/app"),
                    ws("w2", "Add Search", "/home/u/projects/app"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
            )
        }
        onNodeWithText("Fix Renaming").assertIsDisplayed()
        onNodeWithText("Add Search").assertIsDisplayed()
        // formatWorkdir → …/projects/app; PathGroupHeader renders the leaf only.
        onNodeWithText("app").assertIsDisplayed()
    }

    @Test
    fun showsTheBranch() = runComposeUiTest {
        // The user hard-rejected every list concept that dropped the branch.
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p", branch = "mux/fix-renaming")),
                home = "/home/u", activeId = null, onOpen = {},
            )
        }
        onNodeWithText("mux/fix-renaming").assertIsDisplayed()
    }

    @Test
    fun aOneChatWorkspaceShowsNoChildRows() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "solo", "/p", views = listOf(chatView("v1", "s1", "w1")))),
                home = "/home/u", activeId = null, onOpen = {},
            )
        }
        onNodeWithTag("workspace-children-w1").assertDoesNotExist()
    }

    @Test
    fun aTwoChatWorkspaceShowsItsChildRowsAndTheMultiAgentMark() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "shared", "/p", views = listOf(
                    chatView("v1", "s1", "w1"), chatView("v2", "s2", "w1"),
                ))),
                home = "/home/u", activeId = null, onOpen = {},
                sessionNames = mapOf("s1" to "agent one", "s2" to "agent two"),
            )
        }
        onNodeWithTag("workspace-children-w1").assertIsDisplayed()
        onNodeWithText("agent one").assertIsDisplayed()
        onNodeWithText("agent two").assertIsDisplayed()
        // Icon semantics merge into the row; unmerged tree is the reliable finder
        // (same pattern as SessionStatusRailTest).
        onNodeWithTag("workspace-multiagent-w1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun clickingARowOpensThatWorkspace() = runComposeUiTest {
        var opened: String? = null
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p")),
                home = "/home/u", activeId = null, onOpen = { opened = it },
            )
        }
        onNodeWithText("a").performClick()
        assertEquals("w1", opened)
    }

    @Test
    fun clickingAChildRowOpensThatSessionsView() = runComposeUiTest {
        var openedSession: String? = null
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "shared", "/p", views = listOf(
                    chatView("v1", "s1", "w1"), chatView("v2", "s2", "w1"),
                ))),
                home = "/home/u", activeId = null, onOpen = {},
                sessionNames = mapOf("s1" to "agent one", "s2" to "agent two"),
                onOpenSession = { _, s -> openedSession = s },
            )
        }
        onNodeWithText("agent two").performClick()
        assertEquals("s2", openedSession)
    }

    @Test
    fun anArchivedWorkspaceIsNotListed() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "gone", "/p").copy(status = "archived")),
                home = "/home/u", activeId = null, onOpen = {},
            )
        }
        onNodeWithText("gone").assertDoesNotExist()
    }

    // ── Chrome parity with SessionListPanel (these are the tests that would have
    // caught the thin rewrite that dropped the + button and footer rail). ─────

    @Test
    fun newSessionRow_rendersAndFires_onNewSession() = runComposeUiTest {
        var fired = false
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNewSession = { fired = true },
            )
        }
        onNodeWithTag("new_session_row").assertIsDisplayed()
        onNodeWithText("Start a new session").assertIsDisplayed()
        onNodeWithTag("new_session_row").performClick()
        assertTrue(fired, "clicking the new-session card should fire onNewSession")
    }

    @Test
    fun newSessionRow_rendersAndFires_inEmptyState() = runComposeUiTest {
        var fired = false
        setContent {
            WorkspaceListPanel(
                workspaces = emptyList(),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNewSession = { fired = true },
            )
        }
        onNodeWithTag("new_session_row").assertIsDisplayed()
        onNodeWithTag("new_session_row").performClick()
        assertTrue(fired, "new-session card must stay reachable with zero workspaces")
    }

    @Test
    fun footer_rendersAndFiresUsageSettingsDevicesTheme() = runComposeUiTest {
        var usage = false
        var settings = false
        var devices = false
        var theme = false
        setContent {
            WorkspaceListPanel(
                workspaces = emptyList(),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onUsage = { usage = true },
                onSettings = { settings = true },
                onDevices = { devices = true },
                onToggleTheme = { theme = true },
            )
        }
        onNodeWithTag("sidebar_footer").assertIsDisplayed()
        onNodeWithTag("sidebar_footer_usage").performClick()
        onNodeWithTag("sidebar_footer_settings").performClick()
        onNodeWithTag("sidebar_footer_devices").performClick()
        onNodeWithTag("sidebar_footer_theme").performClick()
        assertTrue(usage && settings && devices && theme, "footer rail must fire all four actions")
    }

    @Test
    fun perWorkspaceAddView_firesWithWorkspaceId() = runComposeUiTest {
        var added: String? = null
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onAddView = { added = it },
            )
        }
        // Always in the semantics tree (alpha=0 when not hovered) so chrome tests don't need hover.
        onNodeWithTag("workspace_add_view_w1", useUnmergedTree = true).performClick()
        assertEquals("w1", added)
    }

    @Test
    fun settledSection_appearsWhenArchivedDataIsSupplied() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = emptyList(),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                archived = listOf(
                    ArchivedDto(id = "a1", name = "old sess", workdir = "/home/u/projects/app"),
                ),
            )
        }
        onNodeWithText("Show 1 settled").assertIsDisplayed()
    }

    @Test
    fun settledSection_appearsUnderProjectGroupWhenArchivedMatchesPath() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "live", "/home/u/projects/app")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                archived = listOf(
                    ArchivedDto(id = "a1", name = "old sess", workdir = "/home/u/projects/app"),
                ),
            )
        }
        onNodeWithText("Show 1 settled").assertIsDisplayed()
    }

    @Test
    fun rowContextMenu_offersRenameMuteArchive() {
        // Desktop ContextMenuArea is awkward to drive under headless Skiko; assert the
        // same label set the row wires into the menu so chrome can't silently drop actions.
        val labels = workspaceRowContextLabels(mute = false)
        assertEquals(listOf("Rename", "Mute", "Archive"), labels)
        assertEquals(listOf("Rename", "Unmute", "Archive"), workspaceRowContextLabels(mute = true))
    }

    @Test
    fun hostChips_appearWithTwoHosts_andAbsentWithOne() = runComposeUiTest {
        val h1 = HostView(recordId = "r1", hostId = "h1", displayName = "Alpha", online = true)
        val h2 = HostView(recordId = "r2", hostId = "h2", displayName = "Beta", online = true)
        val session = SessionInfo(id = "s1", name = "sess", workdir = "/p", agent = "claude")
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p", views = listOf(chatView("v1", "s1", "w1")))),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                sessions = listOf(session),
                hosts = listOf(h1, h2),
                sessionHost = mapOf("s1" to "r1"),
            )
        }
        onNodeWithTag("host_filter_chips").assertIsDisplayed()
        onNodeWithTag("host_chip_all").assertIsDisplayed()
    }

    @Test
    fun hostChips_absentWithSingleHost() = runComposeUiTest {
        val h1 = HostView(recordId = "r1", hostId = "h1", displayName = "Only", online = true)
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = listOf(h1),
            )
        }
        onNodeWithTag("host_filter_chips").assertDoesNotExist()
    }
}
