// File-tree / search UI state, split out of EditorState.kt (which still delegates to it, so every
// call site is unchanged). Pure state — the loading itself lives in FileTree.kt.
package dev.supermux.desktop.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** File tree UI state — survives panel / session switches while composed. */
class ExplorerState {
    val treeRoot = mutableStateListOf<TreeNode>()
    var treeRootLoaded by mutableStateOf(false)
    var expandedPaths by mutableStateOf(setOf<String>())
    var treeLoadingPaths by mutableStateOf(setOf<String>())
    var treeVisible by mutableStateOf<Boolean?>(null)
    var searchQuery by mutableStateOf("")

    /** Per-directory tree-listing errors (path → message) surfaced as an inline row (M3-T4). */
    var treeLoadError by mutableStateOf<Map<String, String>>(emptyMap())
}
