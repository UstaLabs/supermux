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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Job
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import dev.supermux.fs.pathLeaf
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
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.terminal.terminalPalette
import dev.supermux.ui.theme.GeistFontFamily
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.files.isFilesTreeView
import dev.supermux.ui.files.nextFocusedFileView
import dev.supermux.ui.keys.KeyContext
import dev.supermux.ui.keys.LocalKeyScopeActive
import dev.supermux.ui.keys.ProvideKeyScope
import dev.supermux.ui.keys.keyScope
import dev.supermux.ui.keys.rememberKeyScope
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
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.ui.panes.PaneStripEnd
import androidx.compose.material.icons.outlined.Tab
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.ui.terminal.liveViewTitle
import dev.supermux.ui.retain.newRetainedPageOpening
import dev.supermux.ui.retain.rememberRetained
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

    /**
     * The fleet's downloads (#95), lent to a window that is a composition of its own (desktop's `Window`, an
     * Android activity, an iPad scene): it sits outside the shell's providers, and a download started there must
     * outlive that window just the same.
     */
    var downloads by mutableStateOf<dev.supermux.work.DownloadCenter?>(null)
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
    // Kept with the workspace for a rebuilt layout (see [WorkspacePanesKept]).
    val kept = rememberWorkspacePanesKept(current.id)
    var walkthroughSessionId by kept::walkthroughSessionId
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

    // A New tab stays in its window: it is not a view another window could hold.
    val tearOff = remember(windowsSeam, hostId, ws, current.id) {
        windowsSeam?.tabTearOff(hostId, ws, current.id)?.let(::NewTabsStayInWindow)
    }
    // The "+" and the split buttons open New tabs (#91), drawn into the tree PaneHost shows; every
    // edit PaneHost makes is replayed on the real tree with them put in first and taken out after.
    // The split buttons are the workspace's, at its top-right: the main window's (a torn-off window
    // shows only part of the workspace), and so are the panes they open.
    val newTabs = kept.newTabs
    val mainWindow = hostId == ui.windows.mainHostId
    val drawnHere = newTabs.slots.filterValues { it.pane?.edge != true || mainWindow }
    val shownLayout = withNewTabs(layout, drawnHere)
    NewTabUpkeep(newTabs, layoutSync.tree) { layoutSync.edit(it) }
    val topRight = topRightGroupId(shownLayout)
    val splitButtons = remember(mainWindow, topRight, newTabs, layoutSync) {
        if (!mainWindow) {
            null
        } else {
            PaneStripEnd(topRight, WorkspaceSplitButtonsWidth) {
                WorkspaceSplitButtons { direction -> newTabs.splitWorkspace(direction, layoutSync.tree) }
            }
        }
    }

    fun pickNewTab(newTabId: String, kind: NewViewKind) {
        val slot = newTabs.slots[newTabId] ?: return
        val tree = layoutSync.tree
        when (val pick = newTabPick(kind, tree, viewsById)) {
            is NewTabPick.SwitchTo -> {
                newTabs.close(newTabId)
                layoutSync.edit { setActiveViewInGroup(it, pick.groupId, pick.viewId) }
            }
            NewTabPick.Add -> {
                newTabs.opening[newTabId] = kind
                // The broker puts the view in a real pane; NewTabUpkeep then moves it to where the
                // New tab is drawn, and that pane goes back to the tab it showed.
                val target = (slot.pane?.anchorGroupId ?: slot.groupId).takeIf { groupActiveViewId(tree, it) != null }
                    ?: topRightGroupId(tree)
                newTabs.pickedFrom[newTabId] = target to groupActiveViewId(tree, target)
                app.addWorkspaceView(
                    current.id, kind, target,
                    onCreated = { newViewId ->
                        newTabs.replacedBy[newTabId] = newViewId
                        ui.windows.expandClaim(hostId, setOf(newViewId), layoutSync.tree)
                    },
                    onFailed = { newTabs.opening.remove(newTabId) },
                )
            }
        }
    }

    PaneHost(
        layout = shownLayout,
        tearOff = tearOff,
        titleFor = { vid -> if (isNewTabId(vid)) "New tab" else viewsById[vid]?.let { liveViewTitle(it, sessionNames::get) } ?: "view" },
        onCloseView = { if (isNewTabId(it)) newTabs.close(it) else onCloseCandidate(viewsById[it]) },
        onEdit = { edit ->
            val before = drawnHere
            if (before.isEmpty()) {
                layoutSync.edit(edit)
            } else {
                newTabEdit(layoutSync.tree, before, edit)?.let { routed ->
                    for ((id, slot) in routed.slots) {
                        if (newTabs.slots[id] != slot) newTabs.slots[id] = slot
                    }
                    layoutSync.edit(routed.onReal)
                }
            }
        },
        addSlot = { groupId -> WorkspaceAddButton { newTabs.open(groupId, groupActiveViewId(layoutSync.tree, groupId)) } },
        stripEnd = splitButtons,
        dragState = tabDragState,
        // Desktop tears a tab dropped outside every pane into a new window. Not on touch: a
        // finger that lets go a little off-target must not spawn a window. Never a New tab.
        onDragEndMiss = { viewId -> if (!isNewTabId(viewId) && windowsSeam?.tearOutOnDragMiss == true) onTearOutTab(viewId) },
        onDocked = { viewId ->
            if (!isNewTabId(viewId)) ui.windows.transfer(viewId, hostId, layoutSync.tree)
        },
        onMoveToWorkspace = { viewId, toWs ->
            if (!isNewTabId(viewId) && toWs != current.id) {
                app.moveViewToWorkspace(viewId, toWs)
            }
        },
        modifier = modifier,
        chrome = stripChrome,
        emptyGroupSlot = { WorkspaceEmptyHint() },
        labelFont = GeistFontFamily,
        sheetColor = { id -> sheetToneOf(id?.let { viewsById[it] }) },
        tabSlot = tabSlot@{ itemId, tabState ->
            if (isNewTabId(itemId)) {
                DefaultTabChip(
                    itemId = itemId,
                    title = "New tab",
                    state = tabState,
                    labelFont = GeistFontFamily,
                    closeLabel = "Close tab",
                    onClose = { newTabs.close(itemId) },
                    icon = {
                        Icon(
                            Icons.Outlined.Tab,
                            contentDescription = null,
                            tint = if (tabState.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                )
                return@tabSlot
            }
            val v = viewsById[itemId]
            val filePath = v
                ?.takeIf { it.kind == "editor" && it.stateString("mode") == "file" }
                ?.stateString("path")
            // Touch has no right-click, so a device that CAN open a second window offers "Move to
            // New Window" on a long press instead. The same hold is what arms a touch tab drag, so
            // the menu waits for the finger to lift: hold-and-move drags, hold-and-lift is the menu.
            TabLongPressMenu(
                enabled = !LocalContextMenuAvailable.current,
                itemId = itemId,
                onMoveToNewWindow = if (windowsSeam != null) ({ onTearOutTab(itemId) }) else null,
                bulkEntries = { bulkEntries(itemId) },
                openOnRelease = true,
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
                            labelFont = GeistFontFamily,
                            onClose = { _ -> onCloseCandidate(v) },
                            icon = {
                                val agent = v?.chatSessionId()?.let { sid -> app.sessions.value.firstOrNull { it.id == sid }?.agent }
                                ViewTabIcon(v, agent, tabState.selected)
                            },
                        )
                        }
                    }
                } else {
                    Box(Modifier.observePress(itemId) { ws.focusedFileViewId = itemId }) {
                    WorkspaceFileTab(
                        itemId = itemId,
                        title = pathLeaf(filePath),
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
        if (isNewTabId(viewId)) {
            key(viewId) {
                NewTabPage(
                    choices = newTabChoices(current, layoutSync.tree, viewsById),
                    workspace = current.name,
                    branch = current.branch,
                    opening = newTabs.opening[viewId],
                    onPick = { kind -> pickNewTab(viewId, kind) },
                    onClose = { newTabs.close(viewId) },
                )
            }
            return@PaneHost
        }
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
                worktreeSize = { app.worktreeSize(it) },
                onConfirmDeletingWorktree = { ids ->
                    // Dismiss first; the (possibly minutes-long) close+delete runs on the store's
                    // scope so leaving this workspace never cancels it or loses its notice.
                    onCloseCandidate(null)
                    app.closeViewAndDeleteWorktree(v.workspaceId, v.id, ids) {
                        reportWorktreeDelete(notices, it, WorktreeDeleteAction.Close)
                    }
                },
                retainKey = kept.closingKey,
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
    val kept = rememberWorkspacePanesKept(current.id)
    var showAdd by kept::addViewOpen
    val closeCandidate = kept.closingViewId?.let { viewsById[it] }
    var walkthroughSessionId by kept::walkthroughSessionId

    fun closeOrConfirm(view: ViewDto) {
        if (view.closeNeedsConfirmation()) kept.askToClose(view)
        else app.closeWorkspaceView(current.id, view.id)
    }

    // Same as the wide strip: a bulk close feeds each tab to its own close, one at a time.
    val bulkQueue = remember(current.id) { mutableStateListOf<String>() }
    LaunchedEffect(kept.closingViewId == null, bulkQueue.size) {
        // An editor closes on the spot (no candidate), so keep going until one needs its question.
        while (kept.closingViewId == null) closeOrConfirm(nextBulkClose(bulkQueue) { viewsById[it] } ?: break)
    }

    fun bulkEntries(itemId: String): List<RowContextMenuEntry> =
        bulkCloseEntries(tabs.viewIds, itemId) { which, ids ->
            if (which != BulkClose.ALL) app.setActiveView(current.id, itemId)
            bulkQueue.addAll(ids.filterNot { it in bulkQueue })
        }

    // The switcher (see PhoneTabSwitcher.kt) replaced the tab strip: the views live behind one
    // count button, and each card shows the frame its view last drew. Whether it is open is kept
    // with the workspace ([WorkspacePanesKept]); one that comes back open starts shown, unanimated.
    var switcherOpen by kept::gridOpen
    val thumbnails = remember(current.id) { mutableStateMapOf<String, ImageBitmap>() }
    val layers = remember(current.id) { mutableMapOf<String, GraphicsLayer>() }
    suspend fun snapshot(id: String): ImageBitmap? {
        val layer = layers[id] ?: return null
        if (layer.size.width <= 0 || layer.size.height <= 0) return null
        // Bounded: a thumbnail is a nicety, and a capture that never returns must not hold anything up.
        return runCatching { withTimeoutOrNull(250) { layer.toImageBitmap() } }
            .onFailure { println("[PhoneTabSwitcher] snapshot $id failed: $it") }
            .getOrNull()
            ?.also { thumbnails[id] = it }
    }
    // Snapshots are taken only when the grid opens, of the tab on screen. A capture on LEAVING a
    // tab came from a view already hidden, which could re-lay itself out (insets) before the
    // capture — its picture then sat ~50dp off the live view and jumped when the live view took over.
    LaunchedEffect(tabs.viewIds) { thumbnails.keys.retainAll(tabs.viewIds.toSet()) }

    // Chrome's container transform: opening the grid shrinks the page you were on into its own
    // card; picking a card grows that card back into the page. Only that one tab moves — the grid
    // fades in behind it and otherwise holds still. [morph] is the tab in flight; progress 0 is
    // the full page, 1 is its card's thumbnail.
    var morph by remember(current.id) { mutableStateOf<TabMorph?>(null) }
    val morphProgress = remember(current.id) { Animatable(0f) }
    val morphAlpha = remember(current.id) { Animatable(1f) }
    val gridAlpha = remember(current.id) { Animatable(if (switcherOpen) 1f else 0f) }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var page by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val thumbSlots = remember(current.id) { mutableMapOf<String, LayoutCoordinates>() }
    // The live view under the grid is hidden once something opaque covers it, so a platform view
    // (iOS UIKitView) can never draw over the grid.
    var hideUnderGrid by remember(current.id) { mutableStateOf(switcherOpen) }
    var morphJob by remember { mutableStateOf<Job?>(null) }
    // While a picked card grows into the page the grid keeps showing the OLD selection, and the
    // picked card's own frame is hidden: otherwise its header flips to the selected (teal) fill
    // the moment the pick lands and flashes behind the growing thumbnail.
    var picking by remember(current.id) { mutableStateOf<Pair<String, String?>?>(null) }

    // The keyboard belongs to the tab being left: any switch (grid, swipe, or the selection moving
    // some other way) puts it away and takes focus out of the field so it does not pop back.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dropKeyboard() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    var keyboardTabSeen by remember(current.id) { mutableStateOf(tabs.selectedId) }
    LaunchedEffect(tabs.selectedId) {
        if (tabs.selectedId != keyboardTabSeen) dropKeyboard()
        keyboardTabSeen = tabs.selectedId
    }

    fun openSwitcher() {
        if (switcherOpen || tabs.viewIds.isEmpty()) return
        dropKeyboard()
        val id = tabs.selectedId
        morphJob?.cancel()
        morphJob = scope.launch {
            val bmp = id?.let { snapshot(it) }
            morphProgress.snapTo(0f)
            morphAlpha.snapTo(1f)
            gridAlpha.snapTo(0f)
            if (id != null && bmp != null) morph = TabMorph(id, bmp)
            switcherOpen = true
            // The card the page lands in has to be laid out before it can be aimed at.
            withFrameNanos {}
            withFrameNanos {}
            hideUnderGrid = true
            launch { gridAlpha.animateTo(1f, tween(SWITCHER_FADE_MS)) }
            if (morph != null) morphProgress.animateTo(1f, tween(SWITCHER_MORPH_MS, easing = FastOutSlowInEasing))
            morph = null
        }
    }

    /** Grow [id]'s card back into the page and leave the grid on it (Done and Back pick the current tab). */
    fun pickTab(id: String?) {
        if (!switcherOpen) return
        if (id != null) picking = id to tabs.selectedId
        id?.takeIf { it != tabs.selectedId }?.let { app.setActiveView(current.id, it) }
        morphJob?.cancel()
        morphJob = scope.launch {
            val bmp = id?.let { thumbnails[it] }
            if (id != null && bmp != null && thumbSlots[id]?.isAttached == true) {
                morph = TabMorph(id, bmp)
                morphAlpha.snapTo(1f)
                morphProgress.snapTo(1f)
                morphProgress.animateTo(0f, tween(SWITCHER_MORPH_MS, easing = FastOutSlowInEasing))
                // Landed: show the live view under the picture and fade the picture off it, so
                // whatever changed since the snapshot (a new message) blends in instead of jumping.
                switcherOpen = false
                hideUnderGrid = false
                morphAlpha.animateTo(0f, tween(SWITCHER_HANDOFF_MS))
            } else {
                gridAlpha.animateTo(0f, tween(SWITCHER_FADE_MS))
            }
            switcherOpen = false
            hideUnderGrid = false
            morph = null
            picking = null
        }
    }

    val anyUnread = tabs.viewIds.any { viewsById[it].tabUnread(it == tabs.selectedId, unreadSessions) }
    val tabsButton = PhoneTabsButton(tabs.viewIds.size, anyUnread) { openSwitcher() }
    if (switcherOpen && tabs.viewIds.isEmpty()) {
        switcherOpen = false
        hideUnderGrid = false
    }
    BackHandler(enabled = switcherOpen) { pickTab(tabs.selectedId) }

    // Chrome's tab swipe, over the whole page: a sideways drag moves the page with the finger
    // while the neighbouring tab slides in beside it. Anything that scrolls sideways itself (a
    // wide code block, a table, the editor) gets the drag first; only what it leaves over at its
    // end — the nested-scroll remainder — moves the tab. [swipeX] is the live page's offset;
    // [swipeTo] the neighbour being revealed; after a committed swipe [handoffId] holds the
    // neighbour's picture in place while it fades into the live view (as the grid's hand-off does).
    val swipeX = remember(current.id) { Animatable(0f) }
    var swipeTo by remember(current.id) { mutableStateOf<String?>(null) }
    var handoffId by remember(current.id) { mutableStateOf<String?>(null) }
    val handoffAlpha = remember(current.id) { Animatable(1f) }
    val selectedNow by rememberUpdatedState(tabs.selectedId)
    val idsNow by rememberUpdatedState(tabs.viewIds)
    val pageWidth = { page?.takeIf { it.isAttached }?.size?.width?.toFloat() ?: 0f }

    fun swipeDrag(dx: Float) {
        val w = pageWidth().takeIf { it > 0f } ?: return
        val ids = idsNow
        val idx = ids.indexOf(selectedNow)
        val next = swipeX.value + dx
        val neighbour = ids.getOrNull(if (next < 0f) idx + 1 else idx - 1)
        // Starting: picture the page as it is now, so swiping back to it later has something to show.
        if (swipeX.value == 0f && swipeTo == null) {
            dropKeyboard()
            selectedNow?.let { id -> scope.launch { snapshot(id) } }
        }
        swipeTo = neighbour
        // No tab that way: rubber-band, a quarter of the width at most.
        val x = if (neighbour == null) (swipeX.value + dx * 0.3f).coerceIn(-w / 4f, w / 4f) else next.coerceIn(-w, w)
        scope.launch { swipeX.snapTo(x) }
    }

    fun swipeEnd(velocity: Float) {
        val w = pageWidth()
        val target = swipeTo
        val x = swipeX.value
        val commit = target != null && w > 0f &&
            (abs(x) > w * SWIPE_COMMIT_FRACTION || (abs(velocity) > SWIPE_FLING_PX_S && sign(velocity) == sign(x)))
        scope.launch {
            if (!commit || target == null) {
                swipeX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                swipeTo = null
                return@launch
            }
            swipeX.animateTo(if (x < 0f) -w else w, tween(SWITCHER_SWIPE_MS, easing = FastOutSlowInEasing), velocity)
            handoffAlpha.snapTo(1f)
            handoffId = target
            app.setActiveView(current.id, target)
            withTimeoutOrNull(800) { snapshotFlow { selectedNow }.first { it == target } }
            swipeTo = null
            swipeX.snapTo(0f)
            withFrameNanos {}
            handoffAlpha.animateTo(0f, tween(SWITCHER_HANDOFF_MS))
            handoffId = null
        }
    }

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val swipeShift = Modifier.graphicsLayer { translationX = swipeX.value }
    fun canSwipe() = !switcherOpen && morph == null && handoffId == null && idsNow.size > 1
    // A scrollable that has hit its end hands the rest of the drag up here.
    val swipeNested = remember(current.id) {
        object : NestedScrollConnection {
            var active = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Already mid-swipe: the page takes the drag (both ways) until it is back at rest,
                // so the child does not start scrolling again under a half-moved page.
                if (!active || source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
                swipeDrag(available.x)
                return Offset(available.x, 0f)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
                if (!active && (!canSwipe() || abs(available.x) < abs(available.y))) return Offset.Zero
                active = true
                swipeDrag(available.x)
                return Offset(available.x, 0f)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!active) return Velocity.Zero
                active = false
                swipeEnd(available.x)
                return Velocity(available.x, 0f)
            }
        }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { root = it }.testTag("phone_workspace_tabs")) {
    Column(
        Modifier.fillMaxSize().pointerInput(current.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                // Anywhere below the status bar, never while the grid or a morph is up, and only
                // with somewhere to go. FINAL pass: whatever the page's own content does with the
                // drag (a scrollable, a text selection, a button) comes first, and only a drag
                // nobody consumed is a tab swipe.
                if (!canSwipe() || down.position.y < statusTop) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var total = Offset.Zero
                var dragging = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // Someone below owns this drag (it scrolled, or it is selecting text); a
                    // scrollable at its end reaches the swipe through [swipeNested] instead.
                    if (!dragging && change.isConsumed) return@awaitEachGesture
                    val d = change.position - change.previousPosition
                    total += d
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (!dragging) {
                        // Clearly sideways, or it is a tap / a vertical scroll and stays theirs.
                        if (abs(total.x) > slop && abs(total.x) > abs(total.y) * 1.5f) {
                            dragging = true
                        } else if (abs(total.y) > slop) {
                            return@awaitEachGesture
                        }
                    }
                    if (dragging) {
                        change.consume()
                        swipeDrag(d.x)
                    }
                }
                if (dragging) swipeEnd(velocity.calculateVelocity().x)
            }
        },
    ) {
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
                    // A file's Save and preview toggle ride on its TAB on wide layouts; with no tabs
                    // here they ride in this bar (PhoneFileBar.kt).
                    val file = selectedView?.let { v -> v.filePathOrNull()?.let { path -> v.id to path } }
                    Row(
                        swipeShift.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp)
                            .testTag("phone_workspace_bar"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            selectedView.switcherIcon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        file?.let { (_, path) -> PhoneFileUnsavedDot(ws.documents, path) }
                        Text(
                            selectedView?.let { liveViewTitle(it, sessionNames::get) } ?: "view",
                            fontFamily = MonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        file?.let { (id, path) -> PhoneFileActions(id, path, ws.documents, ws.previewModes) }
                        PhoneTabCountButton(tabsButton)
                    }
                }
            }
        }
        val liveIds = tabs.viewIds.toSet()
        // Keep the last 3 visited views composed (hidden) so WebView/terminal PTY survive tab
        // switches. Evict LRU beyond 3 — more would pin too many WebViews on a phone.
        val retained = rememberVisitedWorkspaces(tabs.selectedId, liveIds, maxSize = 3)
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { page = it }.then(swipeShift).nestedScroll(swipeNested)) {
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
                        val shown = id == tabs.selectedId && !hideUnderGrid
                        KeepAlivePanel(visible = shown) {
                            CompositionLocalProvider(
                                LocalPhoneTabsButton provides tabsButton,
                                // Hidden, here or with its whole workspace, a tab registers no commands.
                                LocalKeyScopeActive provides (LocalKeyScopeActive.current && shown),
                            ) {
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
        if (switcherOpen) {
            PhoneTabSwitcher(
                viewIds = tabs.viewIds,
                selectedId = picking?.let { it.second } ?: tabs.selectedId,
                viewFor = { viewsById[it] },
                titleFor = { id -> viewsById[id]?.let { liveViewTitle(it, sessionNames::get) } ?: "view" },
                unread = { id -> viewsById[id].tabUnread(id == tabs.selectedId, unreadSessions) },
                unsaved = { id -> viewsById[id]?.filePathOrNull()?.let(ws.documents::isDirty) == true },
                thumbnails = thumbnails,
                onSelect = { id -> pickTab(id) },
                // The tab in flight is drawn by the morph; its card's own picture waits for it.
                hiddenThumbId = morph?.id,
                hiddenCardId = picking?.first,
                onThumbPlaced = { id, coords -> thumbSlots[id] = coords },
                modifier = Modifier.graphicsLayer { alpha = gridAlpha.value },
                onClose = { id -> viewsById[id]?.let { closeOrConfirm(it) } },
                onAdd = { showAdd = true },
                onDismiss = { pickTab(tabs.selectedId) },
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
        (swipeTo ?: handoffId)?.let { nid ->
            val handoff = swipeTo == null
            SwipeNeighbour(
                view = viewsById[nid],
                title = viewsById[nid]?.let { liveViewTitle(it, sessionNames::get) } ?: "view",
                bitmap = thumbnails[nid],
                offsetX = {
                    if (handoff) 0f
                    else swipeX.value + if (swipeX.value < 0f) pageWidth() else -pageWidth()
                },
                alpha = { if (handoff) handoffAlpha.value else 1f },
            )
        }
        if (showAdd) {
            BackHandler { showAdd = false }
            PhoneNewTabPage(
                choices = newTabChoices(current, layout, viewsById, phoneAddKinds()),
                workspace = current.name,
                branch = current.branch,
                onPick = { kind ->
                    showAdd = false
                    switcherOpen = false
                    hideUnderGrid = false
                    addPhoneView(current, ws, app, kind)
                },
                onClose = { showAdd = false },
            )
        }
        morph?.let { m ->
            TabMorphOverlay(
                bitmap = m.bitmap,
                background = MaterialTheme.colorScheme.surfaceContainerLow,
                alpha = { morphAlpha.value },
                progress = { morphProgress.value },
                root = { root },
                from = { page },
                to = { thumbSlots[m.id] },
            )
        }
    }

    closeCandidate?.let { v ->
        CloseViewDialog(
            view = v,
            sessionNames = sessionNames,
            onDismiss = { kept.askToClose(null) },
            onConfirm = {
                app.closeWorkspaceView(current.id, v.id)
                kept.askToClose(null)
            },
            chatWorkdir = v.chatSessionId()?.let { sid -> app.sessions.value.firstOrNull { it.id == sid }?.workdir },
            worktreeForWorkdir = { app.worktreeForWorkdir(it) },
            worktreeSize = { app.worktreeSize(it) },
            onConfirmDeletingWorktree = { ids ->
                kept.askToClose(null)
                app.closeViewAndDeleteWorktree(current.id, v.id, ids) {
                    reportWorktreeDelete(notices, it, WorktreeDeleteAction.Close)
                }
            },
            retainKey = kept.closingKey,
        )
    }
}

/**
 * What a workspace's panes had open, kept by the fleet for a layout that a rotation or a width
 * change rebuilds (#89): whose walkthrough the Changes pane shows, the close question with its
 * worktree choice, its New tabs, and on a phone the tab grid and its New tab page. Both layouts read
 * the same one, so the layout a swap builds finds what the other had open.
 */
internal class WorkspacePanesKept {
    var walkthroughSessionId by mutableStateOf<String?>(null)
    /**
     * The view whose close is being asked about. Resolved against the live views, so a view that
     * goes away takes its question with it.
     */
    var closingViewId by mutableStateOf<String?>(null)
        private set
    /** Names this asking: its worktree choice is kept under it, so the next asking starts unticked. */
    private var closingOpening: String? = null
    var gridOpen by mutableStateOf(false)
    var addViewOpen by mutableStateOf(false)
    val newTabs = NewTabsState()

    /** Ask about closing [view], or drop the question (null). */
    fun askToClose(view: ViewDto?) {
        closingOpening = view?.let { newRetainedPageOpening() }
        closingViewId = view?.id
    }

    /** What the close dialog keeps its worktree choice under, while a question is open. */
    val closingKey: String? get() = closingOpening?.let { "closeView:$it" }
}

/** The [WorkspacePanesKept] of [workspaceId]. */
@Composable
internal fun rememberWorkspacePanesKept(workspaceId: String): WorkspacePanesKept =
    rememberRetained("workspace:$workspaceId:panes") { WorkspacePanesKept() }

/** Chrome's page ⇄ card morph and the grid's fade, measured off a screen recording of it. */
private const val SWITCHER_MORPH_MS = 260
private const val SWITCHER_FADE_MS = 160
private const val SWITCHER_HANDOFF_MS = 120
private const val SWITCHER_SWIPE_MS = 200
private const val SWIPE_COMMIT_FRACTION = 0.33f
private const val SWIPE_FLING_PX_S = 1200f

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
 * A terminal tab in window [hostId]: in the group of the pane pressed last, else of the file last worked in, else
 * the window's first group, the way "+ → Terminal" adds one there.
 */
fun addTerminalInWindow(ui: ShellUiState, hostId: String, ws: WorkspaceSession, app: HostStore, workspaceId: String) {
    val tree = ui.windows.layoutFor(hostId, ws.layoutSync.tree)
    val gid = ws.focusedViewId?.let { groupIdOf(tree, it) }
        ?: ws.focusedFileViewId?.let { groupIdOf(tree, it) }
        ?: firstGroupId(tree)
        ?: return
    app.addWorkspaceView(workspaceId, NewViewKind.TERMINAL, gid) { newViewId ->
        ui.windows.expandClaim(hostId, setOf(newViewId), ws.layoutSync.tree)
    }
}

/** A pane's [KeyContext], from its view's kind (ViewHost.kt's switch). */
internal fun paneKeyContext(view: ViewDto): KeyContext = when (view.kind) {
    "chat" -> KeyContext.Chat
    // A terminal or a remote display is scoped where it is drawn, so every one is, not only a pane's.
    "editor" -> when (view.stateString("mode")) {
        "file", "diff" -> KeyContext.Editor
        else -> KeyContext.Files
    }
    else -> KeyContext.Other
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
            // What the keyboard knows about this pane: its kind (a terminal keeps its keys) and its view.
            val paneScope = rememberKeyScope(paneKeyContext(v), viewId)
            ProvideKeyScope(paneScope) {
            ViewHost(
                previewModeFor = { previewModes[it] == true },
                view = v,
                workspaceId = current.id,
                workdir = current.workdir,
                actions = rememberShellActions(viewApp, appFor),
                chatState = { sid -> rememberChatState(viewApp, sid) },
                chatActions = { s ->
                    val openAccounts = remember(ui) { { ui.openSettings(dev.supermux.ui.nav.SettingsSection.Accounts) } }
                    val openSignIn = remember(ui) { { ui.openSettings(dev.supermux.ui.nav.SettingsSection.Providers) } }
                    rememberChatActions(viewApp, s, openAccounts = openAccounts, openSignIn = openSignIn)
                },
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
                // A press anywhere in a pane makes it the one Ctrl/Cmd+T opens a terminal beside,
                // and in a file pane the file the Files tree follows. (The desktop JCEF editor is
                // heavyweight and never reports presses; its tab does.)
                modifier = Modifier.fillMaxSize().keyScope(paneScope).observePress(viewId) {
                    ws.focusedViewId = viewId
                    if (v.filePathOrNull() != null) ws.focusedFileViewId = viewId
                },
            )
            }
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
 *
 * With [onRelease] it claims nothing instead, and fires on LIFT: a held finger that then moves past
 * the slop is the start of a drag (a tab strip's tab is dragged by hold-then-move on touch), so it
 * is left to the drag and [onLongPress] never runs; one that lifts in place gets it. Firing at the
 * hold would open a popup in the middle of the drag, and a focusable popup takes the rest of the
 * gesture on Compose Multiplatform hosts.
 */
internal fun Modifier.longPress(
    key: Any?,
    onRelease: Boolean = false,
    onLongPress: () -> Unit,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onLongPress)
    pointerInput(key, onRelease) {
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
            if (onRelease) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > slop) break
                    if (!change.pressed) {
                        latest()
                        break
                    }
                }
                return@awaitEachGesture
            }
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
    /**
     * True on a drag-reorderable strip, where the hold also arms a touch drag: the menu opens when
     * the held finger LIFTS in place, and a held finger that moves drags the tab instead.
     */
    openOnRelease: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    var open by remember(itemId) { mutableStateOf(false) }
    Box(Modifier.longPress(itemId, onRelease = openOnRelease) { open = true }) {
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

/** The tone a view's content is drawn on: the sheet under its strip, and its tab while active. */
@Composable
private fun sheetToneOf(v: ViewDto?): Color {
    val cs = MaterialTheme.colorScheme
    return when {
        v == null -> cs.surfaceContainerLow
        // A chat tab with no chat yet shows the launcher, in its own tone.
        v.kind == "chat" && v.chatSessionId() == null -> cs.surfaceContainer
        v.kind == "terminal" -> terminalPalette().background
        v.filePathOrNull() != null -> Color(LocalPanes.current.code)
        v.kind == "editor" && v.stateString("mode") == "diff" -> cs.surface
        else -> cs.surfaceContainerLow
    }
}

/** A tab's 16 dp icon: the agent's mark on a chat, a glyph for what any other view holds. */
@Composable
private fun ViewTabIcon(v: ViewDto?, agent: String?, selected: Boolean) {
    if (v?.kind == "chat" && agent != null) {
        AgentLogo(agent = agent, size = 14.dp)
        return
    }
    val cs = MaterialTheme.colorScheme
    val glyph = when {
        v?.kind == "chat" -> Icons.Outlined.ChatBubbleOutline
        v?.kind == "terminal" -> Icons.Outlined.Terminal
        v?.kind == "display" -> Icons.Outlined.DesktopWindows
        v?.kind == "editor" && v.stateString("mode") == "diff" -> Icons.Outlined.Difference
        v != null && v.isFilesTreeView() -> Icons.Outlined.Folder
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    Icon(glyph, contentDescription = null, tint = if (selected) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
}
