// Keyboard navigation for the Files tree, as a pure function over the flattened rows so every key
// and edge case is unit-testable without a window. [FileTreeView] maps key events to [TreeKey],
// calls [treeKeyAction] and applies the [TreeKeyResult].
package dev.supermux.ui.files

/** A key the tree reacts to. [Type] carries the whole type-ahead prefix typed so far. */
sealed interface TreeKey {
    data object Up : TreeKey
    data object Down : TreeKey
    data object Left : TreeKey
    data object Right : TreeKey
    data object Enter : TreeKey
    data object Home : TreeKey
    data object End : TreeKey
    data object Rename : TreeKey
    data object Delete : TreeKey
    data class Type(val prefix: String) : TreeKey
}

sealed interface TreeKeyResult {
    /** Not ours: let the event go on (no rows, nothing selected for an action key, …). */
    data object Unhandled : TreeKeyResult
    /** Ours, but nothing changes (↑ on the first row, → on a folder with no children yet, …). */
    data object Stay : TreeKeyResult
    data class Select(val path: String) : TreeKeyResult
    data class Expand(val path: String) : TreeKeyResult
    data class Collapse(val path: String) : TreeKeyResult
    /** What a click does: open a file, toggle a folder, retry a failed folder. */
    data class Activate(val path: String) : TreeKeyResult
    data class Rename(val path: String) : TreeKeyResult
    data class Delete(val path: String) : TreeKeyResult
}

/**
 * What [key] does to the tree showing [rows] with [selected] selected and [expanded] open.
 *
 * With no (visible) selection, the movement keys select the first row; Enter/F2/Delete do nothing.
 * Type-ahead is case-insensitive and wraps: a single character looks from the row AFTER the
 * selection (so repeating a letter cycles through its rows), a longer prefix from the selection
 * itself (so typing on keeps the current row while it still matches).
 */
fun treeKeyAction(key: TreeKey, rows: List<TreeRow>, selected: String?, expanded: Set<String>): TreeKeyResult {
    if (rows.isEmpty()) return TreeKeyResult.Unhandled
    val index = if (selected == null) -1 else rows.indexOfFirst { it.path == selected }
    fun select(i: Int): TreeKeyResult =
        if (i == index) TreeKeyResult.Stay else TreeKeyResult.Select(rows[i].path)

    when (key) {
        TreeKey.Home -> return select(0)
        TreeKey.End -> return select(rows.lastIndex)
        is TreeKey.Type -> {
            val prefix = key.prefix.lowercase()
            if (prefix.isEmpty()) return TreeKeyResult.Unhandled
            val start = when {
                index < 0 -> 0
                prefix.length == 1 -> index + 1
                else -> index
            }
            for (k in rows.indices) {
                val i = (start + k) % rows.size
                if (rows[i].entry.name.lowercase().startsWith(prefix)) return select(i)
            }
            return TreeKeyResult.Stay
        }
        else -> Unit
    }

    if (index < 0) {
        return when (key) {
            TreeKey.Up, TreeKey.Down, TreeKey.Left, TreeKey.Right -> TreeKeyResult.Select(rows[0].path)
            else -> TreeKeyResult.Unhandled
        }
    }
    val row = rows[index]
    val isDir = row.status != RowStatus.FILE
    val isOpen = isDir && row.path in expanded
    return when (key) {
        TreeKey.Up -> select((index - 1).coerceAtLeast(0))
        TreeKey.Down -> select((index + 1).coerceAtMost(rows.lastIndex))
        TreeKey.Right -> when {
            !isDir -> TreeKeyResult.Stay
            !isOpen -> TreeKeyResult.Expand(row.path)
            // Rows are depth-first, so an open folder's first child (if listed) is the next row.
            // A loading folder, an empty one, or a symlink loop has none: stay.
            else -> rows.getOrNull(index + 1)?.takeIf { it.depth == row.depth + 1 }
                ?.let { TreeKeyResult.Select(it.path) } ?: TreeKeyResult.Stay
        }
        TreeKey.Left -> when {
            isOpen -> TreeKeyResult.Collapse(row.path)
            else -> (index - 1 downTo 0).firstOrNull { rows[it].depth == row.depth - 1 }
                ?.let { TreeKeyResult.Select(rows[it].path) } ?: TreeKeyResult.Stay
        }
        TreeKey.Enter -> TreeKeyResult.Activate(row.path)
        TreeKey.Rename -> TreeKeyResult.Rename(row.path)
        TreeKey.Delete -> TreeKeyResult.Delete(row.path)
        else -> TreeKeyResult.Unhandled
    }
}
