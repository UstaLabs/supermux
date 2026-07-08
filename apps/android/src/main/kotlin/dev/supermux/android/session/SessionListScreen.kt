package dev.supermux.android.session

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.ui.platform.testTag
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
    else     -> null
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

// Fix 3: path-group header with rotating ChevronDown matching the web app
@Composable
fun PathGroupHeader(label: String, count: Int, collapsed: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
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
                .rotate(if (collapsed) -90f else 0f),
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
        if (count > 1) {
            Text(
                "$count",
                color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
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
            text = { Text("Kill", color = MaterialTheme.colorScheme.error) },
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
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Infer the home dir from the sessions' workdirs (iOS `BrokerSession.grouped` parity) instead of
    // the hardcoded DevConfig.HOME placeholder, so "~/…" abbreviation in group labels matches iOS.
    val effectiveHome = inferHomeDir(sessions.firstOrNull()?.workdir) ?: home
    val groups = remember(sessions, effectiveHome, lastBySession) {
        groupSessions(sessions, effectiveHome) { lastBySession[it.id]?.ts ?: "" }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<SessionInfo?>(null) }

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
            item(key = "new_session_row") {
                NewSessionListRow(
                    onClick = onNewSession,
                    modifier = Modifier.testTag("new_session_row"),
                )
            }

            groups.forEach { g ->
                item(key = "h:${g.workdir}") { PathGroupHeader(g.label, g.sessions.size) }
                items(g.sessions, key = { it.id.ifEmpty { it.name } }) { s ->
                    SessionRow(
                        s,
                        active = s.id == activeId,
                        preview = lastBySession[s.id],
                        working = agentState[s.id]?.working == true,
                        bgOpen = agentState[s.id]?.bgOpen ?: 0,
                        onClick = { onOpen(s.id) },
                        onRename = { renameTarget = s; renameText = s.name },
                        onKill = { killTarget = s },
                        onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                        sharedScope = sharedScope,
                        animScope = animScope,
                    )
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
            title = { Text("Kill session?") },
            text = { Text("This ends \"${target.name}\" and its agent. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onKill(target.id); killTarget = null }) {
                    Text("Kill", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
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
