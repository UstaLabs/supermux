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

    // ── onboarded mirrors the ACTIVE host (per-broker flag, like usageSnapshot — not a merge) ──

    @Test fun onboardedFollowsTheActiveHostAndSetOnboardedDelegates() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath}"
            bodies += (req.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            respond("{}", HttpStatusCode.OK, headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        })
        val s = store(
            PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
            PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
        )
        val fleet = FleetStore(
            store = s, scope = this, deps = testDeps(http = http),
            appFactory = { url, token, onConn ->
                HostStore(
                    url, token, this, testDeps(http = http), connectOnInit = false,
                    onConnectionChange = onConn, apiOverride = BrokerApi(url, token, http),
                )
            },
        )
        assertEquals(null, fleet.onboarded.value)
        fleet.setActiveHost("h1")
        fleet.appForRecord("h1")!!.reduce(dev.supermux.proto.ServerFrame.Snapshot(onboarded = false))
        fleet.appForRecord("h2")!!.reduce(dev.supermux.proto.ServerFrame.Snapshot(onboarded = true))
        // `flatMapLatest` hands the inner flow's value downstream over a channel, so the derived
        // StateFlow catches up on the next dispatch — not inside the caller's stack frame.
        advanceUntilIdle()
        assertEquals(false, fleet.onboarded.value)
        fleet.setActiveHost("h2")
        advanceUntilIdle()
        assertEquals(true, fleet.onboarded.value)

        fleet.setActiveHost("h1")
        advanceUntilIdle()
        assertEquals(false, fleet.onboarded.value)
        assertTrue(fleet.setOnboarded(true))
        assertTrue(seen.any { it == "PUT /settings/config" }, seen.toString())
        assertTrue(bodies.any { it == """{"onboarded":true}""" }, bodies.toString())
        // The PUT's continuation resumes off the test scheduler, so the derived flow catches up in
        // real time rather than on `advanceUntilIdle` — poll, like `refreshArchivedFansOutAndMerges`.
        val start = System.currentTimeMillis()
        while (fleet.onboarded.value != true) {
            if (System.currentTimeMillis() - start > 5_000) {
                throw AssertionError("fleet.onboarded stayed ${fleet.onboarded.value}")
            }
            withContext(Dispatchers.Default) { delay(20) }
        }
        fleet.close()
    }

    // ── activeProjectCatalog follows the ACTIVE host's live HostStore (launcher catalog) ──

    @Test fun activeProjectCatalogFollowsHostSwitchAndConnectionRebuild() = runTest(UnconfinedTestDispatcher()) {
        val s = store(
            PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
            PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
        )
        val fleet = FleetStore(
            store = s, scope = this, deps = testDeps(),
            appFactory = { url, token, onConn ->
                HostStore(url, token, this, testDeps(), connectOnInit = false, onConnectionChange = onConn)
            },
        )
        fun catalog(vararg ids: String) =
            dev.supermux.proto.ServerFrame.ProjectsChanged(projects = ids.map { dev.supermux.proto.ProjectDto(id = it, name = it) })
        assertEquals(emptyList(), fleet.activeProjectCatalog.value)

        fleet.setActiveHost("h1")
        fleet.appForRecord("h1")!!.reduce(catalog("a1"))
        fleet.appForRecord("h2")!!.reduce(catalog("b1"))
        advanceUntilIdle()
        assertEquals(listOf("a1"), fleet.activeProjectCatalog.value.map { it.id })

        fleet.setActiveHost("h2")
        advanceUntilIdle()
        assertEquals(listOf("b1"), fleet.activeProjectCatalog.value.map { it.id })

        // A URL change closes and reopens the SAME record id: the catalog must bind to the new
        // HostStore — the dead one's list must not linger, and the new one's must come through.
        val old = fleet.appForRecord("h2")!!
        fleet.sync(s.list().map { if (it.recordId == "h2") it.copy(relayUrl = "https://h-b2.relay.supermux.dev") else it })
        advanceUntilIdle()
        val rebuilt = fleet.appForRecord("h2")!!
        assertTrue(rebuilt !== old)
        assertEquals(emptyList(), fleet.activeProjectCatalog.value)
        rebuilt.reduce(catalog("b2"))
        advanceUntilIdle()
        assertEquals(listOf("b2"), fleet.activeProjectCatalog.value.map { it.id })
        fleet.close()
    }
    // ── projectCatalogHosts: which hosts' brokers serve a project catalog ("New project" gate) ──

    @Test fun projectCatalogHostsTracksEachHostsKnownFlag() = runTest(UnconfinedTestDispatcher()) {
        val s = store(
            PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
            PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
        )
        val fleet = FleetStore(
            store = s, scope = this, deps = testDeps(),
            appFactory = { url, token, onConn ->
                HostStore(url, token, this, testDeps(), connectOnInit = false, onConnectionChange = onConn)
            },
        )
        advanceUntilIdle()
        // Neither broker has sent a catalog (an old broker never will).
        assertEquals(emptySet(), fleet.projectCatalogHosts.value)

        fleet.appForRecord("h2")!!.reduce(
            dev.supermux.proto.ServerFrame.ProjectsChanged(projects = emptyList()),
        )
        advanceUntilIdle()
        // An EMPTY catalog still counts: the broker speaks projects, it just has none yet.
        assertEquals(setOf("h2"), fleet.projectCatalogHosts.value)

        // A rebuild of h2 (URL change) starts from an unknown catalog again.
        fleet.sync(s.list().map { if (it.recordId == "h2") it.copy(relayUrl = "https://h-b2.relay.supermux.dev") else it })
        advanceUntilIdle()
        assertEquals(emptySet(), fleet.projectCatalogHosts.value)
        fleet.close()
    }
}
