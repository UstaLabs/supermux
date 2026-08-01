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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.sectionKey
import dev.supermux.session.effectiveUserStatus
import dev.supermux.session.projectLabel
import dev.supermux.session.combinedTaskSessions
import dev.supermux.session.buildTaskSections
import dev.supermux.session.sessionsByUserOrder
import dev.supermux.session.TaskSection
import dev.supermux.session.SectionKey
import dev.supermux.net.ArchivedDto
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
    /** ISO last_read_at for this session (null/absent = never read). */
    lastReadAt: String? = null,
    working: Boolean = false,
    bgOpen: Int = 0,
    host: HostView? = null,
    projectTag: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onResume: () -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme

    // Server-authoritative unread mark (shared sessionListShowsUnread — spinner wins while working).
    val hasUnread = dev.supermux.session.sessionListShowsUnread(
        active = active,
        working = working,
        lastMessageTs = preview?.ts,
        lastReadAt = lastReadAt,
    )

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

    Box(modifier) {
    ContextMenuArea(
        items = {
            when (s.sectionKey()) {
                SectionKey.SETTLED -> listOf(ContextMenuItem("Resume") { onResume() })
                SectionKey.DRAFT -> listOf(
                    ContextMenuItem("Open draft") { onClick() },
                    ContextMenuItem("Discard") { onKill() },
                )
                SectionKey.IN_PROGRESS -> buildList {
                    add(ContextMenuItem("Rename") { onRename() })
                    add(ContextMenuItem(if (s.mute == true) "Unmute" else "Mute") { onToggleMute() })
                    onMoveUp?.let { add(ContextMenuItem("Move up") { it() }) }
                    onMoveDown?.let { add(ContextMenuItem("Move down") { it() }) }
                    add(ContextMenuItem("Settle") { onKill() })
                }
            }
        },
    ) {
        Row(
            rowModifier
                .testTag("session_row_${s.id}")
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (s.effectiveUserStatus() == "draft") {
                Text(
                    "✎",
                    color = cs.primary.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            } else {
                SessionStatusRail(
                    git = null,
                    working = working,
                    bgOpen = bgOpen,
                    unread = hasUnread,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
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
    /** Bare sessionId → ISO last_read_at (server + optimistic local marks). */
    lastRead: Map<String, String> = emptyMap(),
    agentState: Map<String, AgentStatus> = emptyMap(),
    onRename: (String, String) -> Unit = { _, _ -> },
    onKill: (String) -> Unit = {},
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
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // The `All · <host…> · +` chip row + per-row host badges appear ONLY with a real fleet
    // (>1 host). Single-host desktop users (and every existing test) see the list unchanged.
    val multiHost = hosts.size > 1
    // When a specific host pill is selected, every row is already that host — hide the redundant badge.
    val showRowHostBadge = multiHost && hostFilter == null
    val hostByRecord = remember(hosts) { hosts.associateBy { it.recordId } }
    // Apply the host filter before grouping so the groups + counts reflect the current chip.
    val visibleSessions = if (multiHost) filterSessions(sessions, sessionHost, hostFilter) else sessions
    // Infer the home dir from the sessions' workdirs (iOS `BrokerSession.grouped` parity) instead
    // of relying solely on the passed-in fallback, so "~/…" abbreviation in group labels matches
    // iOS/Android. `home` is `System.getProperty("user.home")` from the desktop call site.
    val effectiveHome = inferHomeDir(visibleSessions.firstOrNull()?.workdir) ?: home
    val lastTs: (SessionInfo) -> String = { lastBySession[it.id]?.ts ?: "" }
    val groups = remember(visibleSessions, effectiveHome, lastBySession, archived) {
        groupSessions(visibleSessions, effectiveHome, lastTs, archived = archived)
    }
    val flatSections = remember(visibleSessions, lastBySession, archived) {
        buildTaskSections(combinedTaskSessions(visibleSessions, archived), lastTs)
    }
    var groupByProject by remember { mutableStateOf(false) }
    var settledExpanded by remember { mutableStateOf(setOf<String>()) }
    var flatSettledExpanded by remember { mutableStateOf(false) }

    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<SessionInfo?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragReorder = remember(listState) {
        SessionDragReorderState(scope, listState) { ids -> onReorder(ids) }
    }
    var listRootOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    fun sectionOrder(section: TaskSection): List<SessionInfo> {
        val live = dragReorder.liveOrder
        if (live == null || dragReorder.draggingId !in section.sessions.map { it.id }) return section.sessions
        val byId = section.sessions.associateBy { it.id }
        return live.mapNotNull { byId[it] }
    }
    fun dragMod(section: TaskSection, can: Boolean): (String) -> Modifier = { id ->
        val label = section.sessions.firstOrNull { it.id == id }?.name ?: id
        dragReorder.rowModifier(
            id,
            { dragReorder.liveOrder ?: section.sessions.map { it.id } },
            enabled = can,
            label = label,
        )
    }


    fun openSession(s: SessionInfo) {
        when (s.sectionKey()) {
            SectionKey.DRAFT -> onOpenDraft(s.id)
            else -> onOpen(s.id)
        }
    }
    fun reorderWithin(sectionSessions: List<SessionInfo>, id: String, delta: Int) {
        val ids = sectionSessions.map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in ids.indices) return
        java.util.Collections.swap(ids, i, j)
        onReorder(ids)
    }

    Box(
        modifier
            .background(cs.surfaceContainerHigh)
            .onGloballyPositioned { listRootOffset = it.positionInRoot() },
    ) {
        // The "Start a new session" row is always the first item so session creation is reachable
        // from both the populated list and the zero-session empty state (Android parity).
        LazyColumn(
            state = listState,
            modifier = Modifier
                .testTag("sessions_list")
                .fillMaxSize(),
        ) {
            item(key = "new_session_row") { NewSessionListRow(onClick = onNewSession) }
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
            item(key = "group_by_toggle") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { groupByProject = !groupByProject }) {
                        Text(if (groupByProject) "Flat list" else "Group by project", fontSize = 11.sp)
                    }
                }
            }
            if (groups.isEmpty() && flatSections.isEmpty()) {
                item(key = "empty_hint") {
                    Text(
                        "No sessions yet",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md),
                    )
                }
            } else if (!groupByProject) {
                // User sortOrder only; new messages must not reshuffle (web/Android parity).
                val pas = sessionsByUserOrder(visibleSessions.filter { it.role == "personal_assistant" })
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
                    items(pas, key = { "flat:pa:${it.id}" }) { s ->
                        SessionRow(
                            s, active = s.id == activeId, preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                            working = agentState[s.id]?.working == true,
                            bgOpen = agentState[s.id]?.bgOpen ?: 0,
                            host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                            onClick = { openSession(s) },
                            onRename = { renameTarget = s; renameText = s.name },
                            onKill = { killTarget = s },
                            onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                        )
                    }
                }
                flatSections.forEach { section ->
                    if (section.key == SectionKey.SETTLED) {
                        item(key = "flat:settled") {
                            TextButton(onClick = { flatSettledExpanded = !flatSettledExpanded }) {
                                Text(
                                    if (flatSettledExpanded) "Hide ${section.sessions.size} settled"
                                    else "Show ${section.sessions.size} settled",
                                    fontSize = 12.sp,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        if (flatSettledExpanded) {
                            items(section.sessions, key = { "f:${it.id}" }) { s ->
                                SessionRow(
                                    s, active = s.id == activeId, preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                                    host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                    projectTag = projectLabel(s, effectiveHome),
                                    onClick = { openSession(s) },
                                    onResume = { onResume(s.id) },
                                    onKill = { killTarget = s },
                                )
                            }
                        }
                    } else {
                        item(key = "flat:h:${section.key}") {
                            Text(
                                section.label.uppercase(),
                                color = cs.onSurfaceVariant,
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = Space.md, vertical = 6.dp),
                            )
                        }
                        items(sectionOrder(section), key = { "f:${it.id}" }) { s ->
                            SessionRow(
                                s, active = s.id == activeId, preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                                working = agentState[s.id]?.working == true,
                                bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                projectTag = projectLabel(s, effectiveHome),
                                modifier = dragMod(section, true)(s.id),
                                onClick = { openSession(s) },
                                onRename = { renameTarget = s; renameText = s.name },
                                onKill = { killTarget = s },
                                onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                onResume = { onResume(s.id) },
                                onMoveUp = { reorderWithin(sectionOrder(section), s.id, -1) },
                                onMoveDown = { reorderWithin(sectionOrder(section), s.id, +1) },
                            )
                        }
                    }
                }
            } else {
                groups.forEach { g ->
                    val activeCount = if (g.workdir == PA_GROUP_KEY) g.sessions.size
                    else g.sections.filter { it.key != SectionKey.SETTLED }.sumOf { it.sessions.size }
                    item(key = "h:${g.workdir}") { PathGroupHeader(g.label, activeCount) }
                    val openSections = if (g.sections.isEmpty()) {
                        listOf(TaskSection(SectionKey.IN_PROGRESS, "In Progress", g.sessions))
                    } else g.sections.filter { it.key != SectionKey.SETTLED }
                    openSections.forEach { section ->
                        items(sectionOrder(section), key = { it.id.ifEmpty { it.name } }) { s ->
                            SessionRow(
                                s,
                                active = s.id == activeId,
                                preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                                working = agentState[s.id]?.working == true,
                                bgOpen = agentState[s.id]?.bgOpen ?: 0,
                                host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                modifier = dragMod(section, true)(s.id),
                                onClick = { openSession(s) },
                                onRename = { renameTarget = s; renameText = s.name },
                                onKill = { killTarget = s },
                                onToggleMute = { onMute(s.id, !(s.mute ?: false)) },
                                onResume = { onResume(s.id) },
                                onMoveUp = { reorderWithin(sectionOrder(section), s.id, -1) },
                                onMoveDown = { reorderWithin(sectionOrder(section), s.id, +1) },
                            )
                        }
                    }
                    val settled = g.sections.firstOrNull { it.key == SectionKey.SETTLED }
                    if (settled != null && settled.sessions.isNotEmpty()) {
                        item(key = "settled:${g.workdir}") {
                            val open = settledExpanded.contains(g.workdir)
                            TextButton(onClick = {
                                settledExpanded = if (open) settledExpanded - g.workdir else settledExpanded + g.workdir
                            }) {
                                Text(
                                    if (open) "Hide ${settled.sessions.size} settled" else "Show ${settled.sessions.size} settled",
                                    fontSize = 12.sp,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        if (settledExpanded.contains(g.workdir)) {
                            items(settled.sessions, key = { "s:${it.id}" }) { s ->
                                SessionRow(
                                    s, active = false, preview = lastBySession[s.id],
                            lastReadAt = lastRead[s.id],
                                    host = if (showRowHostBadge) hostByRecord[sessionHost[s.id]] else null,
                                    onClick = { openSession(s) },
                                    onResume = { onResume(s.id) },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(Space.lg)) }
        }

        // Floating drag ghost (web TaskSection Teleport parity). Ghost stores root coords;
        // subtract this Box's root origin so offset is local.
        dragReorder.ghost?.let { g ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            Surface(
                modifier = Modifier
                    .zIndex(10f)
                    .offset {
                        IntOffset(
                            (g.x - listRootOffset.x).toInt(),
                            (g.y - listRootOffset.y).toInt(),
                        )
                    }
                    .width(with(density) { g.width.toDp() })
                    .heightIn(min = with(density) { g.height.toDp() }),
                shape = RoundedCornerShape(6.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.35f)),
                color = cs.surface,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(cs.primary, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            g.label,
                            color = cs.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    Text(
                        "Release to drop",
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
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
