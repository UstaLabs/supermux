package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-1 Task 1: [DesktopAppState.transcribeAudio] — the desktop mic-dictation HTTP wrapper. Mirrors
 * [DesktopLspSettingsTest]'s MockEngine layer: BrokerApi is a final concrete class, so the
 * `apiOverride` seam takes a real instance over a ktor [MockEngine] HttpClient — no live broker
 * required. The multipart wire shape (field "audio", filename/mime headers) is already pinned by
 * the shared `BrokerApiVoiceTest` (apps/shared) — these tests only cover the wrapper's routing
 * (session id vs id-less path) and its degrade-to-null-on-failure contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDictationTest {

    private data class Rec(val method: HttpMethod, val path: String)

    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"text":"hello world","degraded":false}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath))
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
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

    @Test fun transcribe_audio_with_a_session_id_posts_to_the_sessioned_path_and_decodes_the_text() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        val result = app.transcribeAudio("s1", byteArrayOf(1, 2, 3), "dictation.wav")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/sessions/s1/transcribe", rec.path)
        assertEquals("hello world", result?.text)
        assertEquals(false, result?.degraded)
    }

    @Test fun transcribe_audio_with_a_null_session_id_posts_to_the_id_less_path() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        app.transcribeAudio(null, byteArrayOf(1, 2, 3), "dictation.wav")

        assertEquals("/transcribe", recorded.single().path)
    }

    @Test fun transcribe_audio_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.transcribeAudio("s1", byteArrayOf(1), "dictation.wav"))
    }

    @Test fun transcribe_audio_surfaces_a_degraded_true_response() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"text":"partial","degraded":true}""")

        val result = app.transcribeAudio("s1", byteArrayOf(1), "dictation.wav")

        assertTrue(result?.degraded == true)
        assertEquals("partial", result?.text)
    }
}
