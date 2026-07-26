// Ported from apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt —
// keep in sync until a shared UI module exists.
//
// Deliberate M1 desktop adaptations vs. the Android source:
//  - `SessionListScreen` (Scaffold + TopAppBar overflow menu + FAB + new-session row) is renamed
//    `SessionListPanel` and trimmed to just the grouped session list. The overflow destinations
//    (Archived/Usage/Proxies/Appearance/Settings/Devices) and the new-session flow
//    (SessionLauncherScreen/ProjectPickerSheet) aren't ported to desktop yet — there is no router
//    or session-creation API surface on DesktopAppState to wire them to. WorkspaceRoot (M1 Task 9)
//    will own the app-level chrome once those land.
//  - Shared-element transition params (sharedScope/animScope) are dropped — desktop has no
//    Android-style shared-element navigation.
//  - Long-press-to-open-menu is dropped in favour of a right-click ContextMenuArea, which is the
//    native desktop affordance for row actions (mouse long-press has no clean desktop equivalent).
package dev.supermux.desktop.session

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.host.HostBadge
import dev.supermux.desktop.host.HostFilterChips
import dev.supermux.desktop.host.HostView
import dev.supermux.desktop.host.filterSessions
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.softElevation
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
import dev.supermux.session.groupSessions
import dev.supermux.session.inferHomeDir

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

/** Per-agent letter + brand tile color. `null` (unrecognised agent) falls back to name initials,
 *  matching Android's `agentDrawableRes(agent) == null` path. */
private data class AgentBrand(val letter: String, val tile: Color, val ink: Color)

private fun agentBrand(agent: String?): AgentBrand? = when (agent?.lowercase()) {
    // Claude "Kraft" terracotta.
    "claude" -> AgentBrand("c", Color(0xFFCC785C), Color.White)
    // OpenAI/Codex teal-green.
    "codex" -> AgentBrand("x", Color(0xFF10A37F), Color.White)
    // Cursor's mark is near-black on white; a near-black tile would vanish against a dark-mode
    // background, so invert to a light tile with near-black ink for legibility in both themes.
    "cursor" -> AgentBrand("▲", Color(0xFFF2F1EC), Color(0xFF111111))
    "opencode" -> AgentBrand("o", Color(0xFF4C6FFF), Color.White)
    // Grok/xAI mark is a near-black X; invert to a light tile like cursor so it stays
    // legible in dark mode.
    "grok" -> AgentBrand("g", Color(0xFFF2F1EC), Color(0xFF111111))
    else -> null
}

/**
 * Session avatar: agent letter mark in a brand-tinted circle, or the session's name initials when
 * the agent isn't recognised. Same composable shape as Android's `SessionAvatar`, minus the
 * shared-element params (no Android-style shared-element nav on desktop) and the drawable lookup
 * (desktop has no bundled per-agent logo art yet).
 *
 * NOT used in [SessionRow] — matching Android, where list rows deliberately stay lean (the small
 * [SessionStatusRail] IS the row's leading visual; a per-row avatar column was tried on Android
 * and reverted as a heavy, repetitive wall of near-identical marks). Currently unused on desktop:
 * its Android call sites — collapsed sessions rail, chat header, workspace detail — arrive with
 * Task 9+ and will use this.
 *
 * TODO(M4): swap the letter tile for real per-agent logo marks once desktop ships bundled agent
 * artwork (Android uses `R.drawable.agent_*`; this is a placeholder for the M1 port).
 */
@Composable
fun SessionAvatar(name: String, agent: String? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val brand = agentBrand(agent)
    if (brand != null) {
        Box(
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(brand.tile)
                .border(1.dp, cs.outline.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(brand.letter, color = brand.ink, fontFamily = MonoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    } else {
        Box(
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(cs.primary)
                .border(1.dp, cs.outline.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initials = name.take(2).uppercase()
            Text(initials, color = cs.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

/** Path-group header with a leading chevron + session count, matching the web app's group
 *  headers. [collapsed] mirrors the Android source: rendered (rotated -90°) but never triggered —
 *  group collapse is not wired up yet on either platform. */
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
        // Android uses R.drawable.ic_chevron_down; desktop has no bundled icon set, so the
        // materialIconsExtended equivalent stands in.
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
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

/**
 * One session row: status rail, name/time header, status badge, and message/workdir preview —
 * lean by design, no per-row avatar (Android parity; see [SessionAvatar]). Right-click opens the
 * row's action menu (Rename/Mute/Settle); Android's long-press + DropdownMenu becomes a native
 * `ContextMenuArea` on desktop (see file header).
 */
@Composable
fun SessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry? = null,
    working: Boolean = false,
    bgOpen: Int = 0,
    host: HostView? = null,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme

    // Unread indicator: inbound message that is the latest entry.
    val hasUnread = !active && preview?.direction == "inbound"

    val rowModifier = if (active) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .softElevation(radius = Radii.md)
            .clip(RoundedCornerShape(6.dp))
            .background(cs.surfaceContainer)
            .clickable(onClick = onClick)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
    }

    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Rename") { onRename() },
                ContextMenuItem(if (s.mute == true) "Unmute" else "Mute") { onToggleMute() },
                ContextMenuItem("Settle") { onKill() },
            )
        },
    ) {
        Row(
            rowModifier
                .testTag("session_row_${s.id}")
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SessionStatusRail(git = s.git, working = working, bgOpen = bgOpen, modifier = Modifier.align(Alignment.CenterVertically))
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                // Name row: session name + relative time aligned to end.
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

                // Per-host badge (merged fleet list, multi-host only): the owning host's identity
                // dot + short name. Null in single-host mode, so the row is unchanged there.
                if (host != null) {
                    Spacer(Modifier.height(2.dp))
                    HostBadge(host)
                }

                // Status badge — show when status is non-null and not "active".
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

                // Preview: last message or workdir fallback.
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

/**
 * The session-list panel: grouped-by-project session rows. Ported from Android's
 * `SessionListScreen` minus the Scaffold/TopAppBar/FAB chrome — see file header for why.
 */
@Composable
fun SessionListPanel(
    sessions: List<SessionInfo>,
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    agentState: Map<String, AgentStatus> = emptyMap(),
    onRename: (String, String) -> Unit = { _, _ -> },
    onKill: (String) -> Unit = {},
    onMute: (String, Boolean) -> Unit = { _, _ -> },
    onNewSession: () -> Unit = {},
    // ── Multi-host fleet (spec §5); all default to single-host (no badges/chips) ──
    hosts: List<HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onSelectHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
    // ── Windows preview card (spec §6 / Task 6); Windows-only, hidden on native-host platforms ──
    showWindowsPreview: Boolean = false,
    onJoinWindowsPreview: () -> Boolean = { false },
    onOpenWslGuide: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // The `All · <host…> · +` chip row + per-row host badges appear ONLY with a real fleet
    // (>1 host). Single-host desktop users (and every existing test) see the list unchanged.
    val multiHost = hosts.size > 1
    val hostByRecord = remember(hosts) { hosts.associateBy { it.recordId } }
    // Apply the host filter before grouping so the groups + counts reflect the current chip.
    val visibleSessions = if (multiHost) filterSessions(sessions, sessionHost, hostFilter) else sessions
    // Infer the home dir from the sessions' workdirs (iOS `BrokerSession.grouped` parity) instead
    // of relying solely on the passed-in fallback, so "~/…" abbreviation in group labels matches
    // iOS/Android. `home` is `System.getProperty("user.home")` from the desktop call site.
    val effectiveHome = inferHomeDir(visibleSessions.firstOrNull()?.workdir) ?: home
    val groups = remember(visibleSessions, effectiveHome, lastBySession) {
        groupSessions(visibleSessions, effectiveHome) { lastBySession[it.id]?.ts ?: "" }
    }

    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<SessionInfo?>(null) }

    Box(modifier.background(cs.surfaceContainerHigh)) {
        // The "Start a new session" row is always the first item so session creation is reachable
        // from both the populated list and the zero-session empty state (Android parity).
        LazyColumn(
            Modifier
                .testTag("sessions_list")
                .fillMaxSize(),
        ) {
            item(key = "new_session_row") { NewSessionListRow(onClick = onNewSession) }
            // Windows-only "Host from this PC — preview" card (Task 6). macOS/Linux pass false.
            if (showWindowsPreview) {
                item(key = "windows_host_preview") {
                    dev.supermux.desktop.host.WindowsHostPreviewCard(
                        onJoinPreview = onJoinWindowsPreview,
                        onOpenWslGuide = onOpenWslGuide,
                    )
                }
            }
            if (multiHost) {
                item(key = "host_chips") {
                    HostFilterChips(
                        hosts = hosts,
                        sessions = sessions,
                        sessionHost = sessionHost,
                        selected = hostFilter,
                        onSelect = onSelectHostFilter,
                        onAddHost = onAddHost,
                    )
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty_hint") {
                    Text(
                        "No sessions yet",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md),
                    )
                }
            } else {
                groups.forEach { g ->
                    item(key = "h:${g.workdir}") { PathGroupHeader(g.label, g.sessions.size) }
                    items(g.sessions, key = { it.id.ifEmpty { it.name } }) { s ->
                        SessionRow(
                            s,
                            active = s.id == activeId,
                            preview = lastBySession[s.id],
                            working = agentState[s.id]?.working == true,
                            bgOpen = agentState[s.id]?.bgOpen ?: 0,
                            host = if (multiHost) hostByRecord[sessionHost[s.id]] else null,
                            onClick = { onOpen(s.id) },
                            onRename = { renameTarget = s; renameText = s.name },
                            onKill = { killTarget = s },
                            onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                        )
                    }
                }
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(Space.lg)) }
        }
    }

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

/**
 * Full-width "Start a new session" row — the desktop port of Android's `NewSessionListRow`
 * (surfaceContainer card, a plus tile at primary@12% alpha, title + subtitle). Uses desktop idioms:
 * an [Icons.Filled.Add] vector (no bundled drawable) and a hand hover cursor. `testTag` is
 * "new_session_row"; clicking it fires [onClick] (wired to the launcher).
 */
@Composable
fun NewSessionListRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .testTag("new_session_row")
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
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
                imageVector = Icons.Filled.Add,
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
