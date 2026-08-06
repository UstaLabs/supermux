// Copy-adapted from SessionListPanel — same group headers, status rail, row chrome.
// Spec §13.6: the sidebar lists workspaces. One-chat workspaces look like today's
// session row; multi-chat workspaces add child rows + a multi-agent mark.
//
// Design rules (non-negotiable, from the reverted session-list redesign):
//  - No large per-row avatars — SessionStatusRail is the leading visual.
//  - Keep branch and git status on the row.
//  - Rows stay lean — no heavier than today's session row.
//  - No animation on this surface (100+/day).
//  - Geist for language, Geist Mono for machine content (paths, branches).
//  - One teal accent, used for state and agency only.
package dev.supermux.desktop.shell

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.session.PathGroupHeader
import dev.supermux.desktop.session.SessionDragReorderState
import dev.supermux.desktop.session.SessionStatusRail
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.softElevation
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.formatWorkdir
import dev.supermux.session.inferHomeDir
import dev.supermux.workspace.WorkspaceActivity
import dev.supermux.workspace.chatSessionIds
import dev.supermux.workspace.groupWorkspaces
import dev.supermux.workspace.isMultiAgent
import dev.supermux.workspace.workspaceActivity

/**
 * Workspace sidebar (spec §13.6). Grouped by project via [groupWorkspaces];
 * Personal Assistants pin at the top when [sessionRoles] marks a primary session
 * as `personal_assistant`.
 *
 * Not wired into [AppShell] yet — row design awaits user approval via the
 * [WorkspaceListPreview] fixture.
 */
@Composable
fun WorkspaceListPanel(
    workspaces: List<WorkspaceDto>,
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    agentState: Map<String, AgentStatus> = emptyMap(),
    /** session id → display name, for multi-agent child rows. */
    sessionNames: Map<String, String> = emptyMap(),
    /**
     * session id → role. A workspace whose primary session has
     * `role == "personal_assistant"` lands in the pinned PA group.
     */
    sessionRoles: Map<String, String?> = emptyMap(),
    /**
     * session id → git lite status, for the status rail (parity with session
     * rows that pass [SessionInfo.git]).
     */
    sessionGit: Map<String, GitLiteStatusDto?> = emptyMap(),
    onOpenSession: (workspaceId: String, sessionId: String) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onArchive: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    onNewWorkspace: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val effectiveHome = inferHomeDir(workspaces.firstOrNull()?.workdir) ?: home
    val groups = remember(workspaces, effectiveHome, sessionRoles) {
        groupWorkspaces(workspaces, effectiveHome) { w ->
            val sid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
            sid != null && sessionRoles[sid] == "personal_assistant"
        }
    }

    var renameTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    var archiveTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragReorder = remember(listState) {
        SessionDragReorderState(scope, listState) { ids -> onReorder(ids) }
    }

    fun groupOrder(ws: List<WorkspaceDto>): List<WorkspaceDto> {
        val live = dragReorder.liveOrder
        if (live == null || dragReorder.draggingId !in ws.map { it.id }) return ws
        val byId = ws.associateBy { it.id }
        return live.mapNotNull { byId[it] }
    }

    fun dragMod(ws: List<WorkspaceDto>, can: Boolean): (String) -> Modifier = { id ->
        val label = ws.firstOrNull { it.id == id }?.name ?: id
        dragReorder.rowModifier(
            id,
            { dragReorder.liveOrder ?: ws.map { it.id } },
            enabled = can,
            label = label,
        )
    }

    fun reorderWithin(list: List<WorkspaceDto>, id: String, delta: Int) {
        val ids = list.map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in ids.indices) return
        java.util.Collections.swap(ids, i, j)
        onReorder(ids)
    }

    Column(
        modifier
            .background(cs.surfaceContainerHigh)
            .fillMaxSize(),
    ) {
        // Header affordance — same chrome as SessionListPanel's new-session card,
        // wording adjusted for workspaces. Caller wires onNewWorkspace (or no-ops).
        NewWorkspaceListRow(onClick = onNewWorkspace)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .testTag("workspaces_list")
                    .fillMaxSize(),
            ) {
                if (groups.isEmpty()) {
                    item(key = "empty_hint") {
                        Text(
                            "No workspaces yet",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space.md, vertical = Space.md),
                        )
                    }
                } else {
                    groups.forEach { g ->
                        item(key = "h:${g.key}") {
                            PathGroupHeader(g.label, g.workspaces.size)
                        }
                        val ordered = groupOrder(g.workspaces)
                        // PA group is not drag-reorderable in the session list's PA
                        // flat section either; keep project groups reorderable.
                        val canDrag = g.key != PA_GROUP_KEY
                        items(ordered, key = { it.id }) { w ->
                            val activity = workspaceActivity(w, agentState)
                            val primarySid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
                            val git = primarySid?.let { sessionGit[it] }
                            Column(Modifier.fillMaxWidth()) {
                                WorkspaceRow(
                                    w = w,
                                    active = w.id == activeId,
                                    activity = activity,
                                    git = git,
                                    pathLabel = formatWorkdir(w.repoRoot ?: w.workdir, effectiveHome),
                                    modifier = dragMod(g.workspaces, canDrag)(w.id),
                                    onClick = { onOpen(w.id) },
                                    onRename = { renameTarget = w; renameText = w.name },
                                    onArchive = { archiveTarget = w },
                                    onMoveUp = if (canDrag) {
                                        { reorderWithin(ordered, w.id, -1) }
                                    } else null,
                                    onMoveDown = if (canDrag) {
                                        { reorderWithin(ordered, w.id, +1) }
                                    } else null,
                                )
                                if (w.isMultiAgent()) {
                                    Column(
                                        Modifier
                                            .testTag("workspace-children-${w.id}")
                                            .fillMaxWidth()
                                            .padding(start = 28.dp, end = 8.dp),
                                    ) {
                                        for (sid in w.chatSessionIds()) {
                                            val childName = sessionNames[sid] ?: sid
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
                    }
                }
                item(key = "bottom_spacer") { Spacer(Modifier.height(Space.lg)) }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename workspace") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target.id, renameText.trim())
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("Archive workspace?") },
            text = {
                Text("This archives \"${target.name}\" and ends its agents. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onArchive(target.id)
                    archiveTarget = null
                }) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One workspace row: status rail, name, multi-agent mark, branch, path —
 * lean by design (SessionRow parity). No per-row avatar.
 */
@Composable
fun WorkspaceRow(
    w: WorkspaceDto,
    active: Boolean,
    activity: WorkspaceActivity,
    git: GitLiteStatusDto? = null,
    pathLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onArchive: () -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val working = activity == WorkspaceActivity.WORKING

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
            buildList {
                add(ContextMenuItem("Rename") { onRename() })
                onMoveUp?.let { add(ContextMenuItem("Move up") { it() }) }
                onMoveDown?.let { add(ContextMenuItem("Move down") { it() }) }
                add(ContextMenuItem("Archive") { onArchive() })
            }
        },
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
                unread = false,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        w.name,
                        color = cs.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (w.isMultiAgent()) {
                        // Box holds the testTag so it stays findable without useUnmergedTree
                        // (Icon merges semantics into the row otherwise).
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
                } else {
                    Spacer(Modifier.height(Space.xs))
                }

                // Path label — same italic mono fallback SessionRow uses for workdir.
                Text(
                    pathLabel,
                    color = cs.onSurfaceVariant,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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

/** Header card — workspace analogue of [dev.supermux.desktop.session.NewSessionListRow]. */
@Composable
private fun NewWorkspaceListRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .testTag("new_workspace_row")
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs)
            .padding(top = Space.md)
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
            Text(
                "+",
                color = cs.primary,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Start a new workspace",
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                "Pick a project and open a chat",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}
