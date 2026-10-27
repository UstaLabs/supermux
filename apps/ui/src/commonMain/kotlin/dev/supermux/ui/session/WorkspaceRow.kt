// The one workspace row every host renders (cluster F3) — desktop's sidebar row, on touch too.
//
// Hover affordance + right-click menu where there is a pointer; where there is no context menu
// (touch, or Android with a keyboard/mouse) the same row shows a visible ⋮ overflow, so rename /
// new chat / mute / archive (and Restore on an archived row) stay reachable everywhere.
package dev.supermux.ui.session

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.proto.LogEntry
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.host.HostBadge
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
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
    /** The row has unread chats and the list can mark them read. */
    canMarkRead: Boolean = false,
    /** The list supports multi-select (batch actions). */
    selectable: Boolean = false,
): List<String> = buildList {
    add("Rename")
    add(if (mute) "Unmute" else "Mute")
    if (canMarkRead) add("Mark as read")
    if (selectable) add("Select")
    add("Archive")
}

/** Labels offered by an archived workspace row's right-click menu. */
fun archivedWorkspaceRowContextLabels(): List<String> = listOf("Restore")


/**
 * One workspace row, on every host: a status mark, the name and the time, then the branch in mono.
 *
 * No message preview, no multi-agent mark (the chats list underneath already says so), no git
 * comparison — the leading mark is agent state only: a spinner while working, a bullseye when a
 * finished chat is unread, a quiet ring when idle. Colour is reserved for "working".
 *
 * @param model the shared [WorkspaceRowModel] both lists derive with [deriveWorkspaceRow].
 * @param preview last message of the primary session — only its timestamp is shown.
 * @param projectTag replaces the time (the status view names the project a floated row is from).
 * @param dropHover true while a tab dragged out of the layout hovers this row (desktop).
 * @param onRowBounds reports the row's root bounds so a tab drag can hit-test it (desktop).
 */
@Composable
fun WorkspaceRow(
    model: WorkspaceRowModel,
    active: Boolean,
    modifier: Modifier = Modifier,
    preview: LogEntry? = null,
    /** Lifecycle status of the primary session (`suspended`, …) — shown in place of the time. */
    sessionStatus: String? = null,
    projectTag: String? = null,
    dropHover: Boolean = false,
    onRowBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
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
    /** Clear every chat's unread mark; null hides the entry. */
    onMarkRead: (() -> Unit)? = null,
    /** Start a multi-select with this row; null hides the entry. */
    onSelect: (() -> Unit)? = null,
    /** Multi-select is on: a checkbox replaces the status mark and [onClick] toggles the row. */
    selecting: Boolean = false,
    selected: Boolean = false,
    /** The sidebar search's query: its matches in the name are picked out. */
    highlight: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val w = model.workspace
    val working = model.activity == WorkspaceActivity.WORKING
    // Any chat of the workspace (model.unread), not just the primary — a second chat's reply must
    // light the row too. The open row and a working workspace keep the shared rule's exceptions.
    val unread = !active && !working && model.unread
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "workspace-drag-elevation")
    val suspended = sessionStatus != null && sessionStatus != "active"
    val branch = w.branch?.takeIf { it.isNotBlank() }
    val twoLine = branch != null || host != null

    val rowBg = when {
        dropHover -> cs.primary.copy(alpha = 0.18f)
        selecting && selected -> cs.primary.copy(alpha = 0.12f)
        active -> cs.onSurface.copy(alpha = 0.075f)
        hovered -> cs.onSurface.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    val nameColor = when {
        active || unread -> cs.onSurface
        suspended -> cs.onSurfaceVariant.copy(alpha = 0.55f)
        else -> cs.onSurfaceVariant
    }
    val trailing = when {
        suspended -> sessionStatus.orEmpty()
        projectTag != null -> projectTag
        working -> ""
        else -> archivedRelTime(preview?.ts)
    }

    Box(modifier.then(dragModifier).fillMaxWidth()) {
        RowContextMenu(
            items = {
                if (selecting) {
                    emptyList()
                } else {
                    workspaceRowContextLabels(
                        mute = mute,
                        canMarkRead = onMarkRead != null && model.unread,
                        selectable = onSelect != null,
                    ).map { label ->
                        RowContextMenuEntry(label) {
                            when (label) {
                                "Rename" -> onRename()
                                "Mute", "Unmute" -> onToggleMute()
                                "Mark as read" -> onMarkRead?.invoke()
                                "Select" -> onSelect?.invoke()
                                "Archive" -> onKill()
                            }
                        }
                    }
                }
            },
        ) {
            Surface(
                tonalElevation = elevation,
                shadowElevation = elevation,
                color = Color.Transparent,
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .onGloballyPositioned { c -> onRowBounds?.invoke(c.boundsInRoot()) },
            ) {
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // Touch: a thumb-sized row; the pointer row stays dense. The mark column
                            // (14dp in from the row, 22dp from the list) is the headers' folder column.
                            .heightIn(min = if (!LocalPointerAvailable.current) 48.dp else if (twoLine) 44.dp else 30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(rowBg)
                            .hoverable(interaction)
                            .listRowFocus()
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                enabled = !isDragging,
                                onClick = onClick,
                            )
                            .testTag(WorkspaceListTestIds.row(w.id))
                            .padding(start = 14.dp, end = 10.dp, top = if (twoLine) 6.dp else 0.dp, bottom = if (twoLine) 6.dp else 0.dp),
                        verticalAlignment = if (twoLine) Alignment.Top else Alignment.CenterVertically,
                    ) {
                        val markModifier = Modifier.padding(top = if (twoLine) 2.dp else 0.dp)
                        if (selecting) {
                            Icon(
                                imageVector = if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = if (selected) "Selected" else "Not selected",
                                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                                modifier = markModifier.size(14.dp).testTag(WorkspaceListTestIds.selectBox(w.id)),
                            )
                        } else {
                            WorkspaceStatusMark(working = working, unread = unread, modifier = markModifier)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    highlightSearchMatches(model.name, highlight, cs.primary),
                                    color = nameColor,
                                    fontSize = 13.sp,
                                    fontWeight = if (active || unread) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (trailing.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        trailing,
                                        color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontFamily = MonoFontFamily,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                    )
                                }
                                // Where the right-click menu is inert (touch, Android in Pointer
                                // mode), the row's actions need somewhere to live — same entries.
                                if (!LocalContextMenuAvailable.current && !selecting) {
                                    Spacer(Modifier.width(Space.xs))
                                    RowOverflowMenu(
                                        entries = buildList {
                                            add(RowContextMenuEntry("Rename", onRename))
                                            add(RowContextMenuEntry("New chat here", onNewChat))
                                            add(RowContextMenuEntry(if (mute) "Unmute" else "Mute", onToggleMute))
                                            if (onMarkRead != null && model.unread) add(RowContextMenuEntry("Mark as read", onMarkRead))
                                            if (onSelect != null) add(RowContextMenuEntry("Select", onSelect))
                                            add(RowContextMenuEntry("Archive", onKill))
                                        },
                                        newChatTag = WorkspaceListTestIds.ROW_NEW_CHAT,
                                    )
                                }
                            }
                            if (twoLine) {
                                Spacer(Modifier.height(1.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (host != null) {
                                        HostBadge(host)
                                        if (branch != null) Spacer(Modifier.width(Space.sm))
                                    }
                                    if (branch != null) {
                                        Text(
                                            branch,
                                            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontFamily = MonoFontFamily,
                                            fontSize = 10.5.sp,
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
        }
    }
}

/**
 * The sidebar's one status vocabulary — agent state only, never git. A spinner while working; a
 * bullseye (ring + centre dot, text colour) when a finished chat is unread; a quiet ring when idle.
 * One 14dp slot so names line up whatever the state.
 */
@Composable
internal fun WorkspaceStatusMark(working: Boolean, unread: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when {
            working -> CircularProgressIndicator(
                modifier = Modifier
                    .size(11.dp)
                    .testTag("session_rail_working")
                    .semantics { contentDescription = "working" },
                strokeWidth = 1.75.dp,
                color = cs.primary,
                trackColor = cs.primary.copy(alpha = 0.18f),
            )
            unread -> Box(
                Modifier
                    .size(10.dp)
                    .border(1.25.dp, cs.onSurface.copy(alpha = 0.55f), CircleShape)
                    .testTag("session_rail_unread")
                    .semantics { contentDescription = "unread" },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(4.dp).clip(CircleShape).background(cs.onSurface)) }
            else -> Box(
                Modifier
                    .size(7.dp)
                    .border(1.25.dp, cs.onSurfaceVariant.copy(alpha = 0.35f), CircleShape)
                    .testTag("session_rail_neutral"),
            )
        }
    }
}

/** An indented chat under a multi-chat workspace: a hairline guide, the same mark, smaller type. */
@Composable
internal fun WorkspaceChildRow(name: String, working: Boolean, unread: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (LocalPointerAvailable.current) 26.dp else 44.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .listRowFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(cs.onSurfaceVariant.copy(alpha = 0.15f)))
        Spacer(Modifier.width(10.dp))
        WorkspaceStatusMark(working = working, unread = unread)
        Spacer(Modifier.width(7.dp))
        Text(
            name,
            color = if (unread) cs.onSurface else cs.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The status view's section label ("Ready for you", "Working", "Everything else") + count. */
@Composable
internal fun StatusSectionLabel(title: String, count: Int?, accent: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val color = if (accent) cs.primary else if (count != null) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.7f)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.weight(1f),
        )
        if (count != null) Text("$count", color = color, fontSize = 10.5.sp, fontFamily = MonoFontFamily)
    }
}

/**
 * A project's archived fold — shared by the per-project and whole-list folds on every host. A row
 * in the workspace column: the archive glyph in the status-mark column, "Archived", the count.
 */
@Composable
fun ArchivedFoldButton(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val muted = cs.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(7.dp))
            .listRowFocus()
            .clickable(onClick = onClick)
            .heightIn(min = if (LocalPointerAvailable.current) 30.dp else 48.dp)
            .padding(start = 14.dp, end = 10.dp)
            .testTag(WorkspaceListTestIds.ARCHIVED_FOLD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Archive, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            if (expanded) "Hide archived" else "Archived",
            color = muted,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = muted, fontSize = 10.5.sp, fontFamily = MonoFontFamily)
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
    /** The sidebar search's query: its matches in the name are picked out. */
    highlight: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val w = model.workspace
    val rowBg = if (active) cs.onSurface.copy(alpha = 0.075f) else Color.Transparent
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
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(rowBg)
                .listRowFocus()
                .clickable(onClick = onSelect)
                .padding(start = 37.dp, end = 10.dp, top = 7.dp, bottom = 7.dp)
                .testTag(WorkspaceListTestIds.archived(w.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                highlightSearchMatches(model.name, highlight, cs.primary),
                fontSize = 12.5.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
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

/**
 * Sidebar rows don't take focus on click. A focused lazy item is PINNED: the list keeps laying it
 * out after it scrolls away, at a stale off-screen slot, and when it scrolls back `animateItem()`
 * slides it from there into place — the selected row visibly flew over its neighbours.
 */
internal fun Modifier.listRowFocus(): Modifier = focusProperties { canFocus = false }
