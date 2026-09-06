// Cluster G2: ONE VNC framebuffer. The pixel math (rect validity, dirty-region merge, resize
// semantics, the BGRA row blit and the BGRA->ARGB swizzle) is pure and lives here; only the upload
// into a platform bitmap is a per-host actual — Skia takes the RFB wire format (BGRA) directly,
// Android's bitmap is ARGB_8888 and needs the swizzle.
package dev.supermux.ui.display

import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import dev.supermux.net.VncRect

/**
 * A rectangle of the framebuffer that one update touched, in framebuffer pixels.
 *
 * Produced by [VncFrameOps.dirtyRegion]; `null` there means "this update changed nothing", which is
 * the signal an actual uses to skip the bitmap push (and therefore the recomposition) entirely.
 */
data class FrameRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * Pure, unit-testable pixel operations over a VNC framebuffer — everything both hosts computed
 * identically before this file existed, in one place.
 *
 * The buffer form the byte-level helpers work on is the RFB wire form: a flat BGRA byte array,
 * stride = width*4, no row padding. That is what Skia uploads verbatim; the Android actual keeps
 * its pixels in the platform bitmap instead and only borrows [accepts], [dirtyRegion],
 * [needsResize] and [swizzleBgraToArgb].
 */
object VncFrameOps {
    /**
     * Whether a rect may be applied to a [fbW]x[fbH] framebuffer.
     *
     * The rule both hosts already shared, verbatim: non-empty, fully inside the framebuffer, and —
     * for a CopyRect — a source rect that is inside too; a Raw rect must also carry at least w*h*4
     * bytes. A rejected rect is skipped, never clamped (the server is wrong about the size, so
     * painting half of it would paint garbage).
     */
    fun accepts(r: VncRect, fbW: Int, fbH: Int): Boolean {
        if (fbW <= 0 || fbH <= 0) return false
        if (r.width <= 0 || r.height <= 0) return false
        if (r.x < 0 || r.y < 0 || r.x + r.width > fbW || r.y + r.height > fbH) return false
        return if (r.isCopy) {
            r.srcX >= 0 && r.srcY >= 0 && r.srcX + r.width <= fbW && r.srcY + r.height <= fbH
        } else {
            r.bgra.size >= r.width * r.height * 4
        }
    }

    /** Whether a framebuffer of [curW]x[curH] must be reallocated to hold [w]x[h].
     *
     *  A zero/negative size is ignored (the server has not reported one yet) and a same-size update
     *  REUSES the backing bitmap — reallocating per frame would churn the native pixel buffer on
     *  every FramebufferUpdate. */
    fun needsResize(curW: Int, curH: Int, w: Int, h: Int): Boolean =
        w > 0 && h > 0 && (w != curW || h != curH)

    /** The union of every rect in [rects] that [accepts] lets through, or null if none does. */
    fun dirtyRegion(rects: List<VncRect>, fbW: Int, fbH: Int): FrameRegion? {
        var acc: FrameRegion? = null
        for (r in rects) {
            if (!accepts(r, fbW, fbH)) continue
            acc = union(acc, FrameRegion(r.x, r.y, r.width, r.height))
        }
        return acc
    }

    /** Merge two dirty regions into their bounding box. A null [a] merges to [b]. */
    fun union(a: FrameRegion?, b: FrameRegion): FrameRegion {
        if (a == null) return b
        val x = minOf(a.x, b.x)
        val y = minOf(a.y, b.y)
        return FrameRegion(x, y, maxOf(a.right, b.right) - x, maxOf(a.bottom, b.bottom) - y)
    }

    /** Blit [bgra] (a Raw rect's w*h*4 bytes) into [buffer] (a fbW*?*4 full-framebuffer BGRA byte
     *  array) at ([x],[y]), row by row. Caller is responsible for bounds-checking ([accepts]). */
    fun uploadRaw(buffer: ByteArray, fbW: Int, x: Int, y: Int, w: Int, h: Int, bgra: ByteArray) {
        val rowBytes = w * 4
        for (row in 0 until h) {
            val srcOff = row * rowBytes
            val dstOff = ((y + row) * fbW + x) * 4
            bgra.copyInto(buffer, dstOff, srcOff, srcOff + rowBytes)
        }
    }

    /** Copy a [w]x[h] sub-rect from ([sx],[sy]) to ([dx],[dy]) within the SAME [buffer] (a
     *  fbW*?*4 BGRA framebuffer). Reads the source into a scratch buffer first so an overlapping
     *  src/dst rect (e.g. a scrolling window) never corrupts itself mid-copy. */
    fun copyRect(buffer: ByteArray, fbW: Int, sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        val scratch = ByteArray(rowBytes * h)
        for (row in 0 until h) {
            val off = ((sy + row) * fbW + sx) * 4
            buffer.copyInto(scratch, row * rowBytes, off, off + rowBytes)
        }
        for (row in 0 until h) {
            val off = row * rowBytes
            scratch.copyInto(buffer, ((dy + row) * fbW + dx) * 4, off, off + rowBytes)
        }
    }

    /**
     * BGRA bytes -> opaque ARGB ints (swizzle B<->R, alpha forced to 0xFF), the first [count]
     * pixels into [out]. The RFB stream carries no usable alpha, and a transparent framebuffer
     * would composite the panel's background through the remote desktop.
     */
    fun swizzleBgraToArgb(bgra: ByteArray, out: IntArray, count: Int) {
        var s = 0
        var d = 0
        while (d < count) {
            out[d] = (0xFF shl 24) or
                ((bgra[s + 2].toInt() and 0xFF) shl 16) or // R
                ((bgra[s + 1].toInt() and 0xFF) shl 8) or // G
                (bgra[s].toInt() and 0xFF) // B
            s += 4
            d++
        }
    }
}

/**
 * The live framebuffer of one VNC stream: apply the server's rects, get a Compose [ImageBitmap].
 *
 * [bitmap] is Compose [State], so a `DisplayPanel` painting `Image(bitmap.value)` recomposes on
 * every applied update and the letterbox is `ContentScale.Fit` — the same formula `VncInput
 * .mapToRemote` maps a pointer with, so a tap lands on the pixel it looks like it lands on.
 *
 * Not thread-confined: the caller (the panel's `LaunchedEffect` collecting `VncClient.updates`)
 * always calls [applyUpdate] from the same coroutine. The bitmap state is read from the composition
 * thread, which Compose snapshots handle.
 *
 * The two actuals differ ONLY in the upload: Skia's `BGRA_8888` bitmap takes the wire bytes
 * directly (a row-wise arraycopy through [VncFrameOps.uploadRaw]), Android's `ARGB_8888` bitmap
 * needs [VncFrameOps.swizzleBgraToArgb] first. Everything else is [VncFrameOps].
 */
expect class VncFramebuffer() {
    /** The latest frame, or null before the first applied update (and after [release]). */
    val bitmap: State<ImageBitmap?>

    /**
     * Apply one FramebufferUpdate. [size] is the latest `VncClient.size` (w,h); the backing bitmap
     * is resized to match BEFORE blitting, so a DesktopSize change reallocates first. An update
     * whose rects are all rejected leaves [bitmap] untouched.
     */
    fun applyUpdate(rects: List<VncRect>, size: Pair<Int, Int>?)

    /** Drop the frame and free the platform bitmap. The instance is reusable afterwards. */
    fun release()
}
