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
        if (!node.loaded && node.children != null) {
            editor.treeLoadingPaths = editor.treeLoadingPaths + node.path
            scope.launch {
                try {
                    node.children!!.clear()
                    node.children!!.addAll(loadDir(node.path))
                    node.loaded = true
                } finally {
                    editor.treeLoadingPaths = editor.treeLoadingPaths - node.path
                }
            }
        }
        editor.expandedPaths = editor.expandedPaths + node.path
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
    onClick: (TreeNode) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDir = node.entry.type == "dir"
    val isOpen = expanded.contains(node.path)
    val isLoading = loading.contains(node.path)
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

    if (isDir && isOpen && node.children != null) {
        node.children.forEach { child ->
            TreeNodeRow(
                node = child,
                depth = depth + 1,
                expanded = expanded,
                loading = loading,
                onClick = onClick,
            )
        }
    }
}

private val SpaceEnd = 8.dp
