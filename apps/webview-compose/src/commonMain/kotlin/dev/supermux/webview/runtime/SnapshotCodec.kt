package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The JPEG quality snapshots are stored at: to the eye the page itself, in a tenth to a twentieth of its pixels' bytes. */
internal const val SNAPSHOT_JPEG_QUALITY: Int = 92

/** How the snapshot cache stores snapshots and gets them back. Neither way throws: what can't be coded is null. */
internal interface ImageCodec {
    /** [image] encoded, or null if it can't be read. [opaque] is false for a page with a transparent background. */
    suspend fun encode(image: ImageBitmap, opaque: Boolean = true): ByteArray?

    /** [bytes] decoded to an image to draw, or null if they aren't one. */
    suspend fun decode(bytes: ByteArray): ImageBitmap?

    /**
     * [image], a page's snapshot as its engine took it, as the runtime keeps it for a view [viewWidthDp] wide
     * ([keptSnapshot]), and that encoded ([encode]); null if it can't be encoded. On the caller's thread, unless the
     * codec moves its work off it, as [SnapshotCodec] does.
     */
    suspend fun keep(image: ImageBitmap, viewWidthDp: Float, opaque: Boolean): KeptSnapshot? {
        val kept = keptSnapshot(image, viewWidthDp)
        return encode(kept, opaque)?.let { KeptSnapshot(kept, it) }
    }
}

/** A page's snapshot as the runtime keeps it: [image], to draw, and [bytes], it encoded, to store. */
internal class KeptSnapshot(val image: ImageBitmap, val bytes: ByteArray)

/**
 * How snapshots are stored: encoded, and decoded back to an `Argb8888` image to draw. Both ways run on
 * `Dispatchers.Default`, never on the caller's thread, so the main thread never waits for them; so does scaling a
 * snapshot down to what the runtime keeps ([keep]).
 * - An opaque snapshot, which nearly every page's is, is a JPEG at [SNAPSHOT_JPEG_QUALITY].
 * - A page with a transparent background ([dev.supermux.webview.WebSettings.transparentBackground]) shows what's under
 *   the view through it, so its snapshot keeps its alpha: a PNG, lossless. JPEG has no alpha.
 *
 * Neither way throws: an image that can't be read (zero-size, or no pixels behind it) encodes to null, and bytes that
 * aren't an image decode to null.
 */
internal object SnapshotCodec : ImageCodec {
    override suspend fun encode(image: ImageBitmap, opaque: Boolean): ByteArray? {
        if (image.width <= 0 || image.height <= 0) return null
        return withContext(Dispatchers.Default) { encodeNow(image, opaque) }
    }

    override suspend fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return withContext(Dispatchers.Default) { decodeNow(bytes) }
    }

    /** [ImageCodec.keep], both steps off the main thread: redrawing a big snapshot smaller costs as much as encoding it. */
    override suspend fun keep(image: ImageBitmap, viewWidthDp: Float, opaque: Boolean): KeptSnapshot? {
        if (image.width <= 0 || image.height <= 0) return null
        return withContext(Dispatchers.Default) {
            val kept = keptSnapshot(image, viewWidthDp)
            encodeNow(kept, opaque)?.let { KeptSnapshot(kept, it) }
        }
    }

    /** [encode], blocking, on the caller's thread. */
    fun encodeNow(image: ImageBitmap, opaque: Boolean = true): ByteArray? {
        if (image.width <= 0 || image.height <= 0) return null
        return tryOrNull { encodeImage(image, if (opaque) EncodedFormat.Jpeg else EncodedFormat.Png) }
    }

    /** [decode], blocking, on the caller's thread. */
    fun decodeNow(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return tryOrNull { decodeImage(bytes) }
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
