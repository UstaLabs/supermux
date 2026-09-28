package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Tooltip
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import dev.supermux.editor.core.tooltipsFacet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a tooltip goes: its top-left in surface pixels, and whether it ended up above its anchor. */
internal data class TooltipPlacement(val x: Float, val y: Float, val above: Boolean)

/**
 * The tooltip layer's geometry (pure: [TooltipPlacementTest]). [anchor] is the caret rect at the
 * tooltip's position and [caret] the main caret's (both surface pixels; null: not in view), [bounds]
 * what the tooltip may cover (the surface minus a soft keyboard over it), [gap] the space kept to the
 * text.
 */
internal object TooltipLayout {
    /** The rows a tooltip must not cover: the anchor's, and the main caret's when it is next to it. */
    private fun band(anchor: Rect, caret: Rect?): Rect {
        if (caret == null) return anchor
        val near = kotlin.math.abs(caret.top - anchor.top) <= anchor.height * 2.5f
        return if (near) Rect(anchor.left, minOf(anchor.top, caret.top), anchor.right, maxOf(anchor.bottom, caret.bottom)) else anchor
    }

    fun roomAbove(anchor: Rect, caret: Rect?, bounds: Rect, gap: Float) = (band(anchor, caret).top - gap - bounds.top).coerceAtLeast(0f)
    fun roomBelow(anchor: Rect, caret: Rect?, bounds: Rect, gap: Float) = (bounds.bottom - band(anchor, caret).bottom - gap).coerceAtLeast(0f)

    /** The tallest a tooltip may be: the room on its better side (it is measured with this). */
    fun maxHeight(anchor: Rect, caret: Rect?, bounds: Rect, gap: Float): Float =
        maxOf(roomAbove(anchor, caret, bounds, gap), roomBelow(anchor, caret, bounds, gap))

    /**
     * The asked-for side when the tooltip fits there, else the other side when it fits there, else
     * the side with more room; never over the anchor's row nor the main caret's line (a far caret
     * decides only when both sides fit); left-aligned with the anchor, clamped into [bounds].
     */
    fun place(anchor: Rect, caret: Rect?, width: Int, height: Int, bounds: Rect, above: Boolean, gap: Float): TooltipPlacement {
        val b = band(anchor, caret)
        val up = roomAbove(anchor, caret, bounds, gap)
        val down = roomBelow(anchor, caret, bounds, gap)
        fun yFor(isAbove: Boolean) = if (isAbove) b.top - gap - height else b.bottom + gap
        fun coversCaret(isAbove: Boolean): Boolean {
            val c = caret ?: return false
            val top = yFor(isAbove)
            return top < c.bottom && top + height > c.top
        }
        val fitsAsked = height <= (if (above) up else down)
        val fitsOther = height <= (if (above) down else up)
        val side = when {
            fitsAsked && fitsOther && coversCaret(above) && !coversCaret(!above) -> !above
            fitsAsked -> above
            fitsOther -> !above
            else -> up > down
        }
        val maxX = bounds.right - width
        val x = if (maxX <= bounds.left) bounds.left else anchor.left.coerceIn(bounds.left, maxX)
        return TooltipPlacement(x, yFor(side), side)
    }
}

/** A hover tooltip's answer: the range it is about, and its tooltip's content key. */
data class HoverResult(val from: Int, val to: Int, val key: WidgetKey, val above: Boolean = true)

/**
 * One hover source ([hoverTooltip]'s), for the surface's hover engine: asked [hoverTime] ms after the
 * mouse stops over text, or at once by [Hover.request] (the "show hover" command).
 */
class HoverSource internal constructor(
    val id: String,
    val hoverTime: Long,
    internal val query: suspend (state: EditorState, pos: Int, side: Int) -> HoverResult?,
)

/** Every [hoverTooltip]'s source. */
val hoverSourcesFacet: Facet<HoverSource, List<HoverSource>> = Facet.list("hoverSources")

/**
 * Hover tooltips (CM6's `hoverTooltip`): the effects their fields follow, and the "show hover"
 * command. Touch has no hover: [showHover] (a host's button, a menu item, a key) asks every source at
 * the main cursor now, and the LSP flows show their information on touch in the completion list's
 * docs and in signature help.
 */
object Hover {
    /** Show ([HoverResult]) or hide (null) the hover of source `first`. */
    val set: StateEffectType<Pair<String, HoverResult?>> = StateEffectType("hover.set")

    /** Ask every hover source about this position now (no delay): the surface's engine runs them. */
    val request: StateEffectType<Int> = StateEffectType("hover.request") { p, c -> c.mapPos(p, 1) }

    /** What hover source [id] shows in [state] (null: nothing, or no such source). */
    fun shown(state: EditorState, id: String): HoverResult? =
        state.facet(hoverFields).firstOrNull { it.name == "hover:$id" }?.let { state.fieldOrNull(it) }

    internal val hoverFields: Facet<StateField<HoverResult?>, List<StateField<HoverResult?>>> = Facet.list("hoverFields")

    /** The "show hover" command: every source asked at the main cursor (touch, keyboard). */
    val showHover: Command = Command { t ->
        if (t.state.facet(hoverSourcesFacet).isEmpty()) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(request.of(t.state.selection.main.head))))
        true
    }

    /** Hide every shown hover; false (the key goes on) when none is shown. */
    val closeHover: Command = Command { t ->
        val st = t.state
        val open = st.facet(hoverFields).filter { st.fieldOrNull(it) != null }
        if (open.isEmpty()) return@Command false
        t.dispatch(TransactionSpec(effects = open.map { set.of(it.name.removePrefix("hover:") to null) }))
        true
    }
}

/**
 * A hover tooltip (CM6's `hoverTooltip(source, {hoverTime, hideOnChange})`): with a mouse, [source]
 * is asked [hoverTime] ms (CM6: 300) after the pointer stops over text, with the position and the
 * side of it the pointer is on; its [HoverResult] is shown as a [Tooltip] (`hideOnScroll`) until the
 * pointer leaves both the result's range and the tooltip, a scroll, Escape, or (with [hideOnChange])
 * an edit. [source] runs on the UI thread's scope and may suspend (an LSP request); a newer hover
 * cancels it. Touch: see [Hover.showHover].
 */
fun hoverTooltip(
    id: String,
    hoverTime: Long = 300,
    hideOnChange: Boolean = true,
    source: suspend (state: EditorState, pos: Int, side: Int) -> HoverResult?,
): Extension {
    val field = StateField<HoverResult?>(
        "hover:$id",
        { null },
        { v, tr ->
            var r = v
            if (r != null && tr.docChanged) {
                r = if (hideOnChange) null else {
                    val a = tr.changes.mapPos(r.from, 1)
                    val b = tr.changes.mapPos(r.to, -1)
                    if (b < a) null else r.copy(from = a, to = b)
                }
            }
            for (e in tr.effects) {
                e.valueIf(Hover.set)?.let { (who, res) -> if (who == id) r = res }
                e.valueIf(Tooltip.dismissed)?.let { k -> if (r?.key == k) r = null }
            }
            r
        },
    )
    return extensionOf(
        field,
        Hover.hoverFields.of(field),
        hoverSourcesFacet.of(HoverSource(id, hoverTime, source)),
        tooltipsFacet.compute(FacetDep.field(field)) { st ->
            st.field(field)?.let { Tooltip(it.from.coerceIn(0, st.doc.length), it.key, above = it.above, hideOnScroll = true) }
        },
        Prec.high(keymapOf(KeyBinding("Escape", Hover.closeHover))),
    )
}

/**
 * The surface's hover engine: pointer moves (a mouse, no button) to hover sources, with their delays;
 * [Hover.request] effects (the "show hover" command) at once. Runs on the view's UI scope.
 */
internal class HoverEngine(private val c: EditorController) {
    private var job: Job? = null
    private var closeJob: Job? = null
    private var pending: Int = -1

    /** Grace before a hover the pointer left closes (time to reach the tooltip). */
    private val leaveGraceMillis = 250L

    private fun sources() = c.view.state.facet(hoverSourcesFacet)

    /** The position under [p] (surface pixels) and the side of it the pointer is on, or null off text. */
    private fun textAt(p: Offset): Pair<Int, Int>? {
        if (p.x < c.gutterWidth || p.y < 0f || p.y > c.viewportSize.height) return null
        val content = Offset(p.x - c.textLeft + c.scroll.x, p.y + c.scroll.y)
        val pos = c.geometry.offsetAt(content)
        val doc = c.view.state.doc
        val r = c.geometry.rectFor(pos)
        if (content.y < r.top || content.y > r.bottom) return null
        val line = doc.lineAt(pos)
        // Past the line's end, or before its start: over no character.
        if (pos == line.to && content.x > r.left + c.layouts.charWidthPx / 2) return null
        return pos to (if (content.x >= r.left) 1 else -1)
    }

    private fun shownOver(pos: Int?): Boolean {
        val st = c.view.state
        return sources().any { s -> Hover.shown(st, s.id)?.let { pos != null && pos in it.from..it.to } == true }
    }

    private fun anyShown(): Boolean = sources().any { Hover.shown(c.view.state, it.id) != null }

    fun move(p: Offset) {
        if (sources().isEmpty()) return
        if (c.tooltipAt(p)) { closeJob?.cancel(); closeJob = null; return }
        val at = textAt(p)
        if (at != null && shownOver(at.first)) { closeJob?.cancel(); closeJob = null; return }
        if (anyShown() && closeJob == null) scheduleClose()
        if (at == null) { job?.cancel(); job = null; pending = -1; return }
        if (at.first == pending && job?.isActive == true) return
        start(at.first, at.second, delayed = true)
    }

    /** A press (a tap, a click) off every tooltip: a shown hover closes. */
    fun pressedOutside() {
        job?.cancel(); job = null; pending = -1
        closeJob?.cancel(); closeJob = null
        if (anyShown()) Hover.closeHover.run(c.view)
    }

    fun exit() {
        job?.cancel(); job = null; pending = -1
        if (anyShown() && closeJob == null) scheduleClose()
    }

    private fun scheduleClose() {
        val scope = c.view.scope ?: return
        closeJob = scope.launch {
            delay(leaveGraceMillis)
            closeJob = null
            Hover.closeHover.run(c.view)
        }
    }

    /** Hover sources at [pos] now (the "show hover" command). */
    fun follow(tr: Transaction) {
        val at = tr.effects.lastOrNull { it.isOf(Hover.request) }?.valueIf(Hover.request) ?: return
        start(at.coerceIn(0, tr.state.doc.length), 1, delayed = false)
    }

    private fun start(pos: Int, side: Int, delayed: Boolean) {
        val scope = c.view.scope ?: return
        job?.cancel()
        pending = pos
        job = scope.launch {
            val list = sources()
            if (delayed) delay(list.minOfOrNull { it.hoverTime } ?: 300L)
            for (s in list) {
                val st = c.view.state
                val res = try {
                    s.query(st, pos, side)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    EditorDiagnostics.reportPluginFailure("hover source ${s.id}", e)
                    null
                }
                // Stale: the document changed while the source worked.
                if (c.view.state.doc !== st.doc) return@launch
                if (res != null || Hover.shown(c.view.state, s.id) != null) {
                    c.view.dispatch(TransactionSpec(effects = listOf(Hover.set.of(s.id to res))))
                }
            }
            pending = -1
            closeJob?.cancel(); closeJob = null
        }
    }

    fun dispose() { job?.cancel(); closeJob?.cancel() }
}
