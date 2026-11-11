package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.HostSnapshot
import dev.supermux.host.HostSnapshotStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.SnapshotPersistence
import dev.supermux.net.BrokerApi
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #121: the offline cache every platform writes, and what it puts on screen before a host connects. */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreSnapshotTest {
    private class FakePersistence(var hosts: MutableList<PairedHost>) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private class FakeSnapshots(var rows: MutableList<HostSnapshot> = mutableListOf()) : SnapshotPersistence {
        override fun loadAll() = rows.toList()
        override fun saveAll(snapshots: List<HostSnapshot>) { rows = snapshots.toMutableList() }
    }

    private fun e(id: String) = LogEntry(id = id, ts = id, direction = "outbound", text = "m$id")
    private fun s(id: String) = SessionInfo(id = id, name = id, agent = "claude", workdir = "/w")

    private fun fleet(scope: CoroutineScope, cache: HostSnapshotStore) = FleetStore(
        store = PairedHostStore(FakePersistence(mutableListOf(PairedHost(recordId = "h1", displayName = "h1", token = "t", directUrl = "http://h1")))) { "rec" },
        scope = scope,
        deps = testDeps(),
        appFactory = { url, token, onConn ->
            val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
            HostStore(url, token, scope, testDeps(http = http), connectOnInit = false, onConnectionChange = onConn, apiOverride = BrokerApi(url, token, http))
        },
        snapshots = cache,
    )

    @Test fun aCachedChatIsOnScreenBeforeTheHostConnects() = runTest(UnconfinedTestDispatcher()) {
        // A cache with no workspaces (a phone's) still seeds the chats' messages.
        val cache = HostSnapshotStore(FakeSnapshots(mutableListOf(
            HostSnapshot("h1", listOf(s("a"), s("b")), recentLogs = mapOf("a" to listOf(e("1"), e("2"))), lastMessages = mapOf("b" to e("7"))),
        )))
        val f = fleet(this, cache)
        assertEquals(listOf(e("1"), e("2")), f.messages.value["a"])
        assertEquals(listOf(e("7")), f.messages.value["b"])
        f.close()
    }

    @Test fun writeSnapshotsCachesTheWholeLiveScreen() = runTest(UnconfinedTestDispatcher()) {
        val cache = HostSnapshotStore(FakeSnapshots())
        val f = fleet(this, cache)
        f.appForRecord("h1")!!.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(s("a")),
                logs = mapOf("a" to listOf(e("1"), e("2"))),
                workspaces = listOf(WorkspaceDto(id = "w1", name = "W", workdir = "/w")),
            ),
        )
        f.writeSnapshots()
        val snap = cache.get("h1")!!
        assertEquals(listOf("a"), snap.sessions.map { it.id })
        assertEquals(listOf("w1"), snap.workspaces.map { it.id })
        assertEquals(mapOf("a" to listOf(e("1"), e("2"))), snap.recentLogs)
        assertEquals(mapOf("a" to e("2")), snap.lastMessages)
        f.close()
    }

    @Test fun aCachedChatKeepsItsReadMarkBeforeTheHostConnects() = runTest(UnconfinedTestDispatcher()) {
        // Card #121 (iOS): without the read marks every saved chat showed unread until the snapshot.
        val cache = HostSnapshotStore(FakeSnapshots(mutableListOf(
            HostSnapshot("h1", listOf(s("a")), lastMessages = mapOf("a" to e("7")), lastRead = mapOf("a" to "7")),
        )))
        val f = fleet(this, cache)
        assertEquals("7", f.lastRead.value["a"])
        f.close()
    }

    @Test fun writeSnapshotsCachesTheReadMarks() = runTest(UnconfinedTestDispatcher()) {
        val cache = HostSnapshotStore(FakeSnapshots())
        val f = fleet(this, cache)
        f.appForRecord("h1")!!.reduce(
            ServerFrame.Snapshot(sessions = listOf(s("a")), logs = mapOf("a" to listOf(e("1"))), reads = mapOf("a" to "1")),
        )
        f.writeSnapshots()
        assertEquals(mapOf("a" to "1"), cache.get("h1")!!.lastRead)
        f.close()
    }
}
