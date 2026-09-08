package dev.supermux.ui.display

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.supermux.net.VncRect
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The Skia actual, IDENTICAL to the JVM one — Compose Multiplatform draws through skiko on iOS too,
 * so `org.jetbrains.skia.Bitmap` and `asComposeImageBitmap()` are the same API here as on desktop
 * and the RFB wire format (BGRA) is uploaded with no per-pixel swizzle (Android's ARGB_8888 bitmap
 * is the odd one out, not this).
 *
 * This is why iOS needs no VideoToolbox decoder to show a display: `Platform.videoDecoder()` is
 * null and every stream falls back to this framebuffer.
 *
 * ONE native bitmap, (re)allocated only on a framebuffer-size change: a fresh Bitmap per update
 * would churn ~fbW*fbH*4 bytes of off-heap memory on every FramebufferUpdate — many per second on a
 * busy desktop. Each update installs pixels into THIS instance and only wraps a fresh (cheap, no
 * native alloc) `asComposeImageBitmap` so Compose's state sees a changed identity.
 *
 * Not `@Synchronized` (the JVM actual is not either): the caller collects `VncClient.updates` on one
 * coroutine, and Kotlin/Native has no `@Synchronized` for a non-JVM target anyway.
 */
actual class VncFramebuffer actual constructor() {
    private var buffer = ByteArray(0)
    private var fbW = 0
    private var fbH = 0
    private var skiaBitmap: Bitmap? = null
    private val frame: MutableState<ImageBitmap?> = mutableStateOf(null)
    actual val bitmap: State<ImageBitmap?> get() = frame

    private fun resize(w: Int, h: Int): Boolean {
        if (!VncFrameOps.needsResize(fbW, fbH, w, h)) return false
        fbW = w
        fbH = h
        buffer = ByteArray(w * h * 4)
        skiaBitmap?.close()
        skiaBitmap = Bitmap().apply {
            allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        }
        return true
    }

    actual fun applyUpdate(rects: List<VncRect>, size: Pair<Int, Int>?) {
        val resized = size?.let { resize(it.first, it.second) } ?: false
        if (fbW <= 0 || fbH <= 0) return
        val applies = VncFrameOps.anyApplies(rects, fbW, fbH)
        for (r in rects) {
            if (!VncFrameOps.accepts(r, fbW, fbH)) continue
            if (r.isCopy) {
                VncFrameOps.copyRect(buffer, fbW, r.srcX, r.srcY, r.x, r.y, r.width, r.height)
            } else {
                VncFrameOps.uploadRaw(buffer, fbW, r.x, r.y, r.width, r.height, r.bgra)
            }
        }
        // Nothing applied AND no reallocation: the painted frame is still current, so skip the
        // upload and the recomposition it would trigger.
        if (!applies && !resized) return
        val bmp = skiaBitmap ?: return
        bmp.installPixels(buffer)
        frame.value = bmp.asComposeImageBitmap()
    }

    actual fun release() {
        buffer = ByteArray(0)
        fbW = 0
        fbH = 0
        skiaBitmap?.close()
        skiaBitmap = null
        frame.value = null
    }
}
