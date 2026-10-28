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

/**
 * Page memories by content key, the least recently used forgotten first. Main thread only.
 *
 * Bounded by count and by bytes, and never over [maxBytes]: an entry costs its states, its key and [ENTRY_OVERHEAD].
 */
class PageMemoryStore(private val maxEntries: Int, private val maxBytes: Long = 16L shl 20) {
    /** A memory and what it cost when it came in, so replacing or evicting it gives back exactly that. */
    private class Entry(val memory: PageMemory, val bytes: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var bytes = 0L

    /**
     * Remembers [memory] for [contentKey] in place of what was there. A memory that costs more than the whole budget
     * is kept without its engine state; if it still costs too much, it isn't kept, and the old one is forgotten too.
     */
    fun remember(contentKey: String, memory: PageMemory) {
        forget(contentKey)
        var kept = memory
        var cost = costOf(contentKey, kept)
        if (cost > maxBytes && kept.engineState != null) {
            kept = PageMemory(kept.snapshotKey, kept.contentSize, null, kept.pageState, kept.pageSavesState)
            cost = costOf(contentKey, kept)
        }
        if (cost > maxBytes) return
        entries[contentKey] = Entry(kept, cost)
        bytes += cost
        while (entries.isNotEmpty() && (entries.size > maxEntries || bytes > maxBytes)) forget(entries.keys.first())
    }

    /** What was remembered for [contentKey]. Recalling a page makes it the most recently used: it's in use again. */
    fun recall(contentKey: String): PageMemory? {
        val entry = entries.remove(contentKey) ?: return null
        entries[contentKey] = entry
        return entry.memory
    }

    private fun forget(contentKey: String) {
        entries.remove(contentKey)?.let { bytes -= it.bytes }
    }

    private fun costOf(contentKey: String, memory: PageMemory): Long =
        memory.bytes + 2L * contentKey.length + ENTRY_OVERHEAD

    internal companion object {
        /** Roughly what an entry's objects cost besides its states and key: the memory, its snapshot key, the map node. */
        const val ENTRY_OVERHEAD = 128L
    }
}
