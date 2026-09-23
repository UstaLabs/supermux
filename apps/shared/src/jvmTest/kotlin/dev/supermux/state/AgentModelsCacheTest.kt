package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ServerFrame
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
import dev.supermux.net.AgentModelsResponse
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The launcher's model catalog is fetched once per connection and again only when the broker says
 * it changed (`agent_models_changed`) — never per New Session open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentModelsCacheTest {

    private fun catalog(model: String) = """
        {"agents":[{"kind":"codex","models":[{"id":"$model","displayName":"$model"}],
          "reasoning":{"levels":[],"visible":false},
          "modelReasoning":{"$model":{"levels":[{"id":"low"},{"id":"high"}],"visible":true}}}]}
    """.trimIndent()

    private fun store(paths: MutableList<String>, answer: () -> Pair<HttpStatusCode, String>): HostStore {
        val engine = MockEngine { req ->
            paths.add(req.url.encodedPath)
            val (status, body) = answer()
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    /** The fetch runs on the HTTP engine's own dispatcher, so wait for the flow to land. */
    private fun HostStore.awaitModel(id: String): AgentModelsResponse = runBlocking {
        withTimeout(5_000) { agentModels.filterNotNull().first { it.agent("codex")?.models?.single()?.id == id } }
    }

    @Test fun the_change_frame_refetches_the_catalog() {
        val paths = mutableListOf<String>()
        var model = "gpt-a"
        val app = store(paths) { HttpStatusCode.OK to catalog(model) }
        app.refreshAgentModels()
        assertEquals(true, app.awaitModel("gpt-a").agent("codex")?.reasoningFor("gpt-a")?.visible)

        model = "gpt-b"
        app.reduce(ServerFrame.AgentModelsChanged)
        app.awaitModel("gpt-b")
        assertEquals(listOf("/agents/models", "/agents/models"), paths)
    }

    @Test fun every_picker_answers_from_the_catalog_without_a_request() {
        val paths = mutableListOf<String>()
        val app = store(paths) { HttpStatusCode.OK to catalog("gpt-a") }
        app.refreshAgentModels()
        app.awaitModel("gpt-a")
        app.reduce(
            ServerFrame.SessionAdded(
                SessionInfo(id = "s1", name = "s1", workdir = "/w", agent = "codex", model = "gpt-a", reasoningLevel = "high"),
            ),
        )
        paths.clear()
        runBlocking {
            assertEquals(listOf("gpt-a"), app.launcherModels("codex").map { it.id })
            assertEquals(true, app.launcherReasoning("codex", "gpt-a")?.visible)
            val sm = app.sessionModels("s1")
            assertEquals("gpt-a", sm?.current)
            assertEquals(listOf("gpt-a"), sm?.models?.map { it.id })
            val sr = app.sessionReasoning("s1")
            assertEquals("high", sr?.current)
            assertEquals(listOf("low", "high"), sr?.levels?.map { it.id })
            // Right after a switch to Default: Default's levels, before the row catches up.
            assertEquals(false, app.sessionReasoningFor("s1", null)?.visible)
        }
        assertEquals(emptyList<String>(), paths, "the catalog answers every picker")
    }

    @Test fun an_agent_missing_from_the_catalog_still_asks_the_broker() {
        val paths = mutableListOf<String>()
        val app = store(paths) { HttpStatusCode.OK to catalog("gpt-a") }
        app.refreshAgentModels()
        app.awaitModel("gpt-a")
        paths.clear()
        runBlocking { app.launcherModels("cursor") }
        assertEquals(listOf("/models"), paths)
    }

    @Test fun an_older_broker_leaves_the_catalog_null() {
        val paths = mutableListOf<String>()
        val app = store(paths) { HttpStatusCode.NotFound to """{"error":"not found"}""" }
        app.refreshAgentModels()
        runBlocking { withTimeout(5_000) { while (paths.isEmpty()) delay(10) } }
        assertNull(app.agentModels.value)
    }

    @Test fun the_frame_decodes_from_the_brokers_wire_shape() {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        assertEquals(
            ServerFrame.AgentModelsChanged,
            json.decodeFromString(ServerFrame.serializer(), """{"type":"agent_models_changed"}"""),
        )
    }
}
