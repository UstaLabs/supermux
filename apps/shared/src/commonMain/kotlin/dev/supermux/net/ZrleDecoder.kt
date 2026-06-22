package dev.supermux.net

/**
 * ZRLE (encoding 16) decoder. RFB ZRLE compresses each rectangle's payload with
 * **one zlib stream shared across the whole connection** — so a single decoder
 * instance must live for the connection and feed every rect's bytes into the
 * same [ZlibInflater].
 *
 * After inflation a rect is walked as **64×64 tiles**, left→right, top→bottom.
 * Each tile starts with a sub-encoding byte:
 *   - `0`           raw CPIXELs (tw*th of them)
 *   - `1`           solid (one CPIXEL fills the tile)
 *   - `2..16`       packed palette (palette of N, indices bit-packed per row)
 *   - `128`         plain RLE (CPIXEL + run-length)
 *   - `129`         unused (reserved)
 *   - `130..255`    palette RLE (palette of N-128, then index/run stream)
 *
 * `CPIXEL` is **3 bytes** for our pinned 32bpp / true-colour / MSB-zero format —
 * the low 3 bytes `[B, G, R]` (the X byte is implicit). Each is expanded to BGRA
 * (`[B, G, R, 0xFF]`) in the output buffer.
 *
 * Output is a tightly-packed `width*height*4` BGRA buffer for the rect. The
 * decoder never throws on a structurally-valid-but-truncated tile stream past
 * what was inflated; callers should still guard the whole rect (see [VncClient]).
 */
class ZrleDecoder(private val inflater: ZlibInflater = ZlibInflater()) {
    private val TILE = 64

    // Accumulated inflated bytes not yet consumed by a tile walk. ZRLE rects are
    // self-contained once their compressed `len` is inflated, but inflate() may
    // emit in chunks, so we drain fully before walking.
    private fun drainInflated(): ByteArray {
        val parts = ArrayList<ByteArray>()
        while (true) {
            val chunk = inflater.inflate()
            if (chunk.isEmpty()) break
            parts.add(chunk)
        }
        if (parts.size == 1) return parts[0]
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /**
     * Decode one ZRLE rectangle. [compressed] is the rect's raw payload (the
     * `len` bytes after the U32 length). [width]×[height] is the rect geometry.
     * Returns a `width*height*4` BGRA buffer.
     */
    fun decodeRect(compressed: ByteArray, width: Int, height: Int): ByteArray {
        inflater.feed(compressed)
        val data = drainInflated()
        return walkTiles(data, width, height)
    }

    /** Tile-walk already-inflated [data] into a [width]×[height] BGRA buffer (exposed for tests). */
    fun walkTiles(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        if (width <= 0 || height <= 0) return out
        var p = 0
        fun u8(): Int {
            if (p >= data.size) return -1
            return data[p++].toInt() and 0xff
        }
        // Read a CPIXEL (3 bytes B,G,R) into a freshly allocated 4-byte BGRA.
        fun cpixel(): ByteArray {
            val b = if (p < data.size) data[p++] else 0
            val g = if (p < data.size) data[p++] else 0
            val r = if (p < data.size) data[p++] else 0
            return byteArrayOf(b, g, r, 0xFF.toByte())
        }

        var ty = 0
        while (ty < height) {
            val th = minOf(TILE, height - ty)
            var tx = 0
            while (tx < width) {
                val tw = minOf(TILE, width - tx)
                val sub = u8()
                if (sub < 0) return out // ran out of data; leave the rest black
                when {
                    sub == 0 -> { // raw
                        for (yy in 0 until th) for (xx in 0 until tw) {
                            putPixel(out, width, tx + xx, ty + yy, cpixel())
                        }
                    }
                    sub == 1 -> { // solid
                        val px = cpixel()
                        for (yy in 0 until th) for (xx in 0 until tw) {
                            putPixel(out, width, tx + xx, ty + yy, px)
                        }
                    }
                    sub in 2..16 -> { // packed palette
                        val palette = Array(sub) { cpixel() }
                        val bpp = when {
                            sub <= 2 -> 1
                            sub <= 4 -> 2
                            else -> 4
                        }
                        val mask = (1 shl bpp) - 1
                        for (yy in 0 until th) {
                            var bitsLeft = 0
                            var acc = 0
                            for (xx in 0 until tw) {
                                if (bitsLeft == 0) { acc = u8(); bitsLeft = 8 }
                                bitsLeft -= bpp
                                val idx = (acc ushr bitsLeft) and mask
                                putPixel(out, width, tx + xx, ty + yy, palette.getOrElse(idx) { BLACK })
                            }
                            // rows are byte-aligned; leftover bits in `acc` discarded by resetting per row
                        }
                    }
                    sub == 128 -> { // plain RLE
                        val total = tw * th
                        var n = 0
                        while (n < total) {
                            val px = cpixel()
                            var runLen = 1
                            while (true) {
                                val b = u8(); if (b < 0) return out
                                runLen += b
                                if (b != 255) break
                            }
                            var k = 0
                            while (k < runLen && n < total) {
                                putPixel(out, width, tx + (n % tw), ty + (n / tw), px)
                                n++; k++
                            }
                        }
                    }
                    sub >= 130 -> { // palette RLE
                        val pSize = sub - 128
                        val palette = Array(pSize) { cpixel() }
                        val total = tw * th
                        var n = 0
                        while (n < total) {
                            val raw = u8(); if (raw < 0) return out
                            if (raw < 128) {
                                putPixel(out, width, tx + (n % tw), ty + (n / tw), palette.getOrElse(raw) { BLACK })
                                n++
                            } else {
                                val idx = raw - 128
                                var runLen = 1
                                while (true) {
                                    val b = u8(); if (b < 0) return out
                                    runLen += b
                                    if (b != 255) break
                                }
                                val px = palette.getOrElse(idx) { BLACK }
                                var k = 0
                                while (k < runLen && n < total) {
                                    putPixel(out, width, tx + (n % tw), ty + (n / tw), px)
                                    n++; k++
                                }
                            }
                        }
                    }
                    else -> { /* 129 unused: skip tile defensively */ }
                }
                tx += TILE
            }
            ty += TILE
        }
        return out
    }

    private fun putPixel(out: ByteArray, width: Int, x: Int, y: Int, px: ByteArray) {
        val off = (y * width + x) * 4
        if (off < 0 || off + 4 > out.size) return
        out[off] = px[0]; out[off + 1] = px[1]; out[off + 2] = px[2]; out[off + 3] = px[3]
    }

    fun close() = inflater.close()

    private companion object {
        val BLACK = byteArrayOf(0, 0, 0, 0xFF.toByte())
    }
}
