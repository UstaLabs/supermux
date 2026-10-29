package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Agents calls (still `/api/pas` on the wire, so older brokers keep working). */
class BrokerApiAgentsTest {
    private fun api(status: HttpStatusCode, body: String, sink: MutableList<HttpRequestData>): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String = (body as? io.ktor.http.content.TextContent)?.text ?: ""

    @Test fun create_posts_name_agent_and_model_without_focus() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        api(HttpStatusCode.OK, """{"ok":true}""", sink).createPA("Dockie", "codex", "gpt-6")
        assertEquals(HttpMethod.Post, sink.single().method)
        assertEquals("http://h/api/pas", sink.single().url.toString())
        assertEquals("""{"name":"Dockie","agent":"codex","model":"gpt-6"}""", sink.single().bodyText())
    }

    @Test fun create_without_a_model_omits_it() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        api(HttpStatusCode.OK, """{"ok":true}""", sink).createPA("Dockie", "claude")
        assertEquals("""{"name":"Dockie","agent":"claude"}""", sink.single().bodyText())
    }

    @Test fun a_refused_create_throws_with_status_and_body() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val e = assertFailsWith<CancellationException> {
            api(HttpStatusCode.Conflict, """{"error":"taken","code":"name_taken"}""", sink).createPA("Dockie", "claude")
        }
        assertTrue(e.message!!.contains("HTTP 409"), e.message)
        assertTrue(e.message!!.contains("name_taken"), e.message)
    }

    @Test fun kill_deletes_the_session() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        api(HttpStatusCode.OK, """{"ok":true}""", sink).kill("s1")
        assertEquals(HttpMethod.Delete, sink.single().method)
        assertEquals("http://h/sessions/s1", sink.single().url.toString())
    }

    @Test fun a_failed_kill_throws() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        assertFailsWith<CancellationException> {
            api(HttpStatusCode.NotFound, """{"error":"not found"}""", sink).kill("nope")
        }
    }

    @Test fun make_default_patches_the_agent_by_id() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        api(HttpStatusCode.OK, """{"ok":true}""", sink).updatePA("a 1", isDefault = true)
        assertEquals(HttpMethod.Patch, sink.single().method)
        assertEquals("http://h/api/pas/a%201", sink.single().url.toString())
        assertEquals("""{"isDefault":true}""", sink.single().bodyText())
    }

    @Test fun rename_patches_only_the_name() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        api(HttpStatusCode.OK, """{"ok":true}""", sink).updatePA("a1", name = "Kahya")
        assertEquals("""{"name":"Kahya"}""", sink.single().bodyText())
    }

    @Test fun a_refused_rename_throws_with_the_code() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val e = assertFailsWith<CancellationException> {
            api(HttpStatusCode.Conflict, """{"error":"name already in use: Bob","code":"name_taken"}""", sink).updatePA("a1", name = "Bob")
        }
        assertTrue(e.message!!.contains("name_taken"), e.message)
    }
}
