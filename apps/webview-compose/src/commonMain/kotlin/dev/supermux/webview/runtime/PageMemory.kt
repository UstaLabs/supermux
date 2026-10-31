package dev.supermux.webview.runtime

import androidx.compose.ui.unit.DpSize
import dev.supermux.webview.WebProfile

/**
 * A page as page memory and snapshots know it: [contentKey] (the content's [dev.supermux.webview.WebContent.key]) shown
 * in [profile], the resolved profile of the engine that showed it. The same content in another profile is another page:
 * a profile is a user's cookies and storage, so nothing of one profile's page reaches another's.
 */
public data class PageKey(val profile: WebProfile, val contentKey: String)

/**
 * State a page saved (`hostBridge.onSaveState`): its JSON text, and the origin of the document that saved it. It goes
 * back only to a document of that origin. One value, so the two can't come apart.
 */
public data class SavedPageState(val json: String, val origin: String) {
    /** The origin and the size, not the state: it is the page's data, up to 256 KiB of it. */
    override fun toString(): String = "SavedPageState(origin=$origin, ${json.length} chars)"
}

/**
 * The last size a page reported, as the loop detector keeps it: the page's height and its viewport's height, in CSS
 * px. The viewport's is null when unknown.
 */
public data class SizeReport(val height: Float, val viewportHeight: Float?)

/**
 * What a page leaves behind when its engine goes, so that a NEW view of the same content picks up where it left off.
 * Lazy lists dispose items that scroll away; the item that comes back is a new view.
 */
public class PageMemory(
    public val snapshotKey: SnapshotKey?,
    public val contentSize: DpSize?,
    public val engineState: ByteArray?,
    /** The page's saved state, kept as JSON text: a tree of a 256 KiB state would cost 7–10 MB. */
    public val pageState: SavedPageState?,
    public val pageSavesState: Boolean,
    /**
     * The loop detector's last report and verdict. With them a new view of a page caught growing with its viewport
     * stays caught, instead of letting it take another step each time, and a page that has really grown since still
     * gets its height.
     */
    public val lastSizeReport: SizeReport?,
    public val growsWithViewport: Boolean,
) {
    internal val bytes: Long
        get() = (engineState?.size ?: 0).toLong() + (pageState?.let { 2L * (it.json.length + it.origin.length) } ?: 0L)
}

/**
 * Page memories by page ([PageKey]: profile and content), the least recently used forgotten first. Main thread only.
 *
 * Bounded by count and by bytes, and never over [maxBytes]: an entry costs its states, its content key and
 * [ENTRY_OVERHEAD].
 */
public class PageMemoryStore(private val maxEntries: Int, public val maxBytes: Long = 16L shl 20) {
    /** A memory and what it cost when it came in, so replacing or evicting it gives back exactly that. */
    private class Entry(val memory: PageMemory, val bytes: Long)

    private val entries = LinkedHashMap<PageKey, Entry>()
    private var bytes = 0L

    /** How many pages are remembered. */
    public val count: Int get() = entries.size

    /** What the remembered pages cost, as counted when each came in: never more than [maxBytes]. */
    public val sizeBytes: Long get() = bytes

    /**
     * Remembers [memory] for [page] in place of what was there. A memory that costs more than the whole budget is kept
     * without its engine state; if it still costs too much, it isn't kept, and the old one is forgotten too.
     */
    public fun remember(page: PageKey, memory: PageMemory) {
        drop(page)
        var kept = memory
        var cost = costOf(page, kept)
        if (cost > maxBytes && kept.engineState != null) {
            kept = PageMemory(
                kept.snapshotKey, kept.contentSize, null, kept.pageState, kept.pageSavesState, kept.lastSizeReport,
                kept.growsWithViewport,
            )
            cost = costOf(page, kept)
        }
        if (cost > maxBytes) return
        entries[page] = Entry(kept, cost)
        bytes += cost
        while (entries.isNotEmpty() && (entries.size > maxEntries || bytes > maxBytes)) drop(entries.keys.first())
    }

    /** What was remembered for [page]. Recalling a page makes it the most recently used: it's in use again. */
    public fun recall(page: PageKey): PageMemory? {
        val entry = entries.remove(page) ?: return null
        entries[page] = entry
        return entry.memory
    }

    /** Forgets the content of [contentKey], in every profile. */
    public fun forget(contentKey: String) {
        entries.keys.filter { it.contentKey == contentKey }.forEach(::drop)
    }

    /** Forgets every page. */
    public fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun drop(page: PageKey) {
        entries.remove(page)?.let { bytes -= it.bytes }
    }

    private fun costOf(page: PageKey, memory: PageMemory): Long =
        memory.bytes + 2L * page.contentKey.length + ENTRY_OVERHEAD

    internal companion object {
        /**
         * Roughly what an entry's objects cost besides its states and content key: the memory, its keys (the profile is
         * shared), the map node.
         */
        const val ENTRY_OVERHEAD = 128L
    }
}
