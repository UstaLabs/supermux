package dev.supermux.desktop.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.host.HostView
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.LogEntry
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

    // ── Visual regressions the first 16 chrome tests missed ───────────────────

    /**
     * SessionListPanel group mode draws one settled fold per live project group and
     * hides settled-only projects. The workspace panel must not invent extra folds
     * (orphan path + per-group) that stack three "Show N settled" buttons.
     */
    @Test
    fun settledFold_exactlyOne_forLiveGroupWithSettledOnlyElsewhere() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "live", "/home/u/projects/app")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                archived = listOf(
                    ArchivedDto(id = "a1", name = "old-a", workdir = "/home/u/projects/app"),
                    ArchivedDto(id = "a2", name = "old-b", workdir = "/home/u/projects/app"),
                    // Settled-only project — SessionListPanel hides this; must not add a fold.
                    ArchivedDto(id = "o1", name = "orphan-settled", workdir = "/home/u/projects/other"),
                    ArchivedDto(id = "o2", name = "orphan-settled-2", workdir = "/home/u/projects/other"),
                    ArchivedDto(id = "o3", name = "orphan-settled-3", workdir = "/home/u/projects/third"),
                ),
            )
        }
        // Exactly one fold in the tree (assert the count, not mere presence).
        onAllNodesWithTag("settled_fold").assertCountEquals(1)
        onNodeWithText("Show 2 settled").assertIsDisplayed()
        // Must not stack settled-only / orphan chrome.
        onNodeWithText("Show 3 settled").assertDoesNotExist()
        onNodeWithText("Show 5 settled").assertDoesNotExist()
        onNodeWithText("Show 1 settled").assertDoesNotExist()
    }

    @Test
    fun row_rendersMessagePreviewFromPrimarySession() = runComposeUiTest {
        val previewText = "Merged into `dev` (fast-forward). — Commit: cd…"
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(
                    ws(
                        "w1", "feature", "/home/u/projects/app",
                        branch = "mux/feature",
                        views = listOf(chatView("v1", "s1", "w1")),
                    ).copy(primarySessionId = "s1"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                sessions = listOf(
                    SessionInfo(id = "s1", name = "feature", workdir = "/home/u/projects/app", agent = "claude"),
                ),
                lastBySession = mapOf(
                    "s1" to LogEntry(
                        id = "log1",
                        ts = "2026-08-07T12:00:00Z",
                        direction = "out",
                        text = previewText,
                    ),
                ),
            )
        }
        // Preview is how the user scans the list — must be present alongside the branch.
        onNodeWithText(previewText).assertIsDisplayed()
        onNodeWithText("mux/feature").assertIsDisplayed()
    }

    @Test
    fun row_rendersSuspendedBadgeWhenPrimarySessionIsSuspended() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(
                    ws(
                        "w1", "paused", "/home/u/projects/app",
                        views = listOf(chatView("v1", "s1", "w1")),
                    ).copy(primarySessionId = "s1"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                sessions = listOf(
                    SessionInfo(
                        id = "s1",
                        name = "paused",
                        workdir = "/home/u/projects/app",
                        agent = "claude",
                        status = "suspended",
                    ),
                ),
            )
        }
        onNodeWithText("suspended").assertIsDisplayed()
    }

    @Test
    fun flatMode_hidesInProgressHeaderWhenNoPersonalAgents() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "task-a", "/home/u/projects/app")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
            )
        }
        // Default is group-by-project; switch to flat so section headers apply.
        onNodeWithContentDescription("Flat list").performClick()
        onNodeWithText("task-a").assertIsDisplayed()
        onNodeWithText("IN PROGRESS").assertDoesNotExist()
        onNodeWithText("PERSONAL ASSISTANTS").assertDoesNotExist()
    }

    @Test
    fun flatMode_showsInProgressHeaderWhenPersonalAgentsExist() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(
                    ws(
                        "pa1", "My PA", "/home/u",
                        views = listOf(chatView("v1", "s-pa", "pa1")),
                    ).copy(primarySessionId = "s-pa"),
                    ws("w1", "task-a", "/home/u/projects/app"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                sessions = listOf(
                    SessionInfo(
                        id = "s-pa",
                        name = "My PA",
                        workdir = "/home/u",
                        agent = "claude",
                        role = "personal_assistant",
                    ),
                ),
            )
        }
        onNodeWithContentDescription("Flat list").performClick()
        onNodeWithText("PERSONAL ASSISTANTS").assertIsDisplayed()
        onNodeWithText("IN PROGRESS").assertIsDisplayed()
        onNodeWithText("My PA").assertIsDisplayed()
        onNodeWithText("task-a").assertIsDisplayed()
    }

    /**
     * Prove there is no take(n)/cap on workspace rows inside a project group.
     * Scroll every row into the LazyColumn viewport and assert it exists — if the
     * list were capped at 7, rows 8–19 would not be scrollable/found.
     */
    @Test
    fun groupOfNineteenWorkspaces_rendersAllNineteenRows() = runComposeUiTest {
        val list = (1..19).map { i ->
            ws("w$i", "ws-$i", "/home/u/projects/supermux")
        }
        setContent {
            WorkspaceListPanel(
                workspaces = list,
                home = "/home/u",
                activeId = null,
                onOpen = {},
            )
        }
        for (i in 1..19) {
            onNodeWithTag("workspaces_list")
                .performScrollToNode(hasTestTag("workspace_row_w$i"))
            onNodeWithTag("workspace_row_w$i").assertExists()
        }
    }
}
