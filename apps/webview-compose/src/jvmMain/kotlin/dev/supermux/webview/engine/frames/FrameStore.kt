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
 * The page's frames as Compose draws them (spec §7 "Linux: frames", "Windows: frames"): blocks of pixel memory the glue
 * writes into. Main thread only.
 * - [pull] has the glue copy what changed in the engine's newest frame straight into a block, then acknowledge it, so
 *   WebKit draws the next one (F10). The first pull, the first at a new size, and the first into another block copy the
 *   whole frame.
 * - A block has room for more than the frame: a resize step by step (a `WrapContentHeight` view's height spring) lands
 *   in it, without allocating at each step.
 * - The store holds the last frame pulled as a Skia image over a block, not a copy of it: Compose's `drawImage` copies a
 *   mutable bitmap in full at every draw (`Image.makeFromBitmap`). That is what every surface of the engine draws, and
 *   what a snapshot copies ([copy]).
 * - Skia owns the blocks' memory, and an image made over a block holds it: Compose records each frame into a picture,
 *   and a recording draws its frame's pixels whenever it is drawn, even after the store closed or took other memory.
 * - With [guardDraws], for a renderer that draws recordings later, on a thread of its own (skiko's Direct3D renderer on
 *   Windows: an engine released while it still had a frame to draw crashed the VM's JVM), a frame never goes into a
 *   block a recording may still draw: into the block the last frame is in if nothing but the store holds its image, else
 *   into another such block, else into a new one, up to [MAX_BLOCKS]; with every block held, the frame waits for the
 *   next pull. Without it (Linux, whose renderer draws on the EDT before the next pull): one block, written in place.
 */
internal class FrameStore(private val view: FrameSource, private val guardDraws: Boolean = false) {
    /** Pixel memory Skia owns, with room for [roomWidth] × [roomHeight] pixels. */
    private class Block(val roomWidth: Int, val roomHeight: Int) {
        val rowBytes = roomWidth * 4
        val data: Data = Data.makeUninitialized(rowBytes * roomHeight)
        val pixels: Long = data.writableData()

        /** The image last made over this block: the store's reference, which recordings of that frame add to. */
        var image: Image? = null

        /** Whether this block holds the last frame pulled: then what changed in the next one is enough. */
        var last = false

        /** Whether something besides the store may still draw this block's pixels: a recording of its image. */
        val held: Boolean get() = (image?.refCount ?: 1) > 1

        /** Whether a [w] × [h] frame fits, and the room isn't four times what it needs. */
        fun fits(w: Int, h: Int): Boolean =
            w <= roomWidth && h <= roomHeight && roomFor(w).toLong() * roomFor(h) * 4 >= roomWidth.toLong() * roomHeight
    }

    /** One block, or with [guardDraws] up to [MAX_BLOCKS]. */
    private val blocks = ArrayList<Block>(MAX_BLOCKS)

    /** The shown image when its block was given up: closed once [image] is another. */
    private var orphan: Image? = null
    private val out = LongArray(8)
    private var closed = false

    /** The frame to draw, over a block: a new instance at every pull that changed it, so Compose draws it again. */
    var image: Image? by mutableStateOf(null)
        private set

    /** The engine's sequence number of the frame in [image]; -1 before the first. */
    var seq: Long = -1L
        private set

    /** The sequence of the frame a surface last drew. */
    var drawnSeq: Long = -1L
        private set

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** The oldest frame, by the engine's sequence, the store may show: older ones are pulled, never shown ([hideUntil]). */
    private var showFrom = 0L

    /** Whether the next frame shown is copied whole: the memory holds a hidden frame's pixels outside its damage. */
    private var wholeNext = false

    /** Whether the store shows nothing until it is told from which frame on ([hideUntil] with [Long.MAX_VALUE]). */
    val hidesAll: Boolean get() = showFrom == Long.MAX_VALUE

    /**
     * Shows nothing older than the engine's frame [seq]: the frame shown goes unless it is that one or a newer one. Hidden
     * frames are still pulled, as WebKit waits for that, and the first one shown after them is copied whole.
     */
    fun hideUntil(seq: Long) {
        showFrom = seq
        if (this.seq >= seq) return
        show(null)
        this.seq = -1L
        drawnSeq = -1L
        wholeNext = true
    }

    /** Copies the engine's newest frame: only what changed in it, unless [full]. True if [image] changed. */
    fun pull(full: Boolean): Boolean {
        if (closed) return false
        var block = writable()
        // Every block may still be drawn (guarded): the frame waits; the engine keeps its newest for the next pull.
        if (block == null && blocks.isNotEmpty()) return false
        // A frame the store won't show is pulled for the engine's sake only: what changed in it, never the whole frame.
        var whole = (full || wholeNext || (block != null && !block.last)) && view.frameSeq() >= showFrom
        // No block yet: asked at no size, so the engine says the frame's.
        var result = if (block == null) view.pullFrame(0L, 0, 0, 0, whole, out) else view.pullFrame(block.pixels, block.rowBytes, width, height, whole, out)
        if (result == -1) {
            val w = out[1].toInt()
            val h = out[2].toInt()
            if (w <= 0 || h <= 0) return false
            block = sized(block, w, h)
            width = w
            height = h
            whole = true
            result = view.pullFrame(block.pixels, block.rowBytes, width, height, true, out)
        }
        if (result != 1 || block == null) return false
        for (other in blocks) other.last = other === block
        if (out[0] < showFrom) {
            wholeNext = true
            return false
        }
        // A frame to show came between the look and the pull, copied in part over a hidden one: again, whole.
        if (wholeNext && !whole && (view.pullFrame(block.pixels, block.rowBytes, width, height, true, out) != 1 || out[0] < showFrom)) return false
        wholeNext = false
        val next = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), block.data, block.rowBytes)
        val previous = block.image
        block.image = next
        show(next)
        // The block's last image: nothing draws it any more (or the block wasn't writable), and the store shows another.
        previous?.close()
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

    /** A copy of the last frame, at its size: a block changes in place at a later pull. Null before the first. */
    fun copy(): ImageBitmap? {
        val shown = image ?: return null
        if (seq < 0) return null
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap()
        try {
            if (!bitmap.allocPixels(info) || !shown.readPixels(bitmap, 0, 0)) return null
            val bytes = bitmap.readPixels(info, width * 4, 0, 0) ?: return null
            return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
        } finally {
            bitmap.close()
        }
    }

    fun close() {
        closed = true
        show(null)
        for (block in blocks) {
            block.image?.close()
            block.image = null
            block.data.close()
        }
        blocks.clear()
        width = 0
        height = 0
    }

    /** [next] is the image shown; the shown one of a block given up is closed once it no longer is. */
    private fun show(next: Image?) {
        image = next
        orphan?.takeIf { it !== next }?.let {
            it.close()
            orphan = null
        }
    }

    /** The block the next frame goes into: the one; guarded, one nothing else may draw, the last frame's first. */
    private fun writable(): Block? {
        if (!guardDraws) return blocks.firstOrNull()
        blocks.firstOrNull { it.last && !it.held }?.let { return it }
        blocks.firstOrNull { !it.held }?.let { return it }
        if (blocks.isEmpty() || blocks.size >= MAX_BLOCKS) return null
        return Block(roomFor(width), roomFor(height)).also { blocks += it }
    }

    /**
     * [block], or a block in its place, with room for a [w] × [h] frame, [ROOM_STEP] pixels at a time: memory is taken
     * only when the frame doesn't fit, or the room is four times what it needs. A recording keeps the old memory.
     */
    private fun sized(block: Block?, w: Int, h: Int): Block {
        if (block != null && block.fits(w, h)) return block
        val next = Block(roomFor(w), roomFor(h))
        if (block != null) {
            blocks.remove(block)
            // The shown image stays until another is: Compose may draw it before the next pull.
            block.image?.let { if (it === image) orphan = it else it.close() }
            block.image = null
            block.data.close()
        }
        blocks += next
        return next
    }

    private companion object {
        /** What a block's room grows by: a resize of up to this many pixels lands in what is there. */
        const val ROOM_STEP = 256

        /** Blocks a guarded store takes at most: the shown frame's, the next one's, and one for a renderer behind. */
        const val MAX_BLOCKS = 3

        fun roomFor(pixels: Int): Int = (pixels + ROOM_STEP - 1) / ROOM_STEP * ROOM_STEP
    }
}
