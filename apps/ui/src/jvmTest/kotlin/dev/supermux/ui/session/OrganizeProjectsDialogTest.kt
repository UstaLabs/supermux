package dev.supermux.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.ProjectDto
import dev.supermux.workspace.ProjectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OrganizeProjectsDialogTest {
    private fun ref(host: String, id: String, name: String, sortOrder: Int = 0) =
        ProjectRef(host, ProjectDto(id = id, name = name, sortOrder = sortOrder))

    @Test fun order_is_per_host_in_sidebar_order() {
        val order = projectOrderByHost(
            listOf(ref("h1", "b", "Beta", 1), ref("h2", "x", "X"), ref("h1", "a", "Alpha", 1), ref("h1", "c", "Zed", 0)),
        )
        assertEquals(listOf("h1", "h2"), order.keys.toList())
        assertEquals(listOf("c", "a", "b"), order.getValue("h1").map { it.project.id })
        assertEquals(listOf("x"), order.getValue("h2").map { it.project.id })
    }

    @Test fun only_changed_hosts_are_saved() {
        val before = projectOrderByHost(listOf(ref("h1", "a", "A"), ref("h1", "b", "B"), ref("h2", "x", "X")))
        val after = before + ("h1" to before.getValue("h1").reversed())
        assertEquals(mapOf("h1" to listOf("b", "a")), changedProjectOrders(before, after))
        assertTrue(changedProjectOrders(before, before).isEmpty())
    }

    @Test fun the_overflow_opens_organize_and_done_without_drag_saves_nothing() = runComposeUiTest {
        val saved = mutableListOf<Pair<String, List<String>>>()
        setContent {
            SessionListScreen(
                mode = SessionListMode.Workspaces,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNavigate = {},
                projects = listOf(ref("h1", "a", "Alpha"), ref("h1", "b", "Beta")),
                onReorderProjects = { h, ids -> saved += h to ids },
            )
        }
        onNodeWithTag("list_overflow").performClick()
        onNodeWithTag("nav_organize_projects").performClick()
        onNodeWithTag(OrganizeProjectsTestIds.SHEET).assertIsDisplayed()
        onNodeWithTag(OrganizeProjectsTestIds.row("h1", "a")).assertIsDisplayed()
        onNodeWithTag(OrganizeProjectsTestIds.row("h1", "b")).assertIsDisplayed()
        onNodeWithTag(OrganizeProjectsTestIds.DONE).performClick()
        assertTrue(saved.isEmpty(), "unchanged order saved $saved")
        onNodeWithTag(OrganizeProjectsTestIds.SHEET).assertDoesNotExist()
    }

    @Test fun a_single_project_hides_the_entry() = runComposeUiTest {
        setContent {
            SessionListScreen(
                mode = SessionListMode.Workspaces,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                onNavigate = {},
                projects = listOf(ref("h1", "a", "Alpha")),
            )
        }
        onNodeWithTag("list_overflow").performClick()
        onNodeWithTag("nav_organize_projects").assertDoesNotExist()
    }
}
