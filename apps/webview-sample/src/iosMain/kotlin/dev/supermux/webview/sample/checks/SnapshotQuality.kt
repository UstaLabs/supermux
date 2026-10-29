package dev.supermux.webview.sample.checks

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIGraphicsImageRendererFormatRangeStandard
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.WebKit.WKWebView
import kotlin.math.abs
import kotlin.time.TimeSource

/*
 * Sharp snapshots, the quality bar (the sharp-snapshot plan's decision 5), as the Android quality test measures it: a
 * cover as the runtime keeps and draws it, against the live page as the screen shows it at the same moment. The
 * library's codec is internal to it, so the check codes the snapshot the way the codec does on every Skia platform: a
 * JPEG at quality 92, decoded.
 */

/** The JPEG quality the runtime stores snapshots at (the library's `SNAPSHOT_JPEG_QUALITY`). */
private const val STORED_JPEG_QUALITY = 92

/** Pixels as RGBA, 8 bits a channel, top row first. */
class Pixels(val width: Int, val height: Int, val rgba: ByteArray)

/** How far two pictures of the same size are apart, over every red, green and blue value of every pixel. */
class Difference(val mean: Double, val p99: Int, val most: Int) {
    override fun toString(): String = "mean ${(mean * 100).toLong() / 100.0}, p99 $p99, max $most"

    companion object {
        fun of(a: Pixels, b: Pixels): Difference {
            require(a.width == b.width && a.height == b.height) { "${a.width}x${a.height} against ${b.width}x${b.height}" }
            val counts = LongArray(256)
            var sum = 0L
            var i = 0
            while (i < a.rgba.size) {
                for (channel in 0..2) {
                    val d = abs((a.rgba[i + channel].toInt() and 0xff) - (b.rgba[i + channel].toInt() and 0xff))
                    counts[d]++
                    sum += d
                }
                i += 4
            }
            val total = 3L * a.width * a.height
            var seen = 0L
            var p99 = 255
            for (d in 0..255) {
                seen += counts[d]
                if (seen >= total * 0.99) {
                    p99 = d
                    break
                }
            }
            val most = (255 downTo 0).first { counts[it] > 0 || it == 0 }
            return Difference(sum.toDouble() / total, p99, most)
        }
    }
}

/** The web view the screen shows now: the one `WKWebView` in the app's window. */
fun shownWebView(): WKWebView? = UIApplication.sharedApplication.keyWindow?.let(::findWebView)

private fun findWebView(view: UIView): WKWebView? {
    if (view is WKWebView) return view
    for (child in view.subviews) findWebView(child as UIView)?.let { return it }
    return null
}

/** What the screen shows of [view] now, at the screen's scale: a copy of the window there, in standard colour. */
fun onScreen(view: UIView): Pixels? {
    val format = UIGraphicsImageRendererFormat.preferredFormat()
    format.preferredRange = UIGraphicsImageRendererFormatRangeStandard
    val image = UIGraphicsImageRenderer(bounds = view.bounds, format = format).imageWithActions { _ ->
        view.drawViewHierarchyInRect(view.bounds, afterScreenUpdates = false)
    }
    return pixelsOf(image)
}

/** [image]'s pixels, as CoreGraphics draws them into an RGBA bitmap, as the engine reads its snapshots. */
fun pixelsOf(image: UIImage): Pixels? {
    val cg = image.CGImage ?: return null
    val width = CGImageGetWidth(cg).toInt()
    val height = CGImageGetHeight(cg).toInt()
    if (width <= 0 || height <= 0) return null
    val rgba = ByteArray(width * height * 4)
    val drawn = rgba.usePinned { pinned ->
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGColorSpaceRelease(space)
        if (context == null) return@usePinned false
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cg)
        CGContextRelease(context)
        true
    }
    return if (drawn) Pixels(width, height, rgba) else null
}

/** A cover as the runtime keeps it: [stored], its JPEG; [pixels], decoded and drawn over the view, [width] × [height]. */
class Cover(val stored: ByteArray, val pixels: Pixels, val encodeMillis: Double, val decodeMillis: Double)

/**
 * [image], a snapshot, as the runtime keeps and draws it: stored as a JPEG at quality 92, decoded, and drawn over the
 * view at [width] × [height] px, scaled, filtered, if it was taken smaller.
 */
fun coverOf(image: ImageBitmap, width: Int, height: Int): Cover {
    val source = Image.makeFromBitmap(image.asSkiaBitmap())
    var mark = TimeSource.Monotonic.markNow()
    val data = source.encodeToData(EncodedImageFormat.JPEG, STORED_JPEG_QUALITY) ?: error("no JPEG")
    val stored = data.bytes
    val encodeMillis = mark.elapsedNow().inWholeMicroseconds / 1000.0
    mark = TimeSource.Monotonic.markNow()
    val decoded = Image.makeFromEncoded(stored)
    val pixels = pixelsOf(decoded, width, height)
    val decodeMillis = mark.elapsedNow().inWholeMicroseconds / 1000.0
    return Cover(stored, pixels, encodeMillis, decodeMillis)
}

/** [image] as it is, if it is [width] × [height], or drawn at that size, filtered: as RGBA pixels. */
fun pixelsOf(image: Image, width: Int, height: Int): Pixels {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    if (image.width == width && image.height == height) {
        image.readPixels(bitmap, 0, 0)
    } else {
        val surface = Surface.makeRaster(info)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
            null,
            true,
        )
        surface.makeImageSnapshot().readPixels(bitmap, 0, 0)
    }
    return Pixels(width, height, bitmap.readPixels(info, width * 4, 0, 0) ?: error("no pixels"))
}

/** [image]'s own pixels as RGBA, at its size. */
fun pixelsOf(image: ImageBitmap): Pixels = pixelsOf(Image.makeFromBitmap(image.asSkiaBitmap()), image.width, image.height)

/** The view's size on screen, in pixels: its bounds at the screen's scale. */
fun pixelSize(view: UIView): Pair<Int, Int> {
    val scale = view.window?.screen?.scale ?: view.contentScaleFactor
    return view.bounds.useContents { (size.width * scale + 0.5).toInt() to (size.height * scale + 0.5).toInt() }
}
