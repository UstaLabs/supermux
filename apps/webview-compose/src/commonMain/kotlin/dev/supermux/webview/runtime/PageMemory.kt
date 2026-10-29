package dev.supermux.webview.runtime

import androidx.compose.ui.unit.DpSize

/**
 * State a page saved (`hostBridge.onSaveState`): its JSON text, and the origin of the document that saved it. It goes
 * back only to a document of that origin. One value, so the two can't come apart.
 */
data class SavedPageState(val json: String, val origin: String) {
    /** The origin and the size, not the state: it is the page's data, up to 256 KiB of it. */
    override fun toString(): String = "SavedPageState(origin=$origin, ${json.length} chars)"
}

/**
 * The last size a page reported, as the loop detector keeps it: the page's height and its viewport's height, in CSS
 * px. The viewport's is null when unknown.
 */
data class SizeReport(val height: Float, val viewportHeight: Float?)

/**
 * What a page leaves behind when its engine goes, so that a NEW view of the same content picks up where it left off.
 * Lazy lists dispose items that scroll away; the item that comes back is a new view.
 */
class PageMemory(
    val snapshotKey: SnapshotKey?,
    val contentSize: DpSize?,
    val engineState: ByteArray?,
    /** The page's saved state, kept as JSON text: a tree of a 256 KiB state would cost 7–10 MB. */
    val pageState: SavedPageState?,
    val pageSavesState: Boolean,
    /**
     * The loop detector's last report and verdict. With them a new view of a page caught growing with its viewport
     * stays caught, instead of letting it take another step each time, and a page that has really grown since still
     * gets its height.
     */
    val lastSizeReport: SizeReport?,
    val growsWithViewport: Boolean,
) {
    internal val bytes: Long
        get() = (engineState?.size ?: 0).toLong() + (pageState?.let { 2L * (it.json.length + it.origin.length) } ?: 0L)
}

/**
 * Page memories by content key, the least recently used forgotten first. Main thread only.
 *
 * Bounded by count and by bytes, and never over [maxBytes]: an entry costs its states, its key and [ENTRY_OVERHEAD].
 */
class PageMemoryStore(private val maxEntries: Int, val maxBytes: Long = 16L shl 20) {
    /** A memory and what it cost when it came in, so replacing or evicting it gives back exactly that. */
    private class Entry(val memory: PageMemory, val bytes: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var bytes = 0L

    /** How many pages are remembered. */
    val count: Int get() = entries.size

    /** What the remembered pages cost, as counted when each came in: never more than [maxBytes]. */
    val sizeBytes: Long get() = bytes

    /**
     * Remembers [memory] for [contentKey] in place of what was there. A memory that costs more than the whole budget
     * is kept without its engine state; if it still costs too much, it isn't kept, and the old one is forgotten too.
     */
    fun remember(contentKey: String, memory: PageMemory) {
        forget(contentKey)
        var kept = memory
        var cost = costOf(contentKey, kept)
        if (cost > maxBytes && kept.engineState != null) {
            kept = PageMemory(
                kept.snapshotKey, kept.contentSize, null, kept.pageState, kept.pageSavesState, kept.lastSizeReport,
                kept.growsWithViewport,
            )
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
