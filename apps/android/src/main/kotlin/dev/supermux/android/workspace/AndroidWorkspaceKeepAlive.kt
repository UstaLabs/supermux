package dev.supermux.android.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.workspace.WorkspaceKeepAliveCache

@Composable
fun rememberVisitedWorkspaces(
    selected: String?,
    liveWorkspaceIds: Set<String>,
    cache: WorkspaceKeepAliveCache = remember { WorkspaceKeepAliveCache() },
): Set<String> {
    var retained by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(selected, liveWorkspaceIds) {
        retained = keepAliveWorkspaceIds(cache, selected, liveWorkspaceIds).toSet()
    }
    return retained
}

@Composable
fun AndroidWorkspaceKeepAliveHost(
    activeWorkspaceId: String?,
    retainedIds: Set<String>,
    workspaces: List<dev.supermux.proto.WorkspaceDto>,
    content: @Composable (workspace: dev.supermux.proto.WorkspaceDto, visible: Boolean) -> Unit,
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
