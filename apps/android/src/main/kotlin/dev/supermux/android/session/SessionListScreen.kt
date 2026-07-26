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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry? = null,
    working: Boolean = false,
    bgOpen: Int = 0,
    // Non-null only in multi-host mode → renders the compact per-row host badge (dot + short name).
    hostBadge: dev.supermux.android.host.HostView? = null,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    var menuExpanded by remember { mutableStateOf(false) }

    // Unread indicator: inbound message that is the latest entry
    val hasUnread = !active && preview?.direction == "inbound"

    val rowModifier = if (active) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .softElevation(radius = Radii.md)
            .clip(RoundedCornerShape(6.dp))
            .background(cs.surfaceContainer)
            .combinedClickable(
                onClick = { haptic(HapticKind.Tick); onClick() },
                onLongClick = { haptic(HapticKind.Confirm); menuExpanded = true },
            )
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .combinedClickable(
                onClick = { haptic(HapticKind.Tick); onClick() },
                onLongClick = { haptic(HapticKind.Confirm); menuExpanded = true },
            )
    }

    Box {
    Row(
        rowModifier
            .testTag("session_row_${s.id}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SessionStatusRail(git = s.git, working = working, bgOpen = bgOpen, modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            // Name row: session name + relative time aligned to end
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    // Bold the ACTIVE session (selection) as well as unread ones. Previously only
                    // unread (last-message-inbound) rows were bold, and selecting a row cleared its
                    // unread → it *lost* bold on click and regained it when you selected away, which
                    // read as "clicking doesn't bold it, but clicking another one does".
                    fontWeight = if (active || hasUnread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
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

            // Fix 2: status badge — show when status is non-null and not "active"
            val status = s.status
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

            // Preview: last message or workdir fallback
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
                    // Match iOS: abbreviate the workdir via the shared KMP util (~/… under home,
                    // …/parent/leaf when deep) instead of showing the raw absolute path.
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
    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
        DropdownMenuItem(
            text = { Text(if (s.mute == true) "Unmute" else "Mute") },
            onClick = { menuExpanded = false; onToggleMute() },
        )
        DropdownMenuItem(
            text = { Text("Settle", color = MaterialTheme.colorScheme.error) },
            onClick = { menuExpanded = false; onKill() },
        )
    }
    }
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
    val groups = remember(onlineSessions, effectiveHome, lastBySession) {
        groupSessions(onlineSessions, effectiveHome) { lastBySession[it.id]?.ts ?: "" }
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
        LazyColumn(
            Modifier
                .testTag("sessions_list")
                .fillMaxSize()
                .padding(innerPadding)
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

            // Each project group renders as its own rounded surface card (Pixel-Settings
            // style), header above it, so the grouping is visually distinct — the native
            // Material take on iOS's inset-grouped sections. Tapping a header collapses the
            // group; the collapsed set is persisted. The whole group is one LazyColumn item
            // (session counts per project are small), which keeps the card + collapse
            // animation simple and lets AnimatedVisibility shrink/expand it in place.
            groups.forEach { g ->
                item(key = "group:${g.workdir}") {
                    val isCollapsed = collapsedPaths.contains(g.workdir)
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        PathGroupHeader(
                            label = g.label,
                            count = g.sessions.size,
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
                                    g.sessions.forEachIndexed { i, s ->
                                        if (i > 0) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 20.dp),
                                                thickness = 0.5.dp,
                                                color = cs.outlineVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                        SessionRow(
                                            s,
                                            active = s.id == activeId,
                                            preview = lastBySession[s.id],
                                            working = agentState[s.id]?.working == true,
                                            bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                            hostBadge = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                                            onClick = { onOpen(s.id) },
                                            onRename = { renameTarget = s; renameText = s.name },
                                            onKill = { killTarget = s },
                                            onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
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
        }
    }

    // Long-press row actions (parity with iOS's session-list swipe/context actions).
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
