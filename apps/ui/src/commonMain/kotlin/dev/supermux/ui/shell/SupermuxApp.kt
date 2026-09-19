// THE root. One shell for every host and every width (cluster G8).
//
// The base is desktop's `shell/AppShell.kt`: a collapsible, drag-resizable sidebar of WORKSPACES,
// the open workspace's pane tree, a Navigation 3 back stack whose overlays keep Home composed
// underneath, and the keyboard shortcuts. Android's compact scaffold is the Compact branch of the
// same tree — its nine `composable<Route.X>` destinations are the `entry<>`s below, its phone tab
// strip is [PhoneWorkspacePanes], its list⇄chat keep-alive is the Compact form of the same
// [WorkspaceKeepAliveHost], and its `PhoneLayerBack` + per-layer `BackHandler`s are ONE
// single-owner handler with predictive back.
//
// Two of desktop's booleans became routes: the New-Session launcher is `Route.NewSession` and the
// Usage card is `Route.Usage` (`ui/nav/Route.kt`'s KDoc flagged the fold). Nothing changed for a
// desktop user: on a wide host those two entries paint NOTHING and Home's own detail pane / the
// sidebar footer draw them in place, which is exactly the detail-pane swap and the anchored
// popover they always were. Under Compact they are ordinary full-screen pushes — Android's.
//
// What stays per host: the window/tray/menu bar and the extra OS windows (desktop `Main.kt` +
// `WindowHosts`/`DetachedWorkspaceWindow`, reached here only through [ShellWindows] and
// `Platform.windows`), the local-broker supervisor and the host wizard (desktop, behind `caps`),
// splash + edge-to-edge + `InputModeDetector` + push (Android `MainActivity`), and the
// session-only chat for a session that belongs to no workspace ([chatFallback]).
package dev.supermux.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.supermux.host.ViewingSurface
import dev.supermux.host.WorkspaceViewingSnapshot
import dev.supermux.host.viewingSurfaceVisible
import dev.supermux.host.visibleWorkspaceChatIds
import dev.supermux.host.workspaceForSession
import dev.supermux.net.AddViewBody
import dev.supermux.net.PatchWorkspaceBody
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.session.asSettledSession
import dev.supermux.session.inferHomeDir
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.SidebarReorderKind
import dev.supermux.state.sidebarReorderKind
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.display.DisplaysScreen
import dev.supermux.ui.display.rememberDisplayActions
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.host.AddHostScreen
import dev.supermux.ui.host.HostScopePicker
import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.notify.NotificationController
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.PaneStripChrome
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NoopNotificationManager
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.session.ArchivedActions
import dev.supermux.ui.session.ArchivedScreen
import dev.supermux.ui.session.SessionLauncherScreen
import dev.supermux.ui.session.SessionListFooter
import dev.supermux.ui.session.SessionListMode
import dev.supermux.ui.session.SessionListScreen
import dev.supermux.ui.session.rememberArchivedActions
import dev.supermux.ui.session.rememberLauncherActions
import dev.supermux.ui.session.rememberSessionListActions
import dev.supermux.ui.session.withWorkspaceOps
import dev.supermux.ui.settings.AppearanceSettingsScreen
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.SettingsExtra
import dev.supermux.ui.settings.SettingsHub
import dev.supermux.ui.settings.SettingsSlotScope
import dev.supermux.ui.settings.rememberDevicesSettingsActions
import dev.supermux.ui.settings.rememberProxiesSettingsActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.update.AppUpdateBannerHost
import dev.supermux.ui.update.AppUpdateScreen
import dev.supermux.ui.usage.UsagePopover
import dev.supermux.ui.usage.UsageScreen
import dev.supermux.ui.usage.rememberUsageActions
import dev.supermux.ui.widgets.keepAlivePanel
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.ui.workspace.rememberWorkspaceSession
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.chatSessionIds
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.toDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Which chats of one workspace are actually ON SCREEN (spec §11).
 *
 * Compact: only the active tab's chat, if that tab is a chat. Anything wider: every chat that is
 * the active view of some group. A chat in a background tab is not on screen.
 */
fun visibleWorkspaceChatIdsAt(
    compact: Boolean,
    layout: LayoutNode?,
    views: List<ViewDto>,
    activeViewId: String?,
): List<String> {
    val byId = views.associateBy { it.id }
    if (!compact) {
        val ids = layout?.let { collectActiveViewIds(it) } ?: views.map { it.id }
        return ids.mapNotNull { id -> byId[id]?.chatSessionId() }.distinct()
    }
    val selected = phoneTabModel(layout, activeViewId).selectedId
    return listOfNotNull(selected?.let { byId[it]?.chatSessionId() })
}

fun visibleWorkspaceChatIdsAt(compact: Boolean, workspace: WorkspaceDto, layout: LayoutNode?): List<String> =
    visibleWorkspaceChatIdsAt(compact, layout, workspace.views, workspace.activeViewId)

/**
 * True when the workspace layer is the surface the user is looking at.
 *
 * [Route.Usage] counts on a WIDE host only, where the Usage card is an anchored popover over the
 * workspace rather than a pushed screen.
 */
fun workspaceLayerVisible(
    currentRoute: Route,
    usageIsPopover: Boolean,
    selectedSessionAvailable: Boolean,
    activeWorkspaceAvailable: Boolean,
): Boolean =
    (currentRoute is Route.Home || (usageIsPopover && currentRoute is Route.Usage)) &&
        selectedSessionAvailable &&
        activeWorkspaceAvailable

/**
 * The paired app.
 *
 * @param fleet every paired host, merged. Both apps drive the shell through it; a per-session or
 *   per-workspace op routes to the owning host inside the store.
 * @param ui the shell's own state — desktop creates it in `Main.kt` so the native MenuBar acts on
 *   the same instance, Android holds it in `rememberSaveable(saver = ShellUiState.Saver)`.
 * @param appForeground whether the app is actually in front (desktop: window focus; Android: the
 *   lifecycle at least STARTED). Gates viewing presence and OS notifications.
 * @param stripChrome the pane strip's platform chrome (desktop's macOS title-bar drag regions).
 * @param sidebarTopPad the band the sidebar body keeps clear (macOS traffic lights).
 * @param sidebarChrome anything the host draws over the sidebar seam (the macOS collapse control).
 * @param chatFallback a chat for a session that belongs to NO workspace — an old broker, or a row
 *   opened from the settled fold. Android renders its own `ChatScreen` here; desktop has none and
 *   keeps the "select a workspace" pane it always showed.
 * @param persistSelection write the selected session id through [dev.supermux.ui.prefs.UiPrefs].
 *   Desktop restores it next launch; Android deliberately opens on the list.
 * @param sessionListMode desktop's sidebar lists WORKSPACES, Android's lists the fleet's sessions.
 *   The one genuine per-host choice left in the sidebar; everything else is width-branched.
 */
@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SupermuxApp(
    fleet: FleetStore,
    ui: ShellUiState,
    modifier: Modifier = Modifier,
    notify: NotificationController = remember { NotificationController(NoopNotificationManager) },
    appearance: AppearanceMode = AppearanceMode.DARK,
    onToggleTheme: () -> Unit = {},
    appForeground: Boolean = LocalWindowInfo.current.isWindowFocused,
    homeFallback: String = "",
    stripChrome: PaneStripChrome = PaneStripChrome.None,
    sidebarTopPad: Dp = 0.dp,
    sidebarChrome: @Composable BoxScope.(sidebarWidth: Dp, collapsed: Boolean) -> Unit = { _, _ -> },
    chatFallback: (@Composable (session: SessionInfo, visible: Boolean, onBack: () -> Unit) -> Unit)? = null,
    persistSelection: Boolean = false,
    /**
     * The dispose-path launcher-draft write. The default (`null`) leaves the screen's own
     * debounced write, which runs on this root's scope and therefore survives the launcher's
     * dispose. A host whose WINDOW can close out from under that scope (desktop) passes a write
     * that blocks until the draft is on disk.
     */
    onLauncherDraftFlush: ((dev.supermux.state.LauncherDraft) -> Unit)? = null,
    autoSelect: Boolean = false,
    autoSelectName: String? = null,
    defaultDeviceName: String = "This device",
    sessionListMode: SessionListMode = SessionListMode.Workspaces,
    groupByProject: Boolean = true,
    onGroupByProjectChange: (Boolean) -> Unit = {},
    onAddedHost: () -> Unit = {},
    settingsExtra: @Composable (SettingsExtra, SettingsSlotScope) -> Unit = { _, _ -> },
    settingsSection: @Composable (SettingsSection, SettingsSlotScope) -> Unit = { _, _ -> },
) {
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // The anchored popover needs a pointer (UsagePopover draws nothing without one), so a wide
    // touch-only host — an unfolded foldable, a tablet — gets the full-pane Usage route instead.
    val usageIsPopover = !compact && dev.supermux.ui.adaptive.LocalPointerAvailable.current
    val openByWorkspace = sessionListMode == SessionListMode.Workspaces

    val sessions by fleet.sessions.collectAsState()
    val archivedSessions by fleet.archivedSessions.collectAsState()
    val workspaces by fleet.workspaces.collectAsState()
    val archivedWorkspaces by fleet.archivedWorkspaces.collectAsState()
    val messages by fleet.messages.collectAsState()
    val agentState by fleet.agentState.collectAsState()
    val lastRead by fleet.lastRead.collectAsState()
    val hostViews by fleet.hostViews.collectAsState()
    val sessionHost by fleet.sessionHost.collectAsState()
    val activeHostId by fleet.activeHost.collectAsState()
    val lastBySession = remember(messages) { messages.mapValues { it.value.lastOrNull() } }

    // Shared across the sidebar and the layout host so a tab can drop onto a workspace row.
    val tabDragState = remember { PaneDragController() }

    // The active host's app (host-global ops: spawn / archived / usage / settings).
    val hostApp = fleet.appForRecord(activeHostId) ?: fleet.activeApp()
    val activeHostSessions = remember(sessions, sessionHost, hostViews, activeHostId) {
        if (hostViews.size >= 2 && activeHostId != null) {
            sessions.filter { sessionHost[it.id] == activeHostId }
        } else {
            sessions
        }
    }
    // The app owning a given session; falls back to the active host's (single-host / unknown id).
    val appFor: (String) -> HostStore? = { id -> fleet.appFor(id) ?: hostApp }

    var hostFilter by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(fleet) { hostFilter = fleet.hostFilter.first() }
    val setHostFilter: (String?) -> Unit = { hostFilter = it; fleet.saveHostFilter(it) }

    // Refresh the settled fold when the live list changes (settle / resume / snapshot).
    LaunchedEffect(sessions) { fleet.refreshArchived() }

    val selectedSession = ui.selectedId?.let { selectedId ->
        sessions.firstOrNull { it.id == selectedId }
            ?: archivedSessions.firstOrNull { it.id == selectedId }?.asSettledSession()
    }
    val activeWorkspace = ui.selectedId?.let { workspaceForSession(workspaces, it) }
    val workspaceLayerVisible = workspaceLayerVisible(
        currentRoute = ui.currentRoute,
        usageIsPopover = usageIsPopover,
        selectedSessionAvailable = selectedSession != null,
        activeWorkspaceAvailable = activeWorkspace != null,
    )

    val onNewSession: () -> Unit = { ui.openLauncher() }

    // Cluster G1: detaching a pane into a real OS window is `Platform.windows`.
    val windowHostController = LocalPlatform.current.windows
    val onTearOutTab: (String) -> Unit = { viewId -> windowHostController?.tearOutTab(viewId) }
    val onMoveToNewWindow: () -> Unit = move@{
        val bind = ui.panesBind ?: return@move
        val viewId = collectActiveViewIds(bind.ws.layoutSync.tree).firstOrNull() ?: return@move
        onTearOutTab(viewId)
    }

    // Scope for fire-and-forget actions that must outlive an overlay's composition (the archived
    // Resume POST closes the overlay the instant it is tapped).
    val overlayScope = rememberCoroutineScope()
    val notices = LocalPlatform.current.notices

    val listActions = rememberSessionListActions(fleet)
    val uiPrefs = LocalUiPrefs.current
    val onCollapsedPathsChange: (Set<String>) -> Unit = { paths ->
        ui.collapsedProjectPaths = paths
        overlayScope.launch { uiPrefs.putCollapsedProjectPaths(paths) }
    }

    // Per-session composer drafts, hoisted to the ROOT so switching sessions — and leaving the
    // workspace entirely — preserves each draft. In-memory only (broker-side sync is M4).
    val drafts = remember { mutableStateMapOf<String, String>() }

    // Headless-verification hook (no input injection on CI boxes); off by default.
    if (autoSelect) {
        LaunchedEffect(sessions, lastBySession) {
            if (ui.selectedId == null && sessions.isNotEmpty()) {
                ui.selectedId = autoSelectName?.let { n -> sessions.firstOrNull { it.name == n }?.id }
                    ?: sessions.maxByOrNull { lastBySession[it.id]?.ts ?: "" }?.id
                    ?: sessions.first().id
            }
        }
    }

    // Viewing presence: one frame per visible chat view (spec §11). ALSO clears each session's
    // notification cooldown the moment it becomes actively viewed, so the NEXT reply after the
    // user looks away notifies immediately rather than waiting out a stale dedup window.
    val viewingSnapshot = run {
        val surface = ViewingSurface(
            homeRoute = ui.currentRoute is Route.Home || (usageIsPopover && ui.currentRoute is Route.Usage),
            overlayOpen = false,
            workspaceResolved = activeWorkspace != null,
            appForeground = appForeground,
        )
        val visible = viewingSurfaceVisible(surface) && workspaceLayerVisible
        val snap = activeWorkspace?.let { ws ->
            WorkspaceViewingSnapshot(
                workspaceId = ws.id,
                visibleChatSessionIds = visibleWorkspaceChatIdsAt(compact, ws, ws.layout.toDomainOrNull()),
                appForeground = appForeground,
            )
        }
        val visibleIds = visibleWorkspaceChatIds(
            surfaceVisible = visible,
            selectedWorkspaceId = activeWorkspace?.id,
            snapshot = snap,
        )
        when {
            visible && snap != null -> snap.copy(visibleChatSessionIds = visibleIds)
            ui.currentRoute is Route.Home && appForeground ->
                WorkspaceViewingSnapshot("", emptyList(), true)
            else -> null
        }
    }
    LaunchedEffect(viewingSnapshot) {
        ui.selectedId?.let { sessionHost[it] }?.let { fleet.setActiveHost(it) }
        ui.selectedId?.let { fleet.ensureMessagesLoaded(it) }
        fleet.updateViewing(viewingSnapshot)
        for (sid in viewingSnapshot?.visibleChatSessionIds.orEmpty()) notify.onSessionFocused(sid)
    }

    // Activate the opened session's chat view once per selection — never in response to a
    // WorkspaceChanged frame (that is the broker acknowledging a user tab switch). Cold start
    // retries while workspaces are still empty.
    var lastActivatedSelection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.selectedId) { if (ui.selectedId == null) lastActivatedSelection = null }
    LaunchedEffect(ui.selectedId, workspaces, sessionHost) {
        val sid = ui.selectedId ?: return@LaunchedEffect
        val ws = workspaceForSession(workspaces, sid)
        val chatView = ws?.views?.firstOrNull { v -> v.chatSessionId() == sid }
        when (chatActivationDecision(sid, lastActivatedSelection, ws, chatView)) {
            ChatActivationHandle.Skip -> Unit
            ChatActivationHandle.ApplyRetry -> Unit
            ChatActivationHandle.ApplyConsume -> {
                if (ws != null && chatView != null && ws.activeViewId != chatView.id) {
                    fleet.setActiveView(ws.id, chatView.id)
                }
                lastActivatedSelection = sid
            }
        }
    }

    // Observe live agent replies and decide whether to raise an OS notification. Keyed on
    // [appForeground] so the collector relaunches with a freshly captured value.
    LaunchedEffect(fleet, appForeground) {
        fleet.agentReplies.collect { event ->
            val target = fleet.sessions.value.firstOrNull { it.id == event.session }
            notify.onAgentReply(
                entry = event.entry,
                session = event.session,
                sessionName = target?.name ?: event.session,
                selectedId = ui.selectedId,
                windowFocused = appForeground,
                muted = target?.mute ?: false,
            )
        }
    }

    // Sessions changed: reconcile the selection against the live set (empty-guarded) and prune
    // stale drafts — a killed session must not keep its text alive for one that reuses the id.
    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) return@LaunchedEffect
        val live = sessions.mapTo(mutableSetOf()) { it.id }
        ui.reconcileSessions(live)
        drafts.keys.filterNot { it in live }.forEach(drafts::remove)
    }

    // Debounced persistence of the screen-level shell state, through the SAME `SettingsKeys` both
    // hosts read (cluster G8 retired desktop's `ui-state.json` fields; the window bounds stay
    // there). Observed through snapshotFlow so a sidebar drag never recomposes the root per frame.
    LaunchedEffect(uiPrefs, persistSelection) {
        snapshotFlow { Triple(ui.sidebarCollapsed, ui.sidebarWidth.value, ui.selectedId) }
            .collectLatest { (collapsed, width, selected) ->
                delay(500)
                withContext(Dispatchers.Default) {
                    runCatching {
                        uiPrefs.putSidebarCollapsed(collapsed)
                        uiPrefs.putSidebarWidthDp(width)
                        if (persistSelection) uiPrefs.putSelectedSession(selected)
                    }
                }
            }
    }

    val home = remember(sessions, homeFallback) {
        inferHomeDir(sessions.firstOrNull()?.workdir) ?: homeFallback
    }

    // ── The launcher pane, in TWO places: the New-Session destination and a workspace tab whose
    //    chat view has no session yet. Extracted so both render exactly the same thing.
    val launcherActions = rememberLauncherActions(fleet, onOpenSession = { ui.selectSession(it) })
    val launcherDraftFlushDefault: (dev.supermux.state.LauncherDraft) -> Unit = { draft ->
        overlayScope.launch { uiPrefs.putLauncherDraft(draft) }
    }
    val launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit = { onBack, onCreated, tab ->
        SessionLauncherScreen(
            sessions = activeHostSessions,
            home = home,
            onBack = onBack,
            lastBySession = lastBySession,
            actions = launcherActions,
            loadPrefs = { uiPrefs.launcherPrefs.first() },
            onPrefsChange = { overlayScope.launch { uiPrefs.putLauncherPrefs(it) } },
            // A workspace tab keeps ITS OWN draft (just the text, in the tab's state) — never the
            // New Session screen's global one, which would drag another project's workdir,
            // worktree and text into this tab. The broker drops it when it binds the tab.
            loadDraft = if (tab == null) {
                { uiPrefs.launcherDraft.first() }
            } else {
                { dev.supermux.state.LauncherDraft(text = tab.draftText) }
            },
            onDraftChange = if (tab == null) {
                { overlayScope.launch { uiPrefs.putLauncherDraft(it) } }
            } else {
                { tab.onDraftText(it.text) }
            },
            onClearDraft = if (tab == null) {
                { overlayScope.launch { uiPrefs.clearLauncherDraft() } }
            } else {
                {}
            },
            onDraftFlush = if (tab == null) {
                onLauncherDraftFlush ?: launcherDraftFlushDefault
            } else {
                { tab.onDraftText(it.text) }
            },
            // Throws the broker's OWN refusal (bad workdir / spawn 4xx), which the screen's
            // doSubmit try/catch turns into the inline launcher_error text. The BROKER delivers
            // the first message (text + pre-uploaded files) with the spawn, so nothing about it
            // depends on this pane staying composed.
            onSubmit = { workdir, agent, model, level, text, staged, worktree, baseBranch, replaceDraftId ->
                onCreated(
                    if (tab == null) {
                        launcherActions.createSessionWithFirstMessage(
                            workdir, agent, model, level, text, staged, worktree, baseBranch, replaceDraftId,
                        )
                    } else {
                        // A workspace tab's composer: JOIN that workspace and fill this very tab.
                        // Without workspaceId the broker mints a second workspace for the session.
                        fleet.createSessionWithFirstMessageOrThrow(
                            workdir, agent, model, level, text, staged, worktree, baseBranch, replaceDraftId,
                            workspaceId = tab.workspaceId,
                            viewId = tab.viewId,
                        )
                    },
                )
                null
            },
            onSaveDraft = { workdir, agent, model, level, text, replaceDraftId ->
                launcherActions.createDraftSession(workdir, agent, model, level, text, replaceDraftId)
            },
            workspaceWorkdir = tab?.workdir,
            // A reopened task-list draft belongs to the New Session route, never to a tab.
            initialDraftId = if (tab == null) ui.launcherDraftId else null,
            initialDraft = if (tab == null) ui.launcherDraftId?.let { dId -> sessions.find { it.id == dId } } else null,
            hosts = hostViews,
            selectedHost = activeHostId,
            // The shell owns this pane's chrome on a wide host (the sidebar / the tab strip), so
            // the screen paints no bar there. Under Compact the launcher is its OWN destination
            // and paints the title + Back at every width — cluster E's chrome rule.
            topBarShown = !compact,
            standalone = compact,
        )
    }

    val onNewChatInWorkspace: (WorkspaceDto) -> Unit = { w ->
        overlayScope.launch {
            val recordId = fleet.activeHost.value
            if (recordId == null) {
                notices.show("No host connected")
            } else {
                runCatching { ui.selectSession(fleet.newChatInWorkspace(recordId, w.id, w.workdir)) }
                    .onFailure { notices.show(dev.supermux.state.spawnFailureMessage(it)) }
            }
        }
    }

    // Root focus so the shortcuts (Ctrl/Cmd B/N) resolve even before the user clicks into a pane;
    // once the composer/terminal is focused, key events still bubble up here.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // ── ONE back handler for the whole app: Android's `PhoneLayerBack` plus the two per-layer
    //    handlers, folded together. Panes that own BACK themselves — the editor's search overlay
    //    and its tree drawer — register their own handler deeper in the tree, and Compose
    //    dispatches to the innermost enabled one first, so they still win. The IME is the
    //    platform's: while it is up the system hides it before this handler is reached.
    var backProgress by remember { mutableFloatStateOf(0f) }
    val canPopLayer = compact && ui.currentRoute is Route.Home && ui.selectedId != null
    BackHandler(enabled = ui.overlayOpen) { ui.goBack() }
    PredictiveBackHandler(enabled = canPopLayer) { events ->
        try {
            events.collect { e -> backProgress = e.progress }
            ui.selectedId = null
        } catch (_: Throwable) {
            // Cancelled gesture: keep the selection, drop the scale.
        }
        backProgress = 0f
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                // Gate the pane/sidebar shortcuts OFF while any destination above Home is up: it
                // is modal, so a chord it leaves unhandled must NOT bubble here and silently
                // mutate the layout behind it.
                .then(
                    if (ui.overlayOpen) Modifier
                    else Modifier.shellShortcuts(ui, onNewSession, onMoveToNewWindow),
                ),
        ) {
            AppUpdateBannerHost(onOpenPage = { ui.openAppUpdate() }, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {

                    val usageActions = rememberUsageActions(fleet)
                    // Body shared by the wide footer-anchored popover and the Compact destination.
                    val usagePopoverBody: @Composable () -> Unit = {
                        Column(Modifier.fillMaxWidth()) {
                            HostScopePicker(hostViews, activeHostId, onSelect = { fleet.setActiveHost(it) })
                            UsageScreen(
                                actions = usageActions,
                                onBack = { ui.closeUsage() },
                                // The popover owns this surface's chrome (the ✕ + the picker
                                // above), so the screen must not paint a top bar even when the
                                // window is narrowed into the Compact width class.
                                topBarShown = true,
                            )
                        }
                    }

                    // Settings dirty-soul close (SettingsHub); Esc / NavDisplay onBack honour it.
                    var settingsTryClose by remember { mutableStateOf<(() -> Unit)?>(null) }
                    val fullPaneOverlay = remember { FullPaneOverlaySceneStrategy<Route>() }

                    // The sidebar, drawn by Home on a wide host and by the Compact list layer.
                    val sidebarList: @Composable (standalone: Boolean, listState: LazyListState?) -> Unit =
                        { standalone, listState ->
                            SessionListScreen(
                                mode = sessionListMode,
                                openWorkspaceByWorkspaceId = openByWorkspace,
                                workspaces = workspaces,
                                home = home,
                                activeId = if (openByWorkspace) {
                                    workspaces.firstOrNull { w -> w.chatSessionIds().contains(ui.selectedId) }?.id
                                } else {
                                    ui.selectedId
                                },
                                onOpen = { id ->
                                    if (openByWorkspace) {
                                        workspaces.firstOrNull { it.id == id }
                                            ?.chatSessionIds()?.firstOrNull()
                                            ?.let { ui.selectSession(it) }
                                    } else {
                                        ui.selectSession(id)
                                    }
                                },
                                sessions = sessions,
                                lastBySession = lastBySession,
                                lastRead = lastRead,
                                agentState = agentState,
                                onOpenSession = { _, sid -> ui.selectSession(sid) },
                                actions = remember(listActions, workspaces) {
                                    listActions.withWorkspaceOps(
                                        archiveWorkspace = { wid ->
                                            if (workspaces.firstOrNull { it.id == wid }
                                                    ?.chatSessionIds()?.contains(ui.selectedId) == true
                                            ) {
                                                ui.selectedId = null
                                            }
                                            listActions.archiveWorkspace(wid)
                                        },
                                        restoreWorkspace = { wid ->
                                            listActions.restoreWorkspace(wid)
                                            ui.selectedArchivedWorkspaceId = null
                                        },
                                    )
                                },
                                onKilled = { id -> if (ui.selectedId == id) ui.selectedId = null },
                                onNewSession = onNewSession,
                                onNewChatInWorkspace = onNewChatInWorkspace,
                                archived = archivedSessions,
                                archivedWorkspaces = archivedWorkspaces,
                                archivedActiveId = ui.selectedArchivedWorkspaceId,
                                onSelectArchived = { ui.selectArchivedWorkspace(it) },
                                onOpenDraft = { id -> ui.openLauncher(draftId = id) },
                                onReorder = { ids ->
                                    if (sidebarReorderKind(workspaces) == SidebarReorderKind.SESSIONS) {
                                        listActions.reorderSessions(ids)
                                    } else {
                                        listActions.reorderWorkspaces(ids)
                                    }
                                },
                                onNavigate = { dest -> ui.navigateByName(dest) },
                                hosts = hostViews,
                                sessionHost = sessionHost,
                                hostFilter = hostFilter,
                                onHostFilter = setHostFilter,
                                onAddHost = { ui.openAddHost() },
                                initialCollapsedPaths = ui.collapsedProjectPaths,
                                onCollapsedPathsChange = onCollapsedPathsChange,
                                initialGroupByProject = groupByProject,
                                onGroupByProjectChange = onGroupByProjectChange,
                                // Inside the shell's own frame the list is a PANE: no top bar, no
                                // FAB, even when the window narrows past 600dp. As the Compact
                                // home layer it IS the whole surface and paints both.
                                standalone = standalone,
                                topBarShown = !standalone,
                                footer = run {
                                    {
                                        SessionListFooter(
                                            appearance = appearance,
                                            onToggleTheme = onToggleTheme,
                                            onUsage = { ui.openUsage() },
                                            onDevices = { ui.openSettings(SettingsSection.Devices) },
                                            onSettings = { ui.openSettings() },
                                            usageOpen = ui.usageOpen,
                                            onUsageDismiss = { ui.closeUsage() },
                                            usageContent = usagePopoverBody,
                                        )
                                    }
                                },
                                tabDragState = tabDragState,
                                listState = listState ?: androidx.compose.foundation.lazy.rememberLazyListState(),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                    NavDisplay(
                        backStack = ui.backStack,
                        modifier = Modifier.fillMaxSize(),
                        onBack = {
                            when (ui.currentRoute) {
                                is Route.Settings -> settingsTryClose?.invoke() ?: run { ui.goBack() }
                                else -> ui.goBack()
                            }
                        },
                        sceneStrategies = listOf(fullPaneOverlay),
                        entryProvider = entryProvider {
                            entry<Route.Home> {
                                ShellHome(
                                    fleet = fleet,
                                    ui = ui,
                                    compact = compact,
                                    sessions = sessions,
                                    archivedSessions = archivedSessions,
                                    workspaces = workspaces,
                                    archivedWorkspaces = archivedWorkspaces,
                                    agentState = agentState,
                                    lastBySession = lastBySession,
                                    lastRead = lastRead,
                                    selectedSession = selectedSession,
                                    activeWorkspace = activeWorkspace,
                                    workspaceLayerVisible = workspaceLayerVisible,
                                    appFor = appFor,
                                    hostApp = hostApp,
                                    drafts = drafts,
                                    overlayScope = overlayScope,
                                    tabDragState = tabDragState,
                                    stripChrome = stripChrome,
                                    sidebarTopPad = sidebarTopPad,
                                    sidebarChrome = sidebarChrome,
                                    sidebarList = sidebarList,
                                    launcherPane = launcherPane,
                                    onNewSession = onNewSession,
                                    onTearOutTab = onTearOutTab,
                                    chatFallback = chatFallback,
                                    backProgress = backProgress,
                                )
                            }

                            // The launcher and the Usage card are ROUTES on both hosts (G8's
                            // fold), but on a wide host Home already draws them in place — the
                            // detail-pane swap and the footer-anchored popover. So the entry is
                            // deliberately EMPTY there: Home stays composed underneath and paints
                            // exactly what desktop always painted.
                            entry<Route.NewSession>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                if (compact) {
                                    launcherPane({ ui.goBack() }, { ui.selectSession(it) }, null)
                                }
                            }
                            entry<Route.Usage>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                if (!usageIsPopover) {
                                    HostScopedPage(hostViews, activeHostId, fleet::setActiveHost) {
                                        key(activeHostId) {
                                            UsageScreen(
                                                actions = usageActions,
                                                onBack = { ui.goBack() },
                                                standalone = true,
                                            )
                                        }
                                    }
                                }
                            }

                            entry<Route.AddHost>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("add_host_overlay", onEscape = { ui.goBack() }) {
                                    AddHostScreen(
                                        onBack = { ui.goBack() },
                                        defaultDeviceName = defaultDeviceName,
                                        onClaim = { payload, name -> fleet.addHost(payload, name) },
                                        onClaimLegacy = { pair -> fleet.addLegacyHost(pair) },
                                        onClaimByUrl = { url, name, allow -> fleet.addHostByUrl(url, name, allow) },
                                        onAdded = { onAddedHost(); ui.goBack() },
                                        needsInsecureOptIn = fleet::urlNeedsInsecureOptIn,
                                        pendingScans = LocalPlatform.current.pendingScans(),
                                    )
                                }
                            }

                            // A `Route.Settings` is the only route that mutates a field of
                            // itself in place: `ShellUiState.settingsSection` rewrites the stack
                            // slot with `Route.Settings(section)` on every rail click. The route
                            // IS NavDisplay's default content key, so without a stable one the
                            // whole hub is disposed and recomposed per click — scroll and
                            // selection lost on a wide host, and the outgoing hub's
                            // `DisposableEffect` free to run AFTER the incoming one registers,
                            // leaving `settingsTryClose` on the unguarded `onBack` so Escape skips
                            // the dirty-discard prompt. One key for the destination, not for its
                            // contents.
                            //
                            // The parameter is spelled `clazzContentKey` on the reified overload,
                            // which is why H4 recorded navigation3 1.1.1 as not exposing one.
                            entry<Route.Settings>(
                                clazzContentKey = { "settings" },
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) { route ->
                                EscapeBox(
                                    tag = when (route.section) {
                                        SettingsSection.EditorLsp -> "lsp_settings_overlay"
                                        SettingsSection.PersonalAssistants -> "personal_assistants_overlay"
                                        else -> "settings_overlay"
                                    },
                                    onEscape = { settingsTryClose?.invoke() ?: run { ui.goBack() } },
                                ) {
                                    HostScopedPage(hostViews, activeHostId, fleet::setActiveHost) {
                                        // Keyed on the HOST only: `route.section` here would
                                        // remount the hub on every section change — the same
                                        // mistake the `clazzContentKey` above exists to prevent
                                        // one level up (E1 review).
                                        key(activeHostId) {
                                            SettingsHub(
                                                section = route.section,
                                                onSectionChange = { ui.settingsSection = it },
                                                onBack = { ui.goBack() },
                                                onRegisterCloseHandler = { settingsTryClose = it },
                                                // Already inside `key(activeHostId)`, so the hub's
                                                // own host scoping has nothing left to reset.
                                                hostKey = null,
                                                extraContent = settingsExtra,
                                                content = settingsSection,
                                            )
                                        }
                                    }
                                }
                            }

                            entry<Route.Archived>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("archived_overlay", onEscape = { ui.goBack() }) {
                                    HostScopedPage(hostViews, activeHostId, fleet::setActiveHost) {
                                        key(activeHostId) {
                                            val archivedActions = rememberArchivedActions(fleet)
                                            ArchivedScreen(
                                                // Resuming CLOSES the overlay: the session is
                                                // coming back on a WS frame and the user's next
                                                // move is in the shell, not in the archive.
                                                actions = remember(archivedActions) {
                                                    ArchivedActions(
                                                        archivedWorkspaces = archivedActions.archivedWorkspaces,
                                                        liveWorkspaces = archivedActions.liveWorkspaces,
                                                        loadArchivedSessions = archivedActions.loadArchivedSessions,
                                                        loadLogs = archivedActions.loadLogs,
                                                        resume = { id ->
                                                            overlayScope.launch { archivedActions.resume(id) }
                                                            ui.goBack()
                                                        },
                                                        restoreWorkspace = archivedActions.restoreWorkspace,
                                                    )
                                                },
                                                home = home,
                                                onBack = { ui.goBack() },
                                                forceOpenId = ui.forceArchivedOpenFor,
                                                onForceOpenConsumed = { ui.forceArchivedOpenFor = null },
                                                // Every full-pane route paints its own title +
                                                // Back at every width (G5's `standalone` rule for
                                                // `Route.AppUpdate`, now applied to all of them):
                                                // Escape is not an affordance on a phone, and a
                                                // wide window with no visible way out was the G4
                                                // review's blocker.
                                                standalone = true,
                                            )
                                        }
                                    }
                                }
                            }

                            entry<Route.Displays>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("displays_overlay", onEscape = { ui.goBack() }) {
                                    HostScopedPage(hostViews, activeHostId, fleet::setActiveHost) {
                                        key(activeHostId) {
                                            DisplaysScreen(
                                                actions = rememberDisplayActions(fleet),
                                                onBack = { ui.goBack() },
                                                standalone = true,
                                            )
                                        }
                                    }
                                }
                            }

                            entry<Route.Devices>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("devices_overlay", onEscape = { ui.goBack() }) {
                                    HostScopedPage(hostViews, activeHostId, fleet::setActiveHost) {
                                        key(activeHostId) {
                                            DevicesSettingsScreen(
                                                actions = rememberDevicesSettingsActions(fleet),
                                                onBack = { ui.goBack() },
                                                standalone = true,
                                            )
                                        }
                                    }
                                }
                            }

                            entry<Route.Proxies>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("proxies_overlay", onEscape = { ui.goBack() }) {
                                    HostScopedPage(hostViews, activeHostId, fleet::setActiveHost) {
                                        key(activeHostId) {
                                            ProxiesSettingsScreen(
                                                actions = rememberProxiesSettingsActions(fleet),
                                                onBack = { ui.goBack() },
                                                standalone = true,
                                            )
                                        }
                                    }
                                }
                            }

                            entry<Route.Appearance>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("appearance_overlay", onEscape = { ui.goBack() }) {
                                    AppearanceSettingsScreen(onBack = { ui.goBack() }, standalone = true)
                                }
                            }

                            entry<Route.AppUpdate>(
                                metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                            ) {
                                EscapeBox("app_update_overlay", onEscape = { ui.goBack() }) {
                                    // `standalone`: a full-pane ROUTE, not a hub detail, so the
                                    // page paints its own title + Back at every width.
                                    AppUpdateScreen(onBack = { ui.goBack() }, standalone = true)
                                }
                            }
                        },
                    )

                    // ── Usage fallback when the sidebar is collapsed (no footer icon to anchor) ──
                    if (ui.usageOpen && usageIsPopover && ui.sidebarCollapsed) {
                        Box(Modifier.fillMaxSize().zIndex(20f)) {
                            Box(Modifier.align(Alignment.BottomStart).size(1.dp)) {
                                UsagePopover(
                                    expanded = true,
                                    onDismissRequest = { ui.closeUsage() },
                                    content = usagePopoverBody,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A full-pane overlay that closes on Escape, the way every sibling overlay does. */
@Composable
private fun EscapeBox(tag: String, onEscape: () -> Unit, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(tag)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    onEscape()
                    true
                } else {
                    false
                }
            },
    ) { content() }
}

/** A destination scoped to one host: its picker above, the page below. */
@Composable
private fun HostScopedPage(
    hosts: List<dev.supermux.host.HostView>,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HostScopePicker(hosts, selectedHostId, onSelectHost)
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * `Route.Home` — the sidebar + the open workspace on a wide host, the list⇄chat keep-alive stack
 * under Compact.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ShellHome(
    fleet: FleetStore,
    ui: ShellUiState,
    compact: Boolean,
    sessions: List<SessionInfo>,
    archivedSessions: List<dev.supermux.net.ArchivedDto>,
    workspaces: List<WorkspaceDto>,
    archivedWorkspaces: List<WorkspaceDto>,
    agentState: Map<String, dev.supermux.proto.AgentStatus>,
    lastBySession: Map<String, dev.supermux.proto.LogEntry?>,
    lastRead: Map<String, String>,
    selectedSession: SessionInfo?,
    activeWorkspace: WorkspaceDto?,
    workspaceLayerVisible: Boolean,
    appFor: (String) -> HostStore?,
    hostApp: HostStore?,
    drafts: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    overlayScope: kotlinx.coroutines.CoroutineScope,
    tabDragState: PaneDragController,
    stripChrome: PaneStripChrome,
    sidebarTopPad: Dp,
    sidebarChrome: @Composable BoxScope.(sidebarWidth: Dp, collapsed: Boolean) -> Unit,
    sidebarList: @Composable (standalone: Boolean, listState: LazyListState?) -> Unit,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit,
    onNewSession: () -> Unit,
    onTearOutTab: (String) -> Unit,
    chatFallback: (@Composable (session: SessionInfo, visible: Boolean, onBack: () -> Unit) -> Unit)?,
    backProgress: Float,
) {
    val cs = MaterialTheme.colorScheme
    val sessionNames = remember(sessions) { sessions.associate { it.id to it.name } }
    val liveWorkspaceIds = remember(workspaces) { workspaces.mapTo(linkedSetOf()) { it.id } }

    // The workspace layer — identical at every width except for the body each panel draws.
    val workspaceLayer: @Composable (showActive: Boolean) -> Unit = { showActive ->
        WorkspaceKeepAliveHost(
            activeWorkspaceId = activeWorkspace?.id,
            liveWorkspaceIds = liveWorkspaceIds,
            showActive = showActive,
            extraRetainIds = ui.windows.extraWorkspaceIds(),
        ) { workspaceId, isActive ->
            val current = workspaces.first { it.id == workspaceId }
            val workspaceSession = if (isActive) {
                selectedSession
            } else {
                current.primarySessionId?.let { sid -> sessions.firstOrNull { it.id == sid } }
                    ?: current.chatSessionIds().firstNotNullOfOrNull { sid ->
                        sessions.firstOrNull { it.id == sid }
                    }
            }
            val wsApp = appFor(current.primarySessionId ?: workspaceSession?.id ?: "")
            if (wsApp != null) {
                WorkspacePanel(
                    current = current,
                    workspaceSession = workspaceSession,
                    isActive = isActive,
                    compact = compact,
                    wsApp = wsApp,
                    appFor = { id -> appFor(id) ?: wsApp },
                    fleet = fleet,
                    ui = ui,
                    drafts = drafts,
                    overlayScope = overlayScope,
                    launcherPane = launcherPane,
                    tabDragState = tabDragState,
                    stripChrome = stripChrome,
                    sessionNames = sessionNames,
                    onTearOutTab = onTearOutTab,
                )
            }
        }
    }

    if (!compact) {
        Box(Modifier.fillMaxSize()) {
            // A tablet-class Android window is edge-to-edge under the status bar and the
            // navigation bar; the whole two-pane frame steps inside them here and CONSUMES them,
            // so a pane that pads for a bar on its own (the phone-layer screens do) does not pad
            // twice. Desktop insets are zero: this is the row it always was.
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .consumeWindowInsets(WindowInsets.systemBars),
            ) {
                // ── Sidebar: collapsed rail, or the full list ──
                val collapsed = ui.sidebarCollapsed
                var resizing by remember { mutableStateOf(false) }
                val animatedWidth by animateDpAsState(
                    targetValue = if (collapsed) 64.dp else ui.sidebarWidth,
                    animationSpec = if (resizing) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                    label = "sidebarWidth",
                )
                Box(
                    Modifier
                        .width(animatedWidth)
                        .fillMaxHeight()
                        .background(cs.surfaceContainerHigh)
                        .clipToBounds()
                        .then(if (sidebarTopPad > 0.dp) Modifier.padding(top = sidebarTopPad) else Modifier),
                ) {
                    if (collapsed) {
                        SessionsRail(
                            sessions = sessions,
                            selectedId = ui.selectedId,
                            agentState = agentState,
                            onSelect = { ui.selectSession(it) },
                            onExpand = { ui.sidebarCollapsed = false },
                            onNewSession = onNewSession,
                            lastBySession = lastBySession,
                            lastRead = lastRead,
                        )
                    } else {
                        // requiredWidth keeps the list at its full width while the narrower
                        // animating parent clips it during the reveal.
                        Box(Modifier.requiredWidth(ui.sidebarWidth).fillMaxHeight()) {
                            sidebarList(false, null)
                        }
                    }
                }

                // ── Detail: the launcher (a side panel — the sidebar stays mounted), the
                //    workspace, or an empty prompt ──
                Box(Modifier.weight(1f)) {
                    workspaceLayer(workspaceLayerVisible)
                    ShellDetailForeground(
                        ui = ui,
                        archivedWorkspaces = archivedWorkspaces,
                        selectedSession = selectedSession,
                        activeWorkspace = activeWorkspace,
                        launcherPane = launcherPane,
                        chatFallback = chatFallback,
                    )
                }
            }

            // Resize OVERLAY on the sidebar seam (not a Row child — zero layout width).
            if (ui.sidebarCollapsed) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 64.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(cs.outlineVariant),
                )
            } else {
                SidebarDivider(
                    onDragDelta = { d -> ui.setSidebarWidth(ui.sidebarWidth + d) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = ui.sidebarWidth - SidebarDividerCenterOffset)
                        .zIndex(20f),
                )
            }
            sidebarChrome(ui.sidebarWidth, ui.sidebarCollapsed)
        }
        return
    }

    // ── Compact: one full-bleed layer per surface. The workspace / chat layers stay composed
    //    (a WebView, a PTY and a transcript are expensive to rebuild) and the session list
    //    slides over them.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
    val visitedSessions = rememberVisitedSessions(ui.selectedId, remember(sessions) { sessions.map { it.id }.toSet() })
    val workspaceBySession = remember(workspaces) {
        workspaces.flatMap { ws -> ws.views.mapNotNull { v -> v.chatSessionId()?.let { it to ws } } }.toMap()
    }
    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.graphicsLayer {
                    val scale = 1f - backProgress * 0.05f
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - backProgress * 0.3f
                },
            ) {
                workspaceLayer(true)
                if (chatFallback != null) {
                    visitedSessions.forEach { sessionId ->
                        if (workspaceBySession[sessionId] != null) return@forEach
                        val session = sessions.firstOrNull { it.id == sessionId }
                            ?: archivedSessions.firstOrNull { it.id == sessionId }?.asSettledSession()
                            ?: return@forEach
                        val visible = sessionId == ui.selectedId && activeWorkspace == null
                        key(sessionId) {
                            Box(Modifier.keepAlivePanel(visible)) {
                                chatFallback(session, visible) { ui.selectedId = null }
                            }
                        }
                    }
                }
                // A session is selected that resolves to NOTHING — no live row, no archived row,
                // no workspace. Outside the `chatFallback` guard on purpose: a host without a
                // session-only chat (iOS) reaches this for ANY workspace-less id, and every host
                // reaches it for an id whose session has gone.
                //
                // It is reachable, and it used to be a trap. Tapping a notification for a chat
                // that has since been killed — or any notification while its host is offline, so
                // the session list is empty — selects the id (`pushTapHandleDecision` returns
                // ApplyRetry on empty workspaces DELIBERATELY, so a cold start can open the chat
                // before the list arrives). The screen then drew nothing at all: no header, no
                // back affordance. On iOS that is unrecoverable, because the interactive
                // edge-swipe back is inert while the navigation stack is one deep, and the app has
                // to be force-quit. So the shell draws its own way out.
                if (ui.selectedId != null && selectedSession == null && activeWorkspace == null) {
                    UnavailableSessionPane { ui.selectedId = null }
                }
            }

            AnimatedContent(
                targetState = ui.selectedId == null,
                transitionSpec = {
                    val showList = targetState
                    val enter = slideInHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        initialOffsetX = { if (showList) -it / 3 else it },
                    ) + fadeIn(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                    val exit = slideOutHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        targetOffsetX = { if (showList) it else -it / 3 },
                    ) + fadeOut(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                    enter togetherWith exit
                },
                label = "sessionListOverlay",
                modifier = Modifier.zIndex(2f),
            ) { showList ->
                if (showList) sidebarList(true, listState)
            }
        }
    }
}

/** The foreground surfaces of a wide detail pane, above the (still composed) workspace layer. */
@Composable
private fun ShellDetailForeground(
    ui: ShellUiState,
    archivedWorkspaces: List<WorkspaceDto>,
    selectedSession: SessionInfo?,
    activeWorkspace: WorkspaceDto?,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit,
    chatFallback: (@Composable (session: SessionInfo, visible: Boolean, onBack: () -> Unit) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    when {
        ui.selectedArchivedWorkspaceId != null -> {
            val archived = archivedWorkspaces.firstOrNull { it.id == ui.selectedArchivedWorkspaceId }
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(cs.surfaceContainerLow)
                    .testTag("archived_workspace_detail"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (archived != null) {
                        "“${archived.name}” is archived. Restore it from the menu to continue."
                    } else {
                        "This workspace is archived. Restore it from the menu to continue."
                    },
                    color = cs.onSurfaceVariant,
                )
            }
        }
        ui.launcherOpen -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .testTag("launcher_overlay")
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            ui.closeLauncher()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                launcherPane({ ui.closeLauncher() }, { ui.selectSession(it) }, null)
            }
        }
        selectedSession == null -> {
            Box(
                Modifier.fillMaxSize().zIndex(2f).background(cs.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) { Text("select a session", color = cs.onSurfaceVariant) }
        }
        activeWorkspace == null -> {
            // The session belongs to no workspace (an old broker, or a settled row). A host with a
            // session-only chat draws it; desktop keeps the prompt it always showed.
            if (chatFallback != null) {
                Box(Modifier.fillMaxSize().zIndex(2f)) {
                    chatFallback(selectedSession, true) { ui.selectedId = null }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                        .background(cs.surfaceContainerLow)
                        .testTag("workspace_welcome"),
                    contentAlignment = Alignment.Center,
                ) { Text("select a workspace", color = cs.onSurfaceVariant) }
            }
        }
        else -> Unit
    }
}

/** One retained workspace: its session, its effects, and the pane body for this width. */
@Composable
private fun WorkspacePanel(
    current: WorkspaceDto,
    workspaceSession: SessionInfo?,
    isActive: Boolean,
    compact: Boolean,
    wsApp: HostStore,
    appFor: (String) -> HostStore,
    fleet: FleetStore,
    ui: ShellUiState,
    drafts: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    overlayScope: kotlinx.coroutines.CoroutineScope,
    launcherPane: @Composable (
        onBack: () -> Unit,
        onCreated: (String) -> Unit,
        /** The pending "+ → Chat" tab hosting the launcher (null outside a workspace tab). */
        tab: LauncherTab?,
    ) -> Unit,
    tabDragState: PaneDragController,
    stripChrome: PaneStripChrome,
    sessionNames: Map<String, String>,
    onTearOutTab: (String) -> Unit,
) {
    // Set by PaneHost onCloseView; never ends work by itself.
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }
    val ws = rememberHostWorkspaceSession(
        current = current,
        wsApp = wsApp,
        overlayScope = overlayScope,
        // The phone never PATCHes a layout — its tabs follow broker membership (D2/D3). Unless
        // another window shows part of this workspace (the phone layout in split screen
        // beside its own extra window): the tree is then what divides the views between the
        // windows, and a tear-out's split that is never written is undone by the next frame.
        writesLayout = !compact || current.id in ui.windows.extraWorkspaceIds(),
        // A phone has no groups to place a pane into: make the new file the active tab.
        activateOpenedFile = compact,
    )
    val layoutSync = ws.layoutSync
    val localLayout = layoutSync.tree

    val panesBind = ui.panesBindFor(current.id) ?: WorkspacePanesBind(
        current, workspaceSession, ws, wsApp, appFor, drafts, overlayScope, launcherPane,
    )
    panesBind.current = current
    panesBind.session = workspaceSession
    panesBind.ws = ws
    panesBind.app = wsApp
    panesBind.appFor = appFor
    panesBind.drafts = drafts
    panesBind.overlayScope = overlayScope
    panesBind.launcherPane = launcherPane
    ui.panesBinds[current.id] = panesBind
    androidx.compose.runtime.DisposableEffect(panesBind) {
        panesBind.holders++
        onDispose { panesBind.holders-- }
    }
    if (isActive) ui.windows.setWorkspaceOnMain(current.id)
    LaunchedEffect(current.id, localLayout) { ui.windows.onWorkspaceTree(current.id, localLayout) }

    val lspSession = ws.let { current.primarySessionId }
    val hasEditorView = ws.viewsById.values.any { it.kind == "editor" }
    androidx.compose.runtime.DisposableEffect(lspSession, hasEditorView) {
        if (lspSession != null && hasEditorView) fleet.editorOpen(lspSession)
        onDispose { if (lspSession != null && hasEditorView) fleet.editorClose(lspSession) }
    }
    LaunchedEffect(ws.documents, lspSession) {
        val sid = lspSession ?: return@LaunchedEffect
        wsApp.fsChanges.collect { f -> if (f.session == sid) ws.documents.markChanged(f.paths) }
    }
    LaunchedEffect(ui.externalOpen, current.id, isActive) {
        if (!isActive) return@LaunchedEffect
        val req = ui.externalOpen ?: return@LaunchedEffect
        val rel = workspaceOpenPath(req.second, current.workdir)
        if (rel == null) {
            println("[SupermuxApp] externalOpen: '${req.second.path}' is outside '${current.workdir}' — dropped")
        } else {
            ws.fileOpener.open(rel, req.second.line, req.second.endLine, sourceViewId = null)
        }
        ui.externalOpen = null
    }
    LaunchedEffect(ui.forceWorkspaceView, current.id, isActive) {
        if (!isActive) return@LaunchedEffect
        val req = ui.forceWorkspaceView ?: return@LaunchedEffect
        val gid = firstGroupId(localLayout)
        if (gid == null) {
            println("[SupermuxApp] forceWorkspaceView: no group to open '${req.first}' into")
        } else {
            runCatching {
                wsApp.api.addView(current.id, AddViewBody(kind = req.first, state = req.second, groupId = gid))
            }.onFailure { println("[SupermuxApp] forceWorkspaceView failed: $it") }
        }
        ui.forceWorkspaceView = null
    }

    if (compact) {
        PhoneWorkspacePanes(
            current = current,
            session = workspaceSession,
            ws = ws,
            app = wsApp,
            appFor = appFor,
            ui = ui,
            drafts = drafts,
            shell = rememberShellActions(wsApp, appFor),
            launcherPane = launcherPane,
            sessionNames = sessionNames,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // `tablet_pane_host` is Android's name for "the TREE renderer is mounted, not the phone tab
    // strip"; `workspace_layout_host` (on the `PaneHost` itself, below) is desktop's name for the
    // tree. Both survive the merge because they sit on different nodes — Compose's `TestTag` merge
    // policy keeps the outermost value, so stacking them on one node would silently drop one.
    Column(Modifier.fillMaxSize().testTag("tablet_pane_host")) {
        val hostedLayout = ui.windows.layoutFor(ui.windows.mainHostId, localLayout)
        WorkspacePanes(
            hostId = ui.windows.mainHostId,
            layout = hostedLayout,
            current = current,
            session = workspaceSession,
            ws = ws,
            app = wsApp,
            appFor = appFor,
            ui = ui,
            drafts = drafts,
            overlayScope = overlayScope,
            launcherPane = launcherPane,
            tabDragState = tabDragState,
            closeCandidate = closeCandidate,
            onCloseCandidate = { closeCandidate = it },
            sessionNames = sessionNames,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("workspace_layout_host"),
            onTearOutTab = onTearOutTab,
            stripChrome = stripChrome,
        )
    }
}

/** Client-minted view / group ids (the workspace's optimistic rows). */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun randomViewId(): String = kotlin.uuid.Uuid.random().toString()

/**
 * Tracks session ids the user has opened; pruned when the broker removes a live session.
 *
 * The currently [selected] id is always retained even when it is not in [liveSessionIds]
 * (archived/settled rows opened from the task list) — otherwise the list slides away and no chat
 * layer is composed, which was a solid black screen.
 */
@Composable
fun rememberVisitedSessions(selected: String?, liveSessionIds: Set<String>): Set<String> {
    var visited by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(selected) { selected?.let { id -> visited = visited + id } }
    LaunchedEffect(liveSessionIds, selected) {
        val kept = visited.intersect(liveSessionIds)
        visited = if (selected != null) kept + selected else kept
    }
    return visited
}

/**
 * What the shell shows for a selected session that does not exist.
 *
 * Deliberately the SHELL's own screen and not a host's: it is the last thing standing between the
 * user and a dead end, so it must not depend on a host having supplied a `chatFallback`. The back
 * arrow is the whole point — the message alone would still be a screen with no way off it.
 */
@Composable
private fun UnavailableSessionPane(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(2f)
            .background(cs.surfaceContainerLow)
            .testTag("session_unavailable"),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("session_unavailable_back")) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This session is no longer available", color = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * One window's live session over workspace [current] of host [wsApp]: its layout sync, open
 * documents and file opener. Every window that draws the workspace holds its own, exactly as two
 * devices showing it do — the broker's frames keep them in step — so a window never depends on
 * another window's composition to stay current.
 *
 * [writesLayout] false never PATCHes the tree (the phone layout, whose tabs follow broker
 * membership). [activateOpenedFile] makes a newly opened file the workspace's active tab.
 */
@Composable
internal fun rememberHostWorkspaceSession(
    current: WorkspaceDto,
    wsApp: HostStore,
    overlayScope: kotlinx.coroutines.CoroutineScope,
    writesLayout: Boolean,
    activateOpenedFile: Boolean,
): WorkspaceSession = rememberWorkspaceSession(
    workspace = current,
    overlayScope = overlayScope,
    patchLayout = workspaceLayoutPatch(
        compact = !writesLayout,
        onPatch = { tree ->
            wsApp.api.patchWorkspace(current.id, PatchWorkspaceBody(layout = tree.toDto()))
        },
        onSkip = { },
    ),
    fsRead = { p -> wsApp.workspaceFsRead(current.id, p) },
    fsWrite = { p, content -> wsApp.workspaceFsWrite(current.id, p, content) },
    postView = { id, state, groupId ->
        val created = runCatching {
            wsApp.api.addView(
                current.id,
                AddViewBody(kind = "editor", state = state, id = id, groupId = groupId),
            )
        }.onFailure { println("[SupermuxApp] open file view failed: $it") }.getOrNull()?.id
        if (created != null && activateOpenedFile) wsApp.setActiveView(current.id, created)
        created
    },
    newId = { randomViewId() },
)
