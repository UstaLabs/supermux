package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.normalizeLayout

/**
 * Renders a workspace [LayoutNode] as nested resizable splits with tab groups at
 * the leaves.
 *
 * This composable is PURE PRESENTATION over the tree. It never edits the tree
 * itself: a tab click and a splitter drag report the new tree through
 * [onLayoutChange], and a tab close reports only the view id through
 * [onCloseView]. A close ends real work (spec §9.3) — the caller confirms with
 * the user, calls the broker, and the resulting workspace_changed frame is what
 * finally changes what is drawn.
 *
 * [content] draws one view's body. The caller maps the view id to a chat, an
 * editor, a terminal, or a display (see ViewHost.kt).
 *
 * Only the ACTIVE view of each group is composed. That is load-bearing, not an
 * optimisation: JediTerm and KCEF are heavyweight AWT SwingPanel children, and
 * one live KCEF per background tab would exhaust memory.
 */
@Composable
fun LayoutHost(
    layout: LayoutNode,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier = Modifier,
    titleFor: (String) -> String = { it },
    onCloseView: (String) -> Unit = {},
    content: @Composable (viewId: String) -> Unit,
) {
    when (layout) {
        is LayoutNode.Group -> GroupHost(layout, onLayoutChange, modifier, titleFor, onCloseView, content)
        is LayoutNode.Split -> SplitHost(layout, onLayoutChange, modifier, titleFor, onCloseView, content)
    }
}

@Composable
private fun GroupHost(
    group: LayoutNode.Group,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    content: @Composable (String) -> Unit,
) {
    if (group.viewIds.isEmpty()) {
        // A workspace whose last view just closed. Valid, not an error (spec §9.3
        // answer 3: the workspace stays open).
        Box(modifier.fillMaxSize().testTag("layout-empty"), contentAlignment = Alignment.Center) {
            EmptyWorkspaceHint()
        }
        return
    }
    val active = group.activeViewId ?: group.viewIds.first()
    Column(modifier.fillMaxSize()) {
        ViewTabStrip(
            viewIds = group.viewIds,
            activeViewId = active,
            titleFor = titleFor,
            onSelect = { onLayoutChange(group.copy(activeViewId = it)) },
            onClose = onCloseView,
        )
        // Only the ACTIVE view is composed — never mount inactive tabs (JediTerm / KCEF).
        Box(Modifier.weight(1f).fillMaxWidth()) { content(active) }
    }
}

@Composable
private fun SplitHost(
    split: LayoutNode.Split,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    content: @Composable (String) -> Unit,
) {
    // Reuse the existing ResizableSplit drag chrome rather than writing new
    // splitter hit-testing: it already handles the handle and the pointer
    // cursor, and it is the widget the rest of the app drags.
    ResizableSplitN(
        direction = split.direction,
        sizes = split.sizes,
        onSizesChange = { next -> onLayoutChange(split.copy(sizes = next)) },
        modifier = modifier,
    ) { index ->
        LayoutHost(
            layout = split.children[index],
            onLayoutChange = { child ->
                val next = split.copy(children = split.children.toMutableList().also { it[index] = child })
                // normalize keeps the tree valid if a child collapsed to nothing.
                onLayoutChange(normalizeLayout(next) ?: child)
            },
            titleFor = titleFor,
            onCloseView = onCloseView,
            content = content,
        )
    }
}

/** Hint for a group with no views (workspace still open, last view closed). */
@Composable
internal fun EmptyWorkspaceHint() {
    val cs = MaterialTheme.colorScheme
    Text(
        "This workspace has no open views",
        color = cs.onSurfaceVariant,
        fontSize = 13.sp,
    )
}

/**
 * A row of tabs for a group's views. Each tab is a clickable label plus a close
 * affordance tagged `tab-close-<viewId>`. The active tab uses the teal primary
 * accent. No animation — this strip changes many times a day.
 */
@Composable
fun ViewTabStrip(
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(cs.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.xs)
            .testTag("view-tab-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (id in viewIds) {
            val selected = id == activeViewId
            val bg = if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent
            val fg = if (selected) cs.primary else cs.onSurfaceVariant
            Row(
                Modifier
                    .clickable { onSelect(id) }
                    .background(bg, RoundedCornerShape(Radii.sm))
                    .padding(start = Space.sm, end = 3.dp, top = 3.dp, bottom = 3.dp)
                    .testTag("view-tab-$id"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = titleFor(id),
                    color = fg,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
                Box(
                    Modifier
                        .size(16.dp)
                        .clickable { onClose(id) }
                        .alpha(if (selected) 0.85f else 0.5f)
                        .testTag("tab-close-$id"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close view",
                        tint = fg,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * N-pane generalisation of [ResizableSplit]. [n] children means [n]-1 splitters,
 * tagged `splitter-0` … `splitter-(n-2)`. A drag on splitter [i] moves weight
 * between children [i] and [i]+1 only; the total stays 1.
 *
 * Matches [ResizableSplit]'s 24dp handle and col-/row-resize cursor behaviour.
 */
@Composable
fun ResizableSplitN(
    direction: String,
    sizes: List<Double>,
    onSizesChange: (List<Double>) -> Unit,
    modifier: Modifier = Modifier,
    child: @Composable (index: Int) -> Unit,
) {
    val n = sizes.size.coerceAtMost(
        // Prefer children count if the tree is inconsistent; never index OOB.
        sizes.size,
    )
    if (n <= 0) return
    if (n == 1) {
        Box(modifier.fillMaxSize()) { child(0) }
        return
    }

    val horizontal = direction == "row"
    var totalPx by remember { mutableStateOf(0) }
    val currentSizes by rememberUpdatedState(sizes)
    val handle = 24.dp

    @Composable
    fun splitter(index: Int) {
        val resizeIcon = if (horizontal) ColResizeIcon else RowResizeIcon
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        var dragging by remember { mutableStateOf(false) }
        val active = hovered || dragging
        val cs = MaterialTheme.colorScheme
        val lineColor = if (active) cs.primary.copy(alpha = 0.90f) else cs.outlineVariant
        val lineThickness = if (active) 2.dp else 1.dp
        Box(
            (if (horizontal) Modifier.fillMaxHeight().width(handle) else Modifier.fillMaxWidth().height(handle))
                .hoverable(interaction)
                .pointerHoverIcon(resizeIcon)
                .pointerInput(totalPx, index) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { _, drag ->
                        if (totalPx <= 0) return@detectDragGestures
                        val delta = (if (horizontal) drag.x else drag.y) / totalPx
                        val cur = currentSizes
                        if (index < 0 || index + 1 >= cur.size) return@detectDragGestures
                        val a = cur[index]
                        val b = cur[index + 1]
                        val pair = a + b
                        // Keep each side at least ~5% of the pair so a pane never collapses.
                        val minFrac = 0.05
                        val nextA = (a + delta).coerceIn(minFrac * pair, (1.0 - minFrac) * pair)
                        val nextB = pair - nextA
                        onSizesChange(
                            cur.toMutableList().also {
                                it[index] = nextA
                                it[index + 1] = nextB
                            },
                        )
                    }
                }
                .testTag("splitter-$index"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                (if (horizontal) {
                    Modifier.fillMaxHeight().width(lineThickness)
                } else {
                    Modifier.fillMaxWidth().height(lineThickness)
                }).background(lineColor),
            )
        }
    }

    if (horizontal) {
        Row(modifier.fillMaxSize().onSizeChanged { totalPx = it.width }) {
            for (i in 0 until n) {
                val weight = sizes.getOrElse(i) { 1.0 / n }.toFloat().coerceAtLeast(0.001f)
                Box(Modifier.weight(weight).fillMaxHeight()) { child(i) }
                if (i < n - 1) splitter(i)
            }
        }
    } else {
        Column(modifier.fillMaxSize().onSizeChanged { totalPx = it.height }) {
            for (i in 0 until n) {
                val weight = sizes.getOrElse(i) { 1.0 / n }.toFloat().coerceAtLeast(0.001f)
                Box(Modifier.weight(weight).fillMaxWidth()) { child(i) }
                if (i < n - 1) splitter(i)
            }
        }
    }
}
