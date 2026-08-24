package dev.supermux.desktop.shell

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.workspace.chatSessionIds
import dev.supermux.proto.stateString
import dev.supermux.ui.panes.DefaultTabChip
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.PaneHost
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.splitGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

/**
 * Shared objects for extra windows of one workspace (selected or not).
 */
internal class WorkspacePanesBind(
    current: WorkspaceDto,
    session: SessionInfo?,
    ws: WorkspaceSession,
    app: DesktopAppState,
    appFor: (String) -> DesktopAppState,
    drafts: SnapshotStateMap<String, String>,
    overlayScope: CoroutineScope,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        joinWorkspaceId: String?,
        seedWorkdir: String?,
    ) -> Unit,
) {
    var current by mutableStateOf(current)
    var session by mutableStateOf(session)
    var ws by mutableStateOf(ws)
    var app by mutableStateOf(app)
    var appFor by mutableStateOf(appFor)
    var drafts by mutableStateOf(drafts)
    var overlayScope by mutableStateOf(overlayScope)
    var launcherPane by mutableStateOf(launcherPane)
}

/**
 * Keep a [WorkspaceSession] (and thus extra OS windows) alive for the selected
 * workspace and every workspace that still has a pop-out.
 */
@Composable
internal fun KeepWorkspacePanesBinds(
    ui: ShellUiState,
    workspaces: List<WorkspaceDto>,
    sessions: List<SessionInfo>,
    app: DesktopAppState,
    appFor: (String) -> DesktopAppState,
    drafts: SnapshotStateMap<String, String>,
    overlayScope: CoroutineScope,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        joinWorkspaceId: String?,
        seedWorkdir: String?,
    ) -> Unit,
) {
    val selectedId = ui.selectedId
    val selectedWsId = workspaces.firstOrNull { w ->
        selectedId != null && (w.id == selectedId || w.chatSessionIds().contains(selectedId))
    }?.id
    val needed = workspaceIdsNeedingSession(
        selectedWsId,
        ui.windowHosts.extras().map { it.workspaceId },
    )
    for (wid in needed) {
        val w = workspaces.firstOrNull { it.id == wid } ?: continue
        key(wid) {
            val wsApp = appFor(w.primarySessionId ?: "")
            val ws = rememberWorkspaceSession(w, wsApp, overlayScope)
            val sess = sessions.firstOrNull { it.id == w.primarySessionId }
            val bind = remember(wid) {
                WorkspacePanesBind(w, sess, ws, app, appFor, drafts, overlayScope, launcherPane)
            }
            bind.current = w
            bind.session = sess
            bind.ws = ws
            bind.app = app
            bind.appFor = appFor
            bind.drafts = drafts
            bind.overlayScope = overlayScope
            bind.launcherPane = launcherPane
            ui.panesBinds[wid] = bind
            DisposableEffect(wid) {
                onDispose { ui.panesBinds.remove(wid) }
            }
            LaunchedEffect(wid, ws.layoutSync.tree) {
                ui.windowHosts.rebase(wid, ws.layoutSync.tree)
                ui.tryRestoreWindowHosts(wid, ws.layoutSync.tree)
            }
        }
    }
}

@Composable
internal fun DetachedWorkspaceWindow(
    host: WindowHost,
    bind: WorkspacePanesBind,
    ui: ShellUiState,
) {
    val current = bind.current
    val ws = bind.ws
    val layoutSync = ws.layoutSync
    val viewsById = ws.viewsById
    val documents = ws.documents
    val previewModes = ws.previewModes
    val fileOpener = ws.fileOpener
    val hosted = ui.windowHosts.layoutFor(host, layoutSync.tree)
        ?: emptyHostLayout(layoutSync.tree)
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
            tearOutTabLive(ui.windowHosts, layoutSync.tree, viewId, current.id) { next ->
                layoutSync.edit { next }
                layoutSync.tree
            }
        },
    )
}

@Composable
internal fun WorkspacePanes(
    hostId: String,
    layout: LayoutNode,
    current: WorkspaceDto,
    session: SessionInfo?,
    ws: WorkspaceSession,
    app: DesktopAppState,
    appFor: (String) -> DesktopAppState,
    ui: ShellUiState,
    drafts: SnapshotStateMap<String, String>,
    overlayScope: CoroutineScope,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        joinWorkspaceId: String?,
        seedWorkdir: String?,
    ) -> Unit,
    tabDragState: PaneDragController,
    closeCandidate: ViewDto?,
    onCloseCandidate: (ViewDto?) -> Unit,
    sessionNames: Map<String, String>,
    modifier: Modifier,
    onTearOutTab: (String) -> Unit = {},
) {
    val layoutSync = ws.layoutSync
    val viewsById = ws.viewsById
    val documents = ws.documents
    val previewModes = ws.previewModes
    val fileOpener = ws.fileOpener
    val localLayout = layoutSync.tree

    PaneHost(
        layout = layout,
        titleFor = { vid -> viewsById[vid]?.let { viewTitle(it) } ?: "view" },
        onCloseView = { onCloseCandidate(viewsById[it]) },
        onEdit = { edit -> layoutSync.edit(edit) },
        addSlot = { groupId ->
            WorkspaceAddButton { kind, placement ->
                val open = openSingletonView(localLayout, viewsById, kind)
                if (open != null) {
                    val (viewId, ownerGroup) = open
                    layoutSync.edit { setActiveViewInGroup(it, ownerGroup, viewId) }
                    return@WorkspaceAddButton
                }
                app.addWorkspaceView(current.id, kind, groupId) { newViewId ->
                    if (placement != NewViewPlacement.HERE) {
                        val dir = if (placement == NewViewPlacement.SPLIT_RIGHT) "row" else "column"
                        val newGroupId = java.util.UUID.randomUUID().toString()
                        layoutSync.edit { tree ->
                            when (val owner = groupIdOf(tree, newViewId)) {
                                newGroupId, null -> tree
                                else -> splitGroup(tree, owner, newViewId, dir, newGroupId)
                            }
                        }
                    }
                }
            }
        },
        dragState = tabDragState,
        onDragEndMiss = { viewId -> onTearOutTab(viewId) },
        onDocked = { viewId ->
            ui.windowHosts.transfer(viewId, hostId, layoutSync.tree)
        },
        onMoveToWorkspace = { viewId, toWs ->
            if (toWs != current.id) {
                app.moveViewToWorkspace(viewId, toWs)
            }
        },
        modifier = modifier,
        chrome = DesktopStripChrome,
        emptyGroupSlot = { WorkspaceEmptyHint() },
        labelFont = MonoFontFamily,
        tabSlot = { itemId, tabState ->
            val v = viewsById[itemId]
            val filePath = v
                ?.takeIf { it.kind == "editor" && it.stateString("mode") == "file" }
                ?.stateString("path")
            if (filePath == null) {
                ContextMenuArea(
                    items = {
                        listOf(ContextMenuItem("Move to New Window") { onTearOutTab(itemId) })
                    },
                ) {
                    Box(Modifier.testTag("tab-move-to-window-$itemId")) {
                    DefaultTabChip(
                        itemId = itemId,
                        title = v?.let { viewTitle(it) } ?: "view",
                        state = tabState,
                        labelFont = MonoFontFamily,
                        onClose = { _ -> onCloseCandidate(v) },
                    )
                    }
                }
            } else {
                WorkspaceFileTab(
                    itemId = itemId,
                    title = filePath.substringAfterLast('/'),
                    path = filePath,
                    state = tabState,
                    dirty = documents.isDirty(filePath),
                    saving = documents.saving,
                    previewMode = previewModes[itemId] == true,
                    onSave = { documents.get(filePath)?.let { documents.save(it) } },
                    onTogglePreview = {
                        previewModes[itemId] = previewModes[itemId] != true
                    },
                    onClose = { _ -> onCloseCandidate(v) },
                    onMoveToNewWindow = { onTearOutTab(itemId) },
                )
            }
        },
    ) { viewId ->
        val v = viewsById[viewId]
        if (v != null && v.kind == "chat" && v.chatSessionId() == null) {
            launcherPane(
                { app.closeWorkspaceView(current.id, v.id) },
                { newId ->
                    app.bindChatView(current.id, v.id, newId)
                    ui.selectedId = newId
                },
                current.id,
                current.workdir,
            )
        } else if (v != null) {
            key(hostId, viewId) {
                ViewHost(
                    previewModeFor = { previewModes[it] == true },
                    view = v,
                    workspaceId = current.id,
                    workdir = current.workdir,
                    app = appFor(v.chatSessionId() ?: current.primarySessionId ?: session?.id ?: ""),
                    drafts = drafts,
                    documents = documents,
                    onOpenFile = { p, line, endLine ->
                        fileOpener.open(p, line, endLine, sourceViewId = viewId)
                    },
                    onCloseView = { onCloseCandidate(v) },
                    primarySessionId = current.primarySessionId,
                    onSelectSession = { ui.selectSession(it) },
                    forceLinksMenuFor = ui.forceLinksMenuFor,
                    onForceLinksMenuConsumed = { ui.forceLinksMenuFor = null },
                    externalAttach = ui.externalAttach,
                    onExternalAttachConsumed = { ui.externalAttach = null },
                    externalDictate = ui.externalDictate,
                    onExternalDictateConsumed = { ui.externalDictate = null },
                    pasteImageFor = ui.selectedId,
                    pasteImageRequestNonce = ui.pasteImageRequestNonce,
                    onPasteImageRequestConsumed = { ui.pasteImageRequestNonce = 0L },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    closeCandidate?.let { v ->
        if (!v.closeNeedsConfirmation()) {
            androidx.compose.runtime.LaunchedEffect(v.id) {
                runCatching { app.api.closeView(v.workspaceId, v.id) }
                onCloseCandidate(null)
            }
        } else {
            CloseViewDialog(
                view = v,
                sessionNames = sessionNames,
                onDismiss = { onCloseCandidate(null) },
                onConfirm = {
                    overlayScope.launch {
                        runCatching { app.api.closeView(v.workspaceId, v.id) }
                        onCloseCandidate(null)
                    }
                },
            )
        }
    }
}
