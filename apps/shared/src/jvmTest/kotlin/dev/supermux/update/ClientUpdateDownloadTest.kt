package dev.supermux.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The installer download resuming over a real socket after the connection drops mid-body — on
 * cellular a 51 MB APK died at 5.3 MB with "Content-Length mismatch" (2026-10-29). The slow-body
 * half of that bug is covered by the desktop module's InstallerDownloadTimeoutTest.
 */
class ClientUpdateDownloadTest {
    private val payload = ByteArray(64 * 1024) { i -> (i % 251).toByte() }

    /** A one-route HTTP/1.1 server; [serve] answers each accepted connection. */
    private fun server(serve: (Socket, Map<String, String>) -> Unit): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                        reader.readLine()
                        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
                            .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                        runCatching { serve(s, headers) }
                    }
                }
            }
        }
        return ss
    }

    private fun Socket.head(status: String, vararg headers: String) {
        val text = buildString {
            append("HTTP/1.1 $status\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

    private fun client() = HttpClient(CIO) {
        engine { requestTimeout = 300 }
        installerDownloadTimeouts()
    }

    @Test
    fun aConnectionDroppedMidBodyResumesWithARangeRequest() = runBlocking {
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val cut = payload.size / 3
        val ss = server { s, headers ->
            val range = headers["range"]
            ranges += range
            if (range == null) {
                s.head("200 OK", "Content-Length: ${payload.size}")
                s.getOutputStream().write(payload, 0, cut)
                s.getOutputStream().flush()
                // Drop the connection with two thirds of the body still owed.
            } else {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                s.head(
                    "206 Partial Content",
                    "Content-Length: ${payload.size - from}",
                    "Content-Range: bytes $from-${payload.size - 1}/${payload.size}",
                )
                s.getOutputStream().write(payload, from, payload.size - from)
                s.getOutputStream().flush()
            }
        }
        ss.use {
            val ticks = mutableListOf<Pair<Long, Long?>>()
            val got = ClientUpdateChecker(client())
                .download("http://127.0.0.1:${ss.localPort}/a.apk") { r, t -> ticks += r to t }
            assertContentEquals(payload, got)
            assertEquals(listOf(null, "bytes=$cut-"), ranges.toList())
            // Progress never runs backwards across the resume, and always reports the FULL size.
            assertTrue(ticks.zipWithNext().all { (a, b) -> b.first >= a.first })
            assertTrue(ticks.all { it.second == payload.size.toLong() })
            assertEquals(payload.size.toLong(), ticks.last().first)
        }
    }

    @Test
    fun aServerIgnoringRangeRestartsFromZero() = runBlocking {
        var calls = 0
        val ss = server { s, _ ->
            calls++
            s.head("200 OK", "Content-Length: ${payload.size}")
            s.getOutputStream().write(payload, 0, if (calls == 1) payload.size / 2 else payload.size)
            s.getOutputStream().flush()
        }
        ss.use {
            val got = ClientUpdateChecker(client()).download("http://127.0.0.1:${ss.localPort}/a.apk")
            assertContentEquals(payload, got)
        }
    }

    /** A file-backed sink, as the Android updater writes the APK (#140); records each write's size. */
    private class FileSink(val file: java.io.File) : DownloadSink {
        private val raf = java.io.RandomAccessFile(file, "rw").apply { setLength(0) }
        val writes = mutableListOf<Int>()
        override val size: Long get() = raf.length()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes += length
            raf.write(bytes, offset, length)
        }
        override fun clear() {
            raf.setLength(0)
            raf.seek(0)
        }
        fun close() = raf.close()
    }

    @Test
    fun downloadToStreamsIntoTheSinkAndResumesFromWhatIsOnDisk() = runBlocking {
        val big = ByteArray(1024 * 1024) { i -> (i % 253).toByte() }
        val cut = big.size / 3
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val ss = server { s, headers ->
            val range = headers["range"]
            ranges += range
            if (range == null) {
                s.head("200 OK", "Content-Length: ${big.size}")
                s.getOutputStream().write(big, 0, cut)
            } else {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                s.head(
                    "206 Partial Content",
                    "Content-Length: ${big.size - from}",
                    "Content-Range: bytes $from-${big.size - 1}/${big.size}",
                )
                s.getOutputStream().write(big, from, big.size - from)
            }
            s.getOutputStream().flush()
        }
        val file = java.io.File.createTempFile("update", ".apk.part")
        val sink = FileSink(file)
        try {
            ss.use {
                val size = ClientUpdateChecker(client()).downloadTo("http://127.0.0.1:${ss.localPort}/a.apk", sink)
                assertEquals(big.size.toLong(), size)
            }
            sink.close()
            assertContentEquals(big, file.readBytes())
            assertEquals(listOf(null, "bytes=$cut-"), ranges.toList())
            // Streamed: written a buffer at a time, never gathered into one whole-file write.
            assertTrue(sink.writes.size > 1)
            assertTrue(sink.writes.all { it <= 64 * 1024 })
        } finally {
            sink.close()
            file.delete()
        }
    }

    @Test
    fun downloadToStartsTheFileOverWhenTheServerIgnoresRange() = runBlocking {
        var calls = 0
        val ss = server { s, _ ->
            calls++
            s.head("200 OK", "Content-Length: ${payload.size}")
            s.getOutputStream().write(payload, 0, if (calls == 1) payload.size / 2 else payload.size)
            s.getOutputStream().flush()
        }
        val file = java.io.File.createTempFile("update", ".apk.part")
        val sink = FileSink(file)
        try {
            ss.use { ClientUpdateChecker(client()).downloadTo("http://127.0.0.1:${ss.localPort}/a.apk", sink) }
            sink.close()
            // The half from the first try is gone, not left in front of the second, whole body.
            assertContentEquals(payload, file.readBytes())
        } finally {
            sink.close()
            file.delete()
        }
    }
}
