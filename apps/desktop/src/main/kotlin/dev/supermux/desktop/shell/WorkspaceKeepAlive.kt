package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.desktop.ui.KeepAlivePanel

internal const val MAX_RETAINED_WORKSPACES = 10

internal class WorkspaceKeepAliveCache(
    private val maxSize: Int = MAX_RETAINED_WORKSPACES,
) {
    private val retained = linkedSetOf<String>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    fun update(activeWorkspaceId: String?, liveWorkspaceIds: Set<String>): List<String> {
        val candidate = preview(activeWorkspaceId, liveWorkspaceIds)
        commit(candidate)
        return candidate
    }

    fun preview(activeWorkspaceId: String?, liveWorkspaceIds: Set<String>): List<String> {
        val candidate = retained.filterTo(linkedSetOf()) { it in liveWorkspaceIds }
        if (activeWorkspaceId != null && activeWorkspaceId in liveWorkspaceIds) {
            candidate.remove(activeWorkspaceId)
            candidate.add(activeWorkspaceId)
        }
        while (candidate.size > maxSize) candidate.remove(candidate.first())
        return candidate.toList()
    }

    fun commit(candidate: List<String>) {
        retained.clear()
        retained.addAll(candidate)
    }
}

@Composable
internal fun WorkspaceKeepAliveHost(
    activeWorkspaceId: String?,
    liveWorkspaceIds: Set<String>,
    showActive: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable (workspaceId: String, active: Boolean) -> Unit,
) {
    val cache = remember { WorkspaceKeepAliveCache() }
    val liveSnapshot = liveWorkspaceIds.toSet()
    val retained = cache.preview(activeWorkspaceId, liveSnapshot)
    SideEffect {
        cache.commit(retained)
    }

    Box(modifier.fillMaxSize()) {
        retained.forEach { workspaceId ->
            val active = showActive && workspaceId == activeWorkspaceId
            key(workspaceId) {
                KeepAlivePanel(
                    visible = active,
                    modifier = Modifier.testTag("workspace-layer-$workspaceId"),
                ) {
                    content(workspaceId, active)
                }
            }
        }
    }
}
