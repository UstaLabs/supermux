package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic FramebufferUpdate parsing — Raw + CopyRect geometry (Task A3). */
class FramebufferUpdateTest {
    @Test fun parses_raw_and_copyrect() {
        // Build a FramebufferUpdate body: pad, nRects=2,
        //   rect0: (1,2,2,1) Raw → 2 BGRA pixels
        //   rect1: (5,6,3,4) CopyRect srcX=7 srcY=8
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add(((v ushr 8) and 0xff).toByte()); out.add((v and 0xff).toByte()) }
        fun s32(v: Int) {
            out.add(((v ushr 24) and 0xff).toByte()); out.add(((v ushr 16) and 0xff).toByte())
            out.add(((v ushr 8) and 0xff).toByte()); out.add((v and 0xff).toByte())
        }
        out.add(0) // pad
        u16(2)     // nRects
        // rect0 Raw
        u16(1); u16(2); u16(2); u16(1); s32(0)
        // 2 pixels: [B,G,R,X]
        listOf(10, 20, 30, 255, 40, 50, 60, 255).forEach { out.add(it.toByte()) }
        // rect1 CopyRect
        u16(5); u16(6); u16(3); u16(4); s32(1)
        u16(7); u16(8) // srcX, srcY

        val data = out.toByteArray()
        val parsed = FramebufferUpdate.parseBody(data, 0, ZrleDecoder())!!
        assertEquals(2, parsed.rects.size)
        assertEquals(data.size, parsed.consumed)

        val raw = parsed.rects[0]
        assertEquals(1, raw.x); assertEquals(2, raw.y); assertEquals(2, raw.width); assertEquals(1, raw.height)
        assertTrue(!raw.isCopy)
        assertTrue(raw.bgra.contentEquals(byteArrayOf(10, 20, 30, 255.toByte(), 40, 50, 60, 255.toByte())))

        val copy = parsed.rects[1]
        assertTrue(copy.isCopy)
        assertEquals(5, copy.x); assertEquals(6, copy.y); assertEquals(3, copy.width); assertEquals(4, copy.height)
        assertEquals(7, copy.srcX); assertEquals(8, copy.srcY)
        assertEquals(0, copy.bgra.size)
    }

    @Test fun incomplete_body_returns_null() {
        // claims 1 rect but truncates before the rect header
        val data = byteArrayOf(0, 0, 1)
        assertNull(FramebufferUpdate.parseBody(data, 0, ZrleDecoder()))
    }

    @Test fun desktop_size_pseudo_rect() {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add(((v ushr 8) and 0xff).toByte()); out.add((v and 0xff).toByte()) }
        fun s32(v: Int) {
            out.add(((v ushr 24) and 0xff).toByte()); out.add(((v ushr 16) and 0xff).toByte())
            out.add(((v ushr 8) and 0xff).toByte()); out.add((v and 0xff).toByte())
        }
        out.add(0); u16(1)
        u16(0); u16(0); u16(1024); u16(768); s32(-223)
        val parsed = FramebufferUpdate.parseBody(out.toByteArray(), 0, ZrleDecoder())!!
        assertEquals(1, parsed.rects.size)
        assertEquals(1024, parsed.rects[0].width)
        assertEquals(768, parsed.rects[0].height)
    }
}
