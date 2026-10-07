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

    /**
     * Give up on this socket, from anywhere, without suspending.
     *
     * A suspended [receive] is the only thing standing between
     * `TerminalClient.stop()` and a `run()` that returns: nothing else wakes a
     * client parked on a healthy socket that has simply gone quiet, and a
     * caller told to "just cancel the job too" is a caller who will forget.
     * After this, [receive] returns null promptly — the same answer it gives
     * when the peer closes, because from the protocol's side that is what
     * happened. Idempotent, and safe to call while another coroutine is
     * inside [receive] or a send.
     */
    fun close()
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
                // Cancelling the INCOMING channel, not the session: a cancelled
                // session would come back out of `webSocket` as a
                // CancellationException, and `run()` cannot tell that apart
                // from its own caller cancelling it. This way the receive ends
                // in null, the session block returns normally, and Ktor closes
                // the socket on its way out.
                override fun close() { ws.incoming.cancel() }
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
