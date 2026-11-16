package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.FinishReadiness
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4b Finish flow: the `finish_job` + `session_git` reducer branches and the finish/verify
 * broker wrappers on [HostStore].
 *
 * Two layers (mirroring [DesktopLauncherTest]):
 *  1. Reducer branches — exercised through the `reduce()` seam with `connectOnInit = false`
 *     (no WebSocket, no HTTP). Sessions are keyed by `it.id` throughout the reducer, so the
 *     finish job / git updates match on `SessionInfo.id`.
 *  2. The HTTP wrappers — exercised against a real [BrokerApi] over a ktor [MockEngine]
 *     (BrokerApi is final — the `apiOverride` seam takes a real instance). The engine records
 *     the /finish request body so the test can assert its shape, and answers a readiness GET.
 *
 * Android parity: apps/android/.../AppViewModel.kt (`_finishJobs` seed ~L195, FinishJobFrame
 * ~L275, SessionGit ~L301, finishReadiness/mergeIntoBase).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopFinishTest {

    private val sent = mutableListOf<dev.supermux.proto.ClientFrame>()

    private fun state() = HostStore(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        deps = testDeps(),
        connectOnInit = false,
        sendFrameOverride = { sent.add(it) },
    )

    private fun session(id: String, finishJob: FinishJobDto? = null, git: GitLiteStatusDto? = null) =
        SessionInfo(id = id, name = "name-$id", workdir = "/w/$id", agent = "claude",
            finish_job = finishJob, git = git)

    private fun job(id: String, status: String = "running", stage: String? = null) =
        FinishJobDto(sessionId = id, action = "merge", status = status, stage = stage)

    // ── Reducer: finish_job ─────────────────────────────────────────────────────────

    @Test fun snapshot_seeds_finish_jobs_from_session_records() {
        val s = state()
        val seeded = job("s1", stage = "verifying")
        s.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(session("s1", finishJob = seeded), session("s2")),
            ),
        )
        // Only the session carrying a finish_job is seeded, keyed by session id.
        assertEquals(setOf("s1"), s.finishJobs.value.keys)
        assertEquals("verifying", s.finishJobs.value["s1"]?.stage)
    }

    @Test fun finish_job_frame_updates_flow_and_session_record() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1"))))
        assertTrue(s.finishJobs.value.isEmpty())
        assertNull(s.sessions.value.single().finish_job)

        val running = job("s1", status = "running", stage = "merging")
        s.reduce(ServerFrame.FinishJobFrame(session = "s1", job = running))

        // Both the finishJobs flow AND the session's finish_job field are updated.
        assertEquals("merging", s.finishJobs.value["s1"]?.stage)
        assertEquals(running, s.sessions.value.single().finish_job)
    }

    @Test fun finish_job_frame_with_null_job_is_ignored() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1", finishJob = job("s1")))))
        s.reduce(ServerFrame.FinishJobFrame(session = "s1", job = null))
        // A null job must not clobber the seeded entry (Android parity: only non-null jobs apply).
        assertTrue(s.finishJobs.value.containsKey("s1"))
    }

    // ── Ack state (header unacked dot; lives in app state so it survives session switches) ─────────

    // ── Reducer: session_git ─────────────────────────────────────────────────────────

    @Test fun session_git_frame_updates_the_session_git_badge() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1"), session("s2"))))
        val git = GitLiteStatusDto(mode = "base", ahead = 3, behind = 1, dirty = 2, touched = true)
        s.reduce(ServerFrame.SessionGit(session = "s1", git = git))

        assertEquals(git, s.sessions.value.first { it.id == "s1" }.git)
        // Unrelated session untouched.
        assertNull(s.sessions.value.first { it.id == "s2" }.git)
    }

    // ── Reducer: deferred frames still no-op without crashing ─────────────────────────

    @Test fun lsp_and_display_frames_still_no_op_without_crashing() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1"))))
        // These stay in the else-branch (deferred to M4g/M5); they must not throw.
        s.reduce(ServerFrame.LspReady(session = "s1", serverId = "kotlin"))
        s.reduce(ServerFrame.DisplayRemoved(id = "d1"))
        assertEquals(listOf("s1"), s.sessions.value.map { it.id })
    }

    // ── Wrappers (MockEngine BrokerApi) ──────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private data class Rec(val path: String, val body: String)

    private fun bodyText(content: Any?): String = when (content) {
        is TextContent -> content.text
        else -> ""
    }

    /** HostStore whose BrokerApi answers /finish (with [finishStatus]) and
     *  /finish/readiness, appending each request to [recorded]. */
    private fun appRecording(
        recorded: MutableList<Rec>,
        finishStatus: HttpStatusCode = HttpStatusCode.OK,
    ): HostStore {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            recorded.add(Rec(path, bodyText(req.body)))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path.endsWith("/finish/readiness") -> respond(
                    """{"branch":"feat-x","base":"main","ahead":2,"behind":1,"prRequiresGreen":true}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                path.endsWith("/finish") -> respond(
                    """{"status":"running"}""", finishStatus, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
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

    /** #72: waits for [HostStore.mergeIntoBase]'s kickoff report (it runs on the store's own scope). */
    private suspend fun mergeKickoff(app: HostStore): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        app.mergeIntoBase("sess-1") { done.complete(it) }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { done.await() }
        }
    }

    @Test fun merge_into_base_sends_merge_with_keep_and_no_checks_choice() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        assertTrue(mergeKickoff(app))
        val rec = recorded.single { it.path.endsWith("/finish") }
        assertEquals("/sessions/sess-1/finish", rec.path)
        assertTrue("\"action\":\"merge\"" in rec.body, "action missing: ${rec.body}")
        assertTrue("\"keep\":true" in rec.body, "keep missing: ${rec.body}")
        assertTrue("skipVerify" !in rec.body, "the checks gate is gone: ${rec.body}")
    }

    @Test fun merge_into_base_reports_a_refused_kickoff() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, finishStatus = HttpStatusCode.InternalServerError)

        assertFalse(mergeKickoff(app))
    }

    @Test fun finish_readiness_decodes_the_preflight_snapshot() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        val readiness: FinishReadiness? = app.finishReadiness("sess-1")

        assertEquals("feat-x", readiness?.branch)
        assertEquals("main", readiness?.base)
        assertEquals(2, readiness?.ahead)
        assertEquals(1, readiness?.behind)
    }
}
