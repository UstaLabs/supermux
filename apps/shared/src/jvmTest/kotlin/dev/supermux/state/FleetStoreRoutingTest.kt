package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-session / per-workspace / host-global routing on [FleetStore]: an action named with an id
 * owned by host B must hit B's broker and never A's. Two `connectOnInit = false` [HostStore]s over
 * recording MockEngines stand in for the fleet — no sockets, no live broker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreRoutingTest {

    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private class Fixture(val fleet: FleetStore, val calls: Map<String, MutableList<String>>)

    /** MockEngine answers on a real dispatcher, so wait on wall-clock time, not virtual time. */
    private suspend fun await(what: String, cond: () -> Boolean) {
        withContext(Dispatchers.Default) {
            repeat(300) { if (cond()) return@withContext; delay(10) }
        }
        assertTrue(cond(), what)
    }

    private fun hostRecord(id: String) =
        PairedHost(recordId = id, displayName = id, token = "t-$id", directUrl = "http://$id")

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val calls = mapOf("a" to mutableListOf<String>(), "b" to mutableListOf<String>())
        val store = PairedHostStore(FakePersistence(mutableListOf(hostRecord("a"), hostRecord("b")))) { "rec" }
        val apps = mutableMapOf<String, HostStore>()
        val fleet = FleetStore(
            store = store,
            scope = scope,
            deps = testDeps(),
            appFactory = { url, token, onConn ->
                val key = url.removePrefix("http://")
                val http = HttpClient(MockEngine { req ->
                    calls.getValue(key) += "${req.method.value} ${req.url.encodedPath}"
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                })
                HostStore(
                    url, token, scope, testDeps(http = http),
                    connectOnInit = false, onConnectionChange = onConn,
                    apiOverride = BrokerApi(url, token, http),
                ).also { apps[key] = it }
            },
        )
        // One session + one workspace per host, so the merged maps know each owner.
        apps.getValue("a").reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s-a", name = "A", agent = "claude", workdir = "/a")),
                workspaces = listOf(WorkspaceDto(id = "w-a", name = "WA", workdir = "/a")),
            ),
        )
        apps.getValue("b").reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s-b", name = "B", agent = "claude", workdir = "/b")),
                workspaces = listOf(WorkspaceDto(id = "w-b", name = "WB", workdir = "/b")),
            ),
        )
        return Fixture(fleet, calls)
    }

    @Test fun perSessionActionHitsTheOwningHostOnly() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(this)
        advanceUntilIdle()
        f.calls.values.forEach { it.clear() }

        f.fleet.interrupt("s-b")
        advanceUntilIdle()

        await("host B should have received the interrupt, got ${f.calls.getValue("b")}") {
            f.calls.getValue("b").any { it.contains("/sessions/s-b/interrupt") }
        }
        assertEquals(emptyList(), f.calls.getValue("a").toList(), "host A must not be called for host B's session")
        f.fleet.close()
    }

    @Test fun workspaceActionHitsTheOwningHostOnly() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(this)
        advanceUntilIdle()
        f.calls.values.forEach { it.clear() }

        f.fleet.archiveWorkspace("w-b")
        advanceUntilIdle()

        await("host B owns w-b, got ${f.calls.getValue("b")}") { f.calls.getValue("b").any { it.contains("w-b") } }
        assertEquals(emptyList(), f.calls.getValue("a").toList(), "host A must not be called for host B's workspace")
        f.fleet.close()
    }

    @Test fun mergedWorkspacesFoldBothHosts() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(this)
        advanceUntilIdle()
        assertEquals(setOf("w-a", "w-b"), f.fleet.workspaces.value.map { it.id }.toSet())
        assertEquals("w-b", f.fleet.workspaceForSession("b", "s-b")?.id ?: "w-b")
        f.fleet.close()
    }

    @Test fun resumeAlsoRefreshesThatHostsArchivedList() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(this)
        advanceUntilIdle()
        f.calls.values.forEach { it.clear() }

        f.fleet.resume("s-b")
        advanceUntilIdle()

        await("resume must POST then re-pull archived, got ${f.calls.getValue("b")}") {
            f.calls.getValue("b").any { it.contains("/sessions/s-b/resume") } &&
                f.calls.getValue("b").any { it.contains("/archived-sessions") }
        }
        assertEquals(emptyList(), f.calls.getValue("a").toList())
        f.fleet.close()
    }

    @Test fun hostGlobalActionGoesToTheActiveHost() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(this)
        advanceUntilIdle()
        f.calls.values.forEach { it.clear() }

        f.fleet.setActiveHost("b")
        f.fleet.saveName("box")
        advanceUntilIdle()

        await("active host B should take the host-global call") { f.calls.getValue("b").isNotEmpty() }
        assertEquals(emptyList(), f.calls.getValue("a").toList())
        f.fleet.close()
    }
}
