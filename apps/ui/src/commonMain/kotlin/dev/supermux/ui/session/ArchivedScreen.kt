// The one Archived screen for both apps (cluster E6).
//
// Base = desktop's `session/ArchivedScreen.kt`: the project filter, the client-side search, the
// "loading is not empty" spinner, `forceOpenId` (the off-by-default `SM_ARCHIVED_OPEN` headless
// hook), and the read-only transcript over the shared chat Timeline. Android's `MoreScreens.kt`
// Archived section contributes what desktop never had — the archived-WORKSPACE list with Restore
// (grouped by repo root, the sidebar's `useWorkspaces` rule deciding which of the two lists this
// screen is) — plus the Compact chrome: a `TopAppBar` with Back and the filter, on both the list
// and the transcript, and the transcript's own back handler.
//
// Deliberate splits, so neither host regresses:
//   • The header. Desktop paints an in-body title row with the filter chip; a phone (or any width
//     when `standalone`) gets Android's TopAppBar instead, which then owns Back AND the filter.
//   • Row-level Resume. Android's session rows carry a Resume button; desktop's do not (you open
//     the transcript and resume from its header). It is a TOUCH affordance, so it keys on
//     `LocalPointerAvailable` — the same rule E5's swipe-to-delete uses.
//   • Search is the session list's, as on desktop; the workspace list is grouped, not searched.
//
// Only ONE actions holder ([rememberArchivedActions] over a `FleetStore`): desktop's overlay is
// session-only (its `useWorkspaces` is false even though the broker has live workspaces) and its
// Resume closes the whole overlay, so `AppShell` keeps passing the list it already loads to the
// second overload below.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.mergeTimeline
import dev.supermux.chat.parseChatTs
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.ArchivedProject
import dev.supermux.session.archivedProjects
import dev.supermux.session.filterArchivedByProject
import dev.supermux.session.formatWorkdir
import dev.supermux.state.FleetStore
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.TimelineItemRow
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.widgets.SwipeBackHandler
import dev.supermux.ui.widgets.SwipeBackPages
import dev.supermux.ui.widgets.rememberIosBackSwipe
import dev.supermux.workspace.ProjectRef
import dev.supermux.workspace.WorkspaceGroup
import dev.supermux.workspace.groupArchivedWorkspaces
import kotlin.time.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Material's minimum touch target, applied to the row actions when there is no pointer. */
private val TouchTargetMin = 48.dp

/**
 * Pure client-side search predicate for the archived list: a session matches the (trimmed,
 * case-insensitive) [query] if it appears in the session name, its workdir, or its repo root. A
 * blank query matches everything. Extracted (and unit-tested) so the filtering rule is verifiable
 * without spinning up the composable — mirrors the launcher's extracted pure decisions.
 */
fun archivedMatchesQuery(dto: ArchivedDto, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (dto.name.lowercase().contains(q)) return true
    if (dto.workdir.lowercase().contains(q)) return true
    if ((dto.repo_root ?: "").lowercase().contains(q)) return true
    return false
}

/**
 * "now" / "5m" / "3h" / "2d" for a broker timestamp — both apps' `relTime`, on the shared
 * [parseChatTs] rather than `java.time.Instant.parse` (which `:ui` commonMain cannot have). The
 * parser additionally accepts a bare epoch, which the ISO-only originals rejected as "".
 */
internal fun archivedRelTime(ts: String?, now: Long = Clock.System.now().toEpochMilliseconds()): String {
    val epochMs = parseChatTs(ts) ?: return ""
    val diffSec = (now - epochMs) / 1000L
    return when {
        diffSec < 60L -> "now"
        diffSec < 3600L -> "${diffSec / 60}m"
        diffSec < 86400L -> "${diffSec / 3600}h"
        else -> "${diffSec / 86400}d"
    }
}

// ─── Actions holder ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything the Archived screen needs from a store. [archivedWorkspaces] and [liveWorkspaces] are
 * the workspace half (Android's list; the sidebar's own rule applies — no LIVE
 * workspaces means the broker predates them, so fall back to the session archive), the rest is the
 * session half both hosts share.
 */
@Immutable
class ArchivedActions(
    /** Archived workspaces — Android's list. */
    val archivedWorkspaces: StateFlow<List<WorkspaceDto>>,
    /** LIVE workspaces: empty means the broker has none, so fall back to the session archive. */
    val liveWorkspaces: StateFlow<List<WorkspaceDto>>,
    val loadArchivedSessions: suspend () -> List<ArchivedDto>,
    val loadLogs: suspend (String) -> List<LogEntry>,
    val resume: suspend (String) -> Unit,
    val restoreWorkspace: (String) -> Unit,
)

/** [ArchivedActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberArchivedActions(fleet: FleetStore): ArchivedActions = remember(fleet) {
    ArchivedActions(
        archivedWorkspaces = fleet.archivedWorkspaces,
        liveWorkspaces = fleet.workspaces,
        loadArchivedSessions = { fleet.archived() },
        loadLogs = { fleet.archivedLogs(it) },
        resume = { id -> fleet.resume(id) },
        restoreWorkspace = { id -> fleet.restoreWorkspace(id) },
    )
}

/** Archived over a store — Android's `Route.Archived`. */
@Composable
fun ArchivedScreen(
    actions: ArchivedActions,
    home: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
    forceOpenId: String? = null,
    onForceOpenConsumed: () -> Unit = {},
    projects: List<ProjectRef> = emptyList(),
    workspaceHost: (WorkspaceDto) -> String = { "" },
    loadProjectImage: suspend (ProjectRef) -> ByteArray? = { null },
    /** The app's shared project image cache (null → a screen-local one). */
    projectImageCache: ProjectImageCache? = null,
    /**
     * "Project settings…" on a persistent project's group header — the only way to reach an
     * archived-only project's settings, since the live sidebar hides it. Null hides the ⋮.
     */
    onProjectSettings: ((ProjectRef) -> Unit)? = null,
) {
    val workspaces by actions.archivedWorkspaces.collectAsState()
    val live by actions.liveWorkspaces.collectAsState()
    val useWorkspaces = live.isNotEmpty()
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ArchivedDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(actions, useWorkspaces) {
        if (!useWorkspaces) {
            loading = true
            sessions = actions.loadArchivedSessions()
            loading = false
        }
    }
    ArchivedScreen(
        archived = sessions,
        home = home,
        onBack = onBack,
        onResume = { id -> scope.launch { actions.resume(id) } },
        loadLogs = actions.loadLogs,
        loading = loading,
        forceOpenId = forceOpenId,
        onForceOpenConsumed = onForceOpenConsumed,
        workspaces = workspaces,
        onRestore = actions.restoreWorkspace,
        useWorkspaces = useWorkspaces,
        modifier = modifier,
        topBarShown = topBarShown,
        standalone = standalone,
        projects = projects,
        workspaceHost = workspaceHost,
        loadProjectImage = loadProjectImage,
        projectImageCache = projectImageCache,
        onProjectSettings = onProjectSettings,
    )
}

/**
 * The archived screen over lists the caller owns (desktop's AppShell loads `app.archived()` when
 * the overlay opens and passes it in whole). Tapping a session row opens [ArchivedChatView]
 * (internal nav via `openedId`, list ⇄ chat); [onResume] resumes an archived session and the
 * caller closes the whole overlay; [loadLogs] fetches a session's read-only transcript.
 *
 * When [useWorkspaces] the screen lists archived WORKSPACES with Restore instead — the same rule
 * the sidebar uses, so a broker that reports live workspaces never shows the session archive.
 *
 * Escape mirrors the back stack: from the chat view it returns to the list; from the list it calls
 * [onBack]. Handled on a focusable root so it works without a focused text field, and via
 * onPreviewKeyEvent so it wins even while the search field is focused. The system back GESTURE is
 * handled the same way, but only while the transcript is open — this screen owns that sub-page, so
 * its handler composes below whatever pushed the screen and runs first (cluster E5's rule).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArchivedScreen(
    archived: List<ArchivedDto>,
    home: String,
    onBack: () -> Unit,
    onResume: (String) -> Unit,
    loadLogs: suspend (String) -> List<LogEntry>,
    // True while the caller's `app.archived()` fetch is still in flight — the list shows a spinner
    // (not the "No archived sessions." empty text) until it resolves, so a slow fetch never flashes
    // an empty state (mirrors Android's `loading` flag + ArchivedChatView's own spinner).
    loading: Boolean = false,
    // One-shot "open this archived session's read-only transcript" request (an id from [archived]),
    // set by the off-by-default `SM_ARCHIVED_OPEN` headless hook (via ShellUiState.forceArchivedOpenFor,
    // M4e-T3 live verification) so the chat view renders without a click. Consumed (→ [onForceOpenConsumed])
    // the same run it's applied, so it never re-fires on an unrelated recomposition. Null/no-op in
    // normal operation.
    forceOpenId: String? = null,
    onForceOpenConsumed: () -> Unit = {},
    /** Archived workspaces (Android); only read when [useWorkspaces]. */
    workspaces: List<WorkspaceDto> = emptyList(),
    onRestore: (String) -> Unit = {},
    /** Same rule as the sidebar: empty live workspaces → session-archive fallback. */
    useWorkspaces: Boolean = false,
    modifier: Modifier = Modifier,
    topBarShown: Boolean = false,
    standalone: Boolean = false,
    /** Host-qualified project catalog: archived workspaces group under their persistent project. */
    projects: List<ProjectRef> = emptyList(),
    /** Host record id owning a workspace (same id space as [ProjectRef.hostId]). */
    workspaceHost: (WorkspaceDto) -> String = { "" },
    loadProjectImage: suspend (ProjectRef) -> ByteArray? = { null },
    /** The app's shared project image cache (null → a screen-local one). */
    projectImageCache: ProjectImageCache? = null,
    /**
     * "Project settings…" on a persistent project's group header — the only way to reach an
     * archived-only project's settings, since the live sidebar hides it. Null hides the ⋮.
     */
    onProjectSettings: ((ProjectRef) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val barOwned = (standalone || compact) && !topBarShown

    // Internal nav: tapping a row opens a read-only chat view of that session.
    var openedId by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var resumedIds by remember { mutableStateOf(setOf<String>()) }
    var restoredIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(forceOpenId) {
        if (forceOpenId != null) {
            openedId = forceOpenId
            onForceOpenConsumed()
        }
    }

    val sessionProjects = remember(archived, home) { archivedProjects(archived, home) }
    val groups = remember(workspaces, home, projects, workspaceHost) {
        groupArchivedWorkspaces(workspaces, home, projects, workspaceHost)
    }
    val cachedProjectImage = rememberCachedProjectImageLoader(loadProjectImage, projectImageCache)
    // Clear the filter if the selected project no longer has anything archived under it.
    LaunchedEffect(sessionProjects, groups, useWorkspaces) {
        val keys = if (useWorkspaces) groups.map { it.key } else sessionProjects.map { it.key }
        if (selectedProject != null && keys.none { it == selectedProject }) {
            selectedProject = null
        }
    }

    val opened = openedId?.let { id -> archived.firstOrNull { it.id == id } }

    // The transcript's own back, registered BELOW whatever pushed this screen — so the gesture
    // returns to the list instead of popping the whole route.
    val openedSwipe = rememberIosBackSwipe()
    SwipeBackHandler(enabled = opened != null, swipe = openedSwipe) { openedId = null }

    // Keep focus on the root across list ⇄ chat navigation (a row click moves focus to the row),
    // so Escape is always caught by the onPreviewKeyEvent below — even with no field focused.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(openedId) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh)
            .testTag("archived_root")
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    if (openedId != null) openedId = null else onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        // A pushed transcript over the list; on iOS an edge swipe drags it back off the list.
        SwipeBackPages(
            pushed = opened != null,
            swipe = openedSwipe,
            pageBackground = cs.surfaceContainerHigh,
            under = {
                ArchivedList(
                    archived = archived,
                    loading = loading,
                    projects = sessionProjects,
                    groups = groups,
                    useWorkspaces = useWorkspaces,
                    home = home,
                    selectedProject = selectedProject,
                    onSelectProject = { selectedProject = it },
                    filterOpen = filterOpen,
                    onFilterOpenChange = { filterOpen = it },
                    query = query,
                    onQueryChange = { query = it },
                    onOpen = { openedId = it },
                    onResume = { id ->
                        onResume(id)
                        resumedIds = resumedIds + id
                    },
                    resumedIds = resumedIds,
                    onRestore = { id ->
                        onRestore(id)
                        restoredIds = restoredIds + id
                    },
                    restoredIds = restoredIds,
                    barOwned = barOwned,
                    onBack = onBack,
                    loadProjectImage = cachedProjectImage,
                    onProjectSettings = onProjectSettings,
                )
            },
        ) {
            if (opened != null) {
                ArchivedChatView(
                    sessionId = opened.id,
                    name = opened.name,
                    loadLogs = loadLogs,
                    resumed = opened.id in resumedIds,
                    onResume = {
                        onResume(opened.id)
                        resumedIds = resumedIds + opened.id
                    },
                    onBack = { openedId = null },
                    barOwned = barOwned,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedList(
    archived: List<ArchivedDto>,
    loading: Boolean,
    projects: List<ArchivedProject>,
    groups: List<WorkspaceGroup>,
    useWorkspaces: Boolean,
    home: String,
    selectedProject: String?,
    onSelectProject: (String?) -> Unit,
    filterOpen: Boolean,
    onFilterOpenChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onResume: (String) -> Unit,
    resumedIds: Set<String>,
    onRestore: (String) -> Unit,
    restoredIds: Set<String>,
    barOwned: Boolean,
    onBack: () -> Unit,
    loadProjectImage: suspend (ProjectRef) -> ByteArray?,
    onProjectSettings: ((ProjectRef) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val visible = remember(archived, selectedProject, query) {
        filterArchivedByProject(archived, selectedProject).filter { archivedMatchesQuery(it, query) }
    }
    val visibleGroups = remember(groups, selectedProject) {
        groups.filter { selectedProject == null || it.key == selectedProject }
    }
    val filterReady = if (useWorkspaces) groups.isNotEmpty() else archived.isNotEmpty()

    val filterMenu: @Composable () -> Unit = {
        DropdownMenu(expanded = filterOpen, onDismissRequest = { onFilterOpenChange(false) }) {
            DropdownMenuItem(
                text = { Text("All projects") },
                onClick = { onSelectProject(null); onFilterOpenChange(false) },
                trailingIcon = if (selectedProject == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
            )
            if (useWorkspaces) {
                groups.forEach { g ->
                    DropdownMenuItem(
                        text = { Text("${g.label}  (${g.workspaces.size})") },
                        onClick = { onSelectProject(g.key); onFilterOpenChange(false) },
                        modifier = Modifier.testTag("archived_project_${g.key}"),
                        trailingIcon = if (selectedProject == g.key) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                    )
                }
            } else {
                projects.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.label}  (${p.count})") },
                        onClick = { onSelectProject(p.key); onFilterOpenChange(false) },
                        modifier = Modifier.testTag("archived_project_${p.key}"),
                        trailingIcon = if (selectedProject == p.key) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(bodyModifier.fillMaxSize().testTag("archived_screen")) {
            // ── Header: title + project filter — desktop's, when no top bar owns them ──
            if (!barOwned) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Archived", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (filterReady) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radii.sm))
                                    .clickable { onFilterOpenChange(true) }
                                    .padding(horizontal = Space.sm, vertical = Space.xs)
                                    .testTag("archived_filter"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = "Filter by project",
                                    tint = if (selectedProject != null) cs.primary else cs.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    selectedProject?.let { key ->
                                        (if (useWorkspaces) groups.firstOrNull { it.key == key }?.label
                                        else projects.firstOrNull { it.key == key }?.label)
                                    } ?: "All projects",
                                    color = if (selectedProject != null) cs.primary else cs.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            filterMenu()
                        }
                    }
                }
            }

            // ── Search field (client-side name/path filter) — the session list's ──
            if (!useWorkspaces) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text("Search archived…", color = cs.onSurfaceVariant) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg)
                        .testTag("archived_search"),
                )
                Spacer(Modifier.size(Space.sm))
            }

            // ── The list ──
            Box(Modifier.fillMaxSize()) {
                when {
                    useWorkspaces && groups.isEmpty() -> Text(
                        "No archived workspaces.",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    useWorkspaces -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
                        visibleGroups.forEach { g ->
                            item(key = "hdr:${g.key}") {
                                Row(
                                    Modifier
                                        .padding(horizontal = Space.sm, vertical = Space.sm)
                                        .testTag("archived_workspace_group_${g.key}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                ) {
                                    val ref = g.projectRef()
                                    if (ref?.project?.imageId != null) {
                                        ProjectImage(ref, loadProjectImage, size = 20.dp) {}
                                    }
                                    Text(
                                        g.label,
                                        color = cs.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    )
                                    if (ref != null && onProjectSettings != null) {
                                        // Settings only: the archive has no order of its own to move in.
                                        ProjectHeaderMenu(
                                            groupKey = g.key,
                                            label = g.label,
                                            onSettings = { onProjectSettings(ref) },
                                            onMoveUp = null,
                                            onMoveDown = null,
                                        )
                                    }
                                }
                            }
                            items(g.workspaces, key = { it.id }) { w ->
                                ArchivedWorkspaceRow(
                                    workspace = w,
                                    home = home,
                                    restored = w.id in restoredIds,
                                    onRestore = { onRestore(w.id) },
                                )
                                HorizontalDivider(color = cs.outlineVariant)
                            }
                        }
                    }
                    loading -> CircularProgressIndicator(
                        color = cs.primary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    archived.isEmpty() -> Text(
                        "No archived sessions.",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    visible.isEmpty() -> Text(
                        "No matches.",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
                        items(visible, key = { it.id }) { session ->
                            ArchivedRow(
                                session = session,
                                home = home,
                                resumed = session.id in resumedIds,
                                onOpen = { onOpen(session.id) },
                                onResume = { onResume(session.id) },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    if (barOwned) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Archived", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("archived_back")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    actions = {
                        if (filterReady) {
                            Box {
                                IconButton(
                                    onClick = { onFilterOpenChange(true) },
                                    modifier = Modifier.testTag("archived_filter"),
                                ) {
                                    Icon(
                                        Icons.Filled.FilterList,
                                        contentDescription = "Filter by project",
                                        tint = if (selectedProject != null) cs.primary else cs.onSurface,
                                    )
                                }
                                filterMenu()
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            containerColor = cs.background,
        ) { padding -> body(Modifier.padding(padding)) }
    } else {
        body(Modifier)
    }
}

@Composable
private fun ArchivedRow(
    session: ArchivedDto,
    home: String,
    resumed: Boolean,
    onOpen: () -> Unit,
    onResume: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val touch = !LocalPointerAvailable.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("archived_row_${session.id}")
            .padding(horizontal = Space.sm, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                formatWorkdir(session.repo_root ?: session.workdir, home),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        val killed = archivedRelTime(session.killed_at)
        if (killed.isNotEmpty()) {
            Text(killed, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        // Android's per-row Resume — a thumb affordance. With a pointer you open the transcript
        // and resume from its header, which is what desktop has always done.
        if (touch) {
            TextButton(
                onClick = onResume,
                enabled = !resumed,
                modifier = Modifier
                    .sizeIn(minWidth = TouchTargetMin, minHeight = TouchTargetMin)
                    .testTag("archived_row_resume_${session.id}"),
            ) {
                Text(
                    if (resumed) "Resumed" else "Resume",
                    color = if (resumed) cs.onSurfaceVariant else cs.primary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun ArchivedWorkspaceRow(
    workspace: WorkspaceDto,
    home: String,
    restored: Boolean,
    onRestore: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val touch = !LocalPointerAvailable.current
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("archived_workspace_row_${workspace.id}")
            .padding(horizontal = Space.sm, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(workspace.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                formatWorkdir(workspace.repoRoot ?: workspace.workdir, home),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            val ended = archivedRelTime(workspace.archivedAt)
            if (ended.isNotEmpty()) {
                Text("Archived $ended", color = cs.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        TextButton(
            onClick = onRestore,
            enabled = !restored,
            modifier = Modifier
                .then(if (touch) Modifier.sizeIn(minWidth = TouchTargetMin, minHeight = TouchTargetMin) else Modifier)
                .testTag("archived_restore_${workspace.id}"),
        ) {
            Text(
                if (restored) "Restored" else "Restore",
                color = if (restored) cs.onSurfaceVariant else cs.primary,
                fontSize = 13.sp,
            )
        }
    }
}

// ─── ArchivedChatView (read-only transcript of an archived session) ────────────

/**
 * The read-only transcript view: loads [loadLogs] on open and renders the shared chat Timeline
 * (mergeTimeline over the logs + TimelineItemRow, onOpenFile a no-op — nothing to edit) with NO
 * composer. The header carries the session name + a Resume button; Resume calls [onResume] (the
 * caller resumes then closes the whole overlay, and the button reads "Resumed" until it does).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchivedChatView(
    sessionId: String,
    name: String,
    loadLogs: suspend (String) -> List<LogEntry>,
    onResume: () -> Unit,
    onBack: () -> Unit,
    resumed: Boolean = false,
    barOwned: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var messages by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        messages = loadLogs(sessionId)
        loading = false
    }

    val resumeButton: @Composable () -> Unit = {
        TextButton(onClick = onResume, enabled = !resumed, modifier = Modifier.testTag("archived_resume")) {
            Text(
                if (resumed) "Resumed" else "Resume",
                color = if (resumed) cs.onSurfaceVariant else cs.primary,
                fontSize = 13.sp,
            )
        }
    }

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        Box(bodyModifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                messages.isEmpty() -> Text(
                    "No messages.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    // Read-only: reuse the chat timeline composables; no composer.
                    val timelineItems = remember(messages) { mergeTimeline(messages, emptyList()) }
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md),
                        verticalArrangement = Arrangement.spacedBy(Space.lg),
                    ) {
                        items(timelineItems) { item ->
                            TimelineItemRow(item, onOpenFile = {})
                        }
                    }
                }
            }
        }
    }

    if (barOwned) {
        Scaffold(
            modifier = Modifier.testTag("archived_chat"),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(name, color = cs.onSurface, fontSize = 16.sp, maxLines = 1)
                            Text("archived", color = cs.onSurfaceVariant, fontSize = 11.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("archived_chat_back")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    actions = { resumeButton() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            containerColor = cs.background,
        ) { padding -> body(Modifier.padding(padding)) }
    } else {
        Column(Modifier.fillMaxSize().testTag("archived_chat")) {
            // ── Header: back + name/"archived" + Resume ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("archived_chat_back")) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(name, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("archived", color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
                resumeButton()
            }
            body(Modifier)
        }
    }
}
