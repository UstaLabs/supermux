package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-session model + reasoning wrappers on [DesktopAppState] (M-uxfix). Each is exercised
 * against a real [BrokerApi] over a ktor [MockEngine] (BrokerApi is final — the `apiOverride` seam
 * takes a real instance). The engine records method + path + body so the tests assert the REST
 * SHAPE (GET the two catalogs, POST /model and POST /reasoning-level with the right body), and a
 * 500 path proves the runApi degrade (null catalog / false switch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopModelSwitchTest {

    private data class Rec(val method: String, val path: String, val body: String)

    private fun bodyText(content: Any?): String = (content as? TextContent)?.text ?: ""

    /** DesktopAppState whose BrokerApi answers the per-session model/reasoning endpoints, recording
     *  each request. [ok]=false makes every request throw a transport error so the runApi degrade
     *  paths are exercised (a plain non-2xx would NOT trip the fire-and-forget POST wrappers, which
     *  don't inspect status — only a thrown exception degrades them, mirroring Android's
     *  `runCatching { api.switch… }`). */
    private fun app(recorded: MutableList<Rec>, ok: Boolean = true): DesktopAppState {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            recorded.add(Rec(req.method.value, path, bodyText(req.body)))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            if (!ok) {
                throw java.io.IOException("simulated broker transport failure")
            } else when (path) {
                "/sessions/s1/models" -> respond(
                    """{"agent":"claude","current":"opus","models":[{"id":"opus","displayName":"Opus"},{"id":"sonnet","displayName":"Sonnet"}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                "/sessions/s1/reasoning-levels" -> respond(
                    """{"agent":"claude","current":"high","levels":[{"id":"low","description":"Low"},{"id":"high","description":"High"}],"visible":true}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
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

    @Test fun session_models_gets_the_catalog_and_current() = runTest {
        val recorded = mutableListOf<Rec>()
        val resp = app(recorded).sessionModels("s1")
        assertEquals("GET", recorded.single().method)
        assertEquals("/sessions/s1/models", recorded.single().path)
        assertEquals("opus", resp?.current)
        assertEquals(listOf("opus", "sonnet"), resp?.models?.map { it.id })
    }

    @Test fun session_reasoning_gets_levels_current_and_visibility() = runTest {
        val recorded = mutableListOf<Rec>()
        val resp = app(recorded).sessionReasoning("s1")
        assertEquals("GET", recorded.single().method)
        assertEquals("/sessions/s1/reasoning-levels", recorded.single().path)
        assertEquals("high", resp?.current)
        assertTrue(resp?.visible == true)
        assertEquals(listOf("low", "high"), resp?.levels?.map { it.id })
    }

    @Test fun switch_model_posts_model_body_and_returns_true() = runTest {
        val recorded = mutableListOf<Rec>()
        val ok = app(recorded).switchModel("s1", "sonnet")
        assertTrue(ok)
        assertEquals("POST", recorded.single().method)
        assertEquals("/sessions/s1/model", recorded.single().path)
        assertTrue("\"model\":\"sonnet\"" in recorded.single().body, "got: ${recorded.single().body}")
    }

    @Test fun switch_reasoning_posts_reasoning_level_body_and_returns_true() = runTest {
        val recorded = mutableListOf<Rec>()
        val ok = app(recorded).switchReasoning("s1", "low")
        assertTrue(ok)
        assertEquals("POST", recorded.single().method)
        assertEquals("/sessions/s1/reasoning-level", recorded.single().path)
        assertTrue("\"reasoningLevel\":\"low\"" in recorded.single().body, "got: ${recorded.single().body}")
    }

    @Test fun wrappers_degrade_on_a_broker_error() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = app(recorded, ok = false)
        assertNull(app.sessionModels("s1"))
        assertNull(app.sessionReasoning("s1"))
        assertFalse(app.switchModel("s1", "sonnet"))
        assertFalse(app.switchReasoning("s1", "low"))
    }
}
