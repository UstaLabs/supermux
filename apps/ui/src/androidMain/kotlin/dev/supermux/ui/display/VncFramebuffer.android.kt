package dev.supermux.ui.display

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.supermux.net.VncRect

/**
 * The Android actual: one ARGB_8888 [Bitmap] holds the whole framebuffer, and every Raw rect goes
 * through [VncFrameOps.swizzleBgraToArgb] before `setPixels` (the platform bitmap has no BGRA
 * config). CopyRect is a getPixels/setPixels round trip through the same scratch array, which
 * snapshots the source and so survives an overlapping source/destination exactly as the shared
 * [VncFrameOps.copyRect] does for the byte-buffer form.
 *
 * The scratch [IntArray] is reallocated only on a framebuffer resize, as the pre-G2 code did.
 * `applyUpdate` is `@Synchronized` because the collect coroutine that calls it is not the thread
 * that reads [bitmap]; the pixel writes themselves are not snapshot state.
 */
actual class VncFramebuffer actual constructor() {
    private var pixels: Bitmap? = null
    private var fbW = 0
    private var fbH = 0
    private var scratch = IntArray(0)
    private val frame: MutableState<ImageBitmap?> = mutableStateOf(null)
    actual val bitmap: State<ImageBitmap?> get() = frame

    // NOT recycled on resize/release (the pre-G2 code did): the bitmap is now handed to Compose as
    // an ImageBitmap, and a frame already in flight would draw a recycled bitmap and crash. Dropping
    // the reference is enough — an ARGB_8888 bitmap's pixels are ordinary tracked allocations.
    private fun resize(w: Int, h: Int): Boolean {
        if (!VncFrameOps.needsResize(fbW, fbH, w, h)) return false
        pixels = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fbW = w
        fbH = h
        scratch = IntArray(w * h)
        return true
    }

    @Synchronized
    actual fun applyUpdate(rects: List<VncRect>, size: Pair<Int, Int>?) {
        val resized = size?.let { resize(it.first, it.second) } ?: false
        val bmp = pixels ?: return
        val dirty = VncFrameOps.dirtyRegion(rects, fbW, fbH)
        for (r in rects) {
            if (!VncFrameOps.accepts(r, fbW, fbH)) continue
            val n = r.width * r.height
            val px = if (scratch.size >= n) scratch else IntArray(n)
            if (r.isCopy) {
                bmp.getPixels(px, 0, r.width, r.srcX, r.srcY, r.width, r.height)
            } else {
                VncFrameOps.swizzleBgraToArgb(r.bgra, px, n)
            }
            bmp.setPixels(px, 0, r.width, r.x, r.y, r.width, r.height)
        }
        // Nothing applied AND no reallocation: the painted frame is still current.
        if (dirty == null && !resized) return
        // A fresh (cheap) wrapper around the SAME native bitmap, so Compose's state sees a new
        // identity and repaints — mirrors the Skia actual.
        frame.value = bmp.asImageBitmap()
    }

    @Synchronized
    actual fun release() {
        pixels = null
        fbW = 0
        fbH = 0
        scratch = IntArray(0)
        frame.value = null
    }
}
