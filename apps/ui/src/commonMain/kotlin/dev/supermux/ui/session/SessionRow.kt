// The session row and the project-group header both lists render (cluster F4).
//
// Desktop's lean sidebar session row on every host — status rail, name + time, lifecycle badge,
// message/workdir preview; actions in a right-click menu, or the visible `⋮` overflow wherever
// there is no context menu (touch included).
package dev.supermux.ui.session

import dev.supermux.ui.adaptive.LocalPointerAvailable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import dev.supermux.ui.chat.councilHeadAndBody
import dev.supermux.ui.chat.inboxSenderName
import dev.supermux.ui.chat.noticePlainText
import dev.supermux.ui.host.HostBadge
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.theme.softElevation
import dev.supermux.ui.sessionPreviewPlainText

/** What [sessionRowPreview] reads of a message at most. */
private const val PREVIEW_INPUT_CAP = 300

/**
 * A session row's preview line: the last message as plain text. A message from another session (or
 * supermux) names its sender ("personal-4: …"), a council report its council, round and member,
 * without the ids agents are shown (decision #19). Null when there is nothing to show.
 */
internal fun sessionRowPreview(entry: LogEntry?): String? {
    // An 80-character line never needs more (sessionPreviewPlainText caps the same way).
    val raw = entry?.text?.take(PREVIEW_INPUT_CAP) ?: return null
    if (entry.channel != "inbox") return sessionPreviewPlainText(raw).ifBlank { null }?.take(80)
    val (sender, said) = if (entry.council_id != null) {
        councilHeadAndBody(raw)
    } else {
        (inboxSenderName(entry) ?: "Another session") to raw
    }
    val plain = sessionPreviewPlainText(noticePlainText(said))
    return (if (plain.isBlank()) sender else "$sender: $plain").take(80)
}

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
 * Project-group header, on every host: a file-tree line — disclosure arrow, folder (or the
 * project's image), the name, then "N new" while any chat is unread or else the workspace count.
 * [trailing] (a persistent project's ⋮ menu) shows on hover; touch always shows it.
 *
 * Geometry is shared with [WorkspaceRow]: the folder sits in the same 14dp column as the rows'
 * status marks (22dp in) and the name starts where the row names start (45dp in).
 */
@Composable
fun PathGroupHeader(
    label: String,
    unreadCount: Int,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
    fullLabel: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Live workspaces in the group, shown on the right when nothing is unread; 0 hides it. */
    workspaceCount: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val leaf = groupHeaderLeaf(label, fullLabel)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pointer = LocalPointerAvailable.current
    val muted = cs.onSurfaceVariant.copy(alpha = 0.7f)
    val clickable = if (onToggle != null) {
        Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
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
            .hoverable(interaction)
            .listRowFocus()
            .then(clickable)
            .heightIn(min = if (pointer) 30.dp else 48.dp)
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(2.dp))
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (leading != null) {
                leading()
            } else {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            leaf,
            color = cs.onSurface.copy(alpha = 0.85f),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            // Always composed, only faded in on hover: removing it would also dispose the menu it
            // opens the moment the pointer moves off the header into that menu.
            Box(Modifier.alpha(if (hovered || !pointer) 1f else 0f)) { trailing() }
            Spacer(Modifier.width(6.dp))
        }
        if (unreadCount > 0) {
            // Hue is reserved for "working": a quiet count in the text colour.
            Text(
                "$unreadCount new",
                color = cs.onSurface.copy(alpha = 0.75f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .testTag("group_unread_count")
                    .semantics { contentDescription = "$unreadCount unread" },
            )
        } else if (workspaceCount > 0) {
            Text("$workspaceCount", color = muted, fontSize = 10.5.sp, fontFamily = MonoFontFamily)
        }
    }
}

private fun groupHeaderLeaf(label: String, fullLabel: Boolean): String =
    if (fullLabel) label else label.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: label

/** [PathGroupHeader]'s default leading tile: the leaf's first letter on a label-hashed colour. */
@Composable
internal fun PathGroupTile(label: String, fullLabel: Boolean = false) {
    val leaf = groupHeaderLeaf(label, fullLabel)
    val letter = leaf.firstOrNull()?.uppercaseChar()?.toString() ?: "·"
    // Stable-ish pastel from label hash so adjacent groups don't all share the same tile.
    val hue = ((leaf.hashCode() ushr 1) % 360).toFloat()
    GroupLetterTile(letter, Color.hsl(hue, 0.45f, 0.42f), 18.dp)
}

/** The colour-hashed letter tile a group header shows when it has no image. */
@Composable
internal fun GroupLetterTile(letter: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        // The default style pads the line above and below the glyph (font ascent/descent and
        // platform font padding), so a letter "centred" in the box sits visibly low. Trim the line
        // to the font size and centre the glyph inside it.
        Text(
            letter,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = LocalTextStyle.current.copy(
                lineHeight = 10.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
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
    /** Running subagents — the "2 agents" tag. */
    runningAgents: Int = 0,
    host: HostView? = null,
    projectTag: String? = null,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    isDragging: Boolean = false,
    // ── Unused since the rows unified (kept so callers compile) ──
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
    PointerSessionRow(
        s = s,
        active = active,
        preview = preview,
        lastReadAt = lastReadAt,
        working = working,
        bgOpen = bgOpen,
        runningAgents = runningAgents,
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

/** Desktop's sidebar session row, verbatim. */
@Composable
private fun PointerSessionRow(
    s: SessionInfo,
    active: Boolean,
    preview: LogEntry?,
    lastReadAt: String?,
    working: Boolean,
    bgOpen: Int,
    runningAgents: Int,
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
                        if (runningAgents > 0) {
                            Spacer(Modifier.width(Space.sm))
                            RunningAgentsTag(runningAgents)
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
                    val previewText = sessionRowPreview(preview)
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
