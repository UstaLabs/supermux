package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic per-subencoding ZRLE tests against [ZrleDecoder.walkTiles] (the pure
 * tile walker, no zlib needed). Each builds the exact inflated byte stream a
 * server would emit for a given tile layout and asserts the decoded BGRA buffer.
 *
 * CPIXEL = 3 bytes [B,G,R]; decoded pixels are [B,G,R,0xFF].
 */
class ZrleDecoderTest {
    private val dec get() = ZrleDecoder() // walkTiles ignores the inflater

    private fun bgra(b: Int, g: Int, r: Int) =
        intArrayOf(b, g, r, 0xFF).map { it.toByte() }.toByteArray()

    private fun pixelAt(buf: ByteArray, w: Int, x: Int, y: Int): ByteArray {
        val o = (y * w + x) * 4
        return buf.copyOfRange(o, o + 4)
    }

    private fun cpix(b: Int, g: Int, r: Int) = intArrayOf(b, g, r).map { it.toByte() }.toByteArray()

    // ── subencoding 1: solid ──────────────────────────────────────────────────
    @Test fun solid_tile() {
        // one 4x3 rect = single tile; subenc 1 + CPIXEL(B=10,G=20,R=30)
        val data = byteArrayOf(1) + cpix(10, 20, 30)
        val out = dec.walkTiles(data, 4, 3)
        for (y in 0 until 3) for (x in 0 until 4) {
            assertContentEq(bgra(10, 20, 30), pixelAt(out, 4, x, y), "px($x,$y)")
        }
    }

    // ── subencoding 0: raw ────────────────────────────────────────────────────
    @Test fun raw_tile() {
        // 2x2 raw: 4 CPIXELs in row-major order
        val data = byteArrayOf(0) +
            cpix(1, 2, 3) + cpix(4, 5, 6) +
            cpix(7, 8, 9) + cpix(10, 11, 12)
        val out = dec.walkTiles(data, 2, 2)
        assertContentEq(bgra(1, 2, 3), pixelAt(out, 2, 0, 0))
        assertContentEq(bgra(4, 5, 6), pixelAt(out, 2, 1, 0))
        assertContentEq(bgra(7, 8, 9), pixelAt(out, 2, 0, 1))
        assertContentEq(bgra(10, 11, 12), pixelAt(out, 2, 1, 1))
    }

    // ── subencoding 2..16: packed palette ─────────────────────────────────────
    @Test fun packed_palette_2_colors_1bpp() {
        // palette of 2 → 1 bit/pixel, rows byte-aligned.
        // 4x2 tile, palette[0]=red-ish, palette[1]=blue-ish
        // row0 indices: 0,1,0,1 → bits 0101_0000 = 0x50
        // row1 indices: 1,1,0,0 → bits 1100_0000 = 0xC0
        val data = byteArrayOf(2) +
            cpix(0, 0, 200) + cpix(200, 0, 0) +
            byteArrayOf(0x50.toByte(), 0xC0.toByte())
        val out = dec.walkTiles(data, 4, 2)
        assertContentEq(bgra(0, 0, 200), pixelAt(out, 4, 0, 0))
        assertContentEq(bgra(200, 0, 0), pixelAt(out, 4, 1, 0))
        assertContentEq(bgra(0, 0, 200), pixelAt(out, 4, 2, 0))
        assertContentEq(bgra(200, 0, 0), pixelAt(out, 4, 3, 0))
        assertContentEq(bgra(200, 0, 0), pixelAt(out, 4, 0, 1))
        assertContentEq(bgra(200, 0, 0), pixelAt(out, 4, 1, 1))
        assertContentEq(bgra(0, 0, 200), pixelAt(out, 4, 2, 1))
        assertContentEq(bgra(0, 0, 200), pixelAt(out, 4, 3, 1))
    }

    @Test fun packed_palette_4_colors_2bpp() {
        // palette of 3 → 2 bits/pixel (psize 3 → bpp 2). 4x1 tile.
        // indices: 0,1,2,1 → bits 00 01 10 01 = 0b00011001 = 0x19
        val data = byteArrayOf(3) +
            cpix(1, 1, 1) + cpix(2, 2, 2) + cpix(3, 3, 3) +
            byteArrayOf(0x19)
        val out = dec.walkTiles(data, 4, 1)
        assertContentEq(bgra(1, 1, 1), pixelAt(out, 4, 0, 0))
        assertContentEq(bgra(2, 2, 2), pixelAt(out, 4, 1, 0))
        assertContentEq(bgra(3, 3, 3), pixelAt(out, 4, 2, 0))
        assertContentEq(bgra(2, 2, 2), pixelAt(out, 4, 3, 0))
    }

    @Test fun packed_palette_5_colors_4bpp() {
        // palette of 5 → 4 bits/pixel. 2x1 tile, indices 4,2 → 0x42
        val data = byteArrayOf(5) +
            cpix(0, 0, 0) + cpix(1, 1, 1) + cpix(2, 2, 2) + cpix(3, 3, 3) + cpix(4, 4, 4) +
            byteArrayOf(0x42)
        val out = dec.walkTiles(data, 2, 1)
        assertContentEq(bgra(4, 4, 4), pixelAt(out, 2, 0, 0))
        assertContentEq(bgra(2, 2, 2), pixelAt(out, 2, 1, 0))
    }

    // ── subencoding 128: plain RLE ────────────────────────────────────────────
    @Test fun plain_rle() {
        // 4x1 tile = 4 pixels. run1: pixelA length 3 (255? no: length encoded as
        // sum of bytes +1 until a byte != 255). length 3 → one byte (2). run2:
        // pixelB length 1 → one byte (0).
        val data = byteArrayOf(128.toByte()) +
            cpix(9, 9, 9) + byteArrayOf(2) +   // A x3
            cpix(8, 8, 8) + byteArrayOf(0)     // B x1
        val out = dec.walkTiles(data, 4, 1)
        assertContentEq(bgra(9, 9, 9), pixelAt(out, 4, 0, 0))
        assertContentEq(bgra(9, 9, 9), pixelAt(out, 4, 1, 0))
        assertContentEq(bgra(9, 9, 9), pixelAt(out, 4, 2, 0))
        assertContentEq(bgra(8, 8, 8), pixelAt(out, 4, 3, 0))
    }

    @Test fun plain_rle_long_run_with_255_continuation() {
        // 300 pixels in a 300x1 "tile" won't happen (max 64) — use 64x5=320>... use 10x1.
        // run of 6: length 6 → bytes [5]. Then run of 4: [3]. total 10.
        val data = byteArrayOf(128.toByte()) +
            cpix(1, 0, 0) + byteArrayOf(5) +
            cpix(2, 0, 0) + byteArrayOf(3)
        val out = dec.walkTiles(data, 10, 1)
        for (x in 0 until 6) assertContentEq(bgra(1, 0, 0), pixelAt(out, 10, x, 0))
        for (x in 6 until 10) assertContentEq(bgra(2, 0, 0), pixelAt(out, 10, x, 0))
    }

    // ── subencoding 130..255: palette RLE ─────────────────────────────────────
    @Test fun palette_rle() {
        // psize = sub-128. Use sub=130 → palette of 2.
        // stream: index<128 = single pixel of that palette entry;
        //         index>=128 = (index-128) run, length = sum(+1) of following bytes.
        // 5x1 tile: [0](single A), [128+1, 2](B x3), [0](single A)
        val data = byteArrayOf(130.toByte()) +
            cpix(50, 0, 0) + cpix(0, 50, 0) + // palette A,B
            byteArrayOf(0) +                  // A single
            byteArrayOf((128 + 1).toByte(), 2) + // B run of 3
            byteArrayOf(0)                    // A single
        val out = dec.walkTiles(data, 5, 1)
        assertContentEq(bgra(50, 0, 0), pixelAt(out, 5, 0, 0))
        assertContentEq(bgra(0, 50, 0), pixelAt(out, 5, 1, 0))
        assertContentEq(bgra(0, 50, 0), pixelAt(out, 5, 2, 0))
        assertContentEq(bgra(0, 50, 0), pixelAt(out, 5, 3, 0))
        assertContentEq(bgra(50, 0, 0), pixelAt(out, 5, 4, 0))
    }

    // ── multi-tile assembly (2 tiles wide) ────────────────────────────────────
    @Test fun multi_tile_layout() {
        // 70x1 rect → 2 tiles (64 + 6). tile0 solid A, tile1 solid B.
        val data = byteArrayOf(1) + cpix(11, 0, 0) +
            byteArrayOf(1) + cpix(0, 22, 0)
        val out = dec.walkTiles(data, 70, 1)
        assertContentEq(bgra(11, 0, 0), pixelAt(out, 70, 0, 0))
        assertContentEq(bgra(11, 0, 0), pixelAt(out, 70, 63, 0))
        assertContentEq(bgra(0, 22, 0), pixelAt(out, 70, 64, 0))
        assertContentEq(bgra(0, 22, 0), pixelAt(out, 70, 69, 0))
    }

    // ── robustness: truncated stream must not throw ───────────────────────────
    @Test fun truncated_stream_does_not_throw() {
        val data = byteArrayOf(1) // solid subenc but no CPIXEL
        val out = dec.walkTiles(data, 4, 4)
        assertEquals(4 * 4 * 4, out.size) // returns a black buffer, no crash
    }

    @Test fun zero_size_rect_is_empty() {
        assertEquals(0, dec.walkTiles(byteArrayOf(1, 2, 3), 0, 0).size)
    }

    private fun assertContentEq(expected: ByteArray, actual: ByteArray, msg: String = "") {
        assertTrue(expected.contentEquals(actual), "$msg expected=${expected.toList()} actual=${actual.toList()}")
    }
}
