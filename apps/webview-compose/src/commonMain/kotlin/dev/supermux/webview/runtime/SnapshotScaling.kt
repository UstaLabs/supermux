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

/** The most pixels a snapshot is kept with: about 600 × 600. */
internal const val MAX_SNAPSHOT_PIXELS: Int = 360_000

/**
 * The size a snapshot taken at [width] × [height] px of a view [viewWidthDp] wide is kept at: at most one pixel per dp
 * of the view, at most [MAX_SNAPSHOT_PIXELS] pixels, never larger than it was taken, and with its aspect ratio. A view
 * of unknown width ([viewWidthDp] 0 or less) keeps the pixel cap only.
 */
internal fun compactSize(width: Int, height: Int, viewWidthDp: Float): IntSize {
    if (width <= 0 || height <= 0) return IntSize(max(width, 0), max(height, 0))
    var scale = 1.0
    if (viewWidthDp > 0f && viewWidthDp < width) scale = viewWidthDp.toDouble() / width
    val pixels = width.toDouble() * height
    if (pixels * scale * scale > MAX_SNAPSHOT_PIXELS) scale = min(scale, sqrt(MAX_SNAPSHOT_PIXELS / pixels))
    if (scale >= 1.0) return IntSize(width, height)
    // Rounded down (a hair over a whole pixel is that pixel, not one less), so the cap holds; a side of at least 1 px.
    val w = max(1, floor(width * scale + 1e-6).toInt())
    var h = max(1, floor(height * scale + 1e-6).toInt())
    while (w.toLong() * h > MAX_SNAPSHOT_PIXELS && h > 1) h--
    return IntSize(w, h)
}

/**
 * [image], a page's snapshot, as the runtime keeps it: a stand-in for the page while it wakes, which the cover draws
 * scaled to the view, not the page itself. So it is kept small: at [compactSize], and in 16-bit pixels
 * ([ImageBitmapConfig.Rgb565]) when the page is [opaque] and the platform can draw into them, else in 32-bit ones. A
 * slightly soft picture for the half second a page takes to wake is the right trade for a chat that keeps a picture of
 * every page it has shown. Engines that downscale their own snapshots (Android at a density of 2 at most) save work
 * here. Returns [image] itself when there is nothing to gain, and when it can't be redrawn.
 */
internal fun compactSnapshot(image: ImageBitmap, viewWidthDp: Float, opaque: Boolean): ImageBitmap {
    val size = compactSize(image.width, image.height, viewWidthDp)
    if (size.width <= 0 || size.height <= 0) return image
    val config = if (opaque) ImageBitmapConfig.Rgb565 else ImageBitmapConfig.Argb8888
    if (size.width == image.width && size.height == image.height && image.config == config) return image
    return redraw(image, size, config)
        ?: (if (config != ImageBitmapConfig.Argb8888) redraw(image, size, ImageBitmapConfig.Argb8888) else null)
        ?: image
}

/** [image] drawn, filtered, into a new bitmap of [size] and [config]; null where the platform can't make one. */
private fun redraw(image: ImageBitmap, size: IntSize, config: ImageBitmapConfig): ImageBitmap? = try {
    val target = ImageBitmap(size.width, size.height, config, hasAlpha = config != ImageBitmapConfig.Rgb565)
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
