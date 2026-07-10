package dev.supermux.desktop.display

import androidx.compose.ui.graphics.asSkiaBitmap
import dev.supermux.net.VncRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M5-2 Task 3: [VncFrameOps] (pure byte-array pixel math — Raw upload + CopyRect self-blit) and
 * [DesktopVncFramebuffer] (the Skia-bitmap adapter). Unlike Android's android.graphics.Bitmap
 * (ARGB_8888 only, forcing a per-pixel BGRA→ARGB swizzle), Skia's ColorType.BGRA_8888 accepts the
 * RFB wire format directly — [VncFrameOps.uploadRaw] is a plain row-by-row arraycopy, no swizzle.
 * The Skia calls in [DesktopVncFramebuffer] are deterministic (no GPU/display needed), so this
 * suite gives it real coverage rather than leaving it untested like M5-1's hardware-bound
 * MicRecorder — these tests double as this task's Skia-API spike.
 */
class VncFramebufferTest {

    // ── VncFrameOps (pure) ─────────────────────────────────────────────────────────

    @Test fun upload_raw_copies_a_sub_rect_row_by_row_into_the_full_buffer() {
        val fbW = 4; val fbH = 2
        val buffer = ByteArray(fbW * fbH * 4)
        // 2x1 raw rect at (1,1): two BGRA pixels, B=0x11.. and B=0x22..
        val bgra = byteArrayOf(0x11, 0x12, 0x13, 0x14, 0x21, 0x22, 0x23, 0x24)

        VncFrameOps.uploadRaw(buffer, fbW, x = 1, y = 1, w = 2, h = 1, bgra = bgra)

        val rowOff = (1 * fbW + 1) * 4
        assertEquals(bgra.toList(), buffer.copyOfRange(rowOff, rowOff + 8).toList())
        // Untouched pixels stay zero.
        assertEquals(0, buffer[0])
    }

    @Test fun copy_rect_duplicates_a_sub_rect_within_the_same_buffer() {
        val fbW = 3; val fbH = 3
        val buffer = ByteArray(fbW * fbH * 4)
        VncFrameOps.uploadRaw(buffer, fbW, x = 0, y = 0, w = 1, h = 1, bgra = byteArrayOf(9, 8, 7, 6))

        VncFrameOps.copyRect(buffer, fbW, sx = 0, sy = 0, dx = 2, dy = 2, w = 1, h = 1)

        val dstOff = (2 * fbW + 2) * 4
        assertEquals(listOf<Byte>(9, 8, 7, 6), buffer.copyOfRange(dstOff, dstOff + 4).toList())
    }

    @Test fun copy_rect_handles_an_overlapping_source_and_destination_without_corruption() {
        // A 2-row rect shifted DOWN by 1 row (dy = sy+1): dest row1 IS source row1's own location,
        // so a naive forward-row-by-row copy would clobber source row1 (with source row0's data)
        // BEFORE it's read for the second row — VncFrameOps.copyRect must snapshot the source into
        // scratch first, or this corrupts.
        val fbW = 2; val fbH = 3
        val buffer = ByteArray(fbW * fbH * 4)
        VncFrameOps.uploadRaw(buffer, fbW, x = 0, y = 0, w = 2, h = 1, bgra = byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0))
        VncFrameOps.uploadRaw(buffer, fbW, x = 0, y = 1, w = 2, h = 1, bgra = byteArrayOf(3, 0, 0, 0, 4, 0, 0, 0))

        VncFrameOps.copyRect(buffer, fbW, sx = 0, sy = 0, dx = 0, dy = 1, w = 2, h = 2)

        // Row 1 (the shift's destination) must hold source row 0's ORIGINAL data (1, 2) — not
        // corrupted by the row-2 half of the same copy reading it after row 1 was already written.
        assertEquals(1.toByte(), buffer[(1 * fbW + 0) * 4])
        assertEquals(2.toByte(), buffer[(1 * fbW + 1) * 4])
        // Row 2 must hold source row 1's original data (3, 4).
        assertEquals(3.toByte(), buffer[(2 * fbW + 0) * 4])
        assertEquals(4.toByte(), buffer[(2 * fbW + 1) * 4])
    }

    // ── DesktopVncFramebuffer (Skia adapter spike) ─────────────────────────────────

    @Test fun apply_update_writes_a_raw_rect_into_the_skia_bitmap_with_correct_colors() {
        val fb = DesktopVncFramebuffer()
        // 2x2 BGRA raw rect: (0,0)=blue (1,0)=green (0,1)=red (1,1)=white.
        val bgra = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )

        fb.applyUpdate(listOf(VncRect(0, 0, 2, 2, bgra)), 2 to 2)

        val bmp = fb.bitmap.value!!.asSkiaBitmap()
        assertEquals(0xFF0000FF.toInt(), bmp.getColor(0, 0)) // blue
        assertEquals(0xFF00FF00.toInt(), bmp.getColor(1, 0)) // green
        assertEquals(0xFFFF0000.toInt(), bmp.getColor(0, 1)) // red
        assertEquals(0xFFFFFFFF.toInt(), bmp.getColor(1, 1)) // white
    }

    @Test fun apply_update_copy_rect_duplicates_pixels_within_the_bitmap() {
        val fb = DesktopVncFramebuffer()
        val bgra = byteArrayOf(
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), // red at (0,0)
            0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(), // blue at (1,0)
        )
        fb.applyUpdate(listOf(VncRect(0, 0, 2, 1, bgra)), 2 to 2)

        fb.applyUpdate(listOf(VncRect(0, 1, 2, 1, ByteArray(0), isCopy = true, srcX = 0, srcY = 0)), 2 to 2)

        val bmp = fb.bitmap.value!!.asSkiaBitmap()
        assertEquals(bmp.getColor(0, 0), bmp.getColor(0, 1))
        assertEquals(bmp.getColor(1, 0), bmp.getColor(1, 1))
    }

    @Test fun apply_update_ignores_a_rect_that_falls_outside_the_current_framebuffer_bounds() {
        val fb = DesktopVncFramebuffer()
        fb.applyUpdate(listOf(VncRect(0, 0, 2, 2, ByteArray(2 * 2 * 4))), 2 to 2)

        // Out-of-bounds rect must not throw and must not touch the bitmap.
        fb.applyUpdate(listOf(VncRect(5, 5, 2, 2, ByteArray(2 * 2 * 4))), null)

        assertEquals(2, fb.bitmap.value!!.width)
    }

    @Test fun release_clears_the_bitmap_state() {
        val fb = DesktopVncFramebuffer()
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, byteArrayOf(1, 2, 3, 4))), 1 to 1)

        fb.release()

        assertNull(fb.bitmap.value)
    }
}
