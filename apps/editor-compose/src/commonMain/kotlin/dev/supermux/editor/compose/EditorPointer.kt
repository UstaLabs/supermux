package dev.supermux.editor.compose

import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pointer gestures to selection transactions (`select.pointer`).
 *
 * **Mouse:** a click places the cursor and takes focus WITHOUT a soft keyboard; Shift-click extends
 * the main range; a drag selects (auto-scrolling past an edge); a double click selects a word, a
 * triple click a line, and a drag after them extends by words / lines; Alt-drag makes a column
 * selection, one range per line.
 *
 * **Touch:** a tap places the cursor, takes focus AND raises the soft keyboard (every time: the user
 * may have dismissed it while the field kept focus, terminal-compose's lesson); a long press
 * selects a word (handles are M3b); a drag scrolls and never selects: its moves are left to
 * `Modifier.scrollable`, which is this node's ancestor and sees the Main pass after it.
 */
internal class EditorPointer(private val c: EditorController, private val scope: CoroutineScope) {
    private enum class Mode { CHAR, WORD, LINE, COLUMN }

    private class Drag(val mode: Mode, val anchor: SelectionRange, val anchorPoint: Offset)

    private class TouchPress(val id: Long, val down: Offset, val downTime: Long) {
        var moved = false
        var longPressed = false
    }

    private var drag: Drag? = null
    private var touch: TouchPress? = null
    private var lastPointer = Offset.Zero
    private var autoScroll: Job? = null
    private var lastClickTime = 0L
    private var lastClickPos = Offset.Zero
    private var clicks = 0

    suspend fun handle(pointer: PointerInputScope) = with(pointer) {
        val longPress = viewConfiguration.longPressTimeoutMillis
        val doubleTap = viewConfiguration.doubleTapTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val pending = touch?.takeIf { !it.moved && !it.longPressed }
                val event = if (pending == null) awaitPointerEvent(PointerEventPass.Main) else {
                    val left = longPress - (currentEventTimeMillis() - pending.downTime)
                    if (left <= 0) { fireLongPress(pending); continue }
                    withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Main) } ?: run { fireLongPress(pending); continue }
                }
                when (event.type) {
                    PointerEventType.Press -> onPress(event, doubleTap, slop)
                    // Leaving the surface mid-drag arrives as Exit (and coming back as Enter): still
                    // a move for the selection and the auto-scroll.
                    PointerEventType.Move, PointerEventType.Exit, PointerEventType.Enter -> onMove(event, slop)
                    PointerEventType.Release -> onRelease(event)
                    else -> Unit
                }
            }
        }
    }

    private var eventTime = 0L
    private fun currentEventTimeMillis() = eventTime

    private fun contentOf(p: Offset) = Offset(p.x - c.textLeft + c.scroll.x, p.y + c.scroll.y)
    private fun offsetAt(p: Offset) = c.geometry.offsetAt(contentOf(p))

    private fun select(sel: EditorSelection) {
        c.view.dispatch(TransactionSpec(selection = sel, userEvent = "select.pointer"))
    }

    private fun AwaitPointerEventScope.onPress(event: PointerEvent, doubleTap: Long, slop: Float) {
        val change = event.changes.firstOrNull { it.pressed } ?: return
        eventTime = change.uptimeMillis
        if (change.type != PointerType.Mouse) {
            touch = TouchPress(change.id.value, change.position, change.uptimeMillis)
            return // a finger's press is also the start of a scroll: leave it to the scrollable
        }
        if (!event.buttons.isPrimaryPressed) return
        c.requestFocus()
        val pos = change.position
        lastPointer = pos
        clicks = if (change.uptimeMillis - lastClickTime <= doubleTap && (pos - lastClickPos).getDistance() <= slop * 2) clicks % 3 + 1 else 1
        lastClickTime = change.uptimeMillis
        lastClickPos = pos
        val doc = c.view.state.doc
        val at = offsetAt(pos)
        val mods = event.keyboardModifiers
        when {
            mods.isAltPressed -> {
                drag = Drag(Mode.COLUMN, SelectionRange(at), contentOf(pos))
                select(EditorSelection.cursor(at))
            }
            mods.isShiftPressed -> {
                val anchor = c.view.state.selection.main.anchor
                drag = Drag(Mode.CHAR, SelectionRange(anchor), contentOf(pos))
                select(EditorSelection.single(anchor, at))
            }
            clicks == 2 -> {
                val w = TextBoundaries.wordAt(doc, at)
                val r = SelectionRange(w.first, w.last + 1)
                drag = Drag(Mode.WORD, r, contentOf(pos))
                select(EditorSelection.single(r.anchor, r.head))
            }
            clicks == 3 -> {
                val r = lineRange(at)
                drag = Drag(Mode.LINE, r, contentOf(pos))
                select(EditorSelection.single(r.anchor, r.head))
            }
            else -> {
                drag = Drag(Mode.CHAR, SelectionRange(at), contentOf(pos))
                select(EditorSelection.cursor(at))
            }
        }
        change.consume()
    }

    private fun AwaitPointerEventScope.onMove(event: PointerEvent, slop: Float) {
        val change = event.changes.firstOrNull() ?: return
        eventTime = change.uptimeMillis
        val t = touch
        if (t != null && change.id.value == t.id) {
            if ((change.position - t.down).getDistance() > slop) t.moved = true
            // After a long press the finger belongs to the selection, not to scrolling.
            if (t.longPressed) change.consume()
            return
        }
        if (drag == null || !change.pressed) return
        lastPointer = change.position
        extendTo(change.position)
        updateAutoScroll()
        change.consume()
    }

    private fun AwaitPointerEventScope.onRelease(event: PointerEvent) {
        val change = event.changes.firstOrNull() ?: return
        eventTime = change.uptimeMillis
        val t = touch
        if (t != null && change.id.value == t.id) {
            touch = null
            if (t.longPressed) { change.consume(); return }
            if (!t.moved) {
                c.focusFromTouch()
                select(EditorSelection.cursor(offsetAt(change.position)))
            }
            return
        }
        if (drag != null) change.consume()
        drag = null
        autoScroll?.cancel()
        autoScroll = null
    }

    private fun fireLongPress(t: TouchPress) {
        t.longPressed = true
        c.focusFromTouch()
        val w = TextBoundaries.wordAt(c.view.state.doc, offsetAt(t.down))
        select(EditorSelection.single(w.first, w.last + 1))
    }

    private fun lineRange(at: Int): SelectionRange {
        val doc = c.view.state.doc
        val line = doc.lineIndexAt(at)
        val end = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) else doc.length
        return SelectionRange(doc.lineStart(line), end)
    }

    /** Extend the drag's selection to the pointer at [pos] (surface pixels). */
    private fun extendTo(pos: Offset) {
        val d = drag ?: return
        val at = offsetAt(pos)
        val a = d.anchor
        val sel = when (d.mode) {
            Mode.CHAR -> EditorSelection.single(a.anchor, at)
            Mode.WORD, Mode.LINE -> {
                val unit = if (d.mode == Mode.WORD) TextBoundaries.wordAt(c.view.state.doc, at).let { SelectionRange(it.first, it.last + 1) } else lineRange(at)
                if (at < a.from) EditorSelection.single(a.to, minOf(unit.from, a.from)) else EditorSelection.single(a.from, maxOf(unit.to, a.to))
            }
            Mode.COLUMN -> column(d.anchorPoint, contentOf(pos))
        }
        if (sel != c.view.state.selection) select(sel)
    }

    /** One range per line between [a] and [b] (content coordinates), from a's x to b's x. */
    private fun column(a: Offset, b: Offset): EditorSelection {
        val g = c.geometry
        val first = g.heights.lineAt(minOf(a.y, b.y))
        val last = g.heights.lineAt(maxOf(a.y, b.y))
        val lh = g.layouts.lineHeightPx
        val ranges = (first..last).map { l ->
            val y = g.lineTop(l) + lh / 2
            SelectionRange(g.offsetAt(Offset(a.x, y)), g.offsetAt(Offset(b.x, y)))
        }
        val main = if (b.y >= a.y) ranges.size - 1 else 0
        return EditorSelection.create(ranges, main)
    }

    /** How far past an edge the pointer is (negative: before the start), per axis. */
    private fun overshoot(): Offset {
        val size = c.viewportSize
        val p = lastPointer
        val dy = when { p.y < 0f -> p.y; p.y > size.height -> p.y - size.height; else -> 0f }
        val dx = if (c.lineWrap) 0f else when { p.x < c.textLeft -> p.x - c.textLeft; p.x > size.width -> p.x - size.width; else -> 0f }
        return Offset(dx, dy)
    }

    private fun updateAutoScroll() {
        if (overshoot() == Offset.Zero || autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (drag != null) {
                val o = overshoot()
                if (o == Offset.Zero) break
                withFrameMillis { }
                fun speed(v: Float) = if (v == 0f) 0f else (abs(v) / 2f).coerceIn(2f, 80f) * (if (v < 0) -1 else 1)
                c.scroll.scrollBy(speed(o.x), speed(o.y))
                extendTo(lastPointer)
            }
        }
    }
}
