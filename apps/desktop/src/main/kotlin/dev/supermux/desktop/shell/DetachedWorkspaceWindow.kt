// One extra OS window showing a slice of a workspace's layout (desktop only).
//
// The panes themselves are the SHARED `ui/shell/WorkspacePanes.kt` (cluster G8) — this file is
// just the window's caption and the wiring that hands it the right slice and the right tear-out.
package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.proto.ViewDto
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.WorkspacePanes
import dev.supermux.ui.shell.WorkspacePanesBind
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.viewTitle

/**
 * Extra OS window caption: workspace name plus the active view on this host.
 */
internal fun extraWindowTitle(
    workspaceName: String,
    hosted: LayoutNode?,
    viewsById: Map<String, ViewDto>,
): String {
    val activeId = hosted?.let { collectActiveViewIds(it).firstOrNull() }
    val viewPart = activeId?.let { viewsById[it] }?.let { viewTitle(it) }
    return if (viewPart.isNullOrBlank()) workspaceName else "$workspaceName — $viewPart"
}

@Composable
internal fun DetachedWorkspaceWindow(
    host: WindowHost,
    bind: WorkspacePanesBind,
    ui: ShellUiState,
) {
    val current = bind.current
    val ws = bind.ws
    val hosted = ui.windows.layoutFor(host.id, ws.layoutSync.tree)
    val tabDragState = remember(host.id) { PaneDragController() }
    var closeCandidate by remember(host.id) { mutableStateOf<ViewDto?>(null) }
    val sessionNames = remember(current) { emptyMap<String, String>() }

    WorkspacePanes(
        hostId = host.id,
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
        modifier = Modifier.fillMaxSize().testTag("workspace_layout_host_extra"),
        onTearOutTab = { viewId ->
            val layoutSync = bind.ws.layoutSync
            tearOutTabLive(
                (ui.windows as? DesktopShellWindows)?.registry ?: return@WorkspacePanes,
                layoutSync.tree,
                viewId,
                current.id,
            ) { next ->
                layoutSync.edit { next }
                layoutSync.tree
            }
        },
        stripChrome = DesktopStripChrome,
    )
}
