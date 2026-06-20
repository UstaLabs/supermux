package dev.supermux.net

/**
 * Synchronous FramebufferUpdate parsing over a complete in-memory byte buffer.
 *
 * The live [VncClient] reads RFB messages off a streaming WS via a suspend
 * `readN`, but the *decoding* of an already-available message is pure and is
 * shared here so it can be unit-tested directly (synthetic Raw/CopyRect/ZRLE
 * buffers) and reused by the client. ZRLE is delegated to a caller-supplied
 * [ZrleDecoder] so the connection-long zlib stream is preserved across messages.
 */
object FramebufferUpdate {
    /** Result of parsing one FramebufferUpdate: the decoded rects + how many bytes were consumed. */
    class Parsed(val rects: List<VncRect>, val consumed: Int)

    /**
     * Parse a FramebufferUpdate **body** (the bytes *after* the U8 message-type id):
     * pad(1), U16 nRects, then each rect (U16 x,y,w,h + S32 enc + encoding body).
     * [data]/[start] point at the pad byte. Returns null if [data] is too short to
     * hold the whole message. ZRLE rects use [zrle]; pass a fresh one per "stream".
     */
    fun parseBody(data: ByteArray, start: Int, zrle: ZrleDecoder): Parsed? {
        var p = start
        fun need(n: Int): Boolean = p + n <= data.size
        if (!need(3)) return null
        p += 1 // pad
        val nRects = RfbCodec.u16(data, p); p += 2
        val rects = ArrayList<VncRect>(nRects)
        for (i in 0 until nRects) {
            if (!need(12)) return null
            val x = RfbCodec.u16(data, p)
            val y = RfbCodec.u16(data, p + 2)
            val w = RfbCodec.u16(data, p + 4)
            val h = RfbCodec.u16(data, p + 6)
            val enc = RfbCodec.s32(data, p + 8)
            p += 12
            when (enc) {
                RfbCodec.ENC_RAW -> {
                    val n = w * h * 4
                    if (p + n > data.size) return null
                    val bgra = data.copyOfRange(p, p + n); p += n
                    rects.add(VncRect(x, y, w, h, bgra))
                }
                RfbCodec.ENC_COPY_RECT -> {
                    if (p + 4 > data.size) return null
                    val srcX = RfbCodec.u16(data, p)
                    val srcY = RfbCodec.u16(data, p + 2)
                    p += 4
                    rects.add(VncRect(x, y, w, h, ByteArray(0), isCopy = true, srcX = srcX, srcY = srcY))
                }
                RfbCodec.ENC_ZRLE -> {
                    if (p + 4 > data.size) return null
                    val len = RfbCodec.u32(data, p); p += 4
                    if (p + len > data.size) return null
                    val payload = data.copyOfRange(p, p + len); p += len
                    val bgra = try {
                        zrle.decodeRect(payload, w, h)
                    } catch (t: Throwable) {
                        ByteArray(w * h * 4)
                    }
                    rects.add(VncRect(x, y, w, h, bgra))
                }
                RfbCodec.ENC_DESKTOP_SIZE -> {
                    rects.add(VncRect(x, y, w, h, ByteArray(0))) // pseudo: geometry only
                }
                else -> return null // unsupported encoding in a synthetic buffer
            }
        }
        return Parsed(rects, p - start)
    }
}
