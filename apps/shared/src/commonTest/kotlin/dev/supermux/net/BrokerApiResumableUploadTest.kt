package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [BrokerApi.uploadResumable] via capturing [MockEngine]s — the small-file
 * single-POST branch, the large-file chunked loop (init → PATCH → finalize), and
 * resume-after-a-dropped-chunk (HEAD resync). Mirrors [BrokerApiUploadTest].
 */
class BrokerApiResumableUploadTest {

    @Test fun byteArrayChunkSource_reads_ranges_and_clamps_at_end() {
        val src = ByteArrayChunkSource(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5L, src.size)
        assertEquals(listOf<Byte>(1, 2, 3), src.read(0, 3).toList())
        assertEquals(listOf<Byte>(4, 5), src.read(3, 3).toList()) // clamped
        assertEquals(0, src.read(5, 3).size)                       // at end
    }

    @Test fun uploadResumable_small_file_uses_single_post_and_reports_progress() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            reqs.add(req)
            respond(
                ByteReadChannel("""{"file_id":"f_small","size":3,"mime":"video/mp4","name":"a.mp4"}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = BrokerApi("http://h", "tok", HttpClient(engine))
        val progress = mutableListOf<Pair<Long, Long>>()
        val res = api.uploadResumable("s1", ByteArrayChunkSource(byteArrayOf(1, 2, 3)), "a.mp4", "video/mp4", "video") { s, t -> progress.add(s to t) }

        assertEquals("f_small", res.file_id)
        assertEquals(1, reqs.size)
        assertEquals(HttpMethod.Post, reqs[0].method)
        assertEquals("http://h/upload", reqs[0].url.toString())
        assertEquals(3L to 3L, progress.last())
    }

    /** A stateful mock broker: init returns chunk_size, each PATCH appends and echoes
     *  the new offset, the final PATCH returns the finalized attachment; HEAD reports
     *  the current offset. `failFirstPatch` throws on the first PATCH to drive resume. */
    private fun chunkedApi(
        chunkSize: Long,
        total: Long,
        reqs: MutableList<HttpRequestData>,
        failFirstPatch: Boolean = false,
    ): BrokerApi {
        var received = 0L
        var patchCalls = 0
        val engine = MockEngine { req ->
            reqs.add(req)
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Post && path == "/upload/init" ->
                    respond(ByteReadChannel("""{"upload_id":"up_1","offset":0,"chunk_size":$chunkSize}"""),
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                req.method == HttpMethod.Patch && path == "/upload/up_1" -> {
                    patchCalls++
                    if (failFirstPatch && patchCalls == 1) throw RuntimeException("boom")
                    val len = (req.body as OutgoingContent.ByteArrayContent).bytes().size
                    received += len
                    if (received >= total)
                        respond(ByteReadChannel("""{"file_id":"up_1","size":$received,"mime":"video/mp4","name":"a.mp4"}"""),
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    else
                        respond(ByteReadChannel("""{"offset":$received}"""),
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.method == HttpMethod.Head && path == "/upload/up_1" ->
                    respond(ByteReadChannel(""), HttpStatusCode.OK, headersOf("Upload-Offset", "$received"))

                else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            }
        }
        // threshold 0 → any non-empty source takes the chunked path.
        return BrokerApi("http://h", "tok", HttpClient(engine)).also { it.resumableThresholdBytes = 0 }
    }

    @Test fun uploadResumable_large_file_chunks_and_reports_per_chunk_progress() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val bytes = ByteArray(10) { it.toByte() }        // 10 bytes, chunk_size 4 → 4+4+2
        val api = chunkedApi(chunkSize = 4, total = 10, reqs = reqs)
        val progress = mutableListOf<Pair<Long, Long>>()
        val res = api.uploadResumable("s1", ByteArrayChunkSource(bytes), "a.mp4", "video/mp4", "video") { s, t -> progress.add(s to t) }

        assertEquals("up_1", res.file_id)
        assertEquals(10L, res.size)
        assertEquals(1, reqs.count { it.method == HttpMethod.Post && it.url.encodedPath == "/upload/init" })
        assertEquals(3, reqs.count { it.method == HttpMethod.Patch })
        val offsets = reqs.filter { it.method == HttpMethod.Patch }.map { it.headers["Upload-Offset"] }
        assertEquals(listOf("0", "4", "8"), offsets)
        assertEquals(listOf(4L to 10L, 8L to 10L, 10L to 10L), progress)
    }

    @Test fun uploadResumable_resumes_after_a_dropped_chunk() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val bytes = ByteArray(10) { it.toByte() }
        val api = chunkedApi(chunkSize = 4, total = 10, reqs = reqs, failFirstPatch = true)
        val res = api.uploadResumable("s1", ByteArrayChunkSource(bytes), "a.mp4", "video/mp4", "video")

        assertEquals("up_1", res.file_id)
        assertEquals(10L, res.size)
        assertTrue(reqs.any { it.method == HttpMethod.Head })
        assertTrue(reqs.count { it.method == HttpMethod.Patch } >= 3)
    }
}
