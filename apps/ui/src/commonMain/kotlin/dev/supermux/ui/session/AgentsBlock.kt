// The sidebar's Agents block — always-on Agents pinned above every project, in every view.
//
// An Agent is not a workspace in a project: it has an identity (avatar, name) and a presence
// (always on, working, waiting for you). So it never gets a folder header, a collapse chevron or
// a project menu — it gets its own card at the top of the list.
package dev.supermux.ui.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import dev.supermux.net.ArchivedDto
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.LogEntry
import dev.supermux.ui.sessionPreviewPlainText
import dev.supermux.ui.theme.MonoFontFamily

/** One Agent as the block draws it. */
internal data class AgentEntry(
    val id: String,
    val name: String,
    val working: Boolean,
    val unread: Boolean,
    val active: Boolean,
    /** False when the Agent's session is suspended / not running. */
    val online: Boolean,
    val muted: Boolean,
    val preview: LogEntry?,
    /** Quiet for AGENT_DOZE_AFTER_MS (agentDozing): drawn asleep until something happens or it is hovered. */
    val dozing: Boolean = false,
    /** The Agent's own avatar; null draws the default blob. */
    val rig: AvatarRig? = null,
    val sessionId: String? = null,
)

/** Header + one row per Agent, inside its own card. */
@Composable
internal fun AgentsBlock(
    agents: List<AgentEntry>,
    onOpen: (AgentEntry) -> Unit,
    contextEntries: (AgentEntry) -> List<RowContextMenuEntry>,
    onNewAgent: (() -> Unit)? = null,
    /** Restores archived Agents (the "Archived" fold); null hides the fold. */
    archiveActions: AgentsActions? = null,
    /** The archived Agents to list, owned by the store (no fetching here). */
    archived: List<ArchivedDto> = emptyList(),
    /** Agent id → avatar (the app-level cache, archived Agents included). */
    avatars: Map<String, AvatarRig> = emptyMap(),
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        // Same header as "Workspaces" (SessionsSectionHeader): title, then 28dp icon buttons.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Agents",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            val waiting = agents.count { it.unread }
            if (waiting > 0) {
                Text("$waiting waiting", color = cs.primary, fontSize = 11.sp, fontFamily = MonoFontFamily)
                Spacer(Modifier.width(4.dp))
            }
            if (onNewAgent != null) {
                // The header's compact 28dp buttons — the Workspaces ⋮ uses the same, so the two
                // trailing icons share one column.
                IconButton(onClick = onNewAgent, modifier = Modifier.size(28.dp).testTag("agents_new")) {
                    Icon(Icons.Filled.Add, contentDescription = "New Agent", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            agents.forEach { a ->
                AgentRow(a, onClick = { onOpen(a) }, menu = { contextEntries(a) })
            }
        }
        archiveActions?.let { ArchivedAgentsFold(archived, avatars, it) }
        // Sections are separated by space alone (like Finder/Mail sidebars), no rule.
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun AgentRow(a: AgentEntry, onClick: () -> Unit, menu: () -> List<RowContextMenuEntry>) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        a.active -> cs.onSurface.copy(alpha = 0.08f)
        hovered -> cs.onSurface.copy(alpha = 0.045f)
        else -> Color.Transparent
    }
    val previewText = remember(a.preview) {
        a.preview?.text?.let(::sessionPreviewPlainText)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    }
    val status = when {
        a.working -> "working…"
        !a.online -> "asleep"
        else -> null
    }
    RowContextMenu(items = menu) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .hoverable(interaction)
                .listRowFocus()
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .testTag("agent_row_${a.id}")
                // The avatar's left edge lines up with the section headers' text (14dp in).
                .padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Hovering (or opening) a dozing Agent wakes it up.
            val asleep = !a.online || (a.dozing && !hovered && !a.active)
            if (a.rig != null) {
                val state = when {
                    asleep -> "asleep"
                    a.working -> "working"
                    a.unread -> "waiting"
                    else -> "idle"
                }
                AvatarRigView(a.rig, state = state, hovered = hovered || a.active, seed = a.sessionId ?: a.id, size = 30)
            } else {
                AgentAvatar(a.name, online = !asleep, working = a.working, unread = a.unread, hovered = hovered || a.active, size = 30)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.name,
                        color = if (a.active || a.unread) cs.onSurface else cs.onSurface.copy(alpha = 0.88f),
                        fontSize = 13.sp,
                        fontWeight = if (a.active || a.unread) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val time = archivedRelTime(a.preview?.ts)
                    if (time.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(time, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontFamily = MonoFontFamily, fontSize = 10.5.sp, maxLines = 1)
                    }
                    if (a.unread) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary))
                    }
                    // No right-click on touch: the same entries behind a ⋮.
                    if (!LocalContextMenuAvailable.current) {
                        Spacer(Modifier.width(2.dp))
                        RowOverflowMenu(entries = menu(), newChatTag = "agent_row_menu_${a.id}")
                    }
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    status ?: previewText ?: "Always on",
                    color = when {
                        a.working -> cs.primary
                        a.unread -> cs.onSurface.copy(alpha = 0.75f)
                        else -> cs.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How long an Agent stays quiet before it is drawn asleep. */
internal const val AGENT_DOZE_AFTER_MS = 5 * 60_000L

/**
 * An Agent is never suspended, so the sidebar shows it asleep once nothing has happened in its
 * chat for [AGENT_DOZE_AFTER_MS] (the caller also keeps it awake while working or waiting).
 * No message at all (a fresh Agent) counts as quiet.
 */
internal fun agentDozing(lastMessageTs: String?, now: Long): Boolean {
    val last = parseChatTs(lastMessageTs) ?: return true
    return now - last >= AGENT_DOZE_AFTER_MS
}

/**
 * "Archived · N" under the Agents: every archived Agent, asleep, with Restore. The list and the
 * avatars come from the store and the app's avatar cache, and the open state is saveable, so
 * scrolling the fold away and back neither refetches nor flashes.
 */
@Composable
private fun ArchivedAgentsFold(list: List<ArchivedDto>, avatars: Map<String, AvatarRig>, actions: AgentsActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (list.isEmpty()) return
    ArchivedFoldButton(count = list.size, expanded = open, onClick = { open = !open })
    if (open) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            list.forEach { a -> ArchivedAgentRow(a, avatars[a.id], actions) }
        }
    }
}

@Composable
private fun ArchivedAgentRow(a: ArchivedDto, rig: AvatarRig?, actions: AgentsActions) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 3.dp)
            .testTag("agent_archived_${a.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            rig?.let { AvatarRigView(it, state = "asleep", seed = a.id, size = 26) }
                ?: AgentAvatar(a.name, online = false, working = false, size = 26)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ago = archivedRelTime(a.killed_at)
            Text(
                if (failed) "Couldn't restore" else if (ago.isEmpty()) "Archived" else "Archived $ago ago".replace("now ago", "just now"),
                color = if (failed) cs.error else cs.onSurfaceVariant.copy(alpha = 0.55f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cs.onSurface.copy(alpha = 0.06f))
                .clickable(enabled = !busy) {
                    busy = true
                    failed = false
                    scope.launch {
                        // On success the store's archive list drops it (sessions change → refresh).
                        val ok = actions.restore(a.id)
                        busy = false
                        if (!ok) failed = true
                    }
                }
                .padding(horizontal = 10.dp)
                .testTag("agent_restore_${a.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (busy) "Restoring…" else "Restore", color = cs.onSurface, fontSize = 12.sp)
        }
    }
}
