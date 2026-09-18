// What an extra window draws: its slice of a workspace's layout (this was desktop's
// `DetachedWorkspaceWindow`, shared so Android's extra activity draws the same thing).
//
// The panes are the SHARED `WorkspacePanes`; this is only the wiring that hands them the right
// slice. The window around it — a desktop `Window {}`, an Android activity — is the host's.
package dev.supermux.ui.shell.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.supermux.proto.ViewDto
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.PaneStripChrome
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.WorkspacePanes
import dev.supermux.ui.shell.WorkspacePanesBind
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.viewTitle

/** Extra window caption: workspace name plus the active view on this host. */
fun extraWindowTitle(
    workspaceName: String,
    hosted: LayoutNode?,
    viewsById: Map<String, ViewDto>,
): String {
    val activeId = hosted?.let { collectActiveViewIds(it).firstOrNull() }
    val viewPart = activeId?.let { viewsById[it] }?.let { viewTitle(it) }
    return if (viewPart.isNullOrBlank()) workspaceName else "$workspaceName — $viewPart"
}

/**
 * The panes of extra window [hostId]: the slice of [bind]'s workspace that window claims.
 *
 * [onTearOutTab] tears a tab out of THIS window into yet another one — the host's verb, because
 * only the host knows how to open the window that results.
 */
@Composable
fun ExtraWindowPanes(
    hostId: String,
    bind: WorkspacePanesBind,
    ui: ShellUiState,
    modifier: Modifier,
    onTearOutTab: (String) -> Unit,
    stripChrome: PaneStripChrome = PaneStripChrome.None,
) {
    val current = bind.current
    val ws = bind.ws
    val hosted = ui.windows.layoutFor(hostId, ws.layoutSync.tree)
    val tabDragState = remember(hostId) { PaneDragController() }
    var closeCandidate by remember(hostId) { mutableStateOf<ViewDto?>(null) }
    val sessionNames = remember(current) { emptyMap<String, String>() }

    WorkspacePanes(
        hostId = hostId,
        layout = hosted,
        current = current,
        session = bind.session,
        ws = ws,
        app = bind.app,
        appFor = bind.appFor,
        ui = ui,
        drafts = bind.drafts,
        overlayScope = bind.overlayScope,
        launcherPane = bind.launcherPane,
        tabDragState = tabDragState,
        closeCandidate = closeCandidate,
        onCloseCandidate = { closeCandidate = it },
        sessionNames = sessionNames,
        modifier = modifier,
        onTearOutTab = onTearOutTab,
        stripChrome = stripChrome,
    )
}

/**
 * Tear [viewId] out of whichever window shows it now into a new extra window of [registry], over
 * [bind]'s live layout. The tree edit (splitting the tab into its own group) goes through the
 * layout sync, so it is PATCHed like any other. Returns the new host, or null when nothing moved.
 */
fun tearOutTabFrom(
    registry: WindowHostRegistry,
    bind: WorkspacePanesBind,
    viewId: String,
): WindowHost? {
    val layoutSync = bind.ws.layoutSync
    return tearOutTabLive(registry, layoutSync.tree, viewId, bind.current.id) { next ->
        layoutSync.edit { next }
        layoutSync.tree
    }
}
