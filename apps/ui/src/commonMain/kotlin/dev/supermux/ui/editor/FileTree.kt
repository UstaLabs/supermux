// Shared file-tree explorer (UI cluster B, task B3): one implementation for both apps.
//
// Desktop's copy won the body — `loadAndExpand` with its in-flight guard over
// `ExplorerState.treeLoadingPaths`, expand-ONLY-on-success, `CancellationException` rethrown, and
// the inline `treeLoadError` row that a re-tap retries — plus Material icons in place of the
// bundled drawables (ic_chevron_right → ChevronRight, ic_chevron_down → KeyboardArrowDown,
// ic_folder_open → FolderOpen, ic_file → InsertDriveFile) and `pointerHoverIcon(Hand)` on the row.
// Android's `rememberHaptics().perform(HapticKind.Tick)` on node click is folded back in (the
// desktop haptics implementation is a no-op).
package dev.supermux.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.FsEntry
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.workspace.TreeNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun childPath(parent: String, name: String): String =
    if (parent == ".") name else "$parent/$name"

internal fun List<FsEntry>.sortedForTree(): List<FsEntry> =
    sortedWith(
        compareBy<FsEntry> { if (it.type == "dir") 0 else 1 }
            .thenBy { it.name.lowercase() },
    )

/**
 * Load [node]'s children via [loadDir] and, ON SUCCESS ONLY, mark it expanded. Split out of the
 * composable so its failure path is unit-testable without Compose: a listing failure does NOT add
 * the node to `expandedPaths` (an empty dir would look identical to a failed one otherwise),
 * records the message in `explorer.treeLoadError` for an inline error row, and logs.
 * A success clears any prior error for the path. `treeLoadingPaths` is always cleared in `finally`.
 *
 * [loadDir] returns a `Result` (cluster C1): the broker error used to be swallowed into an empty
 * list by `HostStore.fsList`, which made the error row below unreachable — a failed listing was
 * indistinguishable from an empty directory. A thrown failure is still handled, so either shape
 * reaches the same error state.
 */
internal suspend fun loadAndExpand(
    explorer: ExplorerState,
    node: TreeNode,
    loadDir: suspend (String) -> Result<List<TreeNode>>,
) {
    // In-flight guard: a second tap while the listing is loading must NOT launch a duplicate fsList
    // (the expand-only-on-success change means the path isn't yet in expandedPaths / node.loaded, so
    // toggleDir's own guards don't catch it) — two loaders racing on the shared node.children list
    // would double every child row. The check + add below is synchronous (no suspension before the
    // loadDir call), so on the single-threaded UI dispatcher only the first loader passes.
    if (node.path in explorer.treeLoadingPaths) return
    explorer.treeLoadingPaths = explorer.treeLoadingPaths + node.path
    try {
        val children = loadDir(node.path).getOrElse { err ->
            if (err is CancellationException) throw err
            explorer.treeLoadError =
                explorer.treeLoadError + (node.path to (err.message ?: "Could not list directory"))
            println("[FileTree] loadDir('${node.path}') failed: $err")
            return@loadAndExpand // no expand: an error row, not a blank directory
        }
        node.children?.apply { clear(); addAll(children) }
        node.loaded = true
        explorer.treeLoadError = explorer.treeLoadError - node.path
        explorer.expandedPaths = explorer.expandedPaths + node.path // expand ONLY after a good listing
    } catch (e: CancellationException) {
        throw e // never swallow a real coroutine cancellation
    } catch (e: Throwable) {
        explorer.treeLoadError = explorer.treeLoadError + (node.path to (e.message ?: "Could not list directory"))
        println("[FileTree] loadDir('${node.path}') failed: $e")
    } finally {
        explorer.treeLoadingPaths = explorer.treeLoadingPaths - node.path
    }
}

@Composable
fun FileTree(
    fsList: suspend (String) -> Result<List<FsEntry>>,
    explorer: ExplorerState,
    workdir: String,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    suspend fun loadDir(path: String): Result<List<TreeNode>> =
        fsList(path).map { entries ->
            entries.sortedForTree().map { entry ->
                TreeNode(
                    entry = entry,
                    path = childPath(path, entry.name),
                    children = if (entry.type == "dir") mutableListOf() else null,
                )
            }
        }

    // The hosts `remember(workspaceId)` the ExplorerState, so a workdir change under the same
    // workspace used to leave the PREVIOUS checkout's tree on screen. `explorer.seenWorkdir` starts
    // null so the FIRST sight of a workdir only loads (no reset → no doubled root listing); every
    // later change resets and re-lists in the same effect, which is what makes the reload happen at
    // all (a separate `LaunchedEffect(Unit)` root load would never re-run). The marker is on the
    // STATE, not on this composable: both apps compose the tree conditionally, and a marker that
    // died with the composable would miss a workdir change made while the tree was hidden.
    LaunchedEffect(workdir) {
        if (explorer.seenWorkdir != null && explorer.seenWorkdir != workdir) explorer.reset()
        explorer.seenWorkdir = workdir
        if (!explorer.treeRootLoaded) {
            loadDir(".")
                .onSuccess { roots ->
                    explorer.treeRoot.clear()
                    explorer.treeRoot.addAll(roots)
                    explorer.treeLoadError = explorer.treeLoadError - "."
                    explorer.treeRootLoaded = true // only on success, so a retry is still possible
                }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    explorer.treeLoadError =
                        explorer.treeLoadError + ("." to (err.message ?: "Could not list directory"))
                    println("[FileTree] loadDir('.') failed: $err")
                }
        }
    }

    fun toggleDir(node: TreeNode) {
        if (explorer.expandedPaths.contains(node.path)) {
            explorer.expandedPaths = explorer.expandedPaths - node.path
            return
        }
        if (node.loaded || node.children == null) {
            // Already listed (or somehow a file): expand immediately, no fs round-trip.
            explorer.expandedPaths = explorer.expandedPaths + node.path
            return
        }
        // Not yet listed: load first and expand ONLY on success (see [loadAndExpand]). A prior error
        // is retried on tap; the failure path re-records it rather than expanding into a blank dir.
        scope.launch { loadAndExpand(explorer, node, ::loadDir) }
    }

    fun onNodeClick(node: TreeNode) {
        if (node.entry.type == "dir") toggleDir(node) else onOpenFile(node.path)
    }

    LazyColumn(modifier.fillMaxSize()) {
        items(explorer.treeRoot, key = { it.path }) { node ->
            TreeNodeRow(
                node = node,
                depth = 0,
                expanded = explorer.expandedPaths,
                loading = explorer.treeLoadingPaths,
                errors = explorer.treeLoadError,
                onClick = { onNodeClick(it) },
            )
        }
    }
}

@Composable
private fun TreeNodeRow(
    node: TreeNode,
    depth: Int,
    expanded: Set<String>,
    loading: Set<String>,
    errors: Map<String, String>,
    onClick: (TreeNode) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val isDir = node.entry.type == "dir"
    val isOpen = expanded.contains(node.path)
    val isLoading = loading.contains(node.path)
    val loadError = errors[node.path]
    val alpha = if (node.entry.ignored) 0.5f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic.perform(HapticKind.Tick)
                onClick(node)
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = (depth * 14 + 10).dp, end = SpaceEnd, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = cs.onSurfaceVariant,
                )
                isDir -> Icon(
                    imageVector = if (isOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Icon(
            imageVector = if (isDir) Icons.Filled.FolderOpen else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(16.dp),
        )
        Text(
            node.entry.name,
            color = cs.onSurface.copy(alpha = alpha),
            fontFamily = MonoFontFamily,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }

    // Inline listing-error row: a failed fsList leaves the dir collapsed and shows why here, just
    // under the offending directory row. Tapping the dir again retries the listing.
    if (isDir && loadError != null) {
        Text(
            loadError,
            color = cs.error,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 14 + 30).dp, end = SpaceEnd, top = 2.dp, bottom = 4.dp),
        )
    }

    val children = node.children
    if (isDir && isOpen && children != null) {
        children.forEach { child ->
            TreeNodeRow(
                node = child,
                depth = depth + 1,
                expanded = expanded,
                loading = loading,
                errors = errors,
                onClick = onClick,
            )
        }
    }
}

private val SpaceEnd = 8.dp
