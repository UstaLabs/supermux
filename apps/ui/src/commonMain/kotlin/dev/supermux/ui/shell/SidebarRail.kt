// The collapsed sidebar (#57): a quiet 52 dp rail of the Agents' own faces and the projects in
// use. Hovering a project opens its workspaces at once, the way the macOS menu bar's menus
// follow the pointer once one is open; the host switch and settings stay at the foot.
//
//   - The rail draws no toggle. The sidebar toggle never moves: it lives in the title band on
//     desktop (macOS, the Linux/Windows custom chrome), and where there is no title band (iPad,
//     Android, the web, a system-framed desktop) the shell gives the sidebar a band of its own
//     ([SIDEBAR_BAND]) and draws [SidebarBandToggle] there, in the same spot in both states.
//   - Touch has no hover: a tap opens the same list.
//   - The rail fills the height it has. A project in use (used within RAIL_RECENT_MS, unread,
//     working, or holding the open workspace) always gets a tile; the room left goes to the most
//     recently used of the rest; all of them in the list's order. "All projects" appears only
//     when some project still has no tile.
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.supermux.chat.parseChatTs
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.SectionKey
import dev.supermux.session.sectionKey
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.session.AgentAvatar
import dev.supermux.ui.session.AvatarRig
import dev.supermux.ui.session.AvatarRigView
import dev.supermux.ui.session.agentDozing
import dev.supermux.ui.session.archivedRelTime
import dev.supermux.ui.sessionPreviewPlainText
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.usage.UsagePopover
import dev.supermux.workspace.WorkspaceGroup
import dev.supermux.workspace.chatSessionIds
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.time.Clock

/** The rail's width. The tiles inside are [RAIL_TILE]; what is left is the gutter. */
val RAIL_WIDTH = 52.dp
private val RAIL_TILE = 34.dp
/** The rail's spacing: between items, around a rule (6 + 1 + 6), and above and below the column. */
private val RAIL_GAP = 2.dp
private val RAIL_RULE = 13.dp
private val RAIL_PAD = 8.dp

/** A project counts as "in use" for this long after its last message. */
internal const val RAIL_RECENT_MS = 24 * 60 * 60_000L

// ── Model ────────────────────────────────────────────────────────────────────────────────────

@Immutable
internal data class RailAgent(
    val workspaceId: String,
    val sessionId: String?,
    val name: String,
    val working: Boolean,
    val unread: Boolean,
    val active: Boolean,
    val online: Boolean,
    val dozing: Boolean,
    val preview: LogEntry?,
)

@Immutable
internal data class RailWorkspace(
    val id: String,
    val name: String,
    /** The chat a click opens: the primary, else the first chat. */
    val sessionId: String?,
    val working: Boolean,
    val unread: Boolean,
    val active: Boolean,
    val settled: Boolean,
    val lastTs: String?,
)

@Immutable
internal data class RailProject(
    val key: String,
    val name: String,
    val rows: List<RailWorkspace>,
) {
    val unread: Int get() = rows.count { it.unread }
    val working: Boolean get() = rows.any { it.working }
    val active: Boolean get() = rows.any { it.active }
    val lastTs: String? get() = rows.mapNotNull { it.lastTs }.maxOrNull()
}

@Immutable
internal data class SidebarRailModel(
    val agents: List<RailAgent>,
    /** Every project with a live workspace, in the list's order. */
    val all: List<RailProject>,
    /** Keys of the projects in use: these always get a tile. */
    val inUse: Set<String>,
)

/**
 * The rail's model from the same [groups] the expanded list draws (`groupWorkspaces`), so the
 * two always agree on what an Agent is, which project a workspace is in, and the order.
 */
internal fun sidebarRailModel(
    groups: List<WorkspaceGroup>,
    sessionsById: Map<String, SessionInfo>,
    agentState: Map<String, AgentStatus?>,
    lastBySession: Map<String, LogEntry?>,
    lastRead: Map<String, String>,
    selectedSessionId: String?,
    nowMs: Long,
): SidebarRailModel {
    fun busy(sid: String) = agentState[sid]?.busy == true
    fun rowOf(w: WorkspaceDto): RailWorkspace {
        val chats = w.chatSessionIds()
        val primary = chats.firstOrNull() ?: w.primarySessionId
        val active = selectedSessionId != null && (selectedSessionId in chats || selectedSessionId == w.primarySessionId)
        val working = chats.any(::busy)
        val unread = !active && chats.any { sid ->
            sessionListShowsUnread(active = false, working = busy(sid), lastMessageTs = lastBySession[sid]?.ts, lastReadAt = lastRead[sid])
        }
        return RailWorkspace(
            id = w.id,
            name = primary?.let { sessionsById[it]?.name }?.takeIf { it.isNotBlank() && w.name.isBlank() } ?: w.name,
            sessionId = primary,
            working = working,
            unread = unread,
            active = active,
            settled = primary?.let { sessionsById[it] }?.sectionKey() == SectionKey.SETTLED,
            lastTs = chats.mapNotNull { lastBySession[it]?.ts }.maxOrNull(),
        )
    }
    val agents = groups.firstOrNull { it.key == PA_GROUP_KEY }?.workspaces.orEmpty().map { w ->
        val r = rowOf(w)
        val s = r.sessionId?.let { sessionsById[it] }
        RailAgent(
            workspaceId = w.id,
            sessionId = r.sessionId,
            name = s?.name?.takeIf { it.isNotBlank() } ?: w.name,
            working = r.working,
            unread = !r.working && r.unread,
            active = r.active,
            online = s?.status == null || s.status == "active",
            dozing = !r.working && !r.unread && agentDozing(r.lastTs, nowMs),
            preview = r.sessionId?.let { lastBySession[it] },
        )
    }
    val all = groups.filter { it.key != PA_GROUP_KEY && it.workspaces.isNotEmpty() }
        .map { g -> RailProject(g.key, g.label, g.workspaces.map(::rowOf)) }
    val inUse = all.filter { p ->
        p.unread > 0 || p.working || p.active ||
            (parseChatTs(p.lastTs)?.let { nowMs - it < RAIL_RECENT_MS } ?: false)
    }.mapTo(HashSet()) { it.key }
    return SidebarRailModel(agents, all, inUse)
}

/**
 * How many project tiles fit in [available] (the rail's scrolling area) under the + tile, the
 * rules and [agents] Agents, out of [total]. All of them when they all fit; otherwise one fewer
 * than fits, to leave room for "All projects".
 */
internal fun railProjectSlots(available: Dp, agents: Int, total: Int): Int {
    val tile = RAIL_TILE.value
    val gap = RAIL_GAP.value
    val rule = RAIL_RULE.value
    val fixedItems = 2 + agents + (if (agents > 0) 1 else 0)
    val fixedHeight = tile + rule + agents * tile + (if (agents > 0) rule else 0f)
    val base = RAIL_PAD.value * 2 + fixedHeight + gap * (fixedItems - 1)
    val fits = floor((available.value - base) / (tile + gap)).toInt().coerceAtLeast(0)
    return if (total <= fits) total else (fits - 1).coerceAtLeast(0)
}

/**
 * The projects that get a tile, given [slots]: every project in use, even past [slots] (the rail
 * scrolls then), then the most recently used of the rest; shown in the list's own order.
 */
internal fun railProjectsShown(all: List<RailProject>, inUse: Set<String>, slots: Int): List<RailProject> {
    if (all.size <= slots) return all
    val keep = all.filter { it.key in inUse }.mapTo(HashSet()) { it.key }
    all.filter { it.key !in keep }
        .sortedByDescending { it.lastTs ?: "" }
        .take((slots - keep.size).coerceAtLeast(0))
        .forEach { keep += it.key }
    return all.filter { it.key in keep }
}

// ── Rail ─────────────────────────────────────────────────────────────────────────────────────

private const val ALL_KEY = "rail:all"

/** Which list is open beside the rail, and why it stays open. */
@Stable
private class RailFlyout {
    var open by mutableStateOf<String?>(null)
    var tile by mutableStateOf<String?>(null)
    var panel by mutableStateOf(false)
    /** Opened by a click or tap: stays until a click elsewhere. */
    var pinned by mutableStateOf(false)
    /** In "All projects", the project drilled into. */
    var drill by mutableStateOf<String?>(null)

    fun close() { open = null; pinned = false; drill = null; panel = false }
}

/**
 * The collapsed sidebar. [footer] is the host's: the host switch and the settings menu, in the
 * rail's bottom sheet; [aboveFooter] stands on the rail's own tone just above that sheet (the
 * downloads' ring, #95). [avatars] is keyed by session id (the app-level Agent avatar cache).
 */
@Composable
internal fun SidebarRail(
    model: SidebarRailModel,
    avatars: Map<String, AvatarRig>,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onShowSidebar: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    aboveFooter: @Composable ColumnScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val flyout = remember { RailFlyout() }
    // Menu-bar timing: the first list waits a beat (a pointer passing over the rail opens
    // nothing), the next one follows at once, and leaving both tile and list closes it shortly.
    LaunchedEffect(flyout.tile, flyout.panel) {
        val t = flyout.tile
        if (t != null) {
            if (flyout.open == null) delay(120)
            if (flyout.open != t) flyout.drill = null
            flyout.open = t
            flyout.pinned = false
        } else if (!flyout.panel && !flyout.pinned && flyout.open != null) {
            delay(280)
            flyout.close()
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .background(cs.surfaceContainerHigh)
            .testTag("sidebar_rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Fill the height the rail has: the projects in use always, then as many more as fit.
            val slots = railProjectSlots(maxHeight, model.agents.size, model.all.size)
            val shown = remember(model, slots) { railProjectsShown(model.all, model.inUse, slots) }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = RAIL_PAD),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
            ) {
                RailTile(onClick = onNewSession, tag = TestIds.NEW_SESSION, label = "New session") {
                    Icon(Icons.Filled.Add, contentDescription = "New session", tint = cs.primary, modifier = Modifier.size(18.dp))
                }
                RailRule()
                model.agents.forEach { a -> RailAgentTile(a, a.sessionId?.let { avatars[it] }, onOpenSession) }
                if (model.agents.isNotEmpty()) RailRule()
                shown.forEach { p ->
                    RailProjectTile(p, flyout) {
                        ProjectFlyout(p, onOpen = { w -> flyout.close(); w.sessionId?.let(onOpenSession) }, onShowSidebar = { flyout.close(); onShowSidebar() }, flyout = flyout)
                    }
                }
                if (shown.size < model.all.size) {
                    RailFlyoutTile(key = ALL_KEY, flyout = flyout, tag = "rail_all_projects", label = "All projects", tile = {
                        Icon(Icons.Filled.GridView, contentDescription = "All projects", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }) {
                        val drilled = flyout.drill?.let { k -> model.all.firstOrNull { it.key == k } }
                        if (drilled != null) {
                            ProjectFlyout(
                                drilled,
                                onOpen = { w -> flyout.close(); w.sessionId?.let(onOpenSession) },
                                onShowSidebar = { flyout.close(); onShowSidebar() },
                                flyout = flyout,
                                onBack = { flyout.drill = null },
                            )
                        } else {
                            AllProjectsFlyout(model.all, flyout = flyout, onPick = { flyout.drill = it.key; flyout.pinned = true })
                        }
                    }
                }
            }
        }
        aboveFooter()
        // The rail's bottom part, as the expanded sidebar's: a sheet in the chat's tone, no rule.
        Column(
            Modifier
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceContainerLow)
                .testTag("sidebar_rail_footer")
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = footer,
        )
    }
}

@Composable
private fun RailRule() {
    Box(Modifier.padding(vertical = (RAIL_RULE - 1.dp) / 2).width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
}

/** A plain rail button: the 34 dp hover square every rail item shares. */
@Composable
internal fun RailTile(
    onClick: () -> Unit,
    tag: String,
    label: String,
    selected: Boolean = false,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    badge: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.width(RAIL_WIDTH).height(RAIL_TILE), contentAlignment = Alignment.Center) {
        // The selected item's pill, on the rail's own edge (Finder / Xcode navigator style).
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(cs.primary),
            )
        }
        Box(
            Modifier
                .size(RAIL_TILE)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    when {
                        selected -> cs.onSurface.copy(alpha = 0.085f)
                        hovered -> cs.onSurface.copy(alpha = 0.05f)
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (badge != null) {
            Box(Modifier.size(RAIL_TILE), contentAlignment = Alignment.TopEnd) { badge() }
        }
    }
}

@Composable
private fun RailAgentTile(a: RailAgent, rig: AvatarRig?, onOpenSession: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Hovering a dozing Agent wakes it, as in the expanded list.
    val asleep = !a.online || (a.dozing && !hovered && !a.active)
    Box {
        RailTile(
            onClick = { a.sessionId?.let(onOpenSession) },
            tag = "rail_agent_${a.workspaceId}",
            label = a.name,
            selected = a.active,
            interaction = interaction,
            badge = if (a.unread) ({ UnreadDot(Modifier.offset(x = (-3).dp, y = 3.dp)) }) else null,
        ) {
            if (rig != null) {
                val state = when {
                    asleep -> "asleep"
                    a.working -> "working"
                    a.unread -> "waiting"
                    else -> "idle"
                }
                AvatarRigView(rig, state = state, hovered = hovered || a.active, seed = a.sessionId ?: a.workspaceId, size = 28)
            } else {
                AgentAvatar(a.name, online = !asleep, working = a.working, unread = a.unread, hovered = hovered || a.active, size = 28)
            }
        }
        var card by remember { mutableStateOf(false) }
        LaunchedEffect(hovered) { if (hovered) { delay(250); card = true } else card = false }
        if (card) {
            Popup(popupPositionProvider = rememberBesideRail(dy = 0.dp), properties = PopupProperties(focusable = false)) {
                AgentCard(a)
            }
        }
    }
}

@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(1.dp)
            .clip(CircleShape)
            .background(cs.primary),
    )
}

@Composable
private fun AgentCard(a: RailAgent) {
    val cs = MaterialTheme.colorScheme
    val preview = remember(a.preview) {
        a.preview?.text?.let(::sessionPreviewPlainText)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    }
    RailSurface(Modifier.width(250.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val t = archivedRelTime(a.preview?.ts)
                if (t.isNotEmpty()) Text(t, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontFamily = MonoFontFamily, fontSize = 10.5.sp)
            }
            val status = when {
                a.working -> "working…"
                a.unread -> "has something for you"
                !a.online -> "asleep"
                else -> null
            }
            if (status != null) {
                Text(status, color = if (a.working) cs.primary else cs.onSurfaceVariant, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                preview ?: "Always on",
                color = cs.onSurface.copy(alpha = 0.8f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun RailProjectTile(p: RailProject, flyout: RailFlyout, list: @Composable () -> Unit) {
    RailFlyoutTile(
        key = p.key,
        flyout = flyout,
        tag = "rail_project_${p.key}",
        label = p.name,
        selected = p.active,
        badge = when {
            p.unread > 0 -> ({ CountBadge(p.unread) })
            else -> null
        },
        tile = {
            Box {
                ProjectMonogram(p.name, 26.dp)
                if (p.working) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(1.5.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(10.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 1.5.dp)
                    }
                }
            }
        },
        content = list,
    )
}

/** A rail item that opens a list beside the rail: on hover with a pointer, on a tap by touch. */
@Composable
private fun RailFlyoutTile(
    key: String,
    flyout: RailFlyout,
    tag: String,
    label: String,
    selected: Boolean = false,
    badge: @Composable (() -> Unit)? = null,
    tile: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val pointer = LocalPointerAvailable.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered, pointer) {
        if (!pointer) return@LaunchedEffect
        if (hovered) flyout.tile = key else if (flyout.tile == key) flyout.tile = null
    }
    Box {
        RailTile(
            onClick = {
                if (flyout.open == key && flyout.pinned) flyout.close()
                else { flyout.open = key; flyout.pinned = true; flyout.drill = null }
            },
            tag = tag,
            label = label,
            selected = selected || flyout.open == key,
            interaction = interaction,
            badge = badge,
            content = tile,
        )
        if (flyout.open == key) {
            Popup(
                popupPositionProvider = rememberBesideRail(dy = (-8).dp),
                onDismissRequest = { if (flyout.pinned) flyout.close() },
                properties = PopupProperties(focusable = false),
            ) {
                val panel = remember { MutableInteractionSource() }
                val inPanel by panel.collectIsHoveredAsState()
                LaunchedEffect(inPanel) { flyout.panel = inPanel }
                Box(Modifier.hoverable(panel)) { content() }
            }
        }
    }
}

@Composable
private fun CountBadge(n: Int) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .offset(x = 3.dp, y = (-3).dp)
            .heightIn(min = 15.dp)
            .widthIn(min = 15.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHigh)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(cs.primary)
            .padding(horizontal = 3.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (n > 99) "99+" else "$n", color = cs.onPrimary, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A project's initials on a quiet tile in a hue of its own (stable per name, like a Finder tag). */
@Composable
internal fun ProjectMonogram(name: String, size: androidx.compose.ui.unit.Dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (bg, fg) = monogramColors(name.fold(0) { h, c -> h * 31 + c.code }.mod(8), dark)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(projectInitials(name), color = fg, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private val MONOGRAM_HUES = floatArrayOf(178f, 32f, 262f, 145f, 300f, 210f, 64f, 12f, 235f)

/**
 * Eight hand-picked tile tones (tile, letters) per theme: quiet, and none of them muddy — a warm
 * hue darkened by formula turns brown, so these are chosen, not computed.
 */
private fun monogramColors(i: Int, dark: Boolean): Pair<Color, Color> = if (dark) when (i) {
    0 -> Color(0xFF1E3B38) to Color(0xFF8FD6C8) // teal
    1 -> Color(0xFF1F3147) to Color(0xFF9CC4F0) // blue
    2 -> Color(0xFF2A2D4D) to Color(0xFFB3B8F5) // indigo
    3 -> Color(0xFF352A4A) to Color(0xFFCDB4F2) // violet
    4 -> Color(0xFF47283A) to Color(0xFFF0B0CE) // rose
    5 -> Color(0xFF24392A) to Color(0xFFA3D9AE) // green
    6 -> Color(0xFF42361F) to Color(0xFFEBCB8B) // amber
    else -> Color(0xFF2C3338) to Color(0xFFB9C6CF) // slate
} else when (i) {
    0 -> Color(0xFFD3EEE9) to Color(0xFF1F6B60)
    1 -> Color(0xFFD9E7F7) to Color(0xFF285A8C)
    2 -> Color(0xFFE0E2FA) to Color(0xFF3C438F)
    3 -> Color(0xFFEADFF8) to Color(0xFF5B3D8A)
    4 -> Color(0xFFF6DCE8) to Color(0xFF8C3460)
    5 -> Color(0xFFDCEFE0) to Color(0xFF2F6B3D)
    6 -> Color(0xFFF5EAD2) to Color(0xFF7A5A16)
    else -> Color(0xFFE1E6EA) to Color(0xFF3E4C57)
}

/** "Supermux Website" → "SW", "supermux" → "Su", "…/local/optikokuyucu" → "Op". */
internal fun projectInitials(name: String): String {
    val clean = pathLeaf(name).trim()
    val words = clean.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
        else -> "?"
    }
}

/**
 * The expanded footer's four icons, folded into one: theme, usage, devices and settings behind a
 * gear. Usage keeps its anchored popover, hung off the gear.
 */
@Composable
internal fun RailSettingsButton(
    appearance: AppearanceMode,
    onToggleTheme: () -> Unit,
    onUsage: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    usageOpen: Boolean,
    onUsageDismiss: () -> Unit,
    usageContent: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val darkNow = appearance != AppearanceMode.LIGHT
    Box {
        RailTile(onClick = { open = true }, tag = "rail_settings", label = "Settings") {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (darkNow) "Light theme" else "Dark theme") },
                leadingIcon = { Icon(if (darkNow) Icons.Filled.LightMode else Icons.Filled.DarkMode, null, Modifier.size(16.dp)) },
                onClick = { open = false; onToggleTheme() },
                modifier = Modifier.testTag("rail_settings_theme"),
            )
            DropdownMenuItem(
                text = { Text("Usage") },
                leadingIcon = { Icon(Icons.Filled.DataUsage, null, Modifier.size(16.dp)) },
                onClick = { open = false; onUsage() },
                modifier = Modifier.testTag("rail_settings_usage"),
            )
            DropdownMenuItem(
                text = { Text("Devices") },
                leadingIcon = { Icon(Icons.Filled.Devices, null, Modifier.size(16.dp)) },
                onClick = { open = false; onDevices() },
                modifier = Modifier.testTag("rail_settings_devices"),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Settings…") },
                leadingIcon = { Icon(Icons.Filled.Settings, null, Modifier.size(16.dp)) },
                onClick = { open = false; onSettings() },
                modifier = Modifier.testTag("rail_settings_open"),
            )
        }
        UsagePopover(expanded = usageOpen, onDismissRequest = onUsageDismiss, content = usageContent)
    }
}

// ── Lists ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RailSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.border(1.dp, cs.onSurface.copy(alpha = 0.08f), RoundedCornerShape(11.dp)),
        shape = RoundedCornerShape(11.dp),
        color = cs.surfaceContainerHighest,
        shadowElevation = 10.dp,
    ) { content() }
}

@Composable
private fun ProjectFlyout(
    p: RailProject,
    onOpen: (RailWorkspace) -> Unit,
    onShowSidebar: () -> Unit,
    flyout: RailFlyout,
    onBack: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val live = p.rows.filterNot { it.settled }
    val settled = p.rows.filter { it.settled }.sortedByDescending { it.lastTs ?: "" }
    RailSurface(Modifier.width(300.dp).testTag("rail_flyout_${p.key}")) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All projects", tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                ProjectMonogram(p.name, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(p.name, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(UnreadGreen))
                    Spacer(Modifier.width(4.dp))
                    Text("${p.unread} new", color = cs.onSurface.copy(alpha = 0.8f), fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Show in sidebar",
                    color = cs.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onShowSidebar)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.onSurface.copy(alpha = 0.07f)))
            Column(
                Modifier
                    .heightIn(max = flyoutListMaxHeight())
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                live.forEach { w -> FlyoutRow(w, onOpen) }
                if (settled.isNotEmpty()) {
                    Text("Settled", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 3.dp))
                    settled.forEach { w -> FlyoutRow(w, onOpen) }
                }
            }
        }
    }
}

/** The expanded list's unread green (SessionStatusRail), so a dot means the same thing everywhere. */
private val UnreadGreen @Composable get() = dev.supermux.ui.theme.LocalSemantics.current.success

@Composable
private fun FlyoutRow(w: RailWorkspace, onOpen: (RailWorkspace) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    w.active -> cs.onSurface.copy(alpha = 0.085f)
                    hovered -> cs.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onOpen(w) }
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag("rail_row_${w.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when {
                w.working -> CircularProgressIndicator(Modifier.size(11.dp), color = cs.primary, strokeWidth = 1.5.dp)
                w.unread -> Box(Modifier.size(7.dp).clip(CircleShape).background(UnreadGreen))
                else -> Box(Modifier.size(6.dp).clip(CircleShape).background(cs.onSurfaceVariant.copy(alpha = 0.3f)))
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            w.name,
            color = if (w.unread || w.active) cs.onSurface else cs.onSurface.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = if (w.unread || w.active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val t = archivedRelTime(w.lastTs)
        if (t.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(t, color = cs.onSurfaceVariant.copy(alpha = 0.55f), fontFamily = MonoFontFamily, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun AllProjectsFlyout(all: List<RailProject>, flyout: RailFlyout, onPick: (RailProject) -> Unit) {
    val cs = MaterialTheme.colorScheme
    RailSurface(Modifier.width(280.dp).testTag("rail_flyout_all")) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All projects", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${all.size}", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontFamily = MonoFontFamily, fontSize = 11.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.onSurface.copy(alpha = 0.07f)))
            Column(Modifier.heightIn(max = flyoutListMaxHeight()).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                all.forEach { p ->
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 5.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (hovered) cs.onSurface.copy(alpha = 0.05f) else Color.Transparent)
                            .hoverable(interaction)
                            .clickable(interactionSource = interaction, indication = null) { onPick(p) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProjectMonogram(p.name, 20.dp)
                        Spacer(Modifier.width(9.dp))
                        Text(p.name, color = cs.onSurface.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (p.unread > 0) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(UnreadGreen))
                            Spacer(Modifier.width(4.dp))
                            Text("${p.unread} new", color = cs.onSurface.copy(alpha = 0.8f), fontSize = 11.sp)
                        } else {
                            Text("${p.rows.size}", color = cs.onSurfaceVariant.copy(alpha = 0.55f), fontFamily = MonoFontFamily, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The sidebar's own band, for hosts with no title band: the toggle's row above the list and the
 * rail alike, so the toggle sits in one spot whichever is showing.
 */
val SIDEBAR_BAND = 44.dp

/**
 * The sidebar toggle for hosts with no title band: the desktop title band's glyph, mirrored to
 * point the way the sidebar will move, centred over the rail's column so it lines up with the
 * rail's items in both states.
 */
@Composable
internal fun SidebarBandToggle(collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (collapsed) "Show sidebar" else "Hide sidebar"
    Box(modifier.width(RAIL_WIDTH).height(SIDEBAR_BAND), contentAlignment = Alignment.Center) {
        RailTile(onClick = onToggle, tag = "sidebar_toggle", label = label) {
            Icon(
                Icons.AutoMirrored.Filled.MenuOpen,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp).graphicsLayer { scaleX = if (collapsed) -1f else 1f },
            )
        }
    }
}

/** A list beside the rail may run the window's height, less its header and a margin above and below. */
@Composable
private fun flyoutListMaxHeight(): Dp {
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return (window - 72.dp).coerceAtLeast(160.dp)
}

// ── Placement ────────────────────────────────────────────────────────────────────────────────

/** Beside the rail: a small gap past its right edge, level with the tile, clamped to the window. */
@Composable
private fun rememberBesideRail(dy: androidx.compose.ui.unit.Dp): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, dy) {
        val gap = with(density) { 6.dp.roundToPx() }
        val dyPx = with(density) { dy.roundToPx() }
        val margin = with(density) { 8.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                // The anchor is the rail-wide row the tile sits in: its right edge is the rail's.
                val x = anchorBounds.right + gap
                val y = (anchorBounds.top + dyPx).coerceAtMost(windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                return IntOffset(x, y)
            }
        }
    }
}

// ── Clock ────────────────────────────────────────────────────────────────────────────────────

/** Wall clock for "dozing" and "recent", nudged once a minute (both are minute-grained). */
@Composable
internal fun rememberRailClock(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}
