package dev.supermux.webview.wintest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The persistent Skia bitmap captured frames land in (spec §7: changed frames are copied into a persistent bitmap with
 * dirty rects, the VncFrameOps pattern). [pull] runs on the UI thread at the start of a Compose frame: the native side
 * copies only the rows that changed since the last pull, straight into this bitmap's pixels.
 */
class FrameBuffer {
    private var bitmap: Bitmap? = null
    private var retired: Bitmap? = null  // the previous bitmap, closed one allocation later so it is never closed mid-draw
    private var pixels = 0L
    private var rowBytes = 0
    private val out = LongArray(13)
    private val draws = ArrayList<Draw>()
    private var pulledUploadNs = 0L
    private var lastDrawnSeq = 0L

    /** The latest frame, re-wrapped after every pull so Compose sees a new value and redraws. */
    var image: ImageBitmap? by mutableStateOf(null)
        private set
    @Volatile var seq = 0L
        private set
    @Volatile var marker = -1
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    /** System.nanoTime of the first pulled frame; 0 before it. */
    @Volatile var firstFrameNs = 0L
        private set
    /** Draws whose image size differed from the surface's (a live resize draws the last frame scaled). */
    @Volatile var scaledDraws = 0L
        private set

    /** Copies what changed since the last pull. Returns true if the image changed. UI thread only. */
    fun pull(): Boolean {
        val start = System.nanoTime()
        var result = WinNative.copyFrame(pixels, rowBytes, width, height, false, out)
        if (result == -1) {
            val w = out[10].toInt()
            val h = out[11].toInt()
            if (w <= 0 || h <= 0) return false
            allocate(w, h)
            result = WinNative.copyFrame(pixels, rowBytes, width, height, true, out)
        }
        if (result != 1) return false
        val target = bitmap ?: return false
        target.notifyPixelsChanged()
        image = target.asComposeImageBitmap()
        seq = out[0]
        marker = out[1].toInt()
        val end = System.nanoTime()
        pulledUploadNs = end - start
        if (firstFrameNs == 0L) firstFrameNs = end
        return true
    }

    /** From the surface's draw, after drawing [image]: records each pulled frame once, with what it cost. */
    fun onDrawn(drawNs: Long, scaled: Boolean) {
        if (scaled) scaledDraws++
        if (seq == lastDrawnSeq) return
        lastDrawnSeq = seq
        synchronized(draws) {
            if (draws.size < 200_000) {
                draws += Draw(marker, System.nanoTime(), pulledUploadNs + drawNs, scaled, pulledUploadNs, drawNs)
            }
        }
    }

    /** The draws recorded since the last call. Any thread. */
    fun takeDraws(): List<Draw> = synchronized(draws) {
        val copy = draws.toList()
        draws.clear()
        copy
    }

    private fun allocate(w: Int, h: Int) {
        retired?.close()
        retired = bitmap
        val next = Bitmap()
        check(next.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL))) { "allocPixels ${w}x$h failed" }
        val pixmap = checkNotNull(next.peekPixels()) { "no pixel memory for ${w}x$h" }
        bitmap = next
        pixels = pixmap.addr
        rowBytes = pixmap.rowBytes
        width = w
        height = h
    }
}
