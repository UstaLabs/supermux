package dev.supermux.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.ProjectRef
import dev.supermux.workspace.projectGroupKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun project(id: String, name: String, order: Int) = ProjectDto(id = id, name = name, sortOrder = order)

private fun pws(id: String, name: String, workdir: String, projectId: String?) = WorkspaceDto(
    id = id, name = name, workdir = workdir, projectId = projectId,
    layout = LayoutNodeDto.Group(id = "g", viewIds = emptyList()),
)

@OptIn(ExperimentalTestApi::class)
class ProjectGroupsTest {
    private val a = ProjectRef("h1", project("a", "Alpha", 0))
    private val b = ProjectRef("h1", project("b", "Beta", 1))
    private val c = ProjectRef("h1", project("c", "Gamma", 2))
    private val other = ProjectRef("h2", project("x", "Other", 0))
    private val all = listOf(c, other, a, b)

    @Test
    fun moveUpSwapsWithThePreviousProjectOfTheSameHostOnly() {
        assertEquals(listOf("a", "c", "b"), reorderedProjectIds(all, c, -1))
        assertEquals(listOf("b", "a", "c"), reorderedProjectIds(all, a, +1))
    }

    @Test
    fun cannotMovePastEitherEnd() {
        assertNull(reorderedProjectIds(all, a, -1))
        assertNull(reorderedProjectIds(all, c, +1))
        assertNull(reorderedProjectIds(all, other, +1))
    }

    @Test
    fun ordersTiesByNameThenId() {
        val t1 = ProjectRef("h", project("2", "Same", 0))
        val t2 = ProjectRef("h", project("1", "Same", 0))
        val t3 = ProjectRef("h", project("3", "Aaa", 0))
        // Painted order is Aaa(3), Same(1), Same(2).
        assertEquals(listOf("3", "2", "1"), reorderedProjectIds(listOf(t1, t2, t3), t1, -1))
    }

    @Test
    fun sidebarGroupsByProjectAndShowsAnEmptyProject() = runComposeUiTest {
        var newIn: ProjectRef? = null
        var reordered: Pair<String, List<String>>? = null
        setContent {
            SessionListScreen(
                workspaces = listOf(
                    pws("w1", "Fix Renaming", "/home/u/projects/app", "a"),
                    pws("w2", "Docs Pass", "/home/u/projects/docs", "a"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                projects = listOf(a, b),
                workspaceHost = { "h1" },
                onNewWorkspaceInProject = { newIn = it },
                onReorderProjects = { host, ids -> reordered = host to ids },
            )
        }
        onNodeWithText("Alpha").assertIsDisplayed()
        onNodeWithText("Fix Renaming").assertIsDisplayed()
        onNodeWithText("Docs Pass").assertIsDisplayed()
        onNodeWithText("Beta").assertIsDisplayed()
        val betaKey = projectGroupKey("h1", "b")
        onNodeWithTag(ProjectTestIds.empty(betaKey)).assertIsDisplayed()
        onNodeWithTag(ProjectTestIds.emptyNew(betaKey)).performClick()
        assertEquals(b, newIn)

        onNodeWithTag(ProjectTestIds.menu(betaKey)).performClick()
        onNodeWithTag(ProjectTestIds.MOVE_UP).performClick()
        assertEquals("h1" to listOf("b", "a"), reordered)
    }
}
