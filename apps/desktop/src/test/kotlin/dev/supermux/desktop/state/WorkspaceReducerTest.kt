package dev.supermux.desktop.state

import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Workspace/view frame reducer tests for [DesktopAppState].
 *
 * [DesktopAppState.forTest] does not exist; construction mirrors
 * [DesktopAppStateReducerTest] (`connectOnInit = false`, no live WebSocket).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceReducerTest {

    private fun app() = DesktopAppState(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        connectOnInit = false,
    )

    private fun ws(id: String, name: String = id, sortOrder: Int = 0, views: List<ViewDto> = emptyList()) =
        WorkspaceDto(
            id = id, name = name, workdir = "/w", sortOrder = sortOrder, views = views,
            layout = LayoutNodeDto.Group(id = "g-$id", viewIds = views.map { it.id }, activeViewId = views.firstOrNull()?.id),
        )

    private fun view(id: String, workspaceId: String, kind: String = "editor") =
        ViewDto(id = id, workspaceId = workspaceId, kind = kind)

    @Test
    fun snapshotSeedsTheWorkspaceList() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        assertEquals(listOf("w1", "w2"), app.workspaces.value.map { it.id })
    }

    @Test
    fun snapshotFromAnOldBrokerLeavesTheListEmpty() {
        val app = app()
        app.reduce(ServerFrame.Snapshot())
        assertEquals(emptyList(), app.workspaces.value)
    }

    @Test
    fun workspaceAddedAppends() {
        val app = app()
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1")))
        assertEquals(listOf("w1"), app.workspaces.value.map { it.id })
    }

    @Test
    fun workspaceAddedForAKnownIdReplacesRatherThanDuplicates() {
        // The broker re-broadcasts the same workspace (early add on spawn, then the
        // authoritative one carrying repo_root / branch) — same trap as SessionAdded.
        val app = app()
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1", name = "first")))
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1", name = "second")))
        assertEquals(1, app.workspaces.value.size)
        assertEquals("second", app.workspaces.value[0].name)
    }

    @Test
    fun workspaceChangedReplacesInPlaceKeepingOrder() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        app.reduce(ServerFrame.WorkspaceChanged(ws("w1", name = "renamed")))
        assertEquals(listOf("w1", "w2"), app.workspaces.value.map { it.id })
        assertEquals("renamed", app.workspaces.value[0].name)
    }

    @Test
    fun workspaceChangedForAnUnknownIdIsIgnored() {
        val app = app()
        app.reduce(ServerFrame.WorkspaceChanged(ws("ghost")))
        assertEquals(emptyList(), app.workspaces.value)
    }

    @Test
    fun workspaceRemovedDropsIt() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        app.reduce(ServerFrame.WorkspaceRemoved("w1"))
        assertEquals(listOf("w2"), app.workspaces.value.map { it.id })
        assertEquals(listOf("w1"), app.archivedWorkspaces.value.map { it.id })
        assertEquals("archived", app.archivedWorkspaces.value[0].status)
    }

    @Test
    fun snapshotSeedsArchivedWorkspaces() {
        val app = app()
        app.reduce(
            ServerFrame.Snapshot(
                workspaces = listOf(ws("w1")),
                archivedWorkspaces = listOf(ws("w2").copy(status = "archived")),
            ),
        )
        assertEquals(listOf("w1"), app.workspaces.value.map { it.id })
        assertEquals(listOf("w2"), app.archivedWorkspaces.value.map { it.id })
    }

    @Test
    fun workspaceAddedLeavesTheArchivedList() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(archivedWorkspaces = listOf(ws("w1").copy(status = "archived"))))
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1")))
        assertEquals(listOf("w1"), app.workspaces.value.map { it.id })
        assertEquals(emptyList(), app.archivedWorkspaces.value.map { it.id })
    }

    @Test
    fun workspacesReorderedRewritesSortOrderByPosition() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1", sortOrder = 0), ws("w2", sortOrder = 1))))
        app.reduce(ServerFrame.WorkspacesReordered(listOf("w2", "w1")))
        val byId = app.workspaces.value.associateBy { it.id }
        assertEquals(0, byId["w2"]!!.sortOrder)
        assertEquals(1, byId["w1"]!!.sortOrder)
    }

    @Test
    fun viewAddedAppendsToItsWorkspace() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"))))
        app.reduce(ServerFrame.ViewAdded("w1", view("v1", "w1")))
        assertEquals(listOf("v1"), app.workspaces.value[0].views.map { it.id })
    }

    @Test
    fun viewRemovedDropsIt() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"), view("v2", "w1"))))))
        app.reduce(ServerFrame.ViewRemoved("w1", "v1"))
        assertEquals(listOf("v2"), app.workspaces.value[0].views.map { it.id })
    }

    @Test
    fun viewChangedReplacesInPlace() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"))))))
        app.reduce(ServerFrame.ViewChanged("w1", view("v1", "w1").copy(title = "renamed")))
        assertEquals("renamed", app.workspaces.value[0].views[0].title)
    }

    @Test
    fun viewMovedTakesItFromOneWorkspaceAndGivesItToTheOther() {
        val app = app()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(
            ws("w1", views = listOf(view("v1", "w1"))),
            ws("w2"),
        )))
        app.reduce(ServerFrame.ViewMoved("v1", "w1", "w2"))
        val byId = app.workspaces.value.associateBy { it.id }
        assertEquals(emptyList(), byId["w1"]!!.views.map { it.id })
        assertEquals(listOf("v1"), byId["w2"]!!.views.map { it.id })
        assertEquals("w2", byId["w2"]!!.views[0].workspaceId)
    }
}
