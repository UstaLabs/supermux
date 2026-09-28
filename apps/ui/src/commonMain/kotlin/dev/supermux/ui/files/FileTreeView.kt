// The Files tree (spec 2026-09-27 §5): a lazy, live view over the host FileSystemService.
//
// Ownership: the PANE owns every folder subscription (root + each expanded folder under the root),
// diffed as the expanded set changes and all closed when the pane leaves composition. Rows own
// nothing — they are plain values from [flattenTree], so scrolling never subscribes/unsubscribes.
// Each subscribed folder's StateFlow is mirrored into one snapshot map by a per-path collector,
// and the row list is a `derivedStateOf` over (rootPath, expanded, that map).
package dev.supermux.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.fs.DirState
import dev.supermux.fs.DirSubscription
import dev.supermux.fs.FileSystemService
import dev.supermux.fs.snapshotOrPrevious
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val IndentStep: Dp = 14.dp
private val IndentBase: Dp = 8.dp
private const val SpinnerDelayMs = 150L
private const val IgnoredAlpha = 0.45f
private val RowMinDense: Dp = 24.dp
private val RowMinTouch: Dp = 44.dp
private val ChevronSize: Dp = 14.dp
private val RowGap: Dp = 6.dp

/**
 * One line of the lazy list: a folder/file row, or the error message under a failed folder.
 * `@Immutable` + data equality: [TreeRow] holds an `FsEntry` from a non-Compose module, so Compose
 * would treat it as unstable and recompose every visible row on each snapshot. Comparing lines by
 * value lets an unchanged row skip when a folder elsewhere updates.
 */
@Immutable
private data class TreeLine(val row: TreeRow, val isError: Boolean) {
    val key: String get() = if (isError) "${row.path}#error" else row.path
}

/**
 * The pane's live subscriptions, keyed by absolute path. [sync] diffs against a wanted set;
 * [retry] swaps a folder's handle for a fresh one (the service re-sends `fs_sub` for a folder the
 * broker dropped after an error); [closeAll] runs when the pane leaves composition.
 */
private class TreeSubscriptions(private val fs: FileSystemService) {
    private val subs = HashMap<String, DirSubscription>()

    fun sync(wanted: Set<String>) {
        val drop = subs.keys - wanted
        for (p in drop) subs.remove(p)?.close()
        for (p in wanted) if (p !in subs) subs[p] = fs.subscribe(p)
    }

    fun retry(path: String) {
        val old = subs[path] ?: return
        subs[path] = fs.subscribe(path) // subscribe BEFORE closing so the ref never hits zero
        old.close()
    }

    fun closeAll() {
        for (s in subs.values) s.close()
        subs.clear()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTreeView(
    fileSystem: FileSystemService,
    view: TreeViewState,
    onOpenFile: (absolutePath: String) -> Unit,
    modifier: Modifier = Modifier,
    activePath: String? = null,
    revealActive: Boolean = true,
    onRowContextMenu: ((TreeRow) -> Unit)? = null,
    /** Phone / touch layout: rows are at least [RowMinTouch] tall (thumb targets). */
    compact: Boolean = false,
) {
    // ── subscriptions (owned here, never by rows) ────────────────────────────────────────────
    val wanted by remember(view) {
        derivedStateOf {
            val root = view.rootPath
            val expanded = view.expanded
            // Only folders actually on screen: every ancestor between the root and it is open too.
            buildSet {
                add(root)
                expanded.filterTo(this) { p ->
                    isWithin(root, p) && ancestorsWithin(root, p).all { it == root || it in expanded }
                }
            }
        }
    }
    val subs = remember(fileSystem) { TreeSubscriptions(fileSystem) }
    DisposableEffect(subs) { onDispose { subs.closeAll() } }
    DisposableEffect(subs, wanted) {
        subs.sync(wanted)
        onDispose { } // diffed by the next sync; the effect above closes everything on leave
    }

    // Mirror each subscribed folder's StateFlow into one snapshot map the row derivation reads.
    val states: SnapshotStateMap<String, DirState> = remember(fileSystem) { mutableStateMapOf() }
    val currentView by rememberUpdatedState(view)
    for (path in wanted) {
        key(fileSystem, path) {
            LaunchedEffect(fileSystem, path) {
                fileSystem.dir(path).collect { st ->
                    states[path] = st
                    if (st == DirState.Gone && path != currentView.rootPath) currentView.prune(path)
                }
            }
        }
    }
    // Forget states of folders no longer wanted, so a re-expand never flashes a stale error.
    LaunchedEffect(wanted) { states.keys.retainAll(wanted) }

    val lines by remember(view, states) {
        derivedStateOf {
            val rows = flattenTree(view.rootPath, view.expanded) { states[it] ?: DirState.Unloaded }
            val out = ArrayList<TreeLine>(rows.size + 4)
            for (r in rows) {
                out += TreeLine(r, isError = false)
                if (r.status == RowStatus.ERROR) out += TreeLine(r, isError = true)
            }
            out
        }
    }

    // Read by the pane itself, so a folder update that keeps the tree non-empty (or the root
    // healthy) doesn't recompose it — only the lazy list reads [lines].
    val isEmpty by remember(view, states) { derivedStateOf { lines.isEmpty() } }
    val rootRefreshFailed by remember(view, states) {
        derivedStateOf { (states[view.rootPath] as? DirState.Failed)?.takeIf { it.previous != null } }
    }

    // ── reveal the active file ────────────────────────────────────────────────────────────────
    LaunchedEffect(activePath, revealActive, view) {
        if (revealActive && activePath != null && isWithin(view.rootPath, activePath)) view.reveal(activePath)
    }
    // Scroll once per activePath, as soon as its row exists (its folders may still be loading).
    // Waiting inside one effect (not keying on the row count) so later listings can't cancel the
    // scroll mid-animation.
    LaunchedEffect(activePath, revealActive, view) {
        val target = activePath ?: return@LaunchedEffect
        if (!revealActive || !isWithin(view.rootPath, target)) return@LaunchedEffect
        val index = snapshotFlow { lines.indexOfFirst { !it.isError && it.row.path == target } }.first { it >= 0 }
        // The list must have LAID OUT the new rows first, or the scroll clamps to the old count.
        snapshotFlow { view.list.layoutInfo.totalItemsCount > index }.first { it }
        val visible = view.list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index && it.offset >= 0 }) view.list.animateScrollToItem(index)
    }

    // ── clicks ────────────────────────────────────────────────────────────────────────────────
    val haptics = rememberHaptics()
    val openFile by rememberUpdatedState(onOpenFile)
    val contextMenu by rememberUpdatedState(onRowContextMenu)
    val onClick: (TreeRow) -> Unit = remember(view, subs, haptics) {
        { row ->
            haptics.perform(HapticKind.Tick)
            view.selected = row.path
            when (row.status) {
                RowStatus.FILE -> openFile(row.path)
                RowStatus.ERROR -> subs.retry(row.path) // stays expanded; the service re-sends fs_sub
                else -> view.toggle(row.path) // LOOP rows are expanded, so this collapses them
            }
        }
    }
    // The chevron always toggles — the only way to fold a failed folder, whose row click retries.
    val onToggle: (TreeRow) -> Unit = remember(view, haptics) {
        { row -> haptics.perform(HapticKind.Tick); view.toggle(row.path) }
    }
    val onLongClick: (TreeRow) -> Unit = remember { { row -> contextMenu?.invoke(row) } }
    val hasMenu = onRowContextMenu != null

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        rootRefreshFailed?.let { failed ->
            TreeErrorStrip(failed.message.ifBlank { failed.code }, compact, onRetry = { subs.retry(view.rootPath) })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("editor_tree"), state = view.list) {
            items(lines, key = { it.key }, contentType = { if (it.isError) 1 else 0 }) { line ->
                val row = line.row
                if (line.isError) {
                    TreeErrorLine(row.depth, row.error.orEmpty())
                } else {
                    TreeRowLine(
                        line = line,
                        selected = view.selected == row.path,
                        active = activePath == row.path,
                        compact = compact,
                        onClick = onClick,
                        onToggle = onToggle,
                        onLongClick = if (hasMenu) onLongClick else null,
                    )
                }
            }
        }
        }
        if (isEmpty) {
            // Read only here: a root refresh with rows on screen must not recompose the pane.
            TreePlaceholder(states[view.rootPath] ?: DirState.Unloaded, onRetry = { subs.retry(view.rootPath) })
        }
    }
}

@Composable
private fun TreePlaceholder(root: DirState, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            root is DirState.Failed && root.previous == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    root.message.ifBlank { root.code },
                    color = cs.error,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry, modifier = Modifier.testTag("editor_tree_retry")) { Text("Retry") }
            }
            root == DirState.Gone -> Text("This folder no longer exists", color = cs.onSurfaceVariant, fontSize = 12.sp)
            root.snapshotOrPrevious != null -> Text("Empty folder", color = cs.onSurfaceVariant, fontSize = 12.sp)
            else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = cs.onSurfaceVariant)
        }
    }
}

/** One line above the list: the root's refresh failed but its previous rows are still shown. */
@Composable
private fun TreeErrorStrip(message: String, compact: Boolean, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("editor_tree_error")
            .background(cs.errorContainer)
            .heightIn(min = if (compact) RowMinTouch else RowMinDense)
            .padding(start = IndentBase, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = cs.onErrorContainer,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag("editor_tree_retry")) { Text("Retry", fontSize = 12.sp) }
    }
}

/**
 * One row. [line] compares by value and the click lambdas are remembered by the caller, so on a
 * new snapshot only rows whose values (or selected/active flags) changed recompose.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowLine(
    line: TreeLine,
    selected: Boolean,
    active: Boolean,
    compact: Boolean,
    onClick: (TreeRow) -> Unit,
    onToggle: (TreeRow) -> Unit,
    onLongClick: ((TreeRow) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val row = line.row
    val entry = row.entry
    val isDir = row.status != RowStatus.FILE
    val expanded = row.status != RowStatus.FILE && row.status != RowStatus.CLOSED
    val alpha = if (entry.ignored) IgnoredAlpha else 1f
    val guide = cs.outlineVariant.copy(alpha = 0.6f)
    val depth = row.depth
    val clickLabel = when (row.status) {
        RowStatus.FILE -> "Open"
        RowStatus.ERROR -> "Retry"
        RowStatus.CLOSED -> "Expand"
        else -> "Collapse"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("tree_row:${entry.name}")
            .then(if (selected) Modifier.background(cs.secondaryContainer) else Modifier)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = clickLabel,
                onLongClickLabel = onLongClick?.let { "More actions" },
                onClick = { onClick(row) },
                onLongClick = onLongClick?.let { { it(row) } },
            )
            .semantics { if (isDir) stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .pointerHoverIcon(PointerIcon.Hand)
            .drawBehind { drawIndentGuides(depth, guide) }
            .heightIn(min = if (compact) RowMinTouch else RowMinDense)
            .height(IntrinsicSize.Min)
            .padding(start = IndentStep * depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        // The chevron's hit area spans the leading inset and the full row height, so it's more than
        // a 14dp speck; the icon itself stays where the indent guides expect it.
        Box(
            Modifier
                .fillMaxHeight()
                .width(IndentBase + ChevronSize)
                .then(
                    if (isDir) {
                        Modifier
                            .testTag("tree_chevron:${entry.name}")
                            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") { onToggle(row) }
                    } else {
                        Modifier
                    },
                )
                .padding(start = IndentBase),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (isDir) FolderChevron(line, alpha)
        }
        if (isDir) {
            Icon(
                if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = cs.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(16.dp),
            )
        } else {
            FileBadgeBox(fileBadge(entry.name, isDir = false), alpha)
        }
        Text(
            entry.name,
            color = cs.onSurface.copy(alpha = alpha),
            fontFamily = MonoFontFamily,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 3.dp),
        )
        if (row.status == RowStatus.LOOP) {
            Text(
                "↻",
                color = cs.onSurfaceVariant.copy(alpha = alpha),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Symlink loop" },
            )
        }
        val git = entry.git
        if (git != null) GitMark(git, alpha)
    }
}

/** The git status letter (or, for "changes inside" a folder, a dot) with a spoken description. */
@Composable
private fun GitMark(letter: String, alpha: Float) {
    val color = gitColor(letter).copy(alpha = alpha)
    val spoken = Modifier.clearAndSetSemantics { contentDescription = gitDescription(letter) }
    if (letter == "*") {
        Box(spoken.size(6.dp).background(color, CircleShape))
    } else {
        Text(
            letter,
            color = color,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = spoken,
        )
    }
}

private fun gitDescription(letter: String): String = when (letter) {
    "M" -> "Modified"
    "A" -> "Added"
    "D" -> "Deleted"
    "R" -> "Renamed"
    "?" -> "Untracked"
    "U" -> "Conflict"
    "*" -> "Changes inside"
    else -> "Git: $letter"
}

/** Chevron, or — for a folder still loading — a small spinner, but only after [SpinnerDelayMs]. */
@Composable
private fun FolderChevron(line: TreeLine, alpha: Float) {
    val row = line.row
    val cs = MaterialTheme.colorScheme
    var showSpinner by remember(row.path) { mutableStateOf(false) }
    LaunchedEffect(row.path, row.status) {
        showSpinner = false
        if (row.status == RowStatus.LOADING) { delay(SpinnerDelayMs); showSpinner = true }
    }
    if (row.status == RowStatus.LOADING && showSpinner) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = cs.onSurfaceVariant)
    } else {
        Icon(
            if (row.status == RowStatus.OPEN || row.status == RowStatus.LOADING || row.status == RowStatus.ERROR) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.Filled.ChevronRight
            },
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(ChevronSize),
        )
    }
}

@Composable
private fun TreeErrorLine(depth: Int, message: String) {
    val cs = MaterialTheme.colorScheme
    val guide = cs.outlineVariant.copy(alpha = 0.6f)
    Text(
        message,
        color = cs.error,
        fontFamily = MonoFontFamily,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawIndentGuides(depth + 1, guide) }
            .padding(start = IndentStep * (depth + 1) + IndentBase + 20.dp, end = 8.dp, top = 1.dp, bottom = 3.dp),
    )
}

/** A 1-px vertical line per ancestor level, centred under that level's chevron. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIndentGuides(depth: Int, color: Color) {
    if (depth <= 0) return
    val step = IndentStep.toPx()
    val base = IndentBase.toPx() + 7.dp.toPx()
    for (i in 0 until depth) {
        val x = base + i * step
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
}

@Composable
private fun gitColor(letter: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (letter) {
        "M", "*" -> Color(0xFFE2A03F)
        "A", "R", "?" -> Color(0xFF4EAA25)
        "D" -> cs.error
        "U" -> Color(0xFFCE422B)
        else -> cs.onSurfaceVariant
    }
}

