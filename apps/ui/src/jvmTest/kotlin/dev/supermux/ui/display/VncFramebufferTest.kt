package dev.supermux.ui.display

import androidx.compose.ui.graphics.asSkiaBitmap
import dev.supermux.net.VncRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [VncFrameOps] (the pure pixel math both hosts share: rect validity, dirty-region merge, resize
 * semantics, the BGRA row blit, the CopyRect self-blit and the BGRA→ARGB swizzle) and the JVM
 * actual of [VncFramebuffer] (the Skia adapter). Skia's ColorType.BGRA_8888 accepts the RFB wire
 * format directly, so uploadRaw is a plain row-by-row copy there; the swizzle is exercised at the
 * ops level: the ARGB actual needs a real platform bitmap and there is no Robolectric in the
 * version catalog, so the actual itself is covered on device only — a recorded gap.
 *
 * Moved here from desktop's own suite in cluster G2, name kept.
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

    // ── VncFramebuffer (Skia adapter spike) ─────────────────────────────────

    @Test fun apply_update_writes_a_raw_rect_into_the_skia_bitmap_with_correct_colors() {
        val fb = VncFramebuffer()
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
        val fb = VncFramebuffer()
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
        val fb = VncFramebuffer()
        fb.applyUpdate(listOf(VncRect(0, 0, 2, 2, ByteArray(2 * 2 * 4))), 2 to 2)

        // Out-of-bounds rect must not throw and must not touch the bitmap.
        fb.applyUpdate(listOf(VncRect(5, 5, 2, 2, ByteArray(2 * 2 * 4))), null)

        assertEquals(2, fb.bitmap.value!!.width)
    }

    @Test fun apply_update_reallocates_on_a_size_change_and_reuses_the_bitmap_on_a_same_size_update() {
        val fb = VncFramebuffer()
        // First frame at 2x2 (red at 0,0).
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))), 2 to 2)
        assertEquals(2, fb.bitmap.value!!.width)
        assertEquals(2, fb.bitmap.value!!.height)

        // A DesktopSize change to 3x1 must reallocate the backing bitmap to the new dimensions.
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0xFF.toByte()))), 3 to 1)
        assertEquals(3, fb.bitmap.value!!.width)
        assertEquals(1, fb.bitmap.value!!.height)

        // A same-size (3x1) update still paints correctly into the reused backing bitmap — green at (1,0).
        fb.applyUpdate(listOf(VncRect(1, 0, 1, 1, byteArrayOf(0x00, 0xFF.toByte(), 0x00, 0xFF.toByte()))), 3 to 1)
        assertEquals(0xFF00FF00.toInt(), fb.bitmap.value!!.asSkiaBitmap().getColor(1, 0))
    }

    @Test fun a_rejected_rect_leaves_the_painted_frame_untouched() {
        val fb = VncFramebuffer()
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, byteArrayOf(1, 2, 3, 4))), 1 to 1)
        val painted = fb.bitmap.value

        // Out of bounds, and a Raw rect whose payload is short: neither applies, so nothing is
        // uploaded and Compose sees the SAME frame (no recomposition on an empty update).
        fb.applyUpdate(listOf(VncRect(5, 5, 1, 1, ByteArray(4))), null)
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, ByteArray(3))), null)
        fb.applyUpdate(emptyList(), null)

        assertSame(painted, fb.bitmap.value)
    }

    @Test fun a_resize_pushes_a_new_frame_even_when_no_rect_applies() {
        val fb = VncFramebuffer()
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, byteArrayOf(1, 2, 3, 4))), 1 to 1)

        fb.applyUpdate(emptyList(), 4 to 3)

        assertEquals(4, fb.bitmap.value!!.width)
        assertEquals(3, fb.bitmap.value!!.height)
    }

    @Test fun release_clears_the_bitmap_state() {
        val fb = VncFramebuffer()
        fb.applyUpdate(listOf(VncRect(0, 0, 1, 1, byteArrayOf(1, 2, 3, 4))), 1 to 1)

        fb.release()

        assertNull(fb.bitmap.value)
    }

    // ── dirty-region merge, resize semantics, rect validity, the ARGB swizzle (pure) ───────────

    @Test fun accepts_rejects_an_empty_out_of_bounds_or_short_rect_and_a_copy_from_outside() {
        assertTrue(VncFrameOps.accepts(VncRect(0, 0, 2, 2, ByteArray(2 * 2 * 4)), 4, 4))
        assertFalse(VncFrameOps.accepts(VncRect(0, 0, 0, 2, ByteArray(0)), 4, 4), "empty")
        assertFalse(VncFrameOps.accepts(VncRect(3, 0, 2, 2, ByteArray(16)), 4, 4), "runs off the right edge")
        assertFalse(VncFrameOps.accepts(VncRect(-1, 0, 2, 2, ByteArray(16)), 4, 4), "negative origin")
        assertFalse(VncFrameOps.accepts(VncRect(0, 0, 2, 2, ByteArray(15)), 4, 4), "short payload")
        assertFalse(VncFrameOps.accepts(VncRect(0, 0, 2, 2, ByteArray(0)), 0, 0), "no framebuffer yet")
        // A CopyRect carries no payload at all — only its SOURCE rect has to be inside.
        assertTrue(VncFrameOps.accepts(VncRect(0, 0, 2, 2, ByteArray(0), isCopy = true, srcX = 2, srcY = 2), 4, 4))
        assertFalse(VncFrameOps.accepts(VncRect(0, 0, 2, 2, ByteArray(0), isCopy = true, srcX = 3, srcY = 0), 4, 4))
    }

    @Test fun dirty_region_merges_every_applied_rect_into_one_bounding_box() {
        val rects = listOf(
            VncRect(1, 1, 2, 2, ByteArray(2 * 2 * 4)),                                   // (1,1)-(3,3)
            VncRect(5, 0, 1, 1, ByteArray(4)),                                            // (5,0)-(6,1)
            VncRect(0, 4, 2, 2, ByteArray(0), isCopy = true, srcX = 0, srcY = 0),         // (0,4)-(2,6)
        )

        assertEquals(FrameRegion(0, 0, 6, 6), VncFrameOps.dirtyRegion(rects, 8, 8))
    }

    @Test fun dirty_region_ignores_rejected_rects_and_is_null_when_nothing_applies() {
        val good = VncRect(1, 1, 1, 1, ByteArray(4))
        val bad = VncRect(9, 9, 1, 1, ByteArray(4))

        assertEquals(FrameRegion(1, 1, 1, 1), VncFrameOps.dirtyRegion(listOf(good, bad), 4, 4))
        assertNull(VncFrameOps.dirtyRegion(listOf(bad), 4, 4))
        assertNull(VncFrameOps.dirtyRegion(emptyList(), 4, 4))
    }

    @Test fun union_takes_the_bounding_box_and_a_null_left_side_is_the_right_side() {
        val a = FrameRegion(2, 2, 2, 2)
        assertEquals(a, VncFrameOps.union(null, a))
        assertEquals(FrameRegion(0, 2, 4, 3), VncFrameOps.union(a, FrameRegion(0, 3, 1, 2)))
        // A contained region changes nothing.
        assertEquals(a, VncFrameOps.union(a, FrameRegion(3, 3, 1, 1)))
    }

    @Test fun needs_resize_only_on_a_real_size_change_with_a_positive_size() {
        assertTrue(VncFrameOps.needsResize(0, 0, 2, 2), "first size")
        assertTrue(VncFrameOps.needsResize(2, 2, 2, 3), "DesktopSize change")
        assertFalse(VncFrameOps.needsResize(2, 2, 2, 2), "same size reuses the bitmap")
        assertFalse(VncFrameOps.needsResize(2, 2, 0, 2), "no size reported yet")
        assertFalse(VncFrameOps.needsResize(2, 2, -1, -1))
    }

    @Test fun the_swizzle_turns_bgra_bytes_into_opaque_argb_ints() {
        // blue, green, red, white — the same rect the Skia adapter test uploads verbatim.
        val bgra = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0x00,
            0x00, 0xFF.toByte(), 0x00, 0x00,
            0x00, 0x00, 0xFF.toByte(), 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00,
        )
        val out = IntArray(4)

        VncFrameOps.swizzleBgraToArgb(bgra, out, 4)

        // Alpha is forced opaque even though the wire bytes carry zero there.
        assertEquals(listOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0xFFFFFFFF.toInt()), out.toList())
    }

    @Test fun the_swizzle_fills_only_the_first_count_pixels_of_a_reused_scratch_array() {
        // The ARGB actual keeps ONE scratch IntArray sized for the whole framebuffer and swizzles
        // only the current rect into its head — the tail must stay untouched.
        val scratch = IntArray(4) { -1 }

        VncFrameOps.swizzleBgraToArgb(byteArrayOf(0x01, 0x02, 0x03, 0x04), scratch, 1)

        assertEquals(0xFF030201.toInt(), scratch[0])
        assertEquals(-1, scratch[3])
    }
}
