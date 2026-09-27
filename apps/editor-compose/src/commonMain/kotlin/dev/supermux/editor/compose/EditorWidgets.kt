package dev.supermux.editor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.math.round

/**
 * Composable content for widget keys, installed by the host or compiled-in plugins: the one
 * sanctioned exception to "plugins describe everything as data" (spec §4.4). A plugin puts a
 * `Decoration.BlockWidget(WidgetKey(type, id))` in the state; the surface looks [type] up here and
 * composes its content between the lines, `id` telling the content which instance it shows.
 *
 * - A block widget whose type is NOT registered is empty space of exactly its
 *   `estimatedHeightLines` lines (the diff plugin's alignment gaps need no composable).
 * - Panels (`Panel(id)`) take their content from the type `panel:<id>`.
 *
 * Registering or unregistering relayouts every editor using this registry.
 */
@Stable
class WidgetRegistry {
    private val contents = mutableStateMapOf<String, @Composable WidgetScope.(WidgetKey) -> Unit>()

    /** Bumped by every change: the surface re-measures its blocks. */
    internal var version: Int by mutableIntStateOf(0)
        private set

    /** Show [content] for every widget of [type] (replacing what was registered for it). */
    fun register(type: String, content: @Composable WidgetScope.(WidgetKey) -> Unit) {
        contents[type] = content
        version++
    }

    fun unregister(type: String) {
        if (contents.remove(type) != null) version++
    }

    operator fun contains(type: String): Boolean = contents.containsKey(type)

    internal fun content(type: String): (@Composable WidgetScope.(WidgetKey) -> Unit)? = contents[type]
}

/** What a widget's content can use: the editor it sits in. */
@Stable
interface WidgetScope {
    /** The editor showing the widget (dispatch through it: an unfold, a resolved thread). */
    val view: EditorView

    /** The editor's theme, zoom included, so the content can match it. */
    val theme: EditorTheme

    /** One editor line's height (content sized in lines stays aligned with the text). */
    val lineHeight: Dp

    /** Hand the keyboard focus back to the editor (Escape in a comment box). */
    fun focusEditor()
}

/** A block widget of the current state: [key], on [line] ([above] it, else below), estimated at [estimateLines]. */
internal class BlockEntry(val key: WidgetKey, val pos: Int, val above: Boolean, val estimateLines: Float, val order: Int) {
    var line = 0
}

/** A block widget in a frame: its surface-pixel [rect], and whether it has composed content there. */
internal class PlacedWidget(val key: WidgetKey, val rect: Rect, val composed: Boolean, val inline: Boolean = false)

/** A drawn placeholder chip in a frame: a fold's "⋯", an inline widget nobody registered content for. */
internal class DrawnChip(val key: WidgetKey, val from: Int, val to: Int, val rect: Rect)

/**
 * The block widgets of the state and their heights in the [HeightMap]: every `BlockWidget`
 * decoration's line gets the sum of its widgets' heights above and below it (measured once their
 * content has been, else `estimatedHeightLines` lines). Kept in step on every transaction (so
 * scroll-into-view sees real heights) and after every measurement.
 */
internal class BlockWidgets {
    /** Every block, by line (a line's widgets above first), then in precedence order. */
    var entries: List<BlockEntry> = emptyList()
        private set
    private var byKey: Map<WidgetKey, BlockEntry> = emptyMap()

    /** Measured heights of registered widgets' content, in pixels. */
    private val measured = HashMap<WidgetKey, Float>()
    private val measuredThisFrame = HashSet<WidgetKey>()

    /** The lines whose blocks are in the height map. */
    private var applied: Set<Int> = emptySet()

    private var syncedDecos: List<RangeSet<Decoration>>? = null
    private var syncedDoc: Rope? = null
    private var syncedHeights: HeightMap? = null
    private var syncedLineHeight = 0f
    private var syncedRegistry = -1
    private var dirty = true

    // Blocks extracted per decoration set, reused while the set is the same instance.
    private var perSet: List<Pair<RangeSet<Decoration>, List<Pair<Int, Decoration.BlockWidget>>>> = emptyList()

    fun entry(key: WidgetKey): BlockEntry? = byKey[key]

    /** The height [e] takes: its content's measured height, else its estimate (exact for a type nobody registered). */
    fun heightOf(e: BlockEntry, lineHeight: Float, registry: WidgetRegistry?): Float {
        if (registry != null && e.key.type in registry) measured[e.key]?.let { return it }
        return round(e.estimateLines * lineHeight).coerceAtLeast(0f)
    }

    /** The blocks on lines in [range] (entries are sorted by line). */
    fun inLines(range: IntRange): List<BlockEntry> {
        if (entries.isEmpty() || range.isEmpty()) return emptyList()
        var lo = 0
        var hi = entries.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (entries[mid].line < range.first) lo = mid + 1 else hi = mid }
        val out = ArrayList<BlockEntry>()
        for (i in lo until entries.size) { val e = entries[i]; if (e.line > range.last) break; out += e }
        return out
    }

    fun beginFrame() = measuredThisFrame.clear()

    /** Record [key]'s measured content height; true when it changed. */
    fun setMeasured(key: WidgetKey, height: Float): Boolean {
        measuredThisFrame += key
        if (measured[key] == height) return false
        measured[key] = height
        dirty = true
        return true
    }

    fun measuredThisFrame(key: WidgetKey) = key in measuredThisFrame

    /** A document change: the lines whose blocks are in the height map moved with their text. */
    fun onChanges(tr: dev.supermux.editor.core.Transaction) {
        if (!tr.docChanged) return
        val before = tr.startState.doc
        val after = tr.state.doc
        applied = applied.mapNotNullTo(HashSet()) { l ->
            if (l >= before.lineCount) null else after.lineIndexAt(tr.changes.mapPos(before.lineStart(l), 1))
        }
        dirty = true
    }

    /** The height map was rebuilt (a new font, another document): every block goes in again. */
    fun invalidate() {
        applied = emptySet()
        dirty = true
    }

    /**
     * Bring the height map in step with [state]'s blocks. Cheap when nothing changed (the
     * decorations, the document, the height map and the registry are the same instances).
     * Returns true when a height changed.
     */
    fun sync(state: EditorState, heights: HeightMap, lineHeight: Float, registry: WidgetRegistry?, hidden: (Int) -> Boolean = { false }): Boolean {
        val decos = state.facet(decorationsFacet)
        val reg = registry?.version ?: -1
        if (!dirty && decos === syncedDecos && state.doc === syncedDoc && heights === syncedHeights && lineHeight == syncedLineHeight && reg == syncedRegistry) return false
        if (heights.lineCount != state.doc.lineCount) return false
        if (heights !== syncedHeights) applied = emptySet()
        syncedDecos = decos
        syncedDoc = state.doc
        syncedHeights = heights
        syncedLineHeight = lineHeight
        syncedRegistry = reg
        dirty = false
        extract(state, decos)
        val desired = HashMap<Int, FloatArray>()
        // A widget on a folded line is hidden with it.
        for (e in entries) if (!hidden(e.line)) desired.getOrPut(e.line) { FloatArray(2) }[if (e.above) 0 else 1] += heightOf(e, lineHeight, registry)
        var changed = false
        for (l in applied) if (l < heights.lineCount && l !in desired && (heights.blockAbove(l) != 0f || heights.blockBelow(l) != 0f)) {
            heights.setBlockHeight(l, 0f, 0f)
            changed = true
        }
        for ((l, v) in desired) if (heights.blockAbove(l) != v[0] || heights.blockBelow(l) != v[1]) {
            heights.setBlockHeight(l, v[0], v[1])
            changed = true
        }
        applied = desired.keys
        return changed
    }

    private fun extract(state: EditorState, decos: List<RangeSet<Decoration>>) {
        val old = perSet
        perSet = decos.mapIndexed { i, set ->
            if (i < old.size && old[i].first === set) old[i]
            else set to set.mapNotNull { r -> (r.value as? Decoration.BlockWidget)?.let { r.from to it } }
        }
        val doc = state.doc
        val out = ArrayList<BlockEntry>()
        var order = 0
        for ((_, blocks) in perSet) for ((pos, b) in blocks) {
            out += BlockEntry(b.key, pos.coerceIn(0, doc.length), b.above, b.estimatedHeightLines, order++).also { it.line = doc.lineIndexAt(it.pos) }
        }
        // A key shown twice (two plugins, one widget) is shown once, at its highest precedence.
        val seen = HashSet<WidgetKey>()
        val unique = out.filter { seen.add(it.key) }
        entries = unique.sortedWith(compareBy({ it.line }, { if (it.above) 0 else 1 }, { it.order }))
        byKey = entries.associateBy { it.key }
        if (measured.size > 2 * entries.size + 16) measured.keys.retainAll(byKey.keys)
    }
}

/** A widget's key for the saved-state holder (a String: a platform bundle can keep it). */
internal fun WidgetKey.saveKey(): String = "$type\u0000$id"
