package dev.supermux.android.session

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.supermux.session.moveId
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.android.theme.softElevation
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
import dev.supermux.session.inferHomeDir
import dev.supermux.session.groupSessions
import dev.supermux.session.effectiveUserStatus
import dev.supermux.session.sectionKey
import dev.supermux.session.projectLabel
import dev.supermux.session.combinedTaskSessions
import dev.supermux.session.buildTaskSections
import dev.supermux.session.TaskSection
import dev.supermux.session.SectionKey
import dev.supermux.net.ArchivedDto
import kotlinx.coroutines.launch

/** Produces a human-readable relative time string from an ISO-8601 timestamp string. */
fun relTime(ts: String?): String {
    if (ts == null) return ""
    return try {
        val epochMs = java.time.Instant.parse(ts).toEpochMilli()
        val diffMs = System.currentTimeMillis() - epochMs
        val diffSec = diffMs / 1000L
        when {
            diffSec < 60L -> "now"
            diffSec < 3600L -> "${diffSec / 60}m"
            diffSec < 86400L -> "${diffSec / 3600}h"
            else -> "${diffSec / 86400}d"
        }
    } catch (_: Exception) {
        ""
    }
}

/** Returns the drawable resource ID for the given agent name, or null if not recognised. */
private fun agentDrawableRes(agent: String?): Int? = when (agent?.lowercase()) {
    "claude" -> R.drawable.agent_claude
    "codex"  -> R.drawable.agent_codex
    "cursor" -> R.drawable.agent_cursor
    "grok"   -> R.drawable.agent_grok
    else     -> null
}

// Collapsed project-group state, persisted across launches. Keyed by each group's
// `workdir` (the PA group uses the "__pas__" sentinel), mirroring the iOS session
// list's `cmux:collapsed-paths` UserDefaults set so the two platforms behave alike.
private const val COLLAPSE_PREFS = "cmux-session-list"
private const val COLLAPSE_KEY = "collapsed-paths"

private fun loadCollapsedPaths(ctx: Context): Set<String> =
    ctx.getSharedPreferences(COLLAPSE_PREFS, Context.MODE_PRIVATE)
        .getStringSet(COLLAPSE_KEY, emptySet())
        ?.toSet() ?: emptySet()

private fun saveCollapsedPaths(ctx: Context, paths: Set<String>) {
    ctx.getSharedPreferences(COLLAPSE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(COLLAPSE_KEY, paths)
        .apply()
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SessionAvatar(
    name: String,
    agent: String? = null,
    modifier: Modifier = Modifier,
    sessionId: String? = null,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val cs = MaterialTheme.colorScheme
    val sharedModifier = if (sessionId != null && sharedScope != null && animScope != null) {
        with(sharedScope) {
            modifier.sharedElement(
                rememberSharedContentState(key = "avatar-$sessionId"),
                animatedVisibilityScope = animScope,
            )
        }
    } else modifier

    val logoRes = agentDrawableRes(agent)
    if (logoRes != null) {
        Box(
            sharedModifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF7F4EE))
                .border(1.dp, cs.outline.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(logoRes),
                contentDescription = agent,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        Box(
            sharedModifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary)
                .border(1.dp, cs.outline.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val initials = name.take(2).uppercase()
            Text(initials, color = cs.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

// Fix 3: path-group header with rotating ChevronDown matching the web app.
// Tappable to collapse/expand the group (parity with the iOS session list); the
// chevron rotation animates and the whole row is a ≥48dp expand/collapse target.
@Composable
fun PathGroupHeader(
    label: String,
    count: Int,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        label = "groupChevronRotation",
    )
    val clickable = if (onToggle != null) {
        Modifier
            .clickable(
                role = Role.Button,
                onClickLabel = if (collapsed) "Expand" else "Collapse",
            ) { haptic(HapticKind.Tick); onToggle() }
            .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" }
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
        Text(
            label,
            color = cs.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            "$count",
            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry? = null,
    working: Boolean = false,
    bgOpen: Int = 0,
    hostBadge: dev.supermux.android.host.HostView? = null,
    projectTag: String? = null,
    /**
     * Applied as an outer Box around SwipeToDismissBox (must NOT be the swipe box's own
     * modifier — that chains under anchoredDraggable and never receives the long-press).
     * Pass [longPressDraggableHandle] + shared [interactionSource] from ReorderableItem.
     */
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onResume: () -> Unit = {},
    /** Elevation while the row is being dragged by the reorder library. */
    isDragging: Boolean = false,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val hasUnread = !active && preview?.direction == "inbound"
    val section = s.sectionKey()
    val rowInteraction = interactionSource ?: remember { MutableInteractionSource() }

    val elevation by androidx.compose.animation.core.animateDpAsState(
        if (isDragging) 6.dp else 0.dp,
        label = "session-drag-elevation",
    )

    // Material3 SwipeToDismissBox: one primary action per edge (Android pattern).
    // End→Start (swipe left): Settle / Delete
    // Start→End (swipe right): Mute (in progress) / Resume (settled) — snaps back after action
    // Threshold is intentionally high: 0.35 fired kill/mute on light flicks.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    when (section) {
                        SectionKey.IN_PROGRESS, SectionKey.DRAFT -> {
                            haptic(HapticKind.Confirm)
                            onKill()
                        }
                        SectionKey.SETTLED -> Unit
                    }
                    false // keep row; kill uses confirm dialog / list update
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    when (section) {
                        SectionKey.IN_PROGRESS -> {
                            haptic(HapticKind.Tick)
                            onToggleMute()
                        }
                        SectionKey.DRAFT -> {
                            haptic(HapticKind.Tick)
                            onClick() // open draft
                        }
                        SectionKey.SETTLED -> {
                            haptic(HapticKind.Tick)
                            onResume()
                        }
                    }
                    false // snap back after secondary action
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        // ~55% of row width before commit (default Material is 56.dp; fraction felt too eager at 0.35).
        positionalThreshold = { total -> total * 0.55f },
    )

    val enableStartToEnd = true
    val enableEndToStart = section != SectionKey.SETTLED
    // While reordering, disable horizontal swipe so Calvin long-press owns the pointer.
    val swipeGestures = !isDragging && (enableStartToEnd || enableEndToStart)

    // longPress must wrap SwipeToDismissBox, not sit under its anchoredDraggable.
    // Otherwise horizontal swipe steals the pointer and drag never starts.
    Box(
        modifier = dragModifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = enableStartToEnd,
        enableDismissFromEndToStart = enableEndToStart,
        gesturesEnabled = swipeGestures,
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val color = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> when (section) {
                    SectionKey.IN_PROGRESS -> Color(c.warning).copy(alpha = 0.35f)
                    else -> cs.primary.copy(alpha = 0.28f)
                }
                SwipeToDismissBoxValue.EndToStart -> when (section) {
                    SectionKey.DRAFT -> cs.error.copy(alpha = 0.35f)
                    else -> cs.primary.copy(alpha = 0.35f)
                }
                else -> Color.Transparent
            }
            val icon = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> when (section) {
                    SectionKey.IN_PROGRESS ->
                        if (s.mute == true) R.drawable.ic_volume_2 else R.drawable.ic_volume_x
                    SectionKey.DRAFT -> R.drawable.ic_pencil
                    SectionKey.SETTLED -> R.drawable.ic_play
                }
                SwipeToDismissBoxValue.EndToStart -> when (section) {
                    SectionKey.DRAFT -> R.drawable.ic_trash
                    else -> R.drawable.ic_check
                }
                else -> null
            }
            val label = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> when (section) {
                    SectionKey.IN_PROGRESS -> if (s.mute == true) "Unmute" else "Mute"
                    SectionKey.DRAFT -> "Edit"
                    SectionKey.SETTLED -> "Activate"
                }
                SwipeToDismissBoxValue.EndToStart -> when (section) {
                    SectionKey.DRAFT -> "Delete"
                    else -> "Settle"
                }
                else -> ""
            }
            val align = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = align,
            ) {
                if (icon != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = label,
                            tint = cs.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(label, color = cs.onSurface, fontSize = 11.sp)
                    }
                }
            }
        },
    ) {
        Surface(
            tonalElevation = elevation,
            shadowElevation = elevation,
            shape = RoundedCornerShape(Radii.md),
            color = if (active) cs.surfaceContainer else cs.surfaceContainerHigh,
            onClick = { haptic(HapticKind.Tick); onClick() },
            interactionSource = rowInteraction,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("session_row_${s.id}")
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (s.effectiveUserStatus() == "draft") {
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil),
                        contentDescription = "draft",
                        tint = cs.primary.copy(alpha = 0.75f),
                        modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
                    )
                } else {
                    SessionStatusRail(
                        git = null,
                        working = working,
                        bgOpen = bgOpen,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.name,
                            color = cs.onSurface,
                            fontSize = 15.sp,
                            fontWeight = if (active || hasUnread) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
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
                        if (hostBadge != null) {
                            Spacer(Modifier.width(Space.sm))
                            dev.supermux.android.host.HostBadge(hostBadge)
                        }
                        val timeStr = relTime(preview?.ts)
                        if (timeStr.isNotEmpty()) {
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                timeStr,
                                color = cs.onSurfaceVariant,
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    val userSt = s.effectiveUserStatus()
                    val status = s.status
                    when {
                        userSt == "draft" -> {
                            Spacer(Modifier.height(2.dp))
                            Text("draft", color = cs.primary, fontFamily = MonoFontFamily, fontSize = 10.sp)
                        }
                        status != null && status != "active" && status != "archived" -> {
                            val badgeColor = if (status == "suspended") Color(c.warning)
                            else cs.onSurfaceVariant.copy(alpha = 0.6f)
                            Spacer(Modifier.height(2.dp))
                            Text(status, color = badgeColor, fontFamily = MonoFontFamily, fontSize = 10.sp)
                        }
                        else -> Spacer(Modifier.height(Space.xs))
                    }
                    val previewText = preview?.text?.replace("\n", " ")?.take(80)
                    if (previewText != null) {
                        Text(
                            previewText,
                            color = cs.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            formatWorkdir(s.workdir, inferHomeDir(s.workdir)),
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    } // Box(dragModifier)
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<SessionInfo>,
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    agentState: Map<String, dev.supermux.proto.AgentStatus?> = emptyMap(),
    onNewSession: () -> Unit = {},
    loadProjects: suspend () -> List<String> = { emptyList() },
    validatePath: suspend (String) -> dev.supermux.net.PathValidation? = { null },
    onNavigate: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onKill: (String) -> Unit = {},
    onMute: (String, Boolean) -> Unit = { _, _ -> },
    /** Archived sessions folded into Settled (web task-list parity). */
    archived: List<ArchivedDto> = emptyList(),
    onResume: (String) -> Unit = {},
    onOpenDraft: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    // ── Multi-host (spec §5). All default-empty so single-host callers render exactly as before. ──
    hosts: List<dev.supermux.android.host.HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
    onRenameHost: (recordId: String, name: String) -> Unit = { _, _ -> },
    onForgetHost: (recordId: String) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Chips + badges appear only with 2+ paired hosts — the common single-host case stays uncluttered.
    val multiHost = hosts.size >= 2
    val hostByRecord = remember(hosts) { hosts.associateBy { it.recordId } }
    val offlineIds = remember(hosts) { hosts.filter { !it.online }.map { it.recordId }.toSet() }

    // Filter (a recordId or null = All), then split live sessions from the offline hosts' cached ones.
    val shown = if (multiHost) dev.supermux.android.host.filterSessions(sessions, sessionHost, hostFilter) else sessions
    val onlineSessions = if (multiHost) shown.filter { (sessionHost[it.id] ?: "") !in offlineIds } else shown

    // Infer the home dir from the sessions' workdirs (iOS `BrokerSession.grouped` parity) instead of
    // the hardcoded DevConfig.HOME placeholder, so "~/…" abbreviation in group labels matches iOS.
    val effectiveHome = inferHomeDir(sessions.firstOrNull()?.workdir) ?: home
    val lastTs: (SessionInfo) -> String = { lastBySession[it.id]?.ts ?: "" }
    val groups = remember(onlineSessions, effectiveHome, lastBySession, archived) {
        groupSessions(onlineSessions, effectiveHome, lastTs, archived = archived)
    }
    val flatSections = remember(onlineSessions, lastBySession, archived) {
        buildTaskSections(combinedTaskSessions(onlineSessions, archived), lastTs)
    }
    // Offline hosts (greyed groups with last-seen), honoring the filter.
    val offlineGroups = if (multiHost) {
        hosts.filter { !it.online && (hostFilter == null || hostFilter == it.recordId) }
            .map { h -> h to shown.filter { sessionHost[it.id] == h.recordId } }
    } else emptyList()

    var menuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<SessionInfo?>(null) }

    // Which project groups are collapsed (keyed by group workdir), restored from and
    // written back to SharedPreferences so the choice survives app restarts.
    val ctx = LocalContext.current
    var collapsedPaths by remember { mutableStateOf(loadCollapsedPaths(ctx)) }
    // Group-by-project toggle (web layout.groupByProject). Default false — flat list.
    var groupByProject by remember {
        mutableStateOf(
            ctx.getSharedPreferences(COLLAPSE_PREFS, Context.MODE_PRIVATE)
                .getBoolean("group-by-project", false),
        )
    }
    var settledExpanded by remember { mutableStateOf(setOf<String>()) }
    var flatSettledExpanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // Native Compose reorder (sh.calvin.reorderable) — elevates the item, auto-scrolls,
    // animates neighbors. Used by production apps (Pocket Casts, ProtonVPN, etc.).
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // Only reorder within the same flat section: keys are "flat:<sessionId>".
        // Do not use from.index/to.index — headers/settled/offline items shift absolute indices.
        if (!fromKey.startsWith("flat:") || !toKey.startsWith("flat:")) return@rememberReorderableLazyListState
        val fromId = fromKey.removePrefix("flat:")
        val toId = toKey.removePrefix("flat:")
        val section = flatSections.find { sec ->
            sec.key != SectionKey.SETTLED && sec.sessions.any { it.id == fromId }
        } ?: return@rememberReorderableLazyListState
        if (section.sessions.none { it.id == toId }) return@rememberReorderableLazyListState
        val ids = section.sessions.map { it.id }
        val fi = ids.indexOf(fromId)
        val ti = ids.indexOf(toId)
        if (fi < 0 || ti < 0 || fi == ti) return@rememberReorderableLazyListState
        // Must finish list mutation before returning (library contract).
        onReorder(moveId(ids, fi, ti))
    }

    fun openSession(s: SessionInfo) {
        when (s.sectionKey()) {
            SectionKey.DRAFT -> onOpenDraft(s.id)
            SectionKey.SETTLED -> onOpen(s.id)
            SectionKey.IN_PROGRESS -> onOpen(s.id)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.mux_logo),
                            contentDescription = "Supermux logo",
                            tint = cs.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "supermux",
                            color = cs.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("list_overflow"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = "Actions",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        // Always-reachable add-host entry (the filter row's `+` chip is hidden until a
                        // 2nd host exists, so the very first extra host is added from here — spec §5).
                        DropdownMenuItem(
                            text = { Text("Add host") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_plus),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_add_host"),
                            onClick = { menuExpanded = false; onAddHost() },
                        )
                        DropdownMenuItem(
                            text = { Text("Archived") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_archive),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_archived"),
                            onClick = { menuExpanded = false; onNavigate("archived") },
                        )
                        DropdownMenuItem(
                            text = { Text("Usage") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bar_chart),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_usage"),
                            onClick = { menuExpanded = false; onNavigate("usage") },
                        )
                        DropdownMenuItem(
                            text = { Text("Proxies") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_network),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_proxies"),
                            onClick = { menuExpanded = false; onNavigate("proxies") },
                        )
                        DropdownMenuItem(
                            text = { Text("Appearance") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_monitor),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_appearance"),
                            onClick = { menuExpanded = false; onNavigate("appearance") },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_settings"),
                            onClick = { menuExpanded = false; onNavigate("settings") },
                        )
                        DropdownMenuItem(
                            text = { Text("Devices") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_smartphone),
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("nav_devices"),
                            onClick = { menuExpanded = false; onNavigate("devices") },
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = cs.surfaceContainerLow,
                ),
            )
        },
        floatingActionButton = {
            // Fix 4: circular FAB — CircleShape instead of rounded-square
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
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = "New session",
                    tint = cs.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        containerColor = cs.surfaceContainerHigh,
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
        LazyColumn(
            state = listState,
                        modifier = Modifier
                .testTag("sessions_list")
                .fillMaxSize()
                .background(cs.surfaceContainerHigh),
        ) {
            if (multiHost) {
                item(key = "host_filter_chips") {
                    dev.supermux.android.host.HostFilterChips(
                        hosts = hosts,
                        sessions = sessions,
                        sessionHost = sessionHost,
                        selected = hostFilter,
                        onSelect = onHostFilter,
                        onAddHost = onAddHost,
                        onRenameHost = onRenameHost,
                        onForgetHost = onForgetHost,
                    )
                }
            }

            item(key = "new_session_row") {
                NewSessionListRow(
                    onClick = onNewSession,
                    modifier = Modifier.testTag("new_session_row"),
                )
            }

            // Web/iOS parity: full-width row, label left, switch right (not a FilterChip).
            item(key = "group_by_toggle") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("group_by_project")
                        .semantics {
                            stateDescription =
                                if (groupByProject) "Group by project on" else "Group by project off"
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
                            painter = painterResource(R.drawable.ic_folder_open),
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
                        checked = groupByProject,
                        onCheckedChange = { checked ->
                            groupByProject = checked
                            ctx.getSharedPreferences(COLLAPSE_PREFS, Context.MODE_PRIVATE)
                                .edit().putBoolean("group-by-project", checked).apply()
                        },
                        modifier = Modifier
                            .testTag("group_by_project_switch")
                            .semantics { contentDescription = "Group by project" },
                    )
                }
            }

            if (!groupByProject) {
                // PA pin (web flat list) — not part of task sections.
                val pas = onlineSessions.filter { it.role == "personal_assistant" }
                if (pas.isNotEmpty()) {
                    item(key = "flat:pa_hdr") {
                        Text(
                            "PERSONAL ASSISTANTS",
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(pas, key = { "flat:pa:${it.id}" }) { s ->
                        SessionRow(
                            s = s,
                            active = s.id == activeId,
                            preview = lastBySession[s.id],
                            working = agentState[s.id]?.working == true,
                            bgOpen = agentState[s.id]?.bgOpen ?: 0,
                            hostBadge = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                            onClick = { openSession(s) },
                            onRename = { renameTarget = s; renameText = s.name },
                            onKill = { killTarget = s },
                            onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                            sharedScope = sharedScope,
                            animScope = animScope,
                        )
                    }
                }
                // Flat task list: In Progress / Drafts / Settled across all projects.
                flatSections.forEach { section ->
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
                                    working = agentState[s.id]?.working == true,
                                    bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                    hostBadge = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                                    projectTag = projectLabel(s, effectiveHome),
                                    onClick = { openSession(s) },
                                    onRename = { renameTarget = s; renameText = s.name },
                                    onKill = { killTarget = s },
                                    onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                    onResume = { onResume(s.id) },
                                    sharedScope = sharedScope,
                                    animScope = animScope,
                                )
                            }
                        }
                    } else {
                        item(key = "flat:hdr:${section.key}") {
                            Text(
                                section.label.uppercase(),
                                color = cs.onSurfaceVariant,
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(section.sessions, key = { "flat:${it.id}" }) { s ->
                            ReorderableItem(reorderableState, key = "flat:${s.id}") { isDragging ->
                                // Whole-row long-press on the swipe shell (parent of Surface click).
                                // Shared interactionSource keeps click + long-press from fighting
                                // (Calvin demo pattern). Swipe is disabled while isDragging.
                                val rowInteraction = remember { MutableInteractionSource() }
                                SessionRow(
                                    s = s,
                                    active = s.id == activeId,
                                    preview = lastBySession[s.id],
                                    working = agentState[s.id]?.working == true,
                                    bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                    hostBadge = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                                    projectTag = projectLabel(s, effectiveHome),
                                    isDragging = isDragging,
                                    interactionSource = rowInteraction,
                                    dragModifier = Modifier.longPressDraggableHandle(
                                        interactionSource = rowInteraction,
                                    ),
                                    onClick = { openSession(s) },
                                    onRename = { renameTarget = s; renameText = s.name },
                                    onKill = { killTarget = s },
                                    onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                    onResume = { onResume(s.id) },
                                    sharedScope = sharedScope,
                                    animScope = animScope,
                                )
                            }
                        }
                    }
                }
            } else {
            // Grouped: path header above Android-style card; rows flush; settled as quiet footer.
            groups.forEach { g ->
                item(key = "group:${g.workdir}") {
                    val isCollapsed = collapsedPaths.contains(g.workdir)
                    val activeCount = g.sections.filter { it.key != SectionKey.SETTLED }
                        .sumOf { it.sessions.size }
                        .let { n -> if (g.workdir == dev.supermux.session.PA_GROUP_KEY) g.sessions.size else n }
                    val openSections = if (g.sections.isEmpty()) {
                        // PA group: single flat list of sessions
                        listOf(TaskSection(SectionKey.IN_PROGRESS, "In Progress", g.sessions))
                    } else {
                        g.sections.filter { it.key != SectionKey.SETTLED }
                    }
                    val settled = g.sections.firstOrNull { it.key == SectionKey.SETTLED }
                    val settledOpen = settledExpanded.contains(g.workdir)
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        PathGroupHeader(
                            label = g.label,
                            count = activeCount,
                            collapsed = isCollapsed,
                            onToggle = {
                                collapsedPaths = if (isCollapsed) {
                                    collapsedPaths - g.workdir
                                } else {
                                    collapsedPaths + g.workdir
                                }
                                saveCollapsedPaths(ctx, collapsedPaths)
                            },
                        )
                        AnimatedVisibility(visible = !isCollapsed) {
                            Surface(
                                shape = RoundedCornerShape(Radii.lg),
                                color = cs.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("group_card_${g.workdir}"),
                            ) {
                                Column {
                                    var rowIndex = 0
                                    openSections.forEach { section ->
                                        section.sessions.forEach { s ->
                                            if (rowIndex > 0) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 20.dp),
                                                    thickness = 0.5.dp,
                                                    color = cs.outlineVariant.copy(alpha = 0.5f),
                                                )
                                            }
                                            rowIndex++
                                            SessionRow(
                                                s = s,
                                                active = s.id == activeId,
                                                preview = lastBySession[s.id],
                                                working = agentState[s.id]?.working == true,
                                                bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                                hostBadge = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                                                onClick = { openSession(s) },
                                                onRename = { renameTarget = s; renameText = s.name },
                                                onKill = { killTarget = s },
                                                onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                                onResume = { onResume(s.id) },
                                                sharedScope = sharedScope,
                                                animScope = animScope,
                                            )
                                        }
                                    }
                                    if (settled != null && settled.sessions.isNotEmpty()) {
                                        if (rowIndex > 0) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = cs.outlineVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                        QuietSettledToggle(
                                            count = settled.sessions.size,
                                            expanded = settledOpen,
                                            onToggle = {
                                                settledExpanded = if (settledOpen) {
                                                    settledExpanded - g.workdir
                                                } else {
                                                    settledExpanded + g.workdir
                                                }
                                            },
                                            inCard = true,
                                        )
                                        if (settledOpen) {
                                            settled.sessions.forEach { s ->
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 20.dp),
                                                    thickness = 0.5.dp,
                                                    color = cs.outlineVariant.copy(alpha = 0.5f),
                                                )
                                                SessionRow(
                                                    s,
                                                    active = s.id == activeId,
                                                    preview = lastBySession[s.id],
                                                    working = false,
                                                    hostBadge = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                                                    onClick = { openSession(s) },
                                                    onResume = { onResume(s.id) },
                                                    onKill = { killTarget = s },
                                                    sharedScope = sharedScope,
                                                    animScope = animScope,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            // Offline hosts (spec §5): a greyed group per unreachable host with its last-seen and
            // its cached sessions (rendered dimmed). Skipped entirely in single-host mode.
            offlineGroups.forEach { (host, cached) ->
                item(key = "offline:${host.recordId}") { OfflineHostHeader(host) }
                items(cached, key = { "off:${it.id.ifEmpty { it.name }}" }) { s ->
                    Box(Modifier.graphicsLayer { alpha = 0.55f }) {
                        SessionRow(
                            s,
                            active = false,
                            preview = lastBySession[s.id],
                            hostBadge = hostByRecord[host.recordId],
                            onClick = { onOpen(s.id) },
                            onRename = { renameTarget = s; renameText = s.name },
                            onKill = { killTarget = s },
                            onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                        )
                    }
                }
            }
            // Bottom padding so the FAB doesn't cover the last item
            item(key = "bottom_spacer") { Spacer(Modifier.height(88.dp)) }
        } // LazyColumn
        } // Box(innerPadding)
    } // Scaffold content

    // Row action dialogs
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
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
            title = { Text("Settle session?") },
            text = { Text("This ends \"${target.name}\" and its agent. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onKill(target.id); killTarget = null }) {
                    Text("Settle", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
    }
}

/** Greyed group header for an offline/unreachable host (spec §5): identity dot + name + last-seen. */
@Composable
private fun OfflineHostHeader(host: dev.supermux.android.host.HostView) {
    val cs = MaterialTheme.colorScheme
    val lastSeen = dev.supermux.android.host.formatLastSeen(System.currentTimeMillis(), host.lastSeenAt)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("offline_host_${host.recordId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        dev.supermux.android.host.HostDot(host.colorIndex, size = 8.dp)
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

@Composable
fun NewSessionListRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick)
            .background(cs.surfaceContainer)
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text("Start a new session", color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                "Pick a project and send your first message",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
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
    val label = if (expanded) "Hide $count settled" else "Show $count settled"
    Text(
        label,
        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                horizontal = if (inCard) 16.dp else 16.dp,
                vertical = if (inCard) 10.dp else 8.dp,
            )
            .testTag("settled_toggle"),
    )
}
