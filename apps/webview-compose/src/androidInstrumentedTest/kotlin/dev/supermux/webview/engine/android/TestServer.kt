package dev.supermux.webview.engine.android

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A small HTTP/1.1 server on 127.0.0.1, in the test APK's own process: what a test needs of a real web server
 * (redirects, an origin that is a secure context for Cache Storage and service workers) without the network. The test
 * APK allows cleartext to 127.0.0.1 and localhost only (`res/xml/test_network_security.xml`). One request per
 * connection; every response is `no-store`, so history navigations ask again.
 */
internal class TestServer : AutoCloseable {
    /** What a path answers; after [delayMillis], for a load that is still under way meanwhile. */
    class Response(
        val status: Int,
        val body: String = "",
        val type: String = "text/html",
        val headers: Map<String, String> = emptyMap(),
        val delayMillis: Long = 0,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, Response>()

    /** The paths asked for, in order. */
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val port: Int = socket.localPort

    /** This server's origin, as `http://127.0.0.1:<port>`. */
    val origin: String = "http://127.0.0.1:$port"

    init {
        thread(isDaemon = true, name = "test-server-$port") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true, name = "test-server-$port-client") { serve(client) }
            }
        }
    }

    /** Answers [path] with [response] from now on, in place of what it answered before. */
    fun route(path: String, response: Response) {
        routes[path] = response
    }

    fun page(path: String, html: String) = route(path, Response(200, html))

    fun redirect(path: String, status: Int, location: String) = route(path, Response(status, headers = mapOf("Location" to location)))

    private fun serve(client: Socket) = client.use { connection ->
        try {
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return@use
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = (requestLine.split(' ').getOrNull(1) ?: "/").substringBefore('?')
            requested += path
            val response = routes[path] ?: Response(404, "not found", "text/plain")
            if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
            val body = response.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
                append("Content-Type: ${response.type}; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            val out = connection.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } catch (e: IOException) {
            // The browser hung up: nothing to answer.
        }
    }

    override fun close() = socket.close()

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        404 -> "Not Found"
        else -> "Status"
    }
}

/** A page titled [title], with a line of text, so it paints content. */
internal fun titledPage(title: String, body: String = title): String =
    """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$title</title></head>
<body style="margin:0;padding:16px;font:16px sans-serif">$body</body></html>"""
