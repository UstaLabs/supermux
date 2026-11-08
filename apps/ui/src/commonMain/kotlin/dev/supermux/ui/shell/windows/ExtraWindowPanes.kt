// What an extra window draws: its slice of a workspace's layout (this was desktop's
// `DetachedWorkspaceWindow`, shared so Android's extra activity draws the same thing).
//
// The panes are the SHARED `WorkspacePanes`; this is only the wiring that hands them the right
// slice. The window around it — a desktop `Window {}`, an Android activity — is the host's.
package dev.supermux.ui.shell.windows

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.RegisterCommand
import dev.supermux.ui.keys.ShortcutDispatcher
import dev.supermux.ui.keys.ShortcutRoot
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.PaneStripChrome
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.WorkspacePanes
import dev.supermux.ui.shell.WorkspacePanesBind
import dev.supermux.ui.shell.addTerminalInWindow
import dev.supermux.ui.work.LocalDownloads
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.ui.terminal.liveViewTitle

/** Extra window caption: workspace name plus the active view on this host. */
fun extraWindowTitle(
    workspaceName: String,
    hosted: LayoutNode?,
    viewsById: Map<String, ViewDto>,
    sessionNames: Map<String, String> = emptyMap(),
): String {
    val activeId = hosted?.let { collectActiveViewIds(it).firstOrNull() }
    val viewPart = activeId?.let { viewsById[it] }?.let { liveViewTitle(it, sessionNames::get) }
    return if (viewPart.isNullOrBlank()) workspaceName else "$workspaceName — $viewPart"
}

/**
 * The panes of extra window [hostId]: the slice of [bind]'s workspace that window claims. It is also that
 * window's keyboard root ([ShortcutRoot]), so a host puts no other around it.
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
    /**
     * The workspace as this window knows it, and its session. The bind's (the main window's)
     * where every window is one composition (desktop); a window of its own ([ExtraWindowHost])
     * passes its own, so it stays current while the main window is paused off screen.
     */
    current: WorkspaceDto = bind.current,
    ws: WorkspaceSession = bind.ws,
) {
    val hosted = ui.windows.layoutFor(hostId, ws.layoutSync.tree)
    val tabDragState = remember(hostId) { PaneDragController() }
    var closeCandidate by remember(hostId) { mutableStateOf<ViewDto?>(null) }
    ShortcutRoot(remember(hostId) { ShortcutDispatcher() }, modifier) {
        // A torn-off window has the pane and workspace keys; the window-level ones (new session, settings,
        // sidebar) belong to the main window.
        RegisterCommand(AppCommands.NewTerminal) { addTerminalInWindow(ui, hostId, ws, bind.app, current.id); true }
        // This window may be a composition of its own, outside the shell's providers: the fleet's downloads come
        // with the workspace (#95), so one started here outlives the window too.
        CompositionLocalProvider(LocalDownloads provides (bind.downloads ?: LocalDownloads.current)) {
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
            sessionNames = bind.sessionNames,
            unreadSessions = bind.unreadSessions,
            modifier = Modifier.fillMaxSize(),
            onTearOutTab = onTearOutTab,
            stripChrome = stripChrome,
        )
        }
    }
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
): WindowHost? = tearOutTabFrom(registry, bind.ws, bind.current.id, viewId)

/** [tearOutTabFrom] over one window's own [ws] of workspace [workspaceId]. */
fun tearOutTabFrom(
    registry: WindowHostRegistry,
    ws: WorkspaceSession,
    workspaceId: String,
    viewId: String,
): WindowHost? {
    val layoutSync = ws.layoutSync
    return tearOutTabLive(registry, layoutSync.tree, viewId, workspaceId) { transform ->
        layoutSync.edit(transform)
        layoutSync.tree
    }
}
