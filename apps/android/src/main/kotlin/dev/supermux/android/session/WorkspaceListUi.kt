package dev.supermux.android.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.WorkspaceActivity
import dev.supermux.ui.session.SessionStatusRail

@Composable
fun WorkspaceRow(
    model: WorkspaceRowModel,
    active: Boolean,
    hostBadge: dev.supermux.host.HostView? = null,
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    openSwipeRowId: String? = null,
    onOpenSwipeRowChange: (String?) -> Unit = {},
    isDragging: Boolean = false,
    rowShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radii.md),
    outerPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    rowColor: Color? = null,
    childrenExpanded: Boolean = false,
    onToggleChildren: (() -> Unit)? = null,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onChildClick: (String) -> Unit = {},
    mute: Boolean = false,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val w = model.workspace
    val working = model.activity == WorkspaceActivity.WORKING
    val hasUnread = model.unread && model.activity != WorkspaceActivity.WORKING
    val rowInteraction = interactionSource ?: remember { MutableInteractionSource() }
    val surfaceColor = rowColor ?: if (active) cs.surfaceContainer else cs.surfaceContainerHigh
    val elevation by androidx.compose.animation.core.animateDpAsState(
        if (isDragging) 6.dp else 0.dp,
        label = "workspace-drag-elevation",
    )
    val startAction = if (mute) SessionSwipeAction.Unmute else SessionSwipeAction.Mute
    val endAction = SessionSwipeAction.Settle

    fun label(action: SessionSwipeAction) = when (action) {
        SessionSwipeAction.Mute -> "Mute"
        SessionSwipeAction.Unmute -> "Unmute"
        SessionSwipeAction.Settle -> "Archive"
        else -> null
    }

    fun icon(action: SessionSwipeAction) = when (action) {
        SessionSwipeAction.Mute -> R.drawable.ic_volume_x
        SessionSwipeAction.Unmute -> R.drawable.ic_volume_2
        SessionSwipeAction.Settle -> R.drawable.ic_archive
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = dragModifier
                .fillMaxWidth()
                .padding(outerPadding),
        ) {
            SwipeActionRow(
                rowId = w.id,
                openRowId = openSwipeRowId,
                onOpenRowChange = onOpenSwipeRowChange,
                startLabel = label(startAction),
                endLabel = label(endAction),
                startIcon = icon(startAction),
                endIcon = icon(endAction),
                onStartAction = {
                    haptic.perform(HapticKind.Tick)
                    onToggleMute()
                },
                onEndAction = {
                    haptic.perform(HapticKind.Confirm)
                    onKill()
                },
                enabled = !isDragging,
                startColor = Color(c.warning).copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth().clip(rowShape),
            ) {
                Surface(
                    tonalElevation = elevation,
                    shadowElevation = elevation,
                    shape = rowShape,
                    color = surfaceColor,
                    onClick = {
                        onOpenSwipeRowChange(null)
                        haptic.perform(HapticKind.Tick)
                        onClick()
                    },
                    interactionSource = rowInteraction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag(WorkspaceListTestIds.row(w.id))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        SessionStatusRail(
                            git = model.git,
                            working = working,
                            unread = hasUnread,
                            unreadTestTag = "workspace_unread_${w.id}",
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (model.multiAgent && onToggleChildren != null) {
                                    val rotation by animateFloatAsState(
                                        targetValue = if (childrenExpanded) 0f else -90f,
                                        label = "wsChildChevron",
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_down),
                                        contentDescription = if (childrenExpanded) "Hide chats" else "Show chats",
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .rotate(rotation)
                                            .clickable(role = Role.Button, onClick = onToggleChildren),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    model.name,
                                    color = cs.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = if (active || hasUnread) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model.multiAgent) {
                                    Text(
                                        "✦",
                                        color = cs.primary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.testTag(WorkspaceListTestIds.multiAgent(w.id)),
                                    )
                                }
                                if (hostBadge != null) {
                                    Spacer(Modifier.width(Space.sm))
                                    dev.supermux.ui.host.HostBadge(hostBadge)
                                }
                                Box {
                                    var menu by remember { mutableStateOf(false) }
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more_vert),
                                        contentDescription = "More",
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable {
                                                haptic.perform(HapticKind.Tick)
                                                menu = true
                                            },
                                    )
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                menu = false
                                                onRename()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("New chat here") },
                                            modifier = Modifier.testTag(WorkspaceListTestIds.ROW_NEW_CHAT),
                                            onClick = {
                                                menu = false
                                                onNewChat()
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                model.pathLabel,
                                color = cs.onSurfaceVariant,
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            GitBadgeRow(model.git)
                        }
                    }
                }
            }
        }
        if (model.multiAgent && childrenExpanded) {
            Column(
                Modifier
                    .testTag(WorkspaceListTestIds.children(w.id))
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 8.dp),
            ) {
                for (child in model.children) {
                    Text(
                        child.name,
                        color = cs.onSurface,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.perform(HapticKind.Tick)
                                onChildClick(child.sessionId)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

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

@Composable
fun ArchivedWorkspaceRow(
    model: ArchivedWorkspaceRowModel,
    onSelect: () -> Unit,
    onRestore: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(WorkspaceListTestIds.archived(model.workspace.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            Text(
                model.pathLabel,
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = MonoFontFamily,
                maxLines = 1,
            )
            val ended = relTime(model.archivedAt)
            if (ended.isNotEmpty()) {
                Text("Archived $ended", color = cs.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        Box {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = "More",
                tint = cs.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { menu = true },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Restore") },
                    onClick = { menu = false; onRestore() },
                )
            }
        }
    }
}
