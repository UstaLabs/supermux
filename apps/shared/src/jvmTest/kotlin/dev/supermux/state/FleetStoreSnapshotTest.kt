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
import kotlinx.coroutines.flow.first
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

    private fun fleet(
        scope: CoroutineScope,
        cache: HostSnapshotStore,
        answer: (path: String) -> Pair<HttpStatusCode, ByteArray> = { HttpStatusCode.OK to "{}".encodeToByteArray() },
    ) = FleetStore(
        store = PairedHostStore(FakePersistence(mutableListOf(PairedHost(recordId = "h1", displayName = "h1", token = "t", directUrl = "http://h1")))) { "rec" },
        scope = scope,
        deps = testDeps(),
        appFactory = { url, token, onConn ->
            val http = HttpClient(MockEngine { req ->
                val (code, body) = answer(req.url.encodedPath)
                respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
            })
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

    private fun applet(id: String, pin: Int?) = dev.supermux.net.AppletDto(id = id, name = id, title = id.uppercase(), iconUrl = "/applets/$id/icon", pin = pin, updatedAt = "t1")

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test fun cachedAppletsAndTheirIconsAreThereWhileTheHostIsOutOfReach() = runTest(UnconfinedTestDispatcher()) {
        // #117: the grid, All applets and the widgets draw from the cache at launch; the host answers nothing.
        val png = byteArrayOf(1, 2, 3)
        val cache = HostSnapshotStore(FakeSnapshots(mutableListOf(
            HostSnapshot(
                "h1", listOf(s("a")),
                applets = listOf(applet("today", 0), applet("notes", 1)),
                appletIcons = mapOf("today" to kotlin.io.encoding.Base64.encode(png)),
            ),
        )))
        val f = fleet(this, cache) { HttpStatusCode.ServiceUnavailable to ByteArray(0) }
        val app = f.appForRecord("h1")!!
        assertEquals(listOf("today", "notes"), app.applets.value?.map { it.name })
        assertEquals(png.toList(), app.appletIconBytes(applet("today", 0))?.toList())
        // A snapshot from the host doesn't wipe them when its applet list can't be fetched.
        app.reduce(ServerFrame.Snapshot(sessions = listOf(s("a"))))
        assertEquals(listOf("today", "notes"), app.applets.value?.map { it.name })
        f.close()
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test fun writeSnapshotsCachesTheAppletsAndTheirIcons() = runTest(UnconfinedTestDispatcher()) {
        val cache = HostSnapshotStore(FakeSnapshots())
        val list = """[{"id":"today","name":"today","title":"Today","iconUrl":"/applets/today/icon","pin":0,"updatedAt":"t1"}]"""
        val f = fleet(this, cache) { path ->
            when (path) {
                "/applets" -> HttpStatusCode.OK to list.encodeToByteArray()
                "/applets/today/icon" -> HttpStatusCode.OK to byteArrayOf(9, 8)
                else -> HttpStatusCode.OK to "{}".encodeToByteArray()
            }
        }
        val app = f.appForRecord("h1")!!
        app.reduce(ServerFrame.Snapshot(sessions = listOf(s("a"))))
        app.refreshApplets()
        // The list arrives on the store's own scope, in real time.
        val listed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { app.applets.first { it != null }!! }
        }
        app.appletIconBytes(listed.single())
        f.writeSnapshots()
        val snap = cache.get("h1")!!
        assertEquals(listOf("today"), snap.applets?.map { it.name })
        assertEquals(listOf<Byte>(9, 8), kotlin.io.encoding.Base64.decode(snap.appletIcons.getValue("today")).toList())
        f.close()
    }
}
