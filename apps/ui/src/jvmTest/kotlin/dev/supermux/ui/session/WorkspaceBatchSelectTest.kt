package dev.supermux.ui.session

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
import kotlin.test.assertTrue

private fun chat(id: String, sessionId: String, wid: String) = ViewDto(
    id = id, workspaceId = wid, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun ws(id: String, name: String, vararg sessionIds: String) = WorkspaceDto(
    id = id, name = name, workdir = "/home/u/projects/app",
    views = sessionIds.mapIndexed { i, sid -> chat("$id-v$i", sid, id) },
    layout = LayoutNodeDto.Group(id = "g", viewIds = sessionIds.indices.map { "$id-v$it" }),
)

/** Multi-select on the workspace list: batch Mark as read / Archive. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceBatchSelectTest {

    @Test fun toggle_adds_then_removes() {
        assertEquals(setOf("a"), toggleWorkspaceSelection(emptySet(), "a"))
        assertEquals(emptySet(), toggleWorkspaceSelection(setOf("a"), "a"))
    }

    @Test fun mark_read_covers_every_chat_of_the_selected_workspaces_only() {
        val all = listOf(ws("w1", "one", "s1", "s2"), ws("w2", "two", "s3"), ws("w3", "three", "s4"))
        assertEquals(listOf("s1", "s2", "s4"), batchMarkReadSessionIds(all, setOf("w1", "w3")))
    }

    @Test fun batch_archive_skips_personal_assistants() {
        val all = listOf(ws("pa", "pa", "s0"), ws("w1", "one", "s1"))
        assertEquals(listOf("w1"), batchArchiveIds(all, setOf("pa", "w1"), paIds = setOf("pa")))
    }

    @Test fun select_rows_then_mark_read_and_archive() = runComposeUiTest {
        val marked = mutableListOf<List<String>>()
        val archived = mutableListOf<String>()
        val opened = mutableListOf<String>()
        setContent {
            SessionListScreen(
                workspaces = listOf(ws("w1", "one", "s1", "s2"), ws("w2", "two", "s3"), ws("w3", "three", "s4")),
                home = "/home/u",
                activeId = null,
                onOpen = { opened += it },
                actions = SessionListActions(
                    markRead = { marked += it },
                    archiveWorkspace = { archived += it },
                ),
            )
        }
        onNodeWithTag(WorkspaceListTestIds.SELECT_TOGGLE).performClick()
        onNodeWithTag(WorkspaceListTestIds.BATCH_BAR).assertIsDisplayed()
        // In selection mode a row click toggles instead of opening.
        onNodeWithTag(WorkspaceListTestIds.row("w1")).performClick()
        onNodeWithTag(WorkspaceListTestIds.row("w3")).performClick()
        assertTrue(opened.isEmpty())
        onNodeWithText("2 selected").assertIsDisplayed()

        onNodeWithTag(WorkspaceListTestIds.BATCH_MARK_READ).performClick()
        assertEquals(listOf(listOf("s1", "s2", "s4")), marked)
        // Mark as read finishes the selection.
        onNodeWithTag(WorkspaceListTestIds.BATCH_BAR).assertDoesNotExist()

        onNodeWithTag(WorkspaceListTestIds.SELECT_TOGGLE).performClick()
        onNodeWithTag(WorkspaceListTestIds.BATCH_SELECT_ALL).performClick()
        onNodeWithText("3 selected").assertIsDisplayed()
        onNodeWithTag(WorkspaceListTestIds.BATCH_ARCHIVE).performClick()
        onNodeWithText("Archive 3 workspaces?").assertIsDisplayed()
        assertTrue(archived.isEmpty(), "nothing archives before the confirm")
        onNodeWithText("Archive").performClick()
        assertEquals(setOf("w1", "w2", "w3"), archived.toSet())
    }

    @Test fun cancel_leaves_selection_and_rows_open_again() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            SessionListScreen(
                workspaces = listOf(ws("w1", "one", "s1")),
                home = "/home/u",
                activeId = null,
                onOpen = { opened += it },
            )
        }
        onNodeWithTag(WorkspaceListTestIds.SELECT_TOGGLE).performClick()
        onNodeWithTag(WorkspaceListTestIds.BATCH_CANCEL).performClick()
        onNodeWithTag(WorkspaceListTestIds.BATCH_BAR).assertDoesNotExist()
        onNodeWithTag(WorkspaceListTestIds.row("w1")).performClick()
        assertEquals(listOf("w1"), opened)
    }
}
