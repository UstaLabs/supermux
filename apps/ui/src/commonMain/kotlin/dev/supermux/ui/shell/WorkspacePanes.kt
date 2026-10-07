// One workspace's body, for every width (cluster G8).
//
// The base is desktop's `shell/DetachedWorkspaceWindow.kt` `WorkspacePanes` — the full `PaneHost`
// tree with drag/tear-out, the file tab, the add button, the walkthrough wiring and the pending
// chat view's launcher. Android's tablet workspace was the same thing with fewer affordances, so
// it simply gained them. Android's PHONE workspace — every view behind one count button and a card grid (PhoneTabSwitcher.kt), over the
// broker's view order, which never PATCHes the layout (D2/D3) — is the Compact branch below.
//
// The pane CONTENT is one `ViewHost` call for every width; only the chrome around it differs.
package dev.supermux.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.pendingChatDraft
import dev.supermux.proto.stateString
import dev.supermux.state.HostStore
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.files.activeFilePath
import dev.supermux.ui.files.filePathOrNull
import dev.supermux.ui.files.isFilesTreeView
import dev.supermux.ui.files.nextFocusedFileView
import dev.supermux.ui.panes.DefaultTabChip
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.panes.PaneStripChrome
import dev.supermux.ui.panes.PaneTabStrip
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.session.LocalContextMenuAvailable
import dev.supermux.ui.session.RowContextMenu
import dev.supermux.ui.session.RowContextMenuEntry
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.widgets.KeepAlivePanel
import dev.supermux.ui.worktree.WorktreeDeleteAction
import dev.supermux.ui.worktree.reportWorktreeDelete
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.ui.workspace.applyEntryMoved
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.NewViewPlacement
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.ui.terminal.liveViewTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock


/**
 * A pending "+ → Chat" tab hosting the launcher: the chat it starts JOINS [workspaceId], is born in
 * [workdir] and fills tab [viewId]. [repoRoot] / [branch] name that work tree for display — a
 * worktree's own path is an opaque `.mux/worktrees/<repo>-<hash>/<uuid>`. [draftText] is the tab's own saved composer text (restored
 * once), [onDraftText] saves it back into the tab's state.
 */
data class LauncherTab(
    val workspaceId: String,
    val workdir: String,
    val viewId: String,
    val draftText: String,
    val repoRoot: String? = null,
    val branch: String? = null,
    val onDraftText: (String) -> Unit,
)
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
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit,
    sessionNames: Map<String, String> = emptyMap(),
    unreadSessions: Set<String> = emptySet(),
) {
    var current by mutableStateOf(current)
    var session by mutableStateOf(session)
    var ws by mutableStateOf(ws)
    var app by mutableStateOf(app)
    var appFor by mutableStateOf(appFor)
    var drafts by mutableStateOf(drafts)
    var overlayScope by mutableStateOf(overlayScope)
    var launcherPane by mutableStateOf(launcherPane)
    /** Session id → name, so a chat tab is titled after its chat in every window. */
    var sessionNames by mutableStateOf(sessionNames)
    /** Unread chat session ids, so a chat tab can wear the unread dot in every window. */
    var unreadSessions by mutableStateOf(unreadSessions)

    /**
     * How many compositions are drawing this workspace right now. The bind itself outlives them
     * (it stays in `ShellUiState.panesBinds`), so a window in ANOTHER composition — an Android
     * Android extra activity — reads this to tell a live workspace from one whose `ws` was
     * disposed with the main window.
     */
    var holders by mutableIntStateOf(0)
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
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit,
    tabDragState: PaneDragController,
    closeCandidate: ViewDto?,
    onCloseCandidate: (ViewDto?) -> Unit,
    sessionNames: Map<String, String>,
    unreadSessions: Set<String>,
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
    val windowsSeam = LocalPlatform.current.windows
    val notices = LocalPlatform.current.notices
    TrackFocusedFileView(ws)
    // Tabs a bulk close (tab menu ▸ Close to the Right / Left / All) still has to close, fed one at
    // a time to the tab's own close action (see nextBulkClose).
    val bulkQueue = remember(current.id) { mutableStateListOf<String>() }
    LaunchedEffect(closeCandidate == null, bulkQueue.size) {
        if (closeCandidate == null) nextBulkClose(bulkQueue) { viewsById[it] }?.let(onCloseCandidate)
    }

    fun bulkClose(anchorId: String, which: BulkClose, ids: List<String>) {
        // The anchor survives a one-sided close, so it becomes the tab on show rather than whatever
        // neighbour the broker would pick.
        if (which != BulkClose.ALL) {
            groupIdOf(layoutSync.tree, anchorId)?.let { g -> layoutSync.edit { setActiveViewInGroup(it, g, anchorId) } }
        }
        bulkQueue.addAll(ids.filterNot { it in bulkQueue })
    }

    fun bulkEntries(itemId: String): List<RowContextMenuEntry> =
        bulkCloseEntries(groupViewIdsOf(layout, itemId), itemId) { which, ids -> bulkClose(itemId, which, ids) }

    PaneHost(
        layout = layout,
        titleFor = { vid -> viewsById[vid]?.let { liveViewTitle(it, sessionNames::get) } ?: "view" },
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
        // Desktop tears a tab dropped outside every pane into a new window. Not on touch: a
        // finger that lets go a little off-target must not spawn a window.
        onDragEndMiss = { viewId -> if (windowsSeam?.tearOutOnDragMiss == true) onTearOutTab(viewId) },
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
            // Touch has no right-click, so a device that CAN open a second window offers "Move to
            // New Window" on a long press instead. A held finger never reaches the strip's drag
            // threshold, so this does not fight the tab drag.
            TabLongPressMenu(
                enabled = !LocalContextMenuAvailable.current,
                itemId = itemId,
                onMoveToNewWindow = if (windowsSeam != null) ({ onTearOutTab(itemId) }) else null,
                bulkEntries = { bulkEntries(itemId) },
            ) {
                if (filePath == null) {
                    RowContextMenu(
                        items = {
                            listOf(RowContextMenuEntry("Move to New Window") { onTearOutTab(itemId) }) +
                                bulkEntries(itemId)
                        },
                    ) {
                        Box(Modifier.testTag("tab-move-to-window-$itemId")) {
                        DefaultTabChip(
                            itemId = itemId,
                            title = v?.let { liveViewTitle(it, sessionNames::get) } ?: "view",
                            state = tabState,
                            dot = if (v.tabUnread(tabState.selected, unreadSessions)) LocalSemantics.current.success else null,
                            labelFont = MonoFontFamily,
                            onClose = { _ -> onCloseCandidate(v) },
                        )
                        }
                    }
                } else {
                    Box(Modifier.observePress(itemId) { ws.focusedFileViewId = itemId }) {
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
                        extraMenu = { bulkEntries(itemId) },
                    )
                    }
                }
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
                chatWorkdir = v.chatSessionId()?.let { sid -> app.sessions.value.firstOrNull { it.id == sid }?.workdir },
                worktreeForWorkdir = { app.worktreeForWorkdir(it) },
                onConfirmDeletingWorktree = { ids ->
                    // Dismiss first; the (possibly minutes-long) close+delete runs on the store's
                    // scope so leaving this workspace never cancels it or loses its notice.
                    onCloseCandidate(null)
                    app.closeViewAndDeleteWorktree(v.workspaceId, v.id, ids) {
                        reportWorktreeDelete(notices, it, WorktreeDeleteAction.Close)
                    }
                },
            )
        }
    }
}

/**
 * The Compact workspace body: every view of the workspace behind one count button and a card grid.
 *
 * Android's `PhoneWorkspace`, unchanged in behaviour — the tab order and the selection come from
 * the BROKER (`workspace.layout` document order + `activeViewId`), and nothing here ever PATCHes a
 * layout: a phone has no splits to describe (D2/D3). The last three visited views stay composed so
 * a WebView or a PTY survives a tab switch.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit,
    sessionNames: Map<String, String>,
    unreadSessions: Set<String>,
    modifier: Modifier = Modifier,
) {
    // Minus whatever an extra window claimed: this phone layout, in split screen beside its own
    // extra window, must not show those views twice.
    val windowsSeam = LocalPlatform.current.windows
    val notices = LocalPlatform.current.notices
    val scope = rememberCoroutineScope()
    val layout = ui.windows.layoutFor(
        ui.windows.mainHostId,
        current.layout.toDomainOrNull() ?: ws.layoutSync.tree,
    )
    val tabs = phoneTabModel(layout, current.activeViewId)
    val viewsById = ws.viewsById
    var showAdd by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }
    var walkthroughSessionId by remember(current.id) { mutableStateOf<String?>(null) }

    fun closeOrConfirm(view: ViewDto) {
        if (view.closeNeedsConfirmation()) closeCandidate = view
        else app.closeWorkspaceView(current.id, view.id)
    }

    // Same as the wide strip: a bulk close feeds each tab to its own close, one at a time.
    val bulkQueue = remember(current.id) { mutableStateListOf<String>() }
    LaunchedEffect(closeCandidate == null, bulkQueue.size) {
        // An editor closes on the spot (no candidate), so keep going until one needs its question.
        while (closeCandidate == null) closeOrConfirm(nextBulkClose(bulkQueue) { viewsById[it] } ?: break)
    }

    fun bulkEntries(itemId: String): List<RowContextMenuEntry> =
        bulkCloseEntries(tabs.viewIds, itemId) { which, ids ->
            if (which != BulkClose.ALL) app.setActiveView(current.id, itemId)
            bulkQueue.addAll(ids.filterNot { it in bulkQueue })
        }

    // The switcher (see PhoneTabSwitcher.kt) replaced the tab strip: the views live behind one
    // count button, and each card shows the frame its view last drew.
    var switcherOpen by remember(current.id) { mutableStateOf(false) }
    val thumbnails = remember(current.id) { mutableStateMapOf<String, ImageBitmap>() }
    // Chrome's zoom: the grid grows out of / shrinks into the card it pivots on. Card centres are
    // remembered from the last time the grid was laid out; before that, the first card's spot.
    val cardOrigins = remember(current.id) { mutableMapOf<String, TransformOrigin>() }
    var zoomOrigin by remember(current.id) { mutableStateOf(TransformOrigin(0.27f, 0.25f)) }
    val layers = remember(current.id) { mutableMapOf<String, GraphicsLayer>() }
    suspend fun snapshot(id: String) {
        val layer = layers[id] ?: return
        if (layer.size.width <= 0 || layer.size.height <= 0) return
        // Bounded: a thumbnail is a nicety, and a capture that never returns must not hold anything up.
        runCatching { withTimeoutOrNull(500) { layer.toImageBitmap() } }
            .onSuccess { bmp -> bmp?.let { thumbnails[id] = it } }
            .onFailure { println("[PhoneTabSwitcher] snapshot $id failed: $it") }
    }
    // The tab being LEFT is still composed (just hidden), and its layer still holds its last frame.
    var lastSelected by remember(current.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(tabs.selectedId) {
        lastSelected?.takeIf { it != tabs.selectedId }?.let { snapshot(it) }
        lastSelected = tabs.selectedId
    }
    LaunchedEffect(tabs.viewIds) { thumbnails.keys.retainAll(tabs.viewIds.toSet()) }
    val anyUnread = tabs.viewIds.any { viewsById[it].tabUnread(it == tabs.selectedId, unreadSessions) }
    val tabsButton = PhoneTabsButton(tabs.viewIds.size, anyUnread) {
        // Open first; the hidden view's layer still holds its last frame, so the card catches up.
        tabs.selectedId?.let { cardOrigins[it] }?.let { zoomOrigin = it }
        switcherOpen = true
        tabs.selectedId?.let { id -> scope.launch { snapshot(id) } }
    }
    if (switcherOpen && tabs.viewIds.isEmpty()) switcherOpen = false
    // The view under the grid stays on screen until the grid has fully covered it, so opening
    // reads as a zoom rather than a cut to an empty page.
    var hideUnderGrid by remember(current.id) { mutableStateOf(false) }
    LaunchedEffect(switcherOpen) {
        if (switcherOpen) {
            delay(SWITCHER_ZOOM_MS.toLong())
            hideUnderGrid = true
        } else {
            hideUnderGrid = false
        }
    }
    BackHandler(enabled = switcherOpen) { switcherOpen = false }

    Box(modifier.fillMaxSize().testTag("phone_workspace_tabs")) {
    Column(Modifier.fillMaxSize()) {
        val selectedView = tabs.selectedId?.let { viewsById[it] }
        if (tabs.viewIds.isNotEmpty()) {
            // The top-most surface pads for the status bar itself (the compact shell does not —
            // on iOS a bar under the Dynamic Island is UNTAPPABLE). A chat draws its own header
            // and gets the count button there; every other view gets a slim bar holding it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding(),
            ) {
                if (selectedView?.kind != "chat") {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp)
                            .testTag("phone_workspace_bar"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            selectedView.switcherIcon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            selectedView?.let { liveViewTitle(it, sessionNames::get) } ?: "view",
                            fontFamily = MonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        PhoneTabCountButton(tabsButton)
                    }
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
                        val layer = rememberGraphicsLayer()
                        DisposableEffect(id, layer) {
                            layers[id] = layer
                            onDispose { if (layers[id] === layer) layers.remove(id) }
                        }
                        // KeepAlivePanel (the expect/actual container), NOT the alpha modifier: a
                        // retained pane can hold a platform view the host's compositor draws
                        // OUTSIDE the Compose layer — a `UIKitView`'s child on iOS, a heavyweight
                        // SwingPanel on desktop — and neither is hidden by alpha. The
                        // Android actual is still exactly the alpha hide this used to be. Hidden
                        // under the switcher too, for the same reason: a platform view would draw
                        // over the grid.
                        KeepAlivePanel(visible = id == tabs.selectedId && !hideUnderGrid) {
                            CompositionLocalProvider(LocalPhoneTabsButton provides tabsButton) {
                            Box(Modifier.fillMaxSize().recordInto(layer)) {
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
        }
    }
        AnimatedVisibility(
            visible = switcherOpen,
            enter = fadeIn(tween(SWITCHER_ZOOM_MS)) +
                scaleIn(tween(SWITCHER_ZOOM_MS, easing = FastOutSlowInEasing), initialScale = 1.6f, transformOrigin = zoomOrigin),
            exit = fadeOut(tween(SWITCHER_ZOOM_MS)) +
                scaleOut(tween(SWITCHER_ZOOM_MS, easing = FastOutSlowInEasing), targetScale = 1.6f, transformOrigin = zoomOrigin),
        ) {
            PhoneTabSwitcher(
                viewIds = tabs.viewIds,
                selectedId = tabs.selectedId,
                viewFor = { viewsById[it] },
                titleFor = { id -> viewsById[id]?.let { liveViewTitle(it, sessionNames::get) } ?: "view" },
                unread = { id -> viewsById[id].tabUnread(id == tabs.selectedId, unreadSessions) },
                thumbnails = thumbnails,
                onSelect = { id ->
                    cardOrigins[id]?.let { zoomOrigin = it }
                    app.setActiveView(current.id, id)
                    switcherOpen = false
                },
                onCardPlaced = { id, origin -> cardOrigins[id] = origin },
                onClose = { id -> viewsById[id]?.let { closeOrConfirm(it) } },
                onAdd = { showAdd = true },
                onDismiss = { switcherOpen = false },
                cardMenu = { id, content ->
                    TabLongPressMenu(
                        enabled = !LocalContextMenuAvailable.current,
                        itemId = id,
                        onMoveToNewWindow = windowsSeam?.let { w -> { w.tearOutTab(id) } },
                        bulkEntries = { bulkEntries(id) },
                    ) {
                        RowContextMenu(items = { bulkEntries(id) }) { content() }
                    }
                },
            )
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            phoneAddKinds().forEach { kind ->
                TextButton(
                    onClick = {
                        showAdd = false
                        switcherOpen = false
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
            chatWorkdir = v.chatSessionId()?.let { sid -> app.sessions.value.firstOrNull { it.id == sid }?.workdir },
            worktreeForWorkdir = { app.worktreeForWorkdir(it) },
            onConfirmDeletingWorktree = { ids ->
                closeCandidate = null
                app.closeViewAndDeleteWorktree(current.id, v.id, ids) {
                    reportWorktreeDelete(notices, it, WorktreeDeleteAction.Close)
                }
            },
        )
    }
}

/** How long the switcher's zoom in/out takes — Chrome's is about this quick. */
private const val SWITCHER_ZOOM_MS = 240

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
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
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
        // key: a different pending tab is a different launcher (its own draft, its own state).
        key(hostId, viewId) {
            launcherPane(
                { app.closeWorkspaceView(current.id, v.id) },
                { newId ->
                    app.bindChatView(current.id, v.id, newId)
                    ui.selectedId = newId
                },
                LauncherTab(
                    workspaceId = current.id,
                    workdir = current.workdir,
                    viewId = v.id,
                    draftText = v.pendingChatDraft(),
                    onDraftText = { app.savePendingChatDraft(current.id, v.id, it) },
                    repoRoot = current.repoRoot,
                    branch = current.branch,
                ),
            )
        }
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
                treeStates = ws.treeStates,
                // Only a Files (tree) pane reads it: the file the user last worked in, else the
                // first group (layout order) showing a file.
                activeFilePath = if (v.isFilesTreeView()) {
                    activeFilePath(layoutSync.tree, viewsById, ws.focusedFileViewId)
                } else null,
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
                // Tabs open on a renamed/deleted path must not keep writing to it: clean ones follow
                // the file (or close), dirty ones stay and show the stale banner — see
                // applyEntryMoved. Closing is the same broker close as the tab's × button.
                onEntryMoved = { old, new ->
                    ws.applyEntryMoved(
                        workdir = current.workdir,
                        oldAbs = old,
                        newAbs = new,
                        closeView = { id -> app.closeWorkspaceView(current.id, id) },
                        onPlaced = { newId -> ui.windows.expandClaim(hostId, setOf(newId), layoutSync.tree) },
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
                modifier = Modifier.fillMaxSize().then(
                    // A press anywhere in a file pane makes it the file the Files tree follows. (The
                    // desktop JCEF editor is heavyweight and never reports presses; its tab does.)
                    if (v.filePathOrNull() != null) Modifier.observePress(viewId) { ws.focusedFileViewId = viewId } else Modifier,
                ),
            )
        }
    }
}

/**
 * Keeps [WorkspaceSession.focusedFileViewId] on the file the user last activated: a file tab that
 * just became active in its group (a tab click, a file opened from the tree) takes over.
 */
@Composable
private fun TrackFocusedFileView(ws: WorkspaceSession) {
    val tree = ws.layoutSync.tree
    val activeIds = remember(tree) { collectActiveViewIds(tree) }
    val previous = remember(ws) { arrayOf<List<String>>(emptyList()) }
    val views = ws.viewsById
    LaunchedEffect(ws, activeIds, views) {
        ws.focusedFileViewId = nextFocusedFileView(previous[0], activeIds, views, ws.focusedFileViewId)
        // Only ids whose view is known: one whose row lands later still counts as "newly active".
        previous[0] = activeIds.filter { it in views }
    }
}

/** Runs [onPress] on every press inside this element without consuming anything. */
private fun Modifier.observePress(key: Any?, onPress: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onPress)
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            latest()
        }
    }
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * A long press on this element, detected WITHOUT taking the tap from whatever is underneath it: a
 * short press, or one that moves past the touch slop (a tab drag, a strip scroll), is left
 * entirely alone. Only once the press has been held does it claim the gesture — it consumes the
 * rest of it, so the tab's own click does not also fire on release.
 */
internal fun Modifier.longPress(key: Any?, onLongPress: () -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onLongPress)
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // `withTimeoutOrNull` here is AwaitPointerEventScope's own: null means the press was
            // HELD for the whole timeout; anything else means it ended or moved first.
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var held = true
                while (held) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id }
                    held = change != null && change.pressed &&
                        (change.position - down.position).getDistance() <= slop
                }
                true
            }
            if (endedEarly != null) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            latest()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * A long-press menu around one tab — the touch stand-in for the desktop tab's right-click menu:
 * "Move to New Window" (only where [onMoveToNewWindow] is set, a host that can open one) and the
 * strip's bulk closes. [enabled] false (a host with right-click) is a plain passthrough.
 */
@Composable
internal fun TabLongPressMenu(
    enabled: Boolean,
    itemId: String,
    onMoveToNewWindow: (() -> Unit)?,
    bulkEntries: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    var open by remember(itemId) { mutableStateOf(false) }
    Box(Modifier.longPress(itemId) { open = true }) {
        content()
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.testTag("tab-menu-$itemId"),
        ) {
            if (onMoveToNewWindow != null) {
                DropdownMenuItem(
                    text = { Text("Move to New Window") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                    onClick = {
                        open = false
                        onMoveToNewWindow()
                    },
                    modifier = Modifier.testTag("tab-menu-new-window-$itemId"),
                )
            }
            // Evaluated only while open, like the right-click menu's `items`.
            if (open) {
                bulkEntries().forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        onClick = {
                            open = false
                            entry.onClick()
                        },
                        modifier = Modifier.testTag("tab-menu-${entry.label.lowercase().replace(' ', '-')}-$itemId"),
                    )
                }
            }
        }
    }
}

/**
 * A chat tab wears the unread dot while its session has news and it isn't the tab on show — the
 * selected tab is being read, the sidebar row's rule for the open chat.
 */
internal fun ViewDto?.tabUnread(selected: Boolean, unreadSessions: Set<String>): Boolean =
    !selected && this?.chatSessionId()?.let { it in unreadSessions } == true
