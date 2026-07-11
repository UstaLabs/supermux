package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.FsRefsResult
import dev.supermux.proto.SessionInfo
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

/**
 * Diff-base-selector wrappers on [DesktopAppState]: the `?base=<spec>` query threaded through
 * [DesktopAppState.fsDiff] and the new [DesktopAppState.fsRefs] endpoint that feeds the picker's
 * "Previous commit…" / "Another branch…" submenus. Same MockEngine layer as [DesktopDiffReviewTest]
 * (BrokerApi built against a ktor MockEngine via the `apiOverride` seam — no live broker).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDiffBaseTest {

    private data class Rec(val method: HttpMethod, val path: String, val baseParam: String?)

    private fun session(id: String = "sess-1") =
        SessionInfo(id = id, name = "name-$id", workdir = "/w/$id", agent = "claude")

    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"repos":[]}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath, req.url.parameters["base"]))
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
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

    // ── fsDiff(base) — the four base specs round-trip through the query ───────────────

    @Test fun fs_diff_omits_the_base_query_for_the_default() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"repos":[],"comments":[]}""")

        app.fsDiff(session("sess-1"))

        val rec = recorded.single()
        assertEquals("/sessions/sess-1/fs/diff", rec.path)
        assertNull(rec.baseParam) // null base → no ?base= sent at all (server picks session-start)
    }

    @Test fun fs_diff_sends_the_head_base_query() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"repos":[],"comments":[]}""")

        app.fsDiff(session("sess-1"), "head")

        assertEquals("head", recorded.single().baseParam)
    }

    @Test fun fs_diff_sends_a_commit_sha_base_query() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"repos":[],"comments":[]}""")

        app.fsDiff(session("sess-1"), "commit:abc1234")

        assertEquals("commit:abc1234", recorded.single().baseParam)
    }

    @Test fun fs_diff_sends_a_branch_name_base_query() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"repos":[],"comments":[]}""")

        app.fsDiff(session("sess-1"), "branch:feature/x")

        assertEquals("branch:feature/x", recorded.single().baseParam)
    }

    // ── fsRefs — the picker's branches + recent commits per repo ──────────────────────

    @Test fun fs_refs_gets_the_refs_path_and_decodes_repos() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{
                "repos":[
                    {"repo":"","branches":["main","dev"],"commits":[
                        {"sha":"aaaaaaa","subject":"first"},
                        {"sha":"bbbbbbb","subject":"second"}
                    ]}
                ]
            }""".trimIndent(),
        )

        val result: FsRefsResult? = app.fsRefs(session("sess-1"))

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/sessions/sess-1/fs/refs", rec.path)
        assertEquals(1, result?.repos?.size)
        assertEquals(listOf("main", "dev"), result?.repos?.get(0)?.branches)
        assertEquals(2, result?.repos?.get(0)?.commits?.size)
        assertEquals("aaaaaaa", result?.repos?.get(0)?.commits?.get(0)?.sha)
        assertEquals("first", result?.repos?.get(0)?.commits?.get(0)?.subject)
    }

    @Test fun fs_refs_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.fsRefs(session()))
    }
}
