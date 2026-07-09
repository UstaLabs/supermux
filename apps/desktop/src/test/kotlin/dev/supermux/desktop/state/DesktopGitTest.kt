package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.GitOpResult
import dev.supermux.net.ProxyDto
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
 * M4c Task 1: the `DesktopAppState` git-op (fetch/pull/push/publish) + proxies wrappers.
 *
 * Mirrors [DesktopFinishTest]'s MockEngine layer: BrokerApi is a final concrete class, so the
 * `apiOverride` seam takes a real instance constructed against a ktor [MockEngine] HttpClient —
 * no live broker required. Each wrapper is asserted for its exact HTTP method + path (matching
 * [BrokerApi.gitFetch]/[BrokerApi.gitPull]/[BrokerApi.gitPush]/[BrokerApi.gitPublish], all bare
 * `POST /sessions/<id>/git/<op>` with no body, and [BrokerApi.proxies]'s `GET /proxies`), that a
 * 2xx response decodes into the real DTO, and that a 5xx degrades to null (or emptyList for
 * proxies) via [DesktopAppState.runApi] — Android AppViewModel:566-569/871 parity (there via an
 * `onResult` callback + `runCatching{}.getOrNull()`; here as a plain suspend fun returning the
 * same getOrNull-degraded result).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopGitTest {

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

    // ── gitFetch ────────────────────────────────────────────────────────────────────

    @Test fun git_fetch_posts_to_the_fetch_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"status":"ok","message":"up to date","files":["a.txt"]}""")

        val result: GitOpResult? = app.gitFetch("sess-1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/git/fetch", rec.path)
        assertEquals(GitOpResult(status = "ok", message = "up to date", files = listOf("a.txt")), result)
    }

    @Test fun git_fetch_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.gitFetch("sess-1")

        assertNull(result)
    }

    // ── gitPull ─────────────────────────────────────────────────────────────────────

    @Test fun git_pull_posts_to_the_pull_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"status":"ok","files":["b.txt"]}""")

        val result = app.gitPull("sess-1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/git/pull", rec.path)
        assertEquals(GitOpResult(status = "ok", files = listOf("b.txt")), result)
    }

    @Test fun git_pull_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.gitPull("sess-1"))
    }

    // ── gitPush ─────────────────────────────────────────────────────────────────────

    @Test fun git_push_posts_to_the_push_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"status":"pushed"}""")

        val result = app.gitPush("sess-1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/git/push", rec.path)
        assertEquals(GitOpResult(status = "pushed"), result)
    }

    @Test fun git_push_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.gitPush("sess-1"))
    }

    // ── gitPublish ──────────────────────────────────────────────────────────────────

    @Test fun git_publish_posts_to_the_publish_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"status":"published"}""")

        val result = app.gitPublish("sess-1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/git/publish", rec.path)
        assertEquals(GitOpResult(status = "published"), result)
    }

    @Test fun git_publish_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.gitPublish("sess-1"))
    }

    // ── proxies ─────────────────────────────────────────────────────────────────────

    @Test fun proxies_gets_the_proxies_path_and_decodes_the_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """[{"domain":"a.example.com","sessionName":"sess-1","port":3000,"isPublic":true,"url":"https://a.example.com"}]""",
        )

        val result: List<ProxyDto> = app.proxies()

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/proxies", rec.path)
        assertEquals(
            listOf(ProxyDto(domain = "a.example.com", sessionName = "sess-1", port = 3000, isPublic = true, url = "https://a.example.com")),
            result,
        )
    }

    @Test fun proxies_returns_empty_list_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.proxies()

        assertTrue(result.isEmpty())
    }
}
