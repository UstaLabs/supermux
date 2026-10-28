package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap

/** A snapshot's identity: the content, its pixel size and the theme it was taken in. */
data class SnapshotKey(val contentKey: String, val width: Int, val height: Int, val dark: Boolean)

/** Least-recently-used snapshots, bounded by their pixel bytes (4 per pixel). Main thread only. */
class SnapshotCache(maxBytes: Long) {
    var maxBytes: Long = maxBytes
        private set

    private val entries = LinkedHashMap<SnapshotKey, ImageBitmap>()

    var sizeBytes: Long = 0
        private set

    val count: Int get() = entries.size

    operator fun get(key: SnapshotKey): ImageBitmap? {
        val image = entries.remove(key) ?: return null
        entries[key] = image
        return image
    }

    fun put(key: SnapshotKey, image: ImageBitmap) {
        remove(key)
        val bytes = bytesOf(image)
        if (bytes > maxBytes) return
        entries[key] = image
        sizeBytes += bytes
        trimTo(maxBytes)
    }

    fun remove(key: SnapshotKey) {
        entries.remove(key)?.let { sizeBytes -= bytesOf(it) }
    }

    /** Evicts least-recently-used snapshots until at most [limit] bytes remain. */
    fun trimTo(limit: Long) {
        val iterator = entries.entries.iterator()
        while (sizeBytes > limit && iterator.hasNext()) {
            sizeBytes -= bytesOf(iterator.next().value)
            iterator.remove()
        }
    }

    private fun bytesOf(image: ImageBitmap): Long = image.width.toLong() * image.height * 4
}
