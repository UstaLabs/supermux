package dev.supermux.android.session

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    val c = LocalPanes.current
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
                .border(1.dp, Color(c.border).copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
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
                .background(Color(c.primary))
                .border(1.dp, Color(c.border).copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val initials = name.take(2).uppercase()
            Text(initials, color = Color(0xFFFFFFFF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

// Fix 3: path-group header with rotating ChevronDown matching the web app
@Composable
fun PathGroupHeader(label: String, count: Int, collapsed: Boolean = false) {
    val c = LocalPanes.current
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
            tint = Color(c.mutedForeground),
            modifier = Modifier
                .size(14.dp)
                .rotate(if (collapsed) -90f else 0f),
        )
        Text(
            label,
            color = Color(c.mutedForeground),
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (count > 1) {
            Text(
                "$count",
                color = Color(c.mutedForeground).copy(alpha = 0.6f),
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry? = null,
    onClick: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val c = LocalPanes.current
    val haptic = rememberHaptics()

    // Unread indicator: inbound message that is the latest entry
    val hasUnread = !active && preview?.direction == "inbound"

    val rowModifier = if (active) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .softElevation(radius = Radii.md)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(c.card))
            .clickable { haptic(HapticKind.Tick); onClick() }
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .clickable { haptic(HapticKind.Tick); onClick() }
    }

    Row(
        rowModifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Subtle teal left-edge unread indicator
        if (hasUnread) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(c.primary).copy(alpha = 0.7f))
                    .align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(Space.sm))
        } else {
            // Reserve same horizontal space so avatar aligns consistently
            Spacer(Modifier.width(4.dp + Space.sm))
        }

        SessionAvatar(
            name = s.name,
            agent = s.agent,
            sessionId = s.id,
            sharedScope = sharedScope,
            animScope = animScope,
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            // Name row: session name + relative time aligned to end
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    color = Color(c.foreground),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val timeStr = relTime(preview?.ts)
                if (timeStr.isNotEmpty()) {
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        timeStr,
                        color = Color(c.mutedForeground),
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                    )
                }
            }

            // Fix 2: status badge — show when status is non-null and not "active"
            val status = s.status
            if (status != null && status != "active") {
                val badgeColor = if (status == "suspended") Color(c.warning)
                                 else Color(c.mutedForeground).copy(alpha = 0.6f)
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
                    color = Color(c.mutedForeground),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    s.workdir,
                    color = Color(c.mutedForeground),
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

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<SessionInfo>,
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    onNewSession: () -> Unit = {},
    loadProjects: suspend () -> List<String> = { emptyList() },
    validatePath: suspend (String) -> dev.supermux.net.PathValidation? = { null },
    onNavigate: (String) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val c = LocalPanes.current
    val groups = remember(sessions, home, lastBySession) {
        groupSessions(sessions, home) { lastBySession[it.id]?.ts ?: "" }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    // Fix 5: notification banner — local dismissed state (no persistence needed)
    var notifyBannerDismissed by remember { mutableStateOf(false) }
    val notifyPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Whether granted or denied, dismiss the banner
        notifyBannerDismissed = true
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(Color(c.sessionList)),
        ) {
            item(key = "header") {
                // Fix 1: brand header with MuxLogo mark before "supermux" wordmark
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(c.header))
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mux_logo),
                        contentDescription = "Supermux logo",
                        tint = Color(c.foreground),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        "supermux",
                        color = Color(c.foreground),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = "Actions",
                                tint = Color(c.mutedForeground),
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
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("archived") },
                            )
                            DropdownMenuItem(
                                text = { Text("Usage") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bar_chart),
                                        contentDescription = null,
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("usage") },
                            )
                            DropdownMenuItem(
                                text = { Text("Proxies") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_network),
                                        contentDescription = null,
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("proxies") },
                            )
                            DropdownMenuItem(
                                text = { Text("Displays") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_monitor),
                                        contentDescription = null,
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("displays") },
                            )
                            DropdownMenuItem(
                                text = { Text("Appearance") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_monitor),
                                        contentDescription = null,
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("appearance") },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_settings),
                                        contentDescription = null,
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("settings") },
                            )
                            DropdownMenuItem(
                                text = { Text("Devices") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_smartphone),
                                        contentDescription = null,
                                        tint = Color(c.mutedForeground),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { menuExpanded = false; onNavigate("devices") },
                            )
                        }
                    }
                }
                // Hairline bottom border for the header
                HorizontalDivider(color = Color(c.border).copy(alpha = 0.5f), thickness = 0.5.dp)
            }

            // Fix 5: notification banner — shown above first group until dismissed
            if (!notifyBannerDismissed) {
                item(key = "notify_banner") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.md, vertical = Space.sm)
                            .clip(RoundedCornerShape(Radii.md))
                            .background(Color(c.card))
                            .padding(horizontal = Space.md, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bell),
                            contentDescription = null,
                            tint = Color(c.primary),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "Get notified when a session replies",
                            color = Color(c.mutedForeground),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Space.sm))
                        TextButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    notifyBannerDismissed = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = Space.sm, vertical = 2.dp),
                        ) {
                            Text(
                                "Enable",
                                color = Color(c.primary),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "Clear",
                            tint = Color(c.mutedForeground).copy(alpha = 0.6f),
                            modifier = Modifier
                                .clickable { notifyBannerDismissed = true }
                                .padding(start = Space.xs, end = Space.xs)
                                .size(18.dp),
                        )
                    }
                }
            }

            item(key = "new_session_row") {
                NewSessionListRow(onClick = onNewSession)
            }

            groups.forEach { g ->
                item(key = "h:${g.workdir}") { PathGroupHeader(g.label, g.sessions.size) }
                items(g.sessions, key = { it.id.ifEmpty { it.name } }) { s ->
                    SessionRow(
                        s,
                        active = s.id == activeId,
                        preview = lastBySession[s.id],
                        onClick = { onOpen(s.id) },
                        sharedScope = sharedScope,
                        animScope = animScope,
                    )
                }
            }
            // Bottom padding so FAB doesn't cover last item
            item(key = "bottom_spacer") { Spacer(Modifier.height(88.dp)) }
        }

        // Fix 4: circular FAB — CircleShape instead of rounded-square
        FloatingActionButton(
            onClick = onNewSession,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Space.xl)
                .navigationBarsPadding()
                .size(56.dp)
                .softElevation(radius = Radii.pill),
            shape = CircleShape,
            containerColor = Color(c.primary),
            contentColor = Color(c.primaryForeground),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = "New session",
                tint = Color(c.primaryForeground),
                modifier = Modifier.size(24.dp),
            )
        }
    }

}

@Composable
fun NewSessionListRow(onClick: () -> Unit) {
    val c = LocalPanes.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick)
            .background(Color(c.card))
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(c.primary).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = null,
                tint = Color(c.primary),
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text("Start a new session", color = Color(c.foreground), fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                "Pick a project and send your first message",
                color = Color(c.mutedForeground),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Working-directory picker mirroring src/web-app/src/components/ProjectPathPicker.vue:
 * a free-text path field with a trailing button that opens a searchable dropdown of
 * known projects. Selecting a project fills the path; the field stays editable.
 */
@Composable
internal fun ProjectPathPicker(
    value: String,
    onValueChange: (String) -> Unit,
    projects: List<String>,
    home: String,
    fieldColors: TextFieldColors,
) {
    val c = LocalPanes.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("/path/to/project", color = Color(c.mutedForeground)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors,
            trailingIcon = {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_open),
                        contentDescription = "Select project",
                        tint = Color(c.mutedForeground),
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = Color(c.mutedForeground),
                        modifier = Modifier.size(14.dp),
                    )
                }
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(menuWidth)
                .background(Color(c.card)),
        ) {
            Column {
                // Search field
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search projects...", color = Color(c.mutedForeground)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = Color(c.mutedForeground),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )

                when {
                    projects.isEmpty() -> {
                        Text(
                            "No known projects yet.",
                            color = Color(c.mutedForeground),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 20.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    else -> {
                        val filtered = projects.filter { path ->
                            val label = formatWorkdir(path, home)
                            query.isBlank() ||
                                label.contains(query, ignoreCase = true) ||
                                path.contains(query, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                "No projects found.",
                                color = Color(c.mutedForeground),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 20.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        } else {
                            Column(
                                Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                filtered.forEach { path ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onValueChange(path); expanded = false }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_folder_open),
                                            contentDescription = null,
                                            tint = Color(c.mutedForeground),
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(16.dp),
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                formatWorkdir(path, home),
                                                color = Color(c.foreground),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                path,
                                                color = Color(c.mutedForeground),
                                                fontFamily = MonoFontFamily,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (path == value) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = Color(c.primary),
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .size(16.dp),
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
}
