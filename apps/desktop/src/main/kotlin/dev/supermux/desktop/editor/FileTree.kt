// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/FileTree.kt — keep in sync
// until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - Bundled drawables → compose.materialIconsExtended:
//       ic_chevron_right → Icons.Filled.ChevronRight  (reuses the SessionsRail.kt mapping for the
//         same drawable)
//       ic_chevron_down  → Icons.Filled.KeyboardArrowDown     (Material has no distinct "chevron down"
//         glyph; KeyboardArrowDown is the same chevron family, just pointing down, so it pairs visually
//         with ChevronRight for the collapsed/expanded pair)
//       ic_folder_open   → Icons.Filled.FolderOpen
//       ic_file          → Icons.Filled.InsertDriveFile
//     Directories always render the FolderOpen glyph regardless of expanded state — that mirrors
//     Android exactly (a pre-existing simplification there, not something introduced here).
//   - `rememberHaptics()(HapticKind.Tick)` on node click dropped — no haptic actuator on desktop,
//     and no other ported desktop file wires the no-op haptics stub at a call site (theme/Haptics.kt
//     exists but is uncalled), so this follows that established convention.
//   - `pointerHoverIcon(PointerIcon.Hand)` added to the clickable node row (desktop mouse
//     affordance; Android is touch-only).
package dev.supermux.desktop.editor

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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
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
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.net.FsEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class TreeNode(
    val entry: FsEntry,
    val path: String,
    val children: MutableList<TreeNode>? = null,
    var loaded: Boolean = false,
)

internal fun childPath(parent: String, name: String): String =
    if (parent == ".") name else "$parent/$name"

internal fun List<FsEntry>.sortedForTree(): List<FsEntry> =
    sortedWith(
        compareBy<FsEntry> { if (it.type == "dir") 0 else 1 }
            .thenBy { it.name.lowercase() },
    )

/**
 * Load [node]'s children via [loadDir] and, ON SUCCESS ONLY, mark it expanded. Split out of the
 * composable so its failure path is unit-testable without Compose (M3-T4 obligation): an fsList
 * failure does NOT add the node to `expandedPaths` (an empty dir would look identical to a failed
 * one otherwise), records the message in `editor.treeLoadError` for an inline error row, and logs.
 * A success clears any prior error for the path. `treeLoadingPaths` is always cleared in `finally`.
 */
internal suspend fun loadAndExpand(
    editor: EditorState,
    node: TreeNode,
    loadDir: suspend (String) -> List<TreeNode>,
) {
    // In-flight guard: a second tap while the listing is loading must NOT launch a duplicate fsList
    // (the expand-only-on-success change means the path isn't yet in expandedPaths / node.loaded, so
    // toggleDir's own guards don't catch it) — two loaders racing on the shared node.children list
    // would double every child row. The check + add below is synchronous (no suspension before the
    // loadDir call), so on the single-threaded UI dispatcher only the first loader passes.
    if (node.path in editor.treeLoadingPaths) return
    editor.treeLoadingPaths = editor.treeLoadingPaths + node.path
    try {
        val children = loadDir(node.path)
        node.children?.apply { clear(); addAll(children) }
        node.loaded = true
        editor.treeLoadError = editor.treeLoadError - node.path
        editor.expandedPaths = editor.expandedPaths + node.path // expand ONLY after a good listing
    } catch (e: CancellationException) {
        throw e // never swallow a real coroutine cancellation
    } catch (e: Throwable) {
        editor.treeLoadError = editor.treeLoadError + (node.path to (e.message ?: "Could not list directory"))
        println("[FileTree] loadDir('${node.path}') failed: $e")
    } finally {
        editor.treeLoadingPaths = editor.treeLoadingPaths - node.path
    }
}

@Composable
fun FileTree(
    fsList: suspend (String) -> List<FsEntry>,
    editor: EditorState,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    suspend fun loadDir(path: String): List<TreeNode> =
        fsList(path).sortedForTree().map { entry ->
            TreeNode(
                entry = entry,
                path = childPath(path, entry.name),
                children = if (entry.type == "dir") mutableListOf() else null,
            )
        }

    LaunchedEffect(Unit) {
        if (!editor.treeRootLoaded) {
            editor.treeRoot.clear()
            editor.treeRoot.addAll(loadDir("."))
            editor.treeRootLoaded = true
        }
    }

    fun toggleDir(node: TreeNode) {
        if (editor.expandedPaths.contains(node.path)) {
            editor.expandedPaths = editor.expandedPaths - node.path
            return
        }
        if (node.loaded || node.children == null) {
            // Already listed (or somehow a file): expand immediately, no fs round-trip.
            editor.expandedPaths = editor.expandedPaths + node.path
            return
        }
        // Not yet listed: load first and expand ONLY on success (see [loadAndExpand]). A prior error
        // is retried on tap; the failure path re-records it rather than expanding into a blank dir.
        scope.launch { loadAndExpand(editor, node, ::loadDir) }
    }

    fun onNodeClick(node: TreeNode) {
        if (node.entry.type == "dir") toggleDir(node) else onOpenFile(node.path)
    }

    LazyColumn(modifier.fillMaxSize()) {
        items(editor.treeRoot, key = { it.path }) { node ->
            TreeNodeRow(
                node = node,
                depth = 0,
                expanded = editor.expandedPaths,
                loading = editor.treeLoadingPaths,
                errors = editor.treeLoadError,
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
    val isDir = node.entry.type == "dir"
    val isOpen = expanded.contains(node.path)
    val isLoading = loading.contains(node.path)
    val loadError = errors[node.path]
    val alpha = if (node.entry.ignored) 0.5f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(node) }
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

    // Inline listing-error row (M3-T4): a failed fsList leaves the dir collapsed and shows why here,
    // just under the offending directory row. Tapping the dir again retries the listing.
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

    if (isDir && isOpen && node.children != null) {
        node.children.forEach { child ->
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
