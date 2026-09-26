package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
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
 * The smallest single replacement turning [before] into [after]. When repeated characters make the
 * split ambiguous, the common prefix is capped at the caret, so the edit lands where the user typed.
 */
fun diffField(before: String, after: String, cursorAfter: Int = after.length): FieldEdit? {
    if (before == after) return null
    val maxPrefix = minOf(before.length, after.length)
    var p = 0
    while (p < maxPrefix && before[p] == after[p]) p++
    val growth = after.length - before.length
    if (growth > 0) p = minOf(p, maxOf(0, cursorAfter - growth))
    var s = 0
    while (s < before.length - p && s < after.length - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
    return FieldEdit(p, before.length - s, after.substring(p, after.length - s))
}

/** The slice of the document the hidden field holds: [text] starts at document offset [base]. */
data class FieldWindow(val base: Int, val text: String) {
    val end: Int get() = base + text.length

    fun applyTo(doc: String, e: FieldEdit): String = doc.replaceRange(base + e.from, base + e.to, e.insert)

    companion object {
        /**
         * About [radius] units each side of [cursor], preferring whole lines (so autocorrect sees
         * whole words): an edge moves out to its line's boundary when that stays within twice the
         * radius, else in to a line boundary, else to a word boundary; never inside a surrogate pair.
         */
        fun around(doc: Rope, cursor: Int, radius: Int): FieldWindow {
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
            return FieldWindow(start, doc.slice(start, end))
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
        return FieldText(w.text, (main.anchor - w.base).coerceIn(0, w.text.length), (main.head - w.base).coerceIn(0, w.text.length))
    }

    fun onFieldChange(text: String, selStart: Int, selEnd: Int, composition: IntRange?, deferRewindow: Boolean = false): FieldText? {
        if (!deferRewindow) return apply(text, selStart, selEnd, composition, false)
        deferring = true
        try { return apply(text, selStart, selEnd, composition, true) } finally { deferring = false }
    }

    /** True while an input event's edit is applied: no re-windowing at an edge then. */
    private var deferring = false

    private fun apply(text: String, selStart: Int, selEnd: Int, composition: IntRange?, deferRewindow: Boolean): FieldText? {
        val before = shown
        shown = FieldText(text, selStart, selEnd)
        if (view.readOnly) return if (text != window.text) show(current()) else null
        val wasComposing = this.composition != null
        val w = window
        if (!deferRewindow) this.composition = composition?.let { (w.base + it.first) until (w.base + it.last + 1) }
        if (text == w.text) {
            // Only the caret moved (an IME cursor gesture): follow it, unless mid-composition. The
            // echo of a selection this class wrote (clamped to the window) is not a move.
            val moved = before != null && (before.selStart != selStart || before.selEnd != selEnd)
            if (composition == null && moved) {
                val sel = SelectionRange(w.base + selStart, w.base + selEnd)
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
        val e = diffField(w.text, text, selEnd) ?: return null
        val from = w.base + e.from
        val to = w.base + e.to
        val st = view.state
        val sel = st.selection
        val main = sel.main
        val clampedFrom = main.from.coerceIn(w.base, w.end)
        val clampedTo = main.to.coerceIn(w.base, w.end)
        val event = if (composition != null || wasComposing) "input.ime" else "input"
        when {
            // A soft Return: the editor's newline (it keeps the indentation), at every cursor.
            composition == null && !wasComposing && e.insert == "\n" && e.from == e.to && from == main.head && main.empty ->
                DefaultCommands.insertNewline.run(view)
            // Several cursors: typing at the main one types at all of them.
            sel.ranges.size > 1 && from == clampedFrom && to == clampedTo && composition == null -> {
                val specs = sel.ranges.map { ChangeSpec(it.from, it.to, e.insert) }
                val changes = dev.supermux.editor.core.ChangeSet.of(st.doc.length, specs)
                val next = sel.ranges.map { SelectionRange(changes.mapPos(it.to, 1)) }
                view.dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, sel.mainIndex), scrollIntoView = true, userEvent = event))
            }
            // Several cursors, a soft Backspace at the main one: at all of them.
            sel.ranges.size > 1 && e.insert.isEmpty() && main.empty && to == main.head && composition == null ->
                DefaultCommands.deleteBackward.run(view)
            // Typing over a selection that reaches past the window replaces all of it.
            !main.empty && from == clampedFrom && to == clampedTo && (main.from < w.base || main.to > w.end) ->
                view.dispatch(TransactionSpec(
                    changes = listOf(ChangeSpec(main.from, main.to, e.insert)),
                    selection = EditorSelection.cursor(main.from + e.insert.length),
                    scrollIntoView = true, userEvent = event,
                ))
            else -> {
                // The ordinary case: the field's edit, and the field's caret.
                window = FieldWindow(w.base, text)
                view.dispatch(TransactionSpec(
                    changes = listOf(ChangeSpec(from, to, e.insert)),
                    selection = EditorSelection.single(w.base + selStart, w.base + selEnd),
                    scrollIntoView = true, userEvent = event,
                ))
                if (!deferRewindow && composition == null && nearEdge()) return rewindow()
                return null
            }
        }
        // A command ran instead of the field's own edit: the listener has re-synced the field.
        return null
    }

    /** After every transaction: null while the field still shows the document; else what it must show. */
    fun onStateChange(): FieldText? {
        val w = window
        val main = view.state.selection.main
        val inside = main.from >= w.base && main.to <= w.end
        if (!docMatches(w) || !inside) return rewindow()
        if (composition == null && !deferring && nearEdge()) return rewindow()
        val now = current()
        return if (now == shown) null else show(now)
    }

    /** Rebuild the window around the main cursor (the composition is gone with the old text). */
    fun rewindow(): FieldText {
        window = window()
        composition = null
        return show(current())
    }

    private fun window(): FieldWindow = FieldWindow.around(view.state.doc, view.state.selection.main.head, radius)

    private fun docMatches(w: FieldWindow): Boolean {
        val doc = view.state.doc
        return w.end <= doc.length && doc.slice(w.base, w.end) == w.text
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
}

/**
 * A hardware key-down through the state's keymap facet ([runKey]), then the surface's own
 * [defaultKeymap] bindings as the lowest-precedence fallback (a state needs no keymap of its own
 * to be editable; a plugin overrides a default by binding the same key). Unbound keys return false
 * and reach the hidden field, which is where typed characters come from. While the IME composes,
 * every key is the IME's (Enter picks a candidate, Backspace edits the preedit).
 *
 * The field's own undo/redo chords are swallowed: its private history knows only the window it
 * held, and replaying it would edit the document behind the editor's back (history is M4's).
 */
internal fun handleEditorKey(view: EditorView, event: KeyEvent, composing: Boolean): Boolean {
    if (event.type != KeyEventType.KeyDown || composing) return false
    val name = keyName(event.key) ?: return false
    val chord = KeyChord(name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed, meta = event.isMetaPressed)
    val apple = isApplePlatform
    if (runKey(view, chord, apple)) return true
    for (b in defaultBindings(apple)) if (b.chord(apple) == chord && b.command.run(view)) return true
    return swallowedChords(apple).contains(chord)
}

/** A key's modifier bits for [fastTypeKey]. */
internal object KeyFlags {
    const val CTRL = 1
    const val META = 2
    const val ALT = 4
    const val SHIFT = 8
    /** The IME is composing (`isComposing`, or the key code 229 browsers use for it). */
    const val COMPOSING = 16
}

/**
 * The web's fast path for a DOM key-down ([installFastTyping]): true when it typed [key] into
 * [view] itself. Only a plain printable character, typed into the focused, editable view, with no
 * composition in progress, no Ctrl/Meta (and no Alt outside Apple platforms, where Alt is a
 * shortcut modifier rather than a character layer), and no key binding for that chord (a
 * plugin's binding runs through the ordinary key path instead). A dead key reports "Dead", a
 * named key its name: neither is one character, so both go the ordinary way too.
 */
internal fun fastTypeKey(view: EditorView, composing: Boolean, key: String, code: String, flags: Int, apple: Boolean = isApplePlatform): Boolean {
    if (!view.focused || view.readOnly || composing || flags and KeyFlags.COMPOSING != 0) return false
    if (flags and (KeyFlags.CTRL or KeyFlags.META) != 0) return false
    val alt = flags and KeyFlags.ALT != 0
    if (alt && !apple) return false
    val single = key.length == 1 || (key.length == 2 && key[0].isHighSurrogate() && key[1].isLowSurrogate())
    if (!single || key[0].code < 0x20 || key[0].code == 0x7F) return false
    domKeyName(code)?.let { name ->
        val chord = KeyChord(name, alt = alt, shift = flags and KeyFlags.SHIFT != 0)
        if (view.state.facet(keymapFacet).any { it.chord(apple) == chord }) return false
        if (defaultBindings(apple).any { it.chord(apple) == chord }) return false
    }
    return DefaultCommands.insertText(key).run(view)
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
    "Period" to ".", "Slash" to "/",
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
 * platform anchors candidate windows there.
 *
 * MultiLine, not SingleLine: iOS delivers Return as an inserted "\n", which a single-line field
 * turns into an IME action and drops (terminal-compose's lesson).
 */
@Composable
internal fun EditorInputField(controller: EditorController, readOnly: Boolean) {
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
            else field.edit {
                if (!asCharSequence().contentEquals(u.text)) replace(0, length, u.text)
                selection = TextRange(u.selStart, u.selEnd)
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
            }
            controller.composition = sync.composition
        }
    }
    BasicTextField(
        state = field,
        modifier = Modifier
            .offset { controller.caretRectOnScreen(controller.view.state.selection.main.head).let { IntOffset(it.left.toInt(), it.top.toInt()) } }
            .size(1.dp)
            .focusRequester(controller.focusRequester),
        readOnly = readOnly,
        inputTransformation = transformation,
        keyboardOptions = KeyboardOptions(
            // Code, not prose: nothing is capitalized, and Return is a line break, not an action.
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
        lineLimits = TextFieldLineLimits.MultiLine(1, 1),
        decorator = { Box(Modifier.size(1.dp)) },
    )
}
