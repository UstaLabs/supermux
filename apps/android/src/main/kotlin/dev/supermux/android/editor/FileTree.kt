package dev.supermux.android.editor

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.rememberHaptics
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
    val haptic = rememberHaptics()
    val isDir = node.entry.type == "dir"
    val isOpen = expanded.contains(node.path)
    val isLoading = loading.contains(node.path)
    val alpha = if (node.entry.ignored) 0.5f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic(HapticKind.Tick)
                onClick(node)
            }
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
                    painter = painterResource(
                        if (isOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                    ),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Icon(
            painter = painterResource(if (isDir) R.drawable.ic_folder_open else R.drawable.ic_file),
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
