package dev.supermux.desktop.shell

import dev.supermux.proto.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceListInteractionsTest {

    private fun ws(id: String, sortOrder: Int = 0) = WorkspaceDto(
        id = id,
        name = id,
        workdir = "/p",
        sortOrder = sortOrder,
    )

    @Test
    fun applyWorkingOrder_reordersKnownIdsAndAppendsRest() {
        val rows = listOf(ws("a"), ws("b"), ws("c"))
        assertEquals(
            listOf("b", "a", "c"),
            applyWorkspaceWorkingOrder(rows, listOf("b", "a")).map { it.id },
        )
        assertEquals(rows, applyWorkspaceWorkingOrder(rows, null))
        assertEquals(rows, applyWorkspaceWorkingOrder(rows, emptyList()))
    }

    @Test
    fun moveWithinScope_swapsWithinWorkingOrder() {
        val rows = listOf(ws("a"), ws("b"), ws("c"))
        val move = moveWorkspaceWithinScope(
            rows = rows,
            workingOrders = emptyMap(),
            scopeKey = "g1",
            fromId = "a",
            toId = "c",
        )
        assertEquals(listOf("b", "c", "a"), move!!.orderedIds)
        assertEquals("g1", move.scope.key)
    }

    @Test
    fun moveWithinScope_returnsNullWhenUnchangedOrUnknown() {
        val rows = listOf(ws("a"), ws("b"))
        assertNull(
            moveWorkspaceWithinScope(rows, emptyMap(), "g", "a", "a"),
        )
        assertNull(
            moveWorkspaceWithinScope(rows, emptyMap(), "g", "a", "missing"),
        )
    }

    @Test
    fun dragWorkingState_commitsChangedOrderOnce() {
        val state = WorkspaceDragWorkingState()
        val scope = WorkspaceReorderScope("g1")
        state.begin(scope, listOf("a", "b"))
        state.move(listOf("b", "a"))
        assertEquals(
            WorkspaceReorderMove(scope, listOf("b", "a")),
            state.finish(commit = true),
        )
        assertNull(state.finish(commit = true))
    }

    @Test
    fun dragWorkingState_cancelDoesNotCommit() {
        val state = WorkspaceDragWorkingState()
        state.begin(WorkspaceReorderScope("g1"), listOf("a", "b"))
        state.move(listOf("b", "a"))
        assertNull(state.finish(commit = false))
    }
}
