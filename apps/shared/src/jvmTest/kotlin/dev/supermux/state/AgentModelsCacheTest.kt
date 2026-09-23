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
