package dev.supermux.net

import dev.supermux.media.RangeResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerApiFileRangeTest {
    private val file = ByteArray(10) { it.toByte() }

    /** A broker that serves [file] the way `serveFile` does — including 416 for an end past the size. */
    private fun api(ranges: MutableList<String?>, status: (String?) -> HttpStatusCode? = { null }): BrokerApi {
        val engine = MockEngine { req ->
            val range = req.headers[HttpHeaders.Range]
            ranges += range
            val forced = status(range)
            if (forced != null) return@MockEngine respond(ByteReadChannel(file), forced)
            val m = Regex("bytes=(\\d+)-(\\d+)").find(range.orEmpty())!!
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toInt()
            if (end >= file.size) {
                respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */${file.size}"))
            } else {
                respond(
                    ByteReadChannel(file.copyOfRange(start, end + 1)),
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${file.size}"),
                )
            }
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private suspend fun BrokerApi.collect(from: Long, to: Long): Pair<RangeResult?, ByteArray> {
        val out = mutableListOf<Byte>()
        val r = fileRange("0123456789abcdef0123456789abcdef", from, to) { buf, n -> for (i in 0 until n) out += buf[i] }
        return r to out.toByteArray()
    }

    @Test fun aRangeIsRequestedAndStreamed() = runTest {
        val ranges = mutableListOf<String?>()
        val (r, got) = api(ranges).collect(2, 5)
        assertEquals(10L, r?.total)
        assertContentEquals(file.copyOfRange(2, 6), got)
        assertEquals(listOf<String?>("bytes=2-5"), ranges)
    }

    @Test fun aRangePastTheEndIsRetriedClamped() = runTest {
        val ranges = mutableListOf<String?>()
        val (r, got) = api(ranges).collect(0, 4_194_303)
        assertEquals(10L, r?.total)
        assertContentEquals(file, got)
        assertEquals(listOf<String?>("bytes=0-4194303", "bytes=0-9"), ranges)
    }

    @Test fun aWholeBodyIsAcceptedOnlyFromTheStart() = runTest {
        val ok = api(mutableListOf()) { HttpStatusCode.OK }.collect(0, 3)
        assertEquals(10L, ok.first?.total)
        assertContentEquals(file, ok.second)
        val late = api(mutableListOf()) { HttpStatusCode.OK }.collect(4, 7)
        assertNull(late.first)
        assertTrue(late.second.isEmpty(), "a body that restarts at 0 must not be appended mid-file")
    }

    @Test fun aRangeThatStartsElsewhereIsRefused() = runTest {
        val engine = MockEngine {
            respond(ByteReadChannel(file), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-9/10"))
        }
        val (r, got) = BrokerApi("http://h", "t", HttpClient(engine)).collect(4, 7)
        assertNull(r)
        assertTrue(got.isEmpty())
    }

    @Test fun aMissingFileIsNull() = runTest {
        val (r, _) = api(mutableListOf()) { HttpStatusCode.NotFound }.collect(0, 3)
        assertNull(r)
    }

    @Test fun contentRangeParsing() {
        assertEquals(ContentRange(2, 5, 10), parseContentRange("bytes 2-5/10"))
        assertNull(parseContentRange("bytes 2-5/*"))
        assertNull(parseContentRange(null))
        assertEquals(10L, parseUnsatisfiedRangeTotal("bytes */10"))
        assertNull(parseUnsatisfiedRangeTotal("bytes 0-1/10"))
    }
}
