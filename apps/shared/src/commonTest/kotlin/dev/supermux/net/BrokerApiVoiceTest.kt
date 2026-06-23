package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the voice-dictation BrokerApi methods (transcribeDraft/transcribeAudio/
 * fetchGlossary/updateGlossary) via a capturing [MockEngine] — the request bodies
 * are file-private and can only be asserted through the public API. Mirrors
 * [BrokerApiSettingsTest]'s pattern.
 */
class BrokerApiVoiceTest {
    /** Build a BrokerApi whose engine records every request and replies [body]. */
    private fun captured(
        body: String = "{}",
        status: io.ktor.http.HttpStatusCode = io.ktor.http.HttpStatusCode.OK,
        sink: MutableList<HttpRequestData>,
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String =
        when (val c = this.body) {
            is io.ktor.http.content.TextContent -> c.text
            is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> error("unexpected body type: ${c::class.simpleName}")
        }

    @Test fun transcribe_draft_posts_json_to_session_path() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"hello world","degraded":false}""", sink = reqs)
        val res = api.transcribeDraft("s1", "hi")
        assertEquals("hello world", res.text)
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/sessions/s1/transcribe", r.url.toString())
        assertEquals("""{"draft":"hi"}""", r.bodyText())
    }

    @Test fun transcribe_draft_decodes_degraded_flag() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"raw draft","degraded":true}""", sink = reqs)
        val res = api.transcribeDraft("s1", "raw")
        assertEquals("raw draft", res.text)
        assertTrue(res.degraded)
    }

    @Test fun transcribe_audio_posts_multipart_to_session_path() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"spoken text"}""", sink = reqs)
        val res = api.transcribeAudio("s1", byteArrayOf(1, 2, 3), "voice.m4a")
        assertEquals("spoken text", res.text)
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/sessions/s1/transcribe", r.url.toString())
        // Multipart form (field name "audio" enforced by the production call + broker route).
        assertTrue(r.body.contentType?.toString()?.startsWith("multipart/form-data") == true,
            "ct=${r.body.contentType}")
    }

    @Test fun fetch_glossary_gets_and_returns_terms() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"glossary":["Supermux","Ktor"]}""", sink = reqs)
        val terms = api.fetchGlossary()
        assertEquals(listOf("Supermux", "Ktor"), terms)
        val r = reqs.single()
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("http://h/config/voice-glossary", r.url.toString())
    }

    @Test fun update_glossary_puts_glossary_and_echoes_list() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"glossary":["A","B"]}""", sink = reqs)
        val terms = api.updateGlossary(listOf("A", "B"))
        assertEquals(listOf("A", "B"), terms)
        val r = reqs.single()
        assertEquals(HttpMethod.Put, r.method)
        assertEquals("http://h/config/voice-glossary", r.url.toString())
        assertEquals("""{"glossary":["A","B"]}""", r.bodyText())
    }
}
