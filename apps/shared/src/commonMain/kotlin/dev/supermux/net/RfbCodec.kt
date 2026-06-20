package dev.supermux.net

/**
 * Pure RFB 3.8 (RFC 6143) parse/encode helpers — no I/O, fully unit-testable.
 *
 * Wire ints are **big-endian**. The pixel format is pinned to 32bpp true-colour
 * BGRA (depth 24, little-endian memory, red/green/blue shift 16/8/0) so a decoded
 * pixel lands in memory as `[B, G, R, X]` — Metal `.bgra8Unorm`. The matching
 * `CPIXEL` (used by ZRLE) is the low 3 bytes `[B, G, R]`.
 *
 * Everything here operates on plain [ByteArray]; the streaming WS read side lives
 * in [VncClient]. Encoder outputs are byte-for-byte validated against a real
 * captured handshake (`rfb-client-handshake.bin`).
 */
object RfbCodec {
    // ── client→server message type ids ──────────────────────────────────────
    const val MSG_SET_PIXEL_FORMAT = 0
    const val MSG_SET_ENCODINGS = 2
    const val MSG_FB_UPDATE_REQUEST = 3
    const val MSG_KEY_EVENT = 4
    const val MSG_POINTER_EVENT = 5

    // ── server→client message type ids ──────────────────────────────────────
    const val SMSG_FB_UPDATE = 0
    const val SMSG_SET_COLOUR_MAP = 1
    const val SMSG_BELL = 2
    const val SMSG_SERVER_CUT_TEXT = 3

    // ── encodings ───────────────────────────────────────────────────────────
    const val ENC_RAW = 0
    const val ENC_COPY_RECT = 1
    const val ENC_ZRLE = 16
    const val ENC_DESKTOP_SIZE = -223

    /** The encodings we advertise, most-preferred first: ZRLE, CopyRect, Raw, DesktopSize. */
    val ENCODINGS = intArrayOf(ENC_ZRLE, ENC_COPY_RECT, ENC_RAW, ENC_DESKTOP_SIZE)

    // ── protocol version ──────────────────────────────────────────────────────

    /** Parse `"RFB 003.008\n"` → (major, minor). Throws on malformed input. */
    fun parseProtocolVersion(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 12) { "ProtocolVersion needs 12 bytes, got ${bytes.size}" }
        val s = bytes.copyOf(12).decodeToString()
        val m = Regex("""RFB (\d{3})\.(\d{3})\n""").find(s)
            ?: throw IllegalArgumentException("bad ProtocolVersion: ${s.trim()}")
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** Our ProtocolVersion reply (always 3.8 — we speak RFB 3.8). */
    fun protocolVersionReply(): ByteArray = "RFB 003.008\n".encodeToByteArray()

    // ── init ──────────────────────────────────────────────────────────────────

    /** ClientInit: a single U8 shared-flag (1 = shared, don't disconnect other clients). */
    fun encodeClientInit(shared: Boolean = true): ByteArray = byteArrayOf(if (shared) 1 else 0)

    /** Parsed ServerInit: framebuffer size + desktop name (pixel format is ignored — we pin ours). */
    class ServerInit(val width: Int, val height: Int, val name: String)

    /**
     * Parse ServerInit: U16 width, U16 height, 16-byte PIXEL_FORMAT, U32 nameLen, name.
     * Returns null if [bytes] is too short to hold the whole message.
     */
    fun parseServerInit(bytes: ByteArray): ServerInit? {
        if (bytes.size < 24) return null
        val w = u16(bytes, 0)
        val h = u16(bytes, 2)
        // bytes[4..19] = PIXEL_FORMAT (ignored)
        val nameLen = u32(bytes, 20)
        if (bytes.size < 24 + nameLen) return null
        val name = bytes.copyOfRange(24, 24 + nameLen).decodeToString()
        return ServerInit(w, h, name)
    }

    /** Total on-wire size of a ServerInit whose name is [nameLen] bytes. */
    fun serverInitSize(nameLen: Int): Int = 24 + nameLen

    // ── SetPixelFormat (msg 0) ─────────────────────────────────────────────────

    /**
     * SetPixelFormat pinned to 32bpp BGRA: U8 type=0, pad(3), then the 16-byte
     * PIXEL_FORMAT — bpp=32, depth=24, big-endian=0, true-colour=1, max=255/255/255,
     * shift=16/8/0, pad(3). In little-endian memory the 4 pixel bytes are [B,G,R,X].
     */
    fun encodeSetPixelFormat(): ByteArray = byteArrayOf(
        MSG_SET_PIXEL_FORMAT.toByte(), 0, 0, 0, // type + pad(3)
        32, // bits-per-pixel
        24, // depth
        0,  // big-endian-flag = 0
        1,  // true-colour-flag = 1
        0, 255.toByte(), // red-max   = 255
        0, 255.toByte(), // green-max = 255
        0, 255.toByte(), // blue-max  = 255
        16, // red-shift
        8,  // green-shift
        0,  // blue-shift
        0, 0, 0, // padding
    )

    // ── SetEncodings (msg 2) ────────────────────────────────────────────────────

    /** SetEncodings: U8 type=2, pad(1), U16 count, then S32 encoding ids (big-endian). */
    fun encodeSetEncodings(encodings: IntArray = ENCODINGS): ByteArray {
        val out = ByteArray(4 + encodings.size * 4)
        out[0] = MSG_SET_ENCODINGS.toByte()
        out[1] = 0 // pad
        putU16(out, 2, encodings.size)
        var o = 4
        for (e in encodings) { putS32(out, o, e); o += 4 }
        return out
    }

    // ── FramebufferUpdateRequest (msg 3) ────────────────────────────────────────

    /** FramebufferUpdateRequest: U8 type=3, U8 incremental, U16 x,y,w,h. */
    fun encodeFramebufferUpdateRequest(incremental: Boolean, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val out = ByteArray(10)
        out[0] = MSG_FB_UPDATE_REQUEST.toByte()
        out[1] = if (incremental) 1 else 0
        putU16(out, 2, x)
        putU16(out, 4, y)
        putU16(out, 6, width)
        putU16(out, 8, height)
        return out
    }

    // ── input encoders ──────────────────────────────────────────────────────────

    /** PointerEvent (msg 5): U8 type, U8 button-mask, U16 x, U16 y. */
    fun encodePointerEvent(buttonMask: Int, x: Int, y: Int): ByteArray {
        val out = ByteArray(6)
        out[0] = MSG_POINTER_EVENT.toByte()
        out[1] = (buttonMask and 0xff).toByte()
        putU16(out, 2, x)
        putU16(out, 4, y)
        return out
    }

    /** KeyEvent (msg 4): U8 type, U8 down-flag, pad(2), U32 keysym. */
    fun encodeKeyEvent(keysym: Long, down: Boolean): ByteArray {
        val out = ByteArray(8)
        out[0] = MSG_KEY_EVENT.toByte()
        out[1] = if (down) 1 else 0
        // out[2..3] = pad
        putU32(out, 4, keysym)
        return out
    }

    // ── VNC-Auth (security type 2) — DES with a bit-reversed key ────────────────

    /**
     * VNC-Auth response: DES-ECB-encrypt each 8-byte half of the 16-byte [challenge]
     * with the password as the key, where **every key byte has its bits reversed**
     * (the historical VNC quirk). The password is truncated/zero-padded to 8 bytes.
     * Returns the 16-byte response.
     */
    fun encodeVncAuthResponse(challenge: ByteArray, password: String): ByteArray {
        require(challenge.size == 16) { "VNC-Auth challenge must be 16 bytes" }
        val key = ByteArray(8)
        val pw = password.encodeToByteArray()
        for (i in 0 until 8) {
            val b = if (i < pw.size) pw[i].toInt() and 0xff else 0
            key[i] = reverseBits(b).toByte()
        }
        val des = Des(key)
        val out = ByteArray(16)
        des.encryptBlock(challenge, 0, out, 0)
        des.encryptBlock(challenge, 8, out, 8)
        return out
    }

    // ── little big-endian helpers (public for VncClient/tests) ──────────────────

    fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
    fun u32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
    fun s32(b: ByteArray, off: Int): Int = u32(b, off)

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xff).toByte()
        b[off + 1] = (v and 0xff).toByte()
    }
    fun putS32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }
    fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }

    /** Reverse the 8 bits of a byte value (0..255). */
    fun reverseBits(v: Int): Int {
        var x = v and 0xff
        x = ((x and 0xF0) ushr 4) or ((x and 0x0F) shl 4)
        x = ((x and 0xCC) ushr 2) or ((x and 0x33) shl 2)
        x = ((x and 0xAA) ushr 1) or ((x and 0x55) shl 1)
        return x and 0xff
    }
}

// X keysyms used by sendCtrlAltDel and common special keys.
object Keysyms {
    const val CONTROL_L = 0xFFE3L
    const val ALT_L = 0xFFE9L
    const val DELETE = 0xFFFFL
    const val BACKSPACE = 0xFF08L
    const val TAB = 0xFF09L
    const val RETURN = 0xFF0DL
    const val ESCAPE = 0xFF1BL
    const val LEFT = 0xFF51L
    const val UP = 0xFF52L
    const val RIGHT = 0xFF53L
    const val DOWN = 0xFF54L
}
