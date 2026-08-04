package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
 * Desktop-parity Task 4: DesktopAppState forge wrappers (settings accounts + launcher omnibox).
 *
 * MockEngine-backed BrokerApi — asserts HTTP method/path and 2xx decode / 5xx degrade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopGitHostingTest {

    private data class Rec(val method: HttpMethod, val path: String, val body: String)

    private fun bodyText(content: Any?): String = when (content) {
        is TextContent -> content.text
        else -> ""
    }

    private fun appRecording(
        recorded: MutableList<Rec>,
        respondFor: (Rec) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" },
    ): DesktopAppState {
        val engine = MockEngine { req ->
            val rec = Rec(req.method, req.url.encodedPath, bodyText(req.body))
            recorded.add(rec)
            val (status, payload) = respondFor(rec)
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            respond(ByteReadChannel(payload), status, jsonHeaders)
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

    @Test fun forges_load_gets_connections_and_decodes() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """
                {"connections":[{"id":"c1","kind":"github","host":"github.com","account":{"login":"a"},"status":"ok"}],
                 "cli":{"github":{"available":true,"login":"a"},"gitlab":{"available":false}}}
            """.trimIndent()
        }
        val r = app.forgesLoad()
        assertEquals(HttpMethod.Get, recorded.single().method)
        assertEquals("/forge/connections", recorded.single().path)
        assertEquals(1, r?.connections?.size)
        assertEquals("a", r?.connections?.first()?.account?.login)
        assertTrue(r?.cli?.github?.available == true)
    }

    @Test fun forges_load_null_on_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) { HttpStatusCode.InternalServerError to "err" }
        assertNull(app.forgesLoad())
    }

    @Test fun forge_add_posts_and_returns_true() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"id":"n1","kind":"github","account":{"login":"x"},"status":"ok"}"""
        }
        assertTrue(app.forgeAdd("github", "pat_xxx", null, "https"))
        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/forge/connections", rec.path)
        assertTrue(rec.body.contains("pat_xxx"))
        assertTrue(rec.body.contains("github"))
    }

    @Test fun forge_add_false_on_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) { HttpStatusCode.InternalServerError to "err" }
        assertFalse(app.forgeAdd("github", "bad", null, "https"))
    }

    @Test fun forge_import_posts_to_import_path() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"id":"i1","kind":"github","account":{"login":"cli"},"source":"cli","status":"ok"}"""
        }
        assertTrue(app.forgeImport("github", "https"))
        assertEquals("/forge/connections/import", recorded.single().path)
        assertEquals(HttpMethod.Post, recorded.single().method)
    }

    @Test fun list_forges_returns_connections_only() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"connections":[{"id":"c1","kind":"github","account":{"login":"a"},"status":"ok"}]}"""
        }
        val list = app.listForges()
        assertEquals(1, list.size)
        assertEquals("c1", list.first().id)
    }

    @Test fun search_forge_posts_query() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"repos":[{"connectionId":"c1","owner":"a","name":"repo","fullName":"a/repo"}]}"""
        }
        val result = app.searchForge("repo")
        assertEquals(1, result?.repos?.size)
        assertEquals("a/repo", result?.repos?.first()?.fullName)
        assertEquals(HttpMethod.Post, recorded.single().method)
        assertEquals("/forge/search", recorded.single().path)
        assertTrue(recorded.single().body.contains("repo"))
    }

    @Test fun clone_forge_returns_local_path() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"localPath":"/home/u/repo"}"""
        }
        assertEquals("/home/u/repo", app.cloneForge("c1", "a", "repo"))
        assertEquals("/forge/clone", recorded.single().path)
    }

    @Test fun clone_forge_null_on_blank_path() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"localPath":""}"""
        }
        assertNull(app.cloneForge("c1", "a", "repo"))
    }

    @Test fun create_local_and_create_forge_return_paths() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) { rec ->
            when (rec.path) {
                "/forge/create-local" -> HttpStatusCode.OK to """{"localPath":"/home/u/local"}"""
                "/forge/create" -> HttpStatusCode.OK to """{"localPath":"/home/u/remote","repo":null}"""
                else -> HttpStatusCode.OK to "{}"
            }
        }
        assertEquals("/home/u/local", app.createLocalRepo("local"))
        assertEquals("/home/u/remote", app.createForge("c1", "remote"))
        assertTrue(recorded.any { it.path == "/forge/create-local" })
        assertTrue(recorded.any { it.path == "/forge/create" })
    }

    @Test fun search_forge_null_on_5xx_distinguishes_from_empty_success() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) { HttpStatusCode.InternalServerError to "err" }
        // 5xx must be null — not an empty list that the UI would paint as "no repos found".
        assertNull(app.searchForge("x"))
        assertNull(app.cloneForge("c", "o", "n"))
        assertNull(app.createLocalRepo("n"))
        assertNull(app.createForge("c", "n"))
        assertTrue(app.listForges().isEmpty())
    }

    @Test fun search_forge_empty_repos_on_2xx_is_success_not_null() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) {
            HttpStatusCode.OK to """{"repos":[],"errors":[]}"""
        }
        val result = app.searchForge("nothing")
        assertTrue(result != null)
        assertTrue(result!!.repos.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test fun forge_remove_false_on_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) { HttpStatusCode.InternalServerError to "err" }
        assertFalse(app.forgeRemove("c1"))
        // remove + confirm-list may both fire; at least one DELETE is required.
        assertTrue(recorded.any { it.method == HttpMethod.Delete && it.path == "/forge/connections/c1" })
    }

    @Test fun forge_remove_true_on_2xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded) { HttpStatusCode.OK to "{}" }
        assertTrue(app.forgeRemove("c1"))
    }
}
