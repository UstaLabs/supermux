package dev.supermux.android

import dev.supermux.android.host.WorkspaceHostState
import dev.supermux.android.host.reduceWorkspaceFrame
import dev.supermux.android.host.workspaceForSession
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Workspace/view frame reducer tests — ports desktop [WorkspaceReducerTest]
 * onto the pure [reduceWorkspaceFrame] used by [AppViewModel].
 */
class WorkspaceReducerTest {

    private fun reduce(state: WorkspaceHostState, frame: ServerFrame): WorkspaceHostState =
        reduceWorkspaceFrame(state, frame) ?: state

    private fun ws(id: String, name: String = id, sortOrder: Int = 0, views: List<ViewDto> = emptyList()) =
        WorkspaceDto(
            id = id, name = name, workdir = "/w", sortOrder = sortOrder, views = views,
            layout = LayoutNodeDto.Group(
                id = "g-$id",
                viewIds = views.map { it.id },
                activeViewId = views.firstOrNull()?.id,
            ),
        )

    private fun view(id: String, workspaceId: String, kind: String = "editor") =
        ViewDto(id = id, workspaceId = workspaceId, kind = kind)

    private fun chatView(id: String, workspaceId: String, sessionId: String) =
        ViewDto(
            id = id,
            workspaceId = workspaceId,
            kind = "chat",
            state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
        )

    @Test
    fun snapshotSeedsTheWorkspaceList() {
        val next = reduce(WorkspaceHostState(), ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        assertEquals(listOf("w1", "w2"), next.workspaces.map { it.id })
    }

    @Test
    fun snapshotSeedsArchivedWorkspaces() {
        val next = reduce(
            WorkspaceHostState(),
            ServerFrame.Snapshot(
                workspaces = listOf(ws("w1")),
                archivedWorkspaces = listOf(ws("w2").copy(status = "archived")),
            ),
        )
        assertEquals(listOf("w1"), next.workspaces.map { it.id })
        assertEquals(listOf("w2"), next.archivedWorkspaces.map { it.id })
    }

    @Test
    fun workspaceAddedAppends() {
        val next = reduce(WorkspaceHostState(), ServerFrame.WorkspaceAdded(ws("w1")))
        assertEquals(listOf("w1"), next.workspaces.map { it.id })
    }

    @Test
    fun workspaceChangedReplacesInPlaceKeepingOrder() {
        val seeded = reduce(WorkspaceHostState(), ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        val next = reduce(seeded, ServerFrame.WorkspaceChanged(ws("w1", name = "renamed")))
        assertEquals(listOf("w1", "w2"), next.workspaces.map { it.id })
        assertEquals("renamed", next.workspaces[0].name)
    }

    @Test
    fun workspaceRemovedDropsItToArchived() {
        val seeded = reduce(WorkspaceHostState(), ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        val next = reduce(seeded, ServerFrame.WorkspaceRemoved("w1"))
        assertEquals(listOf("w2"), next.workspaces.map { it.id })
        assertEquals(listOf("w1"), next.archivedWorkspaces.map { it.id })
        assertEquals("archived", next.archivedWorkspaces[0].status)
    }

    @Test
    fun workspacesReorderedRewritesSortOrderByPosition() {
        val seeded = reduce(
            WorkspaceHostState(),
            ServerFrame.Snapshot(workspaces = listOf(ws("w1", sortOrder = 0), ws("w2", sortOrder = 1))),
        )
        val next = reduce(seeded, ServerFrame.WorkspacesReordered(listOf("w2", "w1")))
        val byId = next.workspaces.associateBy { it.id }
        assertEquals(0, byId["w2"]!!.sortOrder)
        assertEquals(1, byId["w1"]!!.sortOrder)
    }

    @Test
    fun viewAddedAppendsToItsWorkspace() {
        val seeded = reduce(WorkspaceHostState(), ServerFrame.Snapshot(workspaces = listOf(ws("w1"))))
        val next = reduce(seeded, ServerFrame.ViewAdded("w1", view("v1", "w1")))
        assertEquals(listOf("v1"), next.workspaces[0].views.map { it.id })
    }

    @Test
    fun viewChangedReplacesInPlace() {
        val seeded = reduce(
            WorkspaceHostState(),
            ServerFrame.Snapshot(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"))))),
        )
        val next = reduce(seeded, ServerFrame.ViewChanged("w1", view("v1", "w1").copy(title = "renamed")))
        assertEquals("renamed", next.workspaces[0].views[0].title)
    }

    @Test
    fun viewRemovedDropsIt() {
        val seeded = reduce(
            WorkspaceHostState(),
            ServerFrame.Snapshot(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"), view("v2", "w1"))))),
        )
        val next = reduce(seeded, ServerFrame.ViewRemoved("w1", "v1"))
        assertEquals(listOf("v2"), next.workspaces[0].views.map { it.id })
    }

    @Test
    fun viewMovedTakesItFromOneWorkspaceAndGivesItToTheOther() {
        val seeded = reduce(
            WorkspaceHostState(),
            ServerFrame.Snapshot(
                workspaces = listOf(
                    ws("w1", views = listOf(view("v1", "w1"))),
                    ws("w2"),
                ),
            ),
        )
        val next = reduce(seeded, ServerFrame.ViewMoved("v1", "w1", "w2"))
        val byId = next.workspaces.associateBy { it.id }
        assertEquals(emptyList(), byId["w1"]!!.views.map { it.id })
        assertEquals(listOf("v1"), byId["w2"]!!.views.map { it.id })
        assertEquals("w2", byId["w2"]!!.views[0].workspaceId)
    }

    @Test
    fun workspaceForSessionFindsChatViewOwner() {
        val w1 = ws("w1", views = listOf(chatView("v1", "w1", "s-chat")))
        val w2 = ws("w2", views = listOf(view("v2", "w2")))
        assertEquals("w1", workspaceForSession(listOf(w1, w2), "s-chat")?.id)
        assertNull(workspaceForSession(listOf(w1, w2), "missing"))
    }
}
