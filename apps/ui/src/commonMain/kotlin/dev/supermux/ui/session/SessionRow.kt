// The session row and the project-group header both lists render (cluster F4).
//
// Same split as the workspace row (F3): under [InputMode.Pointer] this is desktop's lean sidebar
// row — status rail, name + time, lifecycle badge, message/workdir preview, actions in a
// right-click menu; under [InputMode.Touch] it is Android's phone card — a [SwipeActionRow] over a
// tonal surface with 15sp type and the draft pencil.
//
// As in F3, the right-click menu is inert where [LocalContextMenuAvailable] is false (Android with
// a keyboard or mouse attached), so the Pointer row falls back to the visible `⋮` overflow there
// rather than stranding Rename / Mute / Settle.
package dev.supermux.ui.session

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.session.SectionKey
import dev.supermux.session.effectiveUserStatus
import dev.supermux.session.formatWorkdir
import dev.supermux.session.inferHomeDir
import dev.supermux.session.sectionKey
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.ui.TestIds
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

/**
 * Labels a session row's right-click / overflow menu offers, by lifecycle section.
 * Extracted so chrome tests can assert them without driving a real context menu (F3 precedent).
 */
fun sessionRowContextLabels(
    s: SessionInfo,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
): List<String> = when (s.sectionKey()) {
    SectionKey.SETTLED -> listOf("Resume")
    SectionKey.DRAFT -> listOf("Open draft", "Discard")
    SectionKey.IN_PROGRESS -> buildList {
        add("Rename")
        add(if (s.mute == true) "Unmute" else "Mute")
        if (canMoveUp) add("Move up")
        if (canMoveDown) add("Move down")
        add("Settle")
    }
}

/**
 * Project-group header.
 *
 * Pointer: desktop's shell-style row — a colour-hashed letter tile, the path LEAF, a count and a
 * rotating chevron. Touch: Android's row — chevron first, the full mono group label, the count,
 * and a ≥48dp tap target with a haptic.
 */
@Composable
fun PathGroupHeader(
    label: String,
    count: Int,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        label = "groupChevronRotation",
    )
    if (LocalInputMode.current == InputMode.Touch) {
        val haptic = rememberHaptics()
        val clickable = if (onToggle != null) {
            Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (collapsed) "Expand" else "Collapse",
                ) { haptic.perform(HapticKind.Tick); onToggle() }
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
                imageVector = Icons.Filled.KeyboardArrowDown,
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
        return
    }
    val leaf = label.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: label
    val letter = leaf.firstOrNull()?.uppercaseChar()?.toString() ?: "·"
    // Stable-ish pastel from label hash so adjacent groups don't all share the same tile.
    val hue = ((leaf.hashCode() ushr 1) % 360).toFloat()
    val tile = Color.hsl(hue, 0.45f, 0.42f)
    val clickable = if (onToggle != null) {
        Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                role = Role.Button,
                onClickLabel = if (collapsed) "Expand" else "Collapse",
            ) { onToggle() }
            .semantics {
                contentDescription = if (collapsed) "Expand $leaf" else "Collapse $leaf"
                stateDescription = if (collapsed) "Collapsed" else "Expanded"
            }
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            leaf,
            color = cs.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 1) {
            Text("$count", color = cs.onSurfaceVariant.copy(alpha = 0.55f), fontSize = 10.sp)
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
    }
}

/** Full-width "Start a new session" card. Tag `new_session_row` on both hosts. */
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
            Text(
                "Start a new session",
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                "Pick a project and send your first message",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * One session row.
 *
 * @param preview last message; drives the relative time and the preview line (workdir fallback).
 * @param dragModifier applied outside the click/reveal handlers so the reorder handle owns the drag.
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
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    isDragging: Boolean = false,
    // ── Touch-branch inputs ───────────────────────────────────────────────────────────────────
    openSwipeRowId: String? = null,
    onOpenSwipeRowChange: (String?) -> Unit = {},
    rowShape: Shape = RoundedCornerShape(Radii.md),
    outerPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    /** Grouped mode passes a card tone so joined rows read as inset cards. */
    rowColor: Color? = null,
    // ── Actions ───────────────────────────────────────────────────────────────────────────────
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onKill: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onResume: () -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    if (LocalInputMode.current == InputMode.Touch) {
        TouchSessionRow(
            s = s,
            active = active,
            preview = preview,
            lastReadAt = lastReadAt,
            working = working,
            bgOpen = bgOpen,
            host = host,
            projectTag = projectTag,
            modifier = modifier,
            dragModifier = dragModifier,
            interactionSource = interactionSource,
            isDragging = isDragging,
            openSwipeRowId = openSwipeRowId,
            onOpenSwipeRowChange = onOpenSwipeRowChange,
            rowShape = rowShape,
            outerPadding = outerPadding,
            rowColor = rowColor,
            onClick = onClick,
            onKill = onKill,
            onToggleMute = onToggleMute,
            onResume = onResume,
        )
    } else {
        PointerSessionRow(
            s = s,
            active = active,
            preview = preview,
            lastReadAt = lastReadAt,
            working = working,
            bgOpen = bgOpen,
            host = host,
            projectTag = projectTag,
            modifier = modifier.then(dragModifier),
            onClick = onClick,
            onRename = onRename,
            onKill = onKill,
            onToggleMute = onToggleMute,
            onResume = onResume,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )
    }
}

/** Desktop's sidebar session row, verbatim. */
@Composable
private fun PointerSessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry?,
    lastReadAt: String?,
    working: Boolean,
    bgOpen: Int,
    host: HostView?,
    projectTag: String?,
    modifier: Modifier,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
    onToggleMute: () -> Unit,
    onResume: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme

    // Server-authoritative unread mark (shared sessionListShowsUnread — spinner wins while working).
    val hasUnread = sessionListShowsUnread(
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

    fun entries(): List<RowContextMenuEntry> = when (s.sectionKey()) {
        SectionKey.SETTLED -> listOf(RowContextMenuEntry("Resume", onResume))
        SectionKey.DRAFT -> listOf(
            RowContextMenuEntry("Open draft", onClick),
            RowContextMenuEntry("Discard", onKill),
        )
        SectionKey.IN_PROGRESS -> buildList {
            add(RowContextMenuEntry("Rename", onRename))
            add(RowContextMenuEntry(if (s.mute == true) "Unmute" else "Mute", onToggleMute))
            onMoveUp?.let { add(RowContextMenuEntry("Move up", it)) }
            onMoveDown?.let { add(RowContextMenuEntry("Move down", it)) }
            add(RowContextMenuEntry("Settle", onKill))
        }
    }

    // Two tags: the shared `session-row:<id>` vocabulary on the row itself, and desktop's original
    // `session_row_<id>` on the wrapper so nothing that knew the old name loses its handle.
    Box(modifier.testTag("session_row_${s.id}")) {
        RowContextMenu(items = { entries() }) {
            Row(
                rowModifier
                    .testTag(TestIds.sessionRow(s.id))
                    // Desktop-compact: 8dp vertical vs Android's 10dp.
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (s.effectiveUserStatus() == "draft") {
                    Text(
                        "✎",
                        color = cs.primary.copy(alpha = 0.85f),
                        fontSize = 13.sp,
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
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.name,
                            color = cs.onSurface,
                            fontSize = 13.sp,
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
                        // actions need somewhere to live — same rule as the workspace row (F3).
                        if (!LocalContextMenuAvailable.current) {
                            Spacer(Modifier.width(Space.xs))
                            RowOverflowMenu(entries = entries())
                        }
                    }

                    // Per-host badge (merged fleet list, multi-host only).
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
                        Text(status, color = badgeColor, fontFamily = MonoFontFamily, fontSize = 10.sp)
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
                            fontSize = 10.sp,
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

/** Android's phone session card, verbatim. */
@Composable
private fun TouchSessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry?,
    lastReadAt: String?,
    working: Boolean,
    bgOpen: Int,
    host: HostView?,
    projectTag: String?,
    modifier: Modifier,
    dragModifier: Modifier,
    interactionSource: MutableInteractionSource?,
    isDragging: Boolean,
    openSwipeRowId: String?,
    onOpenSwipeRowChange: (String?) -> Unit,
    rowShape: Shape,
    outerPadding: PaddingValues,
    rowColor: Color?,
    onClick: () -> Unit,
    onKill: () -> Unit,
    onToggleMute: () -> Unit,
    onResume: () -> Unit,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val hasUnread = sessionListShowsUnread(
        active = active,
        working = working,
        lastMessageTs = preview?.ts,
        lastReadAt = lastReadAt,
    )
    val rowInteraction = interactionSource ?: remember { MutableInteractionSource() }
    val actions = sessionSwipeActions(s)
    val surfaceColor = rowColor ?: if (active) cs.surfaceContainer else cs.surfaceContainerHigh

    val elevation by animateDpAsState(
        if (isDragging) 6.dp else 0.dp,
        label = "session-drag-elevation",
    )

    fun label(action: SessionSwipeAction?): String? = when (action) {
        SessionSwipeAction.Mute -> "Mute"
        SessionSwipeAction.Unmute -> "Unmute"
        SessionSwipeAction.Settle -> "Settle"
        SessionSwipeAction.Edit -> "Edit"
        SessionSwipeAction.Discard -> "Discard"
        SessionSwipeAction.Activate -> "Activate"
        null -> null
    }

    fun runAction(action: SessionSwipeAction?) {
        when (action) {
            SessionSwipeAction.Mute, SessionSwipeAction.Unmute -> {
                haptic.perform(HapticKind.Tick)
                onToggleMute()
            }
            SessionSwipeAction.Settle, SessionSwipeAction.Discard -> {
                haptic.perform(HapticKind.Confirm)
                onKill()
            }
            SessionSwipeAction.Edit -> {
                haptic.perform(HapticKind.Tick)
                onClick()
            }
            SessionSwipeAction.Activate -> {
                haptic.perform(HapticKind.Tick)
                onResume()
            }
            null -> Unit
        }
    }

    Box(
        modifier = modifier
            .then(dragModifier)
            .fillMaxWidth()
            .padding(outerPadding),
    ) {
        SwipeActionRow(
            rowId = s.id,
            openRowId = openSwipeRowId,
            onOpenRowChange = onOpenSwipeRowChange,
            startLabel = label(actions.start),
            endLabel = label(actions.end),
            startIcon = sessionSwipeActionIcon(actions.start),
            endIcon = sessionSwipeActionIcon(actions.end),
            onStartAction = { runAction(actions.start) },
            onEndAction = { runAction(actions.end) },
            enabled = !isDragging,
            startColor = when (actions.start) {
                SessionSwipeAction.Mute, SessionSwipeAction.Unmute ->
                    Color(c.warning).copy(alpha = 0.35f)
                else -> cs.primaryContainer
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape),
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
                        .testTag(TestIds.sessionRow(s.id))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (s.effectiveUserStatus() == "draft") {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "draft",
                            tint = cs.primary.copy(alpha = 0.75f),
                            modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
                        )
                    } else {
                        SessionStatusRail(
                            git = null,
                            working = working,
                            bgOpen = bgOpen,
                            // Unread lives in the leading rail (green dot when idle); the spinner
                            // wins while the agent is working so we don't double-signal.
                            unread = hasUnread,
                            unreadTestTag = "session_unread_${s.id}",
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
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
                            if (host != null) {
                                Spacer(Modifier.width(Space.sm))
                                HostBadge(host)
                            }
                            val timeStr = archivedRelTime(preview?.ts)
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
                        val userSt = s.effectiveUserStatus()
                        val status = s.status
                        when {
                            userSt == "draft" -> {
                                Spacer(Modifier.height(2.dp))
                                Text("draft", color = cs.primary, fontFamily = MonoFontFamily, fontSize = 10.sp)
                            }
                            status != null && status != "active" && status != "archived" -> {
                                val badgeColor = if (status == "suspended") Color(c.warning)
                                else cs.onSurfaceVariant.copy(alpha = 0.6f)
                                Spacer(Modifier.height(2.dp))
                                Text(status, color = badgeColor, fontFamily = MonoFontFamily, fontSize = 10.sp)
                            }
                            else -> Spacer(Modifier.height(Space.xs))
                        }
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
}
