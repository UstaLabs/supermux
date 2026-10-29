package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig

/** A snapshot's identity: the content, its pixel size, the theme it was taken in and the density it was drawn at. */
data class SnapshotKey(val contentKey: String, val width: Int, val height: Int, val dark: Boolean, val density: Float)

/**
 * Least-recently-used snapshots, bounded by their pixel bytes: never more than [maxBytes], and less while memory is
 * short ([WebViewRuntime.onMemoryPressure]). Main thread only.
 */
class SnapshotCache(val maxBytes: Long) {
    /** An image and what it cost when it came in, so it leaves with exactly that, whatever it reports later. */
    private class Entry(val image: ImageBitmap, val bytes: Long)

    private val entries = LinkedHashMap<SnapshotKey, Entry>()

    var sizeBytes: Long = 0
        private set

    val count: Int get() = entries.size

    /**
     * A lower budget while memory is short, or null. Setting it evicts down to it at once, and the cache keeps within
     * it, as within [maxBytes], until it is null again. It never raises the budget above [maxBytes].
     */
    internal var limitBytes: Long? = null
        set(value) {
            field = value
            trimTo(budget)
        }

    /** What the cache may hold now. */
    private val budget: Long get() = minOf(maxBytes, limitBytes ?: maxBytes)

    operator fun get(key: SnapshotKey): ImageBitmap? {
        val entry = entries.remove(key) ?: return null
        entries[key] = entry
        return entry.image
    }

    /**
     * Keeps [image] under [key] in place of what was there. An image bigger than the whole cache, or than what memory
     * pressure leaves of it, isn't kept.
     */
    fun put(key: SnapshotKey, image: ImageBitmap) {
        remove(key)
        val bytes = pixelBytes(image)
        if (bytes > budget) return
        entries[key] = Entry(image, bytes)
        sizeBytes += bytes
        trimTo(budget)
    }

    fun remove(key: SnapshotKey) {
        entries.remove(key)?.let { sizeBytes -= it.bytes }
    }

    /** Evicts least-recently-used snapshots until at most [limit] bytes remain. */
    fun trimTo(limit: Long) {
        val iterator = entries.values.iterator()
        while (sizeBytes > limit && iterator.hasNext()) {
            sizeBytes -= iterator.next().bytes
            iterator.remove()
        }
    }
}

/** The bytes [image]'s pixels take, by their format. */
internal fun pixelBytes(image: ImageBitmap): Long = image.width.toLong() * image.height * bytesPerPixel(image.config)

private fun bytesPerPixel(config: ImageBitmapConfig): Int = when (config) {
    ImageBitmapConfig.Alpha8 -> 1
    ImageBitmapConfig.Rgb565 -> 2
    ImageBitmapConfig.F16 -> 8
    else -> 4 // Argb8888, Gpu, and any format added later
}
