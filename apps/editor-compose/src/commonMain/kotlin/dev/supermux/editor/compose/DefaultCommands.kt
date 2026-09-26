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

    /** Smart Home: to the line's first non-blank character, or from there to column 0. */
    val cursorLineStart = Command { t -> move(t, false) { st, r -> smartHome(st.doc, r.head) } }
    val selectLineStart = Command { t -> move(t, true) { st, r -> smartHome(st.doc, r.head) } }
    val cursorLineEnd = Command { t -> move(t, false) { st, r -> lineEnd(st.doc, r.head) } }
    val selectLineEnd = Command { t -> move(t, true) { st, r -> lineEnd(st.doc, r.head) } }

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

    val selectAll = Command { t ->
        t.dispatch(TransactionSpec(selection = EditorSelection.single(0, t.state.doc.length), userEvent = "select"))
        true
    }

    /** A line break, keeping the current line's indentation (up to the cursor). */
    val insertNewline = Command { t ->
        change(t, "input") { st, r ->
            val line = st.doc.lineAt(r.from)
            val indent = line.text.takeWhile { it == ' ' || it == '\t' }.take(r.from - line.from)
            ChangeSpec(r.from, r.to, "\n" + indent)
        }
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

    // ------------------------------------------------------------------ helpers --

    private fun move(t: CommandTarget, extend: Boolean, to: (EditorState, SelectionRange) -> Int): Boolean {
        val st = t.state
        val ranges = st.selection.ranges.map { r ->
            val head = to(st, r)
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

    private fun indentLines(t: CommandTarget, unit: String): Boolean {
        val st = t.state
        val doc = st.doc
        val lines = HashSet<Int>()
        for (r in st.selection.ranges) {
            val last = doc.lineIndexAt(r.to).let { l -> if (!r.empty && l > doc.lineIndexAt(r.from) && doc.lineStart(l) == r.to) l - 1 else l }
            for (l in doc.lineIndexAt(r.from)..last) lines += l
        }
        val changes = ChangeSet.of(doc.length, lines.sorted().map { ChangeSpec(doc.lineStart(it), doc.lineStart(it), unit) })
        val sel = EditorSelection.create(
            st.selection.ranges.map { SelectionRange(changes.mapPos(it.anchor, 1), changes.mapPos(it.head, 1)) },
            st.selection.mainIndex,
        )
        t.dispatch(TransactionSpec(changeSet = changes, selection = sel, scrollIntoView = true, userEvent = "input.indent"))
        return true
    }

    private fun smartHome(doc: Rope, head: Int): Int {
        val line = doc.lineAt(head)
        val firstNonBlank = line.from + line.text.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it }
        return if (head != firstNonBlank) firstNonBlank else line.from
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
        val y = (r.top + r.bottom) / 2 + dir * step
        if (y < 0f) return 0
        if (y >= g.heights.totalHeight) return doc.length
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
fun defaultKeymap(apple: Boolean = isApplePlatform): Extension {
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
    }
    bind("Alt-Backspace", c.deleteWordBackward)
    bind("Backspace", c.deleteBackward)
    bind("Shift-Backspace", c.deleteBackward)
    bind("Delete", c.deleteForward)
    bind("Enter", c.insertNewline)
    bind("Shift-Enter", c.insertNewline)
    bind("Tab", c.insertTab)
    bind("Mod-a", c.selectAll)
    return keymapOf(*b.toTypedArray())
}
