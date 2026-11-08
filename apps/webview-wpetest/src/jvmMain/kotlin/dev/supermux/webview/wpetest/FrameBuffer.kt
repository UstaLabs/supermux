package dev.supermux.webview.wpetest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.ConcurrentLinkedQueue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** One pull: when, how long the native copy took, what it copied. */
data class Pull(val seq: Long, val atNs: Long, val copyNs: Long, val pixels: Long, val full: Boolean, val rects: Int)

/**
 * The persistent Skia bitmap WebKit's frames land in (spec §7: SHM buffers plus damage into a persistent bitmap).
 * [pull] runs on the UI thread at the start of a Compose frame: the native side copies the damaged rows of the newest
 * buffer straight into this bitmap's pixels, then acknowledges the buffer to WebKit.
 */
class FrameBuffer {
    private var bitmap: Bitmap? = null
    private var retired: Bitmap? = null // closed one allocation later, so it is never closed mid-draw
    private var pixels = 0L
    private var rowBytes = 0
    private val out = LongArray(8)
    private var lastDrawnSeq = -1L

    var image: ImageBitmap? by mutableStateOf(null)
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var seq = 0L
        private set
    /** The marker pixel (0xRRGGBB) of the buffer last pulled; -1 if none. */
    @Volatile var lastMarker = -1L
        private set
    val pulls = ConcurrentLinkedQueue<Pull>()
    val draws = ConcurrentLinkedQueue<Draw>()

    /** Copies what changed since the last pull; [full] copies the whole buffer. True if the image changed. UI thread. */
    fun pull(view: Long, full: Boolean = false): Boolean {
        var result = WpeNative.pullFrame(view, pixels, rowBytes, width, height, full, out)
        if (result == -1) {
            val w = out[1].toInt()
            val h = out[2].toInt()
            if (w <= 0 || h <= 0) return false
            allocate(w, h)
            result = WpeNative.pullFrame(view, pixels, rowBytes, width, height, true, out)
        }
        if (result != 1) return false
        val target = bitmap ?: return false
        target.notifyPixelsChanged()
        image = target.asComposeImageBitmap()
        seq = out[0]
        lastMarker = out[6]
        pulls += Pull(out[0], System.nanoTime(), out[3], out[4], out[5] == 1L, out[7].toInt())
        return true
    }

    /** From the surface's draw: records each pulled frame's first draw. */
    fun onDrawn(atNs: Long) {
        if (seq == lastDrawnSeq) return
        lastDrawnSeq = seq
        draws += Draw(seq, atNs)
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
