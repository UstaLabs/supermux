package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.Rope

/**
 * The tree-sitter edits for [changes], which turned [before] into [after]: one [TextEdit] per
 * change, in document order.
 *
 * tree-sitter applies edits one after the other, so each edit is in the coordinates of the
 * document as the EARLIER edits of the list have already left it: everything before change k is
 * already in [after]'s form, so its start is `fromB`, its old end `fromB + (toA - fromA)` and its
 * new end `toB`. Rows are 0-based lines, columns UTF-16 units from the line start, read from the
 * ropes (the old end's from [before], where the deleted text still is).
 */
fun textEditsFor(changes: ChangeSet, before: Rope, after: Rope): List<TextEdit> {
    val out = ArrayList<TextEdit>()
    for (c in changes.iterChanges()) {
        val start = c.fromB
        val oldEnd = c.fromB + (c.toA - c.fromA)
        val newEnd = c.toB
        val startRow = after.lineIndexAt(start)
        val startColumn = start - after.lineStart(startRow)
        val newEndRow = after.lineIndexAt(newEnd)
        val newEndColumn = newEnd - after.lineStart(newEndRow)
        // The deleted text spans the same lines it spanned in [before].
        val fromRowA = before.lineIndexAt(c.fromA)
        val toRowA = before.lineIndexAt(c.toA)
        val oldEndRow = startRow + (toRowA - fromRowA)
        val oldEndColumn = if (toRowA == fromRowA) startColumn + (c.toA - c.fromA) else c.toA - before.lineStart(toRowA)
        out += TextEdit(start, oldEnd, newEnd, startRow, startColumn, oldEndRow, oldEndColumn, newEndRow, newEndColumn)
    }
    return out
}
