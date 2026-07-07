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
import kotlin.test.assertNull

/**
 * Covers [BrokerApi.upload] / [BrokerApi.uploadBase64] via a capturing [MockEngine].
 * The upload transport switched from a multipart form to a raw-body streaming
 * request (POST /upload, application/octet-stream, X-Mux-* headers); these tests
 * pin that wire shape. Mirrors [BrokerApiVoiceTest]'s capture pattern.
 */
class BrokerApiUploadTest {
    /** Build a BrokerApi whose engine records every request and replies [body]. */
    private fun captured(
        body: String = "{}",
        sink: MutableList<HttpRequestData>,
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    /** The transformed outgoing body's raw bytes (ByteArray bodies only). */
    private fun HttpRequestData.rawBody(): ByteArray =
        (this.body as OutgoingContent.ByteArrayContent).bytes()

    @Test fun upload_posts_octet_stream_with_headers_and_raw_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"file_id":"f_1","size":4,"mime":"video/mp4","name":"clip.mp4"}""",
            sink = reqs,
        )
        val res = api.upload("s1", byteArrayOf(1, 2, 3, 4), "clip.mp4", "video/mp4", "video")

        // Response decoded from the same JSON shape as the old multipart path.
        assertEquals("f_1", res.file_id)
        assertEquals(4L, res.size)
        assertEquals("video/mp4", res.mime)
        assertEquals("clip.mp4", res.name)

        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/upload", r.url.toString())
        // Raw-body streaming request, NOT multipart.
        assertEquals("application/octet-stream", r.body.contentType?.toString())
        assertEquals("Bearer tok", r.headers[HttpHeaders.Authorization])
        assertEquals("s1", r.headers["X-Mux-Session"])
        assertEquals("video/mp4", r.headers["X-Mux-Mime"])
        assertEquals("clip.mp4", r.headers["X-Mux-Filename"])
        assertEquals("video", r.headers["X-Mux-Kind"])
        // Body is the file bytes verbatim.
        assertEquals(listOf<Byte>(1, 2, 3, 4), r.rawBody().toList())
    }

    @Test fun upload_omits_kind_header_when_null() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"file_id":"f_2"}""", sink = reqs)
        api.upload("s1", byteArrayOf(9), "a.bin", "application/octet-stream")
        val r = reqs.single()
        assertNull(r.headers["X-Mux-Kind"])
        assertEquals("s1", r.headers["X-Mux-Session"])
        assertEquals("application/octet-stream", r.headers["X-Mux-Mime"])
    }

    @Test fun upload_percent_encodes_filename() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"file_id":"f_3"}""", sink = reqs)
        // Space + non-ASCII must be RFC3986 (UTF-8) percent-encoded so the value is
        // header-safe and the broker recovers it via decodeURIComponent().
        api.upload("s1", byteArrayOf(0), "my résumé video.mp4", "video/mp4", null)
        val r = reqs.single()
        assertEquals("my%20r%C3%A9sum%C3%A9%20video.mp4", r.headers["X-Mux-Filename"])
    }

    @Test fun upload_base64_decodes_body_and_delegates_to_streaming() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"file_id":"f_4"}""", sink = reqs)
        // "AQID" == base64([1, 2, 3]); uploadBase64 must decode then hit the streaming path.
        val res = api.uploadBase64("s1", "AQID", "a.bin", "application/octet-stream")
        assertEquals("f_4", res.file_id)
        val r = reqs.single()
        assertEquals("http://h/upload", r.url.toString())
        assertEquals("application/octet-stream", r.body.contentType?.toString())
        assertEquals(listOf<Byte>(1, 2, 3), r.rawBody().toList())
    }
}
