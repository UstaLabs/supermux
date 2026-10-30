package dev.supermux.webview.engine.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * The page's frames as Compose draws them (spec §7 "Linux: frames"): one Skia bitmap the glue writes into. Main thread
 * only.
 * - [pull] has the glue copy what changed in WebKit's newest buffer straight into the bitmap's pixels
 *   (`peekPixels().addr`), then acknowledge the buffer, so WebKit draws the next one (F10). The first pull, and the
 *   first at a new size, copy the whole buffer.
 * - It holds the last frame pulled: what every surface of the engine draws, and what a snapshot copies ([copy]).
 */
internal class FrameStore(private val view: WpeView) {
    private var bitmap: Bitmap? = null

    /** The bitmap before the last size change: closed one change later, so a draw in flight never sees it closed. */
    private var retired: Bitmap? = null
    private var pixels = 0L
    private var rowBytes = 0
    private val out = LongArray(8)
    private var closed = false

    /** The frame to draw: a new instance at every pull that changed it, so Compose draws it again. */
    var image: ImageBitmap? by mutableStateOf(null)
        private set

    /** WebKit's sequence number of the frame in [image]; -1 before the first. */
    var seq: Long = -1L
        private set

    /** The sequence of the frame a surface last drew. */
    var drawnSeq: Long = -1L
        private set

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** Copies WebKit's newest frame: only what changed in it, unless [full]. True if [image] changed. */
    fun pull(full: Boolean): Boolean {
        if (closed) return false
        var result = view.pullFrame(pixels, rowBytes, width, height, full, out)
        if (result == -1) {
            val w = out[1].toInt()
            val h = out[2].toInt()
            if (w <= 0 || h <= 0) return false
            allocate(w, h)
            result = view.pullFrame(pixels, rowBytes, width, height, true, out)
        }
        if (result != 1) return false
        val target = bitmap ?: return false
        target.notifyPixelsChanged()
        image = target.asComposeImageBitmap()
        seq = out[0]
        return true
    }

    /** A surface drew [image]: its frame is on screen, under whatever the app draws over it. */
    fun onDrawn() {
        drawnSeq = seq
    }

    /** A copy of the last frame, at its size: the bitmap changes in place at the next pull. Null before the first. */
    fun copy(): ImageBitmap? {
        val source = bitmap ?: return null
        if (seq < 0) return null
        val bytes = source.readPixels() ?: return null
        return Image.makeRaster(source.imageInfo, bytes, source.rowBytes).toComposeImageBitmap()
    }

    fun close() {
        closed = true
        image = null
        bitmap?.close()
        retired?.close()
        bitmap = null
        retired = null
        pixels = 0
        width = 0
        height = 0
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
