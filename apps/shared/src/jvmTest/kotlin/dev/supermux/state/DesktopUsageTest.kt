package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.UsageResponse
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4f Task 1: the `HostStore` usage-panel (usage / redeemCodexReset) wrappers. Mirrors
 * [DesktopArchivedTest]'s MockEngine layer: BrokerApi is a final concrete class, so the
 * `apiOverride` seam takes a real instance constructed against a ktor [MockEngine] HttpClient —
 * no live broker required. Each wrapper is asserted for its exact HTTP method + path (matching
 * [BrokerApi.usage]'s `GET /usage` and [BrokerApi.redeemCodexReset]'s `POST /usage/codex/reset`),
 * that a 2xx response decodes into the real DTO, and that a 5xx degrades gracefully to null via
 * [HostStore.runApi] — same idiom as [HostStore.archived]/[HostStore.gitFetch].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopUsageTest {

    private data class Rec(val method: HttpMethod, val path: String)

    /** HostStore whose BrokerApi answers every request with [body]/[status], recording
     *  each request's method + path into [recorded]. */
    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"status":"ok"}""",
    ): HostStore {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            respond(ByteReadChannel(body), status, jsonHeaders)
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = api,
        )
    }

    // ── usage ───────────────────────────────────────────────────────────────────────

    @Test fun usage_gets_the_usage_path_and_decodes_the_response() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """
                {
                  "claude": {
                    "fiveHour": {"used": 12.5, "resetsAt": "2026-07-09T12:00:00Z"},
                    "sevenDay": {"used": 40.0, "resetsAt": "2026-07-14T00:00:00Z"},
                    "sevenDayFable": {"used": 5.0, "resetsAt": "2026-07-14T00:00:00Z"},
                    "extraUsage": {"enabled": true, "monthlyLimit": 100.0, "usedCredits": 10.0, "currency": "USD"}
                  },
                  "codex": {
                    "plan": "pro",
                    "windows": [
                      {"used": 30.0, "resetsAt": 1799600000.0, "label": "5-hour window", "windowSeconds": 18000.0},
                      {"used": 60.0, "resetsAt": 1799700000.0, "label": "7-day window", "windowSeconds": 604800.0}
                    ],
                    "limitReached": false,
                    "resetCredits": 3
                  },
                  "cursor": {
                    "totalPercentUsed": 20.0,
                    "totalSpendCents": 500.0,
                    "includedCents": 2000.0,
                    "limitCents": 2500.0
                  },
                  "errors": {"opencode": "not configured"}
                }
                """.trimIndent(),
        )

        val result = app.usage()

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/usage", rec.path)
        assertTrue(result != null)
        assertEquals(5.0, result.claude?.sevenDayFable?.used)
        assertEquals(30.0, result.codex?.windows?.first()?.used)
        assertEquals(3, result.codex?.resetCredits)
        assertEquals(20.0, result.cursor?.totalPercentUsed)
        assertEquals("not configured", result.errors["opencode"])
    }

    @Test fun usage_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.usage()

        assertNull(result)
    }

    // ── redeemCodexReset ────────────────────────────────────────────────────────────

    @Test fun redeem_codex_reset_posts_to_the_reset_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """
                {
                  "code": "reset",
                  "windowsReset": 1,
                  "codex": {
                    "plan": "pro",
                    "windows": [
                      {"used": 0.0, "resetsAt": 1799600000.0, "label": "5-hour window", "windowSeconds": 18000.0},
                      {"used": 0.0, "resetsAt": 1799700000.0, "label": "7-day window", "windowSeconds": 604800.0}
                    ],
                    "limitReached": false,
                    "resetCredits": 2
                  }
                }
                """.trimIndent(),
        )

        val result = app.redeemCodexReset()

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/usage/codex/reset", rec.path)
        assertTrue(result != null)
        assertEquals("reset", result.code)
        assertTrue(result.codex != null)
        assertEquals(2, result.codex?.resetCredits)
    }

    @Test fun redeem_codex_reset_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.redeemCodexReset()

        assertNull(result)
    }

    // ── refreshUsage + held snapshot ──────────────────────────────────────────

    @Test fun refresh_usage_posts_to_the_refresh_path_with_force_true() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"errors":{},"refreshing":["claude"],"fetchedAt":{"claude":"2026-01-01T00:00:00Z"},"source":{"claude":"live"}}""",
        )

        val result = app.refreshUsage()

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/usage/refresh", rec.path)
        assertTrue(result != null)
        assertEquals(listOf("claude"), result.refreshing)
        assertEquals("2026-01-01T00:00:00Z", result.fetchedAt["claude"])
        assertEquals(result, app.usageSnapshot.value)
    }

    @Test fun usage_get_holds_the_snapshot_in_app_state() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"errors":{"opencode":"not configured"},"fetchedAt":{"claude":"2026-01-02T03:04:05.000Z"}}""",
        )
        assertNull(app.usageSnapshot.value)

        val result = app.usage()

        assertEquals(result, app.usageSnapshot.value)
        assertEquals("2026-01-02T03:04:05.000Z", app.usageSnapshot.value?.fetchedAt?.get("claude"))
    }

    // ── an answer a usage_updated frame overtook (card #101) ──────────────────

    /**
     * HostStore whose broker pushes [frame] while the request is still in flight, then answers [body]: what a
     * broker does when every fetch of the refresh it just started settles at once (no sign-in found). Measured
     * on Windows 3 runs of 3: the last `usage_updated` (nothing refreshing) lands 250–450 ms before the answer,
     * which was snapshotted as the refresh started (everything refreshing).
     */
    private fun appOvertaken(frame: UsageResponse, body: String): HostStore {
        lateinit var app: HostStore
        val engine = MockEngine {
            app.reduce(ServerFrame.UsageUpdated(frame))
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        return app
    }

    private val settled = UsageResponse(
        errors = mapOf("claude" to "credentials not found or token expired", "codex" to "credentials not found"),
        refreshing = emptyList(),
    )
    private val startedBody =
        """{"errors":{"claude":"credentials not found or token expired","codex":"credentials not found"},"refreshing":["claude","codex"]}"""

    @Test fun a_usage_answer_a_usage_updated_frame_overtook_does_not_bring_the_spinners_back() = runTest {
        val app = appOvertaken(settled, startedBody)

        val result = app.usage()

        assertEquals(settled, app.usageSnapshot.value)
        assertEquals(emptyList(), app.usageSnapshot.value?.refreshing)
        assertEquals(settled, result)
    }

    @Test fun a_refresh_answer_a_usage_updated_frame_overtook_does_not_bring_the_spinners_back() = runTest {
        val app = appOvertaken(settled, startedBody)

        val result = app.refreshUsage()

        assertEquals(settled, app.usageSnapshot.value)
        assertEquals(emptyList(), app.usageSnapshot.value?.refreshing)
        assertEquals(settled, result)
    }

    @Test fun usage_updated_frame_replaces_the_held_snapshot() {
        val s = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
        )
        val snap = UsageResponse(
            fetchedAt = mapOf("codex" to "2026-04-01T00:00:00Z"),
            source = mapOf("codex" to "agent"),
            refreshing = listOf("cursor"),
        )

        s.reduce(ServerFrame.UsageUpdated(snap))

        assertEquals(snap, s.usageSnapshot.value)
        assertEquals(listOf("cursor"), s.usageSnapshot.value?.refreshing)
    }
}
