package dev.supermux.ui.files

import dev.supermux.fs.DirState
import dev.supermux.fs.snapshotOrPrevious
import dev.supermux.net.FsEntry

enum class RowStatus { FILE, CLOSED, OPEN, LOADING, ERROR, LOOP }

data class TreeRow(val path: String, val depth: Int, val entry: FsEntry, val status: RowStatus, val error: String? = null)

val FsEntry.isDirLike: Boolean get() = type == "dir" || (type == "symlink" && target == "dir")

/**
 * Depth-first rows for [root] and every expanded folder, using each folder's current or previous
 * snapshot. A folder whose REAL path is already one of its ancestors' real paths (symlink loop) is
 * shown with [RowStatus.LOOP] and never descended into.
 */
fun flattenTree(root: String, expanded: Set<String>, dirOf: (String) -> DirState): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun realOf(path: String): String? = dirOf(path).snapshotOrPrevious?.real
    fun walk(dir: String, depth: Int, seenReals: Set<String>) {
        val entries = dirOf(dir).snapshotOrPrevious?.entries ?: return
        for (e in entries) {
            val p = childOf(dir, e.name)
            if (!e.isDirLike) { out += TreeRow(p, depth, e, RowStatus.FILE); continue }
            if (p !in expanded) { out += TreeRow(p, depth, e, RowStatus.CLOSED); continue }
            val st = dirOf(p)
            val real = realOf(p)
            if (real != null && real in seenReals) { out += TreeRow(p, depth, e, RowStatus.LOOP); continue }
            when (st) {
                is DirState.Failed -> out += TreeRow(p, depth, e, RowStatus.ERROR, st.message.ifBlank { st.code })
                is DirState.Loading -> if (st.previous == null) out += TreeRow(p, depth, e, RowStatus.LOADING) else {
                    out += TreeRow(p, depth, e, RowStatus.OPEN); walk(p, depth + 1, seenReals + (real ?: p))
                }
                is DirState.Ready -> { out += TreeRow(p, depth, e, RowStatus.OPEN); walk(p, depth + 1, seenReals + (real ?: p)) }
                DirState.Unloaded -> out += TreeRow(p, depth, e, RowStatus.LOADING)
                DirState.Gone -> out += TreeRow(p, depth, e, RowStatus.CLOSED)
            }
        }
    }
    walk(root, 0, setOfNotNull(realOf(root) ?: root))
    return out
}
