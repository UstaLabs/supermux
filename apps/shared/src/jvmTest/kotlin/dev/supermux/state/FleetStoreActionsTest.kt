package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreActionsTest {
    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private fun store(vararg h: PairedHost) =
        PairedHostStore(FakePersistence(h.toMutableList())) { "rec" }

    @Test fun renameHostUpdatesStoreAndHostViews() = runTest(UnconfinedTestDispatcher()) {
        val s = store(PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"))
        val fleet = FleetStore(
            store = s, scope = this, deps = testDeps(),
            appFactory = { url, token, onConn ->
                HostStore(url, token, this, testDeps(), connectOnInit = false, onConnectionChange = onConn)
            },
        )
        fleet.renameHost("h1", "Ada")
        assertEquals("Ada", s.list().single().displayName)
        assertEquals("Ada", fleet.hostViews.value.single { it.recordId == "h1" }.displayName)
        fleet.close()
    }

    @Test fun saveHostFilterRoundTripAndBlankReadsAsNull() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettingsStore()
        val deps = testDeps(settings = settings)
        val s = store(PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"))
        val fleet = FleetStore(
            store = s, scope = this, deps = deps,
            appFactory = { url, token, onConn ->
                HostStore(url, token, this, deps, connectOnInit = false, onConnectionChange = onConn)
            },
        )
        fleet.saveHostFilter("h1"); advanceUntilIdle()
        assertEquals("h1", fleet.hostFilter.first())
        settings.putString(SettingsKeys.HOST_FILTER, "")
        assertEquals(null, fleet.hostFilter.first())
        fleet.close()
    }

    @Test fun refreshArchivedFansOutAndMerges() = runTest(UnconfinedTestDispatcher()) {
        val s = store(
            PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
            PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
        )
        val archivedHits = mutableListOf<String>()
        val fleet = FleetStore(
            store = s, scope = this, deps = testDeps(),
            appFactory = { url, token, onConn ->
                val http = HttpClient(MockEngine { req ->
                    if (req.url.encodedPath == "/archived-sessions") {
                        archivedHits += url
                        val id = if (url.contains("h-a")) "a1" else "b1"
                        respond(
                            """[{"id":"$id","name":"$id","workdir":"/w","agent":"claude"}]""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    } else {
                        respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                })
                HostStore(
                    url, token, this, testDeps(http = http), connectOnInit = false,
                    onConnectionChange = onConn, apiOverride = BrokerApi(url, token, http),
                )
            },
        )
        fleet.refreshArchived()
        val start = System.currentTimeMillis()
        while (fleet.archivedSessions.value.map { it.id }.toSet() != setOf("a1", "b1")) {
            if (System.currentTimeMillis() - start > 5_000) {
                throw AssertionError("archived=${fleet.archivedSessions.value} hits=$archivedHits")
            }
            withContext(Dispatchers.Default) { delay(20) }
        }
        fleet.close()
    }

    @Test fun closeRecordCancelsHostProjections() = runTest(UnconfinedTestDispatcher()) {
        val s = store(PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"))
        val fleet = FleetStore(
            store = s, scope = this, deps = testDeps(),
            appFactory = { url, token, onConn ->
                HostStore(url, token, this, testDeps(), connectOnInit = false, onConnectionChange = onConn)
            },
        )
        val host = fleet.appForRecord("h1")!!
        assertTrue(host.projectionsActive)
        fleet.close("h1")
        assertFalse(host.projectionsActive)
        fleet.close()
    }
}
