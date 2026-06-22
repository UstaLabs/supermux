package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class VncStatus { CONNECTING, CONNECTED, DISCONNECTED, NEEDS_PASSWORD }

/**
 * One decoded framebuffer rectangle in pinned 32-bit BGRA (4 bytes/pixel), OR a
 * CopyRect ([isCopy]=true, [srcX]/[srcY] set, [bgra] empty).
 */
class VncRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val bgra: ByteArray,
    val isCopy: Boolean = false,
    val srcX: Int = 0,
    val srcY: Int = 0,
)

/**
 * WebSocket client for a display's VNC stream (`/ws/display`). The broker bridges
 * the loopback VNC server's RFB bytes verbatim, so this runs the **full RFB 3.8
 * protocol** (RFC 6143): handshake → security (None or VNC-Auth/DES) → init →
 * SetPixelFormat (pinned 32bpp BGRA) → SetEncodings([ZRLE,CopyRect,Raw,DesktopSize])
 * → a request-driven FramebufferUpdate loop. Decoded rects are emitted via
 * [updates] (one list per FramebufferUpdate).
 *
 * Mirrors [ScrcpyClient]'s reconnect structure (backoff [500,1000,2000,4000,8000]ms,
 * [stopped] flag, CancellationException rethrow).
 */
class VncClient(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient,
    private val streamId: String,
) {
    private val _updates = MutableSharedFlow<List<VncRect>>(extraBufferCapacity = 64)
    val updates: SharedFlow<List<VncRect>> = _updates
    private val _status = MutableStateFlow(VncStatus.DISCONNECTED)
    val status: StateFlow<VncStatus> = _status
    private val _size = MutableStateFlow<Pair<Int, Int>?>(null)
    val size: StateFlow<Pair<Int, Int>?> = _size

    private var liveSession: DefaultClientWebSocketSession? = null
    @Volatile private var stopped = false

    // Password for VNC-Auth (macOS). A rendezvous channel unblocks the handshake
    // once setPassword arrives; the latest value is remembered for reconnects.
    @Volatile private var password: String? = null
    private val passwordSignal = Channel<Unit>(Channel.CONFLATED)

    // ── rolling input buffer (WS frames don't align to RFB messages) ──────────
    private var rolling = RollingBuffer()

    suspend fun run() {
        var attempt = 0
        while (!stopped) {
            try {
                _status.value = VncStatus.CONNECTING
                http.webSocket(
                    urlString = "${wsBaseUrl(baseUrl)}/ws/display?id=$streamId",
                    request = { header("Authorization", "Bearer $token") },
                ) {
                    liveSession = this
                    rolling = RollingBuffer()
                    val zrle = ZrleDecoder()
                    try {
                        val ok = handshake()
                        if (ok) {
                            attempt = 0
                            _status.value = VncStatus.CONNECTED
                            sessionLoop(zrle)
                        }
                    } finally {
                        zrle.close()
                        liveSession = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // fall through to backoff
            }
            _status.value = VncStatus.DISCONNECTED
            if (stopped) break
            val delayMs = listOf(500L, 1000L, 2000L, 4000L, 8000L)[minOf(attempt, 4)]
            attempt++
            delay(delayMs)
        }
    }

    /** Run handshake + init + SetPixelFormat/SetEncodings. Returns false if it could not complete. */
    private suspend fun DefaultClientWebSocketSession.handshake(): Boolean {
        // 1. ProtocolVersion
        RfbCodec.parseProtocolVersion(readN(12))
        send(Frame.Binary(true, RfbCodec.protocolVersionReply()))

        // 2. Security types
        val count = readN(1)[0].toInt() and 0xff
        if (count == 0) {
            // failure: U32 reason length + reason
            val rlen = RfbCodec.u32(readN(4), 0)
            if (rlen > 0) readN(rlen)
            return false
        }
        val types = readN(count)
        val secType: Int = when {
            types.any { it.toInt() == 1 } -> 1
            types.any { it.toInt() == 2 } -> 2
            else -> return false
        }
        send(Frame.Binary(true, byteArrayOf(secType.toByte())))

        if (secType == 2) {
            // VNC-Auth: 16-byte challenge → DES response (needs a password)
            var pw = password
            if (pw == null) {
                _status.value = VncStatus.NEEDS_PASSWORD
                // wait until setPassword provides one (or stop)
                while (pw == null && !stopped) {
                    passwordSignal.receive()
                    pw = password
                }
                if (stopped || pw == null) return false
            }
            val challenge = readN(16)
            send(Frame.Binary(true, RfbCodec.encodeVncAuthResponse(challenge, pw)))
        }

        // 3. SecurityResult
        val result = RfbCodec.u32(readN(4), 0)
        if (result != 0) {
            // 3.8: failure → U32 reason length + reason
            try {
                val rlen = RfbCodec.u32(readN(4), 0)
                if (rlen in 1..4096) readN(rlen)
            } catch (_: Throwable) {}
            if (secType == 2) _status.value = VncStatus.NEEDS_PASSWORD
            return false
        }

        // 4. ClientInit
        send(Frame.Binary(true, RfbCodec.encodeClientInit(shared = true)))

        // 5. ServerInit
        val head = readN(24)
        val w = RfbCodec.u16(head, 0)
        val h = RfbCodec.u16(head, 2)
        val nameLen = RfbCodec.u32(head, 20)
        if (nameLen in 1..65535) readN(nameLen) // consume the name
        _size.value = w to h

        // 6. SetPixelFormat + SetEncodings
        send(Frame.Binary(true, RfbCodec.encodeSetPixelFormat()))
        send(Frame.Binary(true, RfbCodec.encodeSetEncodings()))

        // 7. First (full) FramebufferUpdateRequest
        send(Frame.Binary(true, RfbCodec.encodeFramebufferUpdateRequest(false, 0, 0, w, h)))
        return true
    }

    /** Read & dispatch server messages until the socket closes. */
    private suspend fun DefaultClientWebSocketSession.sessionLoop(zrle: ZrleDecoder) {
        while (!stopped) {
            val type = readN(1)[0].toInt() and 0xff
            when (type) {
                RfbCodec.SMSG_FB_UPDATE -> {
                    readN(1) // pad
                    val nRects = RfbCodec.u16(readN(2), 0)
                    val rects = ArrayList<VncRect>(nRects)
                    for (i in 0 until nRects) {
                        val rh = readN(12)
                        val x = RfbCodec.u16(rh, 0)
                        val y = RfbCodec.u16(rh, 2)
                        val w = RfbCodec.u16(rh, 4)
                        val h = RfbCodec.u16(rh, 6)
                        val enc = RfbCodec.s32(rh, 8)
                        val rect = readRect(zrle, x, y, w, h, enc)
                        if (rect != null) rects.add(rect)
                    }
                    if (rects.isNotEmpty()) _updates.emit(rects)
                    // request the next (incremental) update over the whole framebuffer
                    val sz = _size.value
                    if (sz != null) {
                        send(Frame.Binary(true, RfbCodec.encodeFramebufferUpdateRequest(true, 0, 0, sz.first, sz.second)))
                    }
                }
                RfbCodec.SMSG_SET_COLOUR_MAP -> {
                    // not expected (true-colour pinned); read & discard defensively
                    readN(1) // pad
                    val first = RfbCodec.u16(readN(2), 0) // first-colour (ignored)
                    val n = RfbCodec.u16(readN(2), 0)
                    if (n > 0) readN(n * 6)
                    @Suppress("UNUSED_EXPRESSION") first
                }
                RfbCodec.SMSG_BELL -> { /* no payload */ }
                RfbCodec.SMSG_SERVER_CUT_TEXT -> {
                    readN(3) // pad
                    val len = RfbCodec.u32(readN(4), 0)
                    if (len in 1..(16 * 1024 * 1024)) readN(len)
                }
                else -> throw IllegalStateException("unknown RFB server message type $type")
            }
        }
    }

    /** Read one rectangle's body by encoding. Returns null for DesktopSize (pseudo). */
    private suspend fun DefaultClientWebSocketSession.readRect(
        zrle: ZrleDecoder, x: Int, y: Int, w: Int, h: Int, enc: Int,
    ): VncRect? = when (enc) {
        RfbCodec.ENC_RAW -> {
            val bgra = readN(w * h * 4)
            VncRect(x, y, w, h, bgra)
        }
        RfbCodec.ENC_COPY_RECT -> {
            val sp = readN(4)
            VncRect(x, y, w, h, ByteArray(0), isCopy = true, srcX = RfbCodec.u16(sp, 0), srcY = RfbCodec.u16(sp, 2))
        }
        RfbCodec.ENC_ZRLE -> {
            val len = RfbCodec.u32(readN(4), 0)
            val payload = readN(len)
            // Never crash on a malformed rect: an empty BGRA buffer of the right
            // size is a safe (black) fallback.
            val bgra = try {
                zrle.decodeRect(payload, w, h)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ByteArray(w * h * 4)
            }
            VncRect(x, y, w, h, bgra)
        }
        RfbCodec.ENC_DESKTOP_SIZE -> {
            _size.value = w to h
            null
        }
        else -> throw IllegalStateException("unsupported RFB encoding $enc")
    }

    // ── rolling-buffer reader ─────────────────────────────────────────────────

    /**
     * Read exactly [n] bytes from the WS, pulling more `Frame.Binary` frames as
     * needed (WS frame boundaries don't align to RFB messages). Throws if the
     * socket closes before [n] bytes are available.
     */
    private suspend fun DefaultClientWebSocketSession.readN(n: Int): ByteArray {
        require(n >= 0)
        if (n == 0) return ByteArray(0)
        while (true) {
            val out = rolling.take(n)
            if (out != null) return out
            val frame = incoming.receive()
            if (frame is Frame.Binary) {
                rolling.append(frame.readBytes())
            } else if (frame is Frame.Close) {
                throw IllegalStateException("connection closed")
            }
            // ignore Text/Ping/Pong
        }
    }

    // ── public input + control ────────────────────────────────────────────────

    fun setPassword(pw: String) {
        password = pw
        // Unblock a handshake that's parked in NEEDS_PASSWORD waiting on the signal.
        passwordSignal.trySend(Unit)
        // If we're already past the handshake (e.g. a prior wrong password landed
        // us back in the update loop or a NEEDS_PASSWORD reconnect cycle), cancel
        // the live session so run()'s loop reconnects and re-runs auth with the
        // new password. Best-effort.
        liveSession?.let { s -> try { s.cancel() } catch (_: Throwable) {} }
    }

    suspend fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        liveSession?.send(Frame.Binary(true, RfbCodec.encodePointerEvent(buttonMask, x, y)))
    }

    suspend fun sendKey(keysym: Long, down: Boolean) {
        liveSession?.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(keysym, down)))
    }

    suspend fun sendCtrlAltDel() {
        val s = liveSession ?: return
        s.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(Keysyms.CONTROL_L, true)))
        s.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(Keysyms.ALT_L, true)))
        s.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(Keysyms.DELETE, true)))
        s.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(Keysyms.DELETE, false)))
        s.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(Keysyms.ALT_L, false)))
        s.send(Frame.Binary(true, RfbCodec.encodeKeyEvent(Keysyms.CONTROL_L, false)))
    }

    fun stop() {
        stopped = true
        passwordSignal.trySend(Unit) // unblock a NEEDS_PASSWORD wait
    }
}
