package dev.supermux.net

import com.sun.net.httpserver.HttpServer
import dev.supermux.state.cioHttpFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * `/speak` must hand each audio piece over AS IT ARRIVES. A real socket, because the bug was Ktor's
 * `post {}` buffering the whole body before returning — MockEngine bodies are in memory either way.
 */
class SpeakStreamJvmTest {
    private val releaseSecond = CountDownLatch(1)
    private val secondSent = AtomicBoolean(false)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/speak") { ex ->
            ex.requestBody.readAllBytes()
            ex.responseHeaders.add("content-type", "application/x-ndjson")
            ex.sendResponseHeaders(200, 0) // chunked
            val out = ex.responseBody
            out.write("""{"i":0,"n":2,"audio":"AQI="}""".toByteArray() + '\n'.code.toByte())
            out.flush()
            // The second piece is held until the client has PLAYED the first one.
            releaseSecond.await(30, TimeUnit.SECONDS)
            secondSent.set(true)
            out.write("""{"i":1,"n":2,"audio":"AwQ="}""".toByteArray() + '\n'.code.toByte())
            out.close()
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun firstChunkArrivesBeforeTheStreamEnds() = runBlocking {
        val api = BrokerApi("ws://127.0.0.1:${server.address.port}", "t", cioHttpFactory()(null))
        val got = mutableListOf<List<Byte>>()
        api.speakStream("hello") { bytes ->
            got += bytes.toList()
            // A buffering client only gets here after the server gave up waiting and sent the rest.
            if (got.size == 1) {
                assertFalse(secondSent.get(), "first chunk must arrive while the server still holds the second")
                releaseSecond.countDown()
            }
        }
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4)), got)
    }
}
