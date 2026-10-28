package dev.supermux.webview.runtime

import androidx.compose.ui.unit.DpSize

/**
 * What a page leaves behind when its engine goes, so that a NEW view of the same content picks up where it left off.
 * Lazy lists dispose items that scroll away; the item that comes back is a new view.
 */
class PageMemory(
    val snapshotKey: SnapshotKey?,
    val contentSize: DpSize?,
    val engineState: ByteArray?,
    /** The page's saved state as JSON text: a tree of a 256 KiB state would cost 7–10 MB. */
    val pageState: String?,
    val pageSavesState: Boolean,
) {
    internal val bytes: Long get() = (engineState?.size ?: 0).toLong() + 2L * (pageState?.length ?: 0)
}

/** Page memories by content key, least recently remembered forgotten first, bounded by count and bytes. Main thread only. */
class PageMemoryStore(private val maxEntries: Int, private val maxBytes: Long = 16L shl 20) {
    private val entries = LinkedHashMap<String, PageMemory>()
    private var bytes = 0L

    fun remember(contentKey: String, memory: PageMemory) {
        entries.remove(contentKey)?.let { bytes -= it.bytes }
        entries[contentKey] = memory
        bytes += memory.bytes
        while (entries.size > maxEntries || (bytes > maxBytes && entries.size > 1)) {
            val oldest = entries.keys.first()
            entries.remove(oldest)?.let { bytes -= it.bytes }
        }
    }

    fun recall(contentKey: String): PageMemory? = entries[contentKey]
}
