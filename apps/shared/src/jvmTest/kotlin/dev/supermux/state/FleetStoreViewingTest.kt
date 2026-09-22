package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.WorkspaceViewingSnapshot
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreViewingTest {
    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    @Test fun switchingVisibleChatFromHostAToHostBClearsAThenSendsToB() = runTest(UnconfinedTestDispatcher()) {
        val store = PairedHostStore(
            FakePersistence(
                mutableListOf(
                    PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
                    PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
                ),
            ),
        ) { "rec" }
        val frames = mutableMapOf<String, MutableList<ClientFrame>>()
        val fleet = FleetStore(
            store = store,
            scope = this,
            deps = testDeps(),
            appFactory = { url, token, onConn ->
                HostStore(
                    url, token, this, testDeps(), connectOnInit = false, onConnectionChange = onConn,
                    sendFrameOverride = { frames.getOrPut(url) { mutableListOf() }.add(it) },
                )
            },
        )
        fleet.appForRecord("h1")!!.reduce(
            ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "s1", name = "s1", workdir = "/w", agent = "claude"))),
        )
        fleet.appForRecord("h2")!!.reduce(
            ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "s2", name = "s2", workdir = "/w", agent = "claude"))),
        )
        fleet.updateViewing(WorkspaceViewingSnapshot("wA", listOf("s1"), appForeground = true))
        fleet.updateViewing(WorkspaceViewingSnapshot("wB", listOf("s2"), appForeground = true))
        val aUrl = "https://h-a.relay.supermux.dev"
        val bUrl = "https://h-b.relay.supermux.dev"
        val a = frames[aUrl].orEmpty()
        val b = frames[bUrl].orEmpty()
        assertTrue(a.any { it is ClientFrame.Viewing && it.session == null && !it.visible }, a.toString())
        assertTrue(b.any { it is ClientFrame.Viewing && it.session == "s2" && it.visible }, b.toString())
        fleet.close()
    }
}
