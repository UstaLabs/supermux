package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web-parity scratch-terminal tab endpoints: `GET /api/term/list?session=` and
 * `POST /api/term/close`. Mirrors [BrokerApiSettingsTest]'s style — a capturing [MockEngine]
 * asserts the exact request shape (method / path / query / body) [BrokerApi] produces, since the
 * request body ([TermCloseBody]) is file-private and only observable through the public API.
 *
 * The broker handler (src/channels/web/index.ts `/api/term/list`) returns each terminal's
 * `createdAt` as epoch-**millis** (a JSON number = `created * 1000`, or `Date.now()`), NOT an ISO
 * string like the other `*createdAt` DTOs here — so [TerminalSummary.createdAt] must decode as Long.
 */
class BrokerApiTerminalsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun captured(
        body: String = "{}",
        sink: MutableList<HttpRequestData>,
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String =
        when (val c = this.body) {
            is io.ktor.http.content.TextContent -> c.text
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> error("unexpected body type: ${c::class.simpleName}")
        }

    // ── pure DTO decode ────────────────────────────────────────────────────────

    @Test fun terminal_list_decodes_createdAt_as_epoch_millis() {
        val r = json.decodeFromString<TerminalListResponse>(
            """{"terminals":[{"id":"main","createdAt":1717200000000},{"id":"t3f1","createdAt":1717200005000}]}""")
        assertEquals(2, r.terminals.size)
        assertEquals("main", r.terminals[0].id)
        assertEquals(1717200000000L, r.terminals[0].createdAt)
        assertEquals("t3f1", r.terminals[1].id)
        assertEquals(1717200005000L, r.terminals[1].createdAt)
    }

    @Test fun terminal_list_decodes_empty() {
        val r = json.decodeFromString<TerminalListResponse>("""{"terminals":[]}""")
        assertEquals(emptyList(), r.terminals)
    }

    // ── request shapes via MockEngine ──────────────────────────────────────────

    @Test fun list_terminals_gets_urlencoded_session_query() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"terminals":[{"id":"main","createdAt":1717200000000}]}""",
            sink = reqs,
        )
        // A name with a space exercises urlEncode (space → %20) on the query string.
        val terminals = api.listTerminals("my sess")
        val r = reqs.single()
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("http://h/api/term/list?session=my%20sess", r.url.toString())
        assertEquals(1, terminals.size)
        assertEquals("main", terminals[0].id)
        assertEquals(1717200000000L, terminals[0].createdAt)
    }

    @Test fun close_terminal_posts_session_and_terminal_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"ok":true}""", sink = reqs)
        api.closeTerminal("sess-1", "t3f1")
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/api/term/close", r.url.toString())
        assertEquals("""{"session":"sess-1","terminal":"t3f1"}""", r.bodyText())
    }
}
