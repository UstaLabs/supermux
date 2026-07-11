package dev.supermux.desktop.state

import dev.supermux.net.AddCommentBody
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsDiffResult
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4g-2 Task 1: the `DesktopAppState` diff + inline-code-review wrappers (fsDiff/reviewAddComment/
 * reviewResolve/reviewSubmit). Mirrors [DesktopGitTest]'s MockEngine layer: BrokerApi is a final
 * concrete class, so the `apiOverride` seam takes a real instance constructed against a ktor
 * [MockEngine] HttpClient — no live broker required. Each wrapper is asserted for its exact HTTP
 * method + path + (where relevant) request body, that a 2xx response decodes into the real DTO, and
 * that a 5xx degrades gracefully (null / false) via [DesktopAppState.runApi] — Android
 * AppViewModel.kt:805-819 parity (there via `runCatching{}.getOrNull()`/`getOrDefault(false)`).
 *
 * These wrappers take a [SessionInfo] (not a bare session id) — mirroring the fsList/fsRead/fsWrite
 * idiom (DesktopAppState.kt:537-563), NOT the gitFetch/gitPull id-string idiom — because the
 * DiffView call sites in SessionDetail.DesktopEditorPanel already have the SessionInfo in hand
 * (same wrapper shape the plan specifies).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDiffReviewTest {

    private data class Rec(val method: HttpMethod, val path: String, val body: String)

    private fun session(id: String = "sess-1") =
        SessionInfo(id = id, name = "name-$id", workdir = "/w/$id", agent = "claude")

    private fun bodyText(content: Any?): String = when (content) {
        is TextContent -> content.text
        else -> ""
    }

    /** DesktopAppState whose BrokerApi answers every request with [body]/[status], recording
     *  each request's method + path + raw body into [recorded]. */
    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"status":"ok"}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath, bodyText(req.body)))
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

    // ── fsDiff ──────────────────────────────────────────────────────────────────────

    @Test fun fs_diff_gets_the_diff_path_and_decodes_repos_and_comments() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{
                "repos":[
                    {"repo":"","files":[{"path":"a.txt","status":"modified","diff":"@@ -1 +1 @@\n-old\n+new\n"}]},
                    {"repo":"lib","files":[{"path":"b.txt","status":"added","diff":"@@ -0,0 +1 @@\n+hi\n","binary":false,"modeChange":false}]}
                ],
                "comments":[
                    {"id":"c1","repo":"","path":"a.txt","side":"RIGHT","anchorLine":1,"body":"hey","status":"open"}
                ]
            }""".trimIndent(),
        )

        val result: FsDiffResult? = app.fsDiff(session("sess-1"))

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/sessions/sess-1/fs/diff", rec.path)
        assertEquals(2, result?.repos?.size)
        assertEquals("", result?.repos?.get(0)?.repo)
        assertEquals("lib", result?.repos?.get(1)?.repo)
        assertEquals(1, result?.comments?.size)
        assertEquals("c1", result?.comments?.get(0)?.id)
    }

    @Test fun fs_diff_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.fsDiff(session()))
    }

    // ── reviewAddComment ───────────────────────────────────────────────────────────

    @Test fun review_add_comment_posts_the_body_and_decodes_the_created_comment() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"id":"c9","repo":"","path":"a.txt","side":"RIGHT","anchorLine":3,"body":"nice","status":"open"}""",
        )

        val result: ReviewComment? = app.reviewAddComment(
            session("sess-1"),
            AddCommentBody(repo = "", path = "a.txt", side = "RIGHT", anchorLine = 3, anchorContext = "+new", body = "nice"),
        )

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/review/comments", rec.path)
        assertTrue(rec.body.contains("\"anchorLine\":3"))
        assertTrue(rec.body.contains("\"body\":\"nice\""))
        assertEquals("c9", result?.id)
        assertEquals("open", result?.status)
    }

    @Test fun review_add_comment_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.reviewAddComment(
            session(),
            AddCommentBody(repo = "", path = "a.txt", side = "RIGHT", anchorLine = 1, anchorContext = "", body = "x"),
        )

        assertNull(result)
    }

    // ── reviewResolve ──────────────────────────────────────────────────────────────

    @Test fun review_resolve_patches_the_comment_to_status_resolved() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"ok":true}""")

        val result = app.reviewResolve(session("sess-1"), "c9")

        val rec = recorded.single()
        assertEquals(HttpMethod.Patch, rec.method)
        assertEquals("/sessions/sess-1/review/comments/c9", rec.path)
        assertTrue(rec.body.contains("\"status\":\"resolved\""))
        assertTrue(result)
    }

    @Test fun review_resolve_returns_false_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertFalse(app.reviewResolve(session(), "c9"))
    }

    // ── reviewSubmit ───────────────────────────────────────────────────────────────

    @Test fun review_submit_posts_to_the_submit_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"ok":true,"delivered":2}""")

        val result: ReviewSubmitResult? = app.reviewSubmit(session("sess-1"))

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/sess-1/review/submit", rec.path)
        assertEquals(ReviewSubmitResult(ok = true, delivered = 2), result)
    }

    @Test fun review_submit_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.reviewSubmit(session()))
    }
}
