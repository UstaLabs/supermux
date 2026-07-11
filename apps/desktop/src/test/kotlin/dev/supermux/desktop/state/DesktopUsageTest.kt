package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
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
 * M4f Task 1: the `DesktopAppState` usage-panel (usage / redeemCodexReset) wrappers. Mirrors
 * [DesktopArchivedTest]'s MockEngine layer: BrokerApi is a final concrete class, so the
 * `apiOverride` seam takes a real instance constructed against a ktor [MockEngine] HttpClient —
 * no live broker required. Each wrapper is asserted for its exact HTTP method + path (matching
 * [BrokerApi.usage]'s `GET /usage` and [BrokerApi.redeemCodexReset]'s `POST /usage/codex/reset`),
 * that a 2xx response decodes into the real DTO, and that a 5xx degrades gracefully to null via
 * [DesktopAppState.runApi] — same idiom as [DesktopAppState.archived]/[DesktopAppState.gitFetch].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopUsageTest {

    private data class Rec(val method: HttpMethod, val path: String)

    /** DesktopAppState whose BrokerApi answers every request with [body]/[status], recording
     *  each request's method + path into [recorded]. */
    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"status":"ok"}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            respond(ByteReadChannel(body), status, jsonHeaders)
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
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
                    "primaryWindow": {"used": 30.0, "resetsAt": 1799600000.0},
                    "secondaryWindow": {"used": 60.0, "resetsAt": 1799700000.0},
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
        assertEquals(30.0, result.codex?.primaryWindow?.used)
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
                    "primaryWindow": {"used": 0.0, "resetsAt": 1799600000.0},
                    "secondaryWindow": {"used": 0.0, "resetsAt": 1799700000.0},
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
}
