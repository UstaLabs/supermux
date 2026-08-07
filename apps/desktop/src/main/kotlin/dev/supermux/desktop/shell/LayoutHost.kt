package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.moveViewToGroup
import dev.supermux.workspace.normalizeLayout
import dev.supermux.workspace.reorderWithinGroup
import dev.supermux.workspace.splitGroup
import java.util.UUID

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
 *
 * While a tab drag is active, each group's body is **swapped** for a Compose
 * drop-zone surface (see [DropZoneSurface]) so edge highlights are visible over
 * panes that would otherwise hold a SwingPanel. Overlaying Compose on top of
 * JediTerm/KCEF paints nothing.
 */
@Composable
fun LayoutHost(
    layout: LayoutNode,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier = Modifier,
    titleFor: (String) -> String = { it },
    onCloseView: (String) -> Unit = {},
    /**
     * "+" on each group's tab strip. Receives the GROUP the user clicked in, so a
     * new view lands as a tab in that group rather than somewhere arbitrary.
     * Null hides the button.
     */
    onAddView: ((groupId: String, kind: NewViewKind) -> Unit)? = null,
    /**
     * Shared drag state so the sidebar can register workspace-row drop targets.
     * When null, [LayoutHost] owns a private instance (standalone / tests).
     */
    dragState: TabDragState? = null,
    /**
     * Cross-workspace drop (sidebar row). Spec §9.4 — session workdir is unchanged.
     * Null ignores workspace drops.
     */
    onMoveToWorkspace: ((viewId: String, toWorkspaceId: String) -> Unit)? = null,
    content: @Composable (viewId: String) -> Unit,
) {
    val ownedDrag = remember { TabDragState() }
    val drag = dragState ?: ownedDrag
    val layoutState = rememberUpdatedState(layout)
    val onLayoutChangeState = rememberUpdatedState(onLayoutChange)
    val onMoveToWorkspaceState = rememberUpdatedState(onMoveToWorkspace)

    fun applyDrop(target: TabDropTarget) {
        when (target) {
            is TabDropTarget.MoveToWorkspace -> {
                onMoveToWorkspaceState.value?.invoke(target.viewId, target.toWorkspaceId)
                return
            }
            else -> Unit
        }
        val tree = layoutState.value
        val next: LayoutNode? = when (target) {
            is TabDropTarget.Reorder ->
                reorderWithinGroup(tree, target.groupId, target.viewId, target.index)
            is TabDropTarget.MoveToGroup ->
                moveViewToGroup(tree, target.viewId, target.toGroupId, target.index)
            is TabDropTarget.Split -> {
                // splitGroup only acts when the view already lives in the target
                // group and the group has ≥2 views. Cross-group edge drops first
                // move the view into the target, then split — but only if the
                // target already has another view to stay put; a single-view
                // group cannot be split by its only (incoming) tab.
                val owner = groupIdOf(tree, target.viewId)
                val withView = if (owner == target.groupId) {
                    tree
                } else {
                    moveViewToGroup(tree, target.viewId, target.groupId, Int.MAX_VALUE) ?: tree
                }
                val split = splitGroup(
                    withView,
                    target.groupId,
                    target.viewId,
                    target.direction,
                    newGroupId = UUID.randomUUID().toString(),
                )
                if (target.newFirst) reverseNewSplit(split, target.groupId) else split
            }
            is TabDropTarget.MoveToWorkspace -> null // handled above
        }
        if (next != null && next != tree) onLayoutChangeState.value(next)
    }

    LayoutHostNode(
        layout = layout,
        onLayoutChange = onLayoutChange,
        dragState = drag,
        onDrop = { applyDrop(it) },
        modifier = modifier,
        titleFor = titleFor,
        onCloseView = onCloseView,
        onAddView = onAddView,
        content = content,
    )
}

/**
 * After [splitGroup] the new group is always the second child. For left/top
 * edges we want it first — reverse that split's children where the original
 * group id is still the first child's id.
 */
private fun reverseNewSplit(node: LayoutNode, originalGroupId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> node
    is LayoutNode.Split -> {
        val c0 = node.children.getOrNull(0)
        val c1 = node.children.getOrNull(1)
        if (
            node.children.size == 2 &&
            c0 is LayoutNode.Group && c0.id == originalGroupId &&
            c1 is LayoutNode.Group
        ) {
            node.copy(
                children = listOf(c1, c0),
                sizes = node.sizes.reversed(),
            )
        } else {
            node.copy(children = node.children.map { reverseNewSplit(it, originalGroupId) })
        }
    }
}

@Composable
private fun LayoutHostNode(
    layout: LayoutNode,
    onLayoutChange: (LayoutNode) -> Unit,
    dragState: TabDragState,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    onAddView: ((String, NewViewKind) -> Unit)?,
    content: @Composable (String) -> Unit,
) {
    when (layout) {
        is LayoutNode.Group -> GroupHost(
            layout, onLayoutChange, dragState, onDrop, modifier, titleFor, onCloseView, onAddView, content,
        )
        is LayoutNode.Split -> SplitHost(
            layout, onLayoutChange, dragState, onDrop, modifier, titleFor, onCloseView, onAddView, content,
        )
    }
}

@Composable
private fun GroupHost(
    group: LayoutNode.Group,
    onLayoutChange: (LayoutNode) -> Unit,
    dragState: TabDragState,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    onAddView: ((String, NewViewKind) -> Unit)?,
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
            groupId = group.id,
            viewIds = group.viewIds,
            activeViewId = active,
            titleFor = titleFor,
            onSelect = { onLayoutChange(group.copy(activeViewId = it)) },
            onClose = onCloseView,
            dragState = dragState,
            onDrop = onDrop,
            onAddView = onAddView?.let { add -> { kind -> add(group.id, kind) } },
        )
        // While a drag is active, SWAP the heavyweight body for a Compose
        // drop-zone surface. Overlaying zones above SwingPanel is invisible.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    dragState.registerPane(group.id, coords.boundsInRoot())
                },
        ) {
            if (dragState.isDragging) {
                DropZoneSurface(activeZone = dragState.zoneOver(group.id))
            } else {
                content(active)
            }
        }
    }
}

@Composable
private fun SplitHost(
    split: LayoutNode.Split,
    onLayoutChange: (LayoutNode) -> Unit,
    dragState: TabDragState,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    onAddView: ((String, NewViewKind) -> Unit)?,
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
        LayoutHostNode(
            layout = split.children[index],
            onLayoutChange = { child ->
                val next = split.copy(children = split.children.toMutableList().also { it[index] = child })
                // normalize keeps the tree valid if a child collapsed to nothing.
                onLayoutChange(normalizeLayout(next) ?: child)
            },
            dragState = dragState,
            onDrop = onDrop,
            titleFor = titleFor,
            onCloseView = onCloseView,
            onAddView = onAddView,
            content = content,
            modifier = Modifier,
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
 *
 * Tabs support press-and-drag reorder (and cross-strip / edge-split via the
 * shared [dragState]). A plain click still selects — movement below
 * [TAB_DRAG_THRESHOLD_PX] is treated as a click, not a drag.
 */
/** The view kinds the "+" offers. Order is the order they appear in the popover. */
enum class NewViewKind(val wire: String, val label: String) {
    CHAT("chat", "Chat"),
    TERMINAL("terminal", "Terminal"),
    EDITOR("editor", "Editor"),
    DISPLAY("display", "Display"),
}

@Composable
fun ViewTabStrip(
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * "+" at the end of the strip. Opens a popover of view kinds; picking one
     * adds it to THIS group as a new tab. Null hides the button entirely (tests
     * and any caller that cannot create views).
     */
    onAddView: ((NewViewKind) -> Unit)? = null,
) {
    // Back-compat for call sites that do not participate in drag (previews, older tests).
    ViewTabStrip(
        groupId = "",
        viewIds = viewIds,
        activeViewId = activeViewId,
        titleFor = titleFor,
        onSelect = onSelect,
        onClose = onClose,
        dragState = null,
        onDrop = {},
        modifier = modifier,
        onAddView = onAddView,
    )
}

@Composable
internal fun ViewTabStrip(
    groupId: String,
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    dragState: TabDragState?,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier = Modifier,
    onAddView: ((NewViewKind) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(cs.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.xs)
            .onGloballyPositioned { coords ->
                if (groupId.isNotEmpty()) {
                    dragState?.registerStrip(groupId, coords.boundsInRoot())
                }
            }
            .testTag("view-tab-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (id in viewIds) {
            val selected = id == activeViewId
            val bg = if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent
            val fg = if (selected) cs.primary else cs.onSurfaceVariant
            val dimmed = dragState?.isDragging == true && dragState.draggingViewId == id
            Row(
                Modifier
                    // Min width keeps the geometric centre on the label so
                    // performClick("view-tab-x") selects instead of hitting ×.
                    .defaultMinSize(minWidth = 56.dp)
                    .onGloballyPositioned { coords ->
                        if (groupId.isNotEmpty()) {
                            dragState?.registerTab(groupId, id, coords.boundsInRoot())
                        }
                    }
                    .then(
                        if (dragState != null && groupId.isNotEmpty()) {
                            Modifier.tabDragGestures(
                                viewId = id,
                                groupId = groupId,
                                dragState = dragState,
                                onSelect = onSelect,
                                onDrop = onDrop,
                            )
                        } else {
                            Modifier.clickable { onSelect(id) }
                        },
                    )
                    .background(bg, RoundedCornerShape(Radii.sm))
                    .padding(start = Space.sm, end = 3.dp, top = 3.dp, bottom = 3.dp)
                    .alpha(if (dimmed) 0.35f else 1f)
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

        // "+" lives HERE, at the end of the tabs — not on the sidebar row. Adding
        // a view is a thing you do to the group you are looking at, so the
        // affordance belongs where the tabs are.
        if (onAddView != null) {
            var pickerOpen by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier
                        .size(20.dp)
                        .clickable { pickerOpen = true }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("tab-add-view"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add a view",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false },
                    modifier = Modifier.testTag("tab-add-view-menu"),
                ) {
                    for (kind in NewViewKind.entries) {
                        DropdownMenuItem(
                            text = { Text(kind.label, fontSize = 12.sp) },
                            onClick = { pickerOpen = false; onAddView(kind) },
                            modifier = Modifier.testTag("tab-add-view-${kind.wire}"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Press-to-drag on a tab, matching [dev.supermux.desktop.session.SessionDragReorderState]:
 * mouse-friendly grab after a small move, click when movement stays under the
 * threshold. Drop resolution is delegated to [TabDragState] so cross-strip and
 * edge targets share one pointer.
 */
private fun Modifier.tabDragGestures(
    viewId: String,
    groupId: String,
    dragState: TabDragState,
    onSelect: (String) -> Unit,
    onDrop: (TabDropTarget) -> Unit,
): Modifier = this
    // performClick() uses the semantics onClick action, not a real pointer stream.
    .semantics {
        onClick(label = "Select tab") {
            onSelect(viewId)
            true
        }
    }
    .pointerInput(viewId, groupId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downLocal = down.position
            val boundsAtDown = dragState.tabBoundsFor(groupId, viewId)
            val startRoot = if (boundsAtDown != null) {
                Offset(boundsAtDown.left + downLocal.x, boundsAtDown.top + downLocal.y)
            } else {
                Offset(downLocal.x, downLocal.y)
            }
            dragState.begin(viewId, groupId, startRoot)
            var passed = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    if (!passed) {
                        dragState.cancel()
                        onSelect(viewId)
                    } else {
                        val target = dragState.finish()
                        if (target != null) onDrop(target)
                    }
                    break
                }
                if (change.pressed) {
                    val pointerRoot = startRoot + (change.position - downLocal)
                    dragState.updatePointer(pointerRoot, startRoot, TAB_DRAG_THRESHOLD_PX)
                    if (dragState.pastThreshold) {
                        passed = true
                        change.consume()
                    }
                }
            }
            if (dragState.draggingViewId == viewId) {
                dragState.cancel()
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
