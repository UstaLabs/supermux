package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The optimistic halves of the workspace actions — archive/restore move the row between the live
 * and archived lists immediately, and reorder rewrites sortOrder — so the sidebar doesn't snap back
 * while the HTTP call is in flight. (Moved here from Android's pure applyArchive/applyRestore/
 * applyWorkspaceReorder helpers, which the store now owns.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreWorkspaceActionsTest {

    private fun ws(id: String, status: String = "active", archivedAt: String? = null, sortOrder: Int = 0) =
        WorkspaceDto(id = id, name = id, status = status, workdir = "/w", archivedAt = archivedAt, sortOrder = sortOrder)

    private fun store(scope: kotlinx.coroutines.CoroutineScope): HostStore {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        return HostStore(
            "http://h", "t", scope, testDeps(http = http),
            connectOnInit = false, apiOverride = BrokerApi("http://h", "t", http),
        )
    }

    @Test fun archiveWorkspaceMovesFromLiveToArchived() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this)
        s.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"))))
        s.archiveWorkspace("w1")
        assertTrue(s.workspaces.value.isEmpty())
        assertEquals("archived", s.archivedWorkspaces.value.single().status)
        advanceUntilIdle(); s.close()
    }

    @Test fun restoreWorkspaceMovesFromArchivedToLive() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this)
        s.reduce(
            ServerFrame.Snapshot(
                archivedWorkspaces = listOf(ws("w1", status = "archived", archivedAt = "t"), ws("w2", status = "archived")),
            ),
        )
        s.restoreWorkspace("w1")
        assertEquals(listOf("w1"), s.workspaces.value.map { it.id })
        assertEquals("active", s.workspaces.value.single().status)
        assertNull(s.workspaces.value.single().archivedAt)
        assertEquals(listOf("w2"), s.archivedWorkspaces.value.map { it.id })
        advanceUntilIdle(); s.close()
    }

    @Test fun switchModelPatchesTheSessionRowOptimistically() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this)
        s.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(
                    SessionInfo(id = "s1", name = "S", agent = "claude", workdir = "/w", model = "old"),
                ),
            ),
        )
        withContext(Dispatchers.Default) { s.switchModel("s1", "opus-5") }
        assertEquals("opus-5", s.sessions.value.single().model)
        advanceUntilIdle(); s.close()
    }

    @Test fun switchReasoningPatchesTheSessionRowOptimistically() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this)
        s.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(
                    SessionInfo(id = "s1", name = "S", agent = "claude", workdir = "/w", reasoningLevel = "low"),
                ),
            ),
        )
        withContext(Dispatchers.Default) { s.switchReasoning("s1", "high") }
        assertEquals("high", s.sessions.value.single().reasoningLevel)
        advanceUntilIdle(); s.close()
    }

    @Test fun reorderWorkspacesRewritesSortOrderOptimistically() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this)
        s.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("wa", sortOrder = 0), ws("wb", sortOrder = 1))))
        s.reorderWorkspaces(listOf("wb", "wa"))
        assertEquals(mapOf("wa" to 1, "wb" to 0), s.workspaces.value.associate { it.id to it.sortOrder })
        advanceUntilIdle(); s.close()
    }
}
