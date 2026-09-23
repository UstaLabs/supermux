package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText

/** One frame as it came off the wire. Binary is pty bytes, text is control. */
sealed interface TerminalWireFrame {
    class Binary(val bytes: ByteArray) : TerminalWireFrame
    class Text(val text: String) : TerminalWireFrame
}

/**
 * The socket a [TerminalClient] connection runs on.
 *
 * ONE receiver. Frames must come out in arrival order, because that order is
 * the protocol: a control frame that overtakes the bytes it belongs behind
 * would let a `reset` clear a screen that had not been drawn yet.
 */
interface TerminalSocket {
    /** The next frame, or null once the peer closed. Suspends until one of the two. */
    suspend fun receive(): TerminalWireFrame?
    suspend fun sendBinary(bytes: ByteArray)
    suspend fun sendText(text: String)
}

/**
 * Opens terminal sockets. An interface because the client's LIFECYCLE — the
 * queue budget, what survives a reconnect, who is unblocked on close — is
 * worth testing without a network, and because Ktor's MockEngine cannot do
 * WebSockets.
 */
interface TerminalTransport {
    /**
     * Connect to [url], run [session] with the open socket, and close the
     * socket when it returns or throws. Throwing is how a connection failure
     * is reported; returning normally means the peer closed.
     */
    suspend fun open(url: String, token: String, session: suspend (TerminalSocket) -> Unit)
}

/** The real one. */
class KtorTerminalTransport(private val http: HttpClient) : TerminalTransport {
    override suspend fun open(url: String, token: String, session: suspend (TerminalSocket) -> Unit) {
        http.webSocket(urlString = url, request = { bearer(token) }) {
            val ws = this
            session(object : TerminalSocket {
                override suspend fun receive(): TerminalWireFrame? {
                    while (true) {
                        // Ping/pong/close frames are the transport's business,
                        // not the protocol's; skip them without breaking order.
                        when (val frame = ws.incoming.receiveCatching().getOrNull() ?: return null) {
                            is Frame.Binary -> return TerminalWireFrame.Binary(frame.readBytes())
                            is Frame.Text -> return TerminalWireFrame.Text(frame.readText())
                            else -> continue
                        }
                    }
                }
                override suspend fun sendBinary(bytes: ByteArray) = ws.send(Frame.Binary(true, bytes))
                override suspend fun sendText(text: String) = ws.send(Frame.Text(text))
            })
        }
    }
}
