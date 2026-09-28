package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import dev.supermux.editor.core.KeyBinding
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.keymapFacet
import dev.supermux.editor.core.runKey

// ---------------------------------------------------------------------- the pure part --------
//
// A soft keyboard does not press keys: it EDITS the focused text field (terminal-compose's lesson,
// TerminalTextInput.kt). So the surface's focus target is a real, invisible text field holding a
// WINDOW of the real document text around the main cursor, and every change the IME makes to it is
// diffed back into a transaction. With real text before the caret, autocorrect, Turkish input,
// suggestions, dictation and CJK composition all work, and Backspace always has something to eat.
// Proven on an iPhone 15 Pro in M0 (all 8 checks, docs/superpowers/notes/m0-artifacts/ImeProbe.kt).

/** A replacement of field range [from, to) by [insert]. */
data class FieldEdit(val from: Int, val to: Int, val insert: String)

/**
 * The smallest single replacement turning [before] into [after] that COVERS the field's previous
 * selection [selFrom, selTo): the common prefix stops at [selFrom] and the common suffix at
 * [selTo] (for a caret, at the caret in both directions). Without the clamp, typing a selection's
 * own first character over it ("abc" selected, type "a") looks like deleting "bc", and repeated
 * characters ("    " -> "   ") make the split ambiguous. [newCaret] (the field's caret after the
 * edit, -1: unknown) clamps both sides the same way in the new text, so a Backspace in repeated
 * spaces deletes the space BEFORE the caret (CM6's preferred-position diff). The defaults clamp nothing.
 */
fun diffField(before: String, after: String, selFrom: Int = before.length, selTo: Int = 0, newCaret: Int = -1): FieldEdit? {
    if (before == after) return null
    var maxPrefix = minOf(before.length, after.length, selFrom.coerceAtLeast(0))
    if (newCaret >= 0) maxPrefix = minOf(maxPrefix, newCaret)
    var p = 0
    while (p < maxPrefix && before[p] == after[p]) p++
    var maxSuffix = minOf(before.length - p, after.length - p, (before.length - selTo).coerceAtLeast(0))
    if (newCaret >= 0) maxSuffix = minOf(maxSuffix, (after.length - newCaret).coerceAtLeast(0))
    var s = 0
    while (s < maxSuffix && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
    return FieldEdit(p, before.length - s, after.substring(p, after.length - s))
}

/**
 * The slice of the document the hidden field holds: [text] starts at document offset [base]. A
 * replaced (folded) range inside it is ONE placeholder character (U+FFFC) in [text] ([holes]): the
 * keyboard never sees hidden text, and deleting the placeholder is an edit of the whole range (which
 * the view judges: an atomic one is never deleted by one keystroke). [end] is the document offset
 * where it ends.
 */
data class FieldWindow(val base: Int, val text: String, val holes: List<FieldHole> = emptyList()) {
    val end: Int get() = base + text.length + holes.sumOf { it.docTo - it.docFrom - 1 }

    fun applyTo(doc: String, e: FieldEdit): String = doc.replaceRange(toDoc(e.from), toDoc(e.to), e.insert)

    /** The document offset of field offset [k] (a placeholder's edges: its range's ends). */
    fun toDoc(k: Int): Int {
        var shift = 0
        for (h in holes) {
            if (k <= h.at) return base + k + shift
            if (k == h.at + 1) return h.docTo
            shift += h.docTo - h.docFrom - 1
        }
        return base + k + shift
    }

    /** The field offset of document offset [pos] (inside a hidden range: before its placeholder). */
    fun toField(pos: Int): Int {
        var shift = 0
        for (h in holes) {
            if (pos <= h.docFrom) return (pos - base - shift).coerceIn(0, text.length)
            if (pos < h.docTo) return h.at
            if (pos == h.docTo) return h.at + 1
            shift += h.docTo - h.docFrom - 1
        }
        return (pos - base - shift).coerceIn(0, text.length)
    }

    companion object {
        /** What stands for a hidden range in the field. */
        const val PLACEHOLDER = '\uFFFC'

        /** [doc]'s [start, end) as the field shows it: every replaced range of [folds] inside it one [PLACEHOLDER]. */
        internal fun of(doc: Rope, start: Int, end: Int, folds: Folds?): FieldWindow {
            val inside = folds?.replacesInside(start, end)?.filter { it.from >= start && it.to <= end }.orEmpty()
            if (inside.isEmpty()) return FieldWindow(start, doc.slice(start, end))
            val sb = StringBuilder()
            val holes = ArrayList<FieldHole>()
            var pos = start
            for (r in inside) {
                sb.append(doc.slice(pos, r.from))
                holes += FieldHole(sb.length, r.from, r.to)
                sb.append(PLACEHOLDER)
                pos = r.to
            }
            sb.append(doc.slice(pos, end))
            return FieldWindow(start, sb.toString(), holes)
        }

        /**
         * About [radius] units each side of [cursor], preferring whole lines (so autocorrect sees
         * whole words): an edge moves out to its line's boundary when that stays within twice the
         * radius, else in to a line boundary, else to a word boundary; never inside a surrogate pair,
         * never inside a replaced range of [folds] (the range is taken whole, as its placeholder).
         */
        fun around(doc: Rope, cursor: Int, radius: Int): FieldWindow = around(doc, cursor, radius, null)

        internal fun around(doc: Rope, cursor: Int, radius: Int, folds: Folds?): FieldWindow {
            val len = doc.length
            val margin = maxOf(1, radius / 4)
            var start = maxOf(0, cursor - radius)
            if (start > 0) {
                val lineStart = doc.lineStart(doc.lineIndexAt(start))
                start = if (cursor - lineStart <= 2 * radius) lineStart
                else boundaryAfter(doc, start, cursor - margin) ?: start
            }
            var end = minOf(len, cursor + radius)
            if (end < len) {
                val line = doc.lineIndexAt(end)
                val lineEnd = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else len
                end = if (lineEnd - cursor <= 2 * radius) lineEnd
                else boundaryBefore(doc, end, cursor + margin) ?: end
            }
            if (start > 0 && start < len && doc.charAt(start).isLowSurrogate()) start--
            if (end in 1 until len && doc.charAt(end).isLowSurrogate()) end++
            if (folds != null && folds.replaces.isNotEmpty()) {
                folds.replaceInside(start)?.let { start = it.from }
                folds.replaceInside(end)?.let { end = it.to }
            }
            return of(doc, start, end, folds)
        }

        /** The first line start (else word start) in [from, limit], or null. */
        private fun boundaryAfter(doc: Rope, from: Int, limit: Int): Int? {
            if (limit <= from) return null
            val s = doc.slice(from, limit)
            val nl = s.indexOf('\n')
            if (nl >= 0) return from + nl + 1
            val ws = s.indexOfFirst { it == ' ' || it == '\t' }
            return if (ws >= 0) from + ws + 1 else null
        }

        /** The last line end (else word end) in [limit, from], or null. */
        private fun boundaryBefore(doc: Rope, from: Int, limit: Int): Int? {
            if (from <= limit) return null
            val s = doc.slice(limit, from)
            val nl = s.lastIndexOf('\n')
            if (nl >= 0) return limit + nl
            val ws = s.indexOfLast { it == ' ' || it == '\t' }
            return if (ws >= 0) limit + ws else null
        }
    }
}

/** One placeholder of a [FieldWindow]: at field offset [at], standing for document [docFrom, docTo). */
data class FieldHole(val at: Int, val docFrom: Int, val docTo: Int)

/** What the platform field holds (or must be set to): its text and its selection. */
data class FieldText(val text: String, val selStart: Int, val selEnd: Int)

/**
 * The hidden field and the document, kept in step.
 *
 * - [onFieldChange]: the IME changed the field. The change is diffed against the [window] and
 *   dispatched as a transaction (`input`, or `input.ime` while composing); a soft Return becomes
 *   [DefaultCommands.insertNewline] (indentation kept); with several cursors, an edit at the main
 *   one is made at every one; a selection larger than the window is replaced whole.
 * - [onStateChange]: after EVERY transaction (a view listener, synchronous, so a keystroke that
 *   follows a key command sees the new text). When the field no longer shows the document's text
 *   at its window, or the caret left it, the window is rebuilt around the main cursor.
 * - The window is also rebuilt when the caret comes within [margin] of an edge with more document
 *   beyond it, but never while the IME is composing (rewriting the field mid-composition breaks
 *   it): so Backspace at the window's start re-windows instead of being eaten (M0 checklist step 3).
 *
 * The surface calls [onFieldChange] twice per user edit: synchronously from the field's input
 * transformation (`deferRewindow`: the document changes inside the input event, so the edit is
 * painted in the very next frame; the composition is not known there), and again from the field's
 * state once the edit is committed, where the composition IS known (the underline, IME caret moves,
 * and re-windowing happen there).
 *
 * A non-null return is what the platform field must now hold.
 */
internal class FieldSync(
    private val view: EditorView,
    private val radius: Int = DEFAULT_RADIUS,
    private val margin: Int = DEFAULT_MARGIN,
) {
    var window: FieldWindow = window()
        private set

    /** The document range the IME is composing (drawn underlined), or null. */
    var composition: IntRange? = null
        private set

    /** What the platform field holds, as last reported or written. */
    private var shown: FieldText? = null

    /** [f], remembered as what the field now holds. */
    private fun show(f: FieldText): FieldText = f.also { shown = it }

    /** What a newly created field starts with (and is remembered as showing). */
    fun initialField(): FieldText = show(current())

    /** The field as it must be now: the window's text and the main range, clipped to it. */
    fun current(): FieldText {
        val main = view.state.selection.main
        val w = window
        return FieldText(w.text, w.toField(main.anchor), w.toField(main.head))
    }

    fun onFieldChange(rawText: String, rawSelStart: Int, rawSelEnd: Int, composition: IntRange?, deferRewindow: Boolean = false): FieldText? {
        // A pasted or dictated CR (a CRLF, a lone CR) arrives as \n: the editor's only line break.
        // The field is then rewritten to what the document holds.
        if (rawText.indexOf('\r') >= 0) {
            val text = normalizeLineBreaks(rawText)
            val a = normalizeLineBreaks(rawText.substring(0, rawSelStart.coerceIn(0, rawText.length))).length
            val b = normalizeLineBreaks(rawText.substring(0, rawSelEnd.coerceIn(0, rawText.length))).length
            val r = onFieldChange(text, a, b, composition, deferRewindow)
            return r ?: rewindow() ?: show(current())
        }
        val text = rawText
        val selStart = rawSelStart
        val selEnd = rawSelEnd
        if (!deferRewindow) return apply(text, selStart, selEnd, composition, false)
        deferring = true
        try { return apply(text, selStart, selEnd, composition, true) } finally { deferring = false }
    }

    /** True while an input event's edit is applied: no re-windowing at an edge then. */
    private var deferring = false

    /**
     * The last edit went out from the input event as plain `input` (the composition was not known
     * yet). When the committed field then shows a composition, that edit was its first character:
     * [joinNext] makes the next composition step carry [EditorAnnotations.imeJoinPrevious].
     */
    private var lastWasDeferredInput = false
    private var joinNext = false

    /**
     * [spec] as the field's own edit ([EditorAnnotations.fieldInput]: the view refuses it if it
     * inserts the placeholder), with the join annotation when it is the step after a composition's
     * first character.
     */
    private fun joined(spec: TransactionSpec, event: String): TransactionSpec {
        val field = spec.copy(annotations = spec.annotations + EditorAnnotations.fieldInput.of(true))
        if (!joinNext || event != "input.ime") return field
        joinNext = false
        return field.copy(annotations = field.annotations + EditorAnnotations.imeJoinPrevious.of(true))
    }

    /**
     * An IME edit [e] that hands U+FFFC back (a case transform or an autocorrect of a selection over a
     * fold): each U+FFFC of the inserted text stands for the one it replaced, in order (a fold's
     * placeholder, or one the document really holds), and is KEPT: only the text between them
     * changes, so the hidden text stays as it was (untransformed) and so does the fold. When the
     * counts differ, nothing maps unambiguously: the edit is refused and the field shows the
     * document again.
     */
    private fun applyAroundPlaceholders(w: FieldWindow, e: FieldEdit, text: String, selStart: Int, selEnd: Int, event: String): FieldText? {
        val ph = FieldWindow.PLACEHOLDER
        val marks = (e.from until e.to).filter { w.text[it] == ph }
        val parts = e.insert.split(ph)
        if (parts.size - 1 != marks.size) return rewindow() ?: show(current())
        val specs = ArrayList<ChangeSpec>()
        var segStart = e.from
        for ((i, part) in parts.withIndex()) {
            val segEnd = if (i < marks.size) marks[i] else e.to
            if (w.text.substring(segStart, segEnd) != part) specs += ChangeSpec(w.toDoc(segStart), w.toDoc(segEnd), part)
            segStart = segEnd + 1
        }
        val st = view.state
        val changes = dev.supermux.editor.core.ChangeSet.of(st.doc.length, specs)
        // The window after it: the placeholders where the field now has them.
        val delta = e.insert.length - (e.to - e.from)
        val holes = w.holes.map { h ->
            val at = when {
                h.at < e.from -> h.at
                h.at >= e.to -> h.at + delta
                else -> { val j = marks.indexOf(h.at); e.from + parts.take(j + 1).sumOf { it.length } + j }
            }
            FieldHole(at, changes.mapPos(h.docFrom, 1), changes.mapPos(h.docTo, -1))
        }
        val nw = FieldWindow(changes.mapPos(w.base, -1), text, holes)
        val sel = st.selection
        val main = SelectionRange(nw.toDoc(selStart), nw.toDoc(selEnd))
        val next = if (sel.ranges.size == 1) EditorSelection.single(main.anchor, main.head)
        else EditorSelection.create(sel.ranges.mapIndexed { i, r -> if (i == sel.mainIndex) main else r.map(changes) }, sel.mainIndex)
        window = nw
        view.dispatch(joined(TransactionSpec(changeSet = changes, selection = next, scrollIntoView = true, userEvent = event), event))
        if (!docMatches(window)) return rewindow() ?: show(current())
        return null
    }

    private fun apply(text: String, selStart: Int, selEnd: Int, composition: IntRange?, deferRewindow: Boolean): FieldText? {
        val before = shown
        shown = FieldText(text, selStart, selEnd)
        if (view.readOnly) return if (text != window.text) show(current()) else null
        val wasComposing = this.composition != null
        val w = window
        if (!deferRewindow) {
            this.composition = composition?.let { w.toDoc(it.first) until w.toDoc(it.last + 1) }
            // A composition appearing right after an edit that went out as plain input: that edit
            // was its first character (see [joined]).
            if (composition != null && !wasComposing && lastWasDeferredInput) joinNext = true
            if (composition == null) joinNext = false
            lastWasDeferredInput = false
        }
        if (text == w.text) {
            // Only the caret moved (an IME cursor gesture): follow it, unless mid-composition. The
            // echo of a selection this class wrote (clamped to the window) is not a move.
            val moved = before != null && (before.selStart != selStart || before.selEnd != selEnd)
            if (composition == null && moved) {
                val sel = SelectionRange(w.toDoc(selStart), w.toDoc(selEnd))
                val st = view.state
                if (st.selection.ranges.size != 1 || st.selection.main != sel) {
                    if (sel.to <= st.doc.length) view.dispatch(TransactionSpec(selection = EditorSelection.single(sel.anchor, sel.head), userEvent = "select"))
                }
            }
            // The edit was applied from the input transformation; now the composition is known.
            if (!deferRewindow && composition == null && nearEdge()) return rewindow()
            return null
        }
        if (!docMatches(w)) return rewindow()
        // The field's selection before this edit: the edit must cover it.
        val prev = before ?: current()
        val prevFrom = minOf(prev.selStart, prev.selEnd)
        val prevTo = maxOf(prev.selStart, prev.selEnd)
        val raw = diffField(w.text, text) ?: return null
        val clamped = diffField(w.text, text, prevFrom, prevTo, selEnd)!!
        // Typing over a selection replaced it: the edit covers it. At a caret, the edit is the
        // caret's only when covering the caret did not make it bigger (an autocorrect of an earlier
        // word is not an edit at every cursor).
        val atMain = prevFrom < prevTo ||
            (clamped.to - clamped.from == raw.to - raw.from && clamped.insert.length == raw.insert.length)
        val e = if (atMain) clamped else raw
        val from = w.toDoc(e.from)
        val to = w.toDoc(e.to)
        val st = view.state
        val sel = st.selection
        val main = sel.main
        val clampedFrom = main.from.coerceIn(w.base, w.end)
        val clampedTo = main.to.coerceIn(w.base, w.end)
        val event = if (composition != null || wasComposing) "input.ime" else "input"
        if (e.insert.indexOf(FieldWindow.PLACEHOLDER) >= 0) return applyAroundPlaceholders(w, e, text, selStart, selEnd, event)
        if (composition == null && !wasComposing && e.insert == "\n" && e.from == e.to && from == main.head && main.empty) {
            // A soft Return is the Enter key: the keymap's binding (a plugin's Enter between braces),
            // else the editor's newline (it keeps the indentation), at every cursor.
            if (!runBindings(view, KeyChord("Enter"), isApplePlatform)) DefaultCommands.insertNewline.run(view)
            // The field holds the "\n" it typed: a binding that edited nothing (or something else)
            // leaves it there unless the field is rewritten to the document now.
            return rewindow() ?: show(current())
        }
        if (atMain && from <= clampedFrom && to >= clampedTo) {
            // The edit covers the main range (typing, Backspace, autocorrect, a composition step):
            // the same edit, relative to EVERY range (CM6). A selection wider than the window is
            // replaced whole: its clamped part was the field's selection.
            val before0 = clampedFrom - from
            val after0 = to - clampedTo
            val anchor = (selStart - e.from).coerceIn(0, e.insert.length)
            val head = (selEnd - e.from).coerceIn(0, e.insert.length)
            // An input handler took it (it may have edited nothing): the field must show the document.
            val typed = view.typeSpec(e.insert, event, before0, after0, anchor, head) ?: return rewindow() ?: show(current())
            val (spec, changes) = typed
            // The field now holds [text]; so does the document at the window moved through the edit
            // (the window holds no other range, so only ranges before it shift it).
            window = followed(w, e, text, changes)
            if (deferRewindow) lastWasDeferredInput = event == "input"
            view.dispatch(joined(spec, event))
            // Refused (it reached into a fold) or changed by someone else: show the document again.
            if (!docMatches(window)) return rewindow() ?: show(current())
        } else {
            // An edit away from the main range (an IME rewriting another word): as the field made
            // it, every range kept (mapped), never collapsed.
            val changes = dev.supermux.editor.core.ChangeSet.of(st.doc.length, listOf(ChangeSpec(from, to, e.insert)))
            val nw = followed(w, e, text, changes)
            val next = if (sel.ranges.size == 1) EditorSelection.single(nw.toDoc(selStart), nw.toDoc(selEnd)) else sel.map(changes)
            window = nw
            if (deferRewindow) lastWasDeferredInput = event == "input"
            view.dispatch(joined(TransactionSpec(changeSet = changes, selection = next, scrollIntoView = true, userEvent = event), event))
            if (!docMatches(window)) return rewindow() ?: show(current())
        }
        // A huge edit (a paste) leaves a huge field: shrink it now, even inside the input event.
        if (window.text.length > 4 * radius) return rewindow()
        if (!deferRewindow && composition == null && nearEdge()) return rewindow()
        return null
    }

    /**
     * The web's text insertion, taken from the browser before it edits its TEXTAREA (`beforeinput`
     * insertText / insertReplacementText): [data] replaces the field's `[start, end)` as the DOM
     * holds it, as if the field had made that edit (autocorrect and several cursors included).
     * Returns what the field must now hold (always: it did not change itself).
     */
    fun onDomInsert(start: Int, end: Int, data: String): FieldText {
        val f = shown ?: current()
        val a = start.coerceIn(0, f.text.length)
        val b = end.coerceIn(a, f.text.length)
        val text = f.text.substring(0, a) + data + f.text.substring(b)
        val caret = a + data.length
        shown = FieldText(f.text, a, b) // the edit covers the DOM's selection
        return onFieldChange(text, caret, caret, null) ?: show(current())
    }

    /** After every transaction: null while the field still shows the document; else what it must show. */
    fun onStateChange(): FieldText? {
        val w = window
        val main = view.state.selection.main
        val inside = main.from >= w.base && main.to <= w.end
        if (!docMatches(w) || !inside || holdsAnotherRange(w)) return rewindow()
        if (composition == null && !deferring && nearEdge()) return rewindow()
        val now = current()
        return if (now == shown) null else show(now)
    }

    /**
     * Rebuild the window around the main cursor (the composition is gone with the old text); null
     * when that changes nothing (the field already shows it).
     */
    fun rewindow(): FieldText? {
        val next = window()
        if (next == window && current() == shown) return null
        window = next
        composition = null
        return show(current())
    }

    /** [w] after the field's edit [e] (now showing [text]) went into the document as [changes]: its placeholders moved with it. */
    private fun followed(w: FieldWindow, e: FieldEdit, text: String, changes: dev.supermux.editor.core.ChangeSet): FieldWindow {
        if (w.holes.isEmpty()) return FieldWindow(changes.mapPos(w.base, -1), text)
        val delta = e.insert.length - (e.to - e.from)
        val holes = w.holes.mapNotNull { h ->
            when {
                h.at + 1 <= e.from -> h
                h.at >= e.to -> FieldHole(h.at + delta, changes.mapPos(h.docFrom, 1), changes.mapPos(h.docTo, -1))
                else -> null // the edit took its placeholder
            }
        }
        return FieldWindow(changes.mapPos(w.base, -1), text, holes)
    }

    /**
     * About [radius] around the main cursor, but holding no OTHER range: typing at several cursors
     * then changes the field's text only where the field itself changed it, so the field stays in
     * step (and an IME's composition survives) while the other cursors type too.
     */
    private fun window(): FieldWindow {
        val st = view.state
        val main = st.selection.main
        val folds = view.replaced(st)
        val w = FieldWindow.around(st.doc, main.head, radius, folds)
        var start = w.base
        var end = w.end
        for (r in st.selection.ranges) {
            if (r === main) continue
            if (r.to <= main.from) start = maxOf(start, r.to)
            if (r.from >= main.to) end = minOf(end, r.from)
        }
        return if (start == w.base && end == w.end) w else FieldWindow.of(st.doc, start, end, folds)
    }

    /** True when a range other than the main one lies inside [w] (the selection changed shape). */
    private fun holdsAnotherRange(w: FieldWindow): Boolean {
        val sel = view.state.selection
        if (sel.ranges.size == 1) return false
        val main = sel.main
        return sel.ranges.any { it !== main && it.to > w.base && it.from < w.end }
    }

    private fun docMatches(w: FieldWindow): Boolean {
        val st = view.state
        val doc = st.doc
        if (w.end > doc.length) return false
        val folds = view.replaced(st)
        // The window's placeholders must be exactly the replaced ranges in it (checked before any
        // slicing: a stale window must never slice a fold's hidden text, which can be megabytes).
        val inside = if (folds.replaces.isEmpty()) emptyList() else folds.replacesInside(w.base, w.end)
        if (inside.size != w.holes.size) return false
        for (i in inside.indices) if (inside[i].from != w.holes[i].docFrom || inside[i].to != w.holes[i].docTo) return false
        if (w.holes.isEmpty()) return doc.slice(w.base, w.end) == w.text
        return FieldWindow.of(doc, w.base, w.end, folds) == w
    }

    /** The caret is within [margin] of an edge that has more document beyond it. */
    private fun nearEdge(): Boolean {
        val w = window
        val head = view.state.selection.main.head
        return (w.base > 0 && head - w.base < margin) || (w.end < view.state.doc.length && w.end - head < margin)
    }

    companion object {
        const val DEFAULT_RADIUS = 200
        const val DEFAULT_MARGIN = 32
    }
}

// ---------------------------------------------------------------------- hardware keys --------

/** The key name editor-core's [KeyChord] uses for a Compose [Key], or null for keys it has none for. */
internal fun keyName(key: Key): String? = KEY_NAMES[key]

private val KEY_NAMES: Map<Key, String> = buildMap {
    put(Key.DirectionLeft, "ArrowLeft"); put(Key.DirectionRight, "ArrowRight")
    put(Key.DirectionUp, "ArrowUp"); put(Key.DirectionDown, "ArrowDown")
    put(Key.MoveHome, "Home"); put(Key.MoveEnd, "End"); put(Key.PageUp, "PageUp"); put(Key.PageDown, "PageDown")
    put(Key.Backspace, "Backspace"); put(Key.Delete, "Delete"); put(Key.Insert, "Insert")
    put(Key.Enter, "Enter"); put(Key.NumPadEnter, "Enter"); put(Key.Tab, "Tab"); put(Key.Escape, "Escape")
    put(Key.Spacebar, "Space")
    val letters = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
    letters.forEachIndexed { i, k -> put(k, ('a' + i).toString()) }
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    digits.forEachIndexed { i, k -> put(k, i.toString()) }
    val fn = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
    fn.forEachIndexed { i, k -> put(k, "F${i + 1}") }
    put(Key.Minus, "-"); put(Key.Equals, "="); put(Key.LeftBracket, "["); put(Key.RightBracket, "]")
    put(Key.Backslash, "\\"); put(Key.Semicolon, ";"); put(Key.Apostrophe, "'"); put(Key.Grave, "`")
    put(Key.Comma, ","); put(Key.Period, "."); put(Key.Slash, "/")
    put(Key.Plus, "+"); put(Key.NumPadAdd, "+"); put(Key.NumPadSubtract, "-")
}

/**
 * A hardware key-down through the state's keymap facet ([runKey]), then the surface's own
 * [defaultKeymap] bindings as the lowest-precedence fallback (a state needs no keymap of its own
 * to be editable; a plugin overrides a default by binding the same key). Unbound keys return false
 * and reach the hidden field, which is where typed characters come from. While the IME composes,
 * every key is the IME's (Enter picks a candidate, Backspace edits the preedit).
 *
 * The field's own undo/redo chords are swallowed: its private history knows only the window it
 * held, and replaying it would edit the document behind the editor's back (undo is the history
 * plugin's, editor-plugins/history).
 */
internal fun handleEditorKey(view: EditorView, event: KeyEvent, composing: Boolean): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val log = view.onKeyPath
    if (composing) { log?.invoke(keyLabel(event.key), KeyPath.IME); return false }
    val name = keyName(event.key) ?: run { log?.invoke(keyLabel(event.key), KeyPath.FIELD); return false }
    val chord = KeyChord(name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed, meta = event.isMetaPressed)
    val apple = isApplePlatform
    val cp = event.utf16CodePoint
    if (runBindings(view, chord, apple, altGrChar = cp >= 0x20 && cp != 0x7F && cp != 0xFFFF)) { log?.invoke(chord.label(), KeyPath.KEYMAP); return true }
    val swallowed = swallowedChords(apple).contains(chord)
    log?.invoke(chord.label(), if (swallowed) KeyPath.KEYMAP else KeyPath.FIELD)
    return swallowed
}

/** "Ctrl-Shift-ArrowLeft": a chord as a key log shows it. */
internal fun KeyChord.label(): String = buildString {
    if (ctrl) append("Ctrl-"); if (alt) append("Alt-"); if (shift) append("Shift-"); if (meta) append("Meta-")
    append(key)
}

/** What the web's DOM listener knows about one key-down ([webKeyPath]). */
internal class WebKeyFacts(
    /** The event is aimed at Compose's own text input (the TEXTAREA in the canvas's shadow root). */
    val aimedAtField: Boolean,
    val key: String,
    val code: String,
    val flags: Int,
    /** The last pointer's type: "mouse", "touch", "pen", or "" before any. */
    val lastPointer: String,
    /** `navigator.maxTouchPoints`. */
    val maxTouchPoints: Int,
)

/** The web's hardware-key heuristic's memory: a physical-key keydown has been seen. */
internal class WebKeyboardState { var physicalKeySeen = false }

/**
 * Whether [f] is a hardware key ([EditorView.webKeyboard] AUTO): a key with a physical `code`,
 * aimed at the field, not right after a touch or pen, and on a touch-capable device only once a
 * physical-key keydown has been seen (iOS Safari's soft keyboard sends real key values, and must
 * keep going through the field). A heuristic: [WebKeyboard.HARDWARE] / [WebKeyboard.SOFT] force it.
 */
internal fun isHardwareKey(mode: WebKeyboard, f: WebKeyFacts, st: WebKeyboardState): Boolean {
    if (!f.aimedAtField) return false
    if (f.code.isNotEmpty()) st.physicalKeySeen = true
    return when (mode) {
        WebKeyboard.HARDWARE -> true
        WebKeyboard.SOFT -> false
        WebKeyboard.AUTO -> f.lastPointer != "touch" && f.lastPointer != "pen" && (f.maxTouchPoints <= 0 || st.physicalKeySeen)
    }
}

/** The web's DOM keydown: decide the path ([isHardwareKey], [webKeyDown]), log it, return a [WebKey]. */
internal fun webKeyPath(view: EditorView, composing: Boolean, f: WebKeyFacts, st: WebKeyboardState, apple: Boolean = isApplePlatform): Int {
    if (!f.aimedAtField) return WebKey.PASS
    val log = view.onKeyPath
    if (!isHardwareKey(view.webKeyboard, f, st)) { log?.invoke(f.key, KeyPath.WEB_SOFT); return WebKey.PASS }
    val r = webKeyDown(view, composing, f.key, f.code, f.flags, apple)
    log?.invoke(f.key, when (r) { WebKey.HANDLED -> KeyPath.WEB_FAST; WebKey.BROWSER -> KeyPath.WEB_CLIPBOARD; else -> KeyPath.WEB_COMPOSE })
    return r
}

/**
 * The state's keymap, then [defaultBindings]: the first binding for [chord] whose command returns
 * true. [altGrChar]: the key typed a character. Windows reports AltGr as Ctrl+Alt, so off Apple a
 * Ctrl+Alt chord that typed a character matches only a binding that names `Ctrl-Alt` explicitly,
 * never a `Mod-Alt` one (which would eat the `@` of a German layout).
 */
internal fun runBindings(view: EditorView, chord: KeyChord, apple: Boolean, altGrChar: Boolean = false): Boolean {
    val altGr = !apple && altGrChar && chord.ctrl && chord.alt
    fun matches(b: KeyBinding) = b.chord(apple) == chord && (!altGr || b.key.contains("Ctrl"))
    return view.runningCommand(key = true) {
        view.state.facet(keymapFacet).any { b -> matches(b) && b.command.run(view) } ||
            defaultBindings(apple).any { b -> matches(b) && b.command.run(view) }
    }
}

/** A key as the debug log names it: its chord name, a modifier's name, else its code. */
internal fun keyLabel(key: Key): String = keyName(key) ?: OTHER_KEY_LABELS[key] ?: "key 0x${key.keyCode.toString(16)}"

private val OTHER_KEY_LABELS: Map<Key, String> = mapOf(
    Key.ShiftLeft to "Shift", Key.ShiftRight to "Shift", Key.CtrlLeft to "Ctrl", Key.CtrlRight to "Ctrl",
    Key.AltLeft to "Alt", Key.AltRight to "Alt", Key.MetaLeft to "Meta", Key.MetaRight to "Meta",
    Key.CapsLock to "CapsLock", Key.Function to "Fn", Key.NumLock to "NumLock", Key.ScrollLock to "ScrollLock",
    Key.Back to "Back", Key.Menu to "Menu", Key.VolumeUp to "VolumeUp", Key.VolumeDown to "VolumeDown",
    Key.LanguageSwitch to "LanguageSwitch", Key.Unknown to "Unknown",
)

/** A key's modifier bits for [webKeyDown]. */
internal object KeyFlags {
    const val CTRL = 1
    const val META = 2
    const val ALT = 4
    const val SHIFT = 8
    /** The IME is composing (`isComposing`, or the key code 229 browsers use for it). */
    const val COMPOSING = 16
}

/** What the web's DOM key-down listener does with a key ([webKeyDown]). */
internal object WebKey {
    /** Not the surface's: Compose for the web sees it as usual. */
    const val PASS = 0
    /** Done here, in the DOM event: the event is cancelled (preventDefault) and stopped. */
    const val HANDLED = 1
    /** Mod-c/x/v: hidden from Compose (stopped) but not cancelled, so the browser raises its
     * copy/cut/paste event, which the surface serves with the whole selection. */
    const val BROWSER = 2
}

/**
 * The web's key path ([installFastTyping]): Compose for the web handles DOM input at the next
 * animation frame, after that frame drew, so a key it handles is painted two frames late. A
 * hardware key the surface can serve is therefore served inside the DOM event itself:
 * - a chord bound in the state's keymap or [defaultBindings] (Backspace, Enter, arrows, Tab,
 *   Mod-a, a plugin's binding) runs its command;
 * - Mod-c / Mod-x / Mod-v go to the browser's clipboard events ([WebKey.BROWSER]);
 * - a plain printable character (no Ctrl/Meta, no Alt off Apple, where Alt is a shortcut modifier
 *   rather than a character layer) is typed through [EditorView.typeText].
 * Never while an IME composes, never for a view without focus, and typing never into a read-only
 * one. A dead key reports "Dead" and goes the ordinary way.
 */
internal fun webKeyDown(view: EditorView, composing: Boolean, key: String, code: String, flags: Int, apple: Boolean = isApplePlatform): Int {
    if (!view.focused || composing || flags and KeyFlags.COMPOSING != 0) return WebKey.PASS
    val ctrl = flags and KeyFlags.CTRL != 0
    val meta = flags and KeyFlags.META != 0
    val alt = flags and KeyFlags.ALT != 0
    val shift = flags and KeyFlags.SHIFT != 0
    val single = key.length == 1 || (key.length == 2 && key[0].isHighSurrogate() && key[1].isLowSurrogate())
    val printable = single && key[0].code >= 0x20 && key[0].code != 0x7F
    val name = domKeyName(code) ?: when {
        key == " " -> "Space"
        printable -> key.lowercase()
        key.length > 1 && key != "Dead" && key != "Unidentified" && key != "Process" -> key
        else -> null
    }
    val mod = if (apple) meta else ctrl
    if (name != null && mod && !alt && (name == "c" || name == "x" || name == "v")) return WebKey.BROWSER
    if (name != null) {
        val chord = KeyChord(name, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
        if (runBindings(view, chord, apple, altGrChar = printable)) return WebKey.HANDLED
    }
    if (!printable || ctrl || meta || (alt && !apple) || view.readOnly) return WebKey.PASS
    view.typeText(key)
    return WebKey.HANDLED
}

/** A DOM `KeyboardEvent.code` as the key name [KeyChord] uses (the physical key, like [keyName]). */
internal fun domKeyName(code: String): String? = when {
    code.startsWith("Key") && code.length == 4 -> code.substring(3).lowercase()
    code.startsWith("Digit") && code.length == 6 -> code.substring(5)
    else -> DOM_KEY_NAMES[code]
}

private val DOM_KEY_NAMES = mapOf(
    "Space" to "Space", "Minus" to "-", "Equal" to "=", "BracketLeft" to "[", "BracketRight" to "]",
    "Backslash" to "\\", "Semicolon" to ";", "Quote" to "'", "Backquote" to "`", "Comma" to ",",
    "Period" to ".", "Slash" to "/", "NumpadAdd" to "+", "NumpadSubtract" to "-",
)

// Never the hidden field's: its history and its clipboard know only its window. (The clipboard chords
// are bound in defaultBindings; they are listed here too, so a binding that declines them still
// keeps them from the field.)
private val SWALLOWED = listOf("Mod-z", "Mod-Shift-z", "Mod-y", "Mod-c", "Mod-x", "Mod-v", "Mod-Shift-v")
private val swallowedApple = SWALLOWED.map { KeyChord.parse(it, true) }.toSet()
private val swallowedOther = SWALLOWED.map { KeyChord.parse(it, false) }.toSet()
private fun swallowedChords(apple: Boolean) = if (apple) swallowedApple else swallowedOther

// ---------------------------------------------------------------------- the field --------

/**
 * The invisible text field: the surface's focus target and the IME's session. It draws nothing
 * (its decorator never places the inner text field); it sits at the caret, one pixel, so the
 * platform anchors candidate windows there (the surface places it: see `surfaceMeasurePolicy`).
 *
 * MultiLine, not SingleLine: iOS delivers Return as an inserted "\n", which a single-line field
 * turns into an IME action and drops (terminal-compose's lesson).
 */
@Composable
internal fun EditorInputField(controller: EditorController, readOnly: Boolean, focus: Modifier = Modifier) {
    val sync = controller.fieldSync
    val field = remember(sync) { sync.initialField().let { TextFieldState(it.text, TextRange(it.selStart, it.selEnd)) } }
    // While the input transformation runs, the field cannot be edited: a write is kept for it to apply.
    val inEdit = remember(sync) { arrayOfNulls<FieldText>(1) to BooleanArray(1) }
    val transformation = remember(sync, field) {
        InputTransformation {
            val (pending, active) = inEdit
            active[0] = true
            pending[0] = null
            try {
                val sel = selection
                // The user's edit reaches the document NOW, inside the input event: the next frame shows it.
                sync.onFieldChange(asCharSequence().toString(), sel.start, sel.end, field.composition?.let { it.min until it.max }, deferRewindow = true)
                    ?.let { pending[0] = it }
                pending[0]?.let { u ->
                    if (!asCharSequence().contentEquals(u.text)) replace(0, length, u.text)
                    selection = TextRange(u.selStart, u.selEnd)
                    syncPlatformField(controller, u)
                }
            } finally {
                active[0] = false
                pending[0] = null
            }
        }
    }
    DisposableEffect(sync, field) {
        val write = { u: FieldText ->
            val (pending, active) = inEdit
            if (active[0]) pending[0] = u
            else {
                field.edit {
                    if (!asCharSequence().contentEquals(u.text)) replace(0, length, u.text)
                    selection = TextRange(u.selStart, u.selEnd)
                }
                if (!controller.composing) syncPlatformField(controller, u)
            }
        }
        controller.fieldWriter = write
        val remove = controller.view.addListener { sync.onStateChange()?.let(write) }
        // Whatever happened before this field existed (a replaced state, a first dispatch).
        sync.onStateChange()?.let(write)
        onDispose {
            remove()
            if (controller.fieldWriter === write) controller.fieldWriter = null
        }
    }
    LaunchedEffect(sync, field) {
        snapshotFlow { Triple(field.text.toString(), field.selection, field.composition) }.collect { (text, sel, comp) ->
            controller.composing = comp != null
            sync.onFieldChange(text, sel.start, sel.end, comp?.let { it.min until it.max })?.let { u ->
                field.edit {
                    if (!asCharSequence().contentEquals(u.text)) replace(0, length, u.text)
                    selection = TextRange(u.selStart, u.selEnd)
                }
                if (comp == null) syncPlatformField(controller, u)
            }
            controller.composition = sync.composition
        }
    }
    // A touch's keyboard request: once the field is focused and has its session, ask the platform
    // too (the session raises the keyboard only when it starts; a dismissed one needs asking).
    val showKeyboard = rememberPlatformKeyboardShow()
    val request = controller.keyboardRequests
    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        if (controller.focusAfterRequest) {
            // This composition carries showKeyboardOnFocus = true: now the focus starts the session.
            controller.focusAfterRequest = false
            controller.requestFocus()
        }
        if (showKeyboard == null) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(1000) { snapshotFlow { controller.view.focused }.first { it } } ?: return@LaunchedEffect
        // Two frames for the session to exist, then ask the platform (a dismissed keyboard).
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        showKeyboard()
        androidx.compose.runtime.withFrameNanos { }
        platformAfterKeyboardShown()
    }
    // A screen reader reads the SURFACE (its visible text, see editorSemantics): this field holds
    // only a window of text around the caret. Cleared, not merely hidden: iOS and the web ignore
    // hideFromAccessibility and showed it as a second, unlabelled text element. The IME does not
    // use semantics; it stays the input target.
    val expose = LocalEditorExposeField.current
    Box(focus.then(if (expose) Modifier.semantics { hideFromAccessibility() } else Modifier.clearAndSetSemantics { })) {
    BasicTextField(
        state = field,
        // Placed at the caret by the surface's layout pass (it knows where the caret is this frame).
        modifier = Modifier
            .size(1.dp)
            .focusRequester(controller.focusRequester)
            .then(LocalEditorFieldPointerSpy.current?.let { spy -> Modifier.pointerInput(spy) { awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); spy() } } } } ?: Modifier),
        readOnly = readOnly,
        inputTransformation = transformation,
        keyboardOptions = KeyboardOptions(
            // Code, not prose: nothing is capitalized, and Return is a line break, not an action.
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
            // Only a touch raises the keyboard on Android and iOS; a host's programmatic focus, a
            // mouse click or a tab switch never does (see EditorController.keyboardOnFocus).
            showKeyboardOnFocus = controller.keyboardOnFocus,
        ),
        lineLimits = TextFieldLineLimits.MultiLine(1, 1),
        decorator = { Box(Modifier.size(1.dp)) },
    )
    }
}

/** Tests: called for every pointer event that reaches the hidden field (it must never be called). */
internal val LocalEditorFieldPointerSpy = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
