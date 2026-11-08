package dev.supermux.webview.engine.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
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
import platform.UIKit.UIImage

/**
 * This image's pixels, top row first, as an [ImageBitmap] in full colour: 32-bit RGBA, premultiplied, so a page with a
 * transparent background keeps its alpha. Null if it has none. A copy of every pixel: the engine runs it off the main
 * thread.
 */
internal fun UIImage.toImageBitmap(): ImageBitmap? {
    val image = CGImage ?: return null
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    if (width <= 0 || height <= 0) return null
    val rowBytes = width * 4
    val pixels = ByteArray(rowBytes * height)
    val drawn = pixels.usePinned { pinned ->
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            pinned.addressOf(0),
            width.toULong(),
            height.toULong(),
            8u,
            rowBytes.toULong(),
            space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGColorSpaceRelease(space)
        if (context == null) return@usePinned false
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
        CGContextRelease(context)
        true
    }
    if (!drawn) return null
    val bitmap = Bitmap()
    if (!bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) return null
    if (!bitmap.installPixels(pixels)) return null
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
