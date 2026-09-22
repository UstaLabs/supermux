package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily

/**
 * What a cached text layout was measured against. Two runs share a layout only when every one of
 * these matches — the text itself, the font, the pixel size (so a density or font-size change never
 * reuses geometry) and the style bits that change glyph shapes.
 *
 * Colour is NOT part of the key: the painter draws a cached layout with an explicit colour, so one
 * layout serves every colour a run can take and the cache does not blow up with one entry per
 * theme colour. The colour state that DOES change geometry — none today, but a future theme that
 * switches fonts per colour role would — travels in [CacheSignature] instead, which clears the
 * whole cache when it changes.
 */
@Immutable
data class TextRunKey(
    val text: String,
    val fontSizePx: Float,
    val fontFamily: FontFamily,
    val bold: Boolean,
    val italic: Boolean,
)

/**
 * The cache-wide invalidation token: font family, font pixel size, cell size and the theme identity
 * the geometry was measured under. When it changes, every cached layout is stale by construction
 * and the cache is cleared rather than refilled key by key.
 */
@Immutable
data class CacheSignature(
    val fontFamily: FontFamily,
    val fontSizePx: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val themeKey: Int,
)

/**
 * A bounded LRU of measured text layouts.
 *
 * A terminal measures the same handful of strings forever (one ASCII run per style, one entry per
 * distinct grapheme), which is exactly what a cache is for — but `cat`ting a binary, or a program
 * drawing a progress bar with a new unique line every frame, would fill an unbounded map until the
 * app dies. Hence TWO hard bounds: [maxEntries] layouts and [maxMeasuredChars] characters of
 * measured text in total. Both evict least-recently-used first; a single entry larger than
 * [maxMeasuredChars] is never stored at all.
 *
 * Generic in the value so the bounds can be tested without a font stack; the painter uses
 * [TextLayoutCache].
 *
 * Not thread-safe: it belongs to one surface and is touched only from its draw pass.
 */
class TextRunCache<V : Any>(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxMeasuredChars: Int = DEFAULT_MAX_CHARS,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxMeasuredChars > 0) { "maxMeasuredChars must be positive" }
    }

    // A LinkedHashMap in INSERTION order plus "remove then re-insert on use" is the LRU: access
    // order is a JVM-only constructor flag, and this class is multiplatform.
    private val entries = LinkedHashMap<TextRunKey, V>()
    private var signature: CacheSignature? = null

    /** Characters of measured text currently held (the second bound). */
    var measuredChars: Int = 0
        private set

    val size: Int get() = entries.size

    /**
     * Drop everything when the font, the scale or the theme geometry changed. Cheap to call on
     * every draw: it compares one value and does nothing while the signature is stable.
     */
    fun retune(current: CacheSignature) {
        if (signature == current) return
        signature = current
        clear()
    }

    /** The cached layout for [key], or null; a hit becomes the most recently used entry. */
    fun get(key: TextRunKey): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    /** Store [value], evicting least-recently-used entries until both bounds hold again. */
    fun put(key: TextRunKey, value: V) {
        val cost = key.text.length
        if (cost > maxMeasuredChars) return
        entries.remove(key)?.let { measuredChars -= key.text.length }
        entries[key] = value
        measuredChars += cost
        while (entries.size > maxEntries || measuredChars > maxMeasuredChars) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
            measuredChars -= oldest.text.length
        }
    }

    /** [get], or [measure] the value and store it. */
    inline fun getOrPut(key: TextRunKey, measure: (TextRunKey) -> V): V =
        get(key) ?: measure(key).also { put(key, it) }

    fun clear() {
        entries.clear()
        measuredChars = 0
    }

    companion object {
        /**
         * Enough for a screenful of distinct styled runs several times over (a 200-column screen
         * has at most 200 runs per row) without ever growing with the scrollback.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 1024

        /** ~64 KiB of UTF-16 text measured at once. */
        const val DEFAULT_MAX_CHARS: Int = 32 * 1024
    }
}

/** The painter's cache: measured Compose text layouts. */
typealias TextLayoutCache = TextRunCache<TextLayoutResult>
