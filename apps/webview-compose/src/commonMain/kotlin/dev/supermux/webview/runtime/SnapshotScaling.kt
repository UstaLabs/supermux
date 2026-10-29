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
    if (viewWidthDp > 0f && most < width) scale = most / width
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

/** The scales, in pixels a dp, an engine's own draw of its page steps down through when it takes too long. */
internal val SNAPSHOT_DRAW_SCALES: List<Float> = listOf(3f, 2.5f, 2f, 1.5f)

/**
 * The most pixels a dp an engine's own draw of its page should take next, after one at [scale] took [nanos], more
 * than [budgetNanos]: the largest of [SNAPSHOT_DRAW_SCALES] below [scale] that would have fit, its time taken to grow
 * with the pixels, the square of the scale; the smallest of them if none would. Never more than [scale].
 */
internal fun scaleAfterOverrun(scale: Float, nanos: Long, budgetNanos: Long): Float {
    for (candidate in SNAPSHOT_DRAW_SCALES) {
        if (candidate >= scale) continue
        val ratio = candidate.toDouble() / scale
        if (nanos * ratio * ratio <= budgetNanos) return candidate
    }
    return min(scale, SNAPSHOT_DRAW_SCALES.last())
}
