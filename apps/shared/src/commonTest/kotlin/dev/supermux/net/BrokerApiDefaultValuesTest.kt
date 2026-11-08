package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Card #97: a value the user picks must reach the broker even when it equals the field's Kotlin
 * default. The settings endpoints merge a request over what is stored, so a field left out of the
 * body is read as "unchanged" — and a default-valued field used to be left out, which made
 * "Switch accounts automatically" impossible to turn off and the curator unable to go back to
 * Off, 01:00, Claude or the agent's own model.
 */
class BrokerApiDefaultValuesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun captured(body: String, sink: MutableList<HttpRequestData>): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(content = ByteReadChannel(body), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyJson(): JsonObject =
        json.parseToJsonElement((this.body as io.ktor.http.content.TextContent).text).jsonObject

    @Test fun turning_auto_switch_off_sends_false() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured("""{"autoSwitch":false}""", sink)
        api.setAccountsAutoSwitch(false)
        assertEquals(JsonPrimitive(false), sink.single().bodyJson()["autoSwitch"])
    }

    @Test fun curator_settings_at_their_defaults_are_all_sent() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured("""{"config":{},"nextRun":null}""", sink)
        api.saveCuratorSettings(CuratorConfig(enabled = false, hour = 1, minute = 0, agent = "claude"))
        val body = sink.single().bodyJson()
        assertEquals(JsonPrimitive(false), body["enabled"])
        assertEquals(JsonPrimitive(1), body["hour"])
        assertEquals(JsonPrimitive(0), body["minute"])
        assertEquals(JsonPrimitive("claude"), body["agent"])
    }

    /** "Default" model and effort are null in the app; the broker resets them on an empty string. */
    @Test fun curator_default_model_and_effort_are_sent_as_a_reset() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured("""{"config":{},"nextRun":null}""", sink)
        api.saveCuratorSettings(CuratorConfig(enabled = true, hour = 2, minute = 30, agent = "codex"))
        val body = sink.single().bodyJson()
        assertEquals(JsonPrimitive(""), body["model"])
        assertEquals(JsonPrimitive(""), body["reasoningLevel"])
    }

    @Test fun a_chosen_curator_model_and_effort_are_sent_as_chosen() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured("""{"config":{},"nextRun":null}""", sink)
        api.saveCuratorSettings(
            CuratorConfig(enabled = true, hour = 2, minute = 30, agent = "codex", model = "gpt-6", reasoningLevel = "high"),
        )
        val body = sink.single().bodyJson()
        assertEquals(JsonPrimitive("gpt-6"), body["model"])
        assertEquals(JsonPrimitive("high"), body["reasoningLevel"])
    }

    /** The rule holds for every request body, not only the two settings screens that showed it. */
    @Test fun any_request_field_equal_to_its_default_is_sent() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured(
            """{"id":"github:github.com:me","kind":"github","host":"github.com","label":"GitHub","account":{"login":"me"},"source":"pat","transport":"https"}""",
            sink,
        )
        api.addForge("github", "tok")
        val add = sink.last().bodyJson()
        assertEquals(JsonPrimitive("pat"), add["source"])
        assertEquals(JsonPrimitive("https"), add["transport"])
        api.importForge("github")
        assertEquals(JsonPrimitive("https"), sink.last().bodyJson()["transport"])
    }

    /** Null still means "not set": a partial update must not start sending nulls. */
    @Test fun a_null_field_is_still_left_out() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured("""{"id":"github:github.com:me","kind":"github","host":"github.com","label":"GitHub","account":{"login":"me"}}""", sink)
        api.addForge("github", "tok", host = null)
        assertFalse("host" in sink.last().bodyJson())
    }
}
