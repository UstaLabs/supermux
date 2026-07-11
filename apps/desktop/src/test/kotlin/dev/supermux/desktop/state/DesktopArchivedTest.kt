package dev.supermux.desktop.state

import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.proto.LogEntry
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M4e Task 1: the `DesktopAppState` archived-sessions (archived list / resume / archivedLogs)
 * wrappers. Mirrors [DesktopGitTest]'s MockEngine layer: BrokerApi is a final concrete class, so
 * the `apiOverride` seam takes a real instance constructed against a ktor [MockEngine] HttpClient
 * — no live broker required. Each wrapper is asserted for its exact HTTP method + path (matching
 * [BrokerApi.archived]'s `GET /archived-sessions`, [BrokerApi.resume]'s bare
 * `POST /sessions/<id>/resume`, and [BrokerApi.archivedLogs]'s `GET /sessions/<id>/messages`),
 * that a 2xx response decodes into the real DTO, and that a 5xx degrades gracefully via
 * [DesktopAppState.runApi] — Android AppViewModel:677-678/764-765 parity (there via
 * `runCatching{}.getOrNull()` / `.getOrNull() ?: emptyList()`; here as plain suspend funs
 * returning the same getOrNull-degraded result).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopArchivedTest {

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

    // ── archived ────────────────────────────────────────────────────────────────────

    @Test fun archived_gets_the_archived_sessions_path_and_decodes_the_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """[{"id":"sess-1","name":"fix bug","workdir":"/repo","agent":"claude",
                |"killed_at":"2026-07-01T00:00:00Z","repo_root":"/repo"}]""".trimMargin(),
        )

        val result: List<ArchivedDto> = app.archived()

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/archived-sessions", rec.path)
        assertEquals(
            listOf(
                ArchivedDto(
                    id = "sess-1",
                    name = "fix bug",
                    workdir = "/repo",
                    agent = "claude",
                    killed_at = "2026-07-01T00:00:00Z",
                    repo_root = "/repo",
                ),
            ),
            result,
        )
    }

    @Test fun archived_returns_empty_list_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.archived()

        assertTrue(result.isEmpty())
    }

    // ── resume ──────────────────────────────────────────────────────────────────────

    @Test fun resume_posts_to_the_resume_path_and_returns_true_on_success() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = "")

        val result = app.resume("sess-1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/resume", rec.path)
        assertTrue(result)
    }

    /**
     * [BrokerApi.resume] is a bare `http.post` with no [BrokerApi.decode]/status check (unlike
     * e.g. `finish`, which decodes and so throws on non-2xx) — a 5xx from the broker completes
     * the HTTP round-trip normally and does NOT throw, so [DesktopAppState.resume]'s
     * `runCatching{}.isSuccess` stays true on a 5xx (verified below). The only thing that can
     * degrade it to false is a genuine transport failure (connection refused, timeout, ...),
     * covered by [resume_returns_false_on_a_transport_failure].
     */
    @Test fun resume_returns_true_even_on_a_5xx_because_BrokerApi_resume_does_not_check_status() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.resume("sess-1")

        assertTrue(result)
    }

    @Test fun resume_returns_false_on_a_transport_failure() = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        val app = DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            apiOverride = api,
        )

        val result = app.resume("sess-1")

        assertFalse(result)
    }

    // ── archivedLogs ────────────────────────────────────────────────────────────────

    @Test fun archived_logs_gets_the_messages_path_and_decodes_the_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """[{"id":"log-1","ts":"2026-07-01T00:00:00Z","direction":"out","text":"hello"}]""",
        )

        val result: List<LogEntry> = app.archivedLogs("sess-1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/sessions/sess-1/messages", rec.path)
        assertEquals(
            listOf(LogEntry(id = "log-1", ts = "2026-07-01T00:00:00Z", direction = "out", text = "hello")),
            result,
        )
    }

    @Test fun archived_logs_returns_empty_list_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.archivedLogs("sess-1")

        assertTrue(result.isEmpty())
    }
}
