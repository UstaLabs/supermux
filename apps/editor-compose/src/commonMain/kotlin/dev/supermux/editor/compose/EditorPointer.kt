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
 * may have dismissed it while the field kept focus, terminal-compose's lesson) and shows the
 * caret's handle; a double tap selects the word under the finger (a triple tap its line), with both
 * handles and the menu, the first tap's caret placed at once; a long press selects a word and shows two handles (keep the finger down and drag
 * to extend it by words); a tap inside that selection keeps it, a tap outside collapses it; a
 * finger on a handle drags that end (the other stays), snapping to caret positions and
 * auto-scrolling at an edge. Any other drag scrolls and never selects: its moves are left to
 * `Modifier.scrollable`, which is this node's ancestor and sees the Main pass after it.
 */
internal class EditorPointer(private val c: EditorController, private val scope: CoroutineScope) {
    private enum class Mode { CHAR, WORD, LINE, COLUMN }

    private class Drag(val mode: Mode, val anchor: SelectionRange, val anchorPoint: Offset)

    private class TouchPress(val id: Long, val down: Offset, val downTime: Long) {
        var moved = false
        var longPressed = false
    }

    /**
     * A finger on a handle: [fixed] is the selection end that stays (-1 for the caret's handle),
     * [grab] how far from the handle's tip the finger landed (so the handle does not jump to it).
     */
    private class HandleDrag(val id: Long, val kind: HandleKind, val fixed: Int, val grab: Offset, val down: Offset) {
        var moved = false
    }

    /** Two fingers zooming: their ids, how far apart they started, and the size then. */
    private class Pinch(val a: Long, val b: Long, val startDistance: Float, val startSize: Float)

    private var taps = 0
    private var lastTapTime = Long.MIN_VALUE / 2
    private var lastTapPos = Offset.Zero
    private var doubleTapMillis = 300L
    private var slopPx = 8f

    private var drag: Drag? = null
    private var touch: TouchPress? = null
    private var handleDrag: HandleDrag? = null
    private var pinch: Pinch? = null
    private var lastPointer = Offset.Zero
    private var autoScroll: Job? = null
    private var lastClickTime = 0L
    private var lastClickPos = Offset.Zero
    private var clicks = 0

    suspend fun handle(pointer: PointerInputScope) = with(pointer) {
        val longPress = viewConfiguration.longPressTimeoutMillis
        val doubleTap = viewConfiguration.doubleTapTimeoutMillis
        val slop = viewConfiguration.touchSlop
        doubleTapMillis = doubleTap
        slopPx = slop
        awaitPointerEventScope {
            while (true) {
                val pending = touch?.takeIf { !it.moved && !it.longPressed }
                val event = if (pending == null) awaitPointerEvent(PointerEventPass.Main) else {
                    val left = longPress - (currentEventTimeMillis() - pending.downTime)
                    if (left <= 0) { fireLongPress(pending); continue }
                    withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Main) } ?: run { fireLongPress(pending); continue }
                }
                // An event that arrives after the long-press time, before the timer fired (a late
                // frame, the test clock): the finger was still, so the long press happened first.
                if (pending != null && !pending.moved && !pending.longPressed) {
                    val c0 = event.changes.firstOrNull { it.id.value == pending.id }
                    if (c0 != null && c0.uptimeMillis - pending.downTime >= longPress && (c0.previousPosition - pending.down).getDistance() <= slop) fireLongPress(pending)
                }
                if (pinchStep(event)) continue
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

    /**
     * A two-finger pinch zooms the font (continuously, the host hears the size when the fingers
     * lift). Starts when a second finger lands while no handle is held; while it lasts, every
     * change is consumed, so the scrollable does not scroll and no tap or long press fires.
     * Returns true when [event] was the pinch's.
     */
    private fun pinchStep(event: PointerEvent): Boolean {
        val fingers = event.changes.filter { it.type != PointerType.Mouse && it.pressed }
        val p = pinch
        if (p == null) {
            if (fingers.size < 2 || handleDrag != null) return false
            val (a, b) = fingers[0] to fingers[1]
            val d = (a.position - b.position).getDistance()
            if (d < 1f) return false
            pinch = Pinch(a.id.value, b.id.value, d, c.view.effectiveFontSize)
            touch = null
            drag = null
            stopAutoScroll()
            c.menuShown = false
            event.changes.forEach { it.consume() }
            return true
        }
        val a = fingers.firstOrNull { it.id.value == p.a }
        val b = fingers.firstOrNull { it.id.value == p.b }
        if (a == null || b == null) {
            // A finger lifted: the pinch is over (the other finger does nothing until it lifts too).
            if (fingers.isEmpty()) {
                pinch = null
                c.view.zoomTo(c.view.effectiveFontSize)
            }
            event.changes.forEach { it.consume() }
            return true
        }
        val d = (a.position - b.position).getDistance()
        // Quarter steps: a relayout per quarter point, not per pixel of finger travel.
        val size = kotlin.math.round(p.startSize * d / p.startDistance * 4f) / 4f
        c.view.zoomTo(size, report = false)
        event.changes.forEach { it.consume() }
        return true
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
        // A press on a block widget's content is the widget's (its text field, its buttons): no
        // caret, no focus, no keyboard. A drag starting there still scrolls (the scrollable's).
        if (c.widgetAt(change.position)) return
        if (change.type != PointerType.Mouse) {
            // A handle hangs BELOW its tip: a finger above the tip is on the text row, aiming at the
            // text (a second tap on a word, however slow), never the handle whose target reaches up
            // there. A tap continuing a double tap (the triple tap) is the text's too.
            val tapping = change.uptimeMillis - lastTapTime <= doubleTapMillis && (change.position - lastTapPos).getDistance() <= slopPx * 3
            val spot = (if (c.handles != TouchHandles.NONE) EditorTouch.hit(c.handleSpots(), change.position) else null)
                ?.takeUnless { change.position.y < it.tip.y || (tapping && taps >= 2) }
            if (spot != null) {
                // A handle is the surface's own chrome: the finger drags it, it never scrolls.
                val main = c.view.state.selection.main
                val fixed = when (spot.kind) { HandleKind.START -> main.to; HandleKind.END -> main.from; HandleKind.CURSOR -> -1 }
                handleDrag = HandleDrag(change.id.value, spot.kind, fixed, change.position - spot.tip, change.position)
                c.menuHeld = true
                touch = null
                change.consume()
                return
            }
            touch = TouchPress(change.id.value, change.position, change.uptimeMillis)
            return // a finger's press is also the start of a scroll: leave it to the scrollable
        }
        if (!event.buttons.isPrimaryPressed) return
        // A marker column's cell: the click is the marker's (a fold arrow, a comment), not the text's.
        c.gutterHit(change.position)?.let { hit ->
            change.consume()
            c.reportGutterClick(hit)
            return
        }
        c.handles = TouchHandles.NONE
        c.menuShown = false
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
        val h = handleDrag
        if (h != null) {
            val moving = event.changes.firstOrNull { it.id.value == h.id } ?: return
            if (!moving.pressed) return
            // No slop: a grabbed handle is never a scroll, and a one-character move is a few dp.
            // It counts as moved (not a tap on the handle) once the selection or the finger moved.
            if ((moving.position - h.down).getDistance() > slop) h.moved = true
            lastPointer = moving.position
            val before = c.view.state.selection
            extendTo(moving.position)
            if (c.view.state.selection != before) h.moved = true
            updateAutoScroll()
            moving.consume()
            return
        }
        val t = touch
        if (t != null && change.id.value == t.id) {
            if ((change.position - t.down).getDistance() > slop) t.moved = true
            // After a long press the finger belongs to the selection (extending it by words), not
            // to scrolling.
            if (t.longPressed) {
                lastPointer = change.position
                extendTo(change.position)
                updateAutoScroll()
                c.magnifierAt = c.caretRectOnScreen(c.view.state.selection.main.head).center
                change.consume()
            }
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
        val h = handleDrag
        if (h != null) {
            handleDrag = null
            stopAutoScroll()
            c.magnifierAt = Offset.Unspecified
            change.consume()
            onHandleReleased(moved = h.moved)
            return
        }
        val t = touch
        if (t != null && change.id.value == t.id) {
            touch = null
            if (t.longPressed) {
                drag = null
                stopAutoScroll()
                c.magnifierAt = Offset.Unspecified
                change.consume()
                onLongPressReleased()
                return
            }
            if (!t.moved) onTap(change.position, change.uptimeMillis)
            return
        }
        if (drag != null) change.consume()
        drag = null
        stopAutoScroll()
    }

    private fun stopAutoScroll() {
        autoScroll?.cancel()
        autoScroll = null
    }

    /**
     * A finger tapped the text: inside the selection the touch handles hold, the selection stays;
     * anywhere else the caret goes there, with its own handle.
     */
    private fun onTap(p: Offset, time: Long) {
        // A marker column's cell: the tap is the marker's; no caret, no keyboard.
        c.gutterHit(p)?.let { c.reportGutterClick(it); return }
        c.focusFromTouch()
        // Taps close in time and place count up: the first places the caret AT ONCE (a single tap
        // never waits for a second), the second upgrades it to the word, the third to the line.
        taps = if (time - lastTapTime <= doubleTapMillis && (p - lastTapPos).getDistance() <= slopPx * 3) taps % 3 + 1 else 1
        lastTapTime = time
        lastTapPos = p
        if (taps == 2 || taps == 3) {
            val at = offsetAt(p)
            val r = if (taps == 2) TextBoundaries.wordAt(c.view.state.doc, at).let { SelectionRange(it.first, it.last + 1) } else lineRange(at)
            if (!r.empty) {
                select(EditorSelection.single(r.anchor, r.head))
                c.handles = TouchHandles.SELECTION
                c.menuShown = true
                return
            }
        }
        val main = c.view.state.selection.main
        if (c.handles == TouchHandles.SELECTION && !main.empty && insideSelection(p)) {
            onTapInSelection()
            return
        }
        select(EditorSelection.cursor(offsetAt(p)))
        c.handles = TouchHandles.CURSOR
        onCaretPlaced()
    }

    /** True when [p] (surface pixels) is on one of the main range's selection rects. */
    private fun insideSelection(p: Offset): Boolean {
        val main = c.view.state.selection.main
        val q = contentOf(p)
        return c.geometry.selectionRects(main, q.y - 1f, q.y + 1f).any { it.contains(q) }
    }

    // The selection menu: a tap in the selection toggles it; a new caret hides it; it appears when
    // a long press or a handle drag ends (a tap on a handle toggles it).
    private fun onTapInSelection() { c.menuShown = !c.menuShown }
    private fun onCaretPlaced() { c.menuShown = false }
    private fun onLongPressReleased() { c.menuShown = true }
    private fun onHandleReleased(moved: Boolean) {
        c.menuHeld = false
        c.menuShown = if (moved) true else !c.menuShown
    }

    private fun fireLongPress(t: TouchPress) {
        t.longPressed = true
        taps = 0
        c.focusFromTouch()
        val doc = c.view.state.doc
        val at = offsetAt(t.down)
        val w = TextBoundaries.wordAt(doc, at)
        if (w.isEmpty() || noWordAt(t.down, at)) {
            // Nothing to select (an empty line, past a line's end, below the last line): the
            // caret goes there with its handle, and the menu (Paste, Select All) comes on release.
            select(EditorSelection.cursor(at))
            c.handles = TouchHandles.CURSOR
            c.menuShown = false
            drag = null
            lastPointer = t.down
            return
        }
        val r = SelectionRange(w.first, w.last + 1)
        select(EditorSelection.single(r.anchor, r.head))
        c.handles = TouchHandles.SELECTION
        c.menuShown = false
        // Keep the finger down and drag: the selection grows by words from the pressed one.
        drag = Drag(Mode.WORD, r, contentOf(t.down))
        lastPointer = t.down
    }

    /** True when [p] (surface pixels) is past the end of [at]'s line, or below the document. */
    private fun noWordAt(p: Offset, at: Int): Boolean {
        val g = c.geometry
        val q = contentOf(p)
        if (q.y >= g.heights.totalHeight) return true
        val doc = c.view.state.doc
        val line = doc.lineIndexAt(at)
        val lineEnd = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        return at == lineEnd && q.x > g.rectFor(lineEnd).left + g.layouts.charWidthPx / 2
    }

    private fun lineRange(at: Int): SelectionRange {
        val doc = c.view.state.doc
        val line = doc.lineIndexAt(at)
        val end = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) else doc.length
        return SelectionRange(doc.lineStart(line), end)
    }

    /** Extend the drag's selection (or move the dragged handle's end) to the pointer at [pos] (surface pixels). */
    private fun extendTo(pos: Offset) {
        handleDrag?.let { dragHandleTo(it, pos); return }
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

    /**
     * Move [h]'s end to where the finger at [pos] puts the handle's tip: the caret position half a
     * line above it (the tip is at the caret's bottom). A selection end never lands on the other
     * end (the selection would collapse under the finger).
     */
    private fun dragHandleTo(h: HandleDrag, pos: Offset) {
        val tip = pos - h.grab
        val probe = Offset(tip.x, tip.y - c.layouts.lineHeightPx / 2)
        val at = offsetAt(probe)
        val sel = when (h.kind) {
            HandleKind.CURSOR -> EditorSelection.cursor(at)
            else -> if (at == h.fixed) return else EditorSelection.single(h.fixed, at)
        }
        c.magnifierAt = c.caretRectOnScreen(at).center
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
            while (drag != null || handleDrag != null) {
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
