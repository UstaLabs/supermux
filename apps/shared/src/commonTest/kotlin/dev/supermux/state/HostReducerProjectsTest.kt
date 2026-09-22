package dev.supermux.state

import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostReducerProjectsTest {
    private fun ws(id: String, projectId: String? = null, status: String = "active") =
        WorkspaceDto(id = id, name = id, workdir = "/w/$id", status = status, projectId = projectId)

    private fun project(id: String, name: String = id.uppercase(), sortOrder: Int = 0) =
        ProjectDto(
            id = id, name = name, sortOrder = sortOrder, createdAt = "t",
            locations = listOf(ProjectLocationDto(id = "l-$id", path = "/p/$id")),
        )

    @Test fun snapshotSetsProjectsAndPatchesActiveAndArchivedMembership() {
        val out = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(
                workspaces = listOf(ws("w1"), ws("w2")),
                archivedWorkspaces = listOf(ws("w3", status = "archived")),
                projects = listOf(project("p1"), project("p2")),
                projectMembership = mapOf("w1" to "p1", "w3" to "p2"),
            ),
        )
        assertEquals(listOf("p1", "p2"), out.projects.map { it.id })
        assertTrue(out.projectCatalogKnown)
        assertEquals("p1", out.workspaces.first { it.id == "w1" }.projectId)
        assertNull(out.workspaces.first { it.id == "w2" }.projectId)
        assertEquals("p2", out.archivedWorkspaces.single().projectId)
    }

    @Test fun oldBrokerSnapshotLeavesProjectIdsAloneAndCatalogUnknown() {
        val out = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(workspaces = listOf(ws("w1", projectId = "p1"))),
        )
        assertEquals(emptyList(), out.projects)
        assertFalse(out.projectCatalogKnown)
        assertEquals("p1", out.workspaces.single().projectId)
    }

    @Test fun projectsChangedReplacesCatalogAndRepatchesBothLists() {
        val start = HostState(
            workspaces = listOf(ws("w1", projectId = "p1"), ws("w2", projectId = "p1")),
            archivedWorkspaces = listOf(ws("w3", projectId = "p1", status = "archived")),
            projects = listOf(project("p1")),
        )
        val out = reduceHostFrame(
            start,
            ServerFrame.ProjectsChanged(
                projects = listOf(project("p2", name = "Two")),
                projectMembership = mapOf("w1" to "p2", "w3" to "p2"),
            ),
        )
        assertEquals(listOf("Two"), out.projects.map { it.name })
        assertTrue(out.projectCatalogKnown)
        assertEquals("p2", out.workspaces.first { it.id == "w1" }.projectId)
        // Absent from the map → unresolved.
        assertNull(out.workspaces.first { it.id == "w2" }.projectId)
        assertEquals("p2", out.archivedWorkspaces.single().projectId)
    }

    @Test fun projectsChangedWithEmptyCatalogStillMarksKnown() {
        val out = reduceHostFrame(HostState(), ServerFrame.ProjectsChanged())
        assertTrue(out.projectCatalogKnown)
    }

    @Test fun workspaceAddedKeepsItsOwnProjectId() {
        val start = HostState(projects = listOf(project("p1")), projectCatalogKnown = true)
        val out = reduceHostFrame(start, ServerFrame.WorkspaceAdded(ws("w9", projectId = "p1")))
        assertEquals("p1", out.workspaces.single().projectId)
    }

    /** [ServerFrame.WorkspaceRemoved] moves the workspace into [HostState.archivedWorkspaces] by
     *  copying it with `status = "archived"` — every other field, including [WorkspaceDto.projectId],
     *  must survive that copy so the archived list still groups by project. */
    @Test fun workspaceRemovedKeepsProjectIdOnTheArchivedCopy() {
        val start = HostState(
            projects = listOf(project("p1")),
            projectCatalogKnown = true,
            workspaces = listOf(ws("w1", projectId = "p1")),
        )
        val out = reduceHostFrame(start, ServerFrame.WorkspaceRemoved("w1"))
        assertEquals(emptyList(), out.workspaces)
        val archived = out.archivedWorkspaces.single()
        assertEquals("archived", archived.status)
        assertEquals("p1", archived.projectId)
    }
}
