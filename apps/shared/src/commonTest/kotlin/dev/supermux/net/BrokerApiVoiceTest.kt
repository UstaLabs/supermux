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
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The multipart body as text — the only way to see a PART's own headers, which is where the
 * recorded container's content-type has to land. Latin-1, because the audio bytes are not UTF-8.
 */
private suspend fun multipartText(req: HttpRequestData): String {
    val content = req.body as OutgoingContent.WriteChannelContent
    val channel = CoroutineScope(Dispatchers.Unconfined).writer { content.writeTo(channel) }.channel
    return channel.readRemaining().readByteArray()
        .joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
}

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

    @Test fun transcribe_audio_part_carries_the_recorded_container_mime() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"spoken"}""", sink = reqs)

        api.transcribeAudio("s1", byteArrayOf(1, 2, 3), "dictation.webm", "audio/webm;codecs=opus")

        val text = multipartText(reqs.single())
        assertTrue("audio/webm;codecs=opus" in text, text)
        assertTrue("""filename="dictation.webm"""" in text, text)
        assertTrue("audio" in text, text)
    }

    @Test fun transcribe_audio_part_defaults_to_mp4_when_no_mime_is_passed() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"spoken"}""", sink = reqs)

        api.transcribeAudio("s1", byteArrayOf(1, 2, 3), "voice.m4a")

        assertTrue("audio/mp4" in multipartText(reqs.single()), "no default mime in the part")
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

    @Test fun transcribe_draft_null_session_posts_to_idless_path() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"hi there"}""", sink = reqs)
        val res = api.transcribeDraft(null, "hi")
        assertEquals("hi there", res.text)
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/transcribe", r.url.toString())
        assertEquals("""{"draft":"hi"}""", r.bodyText())
    }

    @Test fun transcribe_audio_null_session_posts_to_idless_path() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"text":"spoken"}""", sink = reqs)
        val res = api.transcribeAudio(null, byteArrayOf(1, 2, 3), "voice.m4a")
        assertEquals("spoken", res.text)
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/transcribe", r.url.toString())
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
