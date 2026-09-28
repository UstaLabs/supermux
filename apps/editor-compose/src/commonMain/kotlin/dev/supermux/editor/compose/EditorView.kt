package dev.supermux.editor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.SelectionRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One open editor: its current [EditorState], the way to change it ([dispatch]), and what the
 * surface reports back (the [viewport], [focused]).
 *
 * The host owns it (usually through [rememberEditorView]) and shows it with `Editor(view)`.
 * Plugins and commands only ever see it as a [CommandTarget]: a state plus `dispatch(spec)`.
 *
 * **Threading.** [dispatch] runs on the UI thread. A background producer (the syntax worker) hops
 * its dispatches onto the UI thread, in order.
 *
 * **Listeners** run synchronously inside [dispatch], after the state is replaced, for every
 * transaction: that is where the host hands `tr.state` to a syntax worker.
 */
@Stable
class EditorView(initial: EditorState) : CommandTarget {
    private var current: EditorState by mutableStateOf(initial)
    private var listeners: List<(Transaction) -> Unit> = emptyList()
    private val viewportFlow = MutableStateFlow(IntRange.EMPTY)

    override val state: EditorState get() = current

    /**
     * UTF-16 `[start, end)` of the lines the surface lays out (the visible ones plus overscan), as
     * `start until end`; empty before the first paint. Updated at most once per frame.
     */
    val viewport: StateFlow<IntRange> = viewportFlow.asStateFlow()

    /** True while the surface holds the keyboard focus (mirrors Compose focus). */
    var focused: Boolean by mutableStateOf(false)

    /**
     * No user edits: typing, deleting and pasting (`input*`, `delete*`, `paste*`, `undo`, `redo`)
     * are dropped. Programmatic changes (a disk reload, LSP, plugins' effects) still apply, and the
     * selection still moves. Set by the surface from `Editor(readOnly = ...)`.
     */
    var readOnly: Boolean = false

    /**
     * The zoomed font size in sp, or null for the theme's own ([EditorTheme.fontSizeSp]). The zoom
     * keys (`Mod +` / `Mod −` / `Mod 0`) and a two-finger pinch set it, within [EditorZoom.MIN] and
     * [EditorZoom.MAX]; `Editor(onFontSize = …)` hears every change so the host can keep it. A host
     * restoring a kept size sets it here.
     */
    var fontSize: Float? by mutableStateOf(null)

    /** The theme's size (set by the surface): what [fontSize] null means, and `Mod 0` goes back to. */
    internal var baseFontSize: Float = EditorZoom.DEFAULT

    /** `Editor(onFontSize = …)`. */
    internal var onFontSize: ((Float) -> Unit)? = null

    /** The size `fontSizeFacet` (a host's setting) asks for, as the surface last saw it; null: none. */
    internal var settingFontSize: Float? = null

    /** The size the surface draws with now. */
    val effectiveFontSize: Float get() = fontSize ?: settingFontSize ?: baseFontSize

    /**
     * Zoom to [size] sp (clamped to [EditorZoom.MIN]..[EditorZoom.MAX]); [report] tells the host
     * (a pinch reports once, when the fingers lift). Returns the size now shown.
     */
    fun zoomTo(size: Float, report: Boolean = true): Float {
        val s = size.coerceIn(EditorZoom.MIN, EditorZoom.MAX)
        if (s != effectiveFontSize) fontSize = s
        if (report) onFontSize?.invoke(s)
        return s
    }

    /** Back to the theme's size (`Mod 0`), also over a size the settings chose. */
    fun resetZoom() {
        val setting = settingFontSize
        fontSize = if (setting != null && setting != baseFontSize) baseFontSize else null
        onFontSize?.invoke(baseFontSize)
    }

    /**
     * The web only: whether a key-down comes from a hardware keyboard (served inside the DOM event)
     * or from a soft keyboard (left to the hidden field, for autocorrect and predictions).
     * [WebKeyboard.AUTO] is a heuristic (see the README); a host or a debug menu can force it.
     *
     * Debug API: an override for device passes and debug menus while the heuristic is tuned. It may
     * change or go away; production hosts leave it at [WebKeyboard.AUTO].
     */
    var webKeyboard: WebKeyboard = WebKeyboard.AUTO

    /**
     * Debug: told the path every key-down took ([KeyPath]) with its key name, so a device pass
     * can check which keys went where. Null (the default) costs nothing.
     *
     * Debug API: for device passes and debug screens, not a stable hook; [KeyPath] may change.
     */
    var onKeyPath: ((key: String, path: KeyPath) -> Unit)? = null

    /**
     * A click or tap on a gutter marker column that no plugin's [gutterClickFacet] handler took:
     * the column id, the 0-based line, and the marker there (null for an empty cell). A marker's
     * accessibility action (its tooltip is its label) reports here too.
     */
    var onGutterClick: ((column: String, line: Int, marker: dev.supermux.editor.core.GutterMarker?) -> Unit)? = null

    /**
     * A click or tap on a drawn placeholder chip (a fold's "⋯") that no [widgetClickFacet] handler
     * took: the widget's key and the decoration's range.
     */
    var onWidgetClick: ((key: dev.supermux.editor.core.WidgetKey, from: Int, to: Int) -> Unit)? = null

    /** Where copy and cut put text and paste takes it from; set by the surface (`Editor(clipboard = …)`). */
    internal var clipboard: EditorClipboard? = null

    /** A scope on the UI thread for work a command cannot finish at once (reading the clipboard). */
    internal var scope: CoroutineScope? = null

    /**
     * Paste [text] (userEvent `paste`): with as many cursors as [text] has lines, one line at each
     * cursor (CM6's behaviour for a multi-cursor copy); otherwise all of [text] at every cursor.
     */
    fun paste(pasted: String) {
        // Line breaks are \n inside the editor (hosts convert on load and save): a pasted CRLF or
        // a lone CR becomes \n before anything counts lines.
        val text = normalizeLineBreaks(pasted)
        if (text.isEmpty()) return
        val st = state
        val ranges = st.selection.ranges
        val lines = text.split('\n')
        val specs = ranges.mapIndexed { i, r -> ChangeSpec(r.from, r.to, if (ranges.size > 1 && lines.size == ranges.size) lines[i] else text) }
        val changes = ChangeSet.of(st.doc.length, specs)
        val next = ranges.map { SelectionRange(changes.mapPos(it.to, 1)) }
        dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, st.selection.mainIndex), scrollIntoView = true, userEvent = "paste"))
    }

    /**
     * THE entry point for typed text (the hidden field, the web's key path, [DefaultCommands.insertText]
     * all come here): [text] replaces every selection range, and the cursors land after it. For
     * plain typing (`input`) the [inputHandlerFacet] handlers are asked first. Returns true when
     * something was dispatched.
     */
    fun typeText(text: String, userEvent: String = "input"): Boolean {
        val spec = typeSpec(text, userEvent, 0, 0, text.length, text.length) ?: return true
        dispatch(spec.first)
        return true
    }

    /**
     * The transaction typing [text] makes: each range [from - before, to + after) becomes [text],
     * with the new range at [anchorInText]..[headInText] inside it. Null when an input handler took
     * the text over (it dispatched its own). The change set is returned too, so the hidden field
     * can follow its window through it before listeners run.
     */
    internal fun typeSpec(text: String, userEvent: String, before: Int, after: Int, anchorInText: Int, headInText: Int): Pair<TransactionSpec, ChangeSet>? {
        val st = state
        val sel = st.selection
        val main = sel.main
        if (userEvent == "input" && !readOnly) {
            val from = (main.from - before).coerceAtLeast(0)
            val to = (main.to + after).coerceAtMost(st.doc.length)
            for (h in st.facet(inputHandlerFacet)) if (runningCommand { h.handle(this, from, to, text) }) return null
        }
        // The main range takes the edit with its extension. Another range takes the SAME extension
        // only when the text around it is the text replaced around the main range (CM6); else a
        // pure insertion goes over its own selection, a pure deletion deletes one grapheme there
        // (or its selection), and a replacement (autocorrect) leaves that range untouched. So a
        // soft Backspace of an emoji never deletes two letters elsewhere, and an autocorrect at
        // the main cursor never rewrites or inserts into another cursor's text.
        val doc = st.doc
        val mainFrom = (main.from - before).coerceAtLeast(0)
        val mainTo = (main.to + after).coerceAtMost(doc.length)
        val replacedBefore = doc.slice(mainFrom, main.from)
        val replacedAfter = doc.slice(main.to, mainTo)
        // Each range's change, and where its new selection sits inside the inserted text.
        val placed = sel.ranges.map { r ->
            val f = r.from - replacedBefore.length
            val t = r.to + replacedAfter.length
            when {
                r === main -> Triple(ChangeSpec(mainFrom, mainTo, text), anchorInText, headInText)
                f >= 0 && t <= doc.length && doc.slice(f, r.from) == replacedBefore && doc.slice(r.to, t) == replacedAfter ->
                    Triple(ChangeSpec(f, t, text), anchorInText, headInText)
                text.isEmpty() && r.empty && before > 0 ->
                    Triple(ChangeSpec(TextBoundaries.prevGrapheme(doc, r.head), r.head), 0, 0)
                text.isEmpty() && r.empty && after > 0 ->
                    Triple(ChangeSpec(r.head, TextBoundaries.nextGrapheme(doc, r.head)), 0, 0)
                text.isNotEmpty() && (before > 0 || after > 0) -> null // a replacement that does not apply here
                else -> Triple(ChangeSpec(r.from, r.to, text), text.length, text.length)
            }
        }
        val specs = placed.mapNotNull { it?.first }
        val merged = ArrayList<ChangeSpec>()
        for (sp in specs.sortedWith(compareBy({ it.from }, { it.to }))) {
            val last = merged.lastOrNull()
            if (last != null && sp.from < last.to) merged[merged.size - 1] = ChangeSpec(last.from, maxOf(last.to, sp.to), last.insert)
            else merged += sp
        }
        val changes = ChangeSet.of(st.doc.length, merged)
        val next = sel.ranges.mapIndexed { i, r ->
            val p = placed[i] ?: return@mapIndexed r.map(changes) // untouched: only moved by the others' edits
            val start = changes.mapPos(p.first.from, -1)
            SelectionRange(start + p.second, start + p.third)
        }
        return TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, sel.mainIndex), scrollIntoView = true, userEvent = userEvent) to changes
    }

    /** Scopes this view's widgets' slots and saved state (a draft in one document never shows in another). */
    internal val widgetStateId: Long = nextWidgetStateId++

    private val ownScroll = EditorScrollState()

    /** The scroll state the surface uses: this view's own, or the one passed to `Editor(scrollState = …)`. */
    var scrollState: EditorScrollState = ownScroll
        internal set

    /** The default for `Editor(scrollState)`. */
    internal val defaultScrollState: EditorScrollState get() = ownScroll

    /** A position given before the surface could apply it (not composed yet). */
    internal var pendingScroll: EditorScrollPosition? = null

    /** Where the view is scrolled, by document position (save it with a tab, restore it later). */
    val scrollPosition: EditorScrollPosition
        get() = surface?.scrollPosition() ?: pendingScroll ?: EditorScrollPosition(0)

    /** Scroll back to [position]; before the first paint, it is applied then. */
    fun restoreScroll(position: EditorScrollPosition) {
        val s = surface
        if (s == null) pendingScroll = position else s.restoreScroll(position)
    }

    /**
     * Take the keyboard focus; [showKeyboard] also raises a soft keyboard (a host focusing an editor
     * on its own must not). False while nothing shows this view.
     */
    fun focus(showKeyboard: Boolean = false): Boolean = surface?.focus(showKeyboard) ?: false

    /**
     * The caret rect at [offset] in the surface's own pixels (for a popup, a completion list), or
     * null while nothing shows this view.
     */
    fun coordsAtPos(offset: Int): androidx.compose.ui.geometry.Rect? = surface?.coordsAtPos(offset)

    /** The surface's geometry once it is composed; vertical moves and page moves need it. */
    internal var geometry: Geometry? = null

    /** The composed surface's hooks (height map, scrolling), null while nothing shows this view. */
    internal var surface: EditorSurfaceHooks? = null

    /** The goal x of each range for consecutive vertical moves, valid while the selection is [Goal.selection]. */
    internal var goal: Goal? = null

    internal class Goal(val selection: EditorSelection, val xs: List<Float>)

    /** While > 0 the view is running a command (a key binding, an input handler, a menu item, a click handler). */
    private var commandDepth = 0
    /** While > 0 the command running is a key binding's. */
    private var keyDepth = 0

    /**
     * Run [block] as a command the user triggered ([key]: through a key binding): every dispatch it
     * makes is LOCAL input for the replaced-range rules, whatever its userEvent (or lack of one), unless
     * that userEvent is exempt or the spec carries [EditorAnnotations.remote].
     */
    internal fun <T> runningCommand(key: Boolean = false, block: () -> T): T {
        commandDepth++
        if (key) keyDepth++
        try {
            return block()
        } finally {
            commandDepth--
            if (key) keyDepth--
        }
    }

    override fun dispatch(spec: TransactionSpec) {
        val userEdit = isUserEdit(spec)
        if (readOnly && userEdit) return
        val start = current
        var tr = start.update(spec)
        // Debug assertion: a key-bound command's edit should say what it is (history groups by it).
        if (tr.docChanged && keyDepth > 0 && spec.userEvent == null && !isRemote(spec) && tr.annotation(EditorAnnotations.atomicWhole) != true) {
            EditorDiagnostics.reportUnlabeledCommandEdit(tr.changes.toString())
        }
        // The hidden field's U+FFFC placeholder (a fold in its window) never becomes document text.
        if (tr.docChanged && tr.annotation(EditorAnnotations.fieldInput) == true && insertsPlaceholder(tr)) return
        // Replaced ranges (folds): local input never takes a piece of one, whatever path it came by
        // (the hidden field, typeText, paste, a key command, the web's key path).
        if (tr.docChanged && policed(spec) && tr.annotation(EditorAnnotations.atomicWhole) != true) {
            if (splitAtReplaces(tr, spec)) return
        }
        // The caret never lands inside a replaced range: moved out, unless a reveal handler shows it.
        var reveal: ReplaceRange? = null
        val folds = replaced(tr.state)
        if (folds.replaces.isNotEmpty()) {
            val inside = tr.state.selection.ranges.firstNotNullOfOrNull { r -> folds.replaceInside(r.head) ?: folds.replaceInside(r.anchor) }
            if (inside != null) {
                if (tr.selectionSet && tr.scrollIntoView && tr.state.facet(revealFacet).isNotEmpty()) reveal = inside
                else tr = start.update(spec.copy(selection = clampOut(tr, folds)))
            }
        }
        current = tr.state
        surface?.onTransaction(tr)
        for (l in listeners) l(tr)
        if (tr.scrollIntoView) surface?.scrollIntoView()
        if (reveal != null && !revealRange(reveal.from, reveal.to)) {
            // Nobody showed it after all: out to its edge.
            val f = replaced(current)
            if (current.selection.ranges.any { f.replaceInside(it.head) != null || f.replaceInside(it.anchor) != null }) {
                dispatch(TransactionSpec(selection = clampOut(current.update(TransactionSpec()), f), scrollIntoView = true, userEvent = "select"))
            }
        }
    }

    private val foldCache = Folds.Cache()
    private var foldsOf: Pair<Any, Folds>? = null

    /** [state]'s replaced ranges (cached by its decorations and document). */
    internal fun replaced(state: EditorState): Folds {
        val decos = state.facet(dev.supermux.editor.core.decorationsFacet)
        val key = decos to state.doc
        foldsOf?.let { (k, f) -> if (k is Pair<*, *> && k.first === decos && k.second === state.doc) return f }
        val f = Folds.of(state, foldCache)
        foldsOf = key to f
        return f
    }

    /** The shared per-decoration-set extraction (the surface builds its own [Folds] from it too). */
    internal val sharedFoldCache: Folds.Cache get() = foldCache

    /**
     * Whether [spec] is LOCAL input or a command (policed by the replaced-range rules): not
     * [EditorAnnotations.remote], no userEvent of [POLICY_EXEMPT] (`undo`, `redo`, `disk`, `remote`,
     * `agent`, `lsp` and their sub-events), and either some other userEvent or dispatched while a
     * command runs ([runningCommand]: a key binding, an input handler, a menu item, a plugin's click
     * handler), whatever its userEvent. A transaction without a userEvent from outside any command is
     * programmatic (a host's, a plugin's effect) and passes.
     */
    private fun policed(spec: TransactionSpec): Boolean {
        if (isRemote(spec)) return false
        val e = spec.userEvent ?: return commandDepth > 0
        return POLICY_EXEMPT.none { e == it || e.startsWith("$it.") }
    }

    private fun isRemote(spec: TransactionSpec) = spec.annotations.any { it.type === EditorAnnotations.remote && it.value == true }

    /** A field edit inserting more U+FFFC than the text it replaces held (a leaked placeholder). */
    private fun insertsPlaceholder(tr: Transaction): Boolean {
        for (c in tr.changes.iterChanges()) {
            val n = c.inserted.count { it == FieldWindow.PLACEHOLDER }
            if (n == 0) continue
            if (n > tr.startState.doc.slice(c.fromA, c.toA).count { it == FieldWindow.PLACEHOLDER }) return true
        }
        return false
    }

    /**
     * The replaced-range rules for local input, per change (so per cursor):
     * - a change that deletes part of a NON-atomic Replace grows to take all of it (a hidden range
     *   is never deleted one character at a time, on any input path);
     * - a change that reaches into an ATOMIC range (a fold, `atomicRangesFacet`) is dropped, and that
     *   range goes through the policy ([deleteInto]); the other cursors' changes still apply.
     * A range a selection covered whole is the user's to delete. Returns true when it dispatched
     * (or dropped) instead of [tr].
     */
    private fun splitAtReplaces(tr: Transaction, spec: TransactionSpec): Boolean {
        val st = tr.startState
        val folds = replaced(st)
        val extra = st.facet(dev.supermux.editor.core.atomicRangesFacet)
        if (folds.replaces.isEmpty() && extra.all { it.isEmpty }) return false
        val sel = st.selection.ranges
        fun covered(from: Int, to: Int) = sel.any { it.from <= from && it.to >= to && it.from < it.to }
        val kept = ArrayList<ChangeSpec>()
        val dropped = ArrayList<Pair<dev.supermux.editor.core.Change, Pair<Int, Int>>>()
        val grown = ArrayList<Pair<Int, Int>>() // the grown changes' spans (start coordinates)
        var touched = false
        for (c in tr.changes.iterChanges()) {
            if (c.toA <= c.fromA) { kept += ChangeSpec(c.fromA, c.toA, c.inserted); continue }
            var atomic: Pair<Int, Int>? = null
            var a = c.fromA
            var b = c.toA
            for (r in folds.replacesInside(c.fromA, c.toA)) {
                if (covered(r.from, r.to)) continue
                if (r.atomic) { atomic = r.from to r.to; break }
                a = minOf(a, r.from); b = maxOf(b, r.to)
            }
            if (atomic == null) for (set in extra) for (r in set.between(c.fromA, c.toA)) {
                if (r.from < r.to && r.from < c.toA && r.to > c.fromA && !covered(r.from, r.to)) { atomic = r.from to r.to; break }
            }
            when {
                atomic != null -> { dropped += c to atomic; touched = true }
                a != c.fromA || b != c.toA -> { kept += ChangeSpec(a, b, c.inserted); grown += a to b; touched = true }
                else -> kept += ChangeSpec(c.fromA, c.toA, c.inserted)
            }
        }
        if (!touched) return false
        // Overlapping grown changes (two cursors at one hidden range) merge.
        val merged = ArrayList<ChangeSpec>()
        for (k in kept.sortedWith(compareBy({ it.from }, { it.to }))) {
            val last = merged.lastOrNull()
            if (last != null && k.from < last.to) merged[merged.size - 1] = ChangeSpec(last.from, maxOf(last.to, k.to), last.insert + k.insert)
            else merged += k
        }
        val cs = dev.supermux.editor.core.ChangeSet.of(st.doc.length, merged)
        if (!cs.isEmpty) {
            // Each range where the edit meant it, through the changes that stay: a cursor at a dropped
            // change stays where it was, one at a grown change goes to its end, the others where [tr] put them.
            val inv = tr.changes.invert(st.doc)
            val pairIndex = tr.selection.ranges.size == sel.size
            val next = sel.mapIndexed { i, r ->
                fun at(span: Pair<Int, Int>) = r.head in span.first..span.second
                when {
                    dropped.any { (c, _) -> r.head in c.fromA..c.toA } -> r.map(cs)
                    grown.any { at(it) } -> grown.first { at(it) }.let { SelectionRange(cs.mapPos(it.second, 1)) }
                    pairIndex -> tr.selection.ranges[i].let { t -> SelectionRange(cs.mapPos(inv.mapPos(t.anchor, -1), 1), cs.mapPos(inv.mapPos(t.head, -1), 1)) }
                    else -> r.map(cs)
                }
            }
            dispatch(spec.copy(changes = emptyList(), changeSet = cs, selection = EditorSelection.create(next, st.selection.mainIndex),
                annotations = spec.annotations + EditorAnnotations.atomicWhole.of(true)))
        }
        // Each atomic range reached into: the policy, at its place in the document now.
        for ((from, to) in dropped.map { it.second }.distinct()) {
            val f = cs.mapPos(from, 1)
            val t = maxOf(f, cs.mapPos(to, -1))
            val own = dropped.filter { it.second == (from to to) }.map { (c, _) -> ChangeSpec(cs.mapPos(c.fromA, 1), maxOf(cs.mapPos(c.fromA, 1), cs.mapPos(c.toA, -1)), c.inserted) }
            deleteInto(f, t, spec.copy(changes = own, changeSet = null, selection = null))
        }
        return true
    }

    /**
     * A local edit reached into atomic range [from, to): the handlers' policy ([atomicDeleteFacet]),
     * else unfold first ([revealFacet]), else SELECT the range (a second Backspace then deletes it as
     * a selection: never a dead key, and safe without undo).
     */
    private fun deleteInto(from: Int, to: Int, spec: TransactionSpec) {
        for (h in current.facet(atomicDeleteFacet)) if (h.deleteInto(this, from, to, spec)) return
        if (revealRange(from, to)) return
        val sel = current.selection
        val ranges = sel.ranges.map { r -> if (r.head in from..to) (if (r.head == from) SelectionRange(from, to) else SelectionRange(to, from)) else r }
        dispatch(TransactionSpec(selection = EditorSelection.create(ranges, sel.mainIndex), scrollIntoView = true, userEvent = "select"))
    }

    /** Ask the [revealFacet] handlers to show [from, to) (the fold plugin unfolds). */
    private fun revealRange(from: Int, to: Int): Boolean {
        for (h in current.facet(revealFacet)) if (h.reveal(this, from, to)) return true
        return false
    }

    /**
     * [tr]'s selection with every end inside a replaced range moved out: past it the way it moved
     * (Right into a fold lands after it), else to its nearer edge (a fold made around the caret).
     */
    private fun clampOut(tr: Transaction, folds: Folds): EditorSelection {
        val sel = tr.state.selection
        val old = tr.startState.selection
        fun out(pos: Int, was: Int): Int {
            val r = folds.replaceInside(pos) ?: return pos
            return when {
                pos > was -> r.to
                pos < was -> r.from
                pos - r.from <= r.to - pos -> r.from
                else -> r.to
            }
        }
        // Each new range with the old one it came from: the nearest by position (ranges may have
        // merged, so an index says nothing).
        val olds = old.ranges.map { SelectionRange(tr.changes.mapPos(it.anchor, 1), tr.changes.mapPos(it.head, 1)) }
        val ranges = sel.ranges.map { r ->
            val o = olds.minBy { kotlin.math.abs(it.head - r.head) }
            SelectionRange(out(r.anchor, o.anchor), out(r.head, o.head))
        }
        return EditorSelection.create(ranges, sel.mainIndex)
    }

    /**
     * Replace the whole state (another file, a reload that is not an edit). Transaction listeners are
     * not called; [addReplaceListener]'s are (a syntax host starts over on the new document).
     */
    fun setState(state: EditorState) {
        current = state
        goal = null
        surface?.onStateReplaced()
        for (l in replaceListeners) l(state)
    }

    private var replaceListeners: List<(EditorState) -> Unit> = emptyList()

    /** Call [l] with the new state after every [setState]; returns the function that removes it. */
    fun addReplaceListener(l: (EditorState) -> Unit): () -> Unit {
        replaceListeners = replaceListeners + l
        return { replaceListeners = replaceListeners - l }
    }

    /** Call [l] after every transaction; returns the function that removes it. */
    fun addListener(l: (Transaction) -> Unit): () -> Unit {
        listeners = listeners + l
        return { listeners = listeners - l }
    }

    internal fun publishViewport(range: IntRange) {
        viewportFlow.value = range
    }

    private fun isUserEdit(spec: TransactionSpec): Boolean {
        val changes = spec.changes.isNotEmpty() || spec.changeSet?.isEmpty == false
        val e = spec.userEvent ?: return false
        return changes && USER_EDITS.any { e == it || e.startsWith("$it.") }
    }

    private companion object {
        val USER_EDITS = listOf("input", "delete", "paste", "undo", "redo", "drop")

        /** userEvents the local-input rules (replaced and atomic ranges) never police, with their sub-events. */
        val POLICY_EXEMPT = listOf("undo", "redo", "disk", "remote", "agent", "lsp")
        var nextWidgetStateId = 1L
    }
}

/** What the composed surface does for its view; see [EditorView.surface]. */
internal interface EditorSurfaceHooks {
    /** Follow a transaction (the height map, the scroll anchor) before any listener runs. */
    fun onTransaction(tr: Transaction)

    /** The state was replaced wholesale. */
    fun onStateReplaced()

    /** Scroll the least needed to show the main cursor with a margin. */
    fun scrollIntoView()

    /** Scroll by [dy] pixels (page moves scroll a page as well as moving the cursor). */
    fun scrollBy(dy: Float)

    /** The viewport's height in pixels. */
    val viewportHeightPx: Float

    fun scrollPosition(): EditorScrollPosition
    fun restoreScroll(position: EditorScrollPosition)
    fun focus(showKeyboard: Boolean): Boolean
    fun coordsAtPos(offset: Int): androidx.compose.ui.geometry.Rect
}

/** An [EditorView] that lives as long as the composition; [initial] runs once. */
@Composable
fun rememberEditorView(initial: () -> EditorState): EditorView = remember { EditorView(initial()) }

/** `\r\n` and a lone `\r` as `\n`: the only line break inside the editor. */
internal fun normalizeLineBreaks(text: String): String =
    if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

/** The font zoom's limits (sp; a density-independent pixel, so "px" in the web editor's terms). */
object EditorZoom {
    const val MIN = 10f
    const val MAX = 24f
    /** [EditorTheme.fontSizeSp]'s default. */
    const val DEFAULT = 13f
}

/** How the web tells a hardware keyboard from a soft one ([EditorView.webKeyboard]). */
enum class WebKeyboard {
    /** The heuristic: a hardware key is one with a physical `code`, not after a touch or pen. */
    AUTO,
    /** Every key aimed at the editor is a hardware key (served in the DOM event). */
    HARDWARE,
    /** Every key goes through the hidden field, as a soft keyboard's must. */
    SOFT,
}

/** The path one key-down took ([EditorView.onKeyPath]). */
enum class KeyPath(val label: String) {
    /** Web: a bound chord or a typed character, served inside the DOM event. */
    WEB_FAST("web: served in the DOM event"),
    /** Web: Mod-c/x/v, left to the browser's clipboard event. */
    WEB_CLIPBOARD("web: browser clipboard event"),
    /** Web: taken for a soft keyboard's, left to the hidden field. */
    WEB_SOFT("web: soft keyboard, to the field"),
    /** Web: a hardware key the fast path does not serve, left to Compose. */
    WEB_COMPOSE("web: to Compose"),
    /** A key event that ran a keymap binding. */
    KEYMAP("keymap binding"),
    /** A key event left to the hidden field (a typed character, an unbound key). */
    FIELD("to the field"),
    /** A key event while the IME composes: the IME's. */
    IME("IME composing"),
}
