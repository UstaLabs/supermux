// Skia-backed VNC framebuffer for desktop (M5-2). Replaces Android's android.graphics.Bitmap
// renderer: Skia's ColorType.BGRA_8888 accepts the RFB wire format directly, so uploadRaw is a
// plain row-by-row arraycopy — NO per-pixel ARGB swizzle needed (a genuine simplification over
// the mobile code, confirmed against the real skiko-awt/ui-graphics-desktop jars — see this
// task's KDoc in the plan). Painting itself is a plain Compose Image(contentScale = Fit) in
// DisplayPanel.kt — no manual Canvas/letterbox draw code needed here; only pointer-to-remote
// mapping still needs letterbox math, and that lives in VncInput.mapToRemote.
package dev.supermux.desktop.display

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.supermux.net.VncRect
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Pure, unit-testable pixel operations on a flat BGRA framebuffer byte array (stride =
 * width*4, no row padding). Mirrors the byte-level EFFECT of Android's
 * VncFramebuffer.uploadRaw/copyRect, minus the ARGB swizzle (see this file's header).
 */
internal object VncFrameOps {
    /** Blit [bgra] (a Raw rect's w*h*4 bytes) into [buffer] (a fbW*?*4 full-framebuffer BGRA byte
     *  array) at ([x],[y]), row by row. Caller is responsible for bounds-checking. */
    fun uploadRaw(buffer: ByteArray, fbW: Int, x: Int, y: Int, w: Int, h: Int, bgra: ByteArray) {
        val rowBytes = w * 4
        for (row in 0 until h) {
            val srcOff = row * rowBytes
            val dstOff = ((y + row) * fbW + x) * 4
            System.arraycopy(bgra, srcOff, buffer, dstOff, rowBytes)
        }
    }

    /** Copy a [w]x[h] sub-rect from ([sx],[sy]) to ([dx],[dy]) within the SAME [buffer] (a
     *  fbW*?*4 BGRA framebuffer). Reads the source into a scratch buffer first so an overlapping
     *  src/dst rect (e.g. a scrolling window) never corrupts itself mid-copy. */
    fun copyRect(buffer: ByteArray, fbW: Int, sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        val scratch = ByteArray(rowBytes * h)
        for (row in 0 until h) {
            System.arraycopy(buffer, ((sy + row) * fbW + sx) * 4, scratch, row * rowBytes, rowBytes)
        }
        for (row in 0 until h) {
            System.arraycopy(scratch, row * rowBytes, buffer, ((dy + row) * fbW + dx) * 4, rowBytes)
        }
    }
}

/**
 * Desktop's Skia-backed VNC framebuffer: a flat BGRA byte buffer mutated in place by
 * [VncFrameOps], pushed into an [org.jetbrains.skia.Bitmap] via `installPixels` at
 * `ColorType.BGRA_8888`, then wrapped as a Compose [ImageBitmap] via `asComposeImageBitmap()`.
 * [bitmap] is a Compose [MutableState] so [applyUpdate]'s write triggers recomposition of
 * whatever `Image(bitmap.value)` is painting it (DisplayPanel.kt). Not thread-confined by
 * itself — the caller (DisplayPanel's `LaunchedEffect` collecting `VncClient.updates`) always
 * calls [applyUpdate] from the same coroutine, so no internal synchronization is needed.
 */
internal class DesktopVncFramebuffer {
    private var buffer = ByteArray(0)
    private var fbW = 0
    private var fbH = 0
    val bitmap: MutableState<ImageBitmap?> = mutableStateOf(null)

    private fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == fbW && h == fbH)) return
        fbW = w; fbH = h
        buffer = ByteArray(w * h * 4)
    }

    /** Apply one FramebufferUpdate. [size] is the latest VncClient.size (w,h); resizes the
     *  backing buffer to match BEFORE blitting so a DesktopSize change reallocates first
     *  (mirrors Android's VncFramebuffer.applyUpdate ordering). */
    fun applyUpdate(rects: List<VncRect>, size: Pair<Int, Int>?) {
        size?.let { resize(it.first, it.second) }
        if (fbW <= 0 || fbH <= 0) return
        for (r in rects) {
            val x = r.x; val y = r.y; val w = r.width; val h = r.height
            if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > fbW || y + h > fbH) continue
            if (r.isCopy) {
                if (r.srcX < 0 || r.srcY < 0 || r.srcX + w > fbW || r.srcY + h > fbH) continue
                VncFrameOps.copyRect(buffer, fbW, r.srcX, r.srcY, x, y, w, h)
            } else {
                if (r.bgra.size < w * h * 4) continue
                VncFrameOps.uploadRaw(buffer, fbW, x, y, w, h, r.bgra)
            }
        }
        val skiaBitmap = Bitmap().apply {
            allocPixels(ImageInfo(fbW, fbH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
            installPixels(buffer)
        }
        bitmap.value = skiaBitmap.asComposeImageBitmap()
    }

    fun release() {
        buffer = ByteArray(0); fbW = 0; fbH = 0
        bitmap.value = null
    }
}
