package dev.supermux.editor.plugins.basics

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.CommentTokens
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.commentTokensFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapFacet
import dev.supermux.editor.core.selectParentFacet

/**
 * CM6's `defaultKeymap` extras (`@codemirror/commands` 6.10.3, the version today's editor bundles):
 * move / copy / delete lines, line and block comments, select the line, select the parent syntax
 * node, jump to the matching bracket, insert a blank line. Every command works at every cursor and
 * is ONE transaction (one undo step).
 *
 * | key | command | CM6 |
 * |---|---|---|
 * | `Alt-ArrowUp` / `Alt-ArrowDown` | [moveLineUp] / [moveLineDown] | same |
 * | `Shift-Alt-ArrowUp` / `Shift-Alt-ArrowDown` | [copyLineUp] / [copyLineDown] | same |
 * | `Shift-Mod-k` | [deleteLine] | same |
 * | `Mod-/` | [toggleComment] | same |
 * | `Shift-Alt-a` | [toggleBlockComment] | `Alt-A` (the shifted key) |
 * | `Alt-l`, Apple `Ctrl-l` | [selectLine] | same |
 * | `Mod-i` | [selectParentSyntax] | same |
 * | `Shift-Mod-\` | [cursorMatchingBracket] | same |
 * | `Mod-Enter` | [insertBlankLine] | same |
 *
 * Comments take the tokens of the language at the line (editor-core's `commentTokensFacet`, which
 * editor-syntax answers from its registry, per injected layer); without them the comment commands
 * do nothing. [selectParentSyntax] asks the language layer (`selectParentFacet`: the syntax tree is
 * the syntax worker's, so the selection may arrive a moment later).
 */
object Editing {
    val moveLineUp: Command = Command { t -> moveLine(t, forward = false) }
    val moveLineDown: Command = Command { t -> moveLine(t, forward = true) }
    val copyLineUp: Command = Command { t -> copyLine(t, forward = false) }
    val copyLineDown: Command = Command { t -> copyLine(t, forward = true) }
    val deleteLine: Command = Command(::deleteLines)
    val selectLine: Command = Command(::selectLines)
    val insertBlankLine: Command = Command(::blankLine)
    val cursorMatchingBracket: Command = Command(::matchingBracket)
    val toggleLineComment: Command = Command { t -> changeLineComment(t) }
    val toggleBlockComment: Command = Command { t -> changeBlockComment(t, t.state.selection.ranges.map { it.from to it.to }) }

    /** CM6's `toggleComment`: line comments where the language has them, else block comments around the lines. */
    val toggleComment: Command = Command { t ->
        val st = t.state
        val tokens = tokensAt(st, st.doc.lineAt(st.selection.main.from).from)
        when {
            tokens?.line != null -> changeLineComment(t)
            tokens?.block != null -> changeBlockComment(t, selectedLineRanges(st))
            else -> false
        }
    }

    /** CM6's `selectParentSyntax`, through the language layer (false where there is no syntax tree). */
    val selectParentSyntax: Command = Command { t -> t.state.facet(selectParentFacet)?.selectParent(t) ?: false }

    /** The bindings, CM6's keys (see the table above). */
    val keymap: List<KeyBinding> = listOf(
        KeyBinding("Alt-ArrowUp", moveLineUp),
        KeyBinding("Shift-Alt-ArrowUp", copyLineUp),
        KeyBinding("Alt-ArrowDown", moveLineDown),
        KeyBinding("Shift-Alt-ArrowDown", copyLineDown),
        KeyBinding("Mod-Enter", insertBlankLine),
        KeyBinding("Alt-l", selectLine, mac = "Ctrl-l"),
        KeyBinding("Mod-i", selectParentSyntax),
        KeyBinding("Shift-Mod-k", deleteLine),
        KeyBinding("Shift-Mod-\\", cursorMatchingBracket),
        KeyBinding("Mod-/", toggleComment),
        KeyBinding("Shift-Alt-a", toggleBlockComment),
    )

    val commands: List<NamedCommand> = listOf(
        NamedCommand("editing.moveLineUp", "Move line up", moveLineUp),
        NamedCommand("editing.moveLineDown", "Move line down", moveLineDown),
        NamedCommand("editing.copyLineUp", "Copy line up", copyLineUp),
        NamedCommand("editing.copyLineDown", "Copy line down", copyLineDown),
        NamedCommand("editing.deleteLine", "Delete line", deleteLine),
        NamedCommand("editing.toggleComment", "Toggle comment", toggleComment),
        NamedCommand("editing.toggleBlockComment", "Toggle block comment", toggleBlockComment),
        NamedCommand("editing.selectLine", "Select line", selectLine),
        NamedCommand("editing.selectParentSyntax", "Select enclosing syntax", selectParentSyntax),
        NamedCommand("editing.cursorMatchingBracket", "Go to matching bracket", cursorMatchingBracket),
        NamedCommand("editing.insertBlankLine", "Insert blank line", insertBlankLine),
    )

    val extension: Extension = extensionOf(keymapFacet.of(keymap), commandsFacet.of(commands))

    // ------------------------------------------------------------------------ line blocks --

    /** CM6's `selectedLineBlocks`: the line spans the ranges cover, adjacent ones merged, with their ranges. */
    internal class Block(val from: Int, var to: Int, val ranges: MutableList<Int>)

    internal fun selectedLineBlocks(state: EditorState): List<Block> {
        val doc = state.doc
        val blocks = ArrayList<Block>()
        var upto = -1
        for ((i, r) in state.selection.ranges.withIndex()) {
            val startLine = doc.lineAt(r.from)
            var endLine = doc.lineAt(r.to)
            if (!r.empty && r.to == endLine.from) endLine = doc.lineAt(r.to - 1)
            if (upto >= startLine.number) {
                val prev = blocks.last()
                prev.to = endLine.to
                prev.ranges += i
            } else {
                blocks += Block(startLine.from, endLine.to, mutableListOf(i))
            }
            upto = endLine.number + 1
        }
        return blocks
    }

    private fun moveLine(t: CommandTarget, forward: Boolean): Boolean {
        val st = t.state
        val doc = st.doc
        val ranges = st.selection.ranges.toMutableList()
        val changes = ArrayList<ChangeSpec>()
        for (b in selectedLineBlocks(st)) {
            if (if (forward) b.to == doc.length else b.from == 0) continue
            val next = doc.lineAt(if (forward) b.to + 1 else b.from - 1)
            val size = next.to - next.from + 1
            if (forward) {
                changes += ChangeSpec(b.to, next.to)
                changes += ChangeSpec(b.from, b.from, next.text + "\n")
                for (i in b.ranges) ranges[i] = ranges[i].let { r -> SelectionRange(minOf(doc.length, r.anchor + size), minOf(doc.length, r.head + size)) }
            } else {
                changes += ChangeSpec(next.from, b.from)
                changes += ChangeSpec(b.to, b.to, "\n" + next.text)
                for (i in b.ranges) ranges[i] = ranges[i].let { r -> SelectionRange(r.anchor - size, r.head - size) }
            }
        }
        if (changes.isEmpty()) return false
        t.dispatch(TransactionSpec(changes = changes, selection = EditorSelection.create(ranges, st.selection.mainIndex), scrollIntoView = true, userEvent = "edit.moveLine"))
        return true
    }

    private fun copyLine(t: CommandTarget, forward: Boolean): Boolean {
        val st = t.state
        val doc = st.doc
        val changes = selectedLineBlocks(st).map { b ->
            if (forward) ChangeSpec(b.from, b.from, doc.slice(b.from, b.to) + "\n")
            else ChangeSpec(b.to, b.to, "\n" + doc.slice(b.from, b.to))
        }
        val cs = ChangeSet.of(doc.length, changes)
        t.dispatch(TransactionSpec(changeSet = cs, selection = st.selection.map(cs, if (forward) 1 else -1), scrollIntoView = true, userEvent = "edit.copyLine"))
        return true
    }

    private fun deleteLines(t: CommandTarget): Boolean {
        val st = t.state
        val doc = st.doc
        val cs = ChangeSet.of(doc.length, selectedLineBlocks(st).map { b ->
            var from = b.from
            var to = b.to
            if (from > 0) from-- else if (to < doc.length) to++
            ChangeSpec(from, to)
        })
        // Each cursor goes one line down (the goal column kept), then through the deletion: it lands on
        // the line that followed, where it was across (CM6 moves vertically, then maps).
        val sel = EditorSelection.create(st.selection.ranges.map { r ->
            val line = doc.lineIndexAt(r.head)
            val col = r.head - doc.lineStart(line)
            val down = if (line + 1 < doc.lineCount) {
                val start = doc.lineStart(line + 1)
                val end = if (line + 2 < doc.lineCount) doc.lineStart(line + 2) - 1 else doc.length
                minOf(start + col, end)
            } else doc.length
            SelectionRange(cs.mapPos(down, -1))
        }, st.selection.mainIndex)
        t.dispatch(TransactionSpec(changeSet = cs, selection = sel, scrollIntoView = true, userEvent = "delete.line"))
        return true
    }

    private fun selectLines(t: CommandTarget): Boolean {
        val st = t.state
        val ranges = selectedLineBlocks(st).map { b -> SelectionRange(b.from, minOf(b.to + 1, st.doc.length)) }
        t.dispatch(TransactionSpec(selection = EditorSelection.create(ranges), userEvent = "select"))
        return true
    }

    /** CM6's `insertBlankLine`: a new line below each cursor's line, at that line's indentation, the cursor on it. */
    private fun blankLine(t: CommandTarget): Boolean {
        val st = t.state
        val doc = st.doc
        val specs = ArrayList<ChangeSpec>()
        val starts = ArrayList<Int>()
        for (r in st.selection.ranges) {
            val line = doc.lineAt(r.from)
            val target = if (r.to <= line.to) line else doc.lineAt(r.to)
            var from = target.to
            val indent = target.text.takeWhile { it == ' ' || it == '\t' }
            // A whitespace-only line gives up its blanks (CM6: `from = line.from` when nothing but space precedes it).
            if (from > target.from && from < target.from + 100 && target.text.isBlank()) from = target.from
            specs += ChangeSpec(from, target.to, "\n" + indent)
            starts += from
        }
        // Two cursors on one line: one blank line.
        val unique = specs.withIndex().distinctBy { it.value.from to it.value.to }
        val cs = ChangeSet.of(doc.length, unique.map { it.value })
        val ranges = starts.map { SelectionRange(cs.mapPos(it, 1)) }
        t.dispatch(TransactionSpec(changeSet = cs, selection = EditorSelection.create(ranges, st.selection.mainIndex), scrollIntoView = true, userEvent = "input"))
        return true
    }

    private fun matchingBracket(t: CommandTarget): Boolean {
        val st = t.state
        var found = false
        val ranges = st.selection.ranges.map { r ->
            val head = r.head
            val m = BracketMatching.matchBrackets(st, head, -1)
                ?: BracketMatching.matchBrackets(st, head, 1)
                ?: (if (head > 0) BracketMatching.matchBrackets(st, head - 1, 1) else null)
                ?: (if (head < st.doc.length) BracketMatching.matchBrackets(st, head + 1, -1) else null)
            val end = m?.end
            if (m == null || end == null) r
            else {
                found = true
                // CM6: to the far side of the partner when the cursor was at the bracket's start, else its near side.
                SelectionRange(if (m.start.first == head) end.last + 1 else end.first)
            }
        }
        if (!found) return false
        t.dispatch(TransactionSpec(selection = EditorSelection.create(ranges, st.selection.mainIndex), scrollIntoView = true, userEvent = "select"))
        return true
    }

    // ------------------------------------------------------------------------ comments --

    private fun tokensAt(state: EditorState, pos: Int): CommentTokens? = state.facet(commentTokensFacet)?.tokensAt(state, pos)

    /** CM6's `selectedLineRanges`: each range grown to its lines, from the first non-blank character. */
    private fun selectedLineRanges(state: EditorState): List<Pair<Int, Int>> {
        val doc = state.doc
        val out = ArrayList<Pair<Int, Int>>()
        for (r in state.selection.ranges) {
            val fromLine = doc.lineAt(r.from)
            var toLine = if (r.to <= fromLine.to) fromLine else doc.lineAt(r.to)
            if (toLine.from > fromLine.from && toLine.from == r.to) toLine = if (r.to == fromLine.to + 1) fromLine else doc.lineAt(r.to - 1)
            val last = out.size - 1
            if (last >= 0 && out[last].second > fromLine.from) out[last] = out[last].first to toLine.to
            else out += (fromLine.from + fromLine.text.takeWhile { it.isWhitespace() }.length) to toLine.to
        }
        return out
    }

    private class LineInfo(val from: Int, val text: String, val comment: Int, val token: String, var indent: Int, val empty: Boolean, var single: Boolean)

    /** CM6's `changeLineComment` (toggle): comment every line at the block's common indentation, or uncomment. */
    private fun changeLineComment(t: CommandTarget): Boolean {
        val st = t.state
        val doc = st.doc
        val lines = ArrayList<LineInfo>()
        var prevLine = -1
        ranges@ for (r in st.selection.ranges) {
            val startI = lines.size
            var minIndent = Int.MAX_VALUE
            var token: String? = null
            var pos = r.from
            while (pos <= r.to) {
                val line = doc.lineAt(pos)
                if (token == null) token = tokensAt(st, line.from)?.line ?: continue@ranges
                if (line.from > prevLine && (r.from == r.to || r.to > line.from)) {
                    prevLine = line.from
                    val indent = line.text.takeWhile { it.isWhitespace() }.length
                    val empty = indent == line.text.length
                    val comment = if (line.text.startsWith(token, indent)) indent else -1
                    if (indent < line.text.length && indent < minIndent) minIndent = indent
                    lines += LineInfo(line.from, line.text, comment, token, indent, empty, false)
                }
                pos = line.to + 1
            }
            if (minIndent < Int.MAX_VALUE) for (i in startI until lines.size) if (lines[i].indent < lines[i].text.length) lines[i].indent = minIndent
            if (lines.size == startI + 1) lines[startI].single = true
        }
        if (lines.any { it.comment < 0 && (!it.empty || it.single) }) {
            val specs = lines.filter { it.single || !it.empty }.map { ChangeSpec(it.from + it.indent, it.from + it.indent, it.token + " ") }
            val cs = ChangeSet.of(doc.length, specs)
            t.dispatch(TransactionSpec(changeSet = cs, selection = st.selection.map(cs, 1), userEvent = "edit.comment"))
            return true
        }
        if (lines.any { it.comment >= 0 }) {
            val specs = lines.filter { it.comment >= 0 }.map { l ->
                val from = l.from + l.comment
                var to = from + l.token.length
                if (to - l.from < l.text.length && l.text[to - l.from] == ' ') to++
                ChangeSpec(from, to)
            }
            t.dispatch(TransactionSpec(changes = specs, userEvent = "edit.comment"))
            return true
        }
        return false
    }

    private const val SEARCH_MARGIN = 50

    private class Found(val openPos: Int, val openMargin: Int, val closePos: Int, val closeMargin: Int)

    /** CM6's `findBlockComment`: the comment around (or at the edges of) [from, to), if any. */
    private fun findBlockComment(state: EditorState, block: CommentTokens.BlockComment, from: Int, to: Int): Found? {
        val doc = state.doc
        val open = block.open
        val close = block.close
        val textBefore = doc.slice(maxOf(0, from - SEARCH_MARGIN), from)
        val textAfter = doc.slice(to, minOf(doc.length, to + SEARCH_MARGIN))
        val spaceBefore = textBefore.length - textBefore.trimEnd().length
        val spaceAfter = textAfter.length - textAfter.trimStart().length
        val beforeOff = textBefore.length - spaceBefore
        if (beforeOff >= open.length && textBefore.substring(beforeOff - open.length, beforeOff) == open &&
            textAfter.startsWith(close, spaceAfter)
        ) {
            return Found(from - spaceBefore, if (spaceBefore > 0) 1 else 0, to + spaceAfter, if (spaceAfter > 0) 1 else 0)
        }
        val startText: String
        val endText: String
        if (to - from <= 2 * SEARCH_MARGIN) {
            startText = doc.slice(from, to); endText = startText
        } else {
            startText = doc.slice(from, from + SEARCH_MARGIN); endText = doc.slice(to - SEARCH_MARGIN, to)
        }
        val startSpace = startText.length - startText.trimStart().length
        val endSpace = endText.length - endText.trimEnd().length
        val endOff = endText.length - endSpace - close.length
        if (endOff >= 0 && startText.startsWith(open, startSpace) && endText.startsWith(close, endOff) &&
            // The open and the close are two tokens, not one overlapping run ("/*/").
            (to - from > 2 * SEARCH_MARGIN || startSpace + open.length <= endOff)
        ) {
            val openMargin = if (startSpace + open.length < startText.length && startText[startSpace + open.length].isWhitespace()) 1 else 0
            val closeMargin = if (endOff > 0 && endText[endOff - 1].isWhitespace()) 1 else 0
            return Found(from + startSpace + open.length, openMargin, to - endSpace - close.length, closeMargin)
        }
        return null
    }

    /** CM6's `changeBlockComment` (toggle) over [ranges]: wrap each in the block comment, or unwrap. */
    private fun changeBlockComment(t: CommandTarget, ranges: List<Pair<Int, Int>>): Boolean {
        val st = t.state
        val tokens = ranges.map { (from, _) -> tokensAt(st, from)?.block }
        if (tokens.any { it == null } || tokens.isEmpty()) return false
        val comments = ranges.mapIndexed { i, (from, to) -> findBlockComment(st, tokens[i]!!, from, to) }
        if (comments.any { it == null }) {
            val specs = ranges.flatMapIndexed { i, (from, to) ->
                if (comments[i] != null) emptyList() else listOf(ChangeSpec(from, from, tokens[i]!!.open + " "), ChangeSpec(to, to, " " + tokens[i]!!.close))
            }
            t.dispatch(TransactionSpec(changes = specs, userEvent = "edit.comment"))
            return true
        }
        val specs = ArrayList<ChangeSpec>()
        for ((i, c) in comments.withIndex()) {
            val tok = tokens[i]!!
            c!!
            specs += ChangeSpec(c.openPos - tok.open.length, c.openPos + c.openMargin)
            specs += ChangeSpec(c.closePos - c.closeMargin, c.closePos + tok.close.length)
        }
        t.dispatch(TransactionSpec(changes = specs, userEvent = "edit.comment"))
        return true
    }
}
