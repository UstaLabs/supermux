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
    fun hiddenProjectsAreSkippedAndKeepTheirPlace() {
        // Beta is hidden (archived-only): Gamma moving up swaps with Alpha, Beta stays in the middle.
        val visible = setOf("a", "c")
        assertEquals(listOf("c", "b", "a"), reorderedProjectIds(all, c, -1, visible))
        assertEquals(listOf("c", "b", "a"), reorderedProjectIds(all, a, +1, visible))
        assertNull(reorderedProjectIds(all, a, -1, visible))
        assertNull(reorderedProjectIds(all, c, +1, visible))
        // A hidden project itself never moves.
        assertNull(reorderedProjectIds(all, b, +1, visible))
    }

    @Test
    fun hiddenNeighboursDoNotOfferAMove() {
        // Alpha's only neighbour below is hidden → no Move down; the order around it is untouched.
        val visible = setOf("a")
        assertNull(reorderedProjectIds(all, a, +1, visible))
        assertNull(reorderedProjectIds(all, a, -1, visible))
    }

    @Test
    fun sidebarReordersAmongVisibleProjectsOnly() = runComposeUiTest {
        var reordered: Pair<String, List<String>>? = null
        setContent {
            SessionListScreen(
                workspaces = listOf(pws("w1", "Fix Renaming", "/home/u/projects/app", "a")),
                // Beta only has an archived workspace → hidden from the live sidebar.
                archivedWorkspaces = listOf(
                    pws("w9", "Old", "/home/u/projects/beta", "b").copy(status = "archived"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                projects = listOf(a, b, c),
                workspaceHost = { "h1" },
                onReorderProjects = { host, ids -> reordered = host to ids },
            )
        }
        onNodeWithText("Gamma").assertIsDisplayed()
        val alphaKey = projectGroupKey("h1", "a")
        onNodeWithTag(ProjectTestIds.menu(alphaKey)).performClick()
        onNodeWithTag(ProjectTestIds.MOVE_UP).assertDoesNotExist()
        onNodeWithTag(ProjectTestIds.MOVE_DOWN).performClick()
        assertEquals("h1" to listOf("c", "b", "a"), reordered)
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

    @Test
    fun sidebarArchivedFoldOffersProjectSettingsOnProjectHeaders() = runComposeUiTest {
        var opened: ProjectRef? = null
        setContent {
            SessionListScreen(
                mode = SessionListMode.Fleet,
                archivedWorkspaces = listOf(
                    pws("w9", "Old", "/home/u/projects/beta", "b").copy(status = "archived"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                projects = listOf(a, b),
                workspaceHost = { "h1" },
                onProjectSettings = { opened = it },
            )
        }
        onNodeWithTag(WorkspaceListTestIds.ARCHIVED_FOLD).performClick()
        onNodeWithTag(ProjectTestIds.menu(projectGroupKey("h1", "b"))).performClick()
        onNodeWithTag(ProjectTestIds.MOVE_UP).assertDoesNotExist()
        onNodeWithTag(ProjectTestIds.SETTINGS).performClick()
        assertEquals(b, opened)
    }
}
