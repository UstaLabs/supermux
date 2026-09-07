// Keeping workspaces composed while they are not on screen.
//
// Cluster G8 merged the two hosts' copies: desktop's `WorkspaceKeepAliveHost` (a
// [WorkspaceKeepAliveCache] over the live workspace ids, plus the ids extra OS windows still
// claim) is the base, and Android's `rememberVisitedWorkspaces` — the same cache expressed as a
// remembered set, which the phone tab strip also uses at `maxSize = 3` for VIEWS — comes with it.
package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.widgets.KeepAlivePanel
import dev.supermux.workspace.MAX_RETAINED_WORKSPACES
import dev.supermux.workspace.WorkspaceKeepAliveCache

/**
 * Composes [content] for every RETAINED workspace, showing only the active one.
 *
 * [extraRetainIds] are workspaces something else still needs mounted — on desktop, the ones an
 * extra OS window claims a slice of. [showActive] hides the active panel without disposing it
 * (a full-pane overlay is up).
 */
@Composable
fun WorkspaceKeepAliveHost(
    activeWorkspaceId: String?,
    liveWorkspaceIds: Set<String>,
    showActive: Boolean = true,
    extraRetainIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    content: @Composable (workspaceId: String, active: Boolean) -> Unit,
) {
    val cache = remember { WorkspaceKeepAliveCache() }
    val liveSnapshot = liveWorkspaceIds.toSet()
    val retained = cache.preview(activeWorkspaceId, liveSnapshot, extraRetainIds)
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

/**
 * The retained id set on its own, for callers that lay the panels out themselves.
 *
 * Android's phone/tablet hosts use it for WORKSPACES, and the phone tab strip reuses it for the
 * last three visited VIEWS (a WebView or a PTY per hidden tab is as much as a phone can hold).
 */
@Composable
fun rememberVisitedWorkspaces(
    selected: String?,
    liveIds: Set<String>,
    maxSize: Int = MAX_RETAINED_WORKSPACES,
    cache: WorkspaceKeepAliveCache = remember(maxSize) { WorkspaceKeepAliveCache(maxSize = maxSize) },
): Set<String> {
    val liveSnapshot = liveIds.toSet()
    val retained = cache.preview(selected, liveSnapshot)
    SideEffect { cache.commit(retained) }
    return retained.toSet()
}
