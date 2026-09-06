// The one session/workspace list both apps render (cluster F4).
//
// Base: desktop's `shell/WorkspaceListPanel.kt` — workspace-first grouping (`groupWorkspaces`),
// the shared reorder, collapsed project groups through `UiPrefs`, per-group archived folds, the
// sidebar footer and the cross-panel tab-drop targets. Android's `session/SessionListScreen.kt` is
// the Compact/Touch branch: the `Scaffold` + `CenterAlignedTopAppBar` + FAB chrome, swipe rows,
// the overflow nav, host rename/forget on the filter chips, the session/task fallback list for
// hosts that report no workspaces, offline-host groups, and one archived fold at the tail.
//
// Two gates, and only two:
//   - CHROME  `(standalone || compact) && !topBarShown` — cluster E's rule. On it the screen paints
//     Android's Scaffold (top bar + FAB, host chips / new-session card / group-by switch as list
//     items); off it, desktop's Column (new-session card, chips, section header, list, footer slot).
//     Nothing else keys on the platform.
//   - INPUT   `LocalInputMode` — inside the rows (see `WorkspaceRow` / `SessionRow`): swipe and
//     48dp targets under Touch, hover + right-click under Pointer.
//
// The screen owns NO navigation of its own: it registers no `BackHandler` (the list is the phone's
// back destination, not a back consumer) and Android's shared-element scopes stay at the
// `MainActivity` mount — this composable takes a plain [Modifier] and nothing else from them.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.host.filterSessions
import dev.supermux.host.formatLastSeen
import kotlin.time.Clock
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.SectionKey
import dev.supermux.session.buildTaskSections
import dev.supermux.session.combinedTaskSessions
import dev.supermux.session.groupSessions
import dev.supermux.session.inferHomeDir
import dev.supermux.session.projectLabel
import dev.supermux.session.sectionKey
import dev.supermux.session.sessionsByUserOrder
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.host.HostDot
import dev.supermux.ui.host.HostFilterChips
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.mux_logo
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.theme.softElevation
import dev.supermux.ui.usage.UsagePopover
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.workspace.WORKSPACE_FLAT_SCOPE
import dev.supermux.workspace.WorkspaceDragWorkingState
import dev.supermux.workspace.WorkspaceReorderScope
import dev.supermux.workspace.applyWorkspaceWorkingOrder
import dev.supermux.workspace.chatSessionIds
import dev.supermux.workspace.groupArchivedWorkspaces
import dev.supermux.workspace.groupWorkspaces
import dev.supermux.workspace.moveWorkspaceWithinScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Which of the two lists a mount is.
 *
 * The difference is not cosmetic, so it is a parameter rather than a platform check:
 *  - [Workspaces] — desktop's sidebar. Workspaces only: an empty host shows the "No workspaces yet"
 *    hint (never a session list), draft sessions get their own rows, and archived workspaces fold
 *    under each project group (plus one flat fold in flat mode).
 *  - [Fleet] — Android's list. Falls back to the session/task sections when the host reports no
 *    workspaces, groups an offline host's cached sessions under its own header, and folds every
 *    archived workspace once at the tail.
 */
enum class SessionListMode { Workspaces, Fleet }

/**
 * The session list.
 *
 * @param mode see [SessionListMode].
 * @param activeId the open row. Keyed by WORKSPACE id when [openWorkspaceByWorkspaceId] (desktop's
 *   sidebar), otherwise by the resolved chat SESSION id (Android).
 * @param standalone true where this screen IS the window/route rather than a pane inside one —
 *   with `compact` it drives the top bar + FAB (cluster E's chrome rule).
 * @param topBarShown true when the caller already painted a top bar for this screen.
 * @param footer desktop's sidebar footer rail (theme / usage / devices / settings). Rendered only
 *   when the screen paints its own (non-Compact) chrome; see [SessionListFooter].
 * @param onNavigate the overflow destinations (`archived`, `usage`, `proxies`, `appearance`,
 *   `settings`, `devices`, `addhost`). Null on a host with no router — then no overflow is drawn.
 * @param tabDragState desktop's cross-panel tab drag; each workspace row registers as a drop target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    mode: SessionListMode = SessionListMode.Workspaces,
    workspaces: List<WorkspaceDto> = emptyList(),
    /** Live sessions — resolve names/roles/mute/git and feed the Draft/Settled chrome. */
    sessions: List<SessionInfo> = emptyList(),
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    /** Bare sessionId → ISO last_read_at (server + optimistic local marks). */
    lastRead: Map<String, String> = emptyMap(),
    agentState: Map<String, AgentStatus?> = emptyMap(),
    /** session id → display name for multi-agent child rows; defaults from [sessions]. */
    sessionNames: Map<String, String> = emptyMap(),
    /** session id → role; a `personal_assistant` primary pins its workspace. Defaults from [sessions]. */
    sessionRoles: Map<String, String?> = emptyMap(),
    /** Archived sessions folded into Settled (web task-list parity) — [SessionListMode.Fleet]. */
    archived: List<ArchivedDto> = emptyList(),
    archivedWorkspaces: List<WorkspaceDto> = emptyList(),
    archivedActiveId: String? = null,
    /** Every broker call this screen makes (cluster F1). */
    actions: SessionListActions = SessionListActions(),
    onOpenSession: (workspaceId: String, sessionId: String) -> Unit = { _, _ -> },
    onNewSession: () -> Unit = {},
    onNewChatInWorkspace: (WorkspaceDto) -> Unit = {},
    onOpenDraft: (String) -> Unit = {},
    onSelectArchived: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    /** Fires after a row's kill lands — a phone host prunes its keep-alive layer here. */
    onKilled: (String) -> Unit = {},
    onNavigate: ((String) -> Unit)? = null,
    // ── Multi-host (spec §5); all default-empty so single-host callers render as before ──
    hosts: List<HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
    // ── Prefs (cluster F1: read synchronously by the host before the first frame) ──
    initialCollapsedPaths: Set<String> = emptySet(),
    onCollapsedPathsChange: (Set<String>) -> Unit = {},
    initialGroupByProject: Boolean = true,
    onGroupByProjectChange: (Boolean) -> Unit = {},
    // ── Mount shape ──
    openWorkspaceByWorkspaceId: Boolean = true,
    standalone: Boolean = false,
    topBarShown: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    tabDragState: PaneDragController? = null,
    /**
     * Optional scroll state. The phone disposes this screen while a chat is open, so its host
     * hoists the [LazyListState] to restore the offset on back.
     */
    listState: LazyListState = rememberLazyListState(),
) {
    val cs = MaterialTheme.colorScheme
    val touch = LocalInputMode.current == InputMode.Touch
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // Cluster E's chrome rule, unchanged: paint the bar/FAB only where this screen is the whole
    // surface and nobody above it already painted one.
    val chrome = (standalone || compact) && !topBarShown
    val haptic = rememberHaptics()
    val listScope = rememberCoroutineScope()

    // ── Multi-host derivations ────────────────────────────────────────────────────────────────
    val multiHost = hosts.size > 1
    // With a specific host pill selected every row is already that host — hide the redundant badge.
    val showRowHostBadge = multiHost && hostFilter == null
    val hostByRecord = remember(hosts) { hosts.associateBy { it.recordId } }
    val offlineIds = remember(hosts) { hosts.filter { !it.online }.map { it.recordId }.toSet() }

    val names = remember(sessions, sessionNames) {
        if (sessionNames.isNotEmpty()) sessionNames else sessions.associate { it.id to it.name }
    }
    val roles = remember(sessions, sessionRoles) {
        if (sessionRoles.isNotEmpty()) sessionRoles else sessions.associate { it.id to it.role }
    }
    val sessionById = remember(sessions) { sessions.associateBy { it.id } }
    val agentTyped = remember(agentState) {
        agentState.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    // The host filter applies to sessions first; a workspace stays visible while any of its chat
    // sessions (or its primary) still passes.
    val visibleSessions = if (multiHost) filterSessions(sessions, sessionHost, hostFilter) else sessions
    val onlineSessions =
        if (multiHost) visibleSessions.filter { (sessionHost[it.id] ?: "") !in offlineIds } else visibleSessions
    val visibleSessionIds = remember(visibleSessions) { visibleSessions.map { it.id }.toHashSet() }
    val visibleWorkspaces = remember(workspaces, multiHost, hostFilter, sessionHost, visibleSessionIds) {
        if (!multiHost || hostFilter == null) workspaces
        else workspaces.filter { w ->
            val ids = w.chatSessionIds()
            val primary = w.primarySessionId
            when {
                ids.isEmpty() && primary == null -> true
                primary != null && primary in visibleSessionIds -> true
                ids.any { it in visibleSessionIds } -> true
                else -> false
            }
        }
    }

    val effectiveHome = inferHomeDir(visibleWorkspaces.firstOrNull()?.workdir)
        ?: inferHomeDir(visibleSessions.firstOrNull()?.workdir)
        ?: home

    val groups = remember(visibleWorkspaces, effectiveHome, roles) {
        groupWorkspaces(visibleWorkspaces, effectiveHome) { w ->
            val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
            sid != null && roles[sid] == "personal_assistant"
        }
    }
    val archivedGroups = remember(archivedWorkspaces, effectiveHome) {
        groupArchivedWorkspaces(archivedWorkspaces, effectiveHome)
    }
    val archivedByPath = remember(archivedGroups) { archivedGroups.associate { it.key to it.workspaces } }

    // ── Session/task fallback (Fleet mode with no workspaces) ─────────────────────────────────
    val useWorkspaces = mode == SessionListMode.Workspaces || workspaces.isNotEmpty()
    val lastTs: (SessionInfo) -> String = { lastBySession[it.id]?.ts ?: "" }
    val sessionGroups = remember(onlineSessions, effectiveHome, lastBySession, archived) {
        groupSessions(onlineSessions, effectiveHome, lastTs, archived = archived)
    }
    val baseFlatSections = remember(onlineSessions, lastBySession, archived) {
        buildTaskSections(combinedTaskSessions(onlineSessions, archived), lastTs)
    }
    val reorderRows = remember(baseFlatSections) { baseFlatSections.flatMap { it.sessions } }
    val workingOrders = remember { mutableStateMapOf<SessionReorderScope, List<String>>() }
    // Draft sessions still live on the session model (workspaces don't draft yet).
    val draftSessions = remember(visibleSessions) {
        sessionsByUserOrder(visibleSessions.filter { it.sectionKey() == SectionKey.DRAFT })
    }
    // Offline hosts (greyed groups with last-seen), honouring the filter.
    val offlineGroups = if (multiHost && mode == SessionListMode.Fleet) {
        hosts.filter { !it.online && (hostFilter == null || hostFilter == it.recordId) }
            .map { h -> h to visibleSessions.filter { sessionHost[it.id] == h.recordId } }
    } else {
        emptyList()
    }

    // ── Screen state ──────────────────────────────────────────────────────────────────────────
    var groupByProject by remember { mutableStateOf(initialGroupByProject) }
    // Collapsed groups live in the shared settings store; the host reads them synchronously and
    // re-supplies them, so a REMOUNT (phone list ↔ chat, or a host switch) adopts the current set
    // instead of resetting to whatever this composition first saw.
    var collapsedPaths by remember { mutableStateOf(initialCollapsedPaths) }
    // Adopt only when the PARAMETER itself changed since we last looked — not whenever it differs
    // from the local set, which it always does right after our own toggle (the host's value only
    // comes back on its next composition, and some hosts never feed it back at all).
    var lastSuppliedPaths by remember { mutableStateOf(initialCollapsedPaths) }
    if (lastSuppliedPaths != initialCollapsedPaths) {
        lastSuppliedPaths = initialCollapsedPaths
        collapsedPaths = initialCollapsedPaths
    }
    fun setCollapsedPaths(next: Set<String>) {
        collapsedPaths = next
        onCollapsedPathsChange(next)
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameWorkspaceTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var archiveWorkspaceTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    var openSwipeRowId by remember { mutableStateOf<String?>(null) }
    var expandedChildren by remember { mutableStateOf(setOf<String>()) }
    var settledExpanded by remember { mutableStateOf(setOf<String>()) }
    var flatSettledExpanded by remember { mutableStateOf(false) }
    var archivedExpanded by remember { mutableStateOf(setOf<String>()) }
    var flatArchivedExpanded by remember { mutableStateOf(false) }
    var archivedFoldOpen by remember { mutableStateOf(false) }

    val wsWorkingOrders = remember { mutableStateMapOf<String, List<String>>() }
    val wsDragState = remember { WorkspaceDragWorkingState() }
    val dragWorkingState = remember { SessionDragWorkingState() }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) openSwipeRowId = null
        }
    }
    LaunchedEffect(groupByProject) { openSwipeRowId = null }

    // ── Broker calls (cluster F1 holder) ──────────────────────────────────────────────────────
    val onRename: (String, String) -> Unit = actions.rename
    val onKill: (String) -> Unit = { id -> actions.kill(id) { onKilled(id) } }
    val onMute: (String, Boolean) -> Unit = actions.setMute
    val onResume: (String) -> Unit = { id -> listScope.launch { actions.resume(id) } }

    // ── Reorder scopes ────────────────────────────────────────────────────────────────────────
    val wsFlatRows = remember(groups) {
        groups.filter { it.key != PA_GROUP_KEY }
            .flatMap { it.workspaces }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }
    val wsFlatIds = remember(wsFlatRows) { wsFlatRows.map { it.id }.toSet() }

    fun wsRowsForScope(scopeKey: String): List<WorkspaceDto> = when (scopeKey) {
        WORKSPACE_FLAT_SCOPE -> wsFlatRows
        else -> groups.firstOrNull { it.key == scopeKey }?.workspaces.orEmpty()
    }

    fun wsScopeOf(workspaceId: String): String? {
        if (!groupByProject) {
            return if (workspaceId in wsFlatIds) WORKSPACE_FLAT_SCOPE else null
        }
        return groups.firstOrNull { g ->
            g.key != PA_GROUP_KEY && g.workspaces.any { it.id == workspaceId }
        }?.key
    }

    fun finishDrag() {
        if (useWorkspaces) {
            wsDragState.finish(commit = true)?.let { move ->
                onReorder(move.orderedIds)
                // Drop the gesture overlay so a later server snapshot (or a failed PATCH rollback)
                // can win.
                wsWorkingOrders.remove(move.scope.key)
            }
            return
        }
        dragWorkingState.finish(commit = true)?.let { move ->
            onReorder(move.orderedIds)
            workingOrders.remove(move.scope)
        }
    }

    fun beginWorkspaceDrag(w: WorkspaceDto) {
        val scopeKey = wsScopeOf(w.id) ?: return
        val orderedIds = wsWorkingOrders[scopeKey] ?: wsRowsForScope(scopeKey).map { it.id }
        wsDragState.begin(WorkspaceReorderScope(scopeKey), orderedIds)
        openSwipeRowId = null
        haptic.perform(HapticKind.Tick)
    }

    fun beginSessionDrag(session: SessionInfo) {
        val scope = reorderScope(session, projectScoped = groupByProject)
        val orderedIds = workingOrders[scope]
            ?: reorderRows
                .filter { reorderScope(it, projectScoped = groupByProject) == scope }
                .map { it.id }
        dragWorkingState.begin(scope, orderedIds)
        openSwipeRowId = null
        haptic.perform(HapticKind.Tick)
    }

    // Shared reorder (ui/session/DragReorder.kt) — elevates the item, auto-scrolls, animates
    // neighbours. The lift is press+slop under Pointer and a long press under Touch.
    val reorderableState = rememberReorderableListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableListState false
        val toKey = to.key as? String ?: return@rememberReorderableListState false
        if (useWorkspaces) {
            if (!fromKey.startsWith("ws:") || !toKey.startsWith("ws:")) {
                return@rememberReorderableListState false
            }
            val fromId = fromKey.removePrefix("ws:")
            val toId = toKey.removePrefix("ws:")
            val scopeKey = wsScopeOf(fromId) ?: return@rememberReorderableListState false
            if (wsScopeOf(toId) != scopeKey) return@rememberReorderableListState false
            val rows = wsRowsForScope(scopeKey)
            val move = moveWorkspaceWithinScope(
                rows = rows,
                workingOrders = wsWorkingOrders,
                scopeKey = scopeKey,
                fromId = fromId,
                toId = toId,
            ) ?: return@rememberReorderableListState false
            val originalIds = wsWorkingOrders[scopeKey] ?: rows.map { it.id }
            wsDragState.beginIfIdle(move.scope, originalIds)
            // The mutation must land before onMove returns so neighbours can animate.
            wsWorkingOrders[scopeKey] = move.orderedIds
            wsDragState.move(move.orderedIds)
            return@rememberReorderableListState true
        }
        if (!fromKey.startsWith("task:") || !toKey.startsWith("task:")) {
            return@rememberReorderableListState false
        }
        val move = moveWithinScope(
            rows = reorderRows,
            workingOrders = workingOrders,
            fromId = fromKey.removePrefix("task:"),
            toId = toKey.removePrefix("task:"),
            projectScoped = groupByProject,
        ) ?: return@rememberReorderableListState false
        val originalIds = workingOrders[move.scope]
            ?: reorderRows
                .filter { reorderScope(it, projectScoped = groupByProject) == move.scope }
                .map { it.id }
        dragWorkingState.beginIfIdle(move.scope, originalIds)
        workingOrders[move.scope] = move.orderedIds
        dragWorkingState.move(move.orderedIds)
        true
    }
    LaunchedEffect(reorderableState) {
        var wasDragging = reorderableState.isAnyItemDragging
        snapshotFlow { reorderableState.isAnyItemDragging }.collect { dragging ->
            if (wasDragging && !dragging) finishDrag()
            wasDragging = dragging
        }
    }

    // ── Row callbacks ─────────────────────────────────────────────────────────────────────────
    val onOpenSwipeRowChange: (String?) -> Unit = remember { { openSwipeRowId = it } }
    val onToggleChildren: (String) -> Unit = remember {
        { id -> expandedChildren = if (id in expandedChildren) expandedChildren - id else expandedChildren + id }
    }

    fun openSession(s: SessionInfo) {
        when (s.sectionKey()) {
            SectionKey.DRAFT -> onOpenDraft(s.id)
            else -> onOpen(s.id)
        }
    }

    fun hostOf(model: WorkspaceRowModel): HostView? {
        if (!showRowHostBadge) return null
        val sid = if (openWorkspaceByWorkspaceId) model.primarySessionId else model.openSessionId
        return sid?.let { hostByRecord[sessionHost[it]] }
    }

    // ── List body ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun ArchivedEntry(w: WorkspaceDto) {
        ArchivedWorkspaceRow(
            model = deriveArchivedWorkspaceRow(w, effectiveHome),
            active = mode == SessionListMode.Workspaces && w.id == archivedActiveId,
            // Desktop selects the archived workspace into its own read-only overlay; Android
            // opens its transcript through the resolved chat session.
            onSelect = {
                if (mode == SessionListMode.Workspaces) {
                    onSelectArchived(w.id)
                } else {
                    resolveWorkspaceOpenSessionId(w)?.let(onOpen)
                }
            },
            onRestore = { actions.restoreWorkspace(w.id) },
        )
    }

    @Composable
    fun LazyItemScope.WorkspaceEntry(
        w: WorkspaceDto,
        scopeKey: String?,
        grouped: Boolean,
        first: Boolean,
        last: Boolean,
        draggable: Boolean,
        showProjectTag: Boolean,
    ) {
        val model = remember(w, sessionById, agentTyped, lastBySession, lastRead, effectiveHome, activeId) {
            deriveWorkspaceRow(
                w = w,
                sessionsById = sessionById,
                agentState = agentTyped,
                lastBySession = lastBySession,
                lastRead = lastRead,
                home = effectiveHome,
                selectedSessionId = activeId,
            )
        }
        val primary = model.primarySessionId?.let { sessionById[it] }
        val openSid = model.openSessionId
        val isActive =
            if (openWorkspaceByWorkspaceId) w.id == activeId else openSid != null && openSid == activeId
        val rowInteraction = remember { MutableInteractionSource() }
        val body: @Composable (Boolean, Modifier) -> Unit = { isDragging, dragModifier ->
            Column(Modifier.fillMaxWidth()) {
                WorkspaceRow(
                    model = model,
                    active = isActive,
                    // Pointer-branch inputs (desktop's sidebar, and Android in a keyboard case).
                    preview = model.primarySessionId?.let { lastBySession[it] },
                    lastReadAt = model.primarySessionId?.let { lastRead[it] },
                    sessionStatus = primary?.status,
                    projectTag = if (!showProjectTag) {
                        null
                    } else {
                        projectLabel(
                            primary ?: SessionInfo(
                                id = w.id, name = w.name, workdir = w.workdir, agent = "claude",
                                repo_root = w.repoRoot,
                            ),
                            effectiveHome,
                        )
                    },
                    dropHover = tabDragState?.hoverWorkspaceId == w.id,
                    onRowBounds = tabDragState?.let { d -> { bounds -> d.registerWorkspace(w.id, bounds) } },
                    host = hostOf(model),
                    mute = primary?.mute == true,
                    dragModifier = dragModifier,
                    interactionSource = rowInteraction,
                    isDragging = isDragging,
                    // Touch-branch inputs (ignored by the Pointer row).
                    openSwipeRowId = openSwipeRowId,
                    onOpenSwipeRowChange = onOpenSwipeRowChange,
                    rowShape = if (grouped) groupedRowShape(first, last) else RoundedCornerShape(Radii.md),
                    outerPadding = if (grouped) {
                        PaddingValues(horizontal = 12.dp)
                    } else {
                        PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    },
                    rowColor = if (grouped) {
                        if (isActive) cs.surfaceContainer else cs.surfaceContainerLow
                    } else {
                        null
                    },
                    childrenExpanded = w.id in expandedChildren,
                    onToggleChildren = { onToggleChildren(w.id) },
                    onClick = {
                        if (openWorkspaceByWorkspaceId) onOpen(w.id) else openSid?.let(onOpen)
                    },
                    onRename = { renameWorkspaceTarget = w; renameText = w.name },
                    onNewChat = { onNewChatInWorkspace(w) },
                    onKill = { archiveWorkspaceTarget = w },
                    onToggleMute = {
                        val sid = model.primarySessionId ?: return@WorkspaceRow
                        onMute(sid, !(primary?.mute ?: false))
                    },
                    onChildClick = { sid -> onOpenSession(w.id, sid) },
                )
                // The Pointer row has no child fold — desktop's sidebar lists a multi-agent
                // workspace's chats inline underneath it (the Touch row expands its own).
                if (!touch && model.multiAgent) {
                    Column(
                        Modifier
                            .testTag(WorkspaceListTestIds.children(w.id))
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 8.dp),
                    ) {
                        for (child in model.children) {
                            WorkspaceChildRow(
                                name = names[child.sessionId] ?: child.sessionId,
                                working = agentTyped[child.sessionId]?.working == true,
                                onClick = { onOpenSession(w.id, child.sessionId) },
                            )
                        }
                    }
                }
            }
        }
        if (draggable && scopeKey != null) {
            ReorderableItem(reorderableState, key = "ws:${w.id}") { isDragging ->
                body(
                    isDragging,
                    Modifier.reorderDragHandle(
                        interactionSource = rowInteraction,
                        onDragStarted = { beginWorkspaceDrag(w) },
                        onDragStopped = { finishDrag() },
                    ),
                )
            }
        } else {
            body(false, Modifier)
        }
    }

    fun LazyListScope.workspaceBody() {
        if (groups.isEmpty() && archivedWorkspaces.isEmpty() && mode == SessionListMode.Workspaces) {
            item(key = "empty_hint") {
                Text(
                    "No workspaces yet",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md),
                )
            }
            return
        }
        val pas = groups.firstOrNull { it.key == PA_GROUP_KEY }?.workspaces.orEmpty()
        if (!groupByProject) {
            if (pas.isNotEmpty()) {
                item(key = "flat:pa_hdr") { SectionLabel("PERSONAL ASSISTANTS") }
                itemsIndexed(pas, key = { _, w -> "flat:pa:${w.id}" }) { index, w ->
                    WorkspaceEntry(
                        w = w, scopeKey = null, grouped = false,
                        first = index == 0, last = index == pas.lastIndex,
                        draggable = false, showProjectTag = false,
                    )
                }
            }
            val rest = applyWorkspaceWorkingOrder(wsFlatRows, wsWorkingOrders[WORKSPACE_FLAT_SCOPE])
            if (rest.isNotEmpty()) {
                // Section chrome only when PAs are also listed — otherwise a lone "IN PROGRESS"
                // header over the whole flat list is noise.
                if (pas.isNotEmpty()) {
                    item(key = "flat:h:in_progress") { SectionLabel("IN PROGRESS") }
                }
                    itemsIndexed(rest, key = { _, w -> "ws:${w.id}" }) { index, w ->
                    WorkspaceEntry(
                        w = w, scopeKey = WORKSPACE_FLAT_SCOPE, grouped = false,
                        first = index == 0, last = index == rest.lastIndex,
                        draggable = true, showProjectTag = true,
                    )
                }
            }
            if (mode == SessionListMode.Workspaces && draftSessions.isNotEmpty()) {
                item(key = "flat:h:draft") { SectionLabel("DRAFTS") }
                items(draftSessions, key = { "f:draft:${it.id}" }) { s ->
                    SessionRow(
                        s = s,
                        active = false,
                        preview = lastBySession[s.id],
                        lastReadAt = lastRead[s.id],
                        host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                        projectTag = projectLabel(s, effectiveHome),
                        openSwipeRowId = openSwipeRowId,
                        onOpenSwipeRowChange = onOpenSwipeRowChange,
                        onClick = { openSession(s) },
                        onKill = { killTarget = s },
                    )
                }
            }
            if (mode == SessionListMode.Workspaces) {
                val flatArchived = archivedGroups.flatMap { it.workspaces }
                if (flatArchived.isNotEmpty()) {
                    item(key = "flat:archived") {
                        ArchivedFoldButton(
                            count = flatArchived.size,
                            expanded = flatArchivedExpanded,
                            onClick = { flatArchivedExpanded = !flatArchivedExpanded },
                        )
                    }
                    if (flatArchivedExpanded) {
                        items(flatArchived, key = { "f:a:${it.id}" }) { w -> ArchivedEntry(w) }
                    }
                }
            }
            return
        }
        // Group-by-project. One archived fold per live project group; archived-only paths stay
        // hidden (groupWorkspaces drops them) — no orphan stack under the list.
        groups.forEach { g ->
            val isCollapsed = collapsedPaths.contains(g.key)
            val canDrag = g.key != PA_GROUP_KEY
            val ordered = applyWorkspaceWorkingOrder(
                g.workspaces,
                if (canDrag) wsWorkingOrders[g.key] else null,
            )
            item(key = "h:${g.key}") {
                GroupHeaderRow(touch) {
                    PathGroupHeader(
                        g.label,
                        ordered.size,
                        collapsed = isCollapsed,
                        onToggle = {
                            openSwipeRowId = null
                            setCollapsedPaths(
                                if (isCollapsed) collapsedPaths - g.key else collapsedPaths + g.key,
                            )
                        },
                    )
                }
            }
            if (isCollapsed) return@forEach
            itemsIndexed(ordered, key = { _, w -> "ws:${w.id}" }) { index, w ->
                WorkspaceEntry(
                    w = w, scopeKey = g.key, grouped = true,
                    first = index == 0, last = index == ordered.lastIndex,
                    draggable = canDrag, showProjectTag = false,
                )
            }
            if (mode != SessionListMode.Workspaces) return@forEach
            val archivedHere = if (g.key == PA_GROUP_KEY) emptyList() else archivedByPath[g.key].orEmpty()
            if (archivedHere.isNotEmpty()) {
                item(key = "archived:${g.key}") {
                    val open = archivedExpanded.contains(g.key)
                    ArchivedFoldButton(
                        count = archivedHere.size,
                        expanded = open,
                        onClick = {
                            archivedExpanded =
                                if (open) archivedExpanded - g.key else archivedExpanded + g.key
                        },
                    )
                }
                if (archivedExpanded.contains(g.key)) {
                    items(archivedHere, key = { "a:${it.id}" }) { w -> ArchivedEntry(w) }
                }
            }
        }
        if (mode == SessionListMode.Workspaces && groups.isEmpty()) {
            val allArchived = archivedGroups.flatMap { it.workspaces }
            if (allArchived.isNotEmpty()) {
                item(key = "archived:all") {
                    ArchivedFoldButton(
                        count = allArchived.size,
                        expanded = flatArchivedExpanded,
                        onClick = { flatArchivedExpanded = !flatArchivedExpanded },
                    )
                }
                if (flatArchivedExpanded) {
                    items(allArchived, key = { "a:all:${it.id}" }) { w -> ArchivedEntry(w) }
                }
            }
        }
    }

    fun LazyListScope.sessionBody() {
        if (!groupByProject) {
            // PA pin (web flat list) — not part of task sections. User sortOrder only; new
            // messages must not reshuffle.
            val pas = sessionsByUserOrder(onlineSessions.filter { it.role == "personal_assistant" })
            if (pas.isNotEmpty()) {
                item(key = "flat:pa_hdr") { SectionLabel("PERSONAL ASSISTANTS") }
                items(pas, key = { "flat:pa:${it.id}" }) { s ->
                    SessionRow(
                        s = s,
                        active = s.id == activeId,
                        preview = lastBySession[s.id],
                        lastReadAt = lastRead[s.id],
                        working = agentState[s.id]?.working == true,
                        bgOpen = agentState[s.id]?.bgOpen ?: 0,
                        host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                        openSwipeRowId = openSwipeRowId,
                        onOpenSwipeRowChange = onOpenSwipeRowChange,
                        onClick = { openSession(s) },
                        onRename = { renameTarget = s; renameText = s.name },
                        onKill = { killTarget = s },
                        onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                    )
                }
            }
            baseFlatSections.forEach { base ->
                val section = base.copy(
                    sessions = applyWorkingOrders(
                        rows = base.sessions,
                        workingOrders = workingOrders,
                        projectScoped = false,
                    ),
                )
                if (section.key == SectionKey.SETTLED) {
                    item(key = "flat:settled_toggle") {
                        QuietSettledToggle(
                            count = section.sessions.size,
                            expanded = flatSettledExpanded,
                            onToggle = { flatSettledExpanded = !flatSettledExpanded },
                        )
                    }
                    if (flatSettledExpanded) {
                        items(section.sessions, key = { "flat:${it.id}" }) { s ->
                            SessionRow(
                                s = s,
                                active = s.id == activeId,
                                preview = lastBySession[s.id],
                                lastReadAt = lastRead[s.id],
                                working = agentState[s.id]?.working == true,
                                bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                projectTag = projectLabel(s, effectiveHome),
                                openSwipeRowId = openSwipeRowId,
                                onOpenSwipeRowChange = onOpenSwipeRowChange,
                                onClick = { openSession(s) },
                                onRename = { renameTarget = s; renameText = s.name },
                                onKill = { killTarget = s },
                                onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                onResume = { onResume(s.id) },
                            )
                        }
                    }
                } else {
                    item(key = "flat:hdr:${section.key}") { SectionLabel(section.label.uppercase()) }
                    items(section.sessions, key = { "task:${it.id}" }) { s ->
                        ReorderableItem(reorderableState, key = "task:${s.id}") { isDragging ->
                            // Whole-row long-press on the swipe shell (parent of the Surface click).
                            // A shared interactionSource keeps click + long-press from fighting;
                            // swipe is disabled while isDragging.
                            val rowInteraction = remember { MutableInteractionSource() }
                            SessionRow(
                                s = s,
                                active = s.id == activeId,
                                preview = lastBySession[s.id],
                                lastReadAt = lastRead[s.id],
                                working = agentState[s.id]?.working == true,
                                bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                projectTag = projectLabel(s, effectiveHome),
                                isDragging = isDragging,
                                interactionSource = rowInteraction,
                                openSwipeRowId = openSwipeRowId,
                                onOpenSwipeRowChange = onOpenSwipeRowChange,
                                dragModifier = Modifier.reorderDragHandle(
                                    interactionSource = rowInteraction,
                                    onDragStarted = { beginSessionDrag(s) },
                                    onDragStopped = { finishDrag() },
                                ),
                                onClick = { openSession(s) },
                                onRename = { renameTarget = s; renameText = s.name },
                                onKill = { killTarget = s },
                                onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                onResume = { onResume(s.id) },
                            )
                        }
                    }
                }
            }
            return
        }
        // Grouped rows stay visually joined, but each row is a top-level lazy item so the reorder
        // engine can move it and auto-scroll exactly as it does in flat mode.
        sessionGroups.forEach { g ->
            val isCollapsed = collapsedPaths.contains(g.workdir)
            val isPaGroup = g.workdir == PA_GROUP_KEY
            val activeCount = if (isPaGroup) {
                g.sessions.size
            } else {
                g.sections.filter { it.key != SectionKey.SETTLED }.sumOf { it.sessions.size }
            }
            val openRows = if (isPaGroup) {
                g.sessions
            } else {
                g.sections
                    .filter { it.key != SectionKey.SETTLED }
                    .flatMap { applyWorkingOrders(it.sessions, workingOrders) }
            }
            val settledRows = g.sections.firstOrNull { it.key == SectionKey.SETTLED }?.sessions.orEmpty()
            val settledOpen = settledExpanded.contains(g.workdir)

            item(key = "group:header:${g.workdir}") {
                GroupHeaderRow(touch) {
                    PathGroupHeader(
                        label = g.label,
                        count = activeCount,
                        collapsed = isCollapsed,
                        onToggle = {
                            openSwipeRowId = null
                            setCollapsedPaths(
                                if (isCollapsed) collapsedPaths - g.workdir else collapsedPaths + g.workdir,
                            )
                        },
                    )
                }
            }
            if (isCollapsed) return@forEach
            if (isPaGroup) {
                itemsIndexed(openRows, key = { _, s -> "group:pa:${s.id}" }) { index, s ->
                    val isActive = s.id == activeId
                    SessionRow(
                        s = s,
                        active = isActive,
                        preview = lastBySession[s.id],
                        lastReadAt = lastRead[s.id],
                        working = agentState[s.id]?.working == true,
                        bgOpen = agentState[s.id]?.bgOpen ?: 0,
                        host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                        openSwipeRowId = openSwipeRowId,
                        onOpenSwipeRowChange = onOpenSwipeRowChange,
                        rowShape = groupedRowShape(index == 0, index == openRows.lastIndex),
                        outerPadding = PaddingValues(horizontal = 12.dp),
                        rowColor = if (isActive) cs.surfaceContainer else cs.surfaceContainerLow,
                        onClick = { openSession(s) },
                        onRename = { renameTarget = s; renameText = s.name },
                        onKill = { killTarget = s },
                        onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                    )
                }
            } else {
                itemsIndexed(openRows, key = { _, s -> "task:${s.id}" }) { index, s ->
                    ReorderableItem(reorderableState, key = "task:${s.id}") { isDragging ->
                        val rowInteraction = remember { MutableInteractionSource() }
                        val isActive = s.id == activeId
                        SessionRow(
                            s = s,
                            active = isActive,
                            preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                            working = agentState[s.id]?.working == true,
                            bgOpen = agentState[s.id]?.bgOpen ?: 0,
                            host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                            openSwipeRowId = openSwipeRowId,
                            onOpenSwipeRowChange = onOpenSwipeRowChange,
                            isDragging = isDragging,
                            interactionSource = rowInteraction,
                            dragModifier = Modifier.reorderDragHandle(
                                interactionSource = rowInteraction,
                                onDragStarted = { beginSessionDrag(s) },
                                onDragStopped = { finishDrag() },
                            ),
                            rowShape = groupedRowShape(
                                first = index == 0,
                                last = index == openRows.lastIndex && settledRows.isEmpty(),
                            ),
                            outerPadding = PaddingValues(horizontal = 12.dp),
                            rowColor = if (isActive) cs.surfaceContainer else cs.surfaceContainerLow,
                            onClick = { openSession(s) },
                            onRename = { renameTarget = s; renameText = s.name },
                            onKill = { killTarget = s },
                            onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                            onResume = { onResume(s.id) },
                        )
                    }
                }
            }
            if (settledRows.isNotEmpty()) {
                item(key = "group:settled-toggle:${g.workdir}") {
                    Surface(
                        shape = groupedRowShape(first = openRows.isEmpty(), last = !settledOpen),
                        color = cs.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        QuietSettledToggle(
                            count = settledRows.size,
                            expanded = settledOpen,
                            onToggle = {
                                openSwipeRowId = null
                                settledExpanded = if (settledOpen) {
                                    settledExpanded - g.workdir
                                } else {
                                    settledExpanded + g.workdir
                                }
                            },
                            inCard = true,
                        )
                    }
                }
                if (settledOpen) {
                    itemsIndexed(settledRows, key = { _, s -> "group:settled:${s.id}" }) { index, s ->
                        val isActive = s.id == activeId
                        SessionRow(
                            s = s,
                            active = isActive,
                            preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                            working = false,
                            host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                            openSwipeRowId = openSwipeRowId,
                            onOpenSwipeRowChange = onOpenSwipeRowChange,
                            rowShape = groupedRowShape(false, index == settledRows.lastIndex),
                            outerPadding = PaddingValues(horizontal = 12.dp),
                            rowColor = if (isActive) cs.surfaceContainer else cs.surfaceContainerLow,
                            onClick = { openSession(s) },
                            onResume = { onResume(s.id) },
                            onKill = { killTarget = s },
                        )
                    }
                }
            }
        }
    }

    fun LazyListScope.body() {
        if (chrome) {
            if (multiHost) {
                item(key = "host_filter_chips") {
                    HostFilterChips(
                        hosts = hosts,
                        sessions = sessions,
                        sessionHost = sessionHost,
                        selected = hostFilter,
                        onSelect = onHostFilter,
                        onAddHost = onAddHost,
                        onRenameHost = actions.renameHost,
                        onForgetHost = actions.forgetHost,
                    )
                }
            }
            item(key = "new_session_row") { NewSessionListRow(onClick = onNewSession) }
            item(key = "group_by_toggle") {
                GroupByProjectRow(
                    checked = groupByProject,
                    onCheckedChange = { groupByProject = it; onGroupByProjectChange(it) },
                )
            }
        }
        if (useWorkspaces) workspaceBody() else sessionBody()

        // Offline hosts (spec §5): a greyed group per unreachable host with its last-seen and its
        // cached sessions (rendered dimmed). Skipped entirely in single-host mode.
        offlineGroups.forEach { (host, cached) ->
            item(key = "offline:${host.recordId}") { OfflineHostHeader(host) }
            items(cached, key = { "off:${it.id.ifEmpty { it.name }}" }) { s ->
                Box(Modifier.graphicsLayer { alpha = 0.55f }) {
                    SessionRow(
                        s = s,
                        active = false,
                        preview = lastBySession[s.id],
                        lastReadAt = lastRead[s.id],
                        host = if (showRowHostBadge) hostByRecord[host.recordId] else null,
                        openSwipeRowId = openSwipeRowId,
                        onOpenSwipeRowChange = onOpenSwipeRowChange,
                        onClick = { onOpen(s.id) },
                        onRename = { renameTarget = s; renameText = s.name },
                        onKill = { killTarget = s },
                        onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                    )
                }
            }
        }
        if (mode == SessionListMode.Fleet && sessionListShowsArchivedWorkspaceFold(archivedWorkspaces)) {
            item(key = "archived_fold") {
                ArchivedFoldButton(
                    count = archivedWorkspaces.size,
                    expanded = archivedFoldOpen,
                    onClick = { archivedFoldOpen = !archivedFoldOpen },
                )
            }
            if (archivedFoldOpen) {
                archivedGroups.forEach { g ->
                    item(key = "arch:hdr:${g.key}") { ArchivedGroupLabel(g.label) }
                    items(g.workspaces, key = { "arch:${it.id}" }) { w -> ArchivedEntry(w) }
                }
            }
        }
        item(key = "bottom_spacer") { Spacer(Modifier.height(if (chrome) 88.dp else Space.lg)) }
    }

    // ── Chrome ────────────────────────────────────────────────────────────────────────────────
    val listTag = if (useWorkspaces) WorkspaceListTestIds.LIST else TestIds.SESSION_LIST
    if (chrome) {
        Scaffold(
            modifier = modifier,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(Res.drawable.mux_logo),
                                contentDescription = "Supermux logo",
                                tint = cs.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text("supermux", color = cs.onSurface, style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    actions = {
                        if (onNavigate != null) {
                            OverflowNav(
                                expanded = menuExpanded,
                                onExpandedChange = { menuExpanded = it },
                                onNavigate = onNavigate,
                                onAddHost = onAddHost,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = cs.surfaceContainerLow,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onNewSession,
                    modifier = Modifier
                        .testTag("new_session_fab")
                        .size(56.dp)
                        .softElevation(radius = Radii.pill),
                    shape = CircleShape,
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New session",
                        tint = cs.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            floatingActionButtonPosition = FabPosition.End,
            containerColor = cs.surfaceContainerHigh,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .testTag(listTag)
                        .fillMaxSize()
                        .background(cs.surfaceContainerHigh),
                ) { body() }
            }
        }
    } else {
        Column(
            modifier
                .background(cs.surfaceContainerHigh)
                .fillMaxSize(),
        ) {
            NewSessionListRow(onClick = onNewSession, modifier = Modifier.padding(top = Space.md))
            if (multiHost) {
                HostFilterChips(
                    hosts = hosts,
                    sessions = sessions,
                    sessionHost = sessionHost,
                    selected = hostFilter,
                    onSelect = onHostFilter,
                    onAddHost = onAddHost,
                    onRenameHost = actions.renameHost,
                    onForgetHost = actions.forgetHost,
                )
            }
            SessionsSectionHeader(
                title = if (useWorkspaces) "Workspaces" else "Sessions",
                groupByProject = groupByProject,
                onToggleGroupByProject = {
                    groupByProject = !groupByProject
                    onGroupByProjectChange(groupByProject)
                },
                // Without the top bar the overflow destinations would be unreachable on a mount
                // that has a router (Android's tablet sidebar), so they live in the header there.
                overflow = onNavigate?.let { nav ->
                    {
                        OverflowNav(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                            onNavigate = nav,
                            onAddHost = onAddHost,
                        )
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.testTag(listTag).fillMaxSize(),
                ) { body() }
            }
            footer?.invoke()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────────────────────
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(target.id, renameText.trim()); renameTarget = null }) {
                    Text("Rename")
                }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    renameWorkspaceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameWorkspaceTarget = null },
            title = { Text("Rename workspace") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val sid = target.primarySessionId ?: target.chatSessionIds().firstOrNull()
                    if (sid != null) onRename(sid, renameText.trim())
                    renameWorkspaceTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameWorkspaceTarget = null }) { Text("Cancel") } },
        )
    }
    archiveWorkspaceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveWorkspaceTarget = null },
            title = { Text("Archive workspace?") },
            text = { Text("This archives \"${target.name}\" and ends its agents. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.archiveWorkspace(target.id)
                    archiveWorkspaceTarget = null
                }) { Text("Archive", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { archiveWorkspaceTarget = null }) { Text("Cancel") } },
        )
    }
    killTarget?.let { target ->
        val discard = target.sectionKey() == SectionKey.DRAFT
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text(if (discard) "Discard draft?" else "Settle session?") },
            text = {
                Text(
                    if (discard) {
                        "This permanently discards \"${target.name}\". This can't be undone."
                    } else {
                        "This ends \"${target.name}\" and its agent. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onKill(target.id); killTarget = null }) {
                    Text(if (discard) "Discard" else "Settle", color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
    }
}

// ─── Pieces ─────────────────────────────────────────────────────────────────────────────────────

private fun groupedRowShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) Radii.lg else 0.dp,
    topEnd = if (first) Radii.lg else 0.dp,
    bottomStart = if (last) Radii.lg else 0.dp,
    bottomEnd = if (last) Radii.lg else 0.dp,
)

@Composable
private fun SectionLabel(text: String) {
    val touch = LocalInputMode.current == InputMode.Touch
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = MonoFontFamily,
        fontSize = 11.sp,
        fontWeight = if (touch) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.padding(
            horizontal = if (touch) 16.dp else Space.md,
            vertical = if (touch) 8.dp else 6.dp,
        ),
    )
}

/** Android insets its group headers; desktop's sit flush against the rail. */
@Composable
private fun GroupHeaderRow(touch: Boolean, content: @Composable () -> Unit) {
    if (touch) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { content() } else content()
}

@Composable
private fun ArchivedGroupLabel(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = MonoFontFamily,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Indented child session under a multi-agent workspace (desktop's sidebar). */
@Composable
private fun WorkspaceChildRow(name: String, working: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionStatusRail(git = null, working = working, bgOpen = 0, unread = false)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Web/iOS parity: full-width row, label left, switch right (not a FilterChip). */
@Composable
private fun GroupByProjectRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("group_by_project")
            .semantics {
                stateDescription = if (checked) "Group by project on" else "Group by project off"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Group by project",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .testTag("group_by_project_switch")
                .semantics { contentDescription = "Group by project" },
        )
    }
}

@Composable
private fun QuietSettledToggle(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    inCard: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Text(
        if (expanded) "Hide $count settled" else "Show $count settled",
        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = if (inCard) 10.dp else 8.dp)
            .testTag("settled_toggle"),
    )
}

/** Greyed group header for an offline/unreachable host (spec §5): dot + name + last-seen. */
@Composable
private fun OfflineHostHeader(host: HostView) {
    val cs = MaterialTheme.colorScheme
    val lastSeen = formatLastSeen(Clock.System.now().toEpochMilliseconds(), host.lastSeenAt)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("offline_host_${host.recordId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HostDot(host.colorIndex, size = 8.dp)
        Text(
            host.displayLabel,
            color = cs.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "· offline" + if (lastSeen.isNotEmpty()) " · seen $lastSeen" else "",
            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/** "<Workspaces|Sessions>" label + search (reserved) + group-by toggle + optional overflow. */
@Composable
private fun SessionsSectionHeader(
    title: String,
    groupByProject: Boolean,
    onToggleGroupByProject: () -> Unit,
    overflow: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        // Search not wired yet — icon reserved so the chrome matches the mock.
        IconButton(
            onClick = { /* search TBD */ },
            modifier = Modifier.size(28.dp).testTag("sidebar_search"),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search ${title.lowercase()}",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        IconButton(
            onClick = onToggleGroupByProject,
            modifier = Modifier.size(28.dp).testTag("sidebar_group_toggle"),
        ) {
            Icon(
                Icons.Filled.ViewAgenda,
                contentDescription = if (groupByProject) "Flat list" else "Group by project",
                tint = if (groupByProject) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        overflow?.invoke()
    }
}

/** The `⋮` list menu: add host + the settings destinations. */
@Composable
private fun OverflowNav(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onAddHost: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag("list_overflow"),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Actions",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            // Always-reachable add-host entry (the filter row's `+` chip is hidden until a 2nd
            // host exists, so the very first extra host is added from here — spec §5).
            NavItem("Add host", Icons.Filled.Add, "nav_add_host") { onExpandedChange(false); onAddHost() }
            NavItem("Archived", Icons.Filled.Archive, "nav_archived") {
                onExpandedChange(false); onNavigate("archived")
            }
            NavItem("Usage", Icons.Filled.BarChart, "nav_usage") {
                onExpandedChange(false); onNavigate("usage")
            }
            NavItem("Proxies", Icons.Filled.Hub, "nav_proxies") {
                onExpandedChange(false); onNavigate("proxies")
            }
            NavItem("Appearance", Icons.Filled.Monitor, "nav_appearance") {
                onExpandedChange(false); onNavigate("appearance")
            }
            NavItem("Settings", Icons.Filled.Settings, "nav_settings") {
                onExpandedChange(false); onNavigate("settings")
            }
            NavItem("Devices", Icons.Filled.PhoneAndroid, "nav_devices") {
                onExpandedChange(false); onNavigate("devices")
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        },
        modifier = Modifier.testTag(tag),
        onClick = onClick,
    )
}

/**
 * Desktop's sticky sidebar footer: theme / usage / devices / settings.
 *
 * A slot rather than parameters on the screen — usage is an anchored popover whose body only the
 * shell can build, and Android has no footer at all.
 */
@Composable
fun SessionListFooter(
    appearance: AppearanceMode,
    onToggleTheme: () -> Unit,
    onUsage: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    usageOpen: Boolean = false,
    onUsageDismiss: () -> Unit = {},
    usageContent: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    // The icon shows what you'll switch TO: sun when dark, moon when light.
    val darkNow = appearance != AppearanceMode.LIGHT
    val themeIcon = if (darkNow) Icons.Filled.LightMode else Icons.Filled.DarkMode
    val themeLabel = if (darkNow) "Switch to light theme" else "Switch to dark theme"
    Column(Modifier.fillMaxWidth()) {
        // onSurface at ~14%: on the dark rail `outlineVariant` is nearly invisible.
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = cs.onSurface.copy(alpha = 0.14f),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("sidebar_footer")
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            FooterIcon(themeIcon, themeLabel, "sidebar_footer_theme", onToggleTheme)
            Box {
                FooterIcon(Icons.Filled.DataUsage, "Usage", "sidebar_footer_usage", onUsage)
                if (usageContent != null) {
                    UsagePopover(
                        expanded = usageOpen,
                        onDismissRequest = onUsageDismiss,
                        content = usageContent,
                    )
                }
            }
            FooterIcon(Icons.Filled.Devices, "Devices", "sidebar_footer_devices", onDevices)
            FooterIcon(Icons.Filled.Settings, "Settings", "sidebar_footer_settings", onSettings)
        }
    }
}

@Composable
private fun FooterIcon(image: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp).testTag(tag)) {
        Icon(image, contentDescription = label, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
    }
}
