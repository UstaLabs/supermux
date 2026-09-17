// The one workspace row every host renders (cluster F3) — desktop's sidebar row, on touch too.
//
// Hover affordance + right-click menu where there is a pointer; where there is no context menu
// (touch, or Android with a keyboard/mouse) the same row shows a visible ⋮ overflow, so rename /
// new chat / mute / archive (and Restore on an archived row) stay reachable everywhere.
package dev.supermux.ui.session

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.proto.LogEntry
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.host.HostBadge
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.theme.softElevation
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.workspace.WorkspaceActivity

/**
 * Labels offered by a workspace row's right-click menu (Pointer) — the same three actions the
 * Touch row exposes through its overflow menu and swipe.
 * Extracted so chrome tests can assert rename/mute/archive without driving a real context menu.
 * Reorder is drag-only (no Move up / Move down) — same on both hosts.
 */
fun workspaceRowContextLabels(
    mute: Boolean = false,
): List<String> = buildList {
    add("Rename")
    add(if (mute) "Unmute" else "Mute")
    add("Archive")
}

/** Labels offered by an archived workspace row's right-click menu. */
fun archivedWorkspaceRowContextLabels(): List<String> = listOf("Restore")

/**
 * One workspace row.
 *
 * Desktop's sidebar row on every host: status rail, name, multi-agent mark, project tag, relative
 * time, host badge, lifecycle badge, message preview and branch — lean by design, no per-row
 * avatar, path omitted (the group header owns it).
 *
 * @param model the shared [WorkspaceRowModel] both lists derive with [deriveWorkspaceRow].
 * @param preview last message of the primary session — Pointer only (the phone row shows the path).
 * @param lastReadAt primary session's last-read stamp; with [preview] it drives the Pointer row's
 *   unread state (the sidebar's unread is primary-only; the Touch row uses `model.unread`, which is
 *   any chat of the workspace).
 * @param dropHover true while a tab dragged out of the layout hovers this row (desktop).
 * @param onRowBounds reports the row's root bounds so a tab drag can hit-test it (desktop).
 */
@Composable
fun WorkspaceRow(
    model: WorkspaceRowModel,
    active: Boolean,
    modifier: Modifier = Modifier,
    // ── Pointer-branch inputs (desktop's sidebar row) ──────────────────────────────────────────
    preview: LogEntry? = null,
    lastReadAt: String? = null,
    /** Lifecycle status of the primary session (`suspended`, …) — SessionRow badge. */
    sessionStatus: String? = null,
    projectTag: String? = null,
    dropHover: Boolean = false,
    onRowBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    // ── Shared ────────────────────────────────────────────────────────────────────────────────
    host: HostView? = null,
    mute: Boolean = false,
    /** Applied outside the clickable so the drag handle can own the gesture. */
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    /** Elevation while the row is being dragged by the shared reorder. */
    isDragging: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    // ── Unused since the rows unified (kept so callers compile) ──
    openSwipeRowId: String? = null,
    onOpenSwipeRowChange: (String?) -> Unit = {},
    rowShape: Shape = RoundedCornerShape(Radii.md),
    outerPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    rowColor: Color? = null,
    childrenExpanded: Boolean = false,
    onToggleChildren: (() -> Unit)? = null,
    onChildClick: (String) -> Unit = {},
) {
    PointerWorkspaceRow(
        model = model,
        active = active,
        modifier = modifier,
        preview = preview,
        lastReadAt = lastReadAt,
        sessionStatus = sessionStatus,
        projectTag = projectTag,
        dropHover = dropHover,
        onRowBounds = onRowBounds,
        host = host,
        mute = mute,
        dragModifier = dragModifier,
        interactionSource = interactionSource,
        isDragging = isDragging,
        onClick = onClick,
        onRename = onRename,
        onNewChat = onNewChat,
        onKill = onKill,
        onToggleMute = onToggleMute,
    )

}

/** Desktop's sidebar row, verbatim. */
@Composable
private fun PointerWorkspaceRow(
    model: WorkspaceRowModel,
    active: Boolean,
    modifier: Modifier,
    preview: LogEntry?,
    lastReadAt: String?,
    sessionStatus: String?,
    projectTag: String?,
    dropHover: Boolean,
    onRowBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)?,
    host: HostView?,
    mute: Boolean,
    dragModifier: Modifier,
    interactionSource: MutableInteractionSource?,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onNewChat: () -> Unit,
    onKill: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val w = model.workspace
    val working = model.activity == WorkspaceActivity.WORKING
    val hasUnread = sessionListShowsUnread(
        active = active,
        working = working,
        lastMessageTs = preview?.ts,
        lastReadAt = lastReadAt,
    )

    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val elevation by animateDpAsState(
        if (isDragging) 6.dp else 0.dp,
        label = "workspace-drag-elevation",
    )

    val rowBg = when {
        dropHover -> cs.primary.copy(alpha = 0.18f)
        active -> cs.surfaceContainer
        else -> Color.Transparent
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp)
        .then(if (active && !isDragging) Modifier.softElevation(radius = Radii.md) else Modifier)
        .clip(RoundedCornerShape(6.dp))
        .background(rowBg)
        .onGloballyPositioned { coords -> onRowBounds?.invoke(coords.boundsInRoot()) }
        .hoverable(interaction)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = !isDragging,
            onClick = onClick,
        )

    Box(
        modifier
            .then(dragModifier)
            .fillMaxWidth(),
    ) {
    RowContextMenu(
        items = {
            workspaceRowContextLabels(mute = mute).map { label ->
                RowContextMenuEntry(label) {
                    when (label) {
                        "Rename" -> onRename()
                        "Mute", "Unmute" -> onToggleMute()
                        "Archive" -> onKill()
                    }
                }
            }
        },
    ) {
        Surface(
            tonalElevation = elevation,
            shadowElevation = elevation,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
        Row(
            rowModifier
                .testTag(WorkspaceListTestIds.row(w.id))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SessionStatusRail(
                git = model.git,
                working = working,
                bgOpen = 0,
                unread = hasUnread,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.name,
                        color = cs.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (active || hasUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (model.multiAgent) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .testTag(WorkspaceListTestIds.multiAgent(w.id)),
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
                    val timeStr = archivedRelTime(preview?.ts)
                    if (timeStr.isNotEmpty()) {
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            timeStr,
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 10.sp,
                        )
                    }
                    // Where the right-click menu is inert (Android in Pointer mode), the row's
                    // actions need somewhere to live — the phone row's overflow, same entries.
                    if (!LocalContextMenuAvailable.current) {
                        Spacer(Modifier.width(Space.xs))
                        RowOverflowMenu(
                            entries = buildList {
                                add(RowContextMenuEntry("Rename", onRename))
                                add(RowContextMenuEntry("New chat here", onNewChat))
                                add(RowContextMenuEntry(if (mute) "Unmute" else "Mute", onToggleMute))
                                add(RowContextMenuEntry("Archive", onKill))
                            },
                            newChatTag = WorkspaceListTestIds.ROW_NEW_CHAT,
                        )
                    }
                }

                if (host != null) {
                    Spacer(Modifier.height(2.dp))
                    HostBadge(host)
                }

                // Status badge — SessionRow parity: non-null and not "active".
                val status = sessionStatus
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

                // Preview: last message from primary session — SessionRow truncation + styling.
                // No workdir fallback: group header owns the path (do not bring path back).
                val previewText = preview?.text?.replace("\n", " ")?.take(80)
                if (previewText != null) {
                    Text(
                        previewText,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                }
            }
        }
        } // Surface
    } // RowContextMenu
    } // Box
}

/** "Show N archived" / "Hide N archived" — shared by flat + per-group folds on both hosts. */
@Composable
fun ArchivedFoldButton(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(WorkspaceListTestIds.ARCHIVED_FOLD),
    ) {
        Text(
            if (expanded) "Hide $count archived" else "Show $count archived",
            fontSize = 12.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

/**
 * One archived workspace row on every host: a compact name-only row with Restore in the right-click
 * menu, or in a visible overflow where there is no context menu.
 */
@Composable
fun ArchivedWorkspaceRow(
    model: ArchivedWorkspaceRowModel,
    onSelect: () -> Unit,
    onRestore: () -> Unit,
    active: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val w = model.workspace
    val rowBg = if (active) cs.surfaceContainer else Color.Transparent
    RowContextMenu(
        items = {
            archivedWorkspaceRowContextLabels().map { label ->
                RowContextMenuEntry(label) { if (label == "Restore") onRestore() }
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(rowBg)
                .clickable(onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .testTag(WorkspaceListTestIds.archived(w.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.name,
                fontSize = 13.sp,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!LocalContextMenuAvailable.current) {
                Spacer(Modifier.width(Space.xs))
                RowOverflowMenu(entries = listOf(RowContextMenuEntry("Restore", onRestore)))
            }
        }
    }
}

/**
 * The `⋮` overflow: the phone row's action menu, reused by the Pointer row wherever
 * [LocalContextMenuAvailable] is false (Android with a keyboard or mouse attached).
 */
@Composable
internal fun RowOverflowMenu(
    entries: List<RowContextMenuEntry>,
    newChatTag: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box {
        var menu by remember { mutableStateOf(false) }
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "More",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp).clickable { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (entry in entries) {
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    modifier = if (newChatTag != null && entry.label == "New chat here") {
                        Modifier.testTag(newChatTag)
                    } else {
                        Modifier
                    },
                    onClick = {
                        menu = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

