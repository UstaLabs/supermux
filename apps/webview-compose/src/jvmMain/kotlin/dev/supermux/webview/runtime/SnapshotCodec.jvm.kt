package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

// Skia's own encoders and decoders, as on every skiko platform (the same code for desktop, iOS and the web).

internal actual fun encodeImage(image: ImageBitmap, format: EncodedFormat): ByteArray? {
    val skia = Image.makeFromBitmap(image.asSkiaBitmap())
    try {
        val data = when (format) {
            EncodedFormat.Jpeg -> skia.encodeToData(EncodedImageFormat.JPEG, SNAPSHOT_JPEG_QUALITY)
            EncodedFormat.Png -> skia.encodeToData(EncodedImageFormat.PNG)
        } ?: return null
        try {
            return data.bytes
        } finally {
            data.close()
        }
    } finally {
        skia.close()
    }
}

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? {
    val skia = Image.makeFromEncoded(bytes)
    try {
        return skia.toComposeImageBitmap()
    } finally {
        skia.close()
    }
}

/** Skia shares an immutable bitmap's pixels with the image Compose's canvas makes of it at each draw: no copy. */
internal actual fun ImageBitmap.markedImmutable(): ImageBitmap = also { it.asSkiaBitmap().setImmutable() }
