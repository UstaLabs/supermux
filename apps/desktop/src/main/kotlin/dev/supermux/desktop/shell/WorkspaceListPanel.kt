// Copy-adapted from SessionListPanel — same sidebar chrome, workspace rows instead of session rows.
// Spec §13.6: the sidebar lists workspaces. One-chat workspaces look like today's session row;
// multi-chat workspaces add child rows + a multi-agent mark.
//
// Design rules (non-negotiable, from the reverted session-list redesign):
//  - No large per-row avatars — SessionStatusRail is the leading visual.
//  - Keep branch and git status on the row.
//  - Rows: name + message preview + branch (path is on the group header — do not bring it back).
//  - Suspended/lifecycle badge from the primary session, SessionRow styling.
//  - Settled fold: SessionListPanel group mode only — one fold per live project group;
//    no orphan/settled-only stack under the list.
//  - No animation on this surface (100+/day).
//  - Geist for language, Geist Mono for machine content (paths, branches).
//  - One teal accent, used for state and agency only.
//
// Rule of thumb for reviewers: diff against SessionListPanel should be row-model changes +
// Chrome (new-session card, host chips, section header,
// footer rail, Settled fold, context menus, drag-reorder) is intentional parity.
package dev.supermux.desktop.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.host.HostBadge
import dev.supermux.desktop.host.HostFilterChips
import dev.supermux.desktop.host.HostView
import dev.supermux.desktop.host.filterSessions
import dev.supermux.desktop.session.NewSessionListRow
import dev.supermux.desktop.session.PathGroupHeader
import dev.supermux.desktop.session.SessionRow
import dev.supermux.desktop.session.SessionStatusRail
import dev.supermux.desktop.session.relTime
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.softElevation
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.SectionKey
import dev.supermux.session.buildTaskSections
import dev.supermux.session.combinedTaskSessions
import dev.supermux.session.inferHomeDir
import dev.supermux.session.projectLabel
import dev.supermux.session.sectionKey
import dev.supermux.session.sessionsByUserOrder
import dev.supermux.workspace.WorkspaceActivity
import dev.supermux.workspace.chatSessionIds
import dev.supermux.workspace.groupWorkspaces
import dev.supermux.workspace.isMultiAgent
import dev.supermux.workspace.workspaceActivity
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Workspace sidebar (spec §13.6). Same chrome as [dev.supermux.desktop.session.SessionListPanel]
 * (new-session card, host chips, section header, footer rail, Settled fold, drag-reorder,
 * context menus). Rows are workspaces grouped via [groupWorkspaces]; [sessions] resolves names,
 * roles, mute, git and agent state per workspace.
 */
@Composable
fun WorkspaceListPanel(
    workspaces: List<WorkspaceDto>,
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    /** Live sessions — resolve names/roles/mute/git and feed Settled/Draft chrome. */
    sessions: List<SessionInfo> = emptyList(),
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    /** Bare sessionId → ISO last_read_at (server + optimistic local marks). */
    lastRead: Map<String, String> = emptyMap(),
    agentState: Map<String, AgentStatus> = emptyMap(),
    /**
     * session id → display name, for multi-agent child rows.
     * Defaults from [sessions] when empty.
     */
    sessionNames: Map<String, String> = emptyMap(),
    /**
     * session id → role. A workspace whose primary session has
     * `role == "personal_assistant"` lands in the pinned PA group.
     * Defaults from [sessions] when empty.
     */
    sessionRoles: Map<String, String?> = emptyMap(),
    onOpenSession: (workspaceId: String, sessionId: String) -> Unit = { _, _ -> },
    /** Rename via the workspace's primary session (caller maps id → session). */
    onRename: (String, String) -> Unit = { _, _ -> },
    /** Archive the workspace (caller settles its chat sessions). */
    onKill: (String) -> Unit = {},
    /** Mute via the workspace's primary session. */
    onMute: (String, Boolean) -> Unit = { _, _ -> },
    onNewSession: () -> Unit = {},
    archived: List<ArchivedDto> = emptyList(),
    onResume: (String) -> Unit = {},
    onOpenDraft: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    // ── Multi-host fleet (spec §5); all default to single-host (no badges/chips) ──
    hosts: List<HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onSelectHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
    // Sidebar footer actions (Cursor-style rail). Defaults no-op so existing tests stay green.
    onUsage: () -> Unit = {},
    onSettings: () -> Unit = {},
    onDevices: () -> Unit = {},
    /**
     * Shared tab-drag state from the layout host. When non-null, each workspace
     * row registers as a drop target for cross-workspace view moves.
     */
    tabDragState: TabDragState? = null,
    /** Current app appearance — footer theme button shows the opposite affordance. */
    appearance: AppearanceMode = AppearanceMode.DARK,
    onToggleTheme: () -> Unit = {},
    /**
     * Per-workspace affordance: add a view/tab to this workspace.
     * Hover-revealed on the row; caller may open the launcher until a dedicated flow exists.
     */
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val multiHost = hosts.size > 1
    val showRowHostBadge = multiHost && hostFilter == null
    val hostByRecord = remember(hosts) { hosts.associateBy { it.recordId } }

    val names = remember(sessions, sessionNames) {
        if (sessionNames.isNotEmpty()) sessionNames else sessions.associate { it.id to it.name }
    }
    val roles = remember(sessions, sessionRoles) {
        if (sessionRoles.isNotEmpty()) sessionRoles else sessions.associate { it.id to it.role }
    }
    val sessionById = remember(sessions) { sessions.associateBy { it.id } }

    // Host filter applies to sessions first; workspaces stay visible when any of their chat
    // sessions (or primary) still passes the filter.
    val visibleSessions = if (multiHost) filterSessions(sessions, sessionHost, hostFilter) else sessions
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

    val lastTs: (SessionInfo) -> String = { lastBySession[it.id]?.ts ?: "" }
    val flatSections = remember(visibleSessions, lastBySession, archived) {
        buildTaskSections(combinedTaskSessions(visibleSessions, archived), lastTs)
    }
    // Settled sessions keyed by project path for the fold under each workspace group.
    // Recency order matches buildTaskSections (SessionListPanel parity).
    val settledByPath = remember(visibleSessions, archived, lastBySession) {
        val combined = combinedTaskSessions(visibleSessions, archived)
        val settled = combined.filter { it.sectionKey() == SectionKey.SETTLED }
        settled.groupBy { it.repo_root ?: it.workdir }.mapValues { (_, list) ->
            buildTaskSections(list, lastTs)
                .firstOrNull { it.key == SectionKey.SETTLED }
                ?.sessions
                .orEmpty()
        }
    }
    // Draft sessions still live on the session model (workspaces don't draft yet).
    val draftSessions = remember(visibleSessions) {
        sessionsByUserOrder(visibleSessions.filter { it.sectionKey() == SectionKey.DRAFT })
    }

    var groupByProject by remember { mutableStateOf(true) }
    var settledExpanded by remember { mutableStateOf(setOf<String>()) }
    var flatSettledExpanded by remember { mutableStateOf(false) }

    var renameTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    val listState = rememberLazyListState()
    // Live order while dragging (Android SessionListScreen / calvin pattern). Keyed by
    // group path or WORKSPACE_FLAT_SCOPE so neighbors can animate before the PATCH returns.
    val workingOrders = remember { mutableStateMapOf<String, List<String>>() }
    val dragWorkingState = remember { WorkspaceDragWorkingState() }

    fun reorderWithin(list: List<WorkspaceDto>, id: String, delta: Int) {
        val ids = list.map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in ids.indices) return
        java.util.Collections.swap(ids, i, j)
        onReorder(ids)
    }

    fun finishDrag() {
        val finished = dragWorkingState.finish(commit = true)
        finished?.let { move ->
            onReorder(move.orderedIds)
            // Drop gesture overlay so a later server snapshot (or failed PATCH rollback) can win.
            workingOrders.remove(move.scope.key)
        }
    }

    // Scope → current rows used by onMove to keep calvin mutations section-local.
    fun rowsForScope(scopeKey: String): List<WorkspaceDto> = when (scopeKey) {
        WORKSPACE_FLAT_SCOPE -> groups
            .filter { it.key != PA_GROUP_KEY }
            .flatMap { it.workspaces }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        else -> groups.firstOrNull { it.key == scopeKey }?.workspaces.orEmpty()
    }

    fun scopeOf(workspaceId: String): String? {
        if (!groupByProject) {
            val restIds = rowsForScope(WORKSPACE_FLAT_SCOPE).map { it.id }
            return if (workspaceId in restIds) WORKSPACE_FLAT_SCOPE else null
        }
        return groups.firstOrNull { g ->
            g.key != PA_GROUP_KEY && g.workspaces.any { it.id == workspaceId }
        }?.key
    }

    // Native Compose reorder (sh.calvin.reorderable) — elevates the item, auto-scrolls,
    // animates neighbors. Same library + pattern as Android SessionListScreen.
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        if (!fromKey.startsWith("ws:") || !toKey.startsWith("ws:")) {
            return@rememberReorderableLazyListState
        }
        val fromId = fromKey.removePrefix("ws:")
        val toId = toKey.removePrefix("ws:")
        val scopeKey = scopeOf(fromId) ?: return@rememberReorderableLazyListState
        if (scopeOf(toId) != scopeKey) return@rememberReorderableLazyListState
        val rows = rowsForScope(scopeKey)
        val move = moveWorkspaceWithinScope(
            rows = rows,
            workingOrders = workingOrders,
            scopeKey = scopeKey,
            fromId = fromId,
            toId = toId,
        ) ?: return@rememberReorderableLazyListState
        val originalIds = workingOrders[scopeKey] ?: rows.map { it.id }
        dragWorkingState.beginIfIdle(move.scope, originalIds)
        // Calvin requires this mutation before onMove returns so neighbors can animate.
        workingOrders[scopeKey] = move.orderedIds
        dragWorkingState.move(move.orderedIds)
    }
    LaunchedEffect(reorderableState) {
        var wasDragging = reorderableState.isAnyItemDragging
        snapshotFlow { reorderableState.isAnyItemDragging }.collect { dragging ->
            if (wasDragging && !dragging) finishDrag()
            wasDragging = dragging
        }
    }

    fun openDraft(s: SessionInfo) {
        when (s.sectionKey()) {
            SectionKey.DRAFT -> onOpenDraft(s.id)
            else -> { /* settled handled via onResume */ }
        }
    }

    fun primarySession(w: WorkspaceDto): SessionInfo? {
        val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
        return sid?.let { sessionById[it] }
    }

    Column(
        modifier
            .background(cs.surfaceContainerHigh)
            .fillMaxSize(),
    ) {
        // ── Header: full "Start a new session" card (same chrome as SessionListPanel) ─
        NewSessionListRow(
            onClick = onNewSession,
            modifier = Modifier.padding(top = Space.md),
        )
        if (multiHost) {
            HostFilterChips(
                hosts = hosts,
                sessions = sessions,
                sessionHost = sessionHost,
                selected = hostFilter,
                onSelect = onSelectHostFilter,
                onAddHost = onAddHost,
            )
        }

        // ── Section chrome: "Workspaces" + group toggle (search reserved) ───────
        WorkspacesSectionHeader(
            groupByProject = groupByProject,
            onToggleGroupByProject = { groupByProject = !groupByProject },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .testTag("workspaces_list")
                .fillMaxSize(),
        ) {
            if (groups.isEmpty() && flatSections.isEmpty()) {
                item(key = "empty_hint") {
                    Text(
                        "No workspaces yet",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md),
                    )
                }
            } else if (!groupByProject) {
                // Flat: PA workspaces pin, then remaining workspaces by sortOrder, then Settled.
                val pas = groups.firstOrNull { it.key == PA_GROUP_KEY }?.workspaces.orEmpty()
                val rest = groups.filter { it.key != PA_GROUP_KEY }.flatMap { it.workspaces }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                if (pas.isNotEmpty()) {
                    item(key = "flat:pa_hdr") {
                        Text(
                            "PERSONAL ASSISTANTS",
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = Space.md, vertical = 6.dp),
                        )
                    }
                    items(pas, key = { "flat:pa:${it.id}" }) { w ->
                        WorkspaceListEntry(
                            w = w,
                            activeId = activeId,
                            agentState = agentState,
                            names = names,
                            sessionById = sessionById,
                            lastBySession = lastBySession,
                            lastRead = lastRead,
                            host = if (showRowHostBadge) {
                                val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
                                sid?.let { hostByRecord[sessionHost[it]] }
                            } else null,
                            onOpen = onOpen,
                            onOpenSession = onOpenSession,
                            onRename = { renameTarget = w; renameText = w.name },
                            onKill = { killTarget = w },
                            onToggleMute = {
                                val s = primarySession(w)
                                if (s != null) onMute(w.id, !(s.mute ?: false))
                            },
                            tabDragState = tabDragState,
                        )
                    }
                }
                if (rest.isNotEmpty()) {
                    // Section chrome only when PAs are also listed — otherwise a lone
                    // "IN PROGRESS" header over the whole flat list is noise.
                    if (pas.isNotEmpty()) {
                        item(key = "flat:h:in_progress") {
                            Text(
                                "IN PROGRESS",
                                color = cs.onSurfaceVariant,
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = Space.md, vertical = 6.dp),
                            )
                        }
                    }
                    val orderedRest = applyWorkspaceWorkingOrder(
                        rest,
                        workingOrders[WORKSPACE_FLAT_SCOPE],
                    )
                    items(orderedRest, key = { "ws:${it.id}" }) { w ->
                        ReorderableItem(reorderableState, key = "ws:${w.id}") { isDragging ->
                            val rowInteraction = remember { MutableInteractionSource() }
                            WorkspaceListEntry(
                                w = w,
                                activeId = activeId,
                                agentState = agentState,
                                names = names,
                                sessionById = sessionById,
                                lastBySession = lastBySession,
                                lastRead = lastRead,
                                host = if (showRowHostBadge) {
                                    val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
                                    sid?.let { hostByRecord[sessionHost[it]] }
                                } else null,
                                projectTag = projectLabel(
                                    primarySession(w) ?: SessionInfo(
                                        id = w.id, name = w.name, workdir = w.workdir, agent = "claude",
                                        repo_root = w.repoRoot,
                                    ),
                                    effectiveHome,
                                ),
                                isDragging = isDragging,
                                interactionSource = rowInteraction,
                                dragModifier = Modifier.draggableHandle(
                                    interactionSource = rowInteraction,
                                    onDragStarted = {
                                        val ids = workingOrders[WORKSPACE_FLAT_SCOPE]
                                            ?: orderedRest.map { it.id }
                                        dragWorkingState.begin(
                                            WorkspaceReorderScope(WORKSPACE_FLAT_SCOPE),
                                            ids,
                                        )
                                    },
                                    onDragStopped = { finishDrag() },
                                ),
                                onOpen = onOpen,
                                onOpenSession = onOpenSession,
                                onRename = { renameTarget = w; renameText = w.name },
                                onKill = { killTarget = w },
                                tabDragState = tabDragState,
                                onToggleMute = {
                                    val s = primarySession(w)
                                    if (s != null) onMute(w.id, !(s.mute ?: false))
                                },
                                onMoveUp = { reorderWithin(orderedRest, w.id, -1) },
                                onMoveDown = { reorderWithin(orderedRest, w.id, +1) },
                            )
                        }
                    }
                }
                if (draftSessions.isNotEmpty()) {
                    item(key = "flat:h:draft") {
                        Text(
                            "DRAFTS",
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = Space.md, vertical = 6.dp),
                        )
                    }
                    items(draftSessions, key = { "f:draft:${it.id}" }) { s ->
                        SessionRow(
                            s, active = false, preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                            host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                            projectTag = projectLabel(s, effectiveHome),
                            onClick = { openDraft(s) },
                            onKill = { /* draft discard routes through session kill if wired later */ },
                        )
                    }
                }
                val settledSection = flatSections.firstOrNull { it.key == SectionKey.SETTLED }
                if (settledSection != null && settledSection.sessions.isNotEmpty()) {
                    item(key = "flat:settled") {
                        SettledFoldButton(
                            count = settledSection.sessions.size,
                            expanded = flatSettledExpanded,
                            onClick = { flatSettledExpanded = !flatSettledExpanded },
                        )
                    }
                    if (flatSettledExpanded) {
                        items(settledSection.sessions, key = { "f:s:${it.id}" }) { s ->
                            SessionRow(
                                s, active = s.id == activeId, preview = lastBySession[s.id],
                                lastReadAt = lastRead[s.id],
                                host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                projectTag = projectLabel(s, effectiveHome),
                                onClick = { onResume(s.id) },
                                onResume = { onResume(s.id) },
                            )
                        }
                    }
                }
            } else {
                // Group-by-project: match SessionListPanel exactly.
                // - Settled fold only under each live project group that has settled rows.
                // - Settled-only paths are HIDDEN (groupSessions drops hasActive=false groups).
                // - No "orphan" fold stacked under the list — that invented extra "Show N settled"
                //   chrome SessionListPanel never draws.
                groups.forEach { g ->
                    item(key = "h:${g.key}") { PathGroupHeader(g.label, g.workspaces.size) }
                    val canDrag = g.key != PA_GROUP_KEY
                    val ordered = applyWorkspaceWorkingOrder(
                        g.workspaces,
                        if (canDrag) workingOrders[g.key] else null,
                    )
                    if (canDrag) {
                        items(ordered, key = { "ws:${it.id}" }) { w ->
                            ReorderableItem(reorderableState, key = "ws:${w.id}") { isDragging ->
                                val rowInteraction = remember { MutableInteractionSource() }
                                WorkspaceListEntry(
                                    w = w,
                                    activeId = activeId,
                                    agentState = agentState,
                                    names = names,
                                    sessionById = sessionById,
                                    lastBySession = lastBySession,
                                    lastRead = lastRead,
                                    host = if (showRowHostBadge) {
                                        val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
                                        sid?.let { hostByRecord[sessionHost[it]] }
                                    } else null,
                                    isDragging = isDragging,
                                    interactionSource = rowInteraction,
                                    dragModifier = Modifier.draggableHandle(
                                        interactionSource = rowInteraction,
                                        onDragStarted = {
                                            val ids = workingOrders[g.key] ?: ordered.map { it.id }
                                            dragWorkingState.begin(WorkspaceReorderScope(g.key), ids)
                                        },
                                        onDragStopped = { finishDrag() },
                                    ),
                                    onOpen = onOpen,
                                    onOpenSession = onOpenSession,
                                    onRename = { renameTarget = w; renameText = w.name },
                                    onKill = { killTarget = w },
                                    onToggleMute = {
                                        val s = primarySession(w)
                                        if (s != null) onMute(w.id, !(s.mute ?: false))
                                    },
                                    onMoveUp = { reorderWithin(ordered, w.id, -1) },
                                    onMoveDown = { reorderWithin(ordered, w.id, +1) },
                                    tabDragState = tabDragState,
                                )
                            }
                        }
                    } else {
                        items(ordered, key = { "ws:${it.id}" }) { w ->
                            WorkspaceListEntry(
                                w = w,
                                activeId = activeId,
                                agentState = agentState,
                                names = names,
                                sessionById = sessionById,
                                lastBySession = lastBySession,
                                lastRead = lastRead,
                                host = if (showRowHostBadge) {
                                    val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
                                    sid?.let { hostByRecord[sessionHost[it]] }
                                } else null,
                                onOpen = onOpen,
                                onOpenSession = onOpenSession,
                                onRename = { renameTarget = w; renameText = w.name },
                                onKill = { killTarget = w },
                                onToggleMute = {
                                    val s = primarySession(w)
                                    if (s != null) onMute(w.id, !(s.mute ?: false))
                                },
                                tabDragState = tabDragState,
                            )
                        }
                    }
                    // Settled fold under this project (SessionListPanel: g.sections SETTLED).
                    val pathKey = g.key
                    val settled = if (pathKey == PA_GROUP_KEY) emptyList()
                    else settledByPath[pathKey].orEmpty()
                    if (settled.isNotEmpty()) {
                        item(key = "settled:${g.key}") {
                            val open = settledExpanded.contains(g.key)
                            SettledFoldButton(
                                count = settled.size,
                                expanded = open,
                                onClick = {
                                    settledExpanded = if (open) settledExpanded - g.key else settledExpanded + g.key
                                },
                            )
                        }
                        if (settledExpanded.contains(g.key)) {
                            items(settled, key = { "s:${it.id}" }) { s ->
                                SessionRow(
                                    s, active = false, preview = lastBySession[s.id],
                                    lastReadAt = lastRead[s.id],
                                    host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                    onClick = { onResume(s.id) },
                                    onResume = { onResume(s.id) },
                                )
                            }
                        }
                    }
                }
                // Empty live list but archived data: one global fold (empty-workspace chrome).
                // SessionListPanel group mode would show nothing here; we keep a single fold so
                // archived remains reachable when there are zero workspaces.
                if (groups.isEmpty()) {
                    val settledSection = flatSections.firstOrNull { it.key == SectionKey.SETTLED }
                    if (settledSection != null && settledSection.sessions.isNotEmpty()) {
                        item(key = "settled:all") {
                            SettledFoldButton(
                                count = settledSection.sessions.size,
                                expanded = flatSettledExpanded,
                                onClick = { flatSettledExpanded = !flatSettledExpanded },
                            )
                        }
                        if (flatSettledExpanded) {
                            items(settledSection.sessions, key = { "s:all:${it.id}" }) { s ->
                                SessionRow(
                                    s, active = false, preview = lastBySession[s.id],
                                    lastReadAt = lastRead[s.id],
                                    onClick = { onResume(s.id) },
                                    onResume = { onResume(s.id) },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(Space.lg)) }
        }
        } // end list Box

        // ── Footer: theme / usage / devices / settings ─
        WorkspaceSidebarFooter(
            appearance = appearance,
            onToggleTheme = onToggleTheme,
            onUsage = onUsage,
            onDevices = onDevices,
            onSettings = onSettings,
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename workspace") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(target.id, renameText.trim()); renameTarget = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Archive workspace?") },
            text = { Text("This archives \"${target.name}\" and ends its agents. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onKill(target.id); killTarget = null }) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
    }
}

/** "Show N settled" / "Hide N settled" — shared by flat + per-group folds. */
@Composable
private fun SettledFoldButton(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag("settled_fold"),
    ) {
        Text(
            if (expanded) "Hide $count settled" else "Show $count settled",
            fontSize = 12.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

/**
 * Workspace row + optional multi-agent children. Keeps list item keys stable.
 */
@Composable
private fun WorkspaceListEntry(
    w: WorkspaceDto,
    activeId: String?,
    agentState: Map<String, AgentStatus>,
    names: Map<String, String>,
    sessionById: Map<String, SessionInfo>,
    lastBySession: Map<String, LogEntry?>,
    lastRead: Map<String, String>,
    host: HostView? = null,
    projectTag: String? = null,
    modifier: Modifier = Modifier,
    /** Calvin drag handle — whole-row press-drag on desktop (Android uses long-press). */
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    isDragging: Boolean = false,
    onOpen: (String) -> Unit,
    onOpenSession: (workspaceId: String, sessionId: String) -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
    onToggleMute: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    tabDragState: TabDragState? = null,
) {
    val activity = workspaceActivity(w, agentState)
    val primarySid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
    val primary = primarySid?.let { sessionById[it] }
    val git = primary?.git
    val preview = primarySid?.let { lastBySession[it] }
    val lastReadAt = primarySid?.let { lastRead[it] }
    Column(Modifier.fillMaxWidth()) {
        WorkspaceRow(
            w = w,
            active = w.id == activeId,
            activity = activity,
            git = git,
            preview = preview,
            lastReadAt = lastReadAt,
            sessionStatus = primary?.status,
            mute = primary?.mute == true,
            host = host,
            projectTag = projectTag,
            modifier = modifier,
            dragModifier = dragModifier,
            interactionSource = interactionSource,
            isDragging = isDragging,
            onClick = { onOpen(w.id) },
            onRename = onRename,
            onKill = onKill,
            onToggleMute = onToggleMute,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            tabDragState = tabDragState,
        )
        if (w.isMultiAgent()) {
            Column(
                Modifier
                    .testTag("workspace-children-${w.id}")
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 8.dp),
            ) {
                for (sid in w.chatSessionIds()) {
                    val childName = names[sid] ?: sid
                    val childWorking = agentState[sid]?.working == true
                    WorkspaceChildRow(
                        name = childName,
                        working = childWorking,
                        onClick = { onOpenSession(w.id, sid) },
                    )
                }
            }
        }
    }
}

/**
 * Labels offered by a workspace row's right-click menu.
 * Extracted so chrome tests can assert rename/mute/archive without driving the desktop context menu.
 */
fun workspaceRowContextLabels(
    mute: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
): List<String> = buildList {
    add("Rename")
    add(if (mute) "Unmute" else "Mute")
    if (canMoveUp) add("Move up")
    if (canMoveDown) add("Move down")
    add("Archive")
}

/**
 * One workspace row: status rail, name, multi-agent mark, message preview, branch —
 * lean by design (SessionRow parity). No per-row avatar. Path is omitted (group header owns it).
 * Preview + lifecycle badge come from the primary session (SessionRow plumbing).
 */
@Composable
fun WorkspaceRow(
    w: WorkspaceDto,
    active: Boolean,
    activity: WorkspaceActivity,
    git: dev.supermux.proto.GitLiteStatusDto? = null,
    preview: LogEntry? = null,
    lastReadAt: String? = null,
    /** Lifecycle status of the primary session (`suspended`, …) — SessionRow badge. */
    sessionStatus: String? = null,
    mute: Boolean = false,
    host: HostView? = null,
    projectTag: String? = null,
    modifier: Modifier = Modifier,
    /** Applied outside the clickable so press-drag can own the gesture (Calvin handle). */
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    /** Elevation while the row is being dragged by the reorder library. */
    isDragging: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    /** When set, this row is a drop target for a tab dragged out of the layout. */
    tabDragState: TabDragState? = null,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val working = activity == WorkspaceActivity.WORKING
    val dropHover = tabDragState?.hoverWorkspaceId == w.id
    val hasUnread = dev.supermux.session.sessionListShowsUnread(
        active = active,
        working = working,
        lastMessageTs = preview?.ts,
        lastReadAt = lastReadAt,
    )

    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val elevation by animateDpAsState(
        if (isDragging) 6.dp else 0.dp,
        label = "workspace-drag-elevation",
    )

    val rowBg = when {
        dropHover -> cs.primary.copy(alpha = 0.18f)
        active -> cs.surfaceContainer
        else -> Color.Transparent
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp)
        .then(if (active && !isDragging) Modifier.softElevation(radius = Radii.md) else Modifier)
        .clip(RoundedCornerShape(6.dp))
        .background(rowBg)
        .onGloballyPositioned { coords ->
            tabDragState?.registerWorkspace(w.id, coords.boundsInRoot())
        }
        .hoverable(interaction)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = !isDragging,
            onClick = onClick,
        )

    Box(
        modifier
            .then(dragModifier)
            .fillMaxWidth(),
    ) {
    ContextMenuArea(
        items = {
            val labels = workspaceRowContextLabels(
                mute = mute,
                canMoveUp = onMoveUp != null,
                canMoveDown = onMoveDown != null,
            )
            labels.map { label ->
                ContextMenuItem(label) {
                    when (label) {
                        "Rename" -> onRename()
                        "Mute", "Unmute" -> onToggleMute()
                        "Move up" -> onMoveUp?.invoke()
                        "Move down" -> onMoveDown?.invoke()
                        "Archive" -> onKill()
                    }
                }
            }
        },
    ) {
        Surface(
            tonalElevation = elevation,
            shadowElevation = elevation,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
        Row(
            rowModifier
                .testTag("workspace_row_${w.id}")
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SessionStatusRail(
                git = git,
                working = working,
                bgOpen = 0,
                unread = hasUnread,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        w.name,
                        color = cs.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (active || hasUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (w.isMultiAgent()) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .testTag("workspace-multiagent-${w.id}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Groups,
                                contentDescription = "multi-agent",
                                tint = cs.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    if (projectTag != null) {
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            projectTag,
                            color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                            fontFamily = MonoFontFamily,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                    val timeStr = relTime(preview?.ts)
                    if (timeStr.isNotEmpty()) {
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            timeStr,
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 10.sp,
                        )
                    }
                }

                if (host != null) {
                    Spacer(Modifier.height(2.dp))
                    HostBadge(host)
                }

                // Status badge — SessionRow parity: non-null and not "active".
                val status = sessionStatus
                if (status != null && status != "active") {
                    val badgeColor = if (status == "suspended") Color(c.warning)
                    else cs.onSurfaceVariant.copy(alpha = 0.6f)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        status,
                        color = badgeColor,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                    )
                } else {
                    Spacer(Modifier.height(Space.xs))
                }

                // Preview: last message from primary session — SessionRow truncation + styling.
                // No workdir fallback: group header owns the path (do not bring path back).
                val previewText = preview?.text?.replace("\n", " ")?.take(80)
                if (previewText != null) {
                    Text(
                        previewText,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Branch — machine content, mono. Hard requirement from design rules.
                val branch = w.branch
                if (!branch.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        branch,
                        color = cs.onSurfaceVariant,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        } // Surface
    } // ContextMenuArea
    } // Box
}

/** Indented child session under a multi-agent workspace. */
@Composable
private fun WorkspaceChildRow(
    name: String,
    working: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionStatusRail(
            git = null,
            working = working,
            bgOpen = 0,
            unread = false,
        )
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

/** "Workspaces" label + search (reserved) + group-by-project toggle — SessionListPanel chrome. */
@Composable
private fun WorkspacesSectionHeader(
    groupByProject: Boolean,
    onToggleGroupByProject: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Workspaces",
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { /* search TBD */ },
            modifier = Modifier
                .size(28.dp)
                .testTag("sidebar_search"),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search workspaces",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        IconButton(
            onClick = onToggleGroupByProject,
            modifier = Modifier
                .size(28.dp)
                .testTag("sidebar_group_toggle"),
        ) {
            Icon(
                Icons.Filled.ViewAgenda,
                contentDescription = if (groupByProject) "Flat list" else "Group by project",
                tint = if (groupByProject) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceSidebarDividerLine(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = cs.onSurface.copy(alpha = 0.14f),
    )
}

/** Sticky bottom bar: theme / usage / devices / settings — SessionListPanel chrome. */
@Composable
private fun WorkspaceSidebarFooter(
    appearance: AppearanceMode,
    onToggleTheme: () -> Unit,
    onUsage: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
) {
    val darkNow = appearance != AppearanceMode.LIGHT
    val themeIcon = if (darkNow) Icons.Filled.LightMode else Icons.Filled.DarkMode
    val themeLabel = if (darkNow) "Switch to light theme" else "Switch to dark theme"
    Column(Modifier.fillMaxWidth()) {
        WorkspaceSidebarDividerLine()
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("sidebar_footer")
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            WorkspaceFooterIcon(themeIcon, themeLabel, "sidebar_footer_theme", onToggleTheme)
            WorkspaceFooterIcon(Icons.Filled.DataUsage, "Usage", "sidebar_footer_usage", onUsage)
            WorkspaceFooterIcon(Icons.Filled.Devices, "Devices", "sidebar_footer_devices", onDevices)
            WorkspaceFooterIcon(Icons.Filled.Settings, "Settings", "sidebar_footer_settings", onSettings)
        }
    }
}

@Composable
private fun WorkspaceFooterIcon(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp).testTag(tag)) {
        Icon(image, contentDescription = label, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
    }
}
