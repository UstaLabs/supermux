// In-place (VS Code-style) New file / New folder / Rename for the Files tree, used when a hardware
// keyboard is available (`LocalHardwareKeyboard`). Touch-only devices keep the dialogs.
//
// This file holds the pure half: the edit model, where a new entry's temporary row goes, and what a
// rename pre-selects. The field itself is [InlineNameField]; the tree draws it (FileTreeView) and
// FileTreeWithActions supplies the ops.
package dev.supermux.ui.files

import androidx.compose.runtime.Immutable

/** The in-place edit a Files pane has open, or none. Lives in [TreeViewState.inlineEdit]. */
@Immutable
sealed interface InlineEdit {
    /** The row at [path] shows a name field instead of its name. */
    data class Rename(val path: String) : InlineEdit

    /** A temporary row with an empty name field sits among [parent]'s children. */
    data class Create(val parent: String, val folder: Boolean) : InlineEdit
}

/** Where a [InlineEdit.Create]'s temporary row goes in the flattened rows: before [index], at [depth]. */
data class InlineSlot(val index: Int, val depth: Int)

/**
 * The slot for [edit]'s temporary row among [rows] (the tree showing [root]): at the top of the
 * parent folder's children for a new folder, after the parent's child FOLDERS (and whatever is open
 * under them) for a new file — VS Code's placement, which matches the folders-first listing.
 *
 * Null when the parent isn't on screen (outside [root], or under a collapsed folder): the row
 * appears once it is. A parent whose listing hasn't arrived yet gets the slot right under it.
 */
fun inlineCreateSlot(rows: List<TreeRow>, root: String, edit: InlineEdit.Create): InlineSlot? {
    val start: Int
    val depth: Int
    if (trimSlash(edit.parent) == trimSlash(root)) {
        start = 0
        depth = 0
    } else {
        val at = rows.indexOfFirst { it.path == edit.parent }
        if (at < 0) return null
        val parent = rows[at]
        if (parent.status == RowStatus.FILE) return null
        start = at + 1
        depth = parent.depth + 1
    }
    // The parent's children run until the first row at a shallower depth.
    var end = start
    while (end < rows.size && rows[end].depth >= depth) end++
    if (edit.folder) return InlineSlot(start, depth)
    val firstFile = (start until end).firstOrNull { rows[it].depth == depth && rows[it].status == RowStatus.FILE }
    return InlineSlot(firstFile ?: end, depth)
}

/**
 * Where a rename's initial selection ends: before the extension of a file ("name" of "name.kt"),
 * so typing keeps ".kt"; the whole name for a folder or a dotfile (".env" has no stem to keep).
 */
fun renameSelectionEnd(name: String, folder: Boolean): Int =
    if (folder) name.length else name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

/**
 * What a blur (focus leaving the field) does, VS Code's rule: commit a valid, changed name; drop
 * everything else (empty, invalid, or a rename that kept its name) rather than keep a field open
 * that no longer has focus.
 */
fun commitsOnBlur(name: String, invalid: String?, current: String?): Boolean =
    invalid == null && name != current

private fun trimSlash(p: String) = if (p.length > 1) p.trimEnd('/') else p
