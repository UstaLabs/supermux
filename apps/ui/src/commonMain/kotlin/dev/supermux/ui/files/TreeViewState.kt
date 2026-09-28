// What ONE Files pane is looking at. Folder CONTENTS live in the host's FileSystemService and are
// shared; this is only the view: where the tree starts, which folders are open, what's selected,
// and the scroll position. Held per view id in [TreeViewStates], not in `remember {}`, so dragging,
// splitting or re-tabbing the pane keeps it.
package dev.supermux.ui.files

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class TreeViewState(rootPath: String) {
    /** The workspace workdir this state was created for; [rootPath] may move above it. */
    val workdir: String = rootPath
    var rootPath by mutableStateOf(rootPath)
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    var selected by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    val list = LazyListState()

    fun isExpanded(path: String) = path in expanded
    fun expand(path: String) { expanded = expanded + path }
    fun toggle(path: String) {
        expanded = if (path in expanded) expanded.filterNot { it == path || isWithin(path, it) }.toSet() else expanded + path
    }
    /** Drop [path] and everything under it (folder gone, or collapsed). */
    fun prune(path: String) { expanded = expanded.filterNot { it == path || isWithin(path, it) }.toSet() }
    fun collapseAll() { expanded = emptySet() }

    /** Expand every ancestor of [path] under [rootPath] and select it. */
    fun reveal(path: String) {
        if (!isWithin(rootPath, path)) return // nothing to show outside the tree's root
        val anc = ancestorsWithin(rootPath, path).filter { it != rootPath }
        if (anc.isNotEmpty()) expanded = expanded + anc
        selected = path
    }
}

/** Per-host, in-memory holder: one [TreeViewState] per Files view id. */
class TreeViewStates {
    private val byView = HashMap<String, TreeViewState>()

    fun forView(viewId: String, workdir: String): TreeViewState {
        val cur = byView[viewId]
        if (cur != null && cur.workdir == workdir) return cur
        return TreeViewState(workdir).also { byView[viewId] = it }
    }

    fun forget(viewId: String) { byView.remove(viewId) }
}
