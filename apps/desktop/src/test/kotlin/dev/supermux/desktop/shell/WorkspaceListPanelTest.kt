package dev.supermux.desktop.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
