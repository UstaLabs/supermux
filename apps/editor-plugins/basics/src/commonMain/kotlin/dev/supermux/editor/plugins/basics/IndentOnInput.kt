package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.InputHandler
import dev.supermux.editor.compose.inputHandlerFacet
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TokenContext
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.tokenContextFacet

/**
 * Indent on input (CM6's `indentOnInput`, with the opener-line rule): typing a closing `}`, `)` or `]`
 * as the first non-blank character of a line re-indents that line to the indentation of the line
 * holding its opener (found by [BracketMatching.matchBrackets], so string brackets do not count
 * when the syntax hook is there). The brace and the re-indent are ONE transaction (`input.type`),
 * so one undo takes both. At every cursor; a cursor where the rule does not apply just types it.
 *
 * Tree-based indentation (tree-sitter `indents.scm`) is later; the opener-line rule is what CM6's
 * `indentOnInput` does for brackets in most languages.
 */
object IndentOnInput {
    private const val CLOSERS = "})]"

    val inputHandler: InputHandler = InputHandler { t, from, to, text -> handle(t, from, to, text) }

    val extension: Extension = inputHandlerFacet.of(inputHandler)

    private fun handle(t: CommandTarget, from: Int, to: Int, text: String): Boolean {
        val st = t.state
        val main = st.selection.main
        if (text.length != 1 || text[0] !in CLOSERS || from != main.from || to != main.to) return false
        val doc = st.doc
        val ranges = st.selection.ranges
        // Where each cursor's line starts and what indentation it gets (null: typed as is).
        val plans = ranges.map { r -> if (r.empty) reindent(t, doc, r.head, text[0]) else null }
        if (plans.all { it == null }) return false
        val specs = ArrayList<ChangeSpec>()
        for ((i, r) in ranges.withIndex()) {
            val p = plans[i]
            if (p == null) specs += ChangeSpec(r.from, r.to, text)
            else specs += ChangeSpec(p.first, r.head, p.second + text)
        }
        val changes = ChangeSet.of(doc.length, specs)
        val next = ranges.mapIndexed { i, r ->
            val p = plans[i]
            if (p == null) SelectionRange(changes.mapPos(r.to, 1))
            else SelectionRange(changes.mapPos(p.first, -1) + p.second.length + 1)
        }
        t.dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, st.selection.mainIndex), scrollIntoView = true, userEvent = "input.type"))
        return true
    }

    /** For a closer typed at [head]: (the line's start, the opener line's indentation), or null. */
    private fun reindent(t: CommandTarget, doc: Rope, head: Int, closer: Char): Pair<Int, String>? {
        val lineStart = doc.lineStart(doc.lineIndexAt(head))
        if (head - lineStart > 1_000) return null
        val before = doc.slice(lineStart, head)
        if (before.any { it != ' ' && it != '\t' }) return null
        // The opener: the bracket this closer would close, as bracket matching sees it (the closer
        // is not in the document yet, so scan back from the cursor for an unclosed opener).
        val opener = unclosedOpener(t, doc, head, closer) ?: return null
        val openerLine = doc.lineStart(doc.lineIndexAt(opener))
        if (openerLine == lineStart) return null
        val indent = leadingWhitespace(doc, openerLine)
        if (indent == before) return null
        return lineStart to indent
    }

    private fun unclosedOpener(t: CommandTarget, doc: Rope, head: Int, closer: Char): Int? {
        // The closer is not in the document yet: scan back from the cursor as bracket matching would
        // from a closer there (the same token context as the text just before the cursor).
        val st = t.state
        val config = st.facet(bracketMatchingConfig)
        val bracket = config.brackets.indexOf(closer)
        if (bracket < 0 || head == 0) return null
        val ctx = st.facet(tokenContextFacet)?.contextAt(st, head - 1) ?: TokenContext.CODE
        val (pos, matched) = BracketMatching.partner(st, head - 1, -1, bracket, ctx, config) ?: return null
        return if (matched) pos else null
    }

    private fun leadingWhitespace(doc: Rope, lineStart: Int): String {
        var i = lineStart
        val max = minOf(doc.length, lineStart + 1_000)
        while (i < max && (doc.charAt(i) == ' ' || doc.charAt(i) == '\t')) i++
        return doc.slice(lineStart, i)
    }
}
