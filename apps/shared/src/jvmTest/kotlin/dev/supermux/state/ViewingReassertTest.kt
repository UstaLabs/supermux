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

/**
 * The presence keep-alive the BROWSER needs: the broker drops a viewing entry after 5 minutes
 * (`src/core/push/viewing-tracker.ts:22`), so a host that has said "I am looking at s1" must say it
 * again periodically. [HostStore.reassertViewing] is that "say it again" — no state change, no
 * dedupe, just the last frame on the wire once more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewingReassertTest {
    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    @Test fun reassertResendsTheLastViewingFrame() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore(
            "https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
        )
        app.updateViewing("s1", visible = true)
        val afterUpdate = sent.filterIsInstance<ClientFrame.Viewing>()
        assertEquals(listOf(ClientFrame.Viewing("s1", true)), afterUpdate)

        app.reassertViewing()
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true), ClientFrame.Viewing("s1", true)),
            sent.filterIsInstance<ClientFrame.Viewing>(),
        )
        app.close()
    }

    @Test fun reassertResendsTheMultiChatFormWholesale() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore(
            "https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
        )
        app.updateViewingSessions(listOf("s1", "s2"), visible = true)
        sent.clear()
        app.reassertViewing()
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true, listOf("s1", "s2"))),
            sent.filterIsInstance<ClientFrame.Viewing>(),
        )
        app.close()
    }

    @Test fun reassertBeforeAnyViewingSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore(
            "https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
        )
        app.reassertViewing()
        assertTrue(sent.isEmpty(), sent.toString())
        app.close()
    }

    @Test fun fleetReassertGoesToTheActiveHost() = runTest(UnconfinedTestDispatcher()) {
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
        fleet.updateViewing(WorkspaceViewingSnapshot("wB", listOf("s2"), appForeground = true))
        val bUrl = "https://h-b.relay.supermux.dev"
        frames.values.forEach { it.clear() }

        fleet.reassertViewing()

        assertEquals(
            listOf(ClientFrame.Viewing("s2", true)),
            frames[bUrl].orEmpty().filterIsInstance<ClientFrame.Viewing>(),
        )
        assertTrue(
            frames["https://h-a.relay.supermux.dev"].orEmpty().isEmpty(),
            frames.toString(),
        )
        fleet.close()
    }
}
