package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.net.BrokerApi
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.WorkspaceDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreProjectsTest {

    private fun store(
        scope: CoroutineScope,
        body: String = "{}",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HostStore {
        val http = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })
        return HostStore(
            "http://h", "t", scope, testDeps(http = http),
            connectOnInit = false, apiOverride = BrokerApi("http://h", "t", http),
        )
    }

    private fun project(id: String, sortOrder: Int = 0) = ProjectDto(id = id, name = id, sortOrder = sortOrder)

    @Test fun projectsFlowFollowsSnapshotAndProjectsChanged() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this)
        assertFalse(s.projectCatalogKnown.value)
        s.reduce(ServerFrame.Snapshot(projects = listOf(project("p1"))))
        assertEquals(listOf("p1"), s.projects.value.map { it.id })
        assertTrue(s.projectCatalogKnown.value)
        s.reduce(ServerFrame.ProjectsChanged(projects = listOf(project("p2"))))
        assertEquals(listOf("p2"), s.projects.value.map { it.id })
        advanceUntilIdle(); s.close()
    }

    @Test fun reorderProjectsIsOptimistic() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this, body = """{"ok":true}""")
        s.reduce(ServerFrame.Snapshot(projects = listOf(project("a", 0), project("b", 1))))
        val ok = withContext(Dispatchers.Default) { s.reorderProjects(listOf("b", "a")) }
        assertTrue(ok)
        assertEquals(0, s.projects.value.first { it.id == "b" }.sortOrder)
        assertEquals(1, s.projects.value.first { it.id == "a" }.sortOrder)
        advanceUntilIdle(); s.close()
    }

    @Test fun createProjectUpsertsTheReturnedProject() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this, body = """{"id":"p9","name":"New"}""", status = HttpStatusCode.Created)
        val p = withContext(Dispatchers.Default) { s.createProject("New") }
        assertEquals("p9", p?.id)
        assertEquals(listOf("p9"), s.projects.value.map { it.id })
        advanceUntilIdle(); s.close()
    }

    @Test fun addProjectLocationConflictNamesTheOwner() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this, body = """{"error":"taken","projectId":"p7"}""", status = HttpStatusCode.Conflict)
        val res = withContext(Dispatchers.Default) { s.addProjectLocation("p1", "/a") }
        assertEquals(ProjectLocationResult.Conflict("p7"), res)
        advanceUntilIdle(); s.close()
    }

    @Test fun addProjectLocationOtherFailureIsFailed() = runTest(UnconfinedTestDispatcher()) {
        val s = store(this, body = """{"error":"bad"}""", status = HttpStatusCode.BadRequest)
        val res = withContext(Dispatchers.Default) { s.addProjectLocation("p1", "rel") }
        assertEquals(ProjectLocationResult.Failed, res)
        advanceUntilIdle(); s.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreProjectsTest {
    private class FakePersistence(var hosts: MutableList<PairedHost>) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    @Test fun projectsAreTaggedWithTheOwningRecordId() = runTest(UnconfinedTestDispatcher()) {
        val s = PairedHostStore(
            FakePersistence(
                mutableListOf(
                    PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
                    PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
                ),
            ),
        ) { "rec" }
        val scope = this
        val f = FleetStore(
            store = s, scope = scope, deps = testDeps(),
            appFactory = { url, token, onConn -> HostStore(url, token, scope, testDeps(), connectOnInit = false, onConnectionChange = onConn) },
        )
        f.appForRecord("h1")!!.reduce(
            ServerFrame.Snapshot(
                workspaces = listOf(WorkspaceDto(id = "w1", name = "w1", workdir = "/a")),
                projects = listOf(ProjectDto(id = "p", name = "A")),
            ),
        )
        f.appForRecord("h2")!!.reduce(ServerFrame.Snapshot(projects = listOf(ProjectDto(id = "p", name = "B"))))
        assertEquals(
            listOf(HostProject("h1", ProjectDto(id = "p", name = "A")), HostProject("h2", ProjectDto(id = "p", name = "B"))),
            f.projects.value,
        )
        assertEquals("h1", f.hostIdForWorkspace("w1"))

        f.forgetHost("h1")
        assertEquals(listOf("h2"), f.projects.value.map { it.hostId })
        f.close()
    }
}
