package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The typed usage seam `FleetStore` gained in cluster E6, when Android's hand-written `org.json`
 * parser (and the `usageRaw(): String?` wrapper that fed it) were deleted: [FleetStore.usage] and
 * [FleetStore.refreshUsage] decode into the shared DTOs, [FleetStore.usageSnapshot] republishes the
 * ACTIVE host's held snapshot, and [FleetStore.applyUsage] is the in-place swap the Codex redeem
 * needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreUsageTest {
    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private fun store(vararg h: PairedHost) =
        PairedHostStore(FakePersistence(h.toMutableList())) { "rec" }

    /** `GET /usage` returns 12%/pro for host A and 77%/plus for host B; `POST /usage/refresh` bumps. */
    private fun usageClient(url: String): HttpClient {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val plan = if (url.contains("h-a")) "pro" else "plus"
        val used = if (url.contains("h-a")) 12.0 else 77.0
        return HttpClient(
            MockEngine { req ->
                when {
                    req.url.encodedPath == "/usage" && req.method == HttpMethod.Get -> respond(
                        """{"claude":{"fiveHour":{"used":$used},"sevenDay":{"used":40.0}},""" +
                            """"codex":{"plan":"$plan","windows":[],"limitReached":false,"resetCredits":2}}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                    req.url.encodedPath == "/usage/refresh" -> respond(
                        """{"claude":{"fiveHour":{"used":99.0},"sevenDay":{"used":40.0}},"refreshing":["claude"]}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                    else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
                }
            },
        )
    }

    private fun fleetOf(scope: kotlinx.coroutines.CoroutineScope, vararg hosts: PairedHost): FleetStore {
        val s = store(*hosts)
        return FleetStore(
            store = s, scope = scope, deps = testDeps(),
            appFactory = { url, token, onConn ->
                HostStore(
                    url, token, scope, testDeps(http = usageClient(url)), connectOnInit = false,
                    onConnectionChange = onConn,
                )
            },
        )
    }

    private val hostA = PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev")
    private val hostB = PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev")

    @Test fun usage_decodes_the_typed_dto_from_the_active_host() = runTest(UnconfinedTestDispatcher()) {
        val fleet = fleetOf(this, hostA)
        val u = fleet.usage()
        assertNotNull(u)
        assertEquals(12.0, u.claude?.fiveHour?.used)
        assertEquals("pro", u.codex?.plan)
        assertEquals(2, u.codex?.resetCredits)
        fleet.close()
    }

    @Test fun usage_seeds_the_snapshot_and_the_snapshot_follows_the_active_host() =
        runTest(UnconfinedTestDispatcher()) {
            val fleet = fleetOf(this, hostA, hostB)
            advanceUntilIdle()
            assertNull(fleet.usageSnapshot.value)

            fleet.setActiveHost("h1")
            fleet.usage()
            advanceUntilIdle()
            assertEquals(12.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)

            // Switching hosts republishes THAT host's snapshot — nothing fetched yet, so null…
            fleet.setActiveHost("h2")
            advanceUntilIdle()
            assertNull(fleet.usageSnapshot.value)
            // …and its own fetch fills it with its own numbers.
            fleet.usage()
            advanceUntilIdle()
            assertEquals(77.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)
            fleet.close()
        }

    @Test fun refreshUsage_replaces_the_snapshot_with_the_refreshing_payload() =
        runTest(UnconfinedTestDispatcher()) {
            val fleet = fleetOf(this, hostA)
            fleet.usage()
            advanceUntilIdle()
            assertEquals(12.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)

            val refreshed = fleet.refreshUsage()
            advanceUntilIdle()
            assertEquals(99.0, refreshed?.claude?.fiveHour?.used)
            assertEquals(listOf("claude"), refreshed?.refreshing)
            assertEquals(99.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)
            fleet.close()
        }

    @Test fun applyUsage_swaps_the_held_snapshot_in_place() = runTest(UnconfinedTestDispatcher()) {
        val fleet = fleetOf(this, hostA)
        fleet.usage()
        advanceUntilIdle()
        val held = assertNotNull(fleet.usageSnapshot.value)
        fleet.applyUsage(held.copy(codex = held.codex?.copy(resetCredits = 1)))
        advanceUntilIdle()
        assertEquals(1, fleet.usageSnapshot.value?.codex?.resetCredits)
        fleet.close()
    }

    @Test fun forgetting_a_host_prunes_its_usage_snapshot() = runTest(UnconfinedTestDispatcher()) {
        val fleet = fleetOf(this, hostA, hostB)
        fleet.setActiveHost("h1")
        fleet.usage()
        advanceUntilIdle()
        assertEquals(12.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)

        fleet.forgetHost("h1")
        advanceUntilIdle()
        // h1's cached usage went with it; the snapshot now follows whatever host is left (h2),
        // which has not fetched — NOT h1's stale numbers.
        assertNull(fleet.usageSnapshot.value)
        fleet.setActiveHost("h2")
        fleet.usage()
        advanceUntilIdle()
        assertEquals(77.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)
        fleet.close()
    }

    @Test fun a_snapshot_is_filed_against_the_host_the_fetch_started_on() =
        runTest(UnconfinedTestDispatcher()) {
            val fleet = fleetOf(this, hostA, hostB)
            fleet.setActiveHost("h1")
            fleet.usage()
            advanceUntilIdle()
            // Switch AFTER the fetch resolved: h1's numbers must not have been filed under h2.
            fleet.setActiveHost("h2")
            advanceUntilIdle()
            assertNull(fleet.usageSnapshot.value)
            fleet.setActiveHost("h1")
            advanceUntilIdle()
            assertEquals(12.0, fleet.usageSnapshot.value?.claude?.fiveHour?.used)
            fleet.close()
        }

    @Test fun usage_with_no_paired_host_is_null_not_a_crash() = runTest(UnconfinedTestDispatcher()) {
        val fleet = fleetOf(this)
        assertNull(fleet.usage())
        assertNull(fleet.refreshUsage())
        assertNull(fleet.usageSnapshot.value)
        fleet.close()
    }
}
