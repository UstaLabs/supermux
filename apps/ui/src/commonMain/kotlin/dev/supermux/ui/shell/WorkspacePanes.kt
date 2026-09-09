// One workspace's body, for every width (cluster G8).
//
// The base is desktop's `shell/DetachedWorkspaceWindow.kt` `WorkspacePanes` — the full `PaneHost`
// tree with drag/tear-out, the file tab, the add button, the walkthrough wiring and the pending
// chat view's launcher. Android's tablet workspace was the same thing with fewer affordances, so
// it simply gained them. Android's PHONE workspace — a flattened `ScrollableTabRow` over the
// broker's view order, which never PATCHes the layout (D2/D3) — is the Compact branch below.
//
// The pane CONTENT is one `ViewHost` call for every width; only the chrome around it differs.
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.state.HostStore
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.panes.DefaultTabChip
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.panes.PaneStripChrome
import dev.supermux.ui.session.RowContextMenu
import dev.supermux.ui.session.RowContextMenuEntry
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.widgets.keepAlivePanel
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.NewViewPlacement
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.viewTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Shared objects for one workspace's panes, in every window that draws them (the shell's own, and
 * on desktop each extra OS window).
 */
class WorkspacePanesBind(
    current: WorkspaceDto,
    session: SessionInfo?,
    ws: WorkspaceSession,
    app: HostStore,
    appFor: (String) -> HostStore,
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
 * The wide workspace body: the layout tree, drawn by [PaneHost].
 *
 * [layout] is already the slice this window shows (`ShellWindows.layoutFor`), which is the whole
 * tree everywhere except a desktop window that has torn tabs out into another one.
 */
@Composable
fun WorkspacePanes(
    hostId: String,
    layout: LayoutNode,
    current: WorkspaceDto,
    session: SessionInfo?,
    ws: WorkspaceSession,
    app: HostStore,
    appFor: (String) -> HostStore,
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
    stripChrome: PaneStripChrome = PaneStripChrome.None,
) {
    val layoutSync = ws.layoutSync
    val viewsById = ws.viewsById
    val documents = ws.documents
    val previewModes = ws.previewModes
    val fileOpener = ws.fileOpener
    var walkthroughSessionId by remember(current.id) { mutableStateOf<String?>(null) }

    PaneHost(
        layout = layout,
        titleFor = { vid -> viewsById[vid]?.let { viewTitle(it) } ?: "view" },
        onCloseView = { onCloseCandidate(viewsById[it]) },
        onEdit = { edit -> layoutSync.edit(edit) },
        addSlot = { groupId ->
            WorkspaceAddButton { kind, placement ->
                val open = openSingletonView(layout, viewsById, kind)
                if (open != null) {
                    val (viewId, ownerGroup) = open
                    layoutSync.edit { setActiveViewInGroup(it, ownerGroup, viewId) }
                    return@WorkspaceAddButton
                }
                app.addWorkspaceView(current.id, kind, groupId) { newViewId ->
                    if (placement != NewViewPlacement.HERE) {
                        val dir = if (placement == NewViewPlacement.SPLIT_RIGHT) "row" else "column"
                        val newGroupId = ws.newId()
                        layoutSync.edit { tree ->
                            when (val owner = groupIdOf(tree, newViewId)) {
                                newGroupId, null -> tree
                                else -> splitGroup(tree, owner, newViewId, dir, newGroupId)
                            }
                        }
                    }
                    ui.windows.expandClaim(hostId, setOf(newViewId), layoutSync.tree)
                }
            }
        },
        dragState = tabDragState,
        onDragEndMiss = { viewId -> onTearOutTab(viewId) },
        onDocked = { viewId ->
            ui.windows.transfer(viewId, hostId, layoutSync.tree)
        },
        onMoveToWorkspace = { viewId, toWs ->
            if (toWs != current.id) {
                app.moveViewToWorkspace(viewId, toWs)
            }
        },
        modifier = modifier,
        chrome = stripChrome,
        emptyGroupSlot = { WorkspaceEmptyHint() },
        labelFont = MonoFontFamily,
        tabSlot = { itemId, tabState ->
            val v = viewsById[itemId]
            val filePath = v
                ?.takeIf { it.kind == "editor" && it.stateString("mode") == "file" }
                ?.stateString("path")
            if (filePath == null) {
                RowContextMenu(
                    items = { listOf(RowContextMenuEntry("Move to New Window") { onTearOutTab(itemId) }) },
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
        WorkspacePaneContent(
            viewId = viewId,
            hostId = hostId,
            layout = layout,
            current = current,
            session = session,
            ws = ws,
            app = app,
            appFor = appFor,
            ui = ui,
            drafts = drafts,
            launcherPane = launcherPane,
            walkthroughSessionId = walkthroughSessionId,
            onWalkthroughSessionId = { walkthroughSessionId = it },
            onCloseCandidate = onCloseCandidate,
        )
    }
    closeCandidate?.let { v ->
        if (!v.closeNeedsConfirmation()) {
            LaunchedEffect(v.id) {
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

/**
 * The Compact workspace body: every view of the workspace flattened into one scrollable tab row.
 *
 * Android's `PhoneWorkspace`, unchanged in behaviour — the tab order and the selection come from
 * the BROKER (`workspace.layout` document order + `activeViewId`), and nothing here ever PATCHes a
 * layout: a phone has no splits to describe (D2/D3). The last three visited views stay composed so
 * a WebView or a PTY survives a tab switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneWorkspacePanes(
    current: WorkspaceDto,
    session: SessionInfo?,
    ws: WorkspaceSession,
    app: HostStore,
    appFor: (String) -> HostStore,
    ui: ShellUiState,
    drafts: SnapshotStateMap<String, String>,
    shell: ShellActions,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        joinWorkspaceId: String?,
        seedWorkdir: String?,
    ) -> Unit,
    sessionNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val layout = current.layout.toDomainOrNull() ?: ws.layoutSync.tree
    val tabs = phoneTabModel(layout, current.activeViewId)
    val viewsById = ws.viewsById
    var showAdd by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }
    var walkthroughSessionId by remember(current.id) { mutableStateOf<String?>(null) }

    fun closeOrConfirm(view: ViewDto) {
        if (view.closeNeedsConfirmation()) closeCandidate = view
        else app.closeWorkspaceView(current.id, view.id)
    }

    Column(modifier.fillMaxSize().testTag("phone_workspace_tabs")) {
        if (tabs.viewIds.isNotEmpty()) {
            val selectedIndex = tabs.viewIds.indexOf(tabs.selectedId).coerceAtLeast(0)
            // The strip is the TOP-MOST surface on a phone when a workspace has views: the compact
            // branch of the shell does not pad for the system bars (only the tablet frame does —
            // "the phone-layer screens do it themselves"), and this strip is a phone-layer screen
            // that was not doing it. On iOS that put the tab row and its close/add/overflow buttons
            // underneath the status bar and the Dynamic Island, where they are not merely ugly but
            // UNTAPPABLE — the island does not forward touches — so a session in a workspace could
            // be opened and then never left. The pad is on the Row and not the Column so that a
            // workspace with no strip is unchanged and cannot end up padded twice by the pane
            // below, which pads for itself.
            // ONE surface for the whole strip, painted on the Row and BEFORE the inset pad, so it
            // reaches both the right edge and up under the status bar. Three things were wrong
            // when this was left to `ScrollableTabRow`'s default container:
            //
            //  1. The overflow button is a SIBLING of the tab row, not one of its tabs, so the
            //     tab row's own background stopped short of it and the button sat on the page
            //     background — the strip read as a block that did not reach the right edge.
            //  2. `statusBarsPadding()` was applied outside the coloured area, so the status bar
            //     kept the page colour and the strip looked like it was floating below it.
            //  3. `surface` is the `card` token (L 0.995 — effectively white) against an L 0.955
            //     page. The desktop strip has always used `surfaceContainerLow` (the `chat`
            //     token); the phone strip only ever used `surface` by not asking.
            //
            // `Color.Transparent` on the tab row rather than the same colour, so there is exactly
            // one painter and the selected-tab indicator cannot end up on a second surface.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.weight(1f),
                    containerColor = Color.Transparent,
                    edgePadding = 0.dp,
                ) {
                    tabs.viewIds.forEach { id ->
                        val view = viewsById[id]
                        val title = view?.let { viewTitle(it) } ?: "view"
                        // The title and its close button on ONE line. M3's `text` + `icon` slots
                        // stack them vertically, which on a phone spent ~72dp of a small screen on
                        // a tab bar and put the close button ABOVE its own label — no phone tab
                        // strip on either platform looks like that, and the pane below is the
                        // thing the user came for.
                        Tab(
                            selected = id == tabs.selectedId,
                            onClick = { app.setActiveView(current.id, id) },
                            modifier = Modifier.height(48.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                            ) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(
                                    onClick = {
                                        val v = view ?: return@IconButton
                                        closeOrConfirm(v)
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Close $title",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Tab(
                        selected = false,
                        onClick = { showAdd = true },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add view")
                    }
                }
                viewsById[tabs.selectedId]?.chatSessionId()?.let { sid ->
                    PhoneTabChatOverflow(sid, shell) { ui.selectSession(it) }
                }
            }
        }
        val liveIds = tabs.viewIds.toSet()
        // Keep the last 3 visited views composed (hidden) so WebView/terminal PTY survive tab
        // switches. Evict LRU beyond 3 — more would pin too many WebViews on a phone.
        val retained = rememberVisitedWorkspaces(tabs.selectedId, liveIds, maxSize = 3)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (retained.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No views", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                retained.forEach { id ->
                    if (viewsById[id] == null) return@forEach
                    key(id) {
                        Box(Modifier.keepAlivePanel(id == tabs.selectedId)) {
                            WorkspacePaneContent(
                                viewId = id,
                                hostId = ui.windows.mainHostId,
                                layout = layout,
                                current = current,
                                session = session,
                                ws = ws,
                                app = app,
                                appFor = appFor,
                                ui = ui,
                                drafts = drafts,
                                launcherPane = launcherPane,
                                walkthroughSessionId = walkthroughSessionId,
                                onWalkthroughSessionId = { walkthroughSessionId = it },
                                onCloseCandidate = { v -> v?.let { closeOrConfirm(it) } },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            phoneAddKinds().forEach { kind ->
                TextButton(
                    onClick = {
                        showAdd = false
                        addPhoneView(current, ws, app, kind)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tab-add-view-${kind.tag}"),
                ) { Text(kind.label) }
            }
        }
    }
    closeCandidate?.let { v ->
        CloseViewDialog(
            view = v,
            sessionNames = sessionNames,
            onDismiss = { closeCandidate = null },
            onConfirm = {
                app.closeWorkspaceView(current.id, v.id)
                closeCandidate = null
            },
        )
    }
}

/** Phone add: reveal an existing singleton, else post a new view (optimistic, no layout PATCH). */
internal fun addPhoneView(
    workspace: WorkspaceDto,
    ws: WorkspaceSession,
    app: HostStore,
    kind: NewViewKind,
) {
    val tree = workspace.layout.toDomainOrNull() ?: ws.layoutSync.tree
    val open = openSingletonView(tree, ws.viewsById, kind)
    if (open != null) {
        app.setActiveView(workspace.id, open.first)
        return
    }
    val id = ws.newId()
    val state = addViewState(kind, nowMillis())
    ws.provisionalViews[id] = ViewDto(
        id = id,
        workspaceId = workspace.id,
        kind = kind.wire,
        state = state,
    )
    app.addWorkspaceView(workspace.id, kind.wire, state, id)
}

/**
 * One pane's body — identical at every width. Everything width-specific (the tab strip, the split
 * tree, the chat header mode) lives OUTSIDE this.
 */
@Composable
private fun WorkspacePaneContent(
    viewId: String,
    hostId: String,
    layout: LayoutNode,
    current: WorkspaceDto,
    session: SessionInfo?,
    ws: WorkspaceSession,
    app: HostStore,
    appFor: (String) -> HostStore,
    ui: ShellUiState,
    drafts: SnapshotStateMap<String, String>,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        joinWorkspaceId: String?,
        seedWorkdir: String?,
    ) -> Unit,
    walkthroughSessionId: String?,
    onWalkthroughSessionId: (String?) -> Unit,
    onCloseCandidate: (ViewDto?) -> Unit,
) {
    val layoutSync = ws.layoutSync
    val viewsById = ws.viewsById
    val documents = ws.documents
    val previewModes = ws.previewModes
    val fileOpener = ws.fileOpener
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
            // The store this VIEW belongs to (its chat session's host, else the workspace's).
            val viewApp = appFor(v.chatSessionId() ?: current.primarySessionId ?: session?.id ?: "")
            ViewHost(
                previewModeFor = { previewModes[it] == true },
                view = v,
                workspaceId = current.id,
                workdir = current.workdir,
                actions = rememberShellActions(viewApp, appFor),
                chatState = { sid -> rememberChatState(viewApp, sid) },
                chatActions = { s -> rememberChatActions(viewApp, s) },
                drafts = drafts,
                documents = documents,
                onOpenFile = { p, line, endLine ->
                    fileOpener.open(
                        p, line, endLine,
                        sourceViewId = viewId,
                        scope = layout,
                        onPlaced = { newId ->
                            ui.windows.expandClaim(hostId, setOf(newId), layoutSync.tree)
                        },
                    )
                },
                onOpenWalkthrough = { sessionId, stepId ->
                    onWalkthroughSessionId(sessionId)
                    appFor(sessionId).walkthroughState<WalkthroughState>(sessionId).open(stepId)
                    val existing = openSingletonView(layout, viewsById, NewViewKind.DIFF)
                    if (existing != null) {
                        val (diffViewId, ownerGroup) = existing
                        layoutSync.edit { setActiveViewInGroup(it, ownerGroup, diffViewId) }
                        ui.windows.expandClaim(hostId, setOf(diffViewId), layoutSync.tree)
                    } else {
                        val groupId = groupIdOf(layout, viewId)
                        if (groupId != null) {
                            app.addWorkspaceView(current.id, NewViewKind.DIFF, groupId) { newViewId ->
                                ui.windows.expandClaim(hostId, setOf(newViewId), layoutSync.tree)
                            }
                        } else {
                            // A phone workspace has no groups to split: post the pane and let the
                            // broker's view order place it in the tab strip.
                            app.addWorkspaceView(
                                current.id,
                                NewViewKind.DIFF.wire,
                                addViewState(NewViewKind.DIFF, nowMillis()),
                            )
                        }
                    }
                },
                walkthroughSessionId = walkthroughSessionId,
                onWalkthroughClosed = { onWalkthroughSessionId(null) },
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

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
