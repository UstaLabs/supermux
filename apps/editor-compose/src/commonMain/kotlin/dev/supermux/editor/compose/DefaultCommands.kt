package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.keymapOf
import kotlinx.coroutines.launch

/**
 * The editing commands every editor needs, as plain [Command]s over a [CommandTarget].
 *
 * Every command applies to EVERY selection range. Movement is by grapheme (never half a surrogate
 * pair or a cluster) and by word (letters, digits and `_` in any script; see [TextBoundaries]).
 * Vertical and page moves use the surface's geometry when the target is a composed [EditorView]
 * (visual rows, wrapped lines included, keeping the goal x across consecutive moves); without one
 * they move by logical line and character column.
 *
 * User events: moves are `select`, typing-like edits `input` (`input.indent` for Tab), deletions
 * `delete.backward` / `delete.forward`. Every command sets `scrollIntoView`, except [selectAll].
 * A command returns true when it handled the key, even at a document edge where nothing moved:
 * the key must not fall through to the hidden input field.
 */
object DefaultCommands {
    val cursorLeft = Command { t -> move(t, false) { st, r -> if (!r.empty) r.from else TextBoundaries.prevGrapheme(st.doc, r.head) } }
    val cursorRight = Command { t -> move(t, false) { st, r -> if (!r.empty) r.to else TextBoundaries.nextGrapheme(st.doc, r.head) } }
    val selectLeft = Command { t -> move(t, true) { st, r -> TextBoundaries.prevGrapheme(st.doc, r.head) } }
    val selectRight = Command { t -> move(t, true) { st, r -> TextBoundaries.nextGrapheme(st.doc, r.head) } }

    val cursorWordLeft = Command { t -> move(t, false) { st, r -> TextBoundaries.wordLeft(st.doc, r.head) } }
    val cursorWordRight = Command { t -> move(t, false) { st, r -> TextBoundaries.wordRight(st.doc, r.head) } }
    val selectWordLeft = Command { t -> move(t, true) { st, r -> TextBoundaries.wordLeft(st.doc, r.head) } }
    val selectWordRight = Command { t -> move(t, true) { st, r -> TextBoundaries.wordRight(st.doc, r.head) } }

    /**
     * Home: in a wrapped line, first to the start of the visual row (when not there already), then
     * smart Home: to the line's first non-blank character, or from there to column 0.
     */
    val cursorLineStart = Command { t -> move(t, false) { st, r -> home(t, st.doc, r.head) } }
    val selectLineStart = Command { t -> move(t, true) { st, r -> home(t, st.doc, r.head) } }

    /** End: in a wrapped line, first to the end of the visual row, then to the line's end. */
    val cursorLineEnd = Command { t -> move(t, false) { st, r -> end(t, st.doc, r.head) } }
    val selectLineEnd = Command { t -> move(t, true) { st, r -> end(t, st.doc, r.head) } }

    val cursorDocStart = Command { t -> move(t, false) { _, _ -> 0 } }
    val cursorDocEnd = Command { t -> move(t, false) { st, _ -> st.doc.length } }
    val selectDocStart = Command { t -> move(t, true) { _, _ -> 0 } }
    val selectDocEnd = Command { t -> move(t, true) { st, _ -> st.doc.length } }

    val cursorUp = Command { t -> vertical(t, -1, extend = false, page = false) }
    val cursorDown = Command { t -> vertical(t, 1, extend = false, page = false) }
    val selectUp = Command { t -> vertical(t, -1, extend = true, page = false) }
    val selectDown = Command { t -> vertical(t, 1, extend = true, page = false) }
    val cursorPageUp = Command { t -> vertical(t, -1, extend = false, page = true) }
    val cursorPageDown = Command { t -> vertical(t, 1, extend = false, page = true) }
    val selectPageUp = Command { t -> vertical(t, -1, extend = true, page = true) }
    val selectPageDown = Command { t -> vertical(t, 1, extend = true, page = true) }

    /** One size up (`Mod +`), from a pinched size to the next whole one; at most [EditorZoom.MAX]. */
    val zoomIn = Command { t ->
        (t as? EditorView)?.let { it.zoomTo(kotlin.math.floor(it.effectiveFontSize + 0.001f) + 1f) }
        true
    }

    /** One size down (`Mod −`); at least [EditorZoom.MIN]. */
    val zoomOut = Command { t ->
        (t as? EditorView)?.let { it.zoomTo(kotlin.math.ceil(it.effectiveFontSize - 0.001f) - 1f) }
        true
    }

    /** The theme's size again (`Mod 0`). */
    val zoomReset = Command { t ->
        (t as? EditorView)?.resetZoom()
        true
    }

    val selectAll = Command { t ->
        t.dispatch(TransactionSpec(selection = EditorSelection.single(0, t.state.doc.length), userEvent = "select"))
        true
    }

    /** A line break, keeping the current line's indentation (up to the cursor). */
    val insertNewline = Command { t ->
        change(t, "input") { st, r -> ChangeSpec(r.from, r.to, "\n" + st.doc.slice(lineStartOf(st.doc, r.from), indentEnd(st.doc, r.from, r.from))) }
    }

    /**
     * With only cursors: indentation up to the next indent stop ([indentUnitFacet]; a tab unit
     * inserts a tab). With a selection: one indent unit at the start of every line it touches.
     */
    val insertTab = Command { t ->
        val st = t.state
        val unit = st.facet(indentUnitFacet)
        if (st.selection.ranges.all { it.empty }) {
            change(t, "input.indent") { s, r ->
                val col = r.head - s.doc.lineStart(s.doc.lineIndexAt(r.head))
                val text = if (unit == "\t" || unit.isEmpty()) "\t" else " ".repeat(unit.length - col % unit.length)
                ChangeSpec(r.head, r.head, text)
            }
        } else {
            indentLines(t, unit)
        }
    }

    /** One indent unit at the start of every line a range touches, cursors included (`Mod-]`). */
    val indentMore = Command { t -> indentLines(t, t.state.facet(indentUnitFacet).ifEmpty { "\t" }) }

    /**
     * Shift-Tab (and `Mod-[`): one indent unit off the start of every line a range touches, cursor
     * or selection: a leading tab, else leading spaces up to the unit's width (a tab unit: the tab
     * size), or as many as the line has. Hardware keys only: soft keyboards have no Tab.
     */
    val indentLess = Command { t ->
        val st = t.state
        val doc = st.doc
        val unit = st.facet(indentUnitFacet)
        val width = if (unit == "\t" || unit.isEmpty()) st.facet(tabSizeFacet) else unit.length
        val specs = touchedLines(st).mapNotNull { l ->
            val start = doc.lineStart(l)
            val end = if (l + 1 < doc.lineCount) doc.lineStart(l + 1) - 1 else doc.length
            if (start < end && doc.charAt(start) == '\t') return@mapNotNull ChangeSpec(start, start + 1)
            var i = start
            while (i < end && i - start < width && doc.charAt(i) == ' ') i++
            if (i > start) ChangeSpec(start, i) else null
        }
        if (specs.isEmpty()) return@Command true
        val changes = ChangeSet.of(doc.length, specs)
        val sel = EditorSelection.create(st.selection.ranges.map { SelectionRange(changes.mapPos(it.anchor, 1), changes.mapPos(it.head, 1)) }, st.selection.mainIndex)
        t.dispatch(TransactionSpec(changeSet = changes, selection = sel, scrollIntoView = true, userEvent = "delete.dedent"))
        true
    }

    /** Copy every non-empty range, one line per range (nothing when all are cursors). */
    val copy = Command { t ->
        selectedText(t.state)?.let { (t as? EditorView)?.clipboard?.write(it) }
        true
    }

    /** [copy], then delete what was copied (`delete.cut`). */
    val cut = Command { t ->
        val text = selectedText(t.state)
        if (text != null) {
            (t as? EditorView)?.clipboard?.write(text)
            deleteSelection.run(t)
        }
        true
    }

    /** Delete every non-empty range (`delete.cut`): a cut whose text the caller put on a clipboard. */
    internal val deleteSelection = Command { t -> change(t, "delete.cut") { _, r -> if (r.empty) null else ChangeSpec(r.from, r.to) } }

    /** [EditorView.paste] the clipboard's text. */
    val paste = Command { t ->
        val view = t as? EditorView
        val clip = view?.clipboard
        val scope = view?.scope
        if (view != null && clip != null && scope != null && !view.readOnly) {
            scope.launch { clip.read()?.let { view.paste(it) } }
        }
        true
    }

    /** Every non-empty range's text, one line per range; null when all are cursors. */
    internal fun selectedText(st: EditorState): String? {
        val parts = st.selection.ranges.filter { !it.empty }.map { st.doc.slice(it.from, it.to) }
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    /** Type [text] over every range (a cursor gets it inserted), as keyboard input (`input`). */
    fun insertText(text: String): Command = Command { t ->
        if (t is EditorView) t.typeText(text) else change(t, "input") { _, r -> ChangeSpec(r.from, r.to, text) }
    }

    val deleteBackward = Command { t ->
        change(t, "delete.backward") { st, r ->
            if (!r.empty) ChangeSpec(r.from, r.to)
            else if (r.head == 0) null
            else ChangeSpec(TextBoundaries.prevGrapheme(st.doc, r.head), r.head)
        }
    }

    val deleteForward = Command { t ->
        change(t, "delete.forward") { st, r ->
            if (!r.empty) ChangeSpec(r.from, r.to)
            else if (r.head == st.doc.length) null
            else ChangeSpec(r.head, TextBoundaries.nextGrapheme(st.doc, r.head))
        }
    }

    val deleteWordBackward = Command { t ->
        change(t, "delete.backward") { st, r ->
            if (!r.empty) ChangeSpec(r.from, r.to)
            else TextBoundaries.wordLeft(st.doc, r.head).let { if (it == r.head) null else ChangeSpec(it, r.head) }
        }
    }

    val deleteWordForward = Command { t ->
        change(t, "delete.forward") { st, r ->
            if (!r.empty) ChangeSpec(r.from, r.to)
            else TextBoundaries.wordRight(st.doc, r.head).let { if (it == r.head) null else ChangeSpec(r.head, it) }
        }
    }

    // ------------------------------------------------------------------ helpers --

    /** The replaced (folded) ranges [t] shows: the view's, else the state's own. */
    private fun folds(t: CommandTarget, st: EditorState): Folds =
        (t as? EditorView)?.replaced(st) ?: Folds.of(st)

    /** A move to [head] from [from] never lands inside a replaced range: it goes to the range's far side. */
    private fun skipReplaced(folds: Folds, from: Int, head: Int): Int {
        val r = folds.replaceInside(head) ?: return head
        return if (head > from) r.to else r.from
    }

    private fun move(t: CommandTarget, extend: Boolean, to: (EditorState, SelectionRange) -> Int): Boolean {
        val st = t.state
        val folds = folds(t, st)
        val ranges = st.selection.ranges.map { r ->
            val head = skipReplaced(folds, r.head, to(st, r))
            if (extend) SelectionRange(r.anchor, head) else SelectionRange(head)
        }
        t.dispatch(TransactionSpec(selection = EditorSelection.create(ranges, st.selection.mainIndex), scrollIntoView = true, userEvent = "select"))
        return true
    }

    /**
     * Build one change per range with [f] (null: this range changes nothing), merge the ones that
     * overlap (two word deletions reaching back over the same text), and put every range's cursor
     * after its own replacement.
     */
    private fun change(t: CommandTarget, userEvent: String, f: (EditorState, SelectionRange) -> ChangeSpec?): Boolean {
        val st = t.state
        val ranges = st.selection.ranges
        // (A deletion reaching into an atomic range, a fold, is the view's to judge: EditorView.dispatch.)
        val specs = ranges.map { f(st, it) }
        val merged = ArrayList<ChangeSpec>()
        for (s in specs.filterNotNull().sortedWith(compareBy({ it.from }, { it.to }))) {
            val last = merged.lastOrNull()
            if (last != null && s.from < last.to) {
                merged[merged.size - 1] = ChangeSpec(last.from, maxOf(last.to, s.to), last.insert + s.insert)
            } else {
                merged += s
            }
        }
        if (merged.isEmpty()) return true
        val changes = ChangeSet.of(st.doc.length, merged)
        val next = ranges.mapIndexed { i, r ->
            val s = specs[i]
            if (s != null) SelectionRange(changes.mapPos(s.to, 1)) else r.map(changes)
        }
        t.dispatch(TransactionSpec(
            changeSet = changes,
            selection = EditorSelection.create(next, st.selection.mainIndex),
            scrollIntoView = true,
            userEvent = userEvent,
        ))
        return true
    }

    /** Every line a range touches, once, in order (a selection ending at a line's start stops before it). */
    private fun touchedLines(st: EditorState): List<Int> {
        val doc = st.doc
        val lines = HashSet<Int>()
        for (r in st.selection.ranges) {
            val last = doc.lineIndexAt(r.to).let { l -> if (!r.empty && l > doc.lineIndexAt(r.from) && doc.lineStart(l) == r.to) l - 1 else l }
            for (l in doc.lineIndexAt(r.from)..last) lines += l
        }
        return lines.sorted()
    }

    private fun indentLines(t: CommandTarget, unit: String): Boolean {
        val st = t.state
        val doc = st.doc
        val changes = ChangeSet.of(doc.length, touchedLines(st).map { ChangeSpec(doc.lineStart(it), doc.lineStart(it), unit) })
        val sel = EditorSelection.create(
            st.selection.ranges.map { SelectionRange(changes.mapPos(it.anchor, 1), changes.mapPos(it.head, 1)) },
            st.selection.mainIndex,
        )
        t.dispatch(TransactionSpec(changeSet = changes, selection = sel, scrollIntoView = true, userEvent = "input.indent"))
        return true
    }

    /** The visual row around [head], when [t] is a composed view (wrapping is the surface's). */
    private fun row(t: CommandTarget, head: Int): Pair<Int, Int>? = (t as? EditorView)?.geometry?.rowBounds(head)

    private fun home(t: CommandTarget, doc: Rope, head: Int): Int {
        val r = row(t, head)
        if (r != null && r.first > lineStartOf(doc, head) && head != r.first) return r.first
        return smartHome(doc, head)
    }

    private fun end(t: CommandTarget, doc: Rope, head: Int): Int {
        val r = row(t, head)
        // A row that folds lines ends past the fold (its joined tail's end).
        val lineEnd = (t as? EditorView)?.geometry?.visualEnd(head) ?: lineEnd(doc, head)
        if (r != null && r.second < lineEnd && head != r.second) return r.second
        return lineEnd
    }

    private fun smartHome(doc: Rope, head: Int): Int {
        val from = lineStartOf(doc, head)
        val firstNonBlank = indentEnd(doc, head, lineEnd(doc, head))
        return if (head != firstNonBlank) firstNonBlank else from
    }

    private fun lineStartOf(doc: Rope, pos: Int): Int = doc.lineStart(doc.lineIndexAt(pos))

    /** The end of the indentation of [pos]'s line, at most [limit]: scans only the indentation (a line may be 1 MB). */
    private fun indentEnd(doc: Rope, pos: Int, limit: Int): Int {
        var i = lineStartOf(doc, pos)
        while (i < limit && (doc.charAt(i) == ' ' || doc.charAt(i) == '\t')) i++
        return i
    }

    private fun lineEnd(doc: Rope, head: Int): Int {
        val i = doc.lineIndexAt(head)
        return if (i + 1 < doc.lineCount) doc.lineStart(i + 1) - 1 else doc.length
    }

    /** Lines a page moves without a surface to measure one. */
    private const val FALLBACK_PAGE_LINES = 20

    private fun vertical(t: CommandTarget, dir: Int, extend: Boolean, page: Boolean): Boolean {
        val st = t.state
        val view = t as? EditorView
        val g = view?.geometry
        val ranges = st.selection.ranges
        val goals = view?.goal?.takeIf { it.selection == st.selection && it.xs.size == ranges.size }?.xs
            ?: ranges.map { r -> if (g != null) g.rectFor(r.head).left else (r.head - st.doc.lineStart(st.doc.lineIndexAt(r.head))).toFloat() }
        val pageHeight = view?.surface?.viewportHeightPx?.takeIf { it > 0f }
            ?: ((g?.layouts?.lineHeightPx ?: 1f) * FALLBACK_PAGE_LINES)
        val next = ranges.mapIndexed { i, r ->
            val head = if (g != null) verticalByGeometry(g, st.doc, r.head, dir, if (page) pageHeight else 0f, goals[i])
            else verticalByColumn(st.doc, r.head, dir, if (page) FALLBACK_PAGE_LINES else 1, goals[i].toInt())
            if (extend) SelectionRange(r.anchor, head) else SelectionRange(head)
        }
        val sel = EditorSelection.create(next, st.selection.mainIndex)
        // A page move scrolls the page too, so the cursor keeps its place on the screen.
        if (page) view?.surface?.scrollBy(dir * pageHeight)
        t.dispatch(TransactionSpec(selection = sel, scrollIntoView = true, userEvent = "select"))
        if (view != null) view.goal = if (sel.ranges.size == goals.size) EditorView.Goal(view.state.selection, goals) else null
        return true
    }

    private fun verticalByGeometry(g: Geometry, doc: Rope, head: Int, dir: Int, page: Float, goalX: Float): Int {
        val r = g.rectFor(head)
        val step = if (page > 0f) page else (r.bottom - r.top)
        val raw = (r.top + r.bottom) / 2 + dir * step
        if (raw < 0f) return 0
        if (raw >= g.heights.totalHeight) return doc.length
        // Over block widgets (and folded lines) to the next text row that way.
        val y = g.textRowY(raw, dir) ?: return if (dir > 0) doc.length else 0
        return g.offsetAt(Offset(goalX, y))
    }

    private fun verticalByColumn(doc: Rope, head: Int, dir: Int, lines: Int, goalColumn: Int): Int {
        val target = doc.lineIndexAt(head) + dir * lines
        if (target < 0) return 0
        if (target >= doc.lineCount) return doc.length
        val start = doc.lineStart(target)
        val end = if (target + 1 < doc.lineCount) doc.lineStart(target + 1) - 1 else doc.length
        return TextBoundaries.snap(doc, minOf(start + goalColumn, end))
    }
}

/**
 * The default key bindings for [DefaultCommands], following [apple]'s conventions: on Apple
 * platforms Alt-Arrow moves by word, Cmd-Arrow to the line's (and document's) ends and
 * Alt-Backspace deletes a word; elsewhere Ctrl-Arrow and Ctrl-Backspace do. Everywhere: arrows,
 * Home/End (smart Home), Mod-Home/Mod-End, PageUp/PageDown, Shift to extend, Backspace/Delete,
 * Enter, Tab and Mod-a.
 */
fun defaultKeymap(apple: Boolean = isApplePlatform): Extension = keymapOf(*defaultBindings(apple).toTypedArray())

private val appleBindings by lazy { buildDefaultBindings(true) }
private val otherBindings by lazy { buildDefaultBindings(false) }

/** [defaultKeymap]'s bindings; the surface also falls back to them for a state without a keymap. */
internal fun defaultBindings(apple: Boolean): List<KeyBinding> = if (apple) appleBindings else otherBindings

private fun buildDefaultBindings(apple: Boolean): List<KeyBinding> {
    val c = DefaultCommands
    val b = ArrayList<KeyBinding>()
    fun bind(key: String, cmd: Command) { b += KeyBinding(key, cmd) }
    fun pair(key: String, move: Command, select: Command) { bind(key, move); bind("Shift-$key", select) }

    pair("ArrowLeft", c.cursorLeft, c.selectLeft)
    pair("ArrowRight", c.cursorRight, c.selectRight)
    pair("ArrowUp", c.cursorUp, c.selectUp)
    pair("ArrowDown", c.cursorDown, c.selectDown)
    pair("Home", c.cursorLineStart, c.selectLineStart)
    pair("End", c.cursorLineEnd, c.selectLineEnd)
    pair("Mod-Home", c.cursorDocStart, c.selectDocStart)
    pair("Mod-End", c.cursorDocEnd, c.selectDocEnd)
    pair("PageUp", c.cursorPageUp, c.selectPageUp)
    pair("PageDown", c.cursorPageDown, c.selectPageDown)
    if (apple) {
        pair("Alt-ArrowLeft", c.cursorWordLeft, c.selectWordLeft)
        pair("Alt-ArrowRight", c.cursorWordRight, c.selectWordRight)
        pair("Mod-ArrowLeft", c.cursorLineStart, c.selectLineStart)
        pair("Mod-ArrowRight", c.cursorLineEnd, c.selectLineEnd)
        pair("Mod-ArrowUp", c.cursorDocStart, c.selectDocStart)
        pair("Mod-ArrowDown", c.cursorDocEnd, c.selectDocEnd)
    } else {
        pair("Mod-ArrowLeft", c.cursorWordLeft, c.selectWordLeft)
        pair("Mod-ArrowRight", c.cursorWordRight, c.selectWordRight)
        bind("Mod-Backspace", c.deleteWordBackward)
        bind("Mod-Delete", c.deleteWordForward)
    }
    if (apple) bind("Alt-Delete", c.deleteWordForward)
    bind("Alt-Backspace", c.deleteWordBackward)
    bind("Backspace", c.deleteBackward)
    bind("Shift-Backspace", c.deleteBackward)
    bind("Delete", c.deleteForward)
    bind("Enter", c.insertNewline)
    bind("Shift-Enter", c.insertNewline)
    bind("Tab", c.insertTab)
    bind("Shift-Tab", c.indentLess)
    bind("Mod-]", c.indentMore)
    bind("Mod-[", c.indentLess)
    bind("Mod-a", c.selectAll)
    // Font zoom: "=" is the "+" key without Shift on most layouts; "+" is a key of its own on others
    // and on the number pad.
    bind("Mod-=", c.zoomIn)
    bind("Mod-Shift-=", c.zoomIn)
    bind("Mod-+", c.zoomIn)
    bind("Mod-Shift-+", c.zoomIn)
    bind("Mod--", c.zoomOut)
    bind("Mod-Shift--", c.zoomOut)
    bind("Mod-0", c.zoomReset)
    // The editor's own clipboard commands: the hidden field would copy, cut and paste only its window.
    bind("Mod-c", c.copy)
    bind("Mod-x", c.cut)
    bind("Mod-v", c.paste)
    return b
}
