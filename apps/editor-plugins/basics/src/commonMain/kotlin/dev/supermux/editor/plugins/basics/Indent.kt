package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.DefaultCommands
import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.keymapOf

/**
 * Enter between a pair of brackets opens a block: `{|}` becomes `{`, a line indented one
 * [indentUnitFacet] deeper than the current line holding the cursor, and the `}` on its own line
 * at the current line's indentation. Elsewhere Enter is [DefaultCommands.insertNewline] (which
 * keeps the current line's indentation). Also for a soft keyboard's Return: the hidden field runs
 * the keymap's Enter binding.
 */
object BlockIndent {
    val insertNewlineAndIndent: Command = Command { t ->
        val st = t.state
        val doc = st.doc
        val ranges = st.selection.ranges
        if (ranges.any { !it.empty } || ranges.none { between(doc, it.head) }) return@Command false
        val unit = st.facet(indentUnitFacet).ifEmpty { "\t" }
        val specs = ArrayList<ChangeSpec>()
        val carets = ArrayList<Int>() // where each cursor goes, relative to its insertion's start
        for (r in ranges) {
            val indent = lineIndent(doc, r.head)
            if (between(doc, r.head)) {
                specs += ChangeSpec(r.head, r.head, "\n$indent$unit\n$indent")
                carets += 1 + indent.length + unit.length
            } else {
                specs += ChangeSpec(r.head, r.head, "\n$indent")
                carets += 1 + indent.length
            }
        }
        val changes = ChangeSet.of(doc.length, specs)
        val next = ranges.mapIndexed { i, r -> SelectionRange(changes.mapPos(r.head, -1) + carets[i]) }
        t.dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, st.selection.mainIndex), scrollIntoView = true, userEvent = "input"))
        true
    }

    /** Enter (and Shift-Enter) above the default newline. */
    fun extension(): Extension = Prec.high(keymapOf(KeyBinding("Enter", insertNewlineAndIndent)))

    private fun between(doc: Rope, pos: Int): Boolean {
        if (pos <= 0 || pos >= doc.length) return false
        val open = doc.charAt(pos - 1)
        return open in "([{" && doc.charAt(pos) == CloseBrackets.closing(open)
    }

    /** The leading whitespace of [pos]'s line (up to [pos]). */
    private fun lineIndent(doc: Rope, pos: Int): String {
        val start = doc.lineStart(doc.lineIndexAt(pos))
        var i = start
        while (i < pos && (doc.charAt(i) == ' ' || doc.charAt(i) == '\t')) i++
        return doc.slice(start, i)
    }
}

/** The basics: [CloseBrackets] and [BlockIndent]. */
fun basics(): Extension = dev.supermux.editor.core.extensionOf(CloseBrackets.extension(), BlockIndent.extension())
