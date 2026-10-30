package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The most pixels a dp a snapshot is kept at: the screen's own density, up to this. */
internal const val MAX_SNAPSHOT_SCALE: Float = 3f

/** The most pixels a snapshot is kept with, a safety cap only: about 2,000 × 2,000, more than a phone's screen. */
internal const val MAX_SNAPSHOT_PIXELS: Int = 4_000_000

/**
 * The size a snapshot taken at [width] × [height] px of a view [viewWidthDp] wide is kept at: the screen's own
 * density, at most [maxScale] pixels a dp of the view ([MAX_SNAPSHOT_SCALE] unless an engine draws its own smaller), at
 * most [MAX_SNAPSHOT_PIXELS] pixels, never larger than it was taken, and with its aspect ratio. A view of unknown width
 * ([viewWidthDp] 0 or less) keeps the pixel cap only.
 */
internal fun snapshotSize(width: Int, height: Int, viewWidthDp: Float, maxScale: Float = MAX_SNAPSHOT_SCALE): IntSize {
    if (width <= 0 || height <= 0) return IntSize(max(width, 0), max(height, 0))
    var scale = 1.0
    val most = viewWidthDp.toDouble() * maxScale
    // Within half a pixel is the width itself: a width in dp times the density it came from rounds back to it.
    if (viewWidthDp > 0f && most + 0.5 < width) scale = most / width
    val pixels = width.toDouble() * height
    if (pixels * scale * scale > MAX_SNAPSHOT_PIXELS) scale = min(scale, sqrt(MAX_SNAPSHOT_PIXELS / pixels))
    if (scale >= 1.0) return IntSize(width, height)
    // Rounded down (a hair over a whole pixel is that pixel, not one less), so the caps hold; a side of at least 1 px.
    val w = max(1, floor(width * scale + 1e-6).toInt())
    var h = max(1, floor(height * scale + 1e-6).toInt())
    while (w.toLong() * h > MAX_SNAPSHOT_PIXELS && h > 1) h--
    return IntSize(w, h)
}

/**
 * [image], a page's snapshot, as the runtime keeps it: as sharp as the page, so a cover looks like the page itself. At
 * [snapshotSize], so the screen's own density up to 3 pixels a dp, and in full colour ([ImageBitmapConfig.Argb8888]),
 * never 16-bit, which bands gradients. The cache stores it encoded ([SnapshotCache]), which is what keeps hundreds of
 * them small. Returns [image] itself when it is kept as it is, and when it can't be redrawn.
 */
internal fun keptSnapshot(image: ImageBitmap, viewWidthDp: Float): ImageBitmap {
    val size = snapshotSize(image.width, image.height, viewWidthDp)
    if (size.width <= 0 || size.height <= 0) return image
    val asItIs = size.width == image.width && size.height == image.height && image.config == ImageBitmapConfig.Argb8888
    return if (asItIs) image else redraw(image, size) ?: image
}

/** [image] drawn, filtered, into a new full-colour bitmap of [size]; null where the platform can't make one. */
private fun redraw(image: ImageBitmap, size: IntSize): ImageBitmap? = try {
    val target = ImageBitmap(size.width, size.height, ImageBitmapConfig.Argb8888)
    val paint = Paint().apply { filterQuality = FilterQuality.Medium }
    val whole = IntSize(image.width, image.height)
    Canvas(target).drawImageRect(image, IntOffset.Zero, whole, IntOffset.Zero, size, paint)
    target
} catch (e: IllegalArgumentException) {
    null
} catch (e: UnsupportedOperationException) {
    null
} catch (e: IllegalStateException) {
    null
}

/** The scales, in pixels a dp, an engine's own draw of its page steps down through when it would take too long. */
internal val SNAPSHOT_DRAW_SCALES: List<Float> = listOf(3f, 2.5f, 2f, 1.5f)

/** The share of its time bound an engine's own draw is planned to take: a draw's time varies by a fifth or so. */
internal const val DRAW_MARGIN: Double = 0.8

/**
 * The most pixels an engine's own draw of its page fits in [budgetNanos], judged from one that took [nanos] for
 * [pixels]: its time taken to grow with the pixels, and planned at [DRAW_MARGIN] of the bound.
 */
internal fun pixelsWithin(pixels: Long, nanos: Long, budgetNanos: Long): Long =
    if (nanos <= 0) Long.MAX_VALUE else (pixels.toDouble() * budgetNanos * DRAW_MARGIN / nanos).toLong()

/** How much slower a document's first draw is than its next ones (two to four times): its time counts divided by this. */
internal const val COLD_DRAW_FACTOR: Long = 3

/**
 * How many pixels an engine's own draw of a page fits in [boundNanos] on this device ([maxPixels], for [drawScale]),
 * learnt from the draws timed so far, of every page: null until one takes too long. Main thread only.
 * - A draw over the bound lowers it at once, to the pixels that draw would have fit ([pixelsWithin]).
 * - A document's first draw counts too, at a [COLD_DRAW_FACTOR]th of its time: one slow even so lowers the budget.
 *   It never raises it: its time is only a guess at the next one's.
 * - A later draw within the bound raises a lowered budget towards what that draw says fits, at most doubling it each
 *   time, so a slow moment (a busy or a hot phone) doesn't keep every later snapshot small.
 */
internal open class DrawBudget(private val boundNanos: Long) {
    var maxPixels: Long? = null

    /** A draw of [pixels] took [nanos], [cold] if it was its document's first: the budget from now on. */
    fun drawn(pixels: Long, nanos: Long, cold: Boolean): Long? {
        val counted = if (cold) nanos / COLD_DRAW_FACTOR else nanos
        val within = pixelsWithin(pixels, counted, boundNanos)
        val current = maxPixels
        maxPixels = when {
            counted > boundNanos -> if (current == null) within else min(current, within)
            cold || current == null || within <= current -> current
            else -> min(within, if (current > Long.MAX_VALUE / 2) Long.MAX_VALUE else current * 2)
        }
        return maxPixels
    }
}

/**
 * The scale, in pixels a dp, for an engine's own draw of a view [viewWidthDp] × [viewHeightDp]: [fullScale], the
 * screen's density (3 at most), if its pixels are within [maxPixels] (null: no draw has overrun yet); else the largest
 * of [SNAPSHOT_DRAW_SCALES] below it that is; the smallest of them if none is. So a view as big as one that took too
 * long steps down, and a smaller one stays at the screen's density.
 */
internal fun drawScale(viewWidthDp: Float, viewHeightDp: Float, fullScale: Float, maxPixels: Long?): Float {
    if (maxPixels == null || viewWidthDp <= 0f || viewHeightDp <= 0f) return fullScale
    fun fits(scale: Float) = viewWidthDp.toDouble() * scale * viewHeightDp * scale <= maxPixels
    if (fits(fullScale)) return fullScale
    val smaller = SNAPSHOT_DRAW_SCALES.firstOrNull { it < fullScale && fits(it) }
    return smaller ?: min(fullScale, SNAPSHOT_DRAW_SCALES.last())
}
