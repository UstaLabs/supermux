package dev.supermux.webview.engine.ios

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.UIKit.UIImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SnapshotImagesTest {
    /** A [width]×[height] image whose top half is red and bottom half blue, made the way WebKit's snapshots are: by CoreGraphics. */
    private fun twoTone(width: Int, height: Int): UIImage {
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            if (y < height / 2) pixels[i] = 0xff.toByte() else pixels[i + 2] = 0xff.toByte()
            pixels[i + 3] = 0xff.toByte()
        }
        val image = pixels.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
            )
            CGBitmapContextCreateImage(context)
        }
        return UIImage.imageWithCGImage(image!!)
    }

    @Test fun aSnapshotKeepsItsPixelsTopRowFirst() {
        val bitmap = assertNotNull(twoTone(8, 6).toImageBitmap())
        assertEquals(8, bitmap.width)
        assertEquals(6, bitmap.height)
        val pixels = bitmap.toPixelMap()
        assertEquals(Color.Red, pixels[4, 0])
        assertEquals(Color.Blue, pixels[4, 5])
    }

    @Test fun snapshotsAreTakenAtTheScreensOwnScaleUpToThreePixelsAPoint() {
        assertEquals(390.0, snapshotWidthPoints(390.0, 1.0))
        assertEquals(390.0, snapshotWidthPoints(390.0, 2.0))
        assertEquals(390.0, snapshotWidthPoints(390.0, 3.0))
        assertEquals(292.5, snapshotWidthPoints(390.0, 4.0), "a screen denser than 3x gets 3 pixels a point")
    }

    @Test fun aTransparentPagesSnapshotKeepsItsAlphaInFullColour() {
        val bitmap = assertNotNull(halfClear(8, 6).toImageBitmap())
        assertEquals(ImageBitmapConfig.Argb8888, bitmap.config, "32-bit colour")
        val pixels = bitmap.toPixelMap()
        assertEquals(0f, pixels[4, 0].alpha, "the clear half stays clear")
        assertEquals(Color.Blue, pixels[4, 5])
    }

    /** A [width]×[height] image whose top half is clear and bottom half opaque blue: a page with no background. */
    private fun halfClear(width: Int, height: Int): UIImage {
        val pixels = ByteArray(width * height * 4)
        for (y in height / 2 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            pixels[i + 2] = 0xff.toByte()
            pixels[i + 3] = 0xff.toByte()
        }
        val image = pixels.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
            )
            CGBitmapContextCreateImage(context)
        }
        return UIImage.imageWithCGImage(image!!)
    }
}
