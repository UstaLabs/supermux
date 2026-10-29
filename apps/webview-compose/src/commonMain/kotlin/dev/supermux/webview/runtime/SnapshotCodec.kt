package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The JPEG quality snapshots are stored at: to the eye the page itself, in a tenth to a twentieth of its pixels' bytes. */
internal const val SNAPSHOT_JPEG_QUALITY: Int = 92

/**
 * How snapshots are stored: encoded, and decoded back to an `Argb8888` image to draw. Both ways run on
 * `Dispatchers.Default`, never on the caller's thread, so the main thread never waits for them.
 * - An opaque snapshot, which nearly every page's is, is a JPEG at [SNAPSHOT_JPEG_QUALITY].
 * - A page with a transparent background ([dev.supermux.webview.WebSettings.transparentBackground]) shows what's under
 *   the view through it, so its snapshot keeps its alpha: a PNG, lossless. JPEG has no alpha.
 *
 * Neither way throws: an image that can't be read (zero-size, or no pixels behind it) encodes to null, and bytes that
 * aren't an image decode to null.
 */
internal object SnapshotCodec {
    suspend fun encode(image: ImageBitmap, opaque: Boolean = true): ByteArray? {
        if (image.width <= 0 || image.height <= 0) return null
        return withContext(Dispatchers.Default) {
            tryOrNull { encodeImage(image, if (opaque) EncodedFormat.Jpeg else EncodedFormat.Png) }
        }
    }

    suspend fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return withContext(Dispatchers.Default) { tryOrNull { decodeImage(bytes) } }
    }

    private inline fun <T> tryOrNull(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}

internal enum class EncodedFormat { Jpeg, Png }

/**
 * [image] encoded as [format] by the platform's own encoder (JPEG at [SNAPSHOT_JPEG_QUALITY]), or null if it can't
 * be. Blocking: [SnapshotCodec] calls it on `Dispatchers.Default`.
 * - Android: `Bitmap.compress`.
 * - Desktop, iOS and the web: Skia, `Image.encodeToData`. The web engine takes no snapshots, but the codec works there.
 */
internal expect fun encodeImage(image: ImageBitmap, format: EncodedFormat): ByteArray?

/** [bytes] decoded to an `Argb8888` image, or null if they aren't one. Blocking, like [encodeImage]. */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap?
