package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.SessionInfo
import kotlin.math.abs
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Agents sort the way the workspace rows do: the same shared drag (DragReorder.kt) and the same
 * save (onReorder → PATCH /workspaces/reorder), inside a scope of their own. `:ui` jvmTest runs
 * Pointer + Expanded, so a press and a vertical drag lifts a row.
 */
@OptIn(ExperimentalTestApi::class)
class AgentsReorderTest {

    private fun agent(id: String, name: String, sortOrder: Int) = workspaceDto(
        id = id,
        name = name,
        workdir = "/home/u/.mux/workspace/$id",
        views = listOf(workspaceChatView("v-$id", "s-$id", id)),
        primarySessionId = "s-$id",
        sortOrder = sortOrder,
    )

    private fun agentSession(id: String, name: String) = SessionInfo(
        id = "s-$id",
        name = name,
        workdir = "/home/u/.mux/workspace/$id",
        agent = "claude",
        role = "personal_assistant",
    )

    private val workspaces = listOf(
        agent("a1", "Muxie", 0),
        agent("a2", "Atlas", 1),
        agent("a3", "Tally", 2),
        workspaceDto(id = "w1", name = "task-one", sortOrder = 0),
        workspaceDto(id = "w2", name = "task-two", sortOrder = 1),
    )
    private val sessions = listOf(agentSession("a1", "Muxie"), agentSession("a2", "Atlas"), agentSession("a3", "Tally"))

    @Composable
    private fun Sidebar(onReorder: (List<String>) -> Unit) {
        SessionListScreen(
            workspaces = workspaces,
            sessions = sessions,
            home = "/home/u",
            activeId = null,
            onOpen = {},
            onReorder = onReorder,
        )
    }

    private fun SemanticsNodeInteractionsProvider.centerY(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.y

    /**
     * Press the row and carry its centre [dy] px, in ~10 px steps so every slot it passes is asked,
     * then release. The lift spends the touch slop first, so that is added on top.
     */
    private fun SemanticsNodeInteraction.dragBy(dy: Float) = performTouchInput {
        down(center)
        val total = dy + sign(dy) * viewConfiguration.touchSlop
        val steps = (abs(total) / 10f).toInt().coerceAtLeast(1)
        repeat(steps) { moveBy(Offset(0f, total / steps)) }
        up()
    }

    @Test fun draggingAnAgentPastItsNeighbourSavesTheAgentsNewOrder() = runComposeUiTest {
        val saved = mutableListOf<List<String>>()
        setContent { Sidebar { saved += it } }
        waitForIdle()

        onNodeWithTag("agent_row_a1").dragBy(centerY("agent_row_a2") - centerY("agent_row_a1"))
        waitForIdle()

        assertEquals(listOf(listOf("a2", "a1", "a3")), saved)
    }

    @Test fun anAgentCannotBeDroppedAmongTheWorkspaces() = runComposeUiTest {
        val saved = mutableListOf<List<String>>()
        setContent { Sidebar { saved += it } }
        waitForIdle()

        val w1 = WorkspaceListTestIds.row("w1")
        val w2 = WorkspaceListTestIds.row("w2")
        // Down past the Workspaces header, over both workspace rows.
        onNodeWithTag("agent_row_a3").dragBy(centerY(w2) - centerY("agent_row_a3"))
        waitForIdle()

        assertEquals(emptyList(), saved)
        assertTrue(centerY(w1) < centerY(w2), "the workspaces kept their order")
    }

    @Test fun aWorkspaceCannotBeDroppedAmongTheAgents() = runComposeUiTest {
        val saved = mutableListOf<List<String>>()
        setContent { Sidebar { saved += it } }
        waitForIdle()

        val w1 = WorkspaceListTestIds.row("w1")
        onNodeWithTag(w1).dragBy(centerY("agent_row_a1") - centerY(w1))
        waitForIdle()

        assertEquals(emptyList(), saved)
    }

    @Test fun aWorkspaceDragStillSavesItsProjectsOrder() = runComposeUiTest {
        val saved = mutableListOf<List<String>>()
        setContent { Sidebar { saved += it } }
        waitForIdle()

        val w1 = WorkspaceListTestIds.row("w1")
        onNodeWithTag(w1).dragBy(centerY(WorkspaceListTestIds.row("w2")) - centerY(w1))
        waitForIdle()

        assertEquals(listOf(listOf("w2", "w1")), saved)
    }
}
