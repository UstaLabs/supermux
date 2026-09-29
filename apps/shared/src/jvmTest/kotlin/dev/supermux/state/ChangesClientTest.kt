package dev.supermux.state

import dev.supermux.net.BlobText
import dev.supermux.net.BrokerApi
import dev.supermux.net.ChangedFile
import dev.supermux.net.ChangesResult
import dev.supermux.net.RepoChanges
import dev.supermux.net.toFsDiffResult
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesClientTest {
    @Test fun the_list_maps_to_lazy_files_without_a_patch() {
        val r = ChangesResult(
            repos = listOf(
                RepoChanges(
                    repo = "", baseSha = "b".repeat(40), truncated = true, total = 5000,
                    files = listOf(ChangedFile(path = "a.kt", status = "modified", added = 2, removed = 1, baseBlob = "a".repeat(40), size = 10)),
                ),
            ),
        ).toFsDiffResult()
        val repo = r.repos.single()
        assertEquals("b".repeat(40), repo.baseSha)
        assertTrue(repo.truncated)
        assertEquals(5000, repo.total)
        val f = repo.files.single()
        assertTrue(f.lazy)
        assertEquals("", f.diff)
        assertEquals(2, f.added)
        assertEquals("a".repeat(40), f.baseBlob)
    }

    private fun app(routes: Map<String, Pair<HttpStatusCode, String>>, hits: MutableList<String>): HostStore {
        val engine = MockEngine { req ->
            hits += req.url.encodedPath
            val (status, body) = routes[req.url.encodedPath] ?: (HttpStatusCode.NotFound to "")
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HostStore(
            baseUrl = "ws://test:9898", token = "t", scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(), connectOnInit = false, apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    private val session = SessionInfo(id = "s-1", name = "n", workdir = "/w", agent = "claude")

    @Test fun session_diff_uses_changes_when_the_broker_has_it() = runTest {
        val hits = mutableListOf<String>()
        val app = app(mapOf("/sessions/s-1/changes" to (HttpStatusCode.OK to """{"repos":[{"repo":"","baseSha":"x","files":[{"path":"a","status":"modified","added":1,"removed":0}]}],"comments":[]}""")), hits)
        val r = app.fsDiff(session, "head")!!
        assertTrue(r.repos.single().files.single().lazy)
        assertEquals(listOf("/sessions/s-1/changes"), hits)
    }

    @Test fun session_diff_falls_back_to_fs_diff_on_an_older_broker() = runTest {
        val hits = mutableListOf<String>()
        val app = app(mapOf("/sessions/s-1/fs/diff" to (HttpStatusCode.OK to """{"repos":[{"repo":"","files":[{"path":"a","status":"modified","diff":"@@ -1 +1 @@\n-a\n+b\n"}]}],"comments":[]}""")), hits)
        val r = app.fsDiff(session)!!
        assertEquals(false, r.repos.single().files.single().lazy)
        assertEquals(listOf("/sessions/s-1/changes", "/sessions/s-1/fs/diff"), hits)
    }

    @Test fun workspace_diff_uses_changes_then_falls_back() = runTest {
        val hits = mutableListOf<String>()
        val app = app(mapOf("/workspaces/w-1/fs/diff" to (HttpStatusCode.OK to """{"repos":[],"comments":[]}""")), hits)
        app.workspaceFsDiff("w-1")
        assertEquals(listOf("/workspaces/w-1/changes", "/workspaces/w-1/fs/diff"), hits)
    }

    @Test fun blob_results_map_status_codes() = runTest {
        val hits = mutableListOf<String>()
        val app = app(
            mapOf(
                "/workspaces/w-1/changes/blob" to (HttpStatusCode.PayloadTooLarge to """{"error":"TOO_LARGE","size":2000000}"""),
                "/sessions/s-1/changes/blob" to (HttpStatusCode.OK to "text\n"),
            ),
            hits,
        )
        assertEquals(BlobText.TooLarge(2_000_000), app.workspaceChangesBlob("w-1", "", "a".repeat(40), force = false))
        assertEquals(BlobText.Text("text\n"), app.changesBlob(session, "", "a".repeat(40), force = false))
    }
}
