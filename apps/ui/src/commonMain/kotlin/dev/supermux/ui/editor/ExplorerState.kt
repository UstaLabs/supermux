// File-tree / search UI state, split out of EditorState.kt (which still delegates to it, so every
// call site is unchanged). Pure state — the loading itself lives in FileTree.kt.
package dev.supermux.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.workspace.TreeNode

/** File tree UI state — survives panel / session switches while composed. */
class ExplorerState {
    val treeRoot = mutableStateListOf<TreeNode>()
    var treeRootLoaded by mutableStateOf(false)
    var expandedPaths by mutableStateOf(setOf<String>())
    var treeLoadingPaths by mutableStateOf(setOf<String>())
    var treeVisible by mutableStateOf<Boolean?>(null)
    var searchQuery by mutableStateOf("")

    /**
     * The workdir this state's listings belong to, or null before [FileTree] has ever composed for
     * it. It lives HERE and not in the composable because both apps compose the tree CONDITIONALLY
     * (Android hides it behind `treeVisible` / a drawer): a marker held in the composable is lost
     * every time the tree is closed, so a workdir change while it was hidden would never reset and
     * the old checkout's tree would come back. The state object outlives those toggles.
     */
    var seenWorkdir: String? = null

    /** Per-directory tree-listing errors (path → message) surfaced as an inline row (M3-T4). */
    var treeLoadError by mutableStateOf<Map<String, String>>(emptyMap())

    /**
     * Drop every tree listing so the next composition re-lists from the root. Called by [FileTree]
     * when its `workdir` changes: the hosts `remember(workspaceId)` this object, so a session that
     * merely changes workdir (or a workspace re-pointed at another checkout) used to keep showing
     * the PREVIOUS tree — expanded paths, cached children and stale error rows included — until the
     * state object itself was recreated.
     *
     * Deliberately does NOT touch [treeVisible] or [searchQuery]: those are the user's layout and
     * filter choices, not listings of the old workdir.
     */
    fun reset() {
        treeRoot.clear()
        treeRootLoaded = false
        expandedPaths = emptySet()
        treeLoadingPaths = emptySet()
        treeLoadError = emptyMap()
    }
}
