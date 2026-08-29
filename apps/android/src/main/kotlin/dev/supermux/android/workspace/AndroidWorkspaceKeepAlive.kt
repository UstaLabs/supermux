package dev.supermux.android.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.WorkspaceKeepAliveCache

@Composable
fun rememberVisitedWorkspaces(
    selected: String?,
    liveIds: Set<String>,
    maxSize: Int = 10,
    cache: WorkspaceKeepAliveCache = remember(maxSize) { WorkspaceKeepAliveCache(maxSize = maxSize) },
): Set<String> {
    val liveSnapshot = liveIds.toSet()
    val retained = cache.preview(selected, liveSnapshot)
    SideEffect { cache.commit(retained) }
    return retained.toSet()
}

@Composable
fun AndroidWorkspaceKeepAliveHost(
    activeWorkspaceId: String?,
    retainedIds: Set<String>,
    workspaces: List<WorkspaceDto>,
    content: @Composable (workspace: WorkspaceDto, visible: Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        retainedIds.forEach { id ->
            val ws = workspaces.firstOrNull { it.id == id } ?: return@forEach
            key(id) {
                Box(Modifier.keepAlivePanel(id == activeWorkspaceId)) {
                    content(ws, id == activeWorkspaceId)
                }
            }
        }
    }
}
