package dev.supermux.desktop.update

import com.sun.net.httpserver.HttpServer
import dev.supermux.update.ClientUpdateChecker
import dev.supermux.update.installerDownloadTimeouts
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Windows "Download & install" died with "Request timeout has expired … unknown ms": a bare
 * `HttpClient(CIO)` carries CIO's 15 s request deadline, and the .msi takes longer than that.
 * The engine deadline is shortened here so a body trickled out over ~1.2 s outlives it.
 */
class InstallerDownloadTimeoutTest {
    private fun slowServer(chunks: Int = 6, pauseMs: Long = 200): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/installer.msi") { ex ->
                ex.sendResponseHeaders(200, chunks.toLong())
                ex.responseBody.use { out ->
                    repeat(chunks) { out.write('x'.code); out.flush(); Thread.sleep(pauseMs) }
                }
            }
            start()
        }

    @Test
    fun `a download outlives the engine's request deadline`() = runBlocking {
        val server = slowServer()
        try {
            val http = HttpClient(CIO) {
                engine { requestTimeout = 300 }
                installerDownloadTimeouts()
            }
            val bytes = ClientUpdateChecker(http).download("http://127.0.0.1:${server.address.port}/installer.msi")
            assertEquals(6, bytes.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `without the installer timeouts the same download is cut off`() = runBlocking {
        val server = slowServer()
        try {
            val http = HttpClient(CIO) { engine { requestTimeout = 300 } }
            assertFailsWith<Exception> {
                ClientUpdateChecker(http).download("http://127.0.0.1:${server.address.port}/installer.msi")
            }
            Unit
        } finally {
            server.stop(0)
        }
    }
}
