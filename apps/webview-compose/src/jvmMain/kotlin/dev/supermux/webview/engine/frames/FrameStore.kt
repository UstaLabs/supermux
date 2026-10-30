package dev.supermux.webview.engine.frames

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * The page's frames as Compose draws them (spec §7 "Linux: frames"): one block of pixel memory the glue writes into.
 * Main thread only.
 * - [pull] has the glue copy what changed in WebKit's newest buffer straight into that memory, then acknowledge the
 *   buffer, so WebKit draws the next one (F10). The first pull, and the first at a new size, copy the whole buffer.
 * - The memory has room for more than the frame: a resize step by step (a `WrapContentHeight` view's height spring)
 *   lands in it, without allocating at each step.
 * - It holds the last frame pulled, as a Skia image over that memory, not a copy of it: Compose's `drawImage` copies a
 *   mutable bitmap in full at every draw (`Image.makeFromBitmap`). That is what every surface of the engine draws, and
 *   what a snapshot copies ([copy]). Only the main thread writes the memory, and draws from it, so no draw sees a frame
 *   being copied.
 */
internal class FrameStore(private val view: FrameSource) {
    /** The memory frames are copied into, with room for more than the frame ([ROOM_STEP]). */
    private var bitmap: Bitmap? = null

    /** That memory as Skia data, which the frame's images share. */
    private var data: Data? = null

    /** The memory before the last reallocation: closed one reallocation later, so a draw in flight never sees it go. */
    private var retired: Bitmap? = null
    private var retiredData: Data? = null
    private var pixels = 0L
    private var rowBytes = 0
    private var roomWidth = 0
    private var roomHeight = 0
    private val out = LongArray(8)
    private var closed = false

    /** The frame to draw, over the store's memory: a new instance at every pull that changed it, so Compose draws it again. */
    var image: Image? by mutableStateOf(null)
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

    /** The oldest frame, by WebKit's sequence, the store may show: older ones are pulled, never shown ([hideUntil]). */
    private var showFrom = 0L

    /** Whether the next frame shown is copied whole: the memory holds a hidden frame's pixels outside its damage. */
    private var wholeNext = false

    /** Whether the store shows nothing until it is told from which frame on ([hideUntil] with [Long.MAX_VALUE]). */
    val hidesAll: Boolean get() = showFrom == Long.MAX_VALUE

    /**
     * Shows nothing older than WebKit's frame [seq]: the frame shown goes unless it is that one or a newer one. Hidden
     * frames are still pulled, as WebKit waits for that, and the first one shown after them is copied whole.
     */
    fun hideUntil(seq: Long) {
        showFrom = seq
        if (this.seq >= seq) return
        image = null
        this.seq = -1L
        drawnSeq = -1L
        wholeNext = true
    }

    /** Copies WebKit's newest frame: only what changed in it, unless [full]. True if [image] changed. */
    fun pull(full: Boolean): Boolean {
        if (closed) return false
        // A frame the store won't show is pulled for WebKit's sake only: what changed in it, never the whole buffer.
        var whole = (full || wholeNext) && view.frameSeq() >= showFrom
        var result = view.pullFrame(pixels, rowBytes, width, height, whole, out)
        if (result == -1) {
            val w = out[1].toInt()
            val h = out[2].toInt()
            if (w <= 0 || h <= 0) return false
            resize(w, h)
            whole = true
            result = view.pullFrame(pixels, rowBytes, width, height, true, out)
        }
        if (result != 1) return false
        if (out[0] < showFrom) {
            wholeNext = true
            return false
        }
        // A frame to show came between the look and the pull, copied in part over a hidden one: again, whole.
        if (wholeNext && !whole && (view.pullFrame(pixels, rowBytes, width, height, true, out) != 1 || out[0] < showFrom)) return false
        wholeNext = false
        val memory = data ?: return false
        image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), memory, rowBytes)
        seq = out[0]
        return true
    }

    /** A surface drew [image]: its frame is on screen, under whatever the app draws over it. */
    fun onDrawn() {
        drawnSeq = seq
    }

    /** A surface comes into the composition: it has drawn nothing yet, whatever the last one drew. */
    fun forgetDrawn() {
        drawnSeq = -1L
    }

    /** A copy of the last frame, at its size: the memory changes in place at the next pull. Null before the first. */
    fun copy(): ImageBitmap? {
        val source = bitmap ?: return null
        if (seq < 0) return null
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bytes = source.readPixels(info, width * 4, 0, 0) ?: return null
        return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
    }

    fun close() {
        closed = true
        image = null
        data = null
        retiredData = null
        bitmap?.close()
        retired?.close()
        bitmap = null
        retired = null
        pixels = 0
        width = 0
        height = 0
    }

    /**
     * The frame is now [w] × [h]: memory with room for it, [ROOM_STEP] pixels at a time, allocated only when it doesn't
     * fit, or holds four times what it needs.
     */
    private fun resize(w: Int, h: Int) {
        val roomW = roomFor(w)
        val roomH = roomFor(h)
        if (w > roomWidth || h > roomHeight || roomW.toLong() * roomH * 4 < roomWidth.toLong() * roomHeight) allocate(roomW, roomH)
        width = w
        height = h
    }

    private fun allocate(w: Int, h: Int) {
        retired?.close()
        retired = bitmap
        retiredData = data
        val next = Bitmap()
        check(next.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL))) { "allocPixels ${w}x$h failed" }
        val pixmap = checkNotNull(next.peekPixels()) { "no pixel memory for ${w}x$h" }
        bitmap = next
        pixels = pixmap.addr
        rowBytes = pixmap.rowBytes
        data = Data.makeWithoutCopy(pixels, rowBytes * h, next)
        roomWidth = w
        roomHeight = h
    }

    private companion object {
        /** What the memory's room grows by: a resize of up to this many pixels lands in what is there. */
        const val ROOM_STEP = 256

        fun roomFor(pixels: Int): Int = (pixels + ROOM_STEP - 1) / ROOM_STEP * ROOM_STEP
    }
}
